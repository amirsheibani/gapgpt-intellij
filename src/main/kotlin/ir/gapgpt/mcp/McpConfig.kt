package ir.gapgpt.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.EnvironmentUtil
import ir.gapgpt.PluginLog
import java.io.File
import java.security.MessageDigest

/** One configured MCP server: a local process (stdio) or a remote endpoint (streamable HTTP). */
data class McpServerConfig(
    val name: String,
    /** Where it was defined, e.g. "~/.gapgpt/mcp.json" or ".mcp.json". */
    val source: String,
    /** User-level servers are trusted; project-level ones need the user's OK once (they run local commands). */
    val trusted: Boolean,
    val command: String?,
    val args: List<String>,
    val env: Map<String, String>,
    val cwd: String?,
    val url: String?,
    val headers: Map<String, String>,
    val disabled: Boolean,
) {
    val isHttp get() = url != null

    /** Changes whenever the definition changes, so a changed server is asked about again. */
    val signature: String by lazy {
        val text = listOf(name, command, args.joinToString("\u0000"), env.toSortedMap().toString(), cwd, url, headers.toSortedMap().toString())
            .joinToString("\u0001")
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    /** What the user sees when asked to trust it (no header or env values: they may hold secrets). */
    fun describe(): String = if (isHttp) "$name: $url" else "$name: " + (listOfNotNull(command) + args).joinToString(" ")
}

/**
 * Reads MCP server definitions in the format of Claude Code / Claude Desktop:
 * `{"mcpServers": {"name": {"command": "npx", "args": [...], "env": {...}}, "web": {"type": "http", "url": "...", "headers": {...}}}}`.
 * Files (the project one overrides servers with the same name): `~/.gapgpt/mcp.json`, `<project>/.gapgpt/mcp.json`.
 * Other tools' configs (e.g. `<project>/.mcp.json`) are never read, so their servers are not started twice. `${VAR}` and `${VAR:-default}` are expanded from the environment.
 */
object McpConfig {

    private fun files(projectDir: String?): List<Triple<File, String, Boolean>> = buildList {
        add(Triple(File(System.getProperty("user.home") ?: "", ".gapgpt/mcp.json"), "~/.gapgpt/mcp.json", true))
        if (!projectDir.isNullOrBlank()) {
            add(Triple(File(projectDir, ".gapgpt/mcp.json"), ".gapgpt/mcp.json", false))
        }
    }

    /** Cheap fingerprint of the config files (to notice edits). */
    fun stamp(projectDir: String?): String =
        files(projectDir).joinToString("|") { (f, _, _) -> if (f.isFile) "${f.lastModified()}:${f.length()}" else "-" }

    fun load(projectDir: String?): List<McpServerConfig> {
        val env = runCatching { EnvironmentUtil.getEnvironmentMap() }.getOrDefault(System.getenv())
        val byName = LinkedHashMap<String, McpServerConfig>()
        for ((file, label, trusted) in files(projectDir)) {
            if (!file.isFile) continue
            val root = try {
                JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                PluginLog.warn("mcp", "Invalid JSON in $label: ${e.message}")
                continue
            }
            val servers = (root.get("mcpServers") ?: root.get("servers")) as? JsonObject ?: continue
            for ((name, el) in servers.entrySet()) {
                val o = el as? JsonObject ?: continue
                fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.let { expand(it, env) }
                val type = s("type")?.lowercase()
                val url = s("url")?.takeIf { type == null || type == "http" || type == "streamable-http" || type == "sse" }
                val command = s("command")
                if (url == null && command.isNullOrBlank()) {
                    PluginLog.warn("mcp", "Server '$name' in $label has neither command nor url")
                    continue
                }
                byName[name] = McpServerConfig(
                    name = name,
                    source = label,
                    trusted = trusted,
                    command = if (url == null) command else null,
                    args = (o.get("args") as? JsonArray)?.mapNotNull { a -> a.takeIf { it.isJsonPrimitive }?.asString?.let { expand(it, env) } }.orEmpty(),
                    env = map(o.get("env") as? JsonObject, env),
                    cwd = s("cwd"),
                    url = url,
                    headers = map(o.get("headers") as? JsonObject, env),
                    disabled = o.get("disabled")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
                )
            }
        }
        return byName.values.toList()
    }

    private fun map(o: JsonObject?, env: Map<String, String>): Map<String, String> =
        o?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to expand(it, env) } }?.toMap().orEmpty()

    private val VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}""")

    private fun expand(s: String, env: Map<String, String>): String =
        VAR.replace(s) { m -> env[m.groupValues[1]] ?: System.getenv(m.groupValues[1]) ?: m.groupValues[2] }
}
