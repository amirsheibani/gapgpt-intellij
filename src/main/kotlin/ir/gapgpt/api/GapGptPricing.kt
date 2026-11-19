package ir.gapgpt.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Prices of GapGPT models (USD per 1M tokens), from the bundled snapshot of gapgpt.app/platform-v2/pricing.
 * Costs computed from them are a local estimate, not the bill.
 */
object GapGptPricing {

    data class Price(val provider: String, val input: Double, val cached: Double?, val output: Double)

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

    /** Cost in USD, or null when the model has no price. Cached prompt tokens use the cached price when the model has one. */
    fun cost(model: String, input: Long, cached: Long, output: Long): Double? {
        val p = price(model) ?: return null
        val c = cached.coerceIn(0, input)
        val cachedPrice = p.cached ?: p.input
        return ((input - c) * p.input + c * cachedPrice + output * p.output) / 1_000_000.0
    }
}
