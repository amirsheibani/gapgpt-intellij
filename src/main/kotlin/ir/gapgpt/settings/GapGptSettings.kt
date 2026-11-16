package ir.gapgpt.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GapGptSettings", storages = [Storage("gapgpt.xml")])
class GapGptSettings : PersistentStateComponent<GapGptSettings.State> {

    class State {

        /**
         * Which engine answers chats: [BACKEND_GAPGPT] talks to the GapGPT API directly (all its models);
         * [BACKEND_CLAUDE] runs the local Claude Code CLI exactly as installed, untouched.
         */
        var backend: String = BACKEND_GAPGPT
        /** GapGPT OpenAI-compatible base URL. The API key is in the password safe, see [GapGptApi]. */
        var gapgptBaseUrl: String = GapGptApi.DEFAULT_BASE_URL
        /** Model used by the GapGPT engine (independent of the Claude Code [model]). */
        var gapgptModel: String = GapGptApi.DEFAULT_MODEL
        /** Let GapGPT models read/edit files, run commands and query the IDE (function calling). */
        var gapgptTools: Boolean = true
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
        /** Interface language of the plugin: "en" (default) or "fa". */
        var uiLanguage: String = "en"
        /** Reply language: "auto" = same as the user's message, "fa" or "en". */
        var replyLanguage: String = "auto"
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
        const val BACKEND_GAPGPT = "gapgpt"
        const val BACKEND_CLAUDE = "claude"

        fun get(): State =
            ApplicationManager.getApplication().getService(GapGptSettings::class.java).state
    }
}
