package ir.gapgpt.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import ir.gapgpt.PluginLog
import java.io.Closeable
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class McpException(message: String) : Exception(message)

/** A tool offered by an MCP server. */
class McpTool(val name: String, val description: String, val inputSchema: JsonObject, val readOnly: Boolean)

/** Text of a `tools/call` result and whether the server marked it as an error. */
class McpCallResult(val text: String, val isError: Boolean)

/** JSON-RPC 2.0 client for the Model Context Protocol; [StdioMcpClient] and [HttpMcpClient] are the transports. */
abstract class McpClient(val serverName: String) : Closeable {

    protected val gson = Gson()
    protected val nextId = AtomicLong(1)

    @Volatile
    var serverInfo: String = ""
        private set

    abstract val alive: Boolean

    /** Sends a request and waits for its result; throws [McpException] on errors, timeout or [cancelled]. */
    protected abstract fun rpc(method: String, params: JsonObject?, timeoutMs: Long, cancelled: () -> Boolean): JsonElement

    protected abstract fun notifyServer(method: String, params: JsonObject?)

    protected open fun onInitialized(protocolVersion: String) = Unit

    protected fun requestJson(id: Long, method: String, params: JsonObject?) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("id", id)
        addProperty("method", method)
        params?.let { add("params", it) }
    }

    protected fun notificationJson(method: String, params: JsonObject?) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("method", method)
        params?.let { add("params", it) }
    }

    /** The result of a JSON-RPC response object, or [McpException] for an `error`. */
    protected fun resultOf(response: JsonObject): JsonElement {
        (response.get("error") as? JsonObject)?.let { e ->
            val msg = e.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "error"
            throw McpException(msg)
        }
        return response.get("result") ?: JsonObject()
    }

    fun initialize(timeoutMs: Long) {
        val result = rpc("initialize", JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "GapGPT")
                addProperty("version", "0.1")
            })
        }, timeoutMs) { false } as? JsonObject ?: throw McpException("invalid initialize result")
        val info = result.getAsJsonObject("serverInfo")
        serverInfo = listOfNotNull(info?.get("name")?.asString, info?.get("version")?.asString).joinToString(" ")
        onInitialized(result.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString ?: PROTOCOL_VERSION)
        notifyServer("notifications/initialized", null)
    }

    fun listTools(timeoutMs: Long): List<McpTool> {
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val r = rpc("tools/list", cursor?.let { c -> JsonObject().apply { addProperty("cursor", c) } }, timeoutMs) { false } as? JsonObject
                ?: return out
            (r.get("tools") as? JsonArray)?.forEach { el ->
                val t = el as? JsonObject ?: return@forEach
                val name = t.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val schema = (t.get("inputSchema") as? JsonObject)?.deepCopy() ?: JsonObject()
                val readOnly = t.getAsJsonObject("annotations")?.get("readOnlyHint")?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                out += McpTool(name, t.get("description")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(), schema, readOnly)
            }
            cursor = r.get("nextCursor")?.takeIf { it.isJsonPrimitive }?.asString
            if (cursor.isNullOrEmpty()) return out
        }
        return out
    }

    fun callTool(name: String, arguments: JsonObject, timeoutMs: Long, cancelled: () -> Boolean): McpCallResult {
        val r = rpc("tools/call", JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }, timeoutMs, cancelled) as? JsonObject ?: return McpCallResult("", false)
        val isError = r.get("isError")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
        val parts = ArrayList<String>()
        (r.get("content") as? JsonArray)?.forEach { el ->
            val c = el as? JsonObject ?: return@forEach
            fun s(o: JsonObject?, k: String) = o?.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            when (s(c, "type")) {
                "text" -> s(c, "text")?.let { parts += it }
                "image", "audio" -> parts += "[${s(c, "type")} ${s(c, "mimeType") ?: ""} omitted]"
                "resource" -> c.getAsJsonObject("resource")?.let { res -> parts += s(res, "text") ?: "[resource ${s(res, "uri") ?: ""}]" }
                "resource_link" -> parts += "[resource ${s(c, "uri") ?: ""}] ${s(c, "name") ?: ""}".trim()
                else -> parts += gson.toJson(c)
            }
        }
        if (parts.isEmpty()) r.get("structuredContent")?.let { parts += gson.toJson(it) }
        return McpCallResult(parts.joinToString("\n"), isError)
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_PAGES = 20
    }
}

// ==================================================================== stdio

/** A local MCP server process speaking newline-delimited JSON-RPC on stdin/stdout. */
class StdioMcpClient(
    serverName: String,
    command: String,
    args: List<String>,
    env: Map<String, String>,
    cwd: File,
) : McpClient(serverName) {

    private val process: Process
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val stderrTail = ArrayDeque<String>()

    @Volatile
    private var exited = false

    override val alive get() = !exited && process.isAlive

    init {
        val fullEnv = HashMap(env)
        val cmd = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command) + args
        else listOf(resolve(command, fullEnv["PATH"] ?: System.getenv("PATH").orEmpty())) + args
        val pb = ProcessBuilder(cmd).directory(cwd)
        pb.environment().putAll(fullEnv)
        process = try {
            pb.start()
        } catch (e: Exception) {
            throw McpException("cannot start '$command': ${e.message}")
        }
        thread("stdout") { readLoop() }
        thread("stderr") {
            process.errorStream.bufferedReader(UTF_8).forEachLine { line ->
                PluginLog.debug("mcp", "[$serverName] $line")
                synchronized(stderrTail) {
                    stderrTail.addLast(line.take(300))
                    while (stderrTail.size > 20) stderrTail.removeFirst()
                }
            }
        }
    }

    private fun thread(kind: String, body: () -> Unit) = Thread {
        try {
            body()
        } catch (_: Exception) {
        }
    }.apply {
        isDaemon = true
        name = "GapGPT MCP $serverName $kind"
        start()
    }

    private fun readLoop() {
        process.inputStream.bufferedReader(UTF_8).forEachLine { line ->
            val text = line.trim()
            if (!text.startsWith("{")) return@forEachLine
            val msg = try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                return@forEachLine
            }
            val id = msg.get("id")
            val method = msg.get("method")?.takeIf { it.isJsonPrimitive }?.asString
            when {
                method != null && id != null -> answerServerRequest(id, method)
                method == null && id != null -> runCatching { id.asLong }.getOrNull()?.let { pending.remove(it)?.complete(msg) }
                else -> Unit // notification
            }
        }
        exited = true
        val why = McpException("server exited" + lastError().let { if (it.isEmpty()) "" else ": $it" })
        pending.values.forEach { it.completeExceptionally(why) }
        pending.clear()
    }

    /** We offer no client capabilities: answer ping, refuse everything else. */
    private fun answerServerRequest(id: JsonElement, method: String) {
        val reply = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            if (method == "ping") add("result", JsonObject())
            else add("error", JsonObject().apply {
                addProperty("code", -32601)
                addProperty("message", "Method not supported by GapGPT: $method")
            })
        }
        write(reply)
    }

    private fun write(o: JsonObject) {
        synchronized(writeLock) {
            val out = process.outputStream
            out.write((gson.toJson(o) + "\n").toByteArray(UTF_8))
            out.flush()
        }
    }

    fun lastError(): String = synchronized(stderrTail) { stderrTail.takeLast(3).joinToString(" | ") }

    override fun rpc(method: String, params: JsonObject?, timeoutMs: Long, cancelled: () -> Boolean): JsonElement {
        if (!alive) throw McpException("server is not running" + lastError().let { if (it.isEmpty()) "" else ": $it" })
        val id = nextId.getAndIncrement()
        val f = CompletableFuture<JsonObject>()
        pending[id] = f
        try {
            write(requestJson(id, method, params))
        } catch (e: Exception) {
            pending.remove(id)
            throw McpException("cannot write to server: ${e.message}")
        }
        return resultOf(await(f, timeoutMs, cancelled) { pending.remove(id) })
    }

    override fun notifyServer(method: String, params: JsonObject?) {
        runCatching { write(notificationJson(method, params)) }
    }

    override fun close() {
        exited = true
        runCatching { process.outputStream.close() }
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    companion object {
        /** Finds [command] on the user's PATH (the IDE's own PATH may be minimal when started from the Dock). */
        fun resolve(command: String, path: String): String {
            if (command.contains('/') || command.contains(File.separatorChar)) return command
            for (dir in path.split(File.pathSeparatorChar) + listOf("/opt/homebrew/bin", "/usr/local/bin", System.getProperty("user.home") + "/.local/bin")) {
                val f = File(dir, command)
                if (f.isFile && f.canExecute()) return f.path
            }
            return command
        }
    }
}

/** Waits for [f] in short slices so [cancelled] is honoured; [cleanup] runs on timeout or cancel. */
internal fun <T> await(f: CompletableFuture<T>, timeoutMs: Long, cancelled: () -> Boolean, cleanup: () -> Unit): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (cancelled()) {
            cleanup()
            f.cancel(true)
            throw McpException("cancelled")
        }
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) {
            cleanup()
            f.cancel(true)
            throw McpException("timed out after ${timeoutMs / 1000}s")
        }
        try {
            return f.get(minOf(left, 250L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
        } catch (e: ExecutionException) {
            val c = e.cause
            throw c as? McpException ?: McpException(c?.message ?: "request failed")
        }
    }
}

// ==================================================================== streamable HTTP

/** A remote MCP server using the streamable HTTP transport (JSON or SSE replies, `Mcp-Session-Id`). */
class HttpMcpClient(serverName: String, url: String, private val headers: Map<String, String>) : McpClient(serverName) {

    private val uri: URI = try {
        URI(url).also { require(it.scheme == "http" || it.scheme == "https") }
    } catch (e: Exception) {
        throw McpException("invalid url: $url")
    }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var protocol: String? = null

    @Volatile
    private var closed = false

    override val alive get() = !closed

    override fun onInitialized(protocolVersion: String) {
        protocol = protocolVersion
    }

    private fun request(body: JsonObject, timeoutMs: Long): HttpRequest {
        val b = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), UTF_8))
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        protocol?.let { b.header("MCP-Protocol-Version", it) }
        return b.build()
    }

    override fun rpc(method: String, params: JsonObject?, timeoutMs: Long, cancelled: () -> Boolean): JsonElement {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture.supplyAsync {
            val resp = http.send(request(requestJson(id, method, params), timeoutMs), HttpResponse.BodyHandlers.ofInputStream())
            resp.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
            resp.body().use { input ->
                val status = resp.statusCode()
                if (status !in 200..299) {
                    val text = String(input.readNBytes(2000), UTF_8)
                    throw McpException("HTTP $status" + if (text.isNotBlank()) ": ${text.take(200)}" else "")
                }
                val type = resp.headers().firstValue("Content-Type").orElse("")
                if (type.contains("event-stream")) readSse(input.bufferedReader(UTF_8).lineSequence(), id)
                else findResponse(JsonParser.parseString(String(input.readAllBytes(), UTF_8)), id)
                    ?: throw McpException("no response for request $id")
            }
        }
        return resultOf(await(future, timeoutMs, cancelled) {})
    }

    private fun findResponse(el: JsonElement, id: Long): JsonObject? = when {
        el is JsonObject && el.get("id")?.let { runCatching { it.asLong }.getOrNull() } == id -> el
        el is JsonArray -> el.firstNotNullOfOrNull { findResponse(it, id) }
        else -> null
    }

    private fun readSse(lines: Sequence<String>, id: Long): JsonObject {
        val data = StringBuilder()
        for (line in lines) {
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    runCatching { findResponse(JsonParser.parseString(data.toString()), id) }.getOrNull()?.let { return it }
                    data.setLength(0)
                }
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
        }
        if (data.isNotEmpty()) runCatching { findResponse(JsonParser.parseString(data.toString()), id) }.getOrNull()?.let { return it }
        throw McpException("stream ended without a response")
    }

    override fun notifyServer(method: String, params: JsonObject?) {
        runCatching { http.send(request(notificationJson(method, params), 15_000), HttpResponse.BodyHandlers.discarding()) }
    }

    override fun close() {
        closed = true
        val sid = sessionId ?: return
        runCatching {
            val b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Mcp-Session-Id", sid).DELETE()
            headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
            http.sendAsync(b.build(), HttpResponse.BodyHandlers.discarding())
        }
    }
}
