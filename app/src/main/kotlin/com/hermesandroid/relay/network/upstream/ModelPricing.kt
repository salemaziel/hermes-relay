package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.round

/**
 * Advertised model rates, normalised to USD per 1M tokens.
 *
 * Two upstream shapes feed this:
 *
 * - **Nous Portal** (`inference-api.nousresearch.com/v1/models`) and
 *   **OpenRouter** (`openrouter.ai/api/v1/models`) publish OpenRouter-shaped
 *   rows whose `pricing` object is USD *per token* as decimal strings. Nous
 *   also carries `pricing.original` with undiscounted rates; the effective
 *   `prompt`/`completion` is what the account is billed, so that is what we read.
 * - **OpenCode Go** serves ids only from `/zen/go/v1/models`, so rates come from
 *   `models.dev/api.json`, where `cost` is already USD *per 1M tokens*.
 *
 * The gateway's `model.options` RPC does not carry rates today, so
 * [ModelPricingRepository] fetches these public catalogs client-side. Parsing is
 * deliberately tolerant: an unusable rate becomes null and the picker renders no
 * price, never a misleading `$0`.
 */
data class ModelPricing(
    val input: Double?,
    val output: Double?,
    val source: String,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val tiered: Boolean = false,
)

const val PRICING_PROVIDER_NOUS = "nous"
const val PRICING_PROVIDER_OPENROUTER = "openrouter"
const val PRICING_PROVIDER_OPENCODE_GO = "opencode-go"
const val PRICING_PROVIDER_OPENCODE_ZEN = "opencode-zen"

const val NOUS_PRICING_URL = "https://inference-api.nousresearch.com/v1/models"
const val OPENROUTER_PRICING_URL = "https://openrouter.ai/api/v1/models"
const val MODELS_DEV_PRICING_URL = "https://models.dev/api.json"

private const val TOKENS_PER_MILLION = 1_000_000.0

/** Provider slugs upstream spells a few ways; folded onto one canonical id. */
internal fun canonicalPricingProvider(slug: String?): String? =
    when (slug?.trim()?.lowercase()) {
        "nous", "nousresearch", "nous-research", "nous-portal" -> PRICING_PROVIDER_NOUS
        "openrouter", "open-router" -> PRICING_PROVIDER_OPENROUTER
        "opencode-go", "opencode_go", "opencodego" -> PRICING_PROVIDER_OPENCODE_GO
        "opencode-zen", "opencode_zen", "opencodezen" -> PRICING_PROVIDER_OPENCODE_ZEN
        else -> null
    }

/** A finite, non-negative rate, or null when the value is unusable. */
internal fun coercePricingRate(raw: String?): Double? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0.0) return null
    return value
}

private fun JsonObject.rate(key: String): Double? =
    coercePricingRate((this[key] as? JsonPrimitive)?.contentOrNull)

/**
 * Scaling a tiny per-token rate by 1e6 leaves binary-float noise
 * (8e-7 -> 0.7999999999999999); 6 decimals is finer than any published rate.
 */
private fun perMillion(rate: Double?): Double? =
    rate?.let { round(it * TOKENS_PER_MILLION * 1_000_000.0) / 1_000_000.0 }

/** Normalise one OpenRouter-shaped `pricing` object (USD per token) to per 1M. */
internal fun normalizeOpenRouterPricing(obj: JsonObject?, source: String): ModelPricing? {
    if (obj == null) return null
    val input = perMillion(obj.rate("prompt"))
    val output = perMillion(obj.rate("completion"))
    if (input == null && output == null) return null
    val overrides = obj["overrides"] as? JsonArray
    return ModelPricing(
        input = input,
        output = output,
        source = source,
        cacheRead = perMillion(obj.rate("input_cache_read")),
        cacheWrite = perMillion(obj.rate("input_cache_write")),
        tiered = overrides != null && overrides.isNotEmpty(),
    )
}

/** Parse an OpenRouter-shaped `/v1/models` body into id -> rates per 1M. */
internal fun parseOpenRouterPricingCatalog(
    json: Json,
    body: String,
    source: String,
): Map<String, ModelPricing> {
    val rows = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray
    }.getOrNull() ?: return emptyMap()
    val catalog = LinkedHashMap<String, ModelPricing>()
    for (row in rows) {
        val obj = row as? JsonObject ?: continue
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val pricing = normalizeOpenRouterPricing(obj["pricing"] as? JsonObject, source) ?: continue
        catalog[id] = pricing
    }
    return catalog
}

/** Parse `models.dev/api.json`; its `cost` is already per 1M, so rates pass through. */
internal fun parseModelsDevPricingCatalog(
    json: Json,
    body: String,
    providerKey: String,
    source: String,
): Map<String, ModelPricing> {
    val models = runCatching {
        ((json.parseToJsonElement(body) as? JsonObject)?.get(providerKey) as? JsonObject)
            ?.get("models") as? JsonObject
    }.getOrNull() ?: return emptyMap()
    val catalog = LinkedHashMap<String, ModelPricing>()
    for ((rawId, raw) in models) {
        val cost = (raw as? JsonObject)?.get("cost") as? JsonObject ?: continue
        val input = cost.rate("input")
        val output = cost.rate("output")
        if (input == null && output == null) continue
        val id = rawId.trim().takeIf { it.isNotEmpty() } ?: continue
        catalog[id] = ModelPricing(
            input = input,
            output = output,
            source = source,
            cacheRead = cost.rate("cache_read"),
            cacheWrite = cost.rate("cache_write"),
            // models.dev spells context tiers differently from OpenRouter; the
            // normalised flag means the same thing.
            tiered = cost["tiers"] != null || cost["context_over_200k"] != null,
        )
    }
    return catalog
}

/**
 * Resolve a picker model id against a catalog, tolerating id decoration.
 *
 * Ids reach the picker with OpenRouter-style `:free`/`:batch` variant suffixes
 * or a `vendor/` prefix the catalog does not key on (OpenCode Go keys on bare
 * ids), so try the id as given first, then progressively less decorated forms.
 */
internal fun lookupModelPricing(
    catalog: Map<String, ModelPricing>,
    modelId: String,
): ModelPricing? {
    if (modelId.isEmpty() || catalog.isEmpty()) return null
    val candidates = LinkedHashSet<String>()
    candidates.add(modelId)
    candidates.add(modelId.lowercase())
    val base = modelId.substringBefore(':')
    if (base != modelId && base.isNotEmpty()) {
        candidates.add(base)
        candidates.add(base.lowercase())
    }
    for (candidate in candidates.toList()) {
        if (candidate.contains('/')) {
            candidates.add(candidate.substringAfterLast('/'))
        }
    }
    for (candidate in candidates) {
        catalog[candidate]?.let { return it }
    }
    return null
}

/** Format one USD-per-1M rate compactly, or "" when it is unusable. */
internal fun formatPricingRate(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0.0) return ""
    if (value == 0.0) return "\$0"
    // Sub-cent rates need more decimals than dollar-scale ones, and trailing
    // zeros are noise in a picker row.
    val digits = when {
        value < 0.01 -> 4
        value < 1.0 -> 3
        else -> 2
    }
    var text = String.format(java.util.Locale.US, "%.${digits}f", value)
    if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
    return "\$$text"
}

/**
 * Picker copy for a model's rates: `$3 / $15` (input / output per 1M tokens).
 *
 * An unknown side renders as an en dash rather than being dropped, so the pair
 * can never be misread as the other rate. A trailing `+` marks context-tiered
 * pricing. Returns "" when nothing is known, so the caller shows no price at all.
 */
fun modelPricingLabel(pricing: ModelPricing?, freeLabel: String): String {
    if (pricing == null) return ""
    val input = formatPricingRate(pricing.input)
    val output = formatPricingRate(pricing.output)
    if (input.isEmpty() && output.isEmpty()) return ""
    if (input == "\$0" && output == "\$0") return freeLabel
    val body = when {
        input.isNotEmpty() && output.isNotEmpty() -> "$input / $output"
        input.isNotEmpty() -> "$input / –"
        else -> "– / $output"
    }
    return if (pricing.tiered) "$body+" else body
}

/**
 * Rates for one model out of the provider-keyed map ChatViewModel exposes as
 * `modelPricing`, or null when that provider publishes no rate card.
 */
fun pricingForModel(
    pricing: Map<String, Map<String, ModelPricing>>,
    providerSlug: String?,
    modelId: String,
): ModelPricing? {
    if (pricing.isEmpty()) return null
    val provider = canonicalPricingProvider(providerSlug) ?: return null
    val catalog = pricing[provider] ?: return null
    return lookupModelPricing(catalog, modelId)
}

/**
 * Join a model's existing picker secondary ("Not on your plan", "Needs setup")
 * with its price, keeping the availability warning first — it is the reason a
 * row may be unselectable, which outranks the rate.
 */
fun joinPickerSecondary(existing: String?, price: String): String? {
    val left = existing?.trim().orEmpty()
    return when {
        left.isNotEmpty() && price.isNotEmpty() -> "$left · $price"
        left.isNotEmpty() -> left
        price.isNotEmpty() -> price
        else -> null
    }
}
