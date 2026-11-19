package ir.gapgpt.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "GapGptSettings", storages = [Storage("gapgpt.xml")])
class GapGptSettings : PersistentStateComponent<GapGptSettings.State> {

    class State {
        /** OpenAI-compatible base URL of the GapGPT API (without `/chat/completions`). CDN alternative: `https://api.gapapi.com/v1`. */
        var baseUrl: String = "https://api.gapgpt.app/v1"
        var model: String = "claude-sonnet-5-5"
        /** Chat models last fetched from `GET {baseUrl}/models`; empty until fetched. */
        var knownModels: MutableList<String> = mutableListOf()
        var maxCompletionTokens: Int = 8000
        /** Ask the server for a streamed (SSE) reply. */
        var streaming: Boolean = true
        /** Extra system prompt appended after the language prompt and the project rules. */
        var systemPrompt: String = ""
        /** Share the current file / selection with the assistant on every message. */
        var shareSelection: Boolean = true
        /** Keep the chat scrolled to the bottom while the assistant writes. */
        var autoFollow: Boolean = true
        /** The agent uses the project's graphify code graph on every message (the graphify button in the chat toolbar). */
        var useGraphify: Boolean = false
        /** Keep the graph in the project's `graphify-out/` (shared with a graphify CLI) instead of the IDE system folder. */
        var graphInProject: Boolean = false

        /** UI language: "en" (default) or "fa". */
        var language: String = "en"
        /** Ask the assistant to answer in the language the user writes in (Persian or English). */
        var matchReplyLanguage: Boolean = true
        /** Replace Arabic ي/ك with Persian ی/ک before sending. */
        var normalizeArabicChars: Boolean = true
        /** Optional HTTP(S) proxy, e.g. http://127.0.0.1:10809 */
        var httpsProxy: String = ""
        var fontSize: Int = 14
        var sendOnEnter: Boolean = true

        /** Chat works as an agent: the model may call tools (read/search files, edit, run commands). */
        var agentTools: Boolean = true
        /** Apply file edits without asking. */
        var autoApproveEdits: Boolean = false
        /** Run shell commands without asking. */
        var autoApproveCommands: Boolean = false
        /** Open IntelliJ's diff viewer automatically when an edit waits for approval. */
        var showDiffOnApproval: Boolean = true
        /** Start MCP servers from .mcp.json / ~/.gapgpt/mcp.json and offer their tools to the agent. */
        var mcpEnabled: Boolean = true
        /** Maximum model requests (tool rounds) per message. */
        var maxAgentSteps: Int = 40
        /** Compact the conversation automatically when it gets close to [contextLimitTokens]. */
        var autoCompact: Boolean = true
        /** Context budget in tokens; compaction starts at 60% of it. Lower = cheaper requests. */
        var contextLimitTokens: Int = 100_000
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
