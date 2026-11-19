package ir.gapgpt.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import ir.gapgpt.I18n.tr
import ir.gapgpt.PluginLog
import ir.gapgpt.settings.GapGptSettings
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional code graph with graphify, used only when the user turns it on:
 * - `/graphify` in the chat or the Settings button builds or refreshes the graph (AST only, no LLM);
 * - while the graphify button in the chat toolbar is on, the agent gets the `graphify` tool ([query]) on every message.
 *
 * Fully isolated from other tools: graphify is installed into the plugin's own folder (not the user's
 * `uv tool` set), the graph is written to the IDE system folder (not `graphify-out/` in the project, which
 * other tools may own, unless the user opts in with `graphInProject`), and nothing else is written to the
 * project, AGENTS.md or `.git/hooks`.
 * A graphify the user installed themselves is never used or changed.
 */
object GraphifySetup {

    private const val UV_URL = "https://docs.astral.sh/uv/getting-started/installation/"
    private const val INSTALL_TIMEOUT_MS = 20 * 60 * 1000
    private const val STEP_TIMEOUT_MS = 10 * 60 * 1000

    private val running = AtomicBoolean(false)

    /** True while a graph build is running. */
    val isBuilding: Boolean get() = running.get()

    // ---------------------------------------------------------------- private folders

    private fun home(): File = File(PathManager.getSystemPath(), "gapgpt")

    /** Where the plugin's own graphify is installed (uv tool dir, bin dir, cache, Python). */
    private fun toolsDir() = File(home(), "tools")
    private fun binDir() = File(toolsDir(), "bin")

    /**
     * The graph of [project]: outside the project folder by default, or the project's own `graphify-out/`
     * when the user turned on [GapGptSettings.State.graphInProject].
     */
    fun graphDir(project: Project): File {
        val base = project.basePath
        if (GapGptSettings.get().graphInProject && base != null) return File(base, "graphify-out")
        return File(home(), "graphify/" + project.locationHash)
    }

    fun hasGraph(project: Project) = File(graphDir(project), "graph.json").isFile

    /** The plugin's own graphify, or null when it is not installed yet. */
    private fun graphify(): String? {
        val names = if (SystemInfo.isWindows) listOf("graphify.exe", "graphify.cmd", "graphify") else listOf("graphify")
        return names.map { File(binDir(), it) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    /** Environment that keeps graphify and uv inside the plugin's folders. */
    private fun GeneralCommandLine.isolated(project: Project?): GeneralCommandLine {
        val t = toolsDir()
        withEnvironment("PATH", searchPath())
        withEnvironment("UV_TOOL_DIR", File(t, "uv-tools").path)
        withEnvironment("UV_TOOL_BIN_DIR", binDir().path)
        withEnvironment("UV_CACHE_DIR", File(t, "uv-cache").path)
        withEnvironment("UV_PYTHON_INSTALL_DIR", File(t, "python").path)
        withEnvironment("GRAPHIFY_QUERY_LOG_DISABLE", "1")
        if (project != null) withEnvironment("GRAPHIFY_OUT", graphDir(project).path)
        return this
    }

    /** Folders the IDE's PATH often misses when it is launched from the Dock / a desktop entry (to find `uv`). */
    private fun searchPath(): String {
        val home = System.getProperty("user.home")
        val extra = listOf("$home/.local/bin", "$home/.cargo/bin", "/opt/homebrew/bin", "/usr/local/bin")
        return (System.getenv("PATH").orEmpty().split(File.pathSeparator) + extra)
            .filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
    }

    private fun findUv(): String? {
        val names = if (SystemInfo.isWindows) listOf("uv.exe", "uv") else listOf("uv")
        for (dir in searchPath().split(File.pathSeparator)) {
            for (n in names) {
                val f = File(dir, n)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    // ---------------------------------------------------------------- query (agent tool)

    /** Arguments for a `graphify` tool call: mode query / path / explain. Null when they are not valid. */
    fun toolArgs(mode: String, text: String, to: String?): List<String>? = when (mode) {
        "query" -> listOf("query", text)
        "explain" -> listOf("explain", text)
        "path" -> to?.takeIf { it.isNotBlank() }?.let { listOf("path", text, it) }
        else -> null
    }

    /** Runs a read-only `graphify <args>` (query / path / explain) on [project]'s graph. Blocking: off the EDT. */
    fun query(project: Project, args: List<String>, timeoutMs: Int = 60_000): Pair<Boolean, String> {
        val base = project.basePath ?: return false to "The project has no folder."
        val graphify = graphify() ?: return false to "graphify is not set up. Ask the user to send /graphify first."
        if (!hasGraph(project)) return false to "There is no code graph yet. Ask the user to send /graphify first."
        val line = GeneralCommandLine(listOf(graphify) + args).withWorkDirectory(base).withCharset(UTF_8).isolated(project)
        PluginLog.info("graphify", "run: ${args.firstOrNull().orEmpty()}")
        val out = CapturingProcessHandler(line).runProcess(timeoutMs)
        val text = (out.stdout + if (out.stderr.isNotBlank()) "\n" + out.stderr else "").trim()
        return when {
            out.isTimeout -> false to "graphify timed out after ${timeoutMs / 1000}s."
            out.exitCode != 0 -> false to "graphify failed (exit ${out.exitCode}):\n${text.take(4000)}"
            else -> true to text.ifEmpty { "(no output)" }
        }
    }

    // ---------------------------------------------------------------- build

    /**
     * Builds (or rebuilds) the graph in a background task with progress.
     * [quiet]: automatic refresh, so no "already building" and no "graph is ready" notification (errors still notify).
     */
    fun run(project: Project, quiet: Boolean = false) {
        if (project.basePath == null) return
        ApplicationManager.getApplication().invokeLater({ startTask(project, quiet) }, project.disposed)
    }

    private fun startTask(project: Project, quiet: Boolean) {
        if (!running.compareAndSet(false, true)) {
            if (!quiet) notify(project, tr("The code graph is already being built.", "گراف کد در حال ساخته شدن است."), NotificationType.INFORMATION)
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, tr("Building the code graph (graphify)", "ساخت گراف کد (graphify)"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    build(project, indicator, quiet)
                } catch (e: Exception) {
                    PluginLog.warn("graphify", "Build failed: ${e.javaClass.simpleName}")
                    notify(project, tr("Building the code graph failed. See the Log tab.", "ساخت گراف کد ناموفق بود. تب Log را ببینید."), NotificationType.ERROR)
                }
            }

            override fun onFinished() {
                running.set(false)
            }
        })
    }

    private fun build(project: Project, indicator: ProgressIndicator, quiet: Boolean) {
        val base = File(project.basePath ?: return)

        indicator.text = tr("Looking for graphify…", "جست‌وجوی graphify…")
        var graphify = graphify()
        if (graphify == null) {
            val uv = findUv()
            if (uv == null) {
                notify(
                    project,
                    tr(
                        "Setting up graphify needs \"uv\" (Python tool manager). Install it, then try again.",
                        "برای راه‌اندازی graphify ابزار «uv» لازم است. آن را نصب کنید و دوباره تلاش کنید.",
                    ),
                    NotificationType.WARNING,
                    NotificationAction.createSimpleExpiring(tr("How to install uv", "راهنمای نصب uv")) { BrowserUtil.browse(UV_URL) },
                )
                return
            }
            indicator.text = tr("Installing graphify for GapGPT (needs internet)…", "نصب graphify برای GapGPT (نیاز به اینترنت)…")
            toolsDir().mkdirs()
            if (!exec(listOf(uv, "tool", "install", "graphifyy"), toolsDir(), null, indicator, INSTALL_TIMEOUT_MS)) {
                return fail(project, tr("Installing graphify failed.", "نصب graphify ناموفق بود."))
            }
            graphify = graphify() ?: return fail(project, tr("graphify was installed but not found.", "graphify نصب شد ولی پیدا نشد."))
        }

        indicator.text = tr("Building the code graph…", "ساخت گراف کد…")
        graphDir(project).mkdirs()
        if (!exec(listOf(graphify, "update", base.path), base, project, indicator, STEP_TIMEOUT_MS)) {
            return fail(project, tr("Building the code graph failed.", "ساخت گراف کد ناموفق بود."))
        }
        PluginLog.info("graphify", "Code graph built")
        if (!quiet) {
            notify(
                project,
                tr("The code graph is ready. The graphify button in the chat uses it.", "گراف کد آماده است. دکمهٔ graphify در چت از آن استفاده می‌کند."),
                NotificationType.INFORMATION,
            )
        }
    }

    /** Runs a command in the isolated environment; killed when the task is cancelled or times out. */
    private fun exec(cmd: List<String>, dir: File, project: Project?, indicator: ProgressIndicator, timeoutMs: Int): Boolean {
        val line = GeneralCommandLine(cmd).withWorkDirectory(dir).withCharset(UTF_8).isolated(project)
        PluginLog.info("graphify", "run: ${cmd.drop(1).take(2).joinToString(" ")}")
        val out = CapturingProcessHandler(line).runProcessWithProgressIndicator(indicator, timeoutMs)
        if (out.isCancelled || out.isTimeout || out.exitCode != 0) {
            val tail = (out.stderr.ifBlank { out.stdout }).lines().filter { it.isNotBlank() }.takeLast(5).joinToString(" | ")
            PluginLog.warn("graphify", "failed (exit=${out.exitCode}, timeout=${out.isTimeout}, cancelled=${out.isCancelled}): $tail")
            return false
        }
        return true
    }

    private fun fail(project: Project, text: String) {
        notify(project, text + " " + tr("See the Log tab.", "تب Log را ببینید."), NotificationType.ERROR)
    }

    private fun notify(project: Project, text: String, type: NotificationType, vararg actions: NotificationAction) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("GapGPT").createNotification(text, type)
        actions.forEach { n.addAction(it) }
        n.notify(project)
    }
}
