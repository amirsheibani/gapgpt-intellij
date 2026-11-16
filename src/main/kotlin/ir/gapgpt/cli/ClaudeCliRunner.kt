package ir.gapgpt.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import ir.gapgpt.PluginLog
import ir.gapgpt.api.ChatBackend
import ir.gapgpt.ide.IdeMcpServer
import ir.gapgpt.settings.GapGptSettings
import ir.gapgpt.settings.I18n
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Runs the Claude Code CLI in headless mode (`claude -p --output-format stream-json`)
 * and streams parsed JSON events back to [listener]. One process per user turn;
 * the conversation is continued with `--resume <session_id>`.
 */
class ClaudeCliRunner(
    private val workDir: String?,
    private val listener: Listener,
) : ChatBackend {
    interface Listener {
        fun onEvent(event: JsonObject)
        fun onStderr(line: String)
        fun onExit(exitCode: Int, gotResult: Boolean)
        fun onStartFailed(message: String)
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    override var sessionId: String? = null

    override val isRunning: Boolean get() = process?.isAlive == true

    override fun resetSession() {
        stop()
        sessionId = null
    }

    /**
     * @param mcpConfigPath `--mcp-config` file of the IDE MCP server; when set, permission prompts
     *   are routed to the IDE via `--permission-prompt-tool`.
     */
    override fun send(prompt: String, mcpConfigPath: String?) {
        if (isRunning) return
        val s = GapGptSettings.get()
        val exe = locateCli(s.cliPath)
        if (exe == null) {
            listener.onStartFailed(
                "فایل اجرایی claude پیدا نشد. ابتدا Claude Code را نصب کنید " +
                    "(npm install -g @anthropic-ai/claude-code) یا مسیر آن را در Settings › Tools › GapGPT وارد کنید."
            )
            return
        }

        val cmd = GeneralCommandLine(exe)
            .withCharset(UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        workDir?.let { cmd.withWorkDirectory(it) }

        cmd.addParameters("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages")
        if (s.permissionMode.isNotBlank() && s.permissionMode != "default" && s.permissionMode != "manual") {
            cmd.addParameters("--permission-mode", s.permissionMode)
        }
        if (mcpConfigPath != null) {
            // --mcp-config is variadic, so it must be followed by another flag.
            cmd.addParameters("--mcp-config", mcpConfigPath)
            cmd.addParameters("--permission-prompt-tool", IdeMcpServer.PERMISSION_TOOL_FQN)
        }
        if (s.model.isNotBlank()) cmd.addParameters("--model", s.model.trim())
        if (s.effort.isNotBlank()) cmd.addParameters("--effort", s.effort.trim())
        cmd.addParameters("--append-system-prompt", I18n.replyInstruction(s.replyLanguage))
        sessionId?.let { cmd.addParameters("--resume", it) }
        if (s.extraArgs.isNotBlank()) cmd.addParameters(ParametersListUtil.parse(s.extraArgs))

        if (s.httpsProxy.isNotBlank()) {
            cmd.environment["HTTPS_PROXY"] = s.httpsProxy.trim()
            cmd.environment["HTTP_PROXY"] = s.httpsProxy.trim()
        }
        cmd.environment["NO_COLOR"] = "1"
        cmd.environment["LANG"] = cmd.environment["LANG"] ?: "en_US.UTF-8"

        val p = try {
            cmd.createProcess()
        } catch (e: Exception) {
            LOG.warn(e)
            PluginLog.warn("process", "claude failed to start: ${e.message}")
            listener.onStartFailed("اجرای claude ناموفق بود: ${e.message}")
            return
        }
        process = p
        PluginLog.info("process", "claude started: ${exe.substringAfterLast('/')} (${cmd.parametersList.list.size} args)" +
            (sessionId?.let { " resume=${it.take(8)}" } ?: ""))

        val text = if (s.normalizeArabicChars) normalizePersian(prompt) else prompt

        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            try {
                p.outputStream.use { it.write(text.toByteArray(UTF_8)) }
            } catch (e: Exception) {
                LOG.warn("Failed to write prompt to claude stdin", e)
            }
        }
        app.executeOnPooledThread {
            p.errorStream.bufferedReader(UTF_8).useLines { lines ->
                lines.forEach {
                    if (it.isNotBlank()) {
                        PluginLog.debug("process", "stderr: $it")
                        listener.onStderr(it)
                    }
                }
            }
        }
        app.executeOnPooledThread {
            var gotResult = false
            try {
                p.inputStream.bufferedReader(UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("{")) return@forEach
                        val obj = try {
                            JsonParser.parseString(trimmed).asJsonObject
                        } catch (e: Exception) {
                            LOG.debug("Unparseable line: $trimmed")
                            return@forEach
                        }
                        obj.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString?.let { sessionId = it }
                        if (obj.get("type")?.asString == "result") gotResult = true
                        listener.onEvent(obj)
                    }
                }
            } catch (e: Exception) {
                LOG.debug(e)
            }
            val code = try { p.waitFor() } catch (e: InterruptedException) { -1 }
            if (process === p) process = null
            PluginLog.info("process", "claude exited with code $code" + if (gotResult) "" else " (no result)")
            listener.onExit(code, gotResult)
        }
    }

    override fun stop() {
        val p = process ?: return
        try {
            // On Windows `claude.cmd` spawns node as a child; kill the whole tree.
            p.toHandle().descendants().forEach { it.destroy() }
            p.destroy()
        } catch (e: Exception) {
            LOG.debug(e)
        }
    }

    companion object {
        private val LOG = logger<ClaudeCliRunner>()

        const val PERSIAN_SYSTEM_PROMPT =
            "The user prefers Persian (Farsi). Always write your explanations and answers in fluent, natural Persian " +
                "unless the user explicitly writes in or asks for another language. Keep source code, identifiers, " +
                "file paths, shell commands, and technical terms that have no common Persian equivalent in English. " +
                "Never put Persian text inside code blocks except for string literals or comments the user asked for."

        fun normalizePersian(s: String): String =
            s.replace('ي', 'ی') // ي -> ی
                .replace('ى', 'ی') // ى -> ی
                .replace('ك', 'ک') // ك -> ک

        fun locateCli(configured: String): String? {
            if (configured.isNotBlank()) {
                val f = File(configured.trim())
                return if (f.exists()) f.absolutePath else configured.trim()
            }
            val names = if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd", "claude") else listOf("claude")
            for (n in names) {
                PathEnvironmentVariableUtil.findInPath(n)?.let { return it.absolutePath }
            }
            val home = System.getProperty("user.home")
            val candidates = buildList {
                if (SystemInfo.isWindows) {
                    val appData = System.getenv("APPDATA")
                    if (appData != null) add("$appData\\npm\\claude.cmd")
                    add("$home\\.local\\bin\\claude.exe")
                    add("$home\\.claude\\local\\claude.exe")
                } else {
                    add("$home/.local/bin/claude")
                    add("$home/.claude/local/claude")
                    add("/opt/homebrew/bin/claude")
                    add("/usr/local/bin/claude")
                    add("/usr/bin/claude")
                    add("$home/.npm-global/bin/claude")
                    add("$home/.bun/bin/claude")
                }
            }
            return candidates.firstOrNull { File(it).canExecute() }
        }
    }
}
