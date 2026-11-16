package ir.gapgpt.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GapGptSettings", storages = [Storage("gapgpt.xml")])
class GapGptSettings : PersistentStateComponent<GapGptSettings.State> {

    class State {
        /** Path to the `claude` executable. Empty = auto-detect. */
        var cliPath: String = ""
        /** Empty = Claude Code default model. e.g. "sonnet", "opus". */
        var model: String = ""
        /** Empty = CLI default. low | medium | high | xhigh | max */
        var effort: String = ""
        /** default | acceptEdits | plan | bypassPermissions */
        var permissionMode: String = "default"
        /** Open proposed edits in the IDE diff viewer automatically. */
        var autoOpenDiff: Boolean = true
        /** Share the current file / selection with Claude on every message. */
        var shareSelection: Boolean = true
        /** Guard: safety rules for tool calls. */
        var guardEnabled: Boolean = true
        var guardDisabledRules: List<String> = emptyList()
        /** Exact commands / paths the user whitelisted from Guard. */
        var guardWhitelist: List<String> = emptyList()
        /** Keep the chat scrolled to the bottom while Claude writes. */
        var autoFollow: Boolean = true
        /** Ask Claude to answer in Persian. */
        var respondInPersian: Boolean = true
        /** Replace Arabic ي/ك with Persian ی/ک before sending. */
        var normalizeArabicChars: Boolean = true
        /** Optional HTTPS proxy passed to the CLI, e.g. http://127.0.0.1:10809 */
        var httpsProxy: String = ""
        /** Extra CLI arguments, e.g. --add-dir ../shared */
        var extraArgs: String = ""
        var fontSize: Int = 14
        var sendOnEnter: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun get(): State =
            ApplicationManager.getApplication().getService(GapGptSettings::class.java).state
    }
}
