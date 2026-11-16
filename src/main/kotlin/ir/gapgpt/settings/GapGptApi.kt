package ir.gapgpt.settings

import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GapGPT platform (https://gapgpt.app/platform-v2): OpenAI-compatible API used directly by the plugin.
 * Completely independent of the Claude Code CLI — nothing here touches its environment or config.
 */
object GapGptApi {
    const val DEFAULT_BASE_URL = "https://api.gapgpt.app/v1"
    /** Alternative endpoint served through an external CDN. */
    const val CDN_BASE_URL = "https://api.gapapi.com/v1"
    const val DEFAULT_MODEL = "claude-sonnet-5-5"

    /** [contextWindow] / [maxOutput] are only set when `/v1/models` reports them. */
    data class Model(val id: String, val ownedBy: String, val contextWindow: Long? = null, val maxOutput: Long? = null)

    /** Account credit in USD; [limit] is null when the key has no spending cap. */
    data class Balance(val limit: Double?, val used: Double?) {
        val remaining: Double? get() = if (limit != null && used != null) (limit - used).coerceAtLeast(0.0) else null
    }

    private val credentials = CredentialAttributes(generateServiceName("GapGPT", "apiKey"))

    /** The API key lives in the IDE password safe, never in gapgpt.xml. */
    var apiKey: String
        get() = PasswordSafe.instance.getPassword(credentials).orEmpty()
        set(value) = PasswordSafe.instance.setPassword(credentials, value.trim().ifBlank { null })

    /** OpenAI-style base URL ending in `/v1` (added if the user pasted just the host). */
    fun normalizeBaseUrl(url: String): String {
        val u = url.trim().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        return if (u.endsWith("/v1")) u else "$u/v1"
    }

    fun baseUrl(): String = normalizeBaseUrl(GapGptSettings.get().gapgptBaseUrl)

    fun httpClient(proxy: String = GapGptSettings.get().httpsProxy): HttpClient {
        val b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).version(HttpClient.Version.HTTP_1_1)
        proxy.trim().takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() }?.let { u ->
            if (u.host != null && u.port > 0) b.proxy(ProxySelector.of(InetSocketAddress(u.host, u.port)))
        }
        return b.build()
    }

    @Volatile
    private var cachedModels: List<Model>? = null

    /** `GET /v1/models` — every model the account can use. Cached until [refresh]. */
    fun listModels(refresh: Boolean = false, baseUrl: String = baseUrl(), key: String = apiKey): List<Model> {
        if (!refresh) cachedModels?.let { return it }
        require(key.isNotBlank()) { I18n.t("No GapGPT API key (Settings › Tools › GapGPT)", "کلید API گپ‌جی‌پی‌تی وارد نشده است (Settings › Tools › GapGPT)") }
        val req = HttpRequest.newBuilder(URI("$baseUrl/models"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${key.trim()}")
            .GET().build()
        val res = httpClient().send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IllegalStateException(errorMessage(res.statusCode(), res.body()))
        val models = JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("data").mapNotNull { e ->
            val o = e.asJsonObject
            val id = o.get("id")?.asString ?: return@mapNotNull null
            fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
                o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 }
            }
            Model(
                id,
                o.get("owned_by")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                num("context_length", "context_window", "max_context_length", "max_input_tokens", "input_token_limit"),
                num("max_output_tokens", "max_completion_tokens", "output_token_limit"),
            )
        }.distinctBy { it.id }.sortedBy { it.id }
        cachedModels = models
        return models
    }

    fun model(id: String): Model? = cachedModels?.firstOrNull { it.id == id }

    @Volatile
    private var cachedBalance: Pair<Long, Balance?>? = null

    /**
     * Account credit via the OpenAI-style billing endpoints GapGPT exposes
     * (`/dashboard/billing/subscription` → hard_limit_usd, `/dashboard/billing/usage` → total_usage in cents).
     * Returns null when they are unavailable. Cached for [maxAgeMs].
     */
    fun balance(maxAgeMs: Long = 60_000): Balance? {
        cachedBalance?.let { (at, b) -> if (System.currentTimeMillis() - at < maxAgeMs) return b }
        val key = apiKey.takeIf { it.isNotBlank() } ?: return null
        fun get(path: String): com.google.gson.JsonObject? = runCatching {
            val req = HttpRequest.newBuilder(URI("${baseUrl()}$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer ${key.trim()}")
                .GET().build()
            val res = httpClient().send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) JsonParser.parseString(res.body()).asJsonObject else null
        }.getOrNull()
        val sub = get("/dashboard/billing/subscription")
        val today = java.time.LocalDate.now()
        val usage = get("/dashboard/billing/usage?start_date=${today.minusDays(99)}&end_date=${today.plusDays(1)}")
        val limit = sub?.get("hard_limit_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?: sub?.get("system_hard_limit_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
        val used = usage?.get("total_usage")?.takeIf { it.isJsonPrimitive }?.asDouble?.div(100.0)
        val b = if (limit == null && used == null) null
        else Balance(limit?.takeIf { it in 0.0..9_999_999.0 }, used)
        cachedBalance = System.currentTimeMillis() to b
        return b
    }

    fun invalidateBalance() {
        cachedBalance = null
    }

    /** Extracts a readable message from an OpenAI-style error body. */
    fun errorMessage(code: Int, body: String): String {
        val msg = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error").get("message").asString
        }.getOrNull()?.substringBefore(" (request id")
        val hint = when (code) {
            401, 403 -> I18n.t("Invalid API key", "کلید API نامعتبر است")
            402 -> I18n.t("Insufficient account credit", "اعتبار حساب کافی نیست")
            429 -> I18n.t("Too many requests", "تعداد درخواست‌ها بیش از حد مجاز است")
            else -> "HTTP $code"
        }
        return if (msg.isNullOrBlank()) hint else "$hint — $msg"
    }

    /** Lists models (no tokens spent) and returns a short Persian status line. Call off the EDT. */
    fun testConnection(baseUrl: String, key: String): String {
        if (key.isBlank()) return I18n.t("✗ No API key entered", "✗ کلید API وارد نشده است")
        return try {
            val n = listModels(refresh = true, baseUrl = normalizeBaseUrl(baseUrl), key = key).size
            I18n.t("✓ Connected · $n models available", "✓ اتصال برقرار است · $n مدل در دسترس")
        } catch (e: Exception) {
            "✗ ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
