package ir.gapgpt.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import ir.gapgpt.I18n
import ir.gapgpt.PluginLog
import ir.gapgpt.context.InstructionItem
import ir.gapgpt.context.InstructionsImporter
import java.io.File

/** Tools menu: copies rules and skills from other AI tools' folders into `.gapgpt/` after a preview (originals stay). */
class ImportInstructionsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
        e.presentation.text = I18n.tr("GapGPT: Import Rules & Skills", "GapGPT: کپی قوانین و اسکیل‌ها")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = project.basePath ?: return
        // Scanning walks the file system: keep it off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = try {
                InstructionsImporter.scan(base)
            } catch (ex: Exception) {
                PluginLog.warn("import", "Scan failed: ${ex.javaClass.simpleName}")
                notify(project, I18n.tr("Scanning failed.", "جست‌وجو ناموفق بود."), NotificationType.ERROR)
                return@executeOnPooledThread
            }
            if (items.isEmpty()) {
                notify(
                    project,
                    I18n.tr("No rules or skills to import were found.", "قانون یا اسکیل تازه‌ای برای کپی پیدا نشد."),
                    NotificationType.INFORMATION,
                )
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({ confirmAndCopy(project, base, items) }, project.disposed)
        }
    }

    private fun confirmAndCopy(project: Project, base: String, items: List<InstructionItem>) {
        val preview = items.take(PREVIEW_ROWS).joinToString("\n") { "• ${it.kind}: ${it.name}  (${rel(base, it.source)} → ${rel(base, it.target)})" } +
            if (items.size > PREVIEW_ROWS) "\n… +${items.size - PREVIEW_ROWS}" else ""
        val yes = Messages.showYesNoDialog(
            project,
            I18n.tr("Copy ${items.size} item(s) into .gapgpt/ ? The originals stay where they are.\n\n$preview", "${items.size} مورد در پوشهٔ .gapgpt/ کپی شود؟ فایل‌های اصلی دست نمی‌خورند.\n\n$preview"),
            I18n.tr("Import Rules & Skills", "کپی قوانین و اسکیل‌ها"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!yes) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Re-scan: the files may have changed while the dialog was open.
                val res = InstructionsImporter.copy(base, InstructionsImporter.scan(base))
                PluginLog.info("import", "copied ${res.copied.size}, skipped ${res.skipped.size}")
                val skipped = if (res.skipped.isEmpty()) "" else I18n.tr(" (${res.skipped.size} skipped)", " (${res.skipped.size} رد شد)")
                notify(
                    project,
                    I18n.tr("Copied ${res.copied.size} item(s) into .gapgpt/", "${res.copied.size} مورد در .gapgpt/ کپی شد") + skipped,
                    if (res.skipped.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
                )
            } catch (ex: Exception) {
                PluginLog.warn("import", "Copy failed: ${ex.javaClass.simpleName}")
                notify(project, I18n.tr("Import failed.", "کپی ناموفق بود."), NotificationType.ERROR)
            }
        }
    }

    private fun rel(base: String, f: File): String =
        try {
            f.relativeTo(File(base)).path.takeIf { !it.startsWith("..") } ?: f.path
        } catch (e: Exception) {
            f.path
        }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("GapGPT")
            .createNotification(text, type).notify(project)
    }

    companion object {
        private const val PREVIEW_ROWS = 15
    }
}
