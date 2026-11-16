package ir.gapgpt.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import ir.gapgpt.settings.I18n
import java.io.File

/**
 * The before/after content of a proposed Edit / MultiEdit / Write tool call, shown in the
 * IDE diff viewer. The right-hand side is editable: if the user tweaks it before accepting,
 * [finalInput] rewrites the tool input so Claude writes the user's version.
 */
class DiffPreview private constructor(
    private val project: Project,
    val toolName: String,
    private val input: JsonObject,
    val filePath: String,
    /** Raw on-disk content (may contain CRLF). Empty for a new file. */
    private val originalRaw: String,
    val isNewFile: Boolean,
    private val proposedDisplay: String,
) {
    private val lineSep = if (originalRaw.contains("\r\n")) "\r\n" else "\n"
    private val originalDisplay = StringUtil.convertLineSeparators(originalRaw)

    private var document: Document? = null
    private var diffFile: VirtualFile? = null

    val fileName: String get() = File(filePath).name

    /** Number of added / removed lines (rough, for the chat card). */
    val stats: Pair<Int, Int> by lazy {
        val a = originalDisplay.lines()
        val b = proposedDisplay.lines()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        (b.size - p - s) to (a.size - p - s)
    }

    /** Opens the diff in an editor tab with Accept / Reject toolbar buttons. Must be called on the EDT. */
    fun open(onAccept: () -> Unit, onReject: () -> Unit) {
        diffFile?.let { f ->
            FileEditorManager.getInstance(project).openFile(f, true)
            return
        }
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(project, originalDisplay, fileType)
        val doc = document ?: EditorFactory.getInstance().createDocument(proposedDisplay).also { document = it }
        val right = factory.create(project, doc, fileType)
        val request = SimpleDiffRequest(
            "GapGPT ✎ $fileName",
            left,
            right,
            if (isNewFile) I18n.t("(new file)", "(فایل جدید)") else I18n.t("Current version", "نسخهٔ فعلی"),
            I18n.t("Proposed change (editable)", "پیشنهاد (قابل ویرایش)"),
        )
        request.putUserData(
            DiffUserDataKeys.CONTEXT_ACTIONS,
            listOf<AnAction>(
                action(I18n.t("Accept Changes", "پذیرش تغییرات"), "Accept Claude's change (including your edits on the right side)", AllIcons.Actions.Checked, onAccept),
                action(I18n.t("Reject Changes", "رد تغییرات"), "Reject Claude's change", AllIcons.Actions.Cancel, onReject),
            ),
        )
        val vf = ChainDiffVirtualFile(SimpleDiffRequestChain(request), "GapGPT ✎ $fileName")
        diffFile = vf
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(vf, true)
    }

    fun close() {
        val f = diffFile ?: return
        diffFile = null
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) FileEditorManager.getInstance(project).closeFile(f)
        }
    }

    /** True if the user edited the proposed content in the diff viewer. */
    fun userModified(): Boolean = document?.let { it.text != proposedDisplay } ?: false

    /** Tool input to send back to Claude: unchanged, or rewritten to apply the user's edited version. */
    fun finalInput(): JsonObject {
        val edited = document?.text ?: return input
        if (edited == proposedDisplay) return input
        val newRaw = if (lineSep == "\n") edited else edited.replace("\n", lineSep)
        return when (toolName) {
            "Write" -> input.deepCopy().apply { addProperty("content", newRaw) }
            "Edit" -> JsonObject().apply {
                addProperty("file_path", filePath)
                addProperty("old_string", originalRaw)
                addProperty("new_string", newRaw)
                addProperty("replace_all", false)
            }
            "MultiEdit" -> JsonObject().apply {
                addProperty("file_path", filePath)
                add("edits", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("old_string", originalRaw)
                        addProperty("new_string", newRaw)
                    })
                })
            }
            else -> input
        }
    }

    private fun action(text: String, desc: String, icon: javax.swing.Icon, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, desc, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    companion object {
        val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write")

        /** Computes the preview, or null if this isn't a file edit we can simulate. */
        fun compute(project: Project, toolName: String, input: JsonObject): DiffPreview? {
            if (toolName !in EDIT_TOOLS) return null
            val path = input.get("file_path")?.asString ?: return null
            val file = File(path)
            val exists = file.isFile
            val original = if (exists) runCatching { file.readText() }.getOrNull() ?: return null else ""

            val proposedRaw: String = when (toolName) {
                "Write" -> input.get("content")?.asString ?: return null
                "Edit" -> applyEdit(
                    original,
                    input.get("old_string")?.asString ?: return null,
                    input.get("new_string")?.asString ?: return null,
                    input.get("replace_all")?.asBoolean ?: false,
                ) ?: return null
                "MultiEdit" -> {
                    var cur = original
                    val edits = input.getAsJsonArray("edits") ?: return null
                    for (e in edits) {
                        val o = e.asJsonObject
                        cur = applyEdit(
                            cur,
                            o.get("old_string")?.asString ?: return null,
                            o.get("new_string")?.asString ?: return null,
                            o.get("replace_all")?.asBoolean ?: false,
                        ) ?: return null
                    }
                    cur
                }
                else -> return null
            }
            return DiffPreview(
                project, toolName, input, path, original, !exists,
                StringUtil.convertLineSeparators(proposedRaw),
            )
        }

        private fun applyEdit(text: String, old: String, new: String, all: Boolean): String? {
            if (old.isEmpty()) return if (text.isEmpty()) new else null
            // Claude sends LF strings; tolerate CRLF files.
            val crlf = text.contains("\r\n")
            val o = if (crlf && !old.contains("\r\n")) old.replace("\n", "\r\n") else old
            val n = if (crlf && !new.contains("\r\n")) new.replace("\n", "\r\n") else new
            val idx = text.indexOf(o)
            if (idx < 0) return null
            return if (all) text.replace(o, n) else text.substring(0, idx) + n + text.substring(idx + o.length)
        }
    }
}
