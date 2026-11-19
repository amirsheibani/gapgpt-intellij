package ir.gapgpt.chat

/** A function call requested by the model (OpenAI `tool_calls[]` entry). [arguments] is the raw JSON string. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * One chat message in the OpenAI-compatible format (`role` = system | user | assistant | tool).
 * An assistant message may carry [toolCalls]; a `tool` message answers the call with [toolCallId].
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/**
 * Message history of one chat tab. The system prompt is not stored: it is rebuilt for every request
 * (project rules may have changed) and passed to [snapshot].
 */
class Conversation {

    private val turns = ArrayList<ChatMessage>()

    @Volatile
    var inFlight: Boolean = false

    /** Summary of the turns removed by compaction; sent as part of the system prompt. */
    @Volatile
    var summary: String? = null
        private set

    /** Calibration from the last reply: prompt tokens the server counted for [lastPromptChars] characters. */
    @Volatile
    private var lastPromptTokens = 0L

    @Volatile
    private var lastPromptChars = 0

    @Synchronized
    fun addUser(text: String) {
        turns += ChatMessage("user", text)
        trim()
    }

    @Synchronized
    fun addAssistant(text: String, toolCalls: List<ToolCall> = emptyList()) {
        turns += ChatMessage("assistant", text, toolCalls)
    }

    @Synchronized
    fun addToolResult(callId: String, content: String) {
        turns += ChatMessage("tool", content, toolCallId = callId)
    }

    /**
     * Answers every tool call of the last assistant message that has no result yet (the turn was stopped
     * while tools were running). The API rejects a history with unanswered tool calls.
     */
    @Synchronized
    fun closeDanglingToolCalls(reason: String) {
        val idx = turns.indexOfLast { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        if (idx < 0) return
        val answered = turns.subList(idx + 1, turns.size).mapNotNull { it.toolCallId }.toSet()
        turns[idx].toolCalls.filter { it.id !in answered }.forEach { turns += ChatMessage("tool", reason, toolCallId = it.id) }
    }

    /** Drops the last user message when its request failed or was cancelled before any reply. */
    @Synchronized
    fun dropLastUser() {
        if (turns.lastOrNull()?.role == "user") turns.removeAt(turns.size - 1)
    }

    @Synchronized
    fun reset() {
        turns.clear()
        summary = null
        lastPromptTokens = 0
        lastPromptChars = 0
    }

    /** System prompt (if any, plus the compaction summary) followed by the retained turns. */
    @Synchronized
    fun snapshot(systemPrompt: String): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(turns.size + 1)
        val sys = listOfNotNull(
            systemPrompt.takeIf { it.isNotBlank() },
            summary?.let { "# Summary of the earlier conversation\n(Older messages were compacted into this summary. Continue the work from here.)\n\n$it" },
        ).joinToString("\n\n")
        if (sys.isNotBlank()) out += ChatMessage("system", sys)
        out += turns
        return out
    }

    /** Copy of the stored messages (without the system prompt), for saving. */
    @Synchronized
    fun export(): List<ChatMessage> = ArrayList(turns)

    /** Replaces the history with a saved one; dangling tool calls (run stopped mid-way) are answered. */
    @Synchronized
    fun restore(savedSummary: String?, messages: List<ChatMessage>) {
        reset()
        turns += messages
        while (turns.isNotEmpty() && turns[0].role != "user") turns.removeAt(0)
        summary = savedSummary?.takeIf { it.isNotBlank() }
        closeDanglingToolCalls("Cancelled: the run ended before this tool call finished.")
    }

    @Synchronized
    fun isEmpty() = turns.isEmpty() && summary == null

    // ---------------------------------------------------------------- compaction

    /** Records the prompt size the server reported for a request of [messages] (improves [estimateTokens]). */
    fun calibrate(messages: List<ChatMessage>, promptTokens: Long) {
        if (promptTokens <= 0) return
        lastPromptChars = chars(messages)
        lastPromptTokens = promptTokens
    }

    /** Approximate tokens of a request; uses the server's last count when available, else ~3 chars per token. */
    fun estimateTokens(messages: List<ChatMessage>): Long {
        val c = chars(messages)
        val ratio = if (lastPromptChars > 1000 && lastPromptTokens > 0) lastPromptTokens.toDouble() / lastPromptChars else 1.0 / 3
        return (c * ratio).toLong()
    }

    private fun chars(messages: List<ChatMessage>) =
        messages.sumOf { m -> m.content.length + m.toolCalls.sumOf { it.arguments.length + it.name.length } }

    /**
     * Replaces the output of all but the [keepLast] most recent tool results with a short note (the model can call
     * the tool again). Cheap: no request. Returns the number of characters removed.
     */
    @Synchronized
    fun pruneToolResults(keepLast: Int): Int {
        val idx = turns.indices.filter { turns[it].role == "tool" && turns[it].content.length > PRUNE_MIN }
        var saved = 0
        for (i in idx.dropLast(keepLast.coerceAtLeast(0))) {
            val m = turns[i]
            saved += m.content.length - PRUNED.length
            turns[i] = m.copy(content = PRUNED)
        }
        return saved
    }

    /** Messages before the latest user message: what a summary can replace. Empty if there is nothing older. */
    @Synchronized
    fun compactable(): List<ChatMessage> {
        val last = turns.indexOfLast { it.role == "user" }
        return if (last <= 0) emptyList() else ArrayList(turns.subList(0, last))
    }

    /** Replaces the first [count] messages (as returned by [compactable]) with [newSummary]. */
    @Synchronized
    fun applySummary(newSummary: String, count: Int) {
        if (count <= 0 || count > turns.size) return
        turns.subList(0, count).clear()
        while (turns.isNotEmpty() && turns[0].role != "user") turns.removeAt(0)
        summary = newSummary.trim()
    }

    /**
     * Drops whole old turns (a user message and everything up to the next user message) while the history is too
     * long. The latest turn is always kept, so an agent run with many tool calls never loses its own question.
     */
    private fun trim() {
        while (turns.size > MAX_MESSAGES || turns.sumOf { it.content.length } > MAX_CHARS) {
            val next = (1 until turns.size).firstOrNull { turns[it].role == "user" } ?: break
            turns.subList(0, next).clear()
        }
        while (turns.isNotEmpty() && turns[0].role != "user") turns.removeAt(0)
    }

    companion object {
        const val MAX_MESSAGES = 200
        const val MAX_CHARS = 600_000
        private const val PRUNE_MIN = 400
        const val PRUNED = "[Output removed to save context. Call the tool again if you need it.]"
    }
}
