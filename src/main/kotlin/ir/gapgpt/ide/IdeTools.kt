package ir.gapgpt.ide

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.net.URI

/** IDE tools exposed to Claude over [IdeMcpServer]. */
class IdeTools(private val project: Project) {

    private val gson = Gson()

    fun definitions(): JsonArray = JsonArray().apply {
        add(tool(
            "getDiagnostics",
            "Get the IDE's current errors and warnings (red/yellow squiggles) for a file, or for all files " +
                "open in the editor when no file is given. Use after editing to verify the code compiles.",
            props = mapOf("uri" to prop("string", "Absolute file path or file:// URI. Omit for all open files.")),
        ))
        add(tool("getOpenEditors", "List the files currently open in IDE editor tabs, and which one is active."))
        add(tool(
            "getCurrentSelection",
            "Get the file the user is looking at in the IDE and the text they have selected (if any).",
        ))
        add(tool("getWorkspaceFolders", "Get the project root / content root folders open in the IDE."))
        add(tool(
            "openFile",
            "Open a file in the IDE editor (optionally at a line) so the user can see it.",
            props = mapOf(
                "filePath" to prop("string", "Absolute or project-relative path"),
                "line" to prop("integer", "1-based line to place the caret on"),
            ),
            required = listOf("filePath"),
        ))
        add(tool(
            IdeMcpServer.PERMISSION_TOOL,
            "Internal: asks the IDE user to approve a tool call. Do not call directly.",
            props = mapOf(
                "tool_name" to prop("string", "Tool requesting permission"),
                "input" to JsonObject().apply { addProperty("type", "object") },
                "tool_use_id" to prop("string", "Tool use id"),
            ),
            required = listOf("tool_name", "input"),
        ))
    }

    fun call(name: String, args: JsonObject): String = when (name) {
        "getDiagnostics" -> diagnostics(args.get("uri")?.asString)
        "getOpenEditors" -> openEditors()
        "getCurrentSelection" -> currentSelection()
        "getWorkspaceFolders" -> workspaceFolders()
        "openFile" -> openFile(args.get("filePath")?.asString.orEmpty(), args.get("line")?.asInt ?: 0)
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    // ------------------------------------------------------------------ tools

    private fun diagnostics(uri: String?): String {
        val files: List<VirtualFile> = if (uri.isNullOrBlank()) {
            FileEditorManager.getInstance(project).openFiles.toList()
        } else {
            listOfNotNull(findFile(uri))
        }
        val out = JsonArray()
        ReadAction.run<RuntimeException> {
            for (vf in files) {
                val doc = FileDocumentManager.getInstance().getDocument(vf) ?: continue
                val items = JsonArray()
                DaemonCodeAnalyzerEx.processHighlights(doc, project, HighlightSeverity.WEAK_WARNING, 0, doc.textLength) { info ->
                    if (info.description != null) items.add(diag(doc, info))
                    true
                }
                out.add(JsonObject().apply {
                    addProperty("file", vf.path)
                    add("diagnostics", items)
                })
            }
        }
        if (out.isEmpty) return "No diagnostics available (the file may not be open in the editor)."
        return gson.toJson(out)
    }

    private fun diag(doc: Document, info: HighlightInfo) = JsonObject().apply {
        val line = doc.getLineNumber(info.startOffset)
        addProperty("severity", when {
            info.severity >= HighlightSeverity.ERROR -> "error"
            info.severity >= HighlightSeverity.WARNING -> "warning"
            else -> "info"
        })
        addProperty("line", line + 1)
        addProperty("column", info.startOffset - doc.getLineStartOffset(line) + 1)
        addProperty("message", info.description)
    }

    private fun openEditors(): String {
        val fem = FileEditorManager.getInstance(project)
        val selected = fem.selectedFiles.firstOrNull()
        val arr = JsonArray()
        fem.openFiles.forEach { f ->
            arr.add(JsonObject().apply {
                addProperty("path", f.path)
                addProperty("relativePath", relPath(f))
                addProperty("active", f == selected)
                addProperty("unsaved", FileDocumentManager.getInstance().isFileModified(f))
            })
        }
        return gson.toJson(arr)
    }

    private fun currentSelection(): String {
        val snap = SelectionTracker.getInstance(project).snapshot()
            ?: return "No file is open in the editor."
        return gson.toJson(JsonObject().apply {
            addProperty("filePath", snap.path)
            addProperty("relativePath", snap.relPath)
            if (snap.text != null) {
                addProperty("startLine", snap.startLine)
                addProperty("endLine", snap.endLine)
                addProperty("text", snap.text)
            } else {
                addProperty("caretLine", snap.startLine)
                addProperty("selection", "none")
            }
        })
    }

    private fun workspaceFolders(): String = ReadAction.compute<String, RuntimeException> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.map { it.path }
            .ifEmpty { listOfNotNull(project.basePath) }
        gson.toJson(roots)
    }

    private fun openFile(path: String, line: Int): String {
        val vf = findFile(path) ?: return "File not found: $path"
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, vf, maxOf(0, line - 1), 0).navigate(true)
        }
        return "Opened ${vf.path}"
    }

    // ------------------------------------------------------------------ helpers

    fun findFile(pathOrUri: String): VirtualFile? {
        val path = if (pathOrUri.startsWith("file:")) runCatching { File(URI(pathOrUri)).path }.getOrNull() ?: return null
        else pathOrUri
        val f = File(path).let { if (it.isAbsolute) it else File(project.basePath ?: "", path) }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
    }

    private fun relPath(f: VirtualFile): String =
        project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(f, it) } ?: f.path

    private fun prop(type: String, description: String) = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }

    private fun tool(
        name: String,
        description: String,
        props: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
    ) = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
            if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } })
        })
    }
}
