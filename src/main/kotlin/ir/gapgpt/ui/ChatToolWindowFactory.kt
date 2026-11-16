package ir.gapgpt.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.SwingConstants

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "GapGPT"
        val factory = ContentFactory.getInstance()
        val jcefSupported = try {
            JBCefApp.isSupported()
        } catch (e: LinkageError) {
            false
        }
        if (!jcefSupported) {
            val label = JBLabel(
                ir.gapgpt.settings.I18n.t(
                    "<html><div style='padding:16px'>This IDE does not support JCEF.<br>" +
                        "Make sure the \"Web Browser (JCEF)\" plugin is enabled, or pick a JBR with JCEF via Help › Find Action › " +
                        "\"Choose Boot Java Runtime\".</div></html>",
                    "<html><div dir='rtl' style='padding:16px'>این IDE از JCEF پشتیبانی نمی‌کند.<br>" +
                        "مطمئن شوید پلاگین «Web Browser (JCEF)» فعال است، یا از Help › Find Action › " +
                        "«Choose Boot Java Runtime» یک JBR با JCEF انتخاب کنید.</div></html>",
                ),
                SwingConstants.CENTER,
            )
            toolWindow.contentManager.addContent(factory.createContent(label, "", false))
            return
        }
        val panel = ChatPanel(project)
        val content = factory.createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        ChatPanelHolder.getInstance(project).panel = panel
    }

    companion object {
        const val ID = "GapGpt"

        /** Opens the tool window and runs [block] with the chat panel once it exists. */
        fun withPanel(project: Project, block: (ChatPanel) -> Unit) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.activate {
                ChatPanelHolder.getInstance(project).panel?.let(block)
            }
        }
    }
}

@Service(Service.Level.PROJECT)
class ChatPanelHolder {
    var panel: ChatPanel? = null

    companion object {
        fun getInstance(project: Project): ChatPanelHolder = project.getService(ChatPanelHolder::class.java)
    }
}
