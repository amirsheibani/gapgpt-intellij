package ir.gapgpt.ide

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A tiny MCP server (Streamable HTTP transport) running inside the IDE, one per project.
 *
 * The Claude CLI started by the chat panel connects to it via `--mcp-config`, which gives Claude
 * IDE tools (diagnostics, open editors, selection, open file) and lets the IDE answer permission
 * prompts (`--permission-prompt-tool`), showing proposed edits in the IDE diff viewer.
 *
 * Bound to 127.0.0.1 only and protected by a random bearer token.
 */
@Service(Service.Level.PROJECT)
class IdeMcpServer(private val project: Project) : Disposable {

    private val gson = Gson()
    private val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val configFiles = java.util.concurrent.ConcurrentHashMap<String, File>()

    private val tools: IdeTools by lazy { IdeTools(project) }

    /**
     * Starts the server if needed and returns the path of an `--mcp-config` JSON file for [chatId]
     * (each chat gets its own URL so permission prompts reach the right chat), or null on failure.
     */
    @Synchronized
    fun ensureStarted(chatId: String): String? {
        configFiles[chatId]?.takeIf { server != null && it.exists() }?.let { return it.absolutePath }
        return try {
            if (server == null) start()
            writeConfig(chatId).absolutePath
        } catch (e: Exception) {
            LOG.warn("Could not start GapGPT IDE MCP server", e)
            ir.gapgpt.PluginLog.warn("mcp", "Could not start IDE MCP server: ${e.message}")
            null
        }
    }

    fun forgetChat(chatId: String) {
        configFiles.remove(chatId)?.delete()
    }

    private fun start() {
        val exec = Executors.newCachedThreadPool { r ->
            Thread(r, "GapGpt-MCP").apply { isDaemon = true }
        }
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/mcp") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                LOG.warn("MCP request failed", e)
                runCatching { ex.sendResponseHeaders(500, -1) }
            } finally {
                ex.close()
            }
        }
        s.executor = exec
        s.start()
        server = s
        executor = exec
        LOG.info("GapGPT IDE MCP server listening on 127.0.0.1:${s.address.port}")
        ir.gapgpt.PluginLog.info("mcp", "IDE MCP server listening on 127.0.0.1:${s.address.port}")
    }

    private fun writeConfig(chatId: String): File {
        val port = server!!.address.port
        val cfg = JsonObject().apply {
            add("mcpServers", JsonObject().apply {
                add(SERVER_NAME, JsonObject().apply {
                    addProperty("type", "http")
                    addProperty("url", "http://127.0.0.1:$port/mcp/$chatId")
                    add("headers", JsonObject().apply { addProperty("Authorization", "Bearer $token") })
                })
            })
        }
        val f = configFiles[chatId] ?: File.createTempFile("gapgpt-mcp-", ".json").also {
            it.deleteOnExit()
            runCatching {
                Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
        f.writeText(gson.toJson(cfg), UTF_8)
        configFiles[chatId] = f
        return f
    }

    // ------------------------------------------------------------------ HTTP / JSON-RPC

    private fun handle(ex: HttpExchange) {
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $token") {
            ex.sendResponseHeaders(401, -1)
            return
        }
        when (ex.requestMethod) {
            "POST" -> Unit
            "DELETE" -> { ex.sendResponseHeaders(200, -1); return }
            else -> { ex.sendResponseHeaders(405, -1); return } // no server-initiated SSE stream
        }

        val chatId = ex.requestURI.path.removePrefix("/mcp").trim('/').ifBlank { "default" }
        val body = ex.requestBody.readBytes().toString(UTF_8)
        val msg = JsonParser.parseString(body)
        if (msg.isJsonArray) {
            // Batches are rare; answer them synchronously as a JSON array.
            val out = JsonArray()
            msg.asJsonArray.forEach { m -> dispatch(m.asJsonObject, chatId)?.let { out.add(it) } }
            if (out.isEmpty) ex.sendResponseHeaders(202, -1) else sendJson(ex, out)
            return
        }

        val req = msg.asJsonObject
        if (!req.has("id")) { // notification
            ex.sendResponseHeaders(202, -1)
            return
        }
        if (req.get("method")?.asString == "tools/call") {
            // Tool calls (especially permission prompts) can block for a long time waiting for the user.
            // Answer as an SSE stream with keep-alive comments so no HTTP timeout kicks in.
            sendSse(ex) { dispatch(req, chatId) }
        } else {
            sendJson(ex, dispatch(req, chatId) ?: JsonNull.INSTANCE)
        }
    }

    private fun sendJson(ex: HttpExchange, json: JsonElement) {
        val bytes = gson.toJson(json).toByteArray(UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendSse(ex: HttpExchange, compute: () -> JsonObject?) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val future: Future<JsonObject?> = executor!!.submit<JsonObject?> { compute() }
        try {
            while (true) {
                try {
                    val res = future.get(15, TimeUnit.SECONDS)
                    if (res != null) {
                        out.write("event: message\ndata: ${gson.toJson(res)}\n\n".toByteArray(UTF_8))
                    }
                    out.flush()
                    break
                } catch (_: TimeoutException) {
                    out.write(": keep-alive\n\n".toByteArray(UTF_8))
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // Client went away (e.g. user pressed Stop): abort the pending tool call.
            future.cancel(true)
            LOG.debug("SSE stream closed", e)
        } finally {
            runCatching { out.close() }
        }
    }

    private fun dispatch(req: JsonObject, chatId: String): JsonObject? {
        val id = req.get("id") ?: return null
        val method = req.get("method")?.asString.orEmpty()
        val params = req.getAsJsonObject("params") ?: JsonObject()
        return try {
            val result: JsonElement = when (method) {
                "initialize" -> JsonObject().apply {
                    addProperty("protocolVersion", params.get("protocolVersion")?.asString ?: "2025-06-18")
                    add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                    add("serverInfo", JsonObject().apply {
                        addProperty("name", SERVER_NAME)
                        addProperty("version", "1.0")
                    })
                    addProperty(
                        "instructions",
                        "Tools of the JetBrains IDE the user is working in. Use getDiagnostics to check for " +
                            "compile errors/warnings after editing files, and getCurrentSelection/getOpenEditors " +
                            "to see what the user is looking at.",
                    )
                }
                "ping" -> JsonObject()
                "tools/list" -> JsonObject().apply { add("tools", tools.definitions()) }
                "tools/call" -> {
                    val name = params.get("name")?.asString.orEmpty()
                    val args = params.getAsJsonObject("arguments") ?: JsonObject()
                    val text = if (name == PERMISSION_TOOL) {
                        PermissionBroker.getInstance(project).decide(args, chatId)
                    } else {
                        tools.call(name, args)
                    }
                    JsonObject().apply {
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", text)
                            })
                        })
                    }
                }
                else -> return error(id, -32601, "Method not found: $method")
            }
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", id)
                add("result", result)
            }
        } catch (e: InterruptedException) {
            error(id, -32000, "Cancelled")
        } catch (e: Exception) {
            LOG.warn("MCP $method failed", e)
            error(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun error(id: JsonElement, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    override fun dispose() {
        server?.stop(0)
        executor?.shutdownNow()
        configFiles.values.forEach { it.delete() }
        server = null
    }

    companion object {
        private val LOG = logger<IdeMcpServer>()

        const val SERVER_NAME = "gapgpt"
        const val PERMISSION_TOOL = "permission_prompt"
        const val PERMISSION_TOOL_FQN = "mcp__${SERVER_NAME}__$PERMISSION_TOOL"

        fun getInstance(project: Project): IdeMcpServer = project.getService(IdeMcpServer::class.java)
    }
}
