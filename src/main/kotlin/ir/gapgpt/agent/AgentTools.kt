package ir.gapgpt.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.EnvironmentUtil
import ir.gapgpt.chat.ToolCall
import ir.gapgpt.ide.GraphifySetup
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * The tools the chat agent can call. Every path is resolved inside the project directory; edits go through
 * IntelliJ documents (so they can be undone and open editors stay in sync); commands run in the project directory.
 */
class AgentTools(private val project: Project) {

    enum class Kind { READ, EDIT, COMMAND, MCP }

    /** Shared with the running tool so Stop can kill a command. */
    class RunContext {
        @Volatile
        var cancelled = false

        @Volatile
        var process: Process? = null

        fun cancel() {
            cancelled = true
            process?.let { p -> p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        }
    }

    /** [forModel] goes back to the model; [summary] (one line) and [detail] (a few lines) are shown in the chat. */
    class Result(val ok: Boolean, val forModel: String, val summary: String, val detail: String? = null)

    /** A parsed call, ready to show (title/label/preview) and to [run]. */
    class Prepared(
        val call: ToolCall,
        val kind: Kind,
        val title: String,
        val label: String,
        /** What the user approves: a diff, the new file or the command. */
        val preview: String?,
        /** For edits: (current text, text after the change) of the whole file, for the IDE diff viewer. */
        val proposed: (() -> Pair<String, String>?)? = null,
        /** Key for approval ("edit", "command", "mcp:<server>"); null = runs without asking. */
        val approvalKey: String? = when (kind) {
            Kind.READ -> null
            Kind.EDIT -> "edit"
            Kind.COMMAND -> "command"
            Kind.MCP -> "mcp"
        },
        val run: (RunContext) -> Result,
    )

    private val root: File = File(project.basePath ?: System.getProperty("user.home")).canonicalFile

    // ---------------------------------------------------------------- definitions

    /**
     * Tool definitions; in [planMode] only read-only tools plus exit_plan_mode.
     * [graphify]: the user turned graphify on (chat toolbar button) or asked with `/graphify <question>`, so the `graphify` tool is offered too.
     */
    fun definitions(planMode: Boolean, graphify: Boolean = false): JsonArray = JsonArray().apply {
        if (graphify) {
            add(fn("graphify", "Query the project's graphify knowledge graph (enabled by the user). " +
                    "mode \"query\": a question in natural language; \"explain\": one concept or symbol; " +
                    "\"path\": how two concepts are connected (text = from, to = to). Returns a scoped subgraph.",
                props = mapOf(
                    "mode" to JsonObject().apply {
                        addProperty("type", "string")
                        add("enum", JsonArray().apply { add("query"); add("explain"); add("path") })
                    },
                    "text" to str("The question, the concept, or (for path) the first concept."),
                    "to" to str("For mode \"path\" only: the second concept."),
                ), required = listOf("mode", "text")))
        }
        add(fn("todo_write", "Create or update the task list for the current work. Use it for tasks with 3 or more steps: " +
                "send the whole list every time, keep exactly one task in_progress while working, and mark tasks completed " +
                "as soon as they are done. Skip it for simple one-step requests.",
            props = mapOf("todos" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "The complete task list.")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("content", str("What to do, short and imperative."))
                        add("status", JsonObject().apply {
                            addProperty("type", "string")
                            add("enum", JsonArray().apply { add("pending"); add("in_progress"); add("completed") })
                        })
                    })
                    add("required", JsonArray().apply { add("content"); add("status") })
                })
            }), required = listOf("todos")))
        if (planMode) {
            add(fn("exit_plan_mode", "Present your implementation plan to the user for approval and leave plan mode. " +
                    "Call it only when the plan is complete. If the user approves, you can then edit files and run commands.",
                props = mapOf("plan" to str("The plan in Markdown: goal, files to change and how, steps, and how to verify.")),
                required = listOf("plan")))
        }
        readOnlyDefinitions().forEach { add(it) }
        if (!planMode) writeDefinitions().forEach { add(it) }
    }

    private fun readOnlyDefinitions(): JsonArray = JsonArray().apply {
        add(fn("list_dir", "List the entries of a directory in the project (directories end with /).",
            props = mapOf("path" to str("Directory relative to the project root. Default: \".\"")), required = emptyList()))
        add(fn("glob", "Find files whose project-relative path matches a glob pattern, e.g. \"**/*.kt\" or \"src/**/Chat*.js\". " +
                "Build output and VCS folders are skipped.",
            props = mapOf("pattern" to str("Glob pattern."), "path" to str("Directory to search in. Default: \".\"")),
            required = listOf("pattern")))
        add(fn("grep", "Search file contents with a regular expression (Java syntax). Returns path:line: text for each match.",
            props = mapOf(
                "pattern" to str("Regular expression."),
                "path" to str("File or directory to search. Default: \".\""),
                "include" to str("Optional glob on the file name, e.g. \"*.kt\"."),
                "ignore_case" to bool("Case-insensitive search."),
            ), required = listOf("pattern")))
        add(fn("read_file", "Read a text file. Lines are numbered (number + tab). Read large files in parts with offset/limit.",
            props = mapOf(
                "path" to str("File path relative to the project root."),
                "offset" to int("1-based line to start at. Default: 1."),
                "limit" to int("Number of lines to read. Default: $DEFAULT_READ_LINES."),
            ), required = listOf("path")))
        // Code navigation on the IDE's index: prefer these to grep for code questions.
        add(fn("find_symbol", "Find where classes, functions, methods, fields etc. are declared, by name (exact matches " +
                "first, then names containing the text). Uses the IDE's code index, so it ignores comments and strings. " +
                "Prefer it to grep when looking for a declaration.",
            props = mapOf("name" to str("Symbol name or part of it, e.g. \"ChatPanel\" or \"sendMessage\".")),
            required = listOf("name")))
        add(fn("file_outline", "List the declarations of a file (classes, functions, fields …) as a tree with line numbers. " +
                "Cheaper than reading a large file; read only the parts you need afterwards.",
            props = mapOf("path" to str("File path relative to the project root.")), required = listOf("path")))
        add(fn("find_references", "Find every place in the project that uses a symbol (call sites, imports, overrides …), " +
                "resolved by the IDE, not by text. Give the file and line where the name appears (its declaration or any use).",
            props = mapOf(
                "path" to str("File where the name appears."),
                "line" to int("1-based line where the name appears."),
                "symbol" to str("The name as written in the code, e.g. \"sendMessage\"."),
            ), required = listOf("path", "line", "symbol")))
        add(fn("go_to_definition", "Resolve a name used in the code to its declaration (file:line and that line), " +
                "including library and SDK classes.",
            props = mapOf(
                "path" to str("File where the name is used."),
                "line" to int("1-based line where the name is used."),
                "symbol" to str("The name as written in the code."),
            ), required = listOf("path", "line", "symbol")))
        add(fn("file_relations", "Show how a file is connected to the rest of the project: the project files it uses " +
                "and the files that use its declarations, with reference counts. Use it to see the impact of a change " +
                "or to understand a module.",
            props = mapOf("path" to str("File path relative to the project root.")), required = listOf("path")))
    }

    private fun writeDefinitions(): JsonArray = JsonArray().apply {
        add(fn("edit_file", "Replace an exact piece of text in a file. old_string must match the file exactly (whitespace " +
                "included, without line-number prefixes) and be unique unless replace_all is true. Read the file first.",
            props = mapOf(
                "path" to str("File path relative to the project root."),
                "old_string" to str("Exact text to replace."),
                "new_string" to str("Replacement text."),
                "replace_all" to bool("Replace every occurrence."),
            ), required = listOf("path", "old_string", "new_string")))
        add(fn("write_file", "Create a new file or overwrite a whole file. Prefer edit_file for changes to existing files.",
            props = mapOf("path" to str("File path relative to the project root."), "content" to str("Full file content.")),
            required = listOf("path", "content")))
        add(fn("run_command", "Run a shell command in the project directory (${if (SystemInfo.isWindows) "cmd.exe" else "bash"}) " +
                "and return its output and exit code. Use it for builds, tests, git and other CLI tools. Not interactive.",
            props = mapOf(
                "command" to str("The command line."),
                "timeout_seconds" to int("Default 120, maximum $MAX_TIMEOUT_S."),
            ), required = listOf("command")))
    }

    private fun fn(name: String, desc: String, props: Map<String, JsonObject>, required: List<String>) = JsonObject().apply {
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
    }

    private fun prop(type: String, desc: String) = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", desc)
    }

    private fun str(d: String) = prop("string", d)
    private fun int(d: String) = prop("integer", d)
    private fun bool(d: String) = prop("boolean", d)

    // ---------------------------------------------------------------- dispatch

    fun prepare(call: ToolCall): Prepared {
        val args = try {
            JsonParser.parseString(call.arguments.ifBlank { "{}" }).asJsonObject
        } catch (e: Exception) {
            return failed(call, "Invalid JSON arguments: ${e.message}")
        }
        fun s(k: String) = args.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        fun i(k: String) = args.get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        fun b(k: String) = args.get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

        return when (call.name) {
            "list_dir" -> {
                val p = s("path") ?: "."
                Prepared(call, Kind.READ, "List", p, null) { listDir(p) }
            }
            "glob" -> {
                val pattern = s("pattern") ?: return failed(call, "Missing argument: pattern")
                val p = s("path") ?: "."
                Prepared(call, Kind.READ, "Glob", pattern, null) { glob(pattern, p) }
            }
            "grep" -> {
                val pattern = s("pattern") ?: return failed(call, "Missing argument: pattern")
                val p = s("path") ?: "."
                Prepared(call, Kind.READ, "Grep", pattern + if (p != ".") "  in $p" else "", null) {
                    grep(pattern, p, s("include"), b("ignore_case"), it)
                }
            }
            "read_file" -> {
                val p = s("path") ?: return failed(call, "Missing argument: path")
                Prepared(call, Kind.READ, "Read", p, null) { readFile(p, i("offset") ?: 1, i("limit") ?: DEFAULT_READ_LINES) }
            }
            "find_symbol" -> {
                val name = s("name")?.takeIf { it.isNotBlank() } ?: return failed(call, "Missing argument: name")
                Prepared(call, Kind.READ, "Symbol", name, null) { ctx -> safe { code.findSymbol(name, ctx) } }
            }
            "file_outline" -> {
                val p = s("path") ?: return failed(call, "Missing argument: path")
                Prepared(call, Kind.READ, "Outline", p, null) { ctx -> onFile(p) { code.outline(it, ctx) } }
            }
            "find_references", "go_to_definition" -> {
                val p = s("path") ?: return failed(call, "Missing argument: path")
                val sym = s("symbol")?.trim()?.takeIf { it.isNotEmpty() } ?: return failed(call, "Missing argument: symbol")
                val line = i("line") ?: 0
                val refs = call.name == "find_references"
                Prepared(call, Kind.READ, if (refs) "References" else "Definition", "$sym  in $p:$line", null) { ctx ->
                    onFile(p) { if (refs) code.findReferences(it, line, sym, ctx) else code.goToDefinition(it, line, sym, ctx) }
                }
            }
            "graphify" -> {
                val mode = s("mode") ?: "query"
                val text = s("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return failed(call, "Missing argument: text")
                val args = GraphifySetup.toolArgs(mode, text, s("to")?.trim())
                    ?: return failed(call, "Invalid arguments: mode must be query, explain or path (path needs \"to\").")
                Prepared(call, Kind.READ, "Graphify", "$mode: $text" + (s("to")?.let { " → $it" } ?: ""), null) {
                    val (ok, out) = GraphifySetup.query(project, args)
                    val shown = out.take(MAX_GRAPHIFY)
                    Result(ok, if (out.length > MAX_GRAPHIFY) "$shown\n… (truncated)" else shown,
                        if (ok) "${out.lines().size} lines" else "graphify failed")
                }
            }
            "file_relations" -> {
                val p = s("path") ?: return failed(call, "Missing argument: path")
                Prepared(call, Kind.READ, "Relations", p, null) { ctx -> onFile(p) { code.relations(it, ctx) } }
            }
            "edit_file" -> {
                val p = s("path") ?: return failed(call, "Missing argument: path")
                val old = s("old_string") ?: return failed(call, "Missing argument: old_string")
                val new = s("new_string") ?: return failed(call, "Missing argument: new_string")
                val all = b("replace_all")
                Prepared(call, Kind.EDIT, "Edit", p, diffPreview(old, new), proposed = { proposedEdit(p, old, new, all) }) {
                    editFile(p, old, new, all)
                }
            }
            "write_file" -> {
                val p = s("path") ?: return failed(call, "Missing argument: path")
                val content = s("content") ?: return failed(call, "Missing argument: content")
                val exists = resolve(p)?.isFile == true
                val head = content.lines().take(PREVIEW_LINES).joinToString("\n") { "+ $it" }
                val more = content.lines().size - PREVIEW_LINES
                val preview = (if (exists) "(overwrite)\n" else "(new file)\n") + head + if (more > 0) "\n… +$more lines" else ""
                Prepared(call, Kind.EDIT, "Write", p, preview, proposed = {
                    resolve(p)?.let { f -> (if (f.isFile) readTextOrNull(f) else "").orEmpty() to content }
                }) { writeFile(p, content) }
            }
            "run_command" -> {
                val cmd = s("command")?.takeIf { it.isNotBlank() } ?: return failed(call, "Missing argument: command")
                val timeout = (i("timeout_seconds") ?: DEFAULT_TIMEOUT_S).coerceIn(1, MAX_TIMEOUT_S)
                Prepared(call, Kind.COMMAND, "Bash", cmd, "$ $cmd") { runCommand(cmd, timeout, it) }
            }
            else -> failed(call, "Unknown tool: ${call.name}")
        }
    }

    private val code by lazy { CodeTools(project, root) }

    private fun CodeTools.Answer.toResult() = Result(ok, text, summary)

    /** Runs a code query on a project file; index errors become a normal tool error for the model. */
    private fun onFile(path: String, body: (File) -> CodeTools.Answer): Result {
        val f = resolve(path) ?: return outside(path)
        if (!f.isFile) return Result(false, "Error: file not found: $path", "File not found")
        return safe { body(f) }
    }

    private fun safe(body: () -> CodeTools.Answer): Result {
        return try {
            body().toResult()
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            Result(false, "Error: the query was cancelled.", "Cancelled")
        } catch (e: Exception) {
            Result(false, "Error: code index query failed (${e.javaClass.simpleName}). Use grep instead.", "Index query failed")
        }
    }

    private fun failed(call: ToolCall, message: String) =
        Prepared(call, Kind.READ, call.name.ifBlank { "tool" }, "", null) { Result(false, "Error: $message", message) }

    // ---------------------------------------------------------------- paths

    /** The file for a project-relative (or absolute) path, or null when it is outside the project. */
    private fun resolve(path: String): File? {
        val raw = path.trim().ifEmpty { "." }
        val f = try {
            (if (File(raw).isAbsolute) File(raw) else File(root, raw)).canonicalFile
        } catch (e: Exception) {
            return null
        }
        return if (f == root || f.path.startsWith(root.path + File.separator)) f else null
    }

    private fun rel(f: File): String = if (f == root) "." else f.relativeTo(root).invariantSeparatorsPath

    private fun outside(path: String) = Result(false, "Error: '$path' is outside the project directory ($root).", "Outside the project")

    private fun walk(dir: File, visit: (File) -> Boolean) {
        dir.walkTopDown()
            .onEnter { it == dir || it.name !in SKIP_DIRS }
            .filter { it.isFile }
            .forEach { if (!visit(it)) return }
    }

    // ---------------------------------------------------------------- read tools

    private fun listDir(path: String): Result {
        val dir = resolve(path) ?: return outside(path)
        if (!dir.isDirectory) return Result(false, "Error: not a directory: $path", "Not a directory")
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { it.name != ".git" && it.name != ".DS_Store" }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val shown = entries.take(MAX_LIST)
        val text = shown.joinToString("\n") { if (it.isDirectory) it.name + "/" else it.name } +
            if (entries.size > shown.size) "\n… ${entries.size - shown.size} more" else ""
        return Result(true, text.ifEmpty { "(empty directory)" }, "${entries.size} entries")
    }

    private fun glob(pattern: String, path: String): Result {
        val dir = resolve(path) ?: return outside(path)
        if (!dir.isDirectory) return Result(false, "Error: not a directory: $path", "Not a directory")
        val matchers = try {
            val fs = FileSystems.getDefault()
            val list = ArrayList<java.nio.file.PathMatcher>()
            list.add(fs.getPathMatcher("glob:$pattern"))
            if (!pattern.contains('/')) list.add(fs.getPathMatcher("glob:**/$pattern"))
            list
        } catch (e: Exception) {
            return Result(false, "Error: invalid glob: ${e.message}", "Invalid pattern")
        }
        val out = ArrayList<String>()
        var more = false
        walk(dir) { f ->
            val r = Paths.get(f.relativeTo(dir).path)
            if (matchers.any { it.matches(r) }) {
                if (out.size >= MAX_GLOB) { more = true; false } else { out += rel(f); true }
            } else true
        }
        if (out.isEmpty()) return Result(true, "No files found.", "No files found")
        out.sort()
        return Result(true, out.joinToString("\n") + if (more) "\n… (more results; narrow the pattern)" else "",
            "Found ${out.size}${if (more) "+" else ""} files")
    }

    private fun grep(pattern: String, path: String, include: String?, ignoreCase: Boolean, ctx: RunContext): Result {
        val start = resolve(path) ?: return outside(path)
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            return Result(false, "Error: invalid regex: ${e.message}", "Invalid regex")
        }
        val nameMatcher = include?.takeIf { it.isNotBlank() }?.let {
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$it") }.getOrNull()
        }
        val out = ArrayList<String>()
        var files = 0
        var more = false
        val search = { f: File ->
            if (nameMatcher == null || nameMatcher.matches(Paths.get(f.name))) {
                val text = readTextOrNull(f)
                if (text != null) {
                    var hit = false
                    text.lineSequence().forEachIndexed { n, line ->
                        if (out.size < MAX_GREP && regex.containsMatchIn(line)) {
                            out += "${rel(f)}:${n + 1}: ${line.trim().take(MAX_LINE)}"
                            hit = true
                        } else if (out.size >= MAX_GREP && regex.containsMatchIn(line)) more = true
                    }
                    if (hit) files++
                }
            }
            !ctx.cancelled && !more
        }
        if (start.isFile) search(start) else walk(start, search)
        if (out.isEmpty()) return Result(true, "No matches.", "No matches")
        return Result(true, out.joinToString("\n") + if (more) "\n… (more matches; narrow the search)" else "",
            "Found ${out.size}${if (more) "+" else ""} matches in $files files")
    }

    private fun readFile(path: String, offset: Int, limit: Int): Result {
        val f = resolve(path) ?: return outside(path)
        if (!f.isFile) return Result(false, "Error: file not found: $path", "File not found")
        if (f.length() > MAX_FILE_BYTES) return Result(false, "Error: file is too large (${f.length()} bytes). Use grep.", "Too large")
        val text = readTextOrNull(f) ?: return Result(false, "Error: not a text file: $path", "Binary file")
        val lines = text.lines()
        val from = offset.coerceAtLeast(1)
        val count = limit.coerceIn(1, MAX_READ_LINES)
        if (from > lines.size) return Result(false, "Error: offset $from is past the end (${lines.size} lines).", "Offset past end")
        val to = minOf(lines.size, from - 1 + count)
        val body = StringBuilder()
        for (n in from..to) body.append(n).append('\t').append(lines[n - 1].take(MAX_READ_LINE)).append('\n')
        if (to < lines.size) body.append("… (${lines.size - to} more lines; use offset=${to + 1})\n")
        return Result(true, body.toString(), "Read ${to - from + 1} lines" + if (from > 1 || to < lines.size) " ($from-$to of ${lines.size})" else "")
    }

    /** Current text of a file: the open (possibly unsaved) document if there is one, else the disk content. */
    private fun readTextOrNull(f: File): String? {
        if (f.length() > MAX_FILE_BYTES) return null
        val vf = LocalFileSystem.getInstance().findFileByIoFile(f)
        if (vf != null) {
            val doc = ApplicationManager.getApplication().runReadAction(Computable {
                FileDocumentManager.getInstance().getCachedDocument(vf)?.text
            })
            if (doc != null) return doc
        }
        val bytes = try {
            f.readBytes()
        } catch (e: Exception) {
            return null
        }
        if (bytes.take(8000).any { it == 0.toByte() }) return null
        return String(bytes, UTF_8).replace("\r\n", "\n")
    }

    // ---------------------------------------------------------------- edit tools

    private fun diffPreview(old: String, new: String): String {
        fun side(prefix: String, s: String): String {
            val l = s.lines()
            return l.take(PREVIEW_LINES).joinToString("\n") { "$prefix $it" } + if (l.size > PREVIEW_LINES) "\n… +${l.size - PREVIEW_LINES} lines" else ""
        }
        return side("-", old) + "\n" + side("+", new)
    }

    /** The whole file before and after an edit_file call, or null if the edit cannot be applied. */
    private fun proposedEdit(path: String, old: String, new: String, replaceAll: Boolean): Pair<String, String>? {
        val f = resolve(path)?.takeIf { it.isFile } ?: return null
        val text = readTextOrNull(f) ?: return null
        if (old.isEmpty() || !text.contains(old)) return null
        return text to if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
    }

    private fun editFile(path: String, old: String, new: String, replaceAll: Boolean): Result {
        val f = resolve(path) ?: return outside(path)
        if (!f.isFile) return Result(false, "Error: file not found: $path", "File not found")
        if (old.isEmpty()) return Result(false, "Error: old_string is empty. Use write_file to create files.", "Empty old_string")
        if (old == new) return Result(false, "Error: old_string and new_string are the same.", "No change")
        val text = readTextOrNull(f) ?: return Result(false, "Error: not a text file: $path", "Binary file")
        var count = 0
        var at = text.indexOf(old)
        while (at >= 0) {
            count++
            at = text.indexOf(old, at + old.length)
        }
        if (count == 0) return Result(false, "Error: old_string was not found in $path. Read the file again and copy the text exactly.", "Text not found")
        if (count > 1 && !replaceAll) {
            return Result(false, "Error: old_string occurs $count times in $path. Add more surrounding context or set replace_all.", "Not unique ($count matches)")
        }
        val updated = if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
        writeText(f, updated)
        val added = new.lines().size
        val removed = old.lines().size
        return Result(true, "Edited $path" + if (replaceAll) " ($count replacements)." else ".",
            "Updated ${rel(f)} (+$added −$removed)")
    }

    private fun writeFile(path: String, content: String): Result {
        val f = resolve(path) ?: return outside(path)
        if (f.isDirectory) return Result(false, "Error: $path is a directory.", "Is a directory")
        if (rel(f).split('/').contains(".git")) return Result(false, "Error: writing inside .git is not allowed.", "Not allowed")
        val existed = f.isFile
        writeText(f, content)
        val n = content.lines().size
        return Result(true, (if (existed) "Overwrote " else "Created ") + "$path ($n lines).",
            (if (existed) "Overwrote " else "Created ") + "${rel(f)} ($n lines)")
    }

    /** Writes through the IDE document (undoable, keeps editors in sync), creating the file and folders if needed. */
    private fun writeText(f: File, text: String) {
        var error: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait({
            try {
                WriteCommandAction.writeCommandAction(project).withName("GapGPT: edit ${f.name}").run<Throwable> {
                    val dir = VfsUtil.createDirectories(f.parentFile.path)
                    val vf = dir.findChild(f.name) ?: dir.createChildData(project, f.name)
                    val doc = FileDocumentManager.getInstance().getDocument(vf) ?: throw IllegalStateException("Not a text file: ${f.name}")
                    doc.setText(text.replace("\r\n", "\n"))
                    FileDocumentManager.getInstance().saveDocument(doc)
                }
            } catch (t: Throwable) {
                error = t
            }
        }, ModalityState.defaultModalityState())
        error?.let { throw it }
    }

    // ---------------------------------------------------------------- command

    private fun runCommand(command: String, timeoutS: Int, ctx: RunContext): Result {
        val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command)
        else listOf(if (File("/bin/bash").canExecute()) "/bin/bash" else "/bin/sh", "-c", command)
        val pb = ProcessBuilder(shell).directory(root).redirectErrorStream(true)
        // The IDE may have been started from the Dock: take PATH etc. from the user's login shell.
        runCatching { pb.environment().putAll(EnvironmentUtil.getEnvironmentMap()) }
        pb.environment()["TERM"] = "dumb"
        pb.environment()["GIT_PAGER"] = "cat"
        pb.environment()["PAGER"] = "cat"
        val process = pb.start()
        ctx.process = process
        if (ctx.cancelled) ctx.cancel()
        process.outputStream.close()

        val head = StringBuilder()
        val tail = ArrayDeque<String>()
        var tailChars = 0
        var dropped = 0
        val reader = Thread {
            try {
                process.inputStream.bufferedReader(UTF_8).forEachLine { line ->
                    synchronized(head) {
                        if (head.length < OUTPUT_HEAD) head.append(line).append('\n')
                        else {
                            tail.addLast(line); tailChars += line.length + 1
                            while (tailChars > OUTPUT_TAIL) { tailChars -= tail.removeFirst().length + 1; dropped++ }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; name = "GapGPT command output"; start() }

        val finished = process.waitFor(timeoutS.toLong(), TimeUnit.SECONDS)
        if (!finished) ctx.cancel()
        reader.join(2000)
        val output = synchronized(head) {
            head.toString() + (if (dropped > 0) "… ($dropped lines omitted) …\n" else "") + tail.joinToString("\n")
        }.trimEnd()
        val exit = if (finished) process.exitValue() else -1
        val status = when {
            ctx.cancelled && finished -> "exit $exit"
            !finished && !ctx.cancelled -> "timed out after ${timeoutS}s"
            !finished -> "stopped"
            else -> "exit $exit"
        }
        val preview = output.lines().let { l -> l.take(DETAIL_LINES).joinToString("\n") + if (l.size > DETAIL_LINES) "\n… +${l.size - DETAIL_LINES} lines" else "" }
        return Result(finished && exit == 0, "Exit code: $exit${if (!finished) " ($status)" else ""}\n${output.ifEmpty { "(no output)" }}",
            status, preview.takeIf { output.isNotEmpty() })
    }

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".idea", ".intellijPlatform", "build", "out", "target", "dist", "node_modules",
            "__pycache__", ".venv", "venv", ".next", ".cache", "graphify-out",
        )
        private const val DEFAULT_READ_LINES = 800
        private const val MAX_READ_LINES = 2000
        private const val MAX_READ_LINE = 2000
        private const val MAX_FILE_BYTES = 2_000_000L
        private const val MAX_LIST = 500
        private const val MAX_GLOB = 300
        private const val MAX_GREP = 200
        private const val MAX_LINE = 300
        private const val PREVIEW_LINES = 40
        private const val DETAIL_LINES = 8
        private const val DEFAULT_TIMEOUT_S = 120
        private const val MAX_TIMEOUT_S = 600
        private const val OUTPUT_HEAD = 10_000
        private const val OUTPUT_TAIL = 20_000
        private const val MAX_GRAPHIFY = 30_000
    }
}
