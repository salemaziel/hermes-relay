package com.hermesandroid.relay.network.upstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches the public per-1M-token rate cards the picker shows.
 *
 * The gateway `model.options` RPC carries availability and capabilities but no
 * rates, and Relay must work against unmodified upstream hermes-agent, so the
 * rates come from the providers' own public catalogs client-side. All three are
 * unauthenticated reads of a static document.
 *
 * Every failure degrades to an empty catalog: a model with no known rate renders
 * exactly as it did before pricing existed.
 */
class ModelPricingRepository(
    httpClient: OkHttpClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private data class CacheEntry(val expiresAt: Long, val catalog: Map<String, ModelPricing>)

    private val mutex = Mutex()
    private val cache = HashMap<String, CacheEntry>()

    /**
     * Rates for one canonical provider, cached. Unknown providers and every
     * failure yield an empty map.
     */
    suspend fun catalogFor(providerSlug: String?): Map<String, ModelPricing> {
        val provider = canonicalPricingProvider(providerSlug) ?: return emptyMap()
        mutex.withLock {
            cache[provider]?.let { if (it.expiresAt > nowMillis()) return it.catalog }
        }

        val catalog = loadCatalog(provider)
        // A failed fetch is remembered briefly so an offline device does not pay
        // the connect timeout every time the picker opens.
        val ttl = if (catalog.isEmpty()) ERROR_TTL_MS else CATALOG_TTL_MS
        mutex.withLock {
            cache[provider] = CacheEntry(nowMillis() + ttl, catalog)
        }
        return catalog
    }

    /**
     * Rates for every priced provider in [providerSlugs], keyed by canonical
     * provider id then model id. Providers that publish no catalog are omitted.
     */
    suspend fun pricingFor(
        providerSlugs: Collection<String?>,
    ): Map<String, Map<String, ModelPricing>> {
        val providers = providerSlugs.mapNotNull(::canonicalPricingProvider).distinct()
        if (providers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Map<String, ModelPricing>>()
        for (provider in providers) {
            val catalog = catalogFor(provider)
            if (catalog.isNotEmpty()) out[provider] = catalog
        }
        return out
    }

    private suspend fun loadCatalog(provider: String): Map<String, ModelPricing> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (provider) {
                    PRICING_PROVIDER_NOUS ->
                        parseOpenRouterPricingCatalog(json, get(NOUS_PRICING_URL), provider)
                    PRICING_PROVIDER_OPENROUTER ->
                        parseOpenRouterPricingCatalog(json, get(OPENROUTER_PRICING_URL), provider)
                    PRICING_PROVIDER_OPENCODE_GO ->
                        parseModelsDevPricingCatalog(
                            json,
                            get(MODELS_DEV_PRICING_URL),
                            "opencode-go",
                            provider,
                        )
                    PRICING_PROVIDER_OPENCODE_ZEN ->
                        parseModelsDevPricingCatalog(
                            json,
                            get(MODELS_DEV_PRICING_URL),
                            "opencode",
                            provider,
                        )
                    else -> emptyMap()
                }
            }.getOrElse {
                android.util.Log.w(TAG, "pricing catalog fetch failed for $provider: ${it.message}")
                emptyMap()
            }
        }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // Nous and OpenRouter reject a default/absent agent with HTTP 403.
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("$url responded ${response.code}")
            return response.body.string()
        }
    }

    companion object {
        private const val TAG = "ModelPricing"
        private const val USER_AGENT = "hermes-relay model-picker pricing"
        private const val CATALOG_TTL_MS = 6L * 60L * 60L * 1000L
        private const val ERROR_TTL_MS = 5L * 60L * 1000L
    }
}
