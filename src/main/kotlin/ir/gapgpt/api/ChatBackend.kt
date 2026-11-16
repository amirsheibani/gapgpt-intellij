package ir.gapgpt.api

/**
 * One chat's answering engine. Implementations report progress through
 * [ir.gapgpt.cli.ClaudeCliRunner.Listener] using Claude Code's `stream-json` event shapes,
 * so the webview renders every engine the same way.
 */
interface ChatBackend {
    val isRunning: Boolean
    val sessionId: String?

    /** @param mcpConfigPath only used by the Claude Code CLI engine. */
    fun send(prompt: String, mcpConfigPath: String? = null)
    fun stop()
    fun resetSession()
}
