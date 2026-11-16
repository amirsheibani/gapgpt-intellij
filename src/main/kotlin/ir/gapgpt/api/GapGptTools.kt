package ir.gapgpt.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import ir.gapgpt.PluginLog
import ir.gapgpt.ide.Guard
import ir.gapgpt.ide.IdeTools
import ir.gapgpt.ide.PermissionBroker
import ir.gapgpt.settings.GapGptSettings
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.regex.PatternSyntaxException
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Agent tools for the GapGPT engine (OpenAI function calling). Tool names and input shapes mirror
 * Claude Code's (Read / Write / Edit / Bash / Glob / Grep / LS), so the existing Guard rules, permission
 * cards, diff preview and webview tool cards all work unchanged. Mutating calls go through [PermissionBroker].
 */
class GapGptTools(private val project: Project, private val chatId: String) {

    class Outcome(val content: String, val isError: Boolean = false, val input: JsonObject? = null)

    private val ide = IdeTools(project)
    private val root: String = project.basePath ?: System.getProperty("user.home")

    @Volatile
    private var process: Process? = null

    @Volatile
    var cancelled = false

    /** Kills a running Bash command (user pressed stop). */
    fun cancel() {
        cancelled = true
        process?.let { p ->
            runCatching { p.toHandle().descendants().forEach { it.destroyForcibly() } }
            p.destroyForcibly()
        }
    }

    /** Name shown on the webview tool card (IDE tools look like Claude Code's MCP tools). */
    fun displayName(name: String) = if (name in IDE_TOOLS) "mcp__gapgpt__$name" else name

    // ------------------------------------------------------------------ definitions

    fun definitions(): JsonArray = JsonArray().apply {
        fn("Read", "Read a text file. Returns lines prefixed with 1-based line numbers. Use offset/limit for large files. " +
            "Always read a file before editing it.",
            mapOf("file_path" to p("string", "Absolute path, or relative to the project root"),
                "offset" to p("integer", "1-based line to start from"),
                "limit" to p("integer", "Number of lines to read (default 2000)")),
            listOf("file_path"))
        fn("Write", "Create a file or overwrite it completely with new content. Prefer Edit for changes to existing files.",
            mapOf("file_path" to p("string", "Absolute path, or relative to the project root"),
                "content" to p("string", "The full new file content")),
            listOf("file_path", "content"))
        fn("Edit", "Replace an exact string in a file. old_string must match the file exactly (including indentation) " +
            "and be unique unless replace_all is true. Include enough surrounding lines to make it unique.",
            mapOf("file_path" to p("string", "Absolute path, or relative to the project root"),
                "old_string" to p("string", "Exact text to replace"),
                "new_string" to p("string", "Replacement text"),
                "replace_all" to p("boolean", "Replace every occurrence (default false)")),
            listOf("file_path", "old_string", "new_string"))
        fn("Bash", "Run a shell command in the project root (${if (SystemInfo.isWindows) "cmd.exe" else "bash"}) and return " +
            "its output and exit code. Use for builds, tests, git and package managers; not for reading or editing files.",
            mapOf("command" to p("string", "The command to run"),
                "description" to p("string", "Short description of what the command does"),
                "timeout" to p("integer", "Timeout in seconds (default 120, max 600)")),
            listOf("command"))
        fn("Glob", "Find files by glob pattern, e.g. **/*.kt or src/**/Test*.java. Returns matching paths.",
            mapOf("pattern" to p("string", "Glob pattern"),
                "path" to p("string", "Directory to search in (default: project root)")),
            listOf("pattern"))
        fn("Grep", "Search file contents with a regular expression. Returns path:line: text for each match.",
            mapOf("pattern" to p("string", "Regular expression (Java syntax)"),
                "path" to p("string", "File or directory to search (default: project root)"),
                "glob" to p("string", "Only search files whose name matches this glob, e.g. *.kt"),
                "-i" to p("boolean", "Case-insensitive")),
            listOf("pattern"))
        fn("LS", "List the files and folders in a directory.",
            mapOf("path" to p("string", "Directory (default: project root)")))
        fn("getDiagnostics", "Get the IDE's current errors and warnings for a file, or for all open files when no file " +
            "is given. Use after editing to verify the code compiles.",
            mapOf("uri" to p("string", "Absolute file path. Omit for all open files.")))
        fn("getOpenEditors", "List the files currently open in IDE editor tabs, and which one is active.")
        fn("getCurrentSelection", "Get the file the user is looking at in the IDE and the text they have selected.")
        fn("openFile", "Open a file in the IDE editor (optionally at a line) so the user can see it.",
            mapOf("filePath" to p("string", "Absolute or project-relative path"), "line" to p("integer", "1-based line")),
            listOf("filePath"))
    }

    // ------------------------------------------------------------------ execution

    /** Runs one tool call (asking the user first when needed). Never throws. */
    fun run(name: String, rawArgs: String, toolUseId: String): Outcome {
        val input = runCatching { JsonParser.parseString(rawArgs.ifBlank { "{}" }).asJsonObject }.getOrNull()
            ?: return Outcome("Invalid JSON arguments for $name: ${rawArgs.take(300)}", true)
        normalizePaths(input)
        return try {
            val approved = permit(name, input, toolUseId)
            if (approved.isError) return Outcome(approved.content, true, input)
            val finalInput = approved.input ?: input
            val out = execute(name, finalInput)
            Outcome(truncate(out.content), out.isError, finalInput)
        } catch (e: Exception) {
            PluginLog.warn("gapgpt-tool", "$name failed: ${e.message}")
            Outcome("Error: ${e.message ?: e.javaClass.simpleName}", true, input)
        }
    }

    /** Applies the permission mode, Guard and the user's decision. Returns the (possibly user-edited) input. */
    private fun permit(name: String, input: JsonObject, toolUseId: String): Outcome {
        val mode = GapGptSettings.get().permissionMode
        val mutating = name in MUTATING
        if (mutating && mode == "plan") {
            return Outcome("Plan mode is on: do not modify files or run commands. Investigate with read-only tools and " +
                "present a plan to the user instead.", true)
        }
        if (mutating && mode == "dontAsk") return Outcome("Permission mode 'dontAsk' denies $name.", true)

        val rule = Guard.evaluate(chatId, name, input, project.basePath)
        val needsPrompt = when {
            rule != null -> true
            !mutating -> false
            mode == "bypassPermissions" -> false
            mode == "acceptEdits" && name != "Bash" -> false
            else -> true
        }
        if (!needsPrompt) return Outcome("", input = input)

        val args = JsonObject().apply {
            addProperty("tool_name", name)
            add("input", input)
            addProperty("tool_use_id", toolUseId)
        }
        val res = JsonParser.parseString(PermissionBroker.getInstance(project).decide(args, chatId)).asJsonObject
        return if (res.get("behavior")?.asString == "allow") {
            Outcome("", input = res.getAsJsonObject("updatedInput") ?: input)
        } else {
            Outcome(res.get("message")?.asString ?: "The user denied this tool call.", true)
        }
    }

    private fun execute(name: String, a: JsonObject): Outcome {
        fun s(k: String) = a.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        fun i(k: String) = a.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toInt()
        fun b(k: String) = a.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.toBoolean() ?: false
        return when (name) {
            "Read" -> read(req(s("file_path"), "file_path"), i("offset"), i("limit"))
            "Write" -> write(req(s("file_path"), "file_path"), s("content") ?: "")
            "Edit" -> edit(req(s("file_path"), "file_path"), s("old_string") ?: "", s("new_string") ?: "", b("replace_all"))
            "Bash" -> bash(req(s("command"), "command"), (i("timeout") ?: 120).coerceIn(1, 600))
            "Glob" -> glob(req(s("pattern"), "pattern"), s("path"))
            "Grep" -> grep(req(s("pattern"), "pattern"), s("path"), s("glob"), b("-i"))
            "LS" -> ls(s("path"))
            in IDE_TOOLS -> Outcome(ide.call(name, a))
            else -> Outcome("Unknown tool: $name", true)
        }
    }

    private fun req(v: String?, k: String): String = v?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing '$k'")

    private fun read(path: String, offset: Int?, limit: Int?): Outcome {
        saveAllDocuments()
        val f = File(path)
        if (!f.exists()) return Outcome("File does not exist: $path", true)
        if (f.isDirectory) return Outcome("$path is a directory; use LS.", true)
        if (f.length() > 5_000_000) return Outcome("File is too large (${f.length()} bytes); use Grep or offset/limit.", true)
        val lines = f.readText(UTF_8).lines()
        val from = ((offset ?: 1) - 1).coerceIn(0, lines.size)
        val to = (from + (limit ?: 2000)).coerceAtMost(lines.size)
        if (lines.size == 1 && lines[0].isEmpty()) return Outcome("(empty file)")
        val sb = StringBuilder()
        for (n in from until to) sb.append(String.format("%6d\t", n + 1)).append(lines[n].take(2000)).append('\n')
        if (to < lines.size) sb.append("… (${lines.size - to} more lines; use offset=${to + 1} to continue)")
        return Outcome(sb.toString())
    }

    private fun write(path: String, content: String): Outcome {
        saveAllDocuments()
        val f = File(path)
        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(content, UTF_8)
        refresh(f)
        return Outcome((if (existed) "Updated" else "Created") + " $path (${content.lines().size} lines)")
    }

    private fun edit(path: String, old: String, new: String, all: Boolean): Outcome {
        saveAllDocuments()
        val f = File(path)
        if (!f.exists()) {
            if (old.isEmpty()) return write(path, new)
            return Outcome("File does not exist: $path", true)
        }
        if (old == new) return Outcome("old_string and new_string are identical; nothing to change.", true)
        val text = f.readText(UTF_8)
        val crlf = text.contains("\r\n")
        val o = if (crlf && !old.contains("\r\n")) old.replace("\n", "\r\n") else old
        val n = if (crlf && !new.contains("\r\n")) new.replace("\n", "\r\n") else new
        if (o.isEmpty()) return Outcome("old_string is empty. Use Write to replace a whole file.", true)
        var count = 0
        var at = text.indexOf(o)
        while (at >= 0) {
            count++
            at = text.indexOf(o, at + o.length)
        }
        if (count == 0) return Outcome("old_string was not found in $path. Read the file again and copy the text exactly.", true)
        if (count > 1 && !all) return Outcome("old_string matches $count places in $path. Add more context or set replace_all.", true)
        val updated = if (all) text.replace(o, n) else text.replaceFirst(o, n)
        f.writeText(updated, UTF_8)
        refresh(f)
        return Outcome("Edited $path" + if (all && count > 1) " ($count replacements)" else "")
    }

    private fun bash(command: String, timeoutSec: Int): Outcome {
        saveAllDocuments()
        val cmd = if (SystemInfo.isWindows) GeneralCommandLine("cmd.exe", "/c", command)
        else GeneralCommandLine("/bin/bash", "-c", command)
        cmd.withWorkDirectory(root)
            .withCharset(UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withRedirectErrorStream(true)
        GapGptSettings.get().httpsProxy.trim().takeIf { it.isNotEmpty() }?.let {
            cmd.environment["HTTPS_PROXY"] = it
            cmd.environment["HTTP_PROXY"] = it
        }
        cmd.environment["NO_COLOR"] = "1"
        cmd.environment["GIT_PAGER"] = "cat"
        cmd.environment["PAGER"] = "cat"
        val p = cmd.createProcess()
        process = p
        try {
            p.outputStream.close()
            val out = StringBuilder()
            val reader = Thread {
                runCatching {
                    p.inputStream.bufferedReader(UTF_8).use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(out) { if (out.length < MAX_OUTPUT * 2) out.append(buf, 0, n) }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                runCatching { p.toHandle().descendants().forEach { it.destroyForcibly() } }
                p.destroyForcibly()
            }
            reader.join(2000)
            val text = synchronized(out) { out.toString() }.trimEnd()
            if (cancelled) return Outcome("Interrupted by the user.\n$text", true)
            if (!finished) return Outcome("Command timed out after ${timeoutSec}s.\n$text", true)
            val code = p.exitValue()
            refresh(null)
            val body = text.ifEmpty { "(no output)" }
            return Outcome(if (code == 0) body else "Exit code $code\n$body", code != 0)
        } finally {
            process = null
        }
    }

    private fun glob(pattern: String, path: String?): Outcome {
        val base = File(path ?: root).toPath()
        if (!base.isDirectory()) return Outcome("Not a directory: $base", true)
        val m = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return Outcome("Invalid glob: ${e.message}", true)
        }
        val hits = mutableListOf<Pair<Path, Long>>()
        walk(base) { f ->
            val rel = base.relativize(f)
            if (m.matches(rel) || m.matches(f.fileName)) hits += f to runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(0)
            hits.size < 2000
        }
        if (hits.isEmpty()) return Outcome("No files found")
        val sorted = hits.sortedByDescending { it.second }.map { it.first.toString() }
        return Outcome(sorted.take(200).joinToString("\n") + if (sorted.size > 200) "\n… (${sorted.size - 200} more)" else "")
    }

    private fun grep(pattern: String, path: String?, glob: String?, ignoreCase: Boolean): Outcome {
        saveAllDocuments()
        val re = try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: PatternSyntaxException) {
            return Outcome("Invalid regex: ${e.description}", true)
        }
        val nameMatcher = glob?.takeIf { it.isNotBlank() }?.let { runCatching { FileSystems.getDefault().getPathMatcher("glob:$it") }.getOrNull() }
        val start = File(path ?: root).toPath()
        val out = StringBuilder()
        var matches = 0
        fun scan(f: Path) {
            if (nameMatcher != null && !nameMatcher.matches(f.fileName)) return
            val size = runCatching { Files.size(f) }.getOrDefault(0)
            if (size > 2_000_000) return
            val bytes = runCatching { Files.readAllBytes(f) }.getOrNull() ?: return
            if (bytes.take(8000).any { it == 0.toByte() }) return // binary
            String(bytes, UTF_8).lineSequence().forEachIndexed { n, line ->
                if (matches < 300 && re.containsMatchIn(line)) {
                    out.append(f).append(':').append(n + 1).append(": ").append(line.trim().take(300)).append('\n')
                    matches++
                }
            }
        }
        if (start.isRegularFile()) scan(start)
        else if (start.isDirectory()) walk(start) { scan(it); matches < 300 }
        else return Outcome("Path does not exist: $start", true)
        if (matches == 0) return Outcome("No matches found")
        return Outcome(out.toString().trimEnd() + if (matches >= 300) "\n… (results truncated at 300 matches)" else "")
    }

    private fun ls(path: String?): Outcome {
        val dir = File(path ?: root)
        if (!dir.isDirectory) return Outcome("Not a directory: $dir", true)
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        return Outcome("$dir\n" + items.take(500).joinToString("\n") { "  " + it.name + if (it.isDirectory) "/" else "" })
    }

    // ------------------------------------------------------------------ helpers

    private fun walk(base: Path, visit: (Path) -> Boolean) {
        val stack = ArrayDeque<Path>().apply { add(base) }
        while (stack.isNotEmpty()) {
            if (cancelled) return
            val dir = stack.removeLast()
            val children = runCatching { Files.newDirectoryStream(dir).use { it.toList() } }.getOrNull() ?: continue
            for (c in children.sortedBy { it.fileName.toString() }) {
                if (Files.isSymbolicLink(c)) continue
                if (c.isDirectory()) {
                    if (c.fileName.toString() !in SKIP_DIRS) stack.add(c)
                } else if (!visit(c)) return
            }
        }
    }

    /** Relative paths are resolved against the project root, so Guard and the diff preview see real paths. */
    private fun normalizePaths(input: JsonObject) {
        for (k in listOf("file_path", "path", "filePath")) {
            val v = input.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
            if (v.isEmpty()) continue
            val expanded = if (v == "~" || v.startsWith("~/")) System.getProperty("user.home") + v.drop(1) else v
            val f = File(expanded).let { if (it.isAbsolute) it else File(root, expanded) }
            input.addProperty(k, f.normalize().absolutePath)
        }
    }

    private fun saveAllDocuments() {
        ApplicationManager.getApplication().invokeAndWait({ FileDocumentManager.getInstance().saveAllDocuments() }, ModalityState.any())
    }

    private fun refresh(f: File?) {
        if (f != null) LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)?.refresh(false, false)
        else LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root))?.refresh(true, true)
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_OUTPUT) s
        else s.take(MAX_OUTPUT / 2) + "\n\n… (${s.length - MAX_OUTPUT} characters truncated) …\n\n" + s.takeLast(MAX_OUTPUT / 2)

    private fun JsonArray.fn(name: String, desc: String, props: Map<String, JsonObject> = emptyMap(), required: List<String> = emptyList()) =
        add(JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                add("parameters", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
                    add("required", JsonArray().apply { required.forEach { add(it) } })
                })
            })
        })

    private fun p(type: String, description: String) = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }

    companion object {
        private const val MAX_OUTPUT = 30_000
        val MUTATING = setOf("Write", "Edit", "Bash")
        val IDE_TOOLS = setOf("getDiagnostics", "getOpenEditors", "getCurrentSelection", "openFile")
        private val SKIP_DIRS = setOf(".git", "node_modules", ".gradle", "build", "out", "target", ".idea", "dist",
            ".next", "__pycache__", ".venv", "venv", ".dart_tool", "Pods", "graphify-out")
    }
}
