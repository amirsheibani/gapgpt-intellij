package ir.gapgpt.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import ir.gapgpt.PluginLog
import ir.gapgpt.agent.AgentTools
import ir.gapgpt.chat.ToolCall
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Runs the project's MCP servers and exposes their tools to the chat agent as `mcp__<server>__<tool>`.
 * Servers start on the first agent request and are restarted when the config files change.
 */
@Service(Service.Level.PROJECT)
class McpManager(private val project: Project) : Disposable {

    class Server(val config: McpServerConfig) {
        @Volatile
        var client: McpClient? = null

        @Volatile
        var tools: List<McpTool> = emptyList()

        /** "ready", "starting", "failed", "disabled", "not trusted". */
        @Volatile
        var status: String = "starting"

        @Volatile
        var error: String? = null
    }

    private val servers = LinkedHashMap<String, Server>()
    private var stamp: String? = null
    private val rejected = HashSet<String>()
    private val toolIndex = ConcurrentHashMap<String, Pair<Server, McpTool>>()
    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    private var disposed = false

    private fun trusted(): MutableSet<String> =
        PropertiesComponent.getInstance(project).getList(TRUST_KEY)?.toMutableSet() ?: mutableSetOf()

    /**
     * Starts or reloads the configured servers (blocking; the first start can take a while, e.g. `npx` downloads).
     * [askTrust] is called for project-defined servers that were never approved and returns whether to run them.
     * Returns messages about servers that failed now, for the chat.
     */
    @Synchronized
    fun ensureStarted(askTrust: (List<McpServerConfig>) -> Boolean): List<String> {
        if (disposed) return emptyList()
        val base = project.basePath
        val newStamp = McpConfig.stamp(base)
        // Nothing changed and no running server died: keep everything as is.
        if (newStamp == stamp && servers.values.none { it.status == "ready" && it.client?.alive != true }) return emptyList()
        stamp = newStamp

        val configs = McpConfig.load(base)
        // Stop servers that were removed or changed.
        val wanted = configs.associateBy { it.name }
        servers.entries.removeIf { (name, srv) ->
            val keep = wanted[name]?.signature == srv.config.signature && srv.client?.alive == true
            if (!keep) srv.client?.let { runCatching { it.close() } }
            !keep
        }

        val trustedSet = trusted()
        val ask = configs.filter { !it.disabled && !it.trusted && it.signature !in trustedSet && it.signature !in rejected && it.name !in servers }
        if (ask.isNotEmpty()) {
            if (askTrust(ask)) {
                trustedSet += ask.map { it.signature }
                PropertiesComponent.getInstance(project).setList(TRUST_KEY, trustedSet)
            } else {
                rejected += ask.map { it.signature }
            }
        }

        val toStart = ArrayList<Server>()
        for (c in configs) {
            if (c.name in servers) continue
            val srv = Server(c)
            servers[c.name] = srv
            when {
                c.disabled -> srv.status = "disabled"
                !c.trusted && c.signature !in trustedSet -> srv.status = "not trusted"
                else -> toStart += srv
            }
        }

        val futures: List<Pair<Server, Future<*>>> = toStart.map { srv ->
            srv to ApplicationManager.getApplication().executeOnPooledThread(Callable { start(srv) })
        }
        val failures = ArrayList<String>()
        for ((srv, f) in futures) {
            try {
                f.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                f.cancel(true)
                srv.client?.let { runCatching { it.close() } }
                srv.client = null
                srv.status = "failed"
                srv.error = srv.error ?: "did not start within ${START_TIMEOUT_MS / 1000}s"
            }
            if (srv.status == "failed") failures += "${srv.config.name}: ${srv.error}"
        }
        rebuildIndex()
        if (configs.isNotEmpty()) {
            PluginLog.info("mcp", servers.values.joinToString { "${it.config.name}=${it.status}(${it.tools.size})" })
        }
        return failures
    }

    private fun start(srv: Server) {
        val c = srv.config
        try {
            val client = if (c.isHttp) {
                HttpMcpClient(c.name, c.url!!, c.headers)
            } else {
                val env = HashMap(runCatching { EnvironmentUtil.getEnvironmentMap() }.getOrDefault(System.getenv()))
                env.putAll(c.env)
                val root = File(project.basePath ?: System.getProperty("user.home"))
                val cwd = c.cwd?.let { File(it).let { f -> if (f.isAbsolute) f else File(root, it) } } ?: root
                StdioMcpClient(c.name, c.command!!, c.args, env, cwd)
            }
            srv.client = client
            client.initialize(START_TIMEOUT_MS)
            srv.tools = client.listTools(START_TIMEOUT_MS)
            srv.status = "ready"
            srv.error = null
        } catch (e: Exception) {
            srv.status = "failed"
            srv.error = e.message ?: e.javaClass.simpleName
            srv.client?.let { runCatching { it.close() } }
            srv.client = null
            PluginLog.warn("mcp", "${c.name} failed: ${srv.error}")
        }
    }

    private fun rebuildIndex() {
        toolIndex.clear()
        for (srv in servers.values) {
            if (srv.status != "ready") continue
            for (t in srv.tools) {
                var name = ("mcp__" + sanitize(srv.config.name) + "__" + sanitize(t.name)).take(64)
                var n = 2
                while (toolIndex.containsKey(name)) name = name.take(60) + "_" + n++
                toolIndex[name] = srv to t
            }
        }
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun isMcpTool(name: String) = toolIndex.containsKey(name)

    /** Function definitions for the model; in plan mode only tools that declare themselves read-only. */
    fun definitions(readOnlyOnly: Boolean): JsonArray = JsonArray().apply {
        for ((name, pair) in toolIndex.entries.sortedBy { it.key }) {
            val (srv, t) = pair
            if (readOnlyOnly && !t.readOnly) continue
            val schema = t.inputSchema.deepCopy().apply {
                remove("\$schema")
                if (!has("type")) addProperty("type", "object")
                if (!has("properties")) add("properties", JsonObject())
            }
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("description", "[MCP server ${srv.config.name}] ${t.description}".take(1024))
                    add("parameters", schema)
                })
            })
        }
    }

    fun isReadOnly(name: String) = toolIndex[name]?.second?.readOnly == true

    fun prepare(call: ToolCall): AgentTools.Prepared {
        val (srv, tool) = toolIndex[call.name]
            ?: return AgentTools.Prepared(call, AgentTools.Kind.MCP, call.name, "", null, approvalKey = null) {
                AgentTools.Result(false, "Error: unknown or stopped MCP tool ${call.name}", "Unknown MCP tool")
            }
        val args = try {
            JsonParser.parseString(call.arguments.ifBlank { "{}" }).asJsonObject
        } catch (e: Exception) {
            return AgentTools.Prepared(call, AgentTools.Kind.MCP, "${srv.config.name} · ${tool.name}", "", null, approvalKey = null) {
                AgentTools.Result(false, "Error: invalid JSON arguments: ${e.message}", "Invalid arguments")
            }
        }
        val compact = args.toString().let { if (it.length > 120) it.take(117) + "…" else it }
        val preview = "MCP: ${srv.config.name} › ${tool.name}\n" + pretty.toJson(args).let { if (it.length > 3000) it.take(3000) + "\n…" else it }
        return AgentTools.Prepared(
            call, AgentTools.Kind.MCP, "${srv.config.name} · ${tool.name}", compact, preview,
            approvalKey = if (tool.readOnly) null else "mcp:${srv.config.name}",
        ) { ctx ->
            val client = srv.client
            if (client == null || !client.alive) {
                AgentTools.Result(false, "Error: MCP server ${srv.config.name} is not running.", "Server not running")
            } else try {
                val r = client.callTool(tool.name, args, CALL_TIMEOUT_MS) { ctx.cancelled }
                val text = r.text.ifBlank { "(no output)" }
                val lines = text.lines()
                AgentTools.Result(
                    !r.isError, if (r.isError) "Error: $text" else text,
                    (if (r.isError) "Error: " else "") + lines.first().take(100),
                    lines.take(8).joinToString("\n").takeIf { lines.size > 1 },
                )
            } catch (e: McpException) {
                AgentTools.Result(false, "Error: ${e.message}", e.message ?: "failed")
            }
        }
    }

    /** One line per server, for `/mcp`. */
    @Synchronized
    fun statusLines(): List<String> = servers.values.map { s ->
        val c = s.config
        val what = if (c.isHttp) c.url else c.command
        "${c.name} [${c.source}] — ${s.status}" +
            (if (s.status == "ready") " · ${s.tools.size} tools" + (s.client?.serverInfo?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else "") +
            (s.error?.let { " · $it" } ?: "") + " · $what"
    }

    /** Forgets running servers and rejections; the next request starts them again. */
    @Synchronized
    fun reload() {
        servers.values.forEach { s -> s.client?.let { runCatching { it.close() } } }
        servers.clear()
        toolIndex.clear()
        rejected.clear()
        stamp = null
    }

    /** Not synchronized: [ensureStarted] may hold the lock while it waits for the trust dialog on the EDT. */
    override fun dispose() {
        disposed = true
        val running = runCatching { servers.values.toList() }.getOrDefault(emptyList())
        running.forEach { s -> s.client?.let { runCatching { it.close() } } }
        toolIndex.clear()
    }

    companion object {
        private const val TRUST_KEY = "gapgpt.mcp.trusted"
        private const val START_TIMEOUT_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 300_000L

        fun getInstance(project: Project): McpManager = project.getService(McpManager::class.java)
    }
}
