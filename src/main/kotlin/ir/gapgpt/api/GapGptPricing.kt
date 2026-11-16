package ir.gapgpt.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.gapgpt.settings.GapGptApi

/**
 * Prices and limits of GapGPT models.
 *  - Prices (USD per 1M tokens) come from the bundled snapshot of gapgpt.app/platform-v2/pricing.
 *  - Context window / max output come from `GET /v1/models` when the API reports them; otherwise an
 *    approximate value by model family is used (GapGPT does not publish them), flagged as `estimated`.
 */
object GapGptPricing {

    data class Price(val provider: String, val input: Double, val cached: Double?, val output: Double)

    data class Limits(val contextWindow: Long, val maxOutput: Long?, val estimated: Boolean)

    private val data: JsonObject by lazy {
        javaClass.getResourceAsStream("/gapgpt/pricing.json")?.reader(Charsets.UTF_8)?.use {
            JsonParser.parseReader(it).asJsonObject
        } ?: JsonObject()
    }

    val asOf: String get() = data.get("asOf")?.asString.orEmpty()

    private val prices: Map<String, Price> by lazy {
        val m = data.getAsJsonObject("models") ?: return@lazy emptyMap()
        m.entrySet().associate { (id, v) ->
            val o = v.asJsonObject
            id to Price(
                o.get("provider")?.asString.orEmpty(),
                o.get("input")?.asDouble ?: 0.0,
                o.get("cached")?.takeIf { !it.isJsonNull }?.asDouble,
                o.get("output")?.asDouble ?: 0.0,
            )
        }
    }

    /** Exact id first, then the id without a date / "-latest" suffix. */
    fun price(model: String): Price? {
        val id = model.trim().lowercase()
        prices[id]?.let { return it }
        val base = id.replace(Regex("""-(\d{8}|\d{4}-\d{2}-\d{2}|latest)$"""), "")
        return prices[base]
    }

    /** Cost in USD. Cached prompt tokens are billed at the cached price when the model has one. */
    fun cost(model: String, input: Long, cached: Long, output: Long): Double? {
        val p = price(model) ?: return null
        val c = cached.coerceIn(0, input)
        val cachedPrice = p.cached ?: p.input
        return ((input - c) * p.input + c * cachedPrice + output * p.output) / 1_000_000.0
    }

    fun limits(model: String, api: GapGptApi.Model?): Limits {
        if (api?.contextWindow != null && api.contextWindow > 0) return Limits(api.contextWindow, api.maxOutput, false)
        return Limits(estimateContext(model), api?.maxOutput, true)
    }

    private fun estimateContext(model: String): Long {
        val id = model.lowercase()
        return when {
            id.startsWith("claude") -> 200_000
            id.startsWith("gpt-4.1") -> 1_047_576
            id.startsWith("gpt-4o") -> 128_000
            id.startsWith("gpt-5") || id.startsWith("gpt-6") || id.startsWith("gpt-chat") -> 400_000
            Regex("""^o\d""").containsMatchIn(id) -> 200_000
            id.startsWith("gemini") -> 1_048_576
            id.contains("qwen3-coder") -> 262_144
            id.startsWith("grok") || id.startsWith("kimi") -> 256_000
            else -> 128_000
        }
    }
}
