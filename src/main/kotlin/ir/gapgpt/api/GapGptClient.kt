package ir.gapgpt.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import ir.gapgpt.I18n
import ir.gapgpt.PluginLog
import ir.gapgpt.chat.ChatMessage
import ir.gapgpt.chat.ToolCall
import ir.gapgpt.settings.ApiKeyStore
import ir.gapgpt.settings.GapGptSettings
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Talks to the GapGPT chat-completions API (OpenAI-compatible, https://gapgpt.app):
 * `POST {baseUrl}/chat/completions` with `Authorization: Bearer <key>`.
 * Handles both server-sent-event streams and plain JSON replies.
 */
object GapGptClient {

    /** A finished reply: text and/or the tool calls the model wants to make. */
    class Reply(val text: String, val toolCalls: List<ToolCall>, val usage: JsonObject?)

    interface Listener {
        fun onDelta(text: String)
        fun onDone(reply: Reply)

        /** [status] is the HTTP status (0 for network or protocol errors). */
        fun onError(message: String, status: Int = 0)
    }

    /** A request in flight. [cancel] aborts it; no listener callback is made afterwards. */
    class Handle internal constructor() {
        @Volatile
        internal var cancelled = false

        @Volatile
        internal var future: CompletableFuture<*>? = null

        @Volatile
        internal var stream: InputStream? = null

        fun cancel() {
            cancelled = true
            future?.cancel(true)
            try {
                stream?.close()
            } catch (_: Exception) {
            }
        }
    }

    private val gson = Gson()
    private const val MAX_ERROR_BODY = 64 * 1024

    private var cachedClient: HttpClient? = null
    private var cachedProxy: String = ""

    /** One shared client; rebuilt only when the proxy setting changes. */
    @Synchronized
    internal fun client(): HttpClient {
        val proxy = GapGptSettings.get().httpsProxy.trim()
        cachedClient?.let { if (proxy == cachedProxy) return it }
        val b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
        parseProxy(proxy)?.let { b.proxy(ProxySelector.of(it)) }
        return b.build().also {
            cachedClient = it
            cachedProxy = proxy
        }
    }

    private fun parseProxy(text: String): InetSocketAddress? {
        if (text.isBlank()) return null
        return try {
            val uri = URI(if (text.contains("://")) text else "http://$text")
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 8080
            InetSocketAddress(host, port)
        } catch (e: Exception) {
            PluginLog.warn("api", "Invalid proxy setting ignored")
            null
        }
    }

    /** [tools] are OpenAI-style function definitions; null sends a plain chat request. */
    fun send(messages: List<ChatMessage>, tools: JsonArray?, listener: Listener): Handle {
        val handle = Handle()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                run(messages, tools, listener, handle)
            } catch (e: Throwable) {
                if (!handle.cancelled) {
                    PluginLog.warn("api", "Request failed: ${e.javaClass.simpleName}")
                    listener.onError(describe(e))
                }
            }
        }
        return handle
    }

    /** OpenAI-style base URL ending in `/v1` (added when the user pasted just the host). */
    fun normalizeBaseUrl(url: String): String {
        val u = url.trim().ifBlank { "https://api.gapgpt.app/v1" }.trimEnd('/')
        return if (u.endsWith("/v1")) u else "$u/v1"
    }

    /** The configured base URL, normalized. */
    fun baseUrl(): String = normalizeBaseUrl(GapGptSettings.get().baseUrl)

    /** Account credit in USD; [limit] is null when the key has no spending cap. */
    data class Balance(val limit: Double?, val used: Double?) {
        val remaining: Double? get() = if (limit != null && used != null) (limit - used).coerceAtLeast(0.0) else null
    }

    @Volatile
    private var cachedBalance: Pair<Long, Balance?>? = null

    /**
     * Account credit via the OpenAI-style billing endpoints GapGPT exposes
     * (`/dashboard/billing/subscription` -> hard_limit_usd, `/dashboard/billing/usage` -> total_usage in cents).
     * Blocking (HTTP + PasswordSafe): call off the EDT. Returns null on any failure. Cached for [maxAgeMs].
     */
    fun balance(maxAgeMs: Long = 60_000): Balance? {
        cachedBalance?.let { (at, b) -> if (System.currentTimeMillis() - at < maxAgeMs) return b }
        val key = ApiKeyStore.get()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = baseUrl()
        fun get(path: String): JsonObject? = runCatching {
            val req = HttpRequest.newBuilder(URI("$base$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .GET().build()
            val res = client().send(req, HttpResponse.BodyHandlers.ofString(UTF_8))
            if (res.statusCode() == 200) JsonParser.parseString(res.body()).asJsonObject else null
        }.getOrNull()
        val sub = get("/dashboard/billing/subscription")
        val today = java.time.LocalDate.now()
        val usage = get("/dashboard/billing/usage?start_date=${today.minusDays(99)}&end_date=${today.plusDays(1)}")
        val limit = sub?.get("hard_limit_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?: sub?.get("system_hard_limit_usd")?.takeIf { it.isJsonPrimitive }?.asDouble
        val used = usage?.get("total_usage")?.takeIf { it.isJsonPrimitive }?.asDouble?.div(100.0)
        val b = if (limit == null && used == null) null else Balance(limit?.takeIf { it in 0.0..9_999_999.0 }, used)
        cachedBalance = System.currentTimeMillis() to b
        return b
    }

    fun invalidateBalance() {
        cachedBalance = null
    }

    /** Thrown by [listModels]; [message] is ready to show to the user. */
    class ApiException(message: String) : Exception(message)

    /** Ids that are not chat models (embeddings, speech, image generation). */
    private val NON_CHAT = Regex("(?i)embed|\\bbge\\b|whisper|transcribe|tts|image|dall-?e|imagen|flux|midjourney|moderation|sora|veo|rerank|stable-?diffusion|\\bsdxl|suno|kling|runway")

    /**
     * `GET {baseUrl}/models`: the chat models available to this API key, sorted by name.
     * Blocking; call it off the EDT. Throws [ApiException] with a user-facing message.
     * [apiKey] / [baseUrl] override the saved values (the settings page passes what is typed but not applied yet).
     */
    fun listModels(apiKey: String? = null, baseUrl: String? = null): List<String> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: ApiKeyStore.get() ?: throw ApiException(
            I18n.tr("No API key is set. Add it in Settings › Tools › GapGPT.", "کلید API تنظیم نشده است. آن را در Settings › Tools › GapGPT وارد کنید.")
        )
        val base = normalizeBaseUrl(baseUrl?.takeIf { it.isNotBlank() } ?: GapGptSettings.get().baseUrl)
        val uri = try {
            URI("$base/models").also { require(it.scheme == "https" || it.scheme == "http") }
        } catch (e: Exception) {
            throw ApiException(I18n.tr("The base URL in Settings is not valid.", "نشانی پایه در تنظیمات معتبر نیست."))
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = try {
            client().send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiException(describe(CancellationException()))
        } catch (e: Exception) {
            throw ApiException(describe(e))
        }
        val text = response.body().orEmpty()
        val status = response.statusCode()
        if (status !in 200..299) {
            val type = response.headers().firstValue("Content-Type").orElse("?")
            PluginLog.warn("api", "GET /models HTTP $status body=${plainText(text).take(300)}")
            val detail = extractError(text).ifBlank { plainText(text).take(200) }
            throw ApiException(httpError(status, detail, status == 403 && type.contains("html", ignoreCase = true)))
        }
        val data = try {
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("data")
        } catch (e: Exception) {
            null
        } ?: throw ApiException(I18n.tr("The server returned an unreadable reply.", "پاسخ سرور قابل خواندن نبود."))
        val ids = data.mapNotNull { (it as? JsonObject)?.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString?.trim() }
            .filter { it.isNotEmpty() && !NON_CHAT.containsMatchIn(it) }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        PluginLog.info("api", "GET /models: ${data.size()} models, ${ids.size} chat models")
        return ids
    }

    private fun messageJson(m: ChatMessage) = JsonObject().apply {
        addProperty("role", m.role)
        if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
            if (m.content.isEmpty()) add("content", com.google.gson.JsonNull.INSTANCE) else addProperty("content", m.content)
            add("tool_calls", JsonArray().also { arr ->
                m.toolCalls.forEach { c ->
                    arr.add(JsonObject().apply {
                        addProperty("id", c.id)
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", c.name)
                            addProperty("arguments", c.arguments.ifBlank { "{}" })
                        })
                    })
                }
            })
        } else {
            addProperty("content", m.content)
        }
        m.toolCallId?.let { addProperty("tool_call_id", it) }
    }

    private fun run(messages: List<ChatMessage>, tools: JsonArray?, listener: Listener, handle: Handle) {
        val s = GapGptSettings.get()
        val key = ApiKeyStore.get()
        if (key == null) {
            listener.onError(
                I18n.tr(
                    "No API key is set. Add it in Settings › Tools › GapGPT.",
                    "کلید API تنظیم نشده است. آن را در Settings › Tools › GapGPT وارد کنید.",
                )
            )
            return
        }
        val url = baseUrl() + "/chat/completions"
        val uri = try {
            URI(url).also { require(it.scheme == "https" || it.scheme == "http") }
        } catch (e: Exception) {
            listener.onError(I18n.tr("The base URL in Settings is not valid.", "نشانی پایه در تنظیمات معتبر نیست."))
            return
        }
        val stream = s.streaming
        val body = JsonObject().apply {
            addProperty("model", s.model.trim())
            add("messages", JsonArray().also { arr -> messages.forEach { arr.add(messageJson(it)) } })
            if (tools != null && tools.size() > 0) {
                add("tools", tools)
                addProperty("tool_choice", "auto")
            }
            addProperty("max_completion_tokens", s.maxCompletionTokens)
            if (stream) addProperty("stream", true)
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream, application/json" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), UTF_8))
            .build()
        PluginLog.info("api", "POST /chat/completions model=${s.model.trim()} messages=${messages.size} tools=${tools?.size() ?: 0} stream=$stream")

        val future = client().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        handle.future = future
        if (handle.cancelled) future.cancel(true)
        val response = try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        handle.stream = response.body()
        if (handle.cancelled) {
            handle.cancel()
            return
        }
        response.body().use { input ->
            val status = response.statusCode()
            if (status !in 200..299) {
                val text = String(input.readNBytes(MAX_ERROR_BODY), UTF_8)
                val type = response.headers().firstValue("Content-Type").orElse("?")
                val server = response.headers().firstValue("Server").orElse("?")
                PluginLog.warn("api", "HTTP $status type=$type server=$server body=${plainText(text).take(500)}")
                val detail = extractError(text).ifBlank { plainText(text).take(200) }
                // Only an HTML page means something in front of the API (firewall / proxy) answered.
                val blocked = status == 403 && type.contains("html", ignoreCase = true)
                listener.onError(httpError(status, detail, blocked), status)
                return
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("").lowercase()
            if (contentType.contains("event-stream")) {
                readSse(input.bufferedReader(UTF_8).lineSequence(), listener, handle)
            } else {
                val text = input.readBytes().toString(UTF_8)
                if (handle.cancelled) return
                if (text.trimStart().startsWith("data:")) readSse(text.lineSequence(), listener, handle)
                else readJson(text, listener)
            }
        }
    }

    /** OpenAI-style chunks: `data: {...choices[0].delta.content...}` ... `data: [DONE]`. */
    private fun readSse(lines: Sequence<String>, listener: Listener, handle: Handle) {
        val full = StringBuilder()
        var usage: JsonObject? = null
        val calls = ToolCallAccumulator()
        try {
            for (line in lines) {
                if (handle.cancelled) return
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val obj = try {
                    JsonParser.parseString(payload).asJsonObject
                } catch (e: Exception) {
                    continue
                }
                obj.get("error")?.takeIf { !it.isJsonNull }?.let {
                    listener.onError(errorText(it))
                    return
                }
                (obj.get("usage") as? JsonObject)?.let { usage = it }
                val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                val deltaObj = choice.getAsJsonObject("delta")
                (deltaObj?.get("tool_calls") as? JsonArray)?.let { calls.add(it) }
                val delta = deltaObj?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                if (!delta.isNullOrEmpty()) {
                    full.append(delta)
                    listener.onDelta(delta)
                }
            }
        } catch (e: java.io.IOException) {
            if (handle.cancelled) return
            if (full.isEmpty() || !calls.isEmpty()) throw e
            // Connection dropped mid-answer: keep what we have.
            PluginLog.warn("api", "Stream interrupted after ${full.length} chars")
        }
        if (handle.cancelled) return
        finish(full.toString(), calls.result(), usage, listener)
    }

    private fun readJson(text: String, listener: Listener) {
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            listener.onError(I18n.tr("The server returned an unreadable reply.", "پاسخ سرور قابل خواندن نبود."))
            return
        }
        obj.get("error")?.takeIf { !it.isJsonNull }?.let {
            listener.onError(errorText(it))
            return
        }
        val message = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
        val content = message?.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val calls = ToolCallAccumulator()
        (message?.get("tool_calls") as? JsonArray)?.let { calls.add(it) }
        if (content.isNotEmpty()) listener.onDelta(content)
        finish(content, calls.result(), obj.get("usage") as? JsonObject, listener)
    }

    private fun finish(text: String, toolCalls: List<ToolCall>, usage: JsonObject?, listener: Listener) {
        if (text.isEmpty() && toolCalls.isEmpty()) {
            listener.onError(I18n.tr("The model returned an empty reply.", "مدل پاسخ خالی برگرداند."))
        } else {
            listener.onDone(Reply(text, toolCalls, usage))
        }
    }

    /** Joins tool-call fragments: streams send `id`/`name` once and `arguments` in pieces, keyed by `index`. */
    private class ToolCallAccumulator {
        private class Part(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

        private val parts = sortedMapOf<Int, Part>()

        fun isEmpty() = parts.isEmpty()

        fun add(arr: JsonArray) {
            arr.forEachIndexed { pos, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val idx = o.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: pos
                val p = parts.getOrPut(idx) { Part() }
                o.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }?.let { p.id = it }
                val fn = o.getAsJsonObject("function") ?: return@forEachIndexed
                fn.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }?.let { p.name = it }
                fn.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString?.let { p.args.append(it) }
            }
        }

        fun result(): List<ToolCall> = parts.entries.filter { it.value.name.isNotEmpty() }.map { (i, p) ->
            ToolCall(p.id.ifEmpty { "call_${System.nanoTime()}_$i" }, p.name, p.args.toString())
        }
    }

    private fun errorText(e: JsonElement): String =
        if (e.isJsonObject) e.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        else if (e.isJsonPrimitive) e.asString else ""

    /** `error.message` (OpenAI style), `message` or `detail` from an error body; blank if none. */
    private fun extractError(body: String): String {
        val obj = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return ""
        }
        obj.get("error")?.takeIf { !it.isJsonNull }?.let { errorText(it).takeIf { t -> t.isNotBlank() }?.let { t -> return t } }
        for (k in listOf("message", "detail")) {
            obj.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    /** Body text without HTML tags and extra whitespace (error pages from a firewall or proxy are HTML). */
    private fun plainText(body: String): String =
        body.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun httpError(status: Int, serverMessage: String, blocked: Boolean = false): String {
        val base = when {
            status == 402 -> I18n.tr(
                "Insufficient GapGPT account credit (HTTP 402). Top up at gapgpt.app and try again.",
                "اعتبار حساب گپ‌جی‌پی‌تی کافی نیست (HTTP 402). از gapgpt.app حساب را شارژ کنید و دوباره تلاش کنید.",
            )
            blocked -> I18n.tr(
                "The request was blocked before reaching the API (HTTP 403, HTML page). A firewall or proxy may have rejected it.",
                "درخواست پیش از رسیدن به API مسدود شد (HTTP 403، صفحهٔ HTML). احتمالاً فایروال یا پراکسی آن را رد کرده است.",
            )
            status == 401 -> I18n.tr("The API key is invalid or missing (HTTP 401).", "کلید API نامعتبر است یا وجود ندارد (HTTP 401).")
            status == 403 -> I18n.tr(
                "Access denied (HTTP 403): the key may be invalid or have no access to this model.",
                "دسترسی رد شد (HTTP 403): ممکن است کلید نامعتبر باشد یا به این مدل دسترسی نداشته باشد.",
            )
            else -> when (status) {
                404 -> I18n.tr("Endpoint or model not found (HTTP 404). Check the base URL and model name.", "نشانی یا مدل پیدا نشد (HTTP 404). نشانی پایه و نام مدل را بررسی کنید.")
                400, 422 -> I18n.tr("The request was rejected (HTTP $status).", "درخواست پذیرفته نشد (HTTP $status).")
                429 -> I18n.tr("Too many requests or quota exceeded (HTTP 429). Try again later.", "تعداد درخواست‌ها زیاد است یا سهمیه تمام شده (HTTP 429). بعداً دوباره تلاش کنید.")
                in 500..599 -> I18n.tr("The server had an error (HTTP $status). Try again later.", "سرور خطا داد (HTTP $status). بعداً دوباره تلاش کنید.")
                else -> I18n.tr("Unexpected reply from the server (HTTP $status).", "پاسخ غیرمنتظره از سرور (HTTP $status).")
            }
        }
        val detail = serverMessage.trim().take(400)
        return if (detail.isEmpty()) base else "$base\n$detail"
    }

    private fun describe(e: Throwable): String = when (e) {
        is CancellationException -> I18n.tr("Cancelled.", "لغو شد.")
        is java.net.http.HttpConnectTimeoutException, is java.net.http.HttpTimeoutException ->
            I18n.tr("The connection timed out.", "زمان اتصال به پایان رسید.")
        is java.net.ConnectException, is java.nio.channels.UnresolvedAddressException, is java.net.UnknownHostException ->
            I18n.tr("Could not connect to the server. Check your network or proxy.", "اتصال به سرور برقرار نشد. شبکه یا پراکسی را بررسی کنید.")
        else -> I18n.tr("Network error: ", "خطای شبکه: ") + (e.message ?: e.javaClass.simpleName)
    }
}
