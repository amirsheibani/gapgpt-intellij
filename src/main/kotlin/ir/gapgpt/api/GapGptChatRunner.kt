package ir.gapgpt.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import ir.gapgpt.PluginLog
import ir.gapgpt.cli.ClaudeCliRunner
import ir.gapgpt.settings.GapGptApi
import ir.gapgpt.settings.GapGptSettings
import ir.gapgpt.settings.I18n
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

/**
 * An agent that works with any GapGPT model through the OpenAI-compatible `POST /v1/chat/completions`
 * (SSE streaming + function calling). Each user turn loops: stream the model's reply → run the tool
 * calls it asked for ([GapGptTools]: read/edit files, run commands, IDE diagnostics) → send results back,
 * until the model answers without tools.
 *
 * Progress is reported to [listener] as Claude Code `stream-json` events (system/init, stream_event,
 * assistant, user/tool_result, result) so the webview, permission cards and diff preview work unchanged.
 */
class GapGptChatRunner(
    private val project: Project,
    private val chatId: String,
    private val listener: ClaudeCliRunner.Listener,
) : ChatBackend {

    /** Committed conversation (OpenAI message format), without the system prompt. */
    private val history = mutableListOf<JsonObject>()
    private val tools = GapGptTools(project, chatId)

    @Volatile
    private var body: InputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopped = false

    /** Models that rejected the `tools` parameter: chat without tools from then on. */
    private val noToolModels = mutableSetOf<String>()

    override var sessionId: String? = null
        private set

    /** `x-ratelimit-*` headers of the last API response (empty when the server sends none). */
    @Volatile
    var lastRateLimit: Map<String, String> = emptyMap()
        private set

    override val isRunning: Boolean get() = running

    override fun resetSession() {
        stop()
        synchronized(history) { history.clear() }
        sessionId = null
    }

    override fun stop() {
        if (!running) return
        stopped = true
        tools.cancel()
        runCatching { body?.close() }
    }

    override fun send(prompt: String, mcpConfigPath: String?) {
        if (running) return
        val s = GapGptSettings.get()
        val key = GapGptApi.apiKey
        if (key.isBlank()) {
            listener.onStartFailed("کلید API گپ‌جی‌پی‌تی وارد نشده است. آن را در Settings › Tools › GapGPT وارد کنید.")
            return
        }
        running = true
        stopped = false
        tools.cancelled = false
        val sid = sessionId ?: UUID.randomUUID().toString().also { sessionId = it }
        val model = s.gapgptModel.trim().ifBlank { GapGptApi.DEFAULT_MODEL }
        val text = if (s.normalizeArabicChars) ClaudeCliRunner.normalizePersian(prompt) else prompt

        ApplicationManager.getApplication().executeOnPooledThread {
            val started = System.currentTimeMillis()
            emit(JsonObject().apply {
                addProperty("type", "system")
                addProperty("subtype", "init")
                addProperty("backend", "gapgpt")
                addProperty("model", model)
                addProperty("session_id", sid)
                project.basePath?.let { addProperty("cwd", it) }
            })
            val turn = mutableListOf(message("user", text))
            val usage = Usage()
            var steps = 0
            var exitCode = 0
            var gotResult = false
            try {
                while (true) {
                    if (stopped) break
                    if (steps >= MAX_STEPS) {
                        turn += message("assistant", "(Stopped after $MAX_STEPS tool steps.)")
                        break
                    }
                    steps++
                    val useTools = s.gapgptTools && model !in noToolModels
                    val step = try {
                        streamStep(key, model, s, turn, useTools, sid, usage)
                    } catch (e: ToolsUnsupported) {
                        noToolModels += model
                        PluginLog.warn("gapgpt", "$model rejected tools; continuing without them: ${e.message}")
                        emitNotice("مدل $model از ابزارها (function calling) پشتیبانی نمی‌کند؛ فقط گفتگو انجام می‌شود.")
                        streamStep(key, model, s, turn, false, sid, usage)
                    } ?: break // stopped

                    turn += JsonObject().apply {
                        addProperty("role", "assistant")
                        if (step.text.isNotEmpty() || step.calls.isEmpty()) addProperty("content", step.text) else add("content", null)
                        if (step.calls.isNotEmpty()) add("tool_calls", JsonArray().apply {
                            step.calls.forEach { c ->
                                add(JsonObject().apply {
                                    addProperty("id", c.id)
                                    addProperty("type", "function")
                                    add("function", JsonObject().apply {
                                        addProperty("name", c.name)
                                        addProperty("arguments", c.args.ifBlank { "{}" })
                                    })
                                })
                            }
                        })
                    }
                    if (step.calls.isEmpty()) break

                    for (c in step.calls) {
                        val out = if (stopped) GapGptTools.Outcome("Interrupted by the user.", true)
                        else tools.run(c.name, c.args, c.id)
                        turn += JsonObject().apply {
                            addProperty("role", "tool")
                            addProperty("tool_call_id", c.id)
                            addProperty("content", out.content.ifEmpty { "(no output)" })
                        }
                        emitToolResult(sid, c.id, out)
                    }
                }
                if (stopped) {
                    exitCode = 143
                } else {
                    emit(result(sid, started, model, usage, steps, null))
                    gotResult = true
                }
            } catch (e: Exception) {
                if (stopped) {
                    exitCode = 143
                } else {
                    LOG.warn("GapGPT request failed", e)
                    PluginLog.warn("gapgpt", "request failed: ${e.message}")
                    emit(result(sid, started, model, usage, steps, e.message ?: e.javaClass.simpleName))
                    gotResult = true
                }
            } finally {
                commit(turn)
                body = null
                running = false
                PluginLog.info("gapgpt", "turn finished ($model, $steps steps) code=$exitCode")
                listener.onExit(exitCode, gotResult)
            }
        }
    }

    /**
     * Saves the turn so follow-up messages keep the context (including tool work done before a stop).
     * Every assistant tool call must be followed by its result, so missing results are filled in.
     */
    private fun commit(turn: List<JsonObject>) {
        val fixed = mutableListOf<JsonObject>()
        var i = 0
        while (i < turn.size) {
            val m = turn[i]
            fixed += m
            i++
            val calls = m.getAsJsonArray("tool_calls") ?: continue
            val answered = mutableSetOf<String>()
            while (i < turn.size && turn[i].get("role")?.asString == "tool") {
                answered += turn[i].get("tool_call_id").asString
                fixed += turn[i]
                i++
            }
            calls.forEach { c ->
                val id = c.asJsonObject.get("id").asString
                if (id !in answered) fixed += JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", id)
                    addProperty("content", "Interrupted by the user before this ran.")
                }
            }
        }
        if (fixed.size <= 1 && stopped) return // nothing but the user message: drop the aborted turn
        if (fixed.last().get("role")?.asString != "assistant") fixed += message("assistant", "(Interrupted by the user.)")
        synchronized(history) { history += fixed }
    }

    private class Call(var id: String, var name: String, val args: StringBuilder = StringBuilder()) {
        val argsText get() = args.toString()
    }

    private class Step(val text: String, val calls: List<StepCall>)
    private class StepCall(val id: String, val name: String, val args: String)
    private class Usage(
        var input: Long = 0, var cached: Long = 0, var output: Long = 0, var reasoning: Long = 0,
        var lastInput: Long = 0, var cost: Double = 0.0, var priced: Boolean = true,
    )
    private class ToolsUnsupported(message: String) : Exception(message)

    /** Streams one model reply. Returns null when stopped. Throws on HTTP / network errors. */
    private fun streamStep(
        key: String, model: String, s: GapGptSettings.State, turn: List<JsonObject>,
        useTools: Boolean, sid: String, usage: Usage,
    ): Step? {
        val messages = JsonArray()
        messages.add(message("system", systemPrompt(s, useTools)))
        synchronized(history) { history.forEach { messages.add(it) } }
        turn.forEach { messages.add(it) }

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("stream", true)
            add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
            if (useTools) {
                add("tools", tools.definitions())
                addProperty("tool_choice", "auto")
            }
        }
        val req = HttpRequest.newBuilder(URI("${GapGptApi.baseUrl()}/chat/completions"))
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer ${key.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), UTF_8))
            .build()
        PluginLog.info("gapgpt", "POST chat/completions model=$model messages=${messages.size()} tools=$useTools")
        val res = GapGptApi.httpClient().send(req, HttpResponse.BodyHandlers.ofInputStream())
        val stream = res.body()
        body = stream
        res.headers().map().filterKeys { it.lowercase().startsWith("x-ratelimit") }
            .mapValues { it.value.firstOrNull().orEmpty() }
            .takeIf { it.isNotEmpty() }?.let { lastRateLimit = it.mapKeys { e -> e.key.lowercase() } }
        if (stopped) return null
        if (res.statusCode() != 200) {
            val err = stream.use { it.readBytes().toString(UTF_8) }
            if (useTools && res.statusCode() in 400..422 && Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(err)) {
                throw ToolsUnsupported(GapGptApi.errorMessage(res.statusCode(), err))
            }
            throw IllegalStateException(GapGptApi.errorMessage(res.statusCode(), err))
        }

        val msgId = "gapgpt-" + UUID.randomUUID()
        emitStream(JsonObject().apply {
            addProperty("type", "message_start")
            add("message", JsonObject().apply {
                addProperty("id", msgId)
                addProperty("model", model)
                add("usage", JsonObject())
            })
        })

        val text = StringBuilder()
        var textOpen = false
        val calls = sortedMapOf<Int, Call>()
        val announced = mutableSetOf<Int>()
        var stepIn = 0L
        var stepCached = 0L
        var stepOut = 0L
        var stepReason = 0L
        try {
            stream.bufferedReader(UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                    obj.getAsJsonObject("error")?.let { err ->
                        throw IllegalStateException(err.get("message")?.asString ?: err.toString())
                    }
                    obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                        stepIn = u.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: stepIn
                        stepCached = (u.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")
                            ?: u.get("cache_read_input_tokens"))?.takeIf { it.isJsonPrimitive }?.asLong ?: stepCached
                        stepOut = u.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: stepOut
                        stepReason = u.getAsJsonObject("completion_tokens_details")?.get("reasoning_tokens")
                            ?.takeIf { it.isJsonPrimitive }?.asLong ?: stepReason
                    }
                    val delta = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("delta") ?: continue

                    val thinking = (delta.get("reasoning_content") ?: delta.get("reasoning"))
                        ?.takeIf { it.isJsonPrimitive }?.asString
                    if (!thinking.isNullOrEmpty()) emitDelta("thinking_delta", "thinking", thinking, -1)

                    val piece = delta.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!piece.isNullOrEmpty()) {
                        if (!textOpen) {
                            textOpen = true
                            emitStream(JsonObject().apply {
                                addProperty("type", "content_block_start")
                                addProperty("index", 0)
                                add("content_block", JsonObject().apply { addProperty("type", "text"); addProperty("text", "") })
                            })
                        }
                        text.append(piece)
                        emitDelta("text_delta", "text", piece, 0)
                    }

                    delta.getAsJsonArray("tool_calls")?.forEach { tcEl ->
                        val tc = tcEl.asJsonObject
                        val idx = tc.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: calls.size
                        val call = calls.getOrPut(idx) { Call("", "") }
                        tc.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { call.id = it }
                        val fn = tc.getAsJsonObject("function")
                        fn?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { call.name = it }
                        fn?.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString?.let { call.args.append(it) }
                        if (call.name.isNotEmpty() && idx !in announced) {
                            announced += idx
                            if (call.id.isEmpty()) call.id = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)
                            emitStream(JsonObject().apply {
                                addProperty("type", "content_block_start")
                                addProperty("index", idx + 1)
                                add("content_block", JsonObject().apply {
                                    addProperty("type", "tool_use")
                                    addProperty("id", call.id)
                                    addProperty("name", tools.displayName(call.name))
                                })
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (stopped) return null
            throw e
        }
        if (stopped) return null

        usage.input += stepIn
        usage.cached += stepCached
        usage.output += stepOut
        usage.reasoning += stepReason
        if (stepIn > 0) usage.lastInput = stepIn
        val stepCost = GapGptPricing.cost(model, stepIn, stepCached, stepOut)
        if (stepCost == null) usage.priced = false else usage.cost += stepCost
        runCatching { GapGptUsage.get().add(model, stepIn, stepCached, stepOut, stepReason, stepCost ?: 0.0) }

        val stepCalls = calls.values.filter { it.name.isNotEmpty() }.map { c ->
            if (c.id.isEmpty()) c.id = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)
            StepCall(c.id, c.name, c.argsText)
        }
        if (textOpen) emitStream(JsonObject().apply { addProperty("type", "content_block_stop"); addProperty("index", 0) })
        emitStream(JsonObject().apply {
            addProperty("type", "message_delta")
            add("usage", usageJson(stepOut, stepReason))
        })
        emit(JsonObject().apply {
            addProperty("type", "assistant")
            addProperty("session_id", sid)
            add("message", JsonObject().apply {
                addProperty("id", msgId)
                addProperty("model", model)
                add("content", JsonArray().apply {
                    if (text.isNotEmpty()) add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text.toString()) })
                    stepCalls.forEach { c ->
                        add(JsonObject().apply {
                            addProperty("type", "tool_use")
                            addProperty("id", c.id)
                            addProperty("name", tools.displayName(c.name))
                            add("input", runCatching { JsonParser.parseString(c.args.ifBlank { "{}" }) }.getOrNull()
                                ?.takeIf { it.isJsonObject } ?: JsonObject())
                        })
                    }
                })
                add("usage", usageJson(stepOut, stepReason).apply {
                    addProperty("input_tokens", stepIn - stepCached)
                    addProperty("cache_read_input_tokens", stepCached)
                })
            })
        })
        return Step(text.toString(), stepCalls)
    }

    private fun systemPrompt(s: GapGptSettings.State, useTools: Boolean): String {
        val sb = StringBuilder(BASE_PROMPT)
        sb.append("\n\nProject root: ").append(project.basePath ?: "(unknown)")
            .append("\nOS: ").append(SystemInfo.OS_NAME).append(' ').append(SystemInfo.OS_VERSION)
        if (useTools) {
            sb.append("\n\n").append(TOOLS_PROMPT)
            when (s.permissionMode) {
                "plan" -> sb.append("\n\nPLAN MODE: do not modify files or run commands. Investigate with read-only tools, " +
                    "then present a step-by-step plan for the user to approve.")
                "bypassPermissions", "acceptEdits" -> {}
                else -> sb.append("\nThe user is asked to approve every file change and command in the IDE; if they deny one, " +
                    "do not retry it — ask how to proceed.")
            }
        } else {
            sb.append("\n\nYou have no tools in this conversation: you cannot read or change files yourself. " +
                "Show code changes as fenced Markdown code blocks with the file path.")
        }
        sb.append("\n\n").append(I18n.replyInstruction(s.replyLanguage))
        return sb.toString()
    }

    private fun usageJson(out: Long, reason: Long) = JsonObject().apply {
        addProperty("output_tokens", out)
        if (reason > 0) add("output_tokens_details", JsonObject().apply { addProperty("thinking_tokens", reason) })
    }

    private fun result(sid: String, started: Long, model: String, u: Usage, steps: Int, error: String?) = JsonObject().apply {
        addProperty("type", "result")
        addProperty("subtype", if (error == null) "success" else "error")
        addProperty("is_error", error != null)
        error?.let { addProperty("result", it) }
        addProperty("session_id", sid)
        addProperty("duration_ms", System.currentTimeMillis() - started)
        if (steps > 1) addProperty("num_turns", steps)
        add("usage", usageJson(u.output, u.reasoning).apply { addProperty("input_tokens", u.input) })
        if (u.priced && (u.input > 0 || u.output > 0)) addProperty("total_cost_usd", u.cost)
        val limits = GapGptPricing.limits(model, GapGptApi.model(model))
        add("modelUsage", JsonObject().apply {
            add(model, JsonObject().apply {
                addProperty("contextWindow", limits.contextWindow)
                addProperty("inputTokens", u.input)
                addProperty("cacheReadInputTokens", u.cached)
                addProperty("outputTokens", u.output)
                if (u.priced) addProperty("costUSD", u.cost)
            })
        })
    }

    private fun emitToolResult(sid: String, id: String, out: GapGptTools.Outcome) = emit(JsonObject().apply {
        addProperty("type", "user")
        addProperty("session_id", sid)
        add("message", JsonObject().apply {
            addProperty("role", "user")
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", id)
                    addProperty("content", out.content)
                    addProperty("is_error", out.isError)
                })
            })
        })
    })

    /** A short assistant text block, used for engine notices. */
    private fun emitNotice(text: String) = emit(JsonObject().apply {
        addProperty("type", "assistant")
        add("message", JsonObject().apply {
            addProperty("id", "notice-" + UUID.randomUUID())
            add("content", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "> ⚠ $text") })
            })
        })
    })

    private fun emitDelta(type: String, field: String, value: String, index: Int) = emitStream(JsonObject().apply {
        addProperty("type", "content_block_delta")
        addProperty("index", index)
        add("delta", JsonObject().apply { addProperty("type", type); addProperty(field, value) })
    })

    private fun emitStream(event: JsonObject) = emit(JsonObject().apply {
        addProperty("type", "stream_event")
        add("event", event)
    })

    private fun emit(event: JsonObject) {
        try {
            listener.onEvent(event)
        } catch (e: Exception) {
            LOG.debug(e)
        }
    }

    companion object {
        private val LOG = logger<GapGptChatRunner>()
        private const val MAX_STEPS = 60

        private fun message(role: String, content: String) = JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }

        const val BASE_PROMPT =
            "You are an expert software engineering agent working inside the user's JetBrains IDE. Messages may start " +
                "with an <ide_context> block describing the file or selection the user is looking at; use it when relevant. " +
                "Be concise and precise. Use fenced Markdown code blocks with a language tag when you show code."

        const val TOOLS_PROMPT =
            "You can act on the project with tools:\n" +
                "- Explore with Glob, Grep, LS and Read before changing anything. Always Read a file before you Edit it.\n" +
                "- Change files with Edit (exact string replacement; copy old_string exactly from Read output, without " +
                "the line-number prefix) or Write (new files / full rewrites). Make the smallest change that solves the task " +
                "and follow the project's existing style.\n" +
                "- Run builds, tests, git and package managers with Bash (runs in the project root). Never run destructive " +
                "commands unless the user explicitly asked.\n" +
                "- After editing code, check getDiagnostics or run the build/tests when practical.\n" +
                "- Do the work with tools instead of telling the user to do it. When finished, briefly summarize what you " +
                "changed and anything the user should verify."
    }
}
