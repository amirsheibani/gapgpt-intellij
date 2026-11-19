package ir.gapgpt.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.Alarm

/**
 * Tracks which file the user is looking at and what is selected, so it can be shared
 * with the assistant automatically ("selection context").
 */
@Service(Service.Level.PROJECT)
class SelectionTracker(private val project: Project) : Disposable {

    data class Snapshot(
        val path: String,
        val relPath: String,
        val language: String,
        /** 1-based. Caret line when there is no selection. */
        val startLine: Int,
        val endLine: Int,
        /** Selected text, or null when nothing is selected. */
        val text: String?,
    )

    @Volatile
    private var snap: Snapshot? = null
    private val listeners = mutableListOf<(Snapshot?) -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var started = false

    fun snapshot(): Snapshot? = snap

    /** Registers [l] (called on the EDT) until [parent] is disposed. Starts tracking on first use. */
    fun addListener(parent: Disposable, l: (Snapshot?) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            start()
            listeners += l
            com.intellij.openapi.util.Disposer.register(parent) { listeners -= l }
            l(snap)
        }
    }

    private fun start() {
        if (started) return
        started = true
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (e.editor.project == project) schedule()
            }
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor.project == project) schedule()
            }
        }, this)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = schedule()
            },
        )
        update()
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ update() }, 250)
    }

    private fun update() {
        if (project.isDisposed) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val new = if (editor == null || file == null || !file.isInLocalFileSystem) {
            null
        } else {
            val doc = editor.document
            val sel = editor.selectionModel
            val base = project.guessProjectDir()
            val rel = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
            if (sel.hasSelection()) {
                val start = doc.getLineNumber(sel.selectionStart) + 1
                val end = doc.getLineNumber(maxOf(sel.selectionStart, sel.selectionEnd - 1)) + 1
                val text = sel.selectedText.orEmpty().let {
                    if (it.length > MAX_SELECTION) it.take(MAX_SELECTION) + "\n…(truncated)" else it
                }
                Snapshot(file.path, rel, file.extension.orEmpty(), start, end, text)
            } else {
                val line = editor.caretModel.logicalPosition.line + 1
                Snapshot(file.path, rel, file.extension.orEmpty(), line, line, null)
            }
        }
        if (new == snap) return
        snap = new
        listeners.toList().forEach { it(new) }
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        private const val MAX_SELECTION = 20_000

        fun getInstance(project: Project): SelectionTracker = project.getService(SelectionTracker::class.java)
    }
}
