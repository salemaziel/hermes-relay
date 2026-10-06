package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-1M-token normalisation the model picker renders.
 *
 * The two upstream shapes disagree on units — Nous/OpenRouter publish USD per
 * token, models.dev publishes USD per 1M — so the conversion, and the refusal to
 * turn an unusable rate into `$0`, are the contracts worth locking.
 */
class ModelPricingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val openRouterBody = """
        {"data":[
          {"id":"anthropic/claude-fable-5.1","pricing":{
             "prompt":"0.0000080000","completion":"0.0000400000",
             "input_cache_read":"0.0000008000","input_cache_write":"0.0000100000"}},
          {"id":"openai/gpt-6-astra","pricing":{
             "prompt":"0.00001","completion":"0.00005",
             "overrides":[{"min_prompt_tokens":272000,"prompt":"0.00002"}]}},
          {"id":"vendor/model:free","pricing":{"prompt":"0","completion":"0"}},
          {"id":"junk/model","pricing":{"prompt":"n/a","completion":null}},
          {"id":"no-pricing/model"}
        ]}
    """.trimIndent()

    private val modelsDevBody = """
        {"opencode-go":{"models":{
          "glm-5.2":{"cost":{"input":1.4,"output":4.4,"cache_read":0.26}},
          "qwen3.6-plus":{"cost":{"input":0.5,"output":3,"tiers":[{"input":2,"output":6}]}},
          "no-cost":{}
        }}}
    """.trimIndent()

    @Test
    fun perTokenRatesBecomePerMillionWithoutFloatingPointNoise() {
        val catalog = parseOpenRouterPricingCatalog(json, openRouterBody, PRICING_PROVIDER_NOUS)

        val fable = catalog.getValue("anthropic/claude-fable-5.1")
        assertEquals(8.0, fable.input!!, 0.0)
        assertEquals(40.0, fable.output!!, 0.0)
        // 8e-7 * 1e6 is 0.7999999999999999 before rounding
        assertEquals(0.8, fable.cacheRead!!, 0.0)
        assertEquals(10.0, fable.cacheWrite!!, 0.0)
    }

    @Test
    fun contextOverridesMarkAModelAsTiered() {
        val catalog = parseOpenRouterPricingCatalog(json, openRouterBody, PRICING_PROVIDER_OPENROUTER)

        assertTrue(catalog.getValue("openai/gpt-6-astra").tiered)
        assertTrue(!catalog.getValue("anthropic/claude-fable-5.1").tiered)
    }

    @Test
    fun modelsWithNoUsableRatesAreOmittedRatherThanPricedAtZero() {
        val catalog = parseOpenRouterPricingCatalog(json, openRouterBody, PRICING_PROVIDER_NOUS)

        assertNull(catalog["junk/model"])
        assertNull(catalog["no-pricing/model"])
    }

    @Test
    fun genuinelyFreeModelsSurviveAsZero() {
        val catalog = parseOpenRouterPricingCatalog(json, openRouterBody, PRICING_PROVIDER_OPENROUTER)

        assertEquals(0.0, catalog.getValue("vendor/model:free").input!!, 0.0)
        assertEquals(0.0, catalog.getValue("vendor/model:free").output!!, 0.0)
    }

    @Test
    fun malformedBodiesYieldAnEmptyCatalogInsteadOfThrowing() {
        assertTrue(parseOpenRouterPricingCatalog(json, "not json", PRICING_PROVIDER_NOUS).isEmpty())
        assertTrue(parseOpenRouterPricingCatalog(json, "{}", PRICING_PROVIDER_NOUS).isEmpty())
        assertTrue(
            parseModelsDevPricingCatalog(json, "not json", "opencode-go", PRICING_PROVIDER_OPENCODE_GO)
                .isEmpty(),
        )
    }

    @Test
    fun modelsDevRatesPassThroughUnscaled() {
        val catalog =
            parseModelsDevPricingCatalog(json, modelsDevBody, "opencode-go", PRICING_PROVIDER_OPENCODE_GO)

        val glm = catalog.getValue("glm-5.2")
        assertEquals(1.4, glm.input!!, 0.0)
        assertEquals(4.4, glm.output!!, 0.0)
        assertEquals(0.26, glm.cacheRead!!, 0.0)
        assertTrue(!glm.tiered)
        assertNull(catalog["no-cost"])
    }

    @Test
    fun modelsDevContextTiersMapOntoTheSameTieredFlag() {
        val catalog =
            parseModelsDevPricingCatalog(json, modelsDevBody, "opencode-go", PRICING_PROVIDER_OPENCODE_GO)

        assertTrue(catalog.getValue("qwen3.6-plus").tiered)
    }

    @Test
    fun providerSlugSpellingsFoldOntoOneCanonicalId() {
        assertEquals(PRICING_PROVIDER_NOUS, canonicalPricingProvider("NousResearch"))
        assertEquals(PRICING_PROVIDER_NOUS, canonicalPricingProvider(" nous "))
        assertEquals(PRICING_PROVIDER_OPENROUTER, canonicalPricingProvider("OpenRouter"))
        assertEquals(PRICING_PROVIDER_OPENCODE_GO, canonicalPricingProvider("opencode_go"))
        assertNull(canonicalPricingProvider("anthropic"))
        assertNull(canonicalPricingProvider(null))
    }

    @Test
    fun decoratedModelIdsStillResolveAgainstTheCatalog() {
        val catalog = parseOpenRouterPricingCatalog(json, openRouterBody, PRICING_PROVIDER_NOUS)

        assertEquals(8.0, lookupModelPricing(catalog, "anthropic/claude-fable-5.1")!!.input!!, 0.0)
        // an OpenRouter variant suffix the catalog does not key on
        assertEquals(8.0, lookupModelPricing(catalog, "anthropic/claude-fable-5.1:batch")!!.input!!, 0.0)
        assertNull(lookupModelPricing(catalog, "nothing/here"))
        assertNull(lookupModelPricing(catalog, ""))
    }

    @Test
    fun bareCatalogIdsResolveFromAVendorPrefixedRequestId() {
        val catalog =
            parseModelsDevPricingCatalog(json, modelsDevBody, "opencode-go", PRICING_PROVIDER_OPENCODE_GO)

        assertEquals(1.4, lookupModelPricing(catalog, "opencode/glm-5.2")!!.input!!, 0.0)
    }

    @Test
    fun ratesFormatCompactlyAcrossFourOrdersOfMagnitude() {
        assertEquals("\$40", formatPricingRate(40.0))
        assertEquals("\$2.5", formatPricingRate(2.5))
        assertEquals("\$0.26", formatPricingRate(0.26))
        // a sub-cent rate would round away entirely at two decimals
        assertEquals("\$0.006", formatPricingRate(0.006))
        assertEquals("\$0.0032", formatPricingRate(0.0032))
        assertEquals("\$0", formatPricingRate(0.0))
        assertEquals("", formatPricingRate(null))
        assertEquals("", formatPricingRate(-1.0))
        assertEquals("", formatPricingRate(Double.NaN))
    }

    @Test
    fun pickerLabelShowsInputThenOutputAndMarksTieredPricing() {
        val flat = ModelPricing(input = 3.0, output = 15.0, source = PRICING_PROVIDER_OPENROUTER)
        assertEquals("\$3 / \$15", modelPricingLabel(flat, "Free"))
        assertEquals("\$3 / \$15+", modelPricingLabel(flat.copy(tiered = true), "Free"))
    }

    @Test
    fun aZeroRatedModelReadsAsFreeRatherThanZeroSlashZero() {
        val free = ModelPricing(input = 0.0, output = 0.0, source = PRICING_PROVIDER_OPENROUTER)

        assertEquals("Gratis", modelPricingLabel(free, "Gratis"))
    }

    @Test
    fun aHalfKnownRateCannotBeMisreadAsThePair() {
        val inputOnly = ModelPricing(input = 3.0, output = null, source = PRICING_PROVIDER_NOUS)
        val outputOnly = ModelPricing(input = null, output = 15.0, source = PRICING_PROVIDER_NOUS)

        assertEquals("\$3 / –", modelPricingLabel(inputOnly, "Free"))
        assertEquals("– / \$15", modelPricingLabel(outputOnly, "Free"))
    }

    @Test
    fun anUnpricedModelContributesNoLabel() {
        assertEquals("", modelPricingLabel(null, "Free"))
        assertEquals(
            "",
            modelPricingLabel(ModelPricing(input = null, output = null, source = "x"), "Free"),
        )
    }

    @Test
    fun pricingLooksUpThroughTheProviderKeyedMap() {
        val catalog = parseOpenRouterPricingCatalog(json, openRouterBody, PRICING_PROVIDER_NOUS)
        val pricing = mapOf(PRICING_PROVIDER_NOUS to catalog)

        assertEquals(8.0, pricingForModel(pricing, "nous", "anthropic/claude-fable-5.1")!!.input!!, 0.0)
        // a provider with no rate card contributes nothing
        assertNull(pricingForModel(pricing, "anthropic", "anthropic/claude-fable-5.1"))
        assertNull(pricingForModel(emptyMap(), "nous", "anthropic/claude-fable-5.1"))
    }

    @Test
    fun availabilityWarningKeepsPrecedenceOverThePriceInTheSecondaryLine() {
        assertEquals("Not on your plan · \$3 / \$15", joinPickerSecondary("Not on your plan", "\$3 / \$15"))
        assertEquals("\$3 / \$15", joinPickerSecondary(null, "\$3 / \$15"))
        assertEquals("Needs setup", joinPickerSecondary("Needs setup", ""))
        assertNull(joinPickerSecondary(null, ""))
        assertNull(joinPickerSecondary("   ", ""))
    }
}
