package ir.gapgpt.ui

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.util.SystemInfo
import ir.gapgpt.I18n
import ir.gapgpt.PluginLog
import ir.gapgpt.agent.AgentTools
import ir.gapgpt.api.GapGptClient
import ir.gapgpt.chat.ChatMessage
import ir.gapgpt.chat.Conversation
import ir.gapgpt.chat.ToolCall
import ir.gapgpt.chat.Prompts
import ir.gapgpt.chat.SessionStore
import ir.gapgpt.context.InstructionsImporter
import ir.gapgpt.context.ProjectInstructions
import ir.gapgpt.ide.GitInfo
import ir.gapgpt.ide.GraphifySetup
import ir.gapgpt.ide.ProjectFileSearch
import ir.gapgpt.ide.SelectionTracker
import ir.gapgpt.api.GapGptPricing
import ir.gapgpt.api.GapGptUsage
import ir.gapgpt.mcp.McpManager
import ir.gapgpt.mcp.McpServerConfig
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import ir.gapgpt.settings.ApiKeyStore
import ir.gapgpt.settings.GapGptSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JComponent

/**
 * The GapGPT tool window: a JCEF page hosting several chats (tabs). Each chat has its own
 * [Conversation]; replies come from [GapGptClient]. Messages to the page carry the chat id.
 */
class ChatPanel(private val project: Project) : Disposable {

    /**
     * One user message being answered: one or more model requests with tool calls in between.
     * [finished] makes sure it is finalized exactly once (reply, error or stop).
     */
    private class Turn(val chat: String) {
        /** Text streamed in the current step (reset at every model request). */
        val partial = StringBuilder()
        val finished = AtomicBoolean(false)

        @Volatile
        var handle: GapGptClient.Handle? = null

        /** Lets Stop kill a running command. */
        val run = AgentTools.RunContext()

        /** Tool calls waiting for the user's Allow / Deny, by call id. */
        val approvals = ConcurrentHashMap<String, CompletableFuture<String>>()

        /** The edits behind [approvals], so the page can ask for their diff. */
        val pendingEdits = ConcurrentHashMap<String, AgentTools.Prepared>()

        @Volatile
        var steps = 0
        val usage = LongArray(3) // prompt, completion, total

        /** Estimated cost (USD) of the priced steps, and whether any step used a model without a price. Guarded by [usage]. */
        var cost = 0.0
        var unpriced = false

        /** Plan mode: read-only tools until the user approves the plan (exit_plan_mode). */
        @Volatile
        var plan = false

        /** The graphify tool is offered for this turn (the button is on, or the user sent `/graphify <question>`). */
        @Volatile
        var graphify = false

        /** [graphify] came from the explicit `/graphify <question>` command, not from the toolbar button. */
        @Volatile
        var graphifyExplicit = false

        /** At least one edit_file / write_file call succeeded in this turn. */
        @Volatile
        var appliedEdit = false
    }

    private val gson = Gson()
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var pageReady = false
    private val pending = ConcurrentLinkedQueue<String>()
    private val lock = Any()

    private val conversations = ConcurrentHashMap<String, Conversation>()

    /** The saved session behind a chat tab: its id and the transcript the page shows (for /resume). */
    private class ChatSession(
        val id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "",
        val created: Long = System.currentTimeMillis(),
        val transcript: MutableList<JsonObject> = ArrayList(),
    )

    private val sessions = ConcurrentHashMap<String, ChatSession>()
    private val turns = ConcurrentHashMap<String, Turn>()
    /** Chats already told that a message went out without graphify because the graph is not ready. */
    private val graphNoticed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val tracker = SelectionTracker.getInstance(project)
    private val agentTools by lazy { AgentTools(project) }

    /** Approval keys ("edit", "command", "mcp:<server>") the user allowed for the rest of this IDE session. */
    private val sessionApproved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val mcp by lazy { McpManager.getInstance(project) }

    /** Set when the server rejected the `tools` field; later requests are sent as plain chat. */
    @Volatile
    private var toolsUnsupported = false
    private val logListener: (PluginLog.Line) -> Unit = { post("logLine", lineJson(it)) }

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, jsQuery)

        jsQuery.addHandler { raw ->
            try {
                handleFromJs(JsonParser.parseString(raw).asJsonObject)
            } catch (e: Exception) {
                PluginLog.warn("bridge", "JS message failed: ${e.message}")
                post("error", JsonObject().apply { addProperty("text", I18n.tr("Internal error: ", "خطای داخلی: ") + e.message) })
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                cefBrowser.executeJavaScript(
                    "window.__toIde = function(m){ ${jsQuery.inject("m")} };",
                    cefBrowser.url, 0,
                )
                synchronized(lock) {
                    pageReady = true
                    while (true) {
                        val js = pending.poll() ?: break
                        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
                    }
                }
                sendInit()
                pooled { sendKeyStatus() }
            }
        }, browser.cefBrowser)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { sendInit() })

        tracker.addListener(this) { snap -> post("context", contextJson(snap)) }
        PluginLog.addListener(logListener)

        browser.loadHTML(buildHtml())
    }

    private fun conversation(chat: String): Conversation = conversations.getOrPut(chat) { Conversation() }

    private fun chatJson(chat: String) = JsonObject().apply { addProperty("chat", chat) }

    // ---------------------------------------------------------------- IDE context (selection sharing)

    private fun contextJson(snap: SelectionTracker.Snapshot?) = JsonObject().apply {
        if (snap == null) return@apply
        addProperty("file", snap.relPath)
        addProperty("startLine", snap.startLine)
        addProperty("endLine", snap.endLine)
        addProperty("hasSelection", snap.text != null)
    }

    /** Prepends what the user is looking at in the IDE (selection sharing). */
    private fun withIdeContext(text: String): String {
        if (!GapGptSettings.get().shareSelection) return text
        val snap = tracker.snapshot() ?: return text
        val ctx = if (snap.text != null && snap.text.isNotBlank()) {
            "The user is viewing `${snap.relPath}` in the IDE and has selected lines ${snap.startLine}-${snap.endLine}:\n" +
                "```${snap.language}\n${snap.text}\n```"
        } else {
            "The user has `${snap.relPath}` open in the IDE (caret on line ${snap.startLine}). " +
                "It may or may not be related to the request."
        }
        return "<ide_context>\n$ctx\n</ide_context>\n\n$text"
    }

    // ---------------------------------------------------------------- public API (used by actions)

    /** Inserts text into the active chat's input box (does not send). */
    fun insertIntoInput(text: String, inline: Boolean = false) = post("insertInput", JsonObject().apply {
        addProperty("text", text)
        addProperty("inline", inline)
    })

    /** Sends a prompt in the active chat, as if the user typed it. */
    fun sendPrompt(text: String) = post("externalSend", JsonObject().apply { addProperty("text", text) })

    // ---------------------------------------------------------------- JS -> IDE

    private fun validChat(id: String) = CHAT_ID.matches(id)

    private fun handleFromJs(msg: JsonObject) {
        val chat = msg.get("chat")?.takeIf { it.isJsonPrimitive }?.asString ?: "c1"
        if (!validChat(chat)) return
        fun str(k: String) = msg.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun bool(k: String, def: Boolean) = msg.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: def

        when (msg.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
            "send" -> {
                val text = str("text")
                if (text.isBlank() || text.length > MAX_INPUT_CHARS) return
                sendMessage(chat, text, bool("shareContext", true), bool("expandRefs", true), bool("plan", false))
            }
            "stop" -> stopTurn(chat)
            "toolDecision" -> turns[chat]?.approvals?.get(str("id"))?.complete(str("decision"))
            "showDiff" -> turns[chat]?.pendingEdits?.get(str("id"))?.let { p -> pooled { showDiff(p) } }
            "listSessions" -> pooled { sendSessions(chat) }
            "resumeSession" -> pooled { resumeSession(chat, str("id")) }
            "deleteSession" -> pooled {
                SessionStore.delete(project, str("id"))
                sendSessions(chat)
            }
            "clearChat" -> {
                stopTurn(chat)
                sessions.remove(chat)
                conversations[chat]?.reset()
                post("contextSize", chatJson(chat).apply { addProperty("tokens", 0); addProperty("limit", contextLimit()) })
            }
            "closeChat" -> {
                stopTurn(chat)
                sessions.remove(chat)
                conversations.remove(chat)
            }
            "fileSearch" -> {
                val reqId = msg.get("reqId")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val q = str("q")
                pooled {
                    val items = runCatching { ProjectFileSearch.getInstance(project).search(q) }.getOrDefault(emptyList())
                    post("fileResults", JsonObject().apply {
                        addProperty("reqId", reqId)
                        add("items", gson.toJsonTree(items))
                    })
                }
            }
            "recentFiles" -> ApplicationManager.getApplication().invokeLater { sendRecentFiles() }
            "setModel" -> str("value").trim().takeIf { MODEL_ID.matches(it) }?.let { GapGptSettings.get().model = it }
            "fetchModels" -> pooled {
                val reply = JsonObject()
                try {
                    val models = GapGptClient.listModels()
                    GapGptSettings.get().knownModels = models.toMutableList()
                    reply.add("items", gson.toJsonTree(models))
                } catch (e: GapGptClient.ApiException) {
                    reply.addProperty("error", e.message.orEmpty())
                }
                post("models", reply)
            }
            "setAutoFollow" -> GapGptSettings.get().autoFollow = bool("value", true)
            "setGraphify" -> {
                val on = bool("value", false)
                GapGptSettings.get().useGraphify = on
                PluginLog.info("ui", "graphify: $on")
                if (on) pooled {
                    if (!GraphifySetup.hasGraph(project)) {
                        GraphifySetup.run(project)
                        if (msg.get("chat")?.isJsonPrimitive == true) {
                            chatNotice(chat, I18n.tr(
                                "Building the code graph in the background. graphify will be used once it is ready.",
                                "گراف کد در پس‌زمینه ساخته می‌شود. بعد از آماده شدن، graphify استفاده می‌شود.",
                            ))
                        }
                    }
                }
            }
            "setLanguage" -> {
                GapGptSettings.get().language = if (str("value") == "fa") "fa" else "en"
                PluginLog.info("ui", "language: " + GapGptSettings.get().language)
            }
            "attach" -> attach(str("kind"))
            "listCommands" -> pooled {
                post("commands", JsonObject().apply { add("items", gson.toJsonTree(SlashCommands.list(project.basePath))) })
            }
            "copy" -> CopyPasteManager.getInstance().setContents(StringSelection(str("text")))
            "openUrl" -> str("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { BrowserUtil.browse(it) }
            "insertCode" -> insertAtCaret(str("text"))
            "openFile" -> openFile(str("path"), msg.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
            "settings" -> ApplicationManager.getApplication().invokeLater {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "GapGPT")
                sendInit()
                pooled { sendKeyStatus() }
            }

            // ----- Skills / Rules tabs
            "skills" -> pooled { sendSkills() }
            "rules" -> pooled { sendRules() }
            "scanInstructions" -> pooled { scanInstructions() }
            "importInstructions" -> pooled { importInstructions() }

            // ----- Git tab
            "git" -> pooled { post("git", GitInfo.overview(project.basePath)) }
            "ideAction" -> ApplicationManager.getApplication().invokeLater { runIdeAction(str("id")) }

            // ----- Log tab
            "log" -> post("log", JsonObject().apply {
                add("lines", JsonArray().apply { PluginLog.snapshot().forEach { add(lineJson(it)) } })
                addProperty("debug", PluginLog.debugEnabled)
            })
            "logDebug" -> {
                PluginLog.debugEnabled = bool("value", false)
                PluginLog.info("log", "debug logging " + if (PluginLog.debugEnabled) "on" else "off")
            }
        }
    }

    // ---------------------------------------------------------------- chat turns

    private fun sendMessage(chat: String, rawText: String, shareContext: Boolean, expandRefs: Boolean, plan: Boolean) {
        val conv = conversation(chat)
        val turn = Turn(chat).also { it.plan = plan }
        if (turns.putIfAbsent(chat, turn) != null) {
            post("error", chatJson(chat).apply { addProperty("text", I18n.tr("The previous reply is still running.", "پاسخ قبلی هنوز در حال اجراست.")) })
            return
        }
        sendInit()
        pooled(onFail = { finishTurn(turn) { post("error", chatJson(chat).apply { addProperty("text", I18n.tr("Could not send the message.", "ارسال پیام ناموفق بود.")) }) } }) {
            val s = GapGptSettings.get()
            val base = project.basePath
            val session = sessions.getOrPut(chat) { ChatSession() }
            if (session.title.isEmpty()) session.title = rawText.replace(Regex("\\s+"), " ").trim().take(80)
            record(chat, JsonObject().apply {
                addProperty("t", "user")
                addProperty("text", rawText)
            })
            val trimmed = rawText.trim()
            if (trimmed == "/compact" || trimmed.startsWith("/compact ") || trimmed.startsWith("/compact\n")) {
                compactCommand(turn, conv, trimmed.removePrefix("/compact").trim())
                return@pooled
            }
            if (trimmed == "/usage" || trimmed == "/usage reset") {
                usageCommand(turn, reset = trimmed.endsWith("reset"))
                return@pooled
            }
            if (trimmed == "/mcp" || trimmed == "/mcp reload") {
                mcpCommand(turn, reload = trimmed.endsWith("reload"))
                return@pooled
            }
            // "/graphify" builds the graph; "/graphify <question>" answers with it (the tool is offered for this turn only).
            var input = rawText
            if (trimmed == "/graphify" || trimmed.startsWith("/graphify ") || trimmed.startsWith("/graphify\n")) {
                val question = trimmed.removePrefix("/graphify").trim()
                if (question.isEmpty() || question == "update") {
                    graphifyBuildCommand(turn)
                    return@pooled
                }
                if (!s.useGraphify) {
                    finishTurn(turn) {
                        chatNotice(chat, I18n.tr("graphify is off. Turn on the graphify button in the chat toolbar to use the code graph.",
                            "graphify خاموش است. برای استفاده از گراف کد، دکمهٔ graphify را در نوار ابزار چت روشن کنید."))
                        post("done", chatJson(chat))
                    }
                    return@pooled
                }
                if (!s.agentTools || toolsUnsupported) {
                    finishTurn(turn) {
                        chatNotice(chat, I18n.tr("/graphify needs the agent tools (Settings › Tools › GapGPT › Chat Agent) and a model that supports tools.",
                            "‏/graphify به ابزارهای ایجنت (Settings › Tools › GapGPT › ایجنت چت) و مدلی که از ابزار پشتیبانی کند نیاز دارد."))
                        post("done", chatJson(chat))
                    }
                    return@pooled
                }
                if (!GraphifySetup.hasGraph(project)) {
                    finishTurn(turn) {
                        chatNotice(chat, I18n.tr("This project has no code graph yet. Send /graphify to build it, then ask again.",
                            "این پروژه هنوز گراف کد ندارد. اول /graphify را بفرستید تا ساخته شود، بعد دوباره بپرسید."))
                        post("done", chatJson(chat))
                    }
                    return@pooled
                }
                turn.graphify = true
                turn.graphifyExplicit = true
                input = question
            } else if (s.useGraphify && s.agentTools && !toolsUnsupported) {
                if (GraphifySetup.hasGraph(project)) {
                    turn.graphify = true
                } else {
                    if (!GraphifySetup.isBuilding) GraphifySetup.run(project)
                    if (graphNoticed.add(chat)) chatNotice(chat, I18n.tr(
                        "The code graph is not ready yet, so this message is sent without graphify.",
                        "گراف کد هنوز آماده نیست، پس این پیام بدون graphify ارسال می‌شود.",
                    ))
                }
            }
            // "/skill args" becomes the skill's instructions; other text keeps the IDE context.
            var text = input
            var isSkill = false
            if (input.startsWith("/")) {
                val name = input.substring(1).substringBefore(' ').substringBefore('\n')
                val args = input.substring(1 + name.length).trim()
                ProjectInstructions.expandSkill(base, name, args)?.let {
                    text = it
                    isSkill = true
                }
            }
            if (!isSkill && !input.startsWith("/")) {
                if (expandRefs) text = expandFileRefs(text)
                if (shareContext) text = withIdeContext(text)
            }
            if (s.normalizeArabicChars) text = Prompts.normalizePersian(text)

            if (turn.finished.get()) return@pooled
            conv.addUser(text)
            post("busy", chatJson(chat).apply { addProperty("busy", true) })
            step(turn, conv)
        }
    }

    /**
     * One model request. If the reply asks for tools they are run (with approval when needed), their results are
     * added to the conversation and the next request is sent; a reply without tool calls ends the turn.
     */
    private fun step(turn: Turn, conv: Conversation) {
        if (turn.finished.get()) return
        val s = GapGptSettings.get()
        val useTools = s.agentTools && !toolsUnsupported
        val chat = turn.chat
        turn.partial.setLength(0)
        turn.steps++
        val tools = if (useTools) {
            if (s.mcpEnabled) startMcp(chat)
            if (turn.finished.get()) return
            agentTools.definitions(turn.plan, turn.graphify).also { defs -> if (s.mcpEnabled) mcp.definitions(readOnlyOnly = turn.plan).forEach { defs.add(it) } }
        } else null
        val system = buildSystemPrompt(useTools, turn.plan, turn.graphify, turn.graphifyExplicit)
        if (s.autoCompact) maybeCompact(turn, conv, system)
        if (turn.finished.get()) return
        val messages = conv.snapshot(system)
        postContextSize(chat, conv, messages)
        val model = s.model.trim()
        turn.handle = GapGptClient.send(messages, tools, object : GapGptClient.Listener {
            override fun onDelta(text: String) {
                if (turn.finished.get()) return
                turn.partial.append(text)
                post("delta", chatJson(chat).apply { addProperty("text", text) })
            }

            override fun onDone(reply: GapGptClient.Reply) {
                if (turn.finished.get()) return
                addUsage(turn, reply.usage, model)
                reply.usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.let { runCatching { conv.calibrate(messages, it.asLong) } }
                if (!useTools || reply.toolCalls.isEmpty()) {
                    finishTurn(turn) {
                        conv.addAssistant(reply.text)
                        post("done", chatJson(chat).apply { add("usage", turnUsage(turn)) })
                    }
                    return
                }
                synchronized(turn) {
                    if (turn.finished.get()) return
                    conv.addAssistant(reply.text, reply.toolCalls)
                    turn.partial.setLength(0)
                }
                PluginLog.info("agent", "step ${turn.steps}: " + reply.toolCalls.joinToString { it.name })
                for (call in reply.toolCalls) {
                    if (turn.finished.get()) return
                    val result = executeTool(turn, call)
                    synchronized(turn) {
                        if (turn.finished.get()) return
                        conv.addToolResult(call.id, result)
                    }
                }
                if (turn.steps >= s.maxAgentSteps.coerceAtLeast(1)) {
                    finishTurn(turn) {
                        post("notice", chatJson(chat).apply {
                            addProperty("kind", "warn")
                            addProperty("text", I18n.tr(
                                "Stopped after ${turn.steps} steps (limit in Settings). Send \"continue\" to go on.",
                                "پس از ${turn.steps} گام متوقف شد (محدودیت در تنظیمات). برای ادامه بنویسید «ادامه بده».",
                            ))
                        })
                        post("done", chatJson(chat).apply { add("usage", turnUsage(turn)) })
                    }
                    return
                }
                step(turn, conv)
            }

            override fun onError(message: String, status: Int) {
                if (turn.finished.get()) return
                val toolsRejected = useTools && turn.steps == 1 && status in setOf(400, 422) &&
                    Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(message)
                if (toolsRejected) {
                    toolsUnsupported = true
                    PluginLog.warn("agent", "Server rejected tools; continuing without them")
                    post("notice", chatJson(chat).apply {
                        addProperty("kind", "warn")
                        addProperty("text", I18n.tr(
                            "This model or server does not accept tools, so the assistant cannot read project files itself. Continuing as a plain chat.",
                            "این مدل یا سرور از ابزار (tools) پشتیبانی نمی‌کند، پس دستیار نمی‌تواند خودش فایل‌های پروژه را بخواند. ادامه به‌صورت چت ساده.",
                        ))
                    })
                    turn.steps = 0
                    step(turn, conv)
                    return
                }
                finishTurn(turn) {
                    synchronized(turn) {
                        conv.closeDanglingToolCalls("Error: the run ended before this tool call finished.")
                        if (turn.partial.isNotEmpty()) conv.addAssistant(turn.partial.toString()) else conv.dropLastUser()
                    }
                    post("error", chatJson(chat).apply { addProperty("text", message) })
                }
            }
        })
        // The user may have pressed Stop while the request was being set up.
        if (turn.finished.get()) turn.handle?.cancel()
    }

    // ---------------------------------------------------------------- context compaction

    private fun contextLimit(): Long = GapGptSettings.get().contextLimitTokens.toLong().coerceAtLeast(MIN_CONTEXT_LIMIT)

    private fun postContextSize(chat: String, conv: Conversation, messages: List<ChatMessage>) =
        post("contextSize", chatJson(chat).apply {
            addProperty("tokens", conv.estimateTokens(messages))
            addProperty("limit", contextLimit())
        })

    private fun chatNotice(chat: String, text: String, kind: String = "") = post("notice", chatJson(chat).apply {
        addProperty("kind", kind)
        addProperty("text", text)
    })

    /**
     * Keeps the request under the context limit. First (no request needed) the output of old tool calls is dropped;
     * if that is not enough, the messages before the current question are summarized by the model.
     */
    private fun maybeCompact(turn: Turn, conv: Conversation, system: String) {
        val limit = contextLimit()
        fun est() = conv.estimateTokens(conv.snapshot(system))
        val start = est()
        if (start < limit * 6 / 10) return
        val pruned = conv.pruneToolResults(keepLast = KEEP_TOOL_RESULTS)
        if (est() >= limit * 8 / 10 && !summarize(turn, conv, "", auto = true)) {
            // Nothing older to summarize (one long agent run): keep only the latest tool outputs.
            conv.pruneToolResults(keepLast = 2)
        }
        val end = est()
        if (pruned > 0 && end < start && !turn.finished.get()) {
            PluginLog.info("agent", "context pruned: ~$start -> ~$end tokens")
            chatNotice(turn.chat, I18n.tr(
                "⎿  Context compacted: ~${start / 1000}k → ~${end / 1000}k tokens (older tool outputs removed).",
                "⎿  context فشرده شد: ~${start / 1000}k ← ~${end / 1000}k توکن (خروجی ابزارهای قدیمی حذف شد).",
            ))
        }
    }

    /** Replaces the messages before the latest question with a model-written summary. False if nothing was done. */
    private fun summarize(turn: Turn, conv: Conversation, focus: String, auto: Boolean): Boolean {
        val older = conv.compactable()
        if (older.isEmpty() || turn.finished.get()) return false
        val before = conv.estimateTokens(conv.snapshot(""))
        post("status", chatJson(turn.chat).apply { addProperty("text", I18n.tr("Compacting conversation…", "در حال خلاصه‌سازی گفتگو…")) })
        PluginLog.info("agent", "compacting ${older.size} messages (auto=$auto)")
        val request = listOf(
            ChatMessage("system", Prompts.COMPACT_SYSTEM),
            ChatMessage("user", Prompts.compactRequest(conv.summary, older, focus)),
        )
        val summary = complete(turn, request)?.takeIf { it.isNotBlank() } ?: return false
        synchronized(turn) {
            if (turn.finished.get()) return false
            conv.applySummary(summary, older.size)
        }
        val after = conv.estimateTokens(conv.snapshot(""))
        chatNotice(turn.chat, I18n.tr(
            "⎿  Conversation compacted: ${older.size} older messages → summary (~${before / 1000}k → ~${after / 1000}k tokens).",
            "⎿  گفتگو خلاصه شد: ${older.size} پیام قدیمی ← خلاصه (~${before / 1000}k ← ~${after / 1000}k توکن).",
        ))
        return true
    }

    /** A blocking request without tools; null on error or Stop. Runs on the turn's background thread. */
    private fun complete(turn: Turn, messages: List<ChatMessage>): String? {
        val result = CompletableFuture<String?>()
        val model = GapGptSettings.get().model.trim()
        turn.handle = GapGptClient.send(messages, null, object : GapGptClient.Listener {
            override fun onDelta(text: String) = Unit

            override fun onDone(reply: GapGptClient.Reply) {
                addUsage(turn, reply.usage, model)
                result.complete(reply.text)
            }

            override fun onError(message: String, status: Int) {
                PluginLog.warn("agent", "compaction failed: HTTP $status")
                if (!turn.finished.get()) chatNotice(turn.chat, I18n.tr("Could not compact the conversation: ", "خلاصه‌سازی گفتگو ناموفق بود: ") + message, "warn")
                result.complete(null)
            }
        })
        while (!turn.finished.get()) {
            try {
                return result.get(500, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            }
        }
        turn.handle?.cancel()
        return null
    }

    /** `/compact [focus]`: summarize the conversation now. */
    private fun compactCommand(turn: Turn, conv: Conversation, focus: String) {
        val chat = turn.chat
        post("busy", chatJson(chat).apply { addProperty("busy", true) })
        val done = summarize(turn, conv, focus, auto = false)
        val pruned = if (done) 0 else conv.pruneToolResults(keepLast = 0)
        finishTurn(turn) {
            if (!done) chatNotice(chat, if (pruned > 0) I18n.tr("⎿  Removed old tool outputs.", "⎿  خروجی ابزارهای قدیمی حذف شد.")
                else I18n.tr("⎿  Nothing to compact yet.", "⎿  هنوز چیزی برای خلاصه کردن نیست."))
            postContextSize(chat, conv, conv.snapshot(buildSystemPrompt(GapGptSettings.get().agentTools && !toolsUnsupported)))
            post("done", chatJson(chat).apply { add("usage", turnUsage(turn)) })
        }
    }

    /** Shows the call in the chat, asks for approval if needed, runs it and returns the text for the model. */
    private fun executeTool(turn: Turn, call: ToolCall): String {
        val chat = turn.chat
        when (call.name) {
            "todo_write" -> return todoWrite(chat, call)
            "exit_plan_mode" -> return exitPlanMode(turn, call)
        }
        val p = if (call.name.startsWith("mcp__")) mcp.prepare(call) else agentTools.prepare(call)
        post("toolStart", chatJson(chat).apply {
            addProperty("id", call.id)
            addProperty("title", p.title)
            addProperty("label", p.label)
        })
        val writes = p.kind == AgentTools.Kind.EDIT || p.kind == AgentTools.Kind.COMMAND ||
            (p.kind == AgentTools.Kind.MCP && !mcp.isReadOnly(call.name))
        if (turn.plan && writes) {
            toolEnd(chat, call.id, false, I18n.tr("Not allowed in plan mode", "در plan mode مجاز نیست"), null)
            return "Error: plan mode is on, so files cannot be changed and commands cannot be run. Finish the plan and call exit_plan_mode."
        }
        val s = GapGptSettings.get()
        val key = p.approvalKey
        val needsApproval = key != null && key !in sessionApproved && when (key) {
            "edit" -> !s.autoApproveEdits
            "command" -> !s.autoApproveCommands
            else -> true
        }
        if (needsApproval) {
            if (p.proposed != null) turn.pendingEdits[call.id] = p
            if (p.proposed != null && s.showDiffOnApproval) pooled { showDiff(p) }
            val decision = askApproval(turn, call.id, p.kind.name.lowercase(), p.preview.orEmpty(), p.proposed != null)
            turn.pendingEdits.remove(call.id)
            if (turn.finished.get()) return "Cancelled: the user stopped the run."
            when (decision) {
                "always" -> sessionApproved += key!!
                "allow" -> Unit
                else -> {
                    toolEnd(chat, call.id, false, I18n.tr("Denied by you", "رد شد"), null)
                    return "The user denied this tool call. Do not retry it unchanged; ask the user or take another approach."
                }
            }
        }
        val r = try {
            p.run(turn.run).also { if (it.ok && (call.name == "edit_file" || call.name == "write_file")) turn.appliedEdit = true }
        } catch (e: Throwable) {
            PluginLog.warn("agent", "${call.name} failed: ${e.javaClass.simpleName}")
            AgentTools.Result(false, "Error: ${e.message ?: e.javaClass.simpleName}", e.message ?: e.javaClass.simpleName)
        }
        toolEnd(chat, call.id, r.ok, r.summary, r.detail)
        val text = r.forModel
        return if (text.length > MAX_TOOL_RESULT) text.take(MAX_TOOL_RESULT) + "\n… (output truncated)" else text
    }

    /** Shows the approval box and waits for Allow / Always / Deny ("deny" when the run is stopped). */
    private fun askApproval(turn: Turn, id: String, kind: String, preview: String, hasDiff: Boolean): String {
        val f = CompletableFuture<String>()
        turn.approvals[id] = f
        if (turn.finished.get()) f.complete("deny")
        post("toolApproval", chatJson(turn.chat).apply {
            addProperty("id", id)
            addProperty("kind", kind)
            addProperty("preview", preview)
            addProperty("hasDiff", hasDiff)
        })
        return try {
            f.get()
        } catch (e: Exception) {
            "deny"
        } finally {
            turn.approvals.remove(id)
        }
    }

    /** todo_write: validates the list, shows it in the chat and confirms to the model. */
    private fun todoWrite(chat: String, call: ToolCall): String {
        val todos = try {
            JsonParser.parseString(call.arguments.ifBlank { "{}" }).asJsonObject.getAsJsonArray("todos")
        } catch (e: Exception) {
            null
        } ?: return "Error: todos must be an array of {content, status}."
        val items = JsonArray()
        for (el in todos) {
            val o = el as? JsonObject ?: continue
            val content = o.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (content.isEmpty()) continue
            val status = o.get("status")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it in setOf("pending", "in_progress", "completed") } ?: "pending"
            items.add(JsonObject().apply {
                addProperty("content", content.take(300))
                addProperty("status", status)
            })
        }
        post("todos", chatJson(chat).apply { add("items", items) })
        val done = items.count { it.asJsonObject.get("status").asString == "completed" }
        return "Todo list updated ($done/${items.size()} completed). Keep working on the in_progress task and update the list as you go."
    }

    /** exit_plan_mode: asks the user to approve the plan; on approval the rest of the run may edit and run commands. */
    private fun exitPlanMode(turn: Turn, call: ToolCall): String {
        val chat = turn.chat
        val plan = try {
            JsonParser.parseString(call.arguments.ifBlank { "{}" }).asJsonObject.get("plan")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: return "Error: the plan argument is required."
        if (!turn.plan) return "Plan mode is already off; go ahead and implement."
        post("toolStart", chatJson(chat).apply {
            addProperty("id", call.id)
            addProperty("title", "Plan")
            addProperty("label", "")
        })
        val decision = askApproval(turn, call.id, "plan", plan, false)
        if (turn.finished.get()) return "Cancelled: the user stopped the run."
        return when (decision) {
            "allow", "always" -> {
                turn.plan = false
                if (decision == "always") sessionApproved += "edit"
                post("planMode", chatJson(chat).apply { addProperty("value", false) })
                toolEnd(chat, call.id, true, I18n.tr("Plan approved", "طرح تأیید شد"), null)
                "The user approved the plan. Plan mode is off: implement the plan now (you can edit files and run commands). " +
                    "Track the steps with todo_write."
            }
            else -> {
                toolEnd(chat, call.id, false, I18n.tr("Keep planning", "ادامهٔ برنامه‌ریزی"), null)
                "The user did not approve the plan and wants to keep planning. Stay in plan mode: ask what should change " +
                    "and stop until the user answers."
            }
        }
    }

    // ---------------------------------------------------------------- MCP

    /** Starts / reloads MCP servers (asks once before running servers defined in the project). */
    private fun startMcp(chat: String) {
        val failures = try {
            mcp.ensureStarted { configs -> askTrustMcp(configs) }
        } catch (e: Exception) {
            PluginLog.warn("mcp", "start failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        if (failures.isNotEmpty()) chatNotice(chat, I18n.tr("MCP server failed: ", "سرور MCP اجرا نشد: ") + failures.joinToString("\n"), "warn")
    }

    private fun askTrustMcp(configs: List<McpServerConfig>): Boolean {
        var ok = false
        ApplicationManager.getApplication().invokeAndWait({
            ok = Messages.showYesNoDialog(
                project,
                I18n.tr(
                    "This project defines MCP servers. They run on your computer with your permissions:\n\n",
                    "این پروژه سرورهای MCP تعریف کرده است. این سرورها با دسترسی‌های شما روی کامپیوترتان اجرا می‌شوند:\n\n",
                ) + configs.joinToString("\n") { "• " + it.describe() + "   (" + it.source + ")" } +
                    I18n.tr("\n\nOnly allow servers you trust. Run them?", "\n\nفقط سرورهایی را که به آن‌ها اعتماد دارید اجازه دهید. اجرا شوند؟"),
                I18n.tr("GapGPT: MCP Servers", "GapGPT: سرورهای MCP"),
                I18n.tr("Run", "اجرا"),
                I18n.tr("Don't Run", "اجرا نشود"),
                Messages.getQuestionIcon(),
            ) == Messages.YES
        }, ModalityState.defaultModalityState())
        return ok
    }

    /** `/graphify`: builds or updates the project's code graph in a background task (installs graphify if needed). */
    private fun graphifyBuildCommand(turn: Turn) {
        val chat = turn.chat
        GraphifySetup.run(project)
        finishTurn(turn) {
            chatNotice(chat, I18n.tr(
                "Building the code graph in the background (progress in the IDE status bar). When it is done, turn on the graphify button in the chat toolbar.",
                "ساخت گراف کد در پس‌زمینه شروع شد (پیشرفت در نوار وضعیت IDE). بعد از پایان، دکمهٔ graphify را در نوار ابزار چت روشن کنید.",
            ))
            post("done", chatJson(chat))
        }
    }

    /** `/mcp` (status) and `/mcp reload`. */
    private fun mcpCommand(turn: Turn, reload: Boolean) {
        val chat = turn.chat
        post("busy", chatJson(chat).apply { addProperty("busy", true) })
        if (!GapGptSettings.get().mcpEnabled) {
            finishTurn(turn) {
                chatNotice(chat, I18n.tr("MCP is turned off in Settings › Tools › GapGPT.", "MCP در Settings › Tools › GapGPT خاموش است."))
                post("done", chatJson(chat))
            }
            return
        }
        post("status", chatJson(chat).apply { addProperty("text", I18n.tr("Starting MCP servers…", "در حال اجرای سرورهای MCP…")) })
        if (reload) mcp.reload()
        startMcp(chat)
        val lines = mcp.statusLines()
        finishTurn(turn) {
            chatNotice(chat, if (lines.isEmpty()) I18n.tr(
                "No MCP servers configured. Add them to .gapgpt/mcp.json in the project (or ~/.gapgpt/mcp.json), e.g.\n" +
                    "{\"mcpServers\": {\"name\": {\"command\": \"npx\", \"args\": [\"-y\", \"package\"]}}}",
                "هیچ سرور MCP تنظیم نشده است. آن‌ها را در .gapgpt/mcp.json پروژه (یا ~/.gapgpt/mcp.json) اضافه کنید، مثلاً\n" +
                    "{\"mcpServers\": {\"name\": {\"command\": \"npx\", \"args\": [\"-y\", \"package\"]}}}",
            ) else "MCP:\n" + lines.joinToString("\n") { "• $it" })
            post("done", chatJson(chat))
        }
    }

    /** `/usage` (account credit, price of the current model, local usage per model) and `/usage reset`. */
    private fun usageCommand(turn: Turn, reset: Boolean) {
        val chat = turn.chat
        post("busy", chatJson(chat).apply { addProperty("busy", true) })
        if (reset) {
            GapGptUsage.get().reset()
            finishTurn(turn) {
                chatNotice(chat, I18n.tr("⎿  Local usage counters were reset.", "⎿  شمارنده‌های محلی مصرف صفر شد."))
                post("done", chatJson(chat))
            }
            return
        }
        post("status", chatJson(chat).apply { addProperty("text", I18n.tr("Reading account balance…", "در حال خواندن اعتبار حساب…")) })
        GapGptClient.invalidateBalance()
        val balance = GapGptClient.balance()
        val model = GapGptSettings.get().model.trim()
        val usage = GapGptUsage.get()
        val report = buildUsageReport(balance, model, usage.all(), usage.since)
        finishTurn(turn) {
            chatNotice(chat, report)
            post("done", chatJson(chat))
        }
    }

    private fun buildUsageReport(balance: GapGptClient.Balance?, model: String, all: Map<String, GapGptUsage.Totals>, since: String): String {
        fun usd(v: Double?) = if (v == null) "?" else if (v < 1.0) String.format(java.util.Locale.US, "$%.4f", v) else String.format(java.util.Locale.US, "$%.2f", v)
        fun num(v: Long) = String.format(java.util.Locale.US, "%,d", v)
        val sb = StringBuilder()
        sb.append(I18n.tr("GapGPT account", "حساب گپ‌جی‌پی‌تی")).append('\n')
        if (balance == null) {
            sb.append("• ").append(I18n.tr("Credit: not available", "اعتبار: در دسترس نیست")).append('\n')
        } else {
            sb.append("• ").append(I18n.tr("Credit limit: ", "سقف اعتبار: ")).append(if (balance.limit == null) I18n.tr("no cap", "بدون سقف") else usd(balance.limit)).append('\n')
            sb.append("• ").append(I18n.tr("Used: ", "مصرف‌شده: ")).append(usd(balance.used)).append('\n')
            sb.append("• ").append(I18n.tr("Remaining: ", "باقی‌مانده: ")).append(usd(balance.remaining)).append('\n')
        }
        sb.append('\n').append(I18n.tr("Current model: ", "مدل فعلی: ")).append(model).append('\n')
        val price = GapGptPricing.price(model)
        if (price == null) {
            sb.append("• ").append(I18n.tr("No price in the bundled list.", "قیمتی در فهرست قیمت‌های همراه افزونه نیست.")).append('\n')
        } else {
            sb.append("• ").append(I18n.tr("Price per 1M tokens: ", "قیمت هر ۱ میلیون توکن: "))
                .append("input ").append(usd(price.input))
                .append(" · cached ").append(usd(price.cached ?: price.input))
                .append(" · output ").append(usd(price.output))
                .append(" (").append(GapGptPricing.asOf).append(")\n")
        }
        sb.append('\n').append(I18n.tr("Usage on this computer since ", "مصرف روی این کامپیوتر از ")).append(since).append('\n')
        if (all.isEmpty()) {
            sb.append("• ").append(I18n.tr("Nothing recorded yet.", "هنوز چیزی ثبت نشده است.")).append('\n')
        } else {
            var requests = 0L; var input = 0L; var cached = 0L; var output = 0L; var cost = 0.0; var allPriced = true
            for ((id, t) in all.entries.sortedBy { it.key }) {
                val priced = GapGptPricing.price(id) != null
                requests += t.requests; input += t.input; cached += t.cached; output += t.output
                if (priced) cost += t.cost else allPriced = false
                sb.append("• ").append(id).append(" — ").append(num(t.requests)).append(" req · in ").append(num(t.input))
                    .append(" (cached ").append(num(t.cached)).append(") · out ").append(num(t.output))
                    .append(" · ").append(if (priced) usd(t.cost) else "?").append('\n')
            }
            sb.append("Σ ").append(num(requests)).append(" req · in ").append(num(input)).append(" (cached ").append(num(cached))
                .append(") · out ").append(num(output)).append(" · ").append(usd(cost)).append(if (allPriced) "" else "+?").append('\n')
        }
        sb.append('\n').append(I18n.tr(
            "Costs are a local estimate from the bundled price list, not the bill. /usage reset clears the local counters.",
            "هزینه‌ها تخمین محلی بر اساس فهرست قیمت همراه افزونه‌اند، نه صورت‌حساب. ‎/usage reset شمارنده‌های محلی را صفر می‌کند.",
        ))
        return sb.toString()
    }

    /** Opens IntelliJ's diff viewer: the file now vs. the file after the proposed edit. Call off the EDT. */
    private fun showDiff(p: AgentTools.Prepared) {
        val pair = try {
            p.proposed?.invoke()
        } catch (e: Exception) {
            null
        }
        if (pair == null) {
            notify(I18n.tr("The proposed change no longer matches the file, so there is no diff to show.",
                "تغییر پیشنهادی دیگر با فایل جور نیست، پس diff قابل نمایش نیست."), NotificationType.WARNING)
            return
        }
        val name = p.label.substringAfterLast('/')
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                I18n.tr("GapGPT: proposed change to ", "GapGPT: تغییر پیشنهادی در ") + p.label,
                factory.create(project, pair.first, type),
                factory.create(project, pair.second, type),
                I18n.tr("Current", "فعلی"),
                I18n.tr("Proposed", "پیشنهادی"),
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun toolEnd(chat: String, id: String, ok: Boolean, summary: String, detail: String?) =
        post("toolEnd", chatJson(chat).apply {
            addProperty("id", id)
            addProperty("ok", ok)
            addProperty("summary", summary)
            detail?.let { addProperty("detail", it) }
        })

    /** Adds one reply's token usage and its estimated cost (for [model], the model that served the request) to the turn. */
    private fun addUsage(turn: Turn, u: JsonObject?, model: String) {
        if (u == null) return
        fun num(o: JsonObject?, k: String) = o?.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L
        fun n(k: String) = num(u, k)
        val p = n("prompt_tokens")
        val c = n("completion_tokens")
        val cached = num(u.get("prompt_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject, "cached_tokens")
        val reasoning = num(u.get("completion_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject, "reasoning_tokens")
        val cost = GapGptPricing.cost(model, p, cached, c)
        synchronized(turn.usage) {
            turn.usage[0] += p
            turn.usage[1] += c
            turn.usage[2] += n("total_tokens").takeIf { it > 0 } ?: (p + c)
            if (cost != null) turn.cost += cost else turn.unpriced = true
        }
        if (p > 0 || c > 0) GapGptUsage.get().add(model, p, cached, c, reasoning, cost ?: 0.0)
    }

    private fun turnUsage(turn: Turn) = JsonObject().apply {
        synchronized(turn.usage) {
            addProperty("prompt_tokens", turn.usage[0])
            addProperty("completion_tokens", turn.usage[1])
            addProperty("total_tokens", turn.usage[2])
            if (!turn.unpriced && turn.usage[2] > 0) addProperty("cost_usd", turn.cost)
        }
    }

    /** Runs [block] once for [turn] and then releases the chat for the next message. */
    private fun finishTurn(turn: Turn, block: () -> Unit) {
        if (!turn.finished.compareAndSet(false, true)) return
        try {
            block()
        } finally {
            GapGptClient.invalidateBalance()
            turns.remove(turn.chat, turn)
            // Keep the code graph fresh after edits while graphify is on.
            if (turn.appliedEdit && GapGptSettings.get().useGraphify && !GraphifySetup.isBuilding) GraphifySetup.run(project, quiet = true)
            post("busy", chatJson(turn.chat).apply { addProperty("busy", false) })
            pooled { saveSession(turn.chat) }
        }
    }

    // ---------------------------------------------------------------- saved sessions (/resume)

    /** Mirrors what the page shows into the chat's transcript, from the events sent to it. */
    private fun record(chat: String, entry: JsonObject) {
        val session = sessions[chat] ?: return
        synchronized(session) {
            if (session.transcript.size < MAX_TRANSCRIPT) session.transcript += entry
        }
    }

    private fun recordEvent(kind: String, payload: JsonObject) {
        val chat = payload.get("chat")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val session = sessions[chat] ?: return
        fun str(k: String) = payload.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        synchronized(session) {
            val t = session.transcript
            when (kind) {
                "delta" -> {
                    val last = t.lastOrNull()
                    if (last != null && last.get("t")?.asString == "assistant" && last.get("open")?.asBoolean == true) {
                        last.addProperty("text", last.get("text").asString + str("text"))
                    } else if (t.size < MAX_TRANSCRIPT) t += JsonObject().apply {
                        addProperty("t", "assistant")
                        addProperty("text", str("text"))
                        addProperty("open", true)
                    }
                }
                "toolStart" -> {
                    closeAssistant(t)
                    if (t.size < MAX_TRANSCRIPT) t += JsonObject().apply {
                        addProperty("t", "tool")
                        addProperty("id", str("id"))
                        addProperty("title", str("title"))
                        addProperty("label", str("label"))
                    }
                }
                "toolEnd" -> t.lastOrNull { it.get("t")?.asString == "tool" && it.get("id")?.asString == str("id") }?.apply {
                    addProperty("ended", true)
                    add("ok", payload.get("ok"))
                    addProperty("summary", str("summary"))
                    payload.get("detail")?.let { add("detail", it) }
                }
                "todos" -> {
                    closeAssistant(t)
                    t += JsonObject().apply {
                        addProperty("t", "todos")
                        add("items", payload.get("items"))
                    }
                }
                "notice", "error" -> {
                    closeAssistant(t)
                    if (t.size < MAX_TRANSCRIPT) t += JsonObject().apply {
                        addProperty("t", "notice")
                        addProperty("text", str("text"))
                        addProperty("kind", if (kind == "error") "error" else str("kind"))
                    }
                }
                "done" -> closeAssistant(t)
            }
        }
    }

    private fun closeAssistant(t: MutableList<JsonObject>) {
        t.lastOrNull()?.takeIf { it.get("t")?.asString == "assistant" }?.remove("open")
    }

    private fun saveSession(chat: String) {
        val session = sessions[chat] ?: return
        val conv = conversations[chat] ?: return
        if (conv.isEmpty()) return
        val transcript = synchronized(session) { JsonArray().apply { session.transcript.forEach { add(it.deepCopy()) } } }
        SessionStore.save(project, SessionStore.Session(
            id = session.id,
            title = session.title,
            created = session.created,
            updated = System.currentTimeMillis(),
            summary = conv.summary,
            messages = conv.export(),
            transcript = transcript,
        ))
    }

    private fun sendSessions(chat: String) = post("sessions", chatJson(chat).apply {
        add("items", JsonArray().apply {
            SessionStore.list(project).forEach { m ->
                add(JsonObject().apply {
                    addProperty("id", m.id)
                    addProperty("title", m.title)
                    addProperty("updated", m.updated)
                    addProperty("messages", m.messages)
                    addProperty("current", sessions[chat]?.id == m.id)
                })
            }
        })
    })

    private fun resumeSession(chat: String, id: String) {
        val saved = SessionStore.load(project, id)
        if (saved == null) {
            post("notice", chatJson(chat).apply {
                addProperty("kind", "error")
                addProperty("text", I18n.tr("Could not open this conversation.", "باز کردن این گفتگو ممکن نشد."))
            })
            return
        }
        stopTurn(chat)
        val conv = conversation(chat)
        conv.restore(saved.summary, saved.messages)
        val transcript = saved.transcript.mapNotNull { it as? JsonObject }.toMutableList()
        sessions[chat] = ChatSession(saved.id, saved.title, saved.created, transcript)
        PluginLog.info("sessions", "resumed ${saved.messages.size} messages")
        post("transcript", chatJson(chat).apply {
            addProperty("title", saved.title)
            addProperty("updated", saved.updated)
            add("items", saved.transcript)
        })
        postContextSize(chat, conv, conv.snapshot(buildSystemPrompt(GapGptSettings.get().agentTools && !toolsUnsupported)))
    }

    private fun stopTurn(chat: String) {
        val turn = turns[chat] ?: return
        turn.handle?.cancel()
        turn.run.cancel()
        turn.approvals.values.forEach { it.complete("deny") }
        finishTurn(turn) {
            val conv = conversation(chat)
            synchronized(turn) {
                conv.closeDanglingToolCalls("Cancelled: the user stopped the run.")
                if (turn.partial.isNotEmpty()) conv.addAssistant(turn.partial.toString()) else conv.dropLastUser()
            }
            post("done", chatJson(chat).apply { addProperty("stopped", true) })
        }
    }

    /** Only the token counts the page needs. */
    private fun usageJson(u: JsonObject) = JsonObject().apply {
        for (k in listOf("prompt_tokens", "completion_tokens", "total_tokens")) {
            u.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { add(k, it) }
        }
    }

    /** Agent prompt + language prompt + project rules + the user's own extra prompt. Built fresh for every request. */
    private fun buildSystemPrompt(useTools: Boolean, plan: Boolean = false, graphify: Boolean = false, graphifyExplicit: Boolean = false): String {
        val s = GapGptSettings.get()
        return listOf(
            if (useTools) Prompts.agentPrompt(
                project.basePath ?: "",
                topLevelListing(),
                "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}",
                canEdit = !plan,
                canRun = !plan,
            ) else "",
            if (useTools && plan) Prompts.PLAN_MODE else "",
            if (useTools && graphify) (if (graphifyExplicit) Prompts.GRAPHIFY_MODE else Prompts.GRAPHIFY_ON) else "",
            if (s.matchReplyLanguage) Prompts.languagePrompt(I18n.lang) else "",
            ProjectInstructions.systemPrompt(project.basePath),
            s.systemPrompt.trim(),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Names in the project root (directories end with /), so the model knows the layout without a tool call. */
    private fun topLevelListing(): String {
        val dir = project.basePath?.let { File(it) } ?: return "(unknown)"
        val entries = (dir.listFiles() ?: return "(unreadable)")
            .filter { it.name != ".git" && it.name != ".DS_Store" }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return entries.take(150).joinToString("\n") { if (it.isDirectory) it.name + "/" else it.name } +
            if (entries.size > 150) "\n… ${entries.size - 150} more" else ""
    }

    /** Appends the content of `@path/File.ext[#L1-9]` references that point at small text files inside the project. */
    private fun expandFileRefs(text: String): String {
        val root = project.basePath?.let { File(it).canonicalFile } ?: return text
        val seen = HashSet<String>()
        val blocks = StringBuilder()
        for (m in FILE_REF.findAll(text)) {
            if (seen.size >= MAX_REF_FILES) break
            val rel = m.groupValues[1].replace('\\', '/')
            val from = m.groupValues[2].toIntOrNull()
            val to = m.groupValues[3].toIntOrNull()
            if (!seen.add(m.value)) continue
            val f = try { File(root, rel).canonicalFile } catch (e: Exception) { continue }
            if (!f.isFile || !f.path.startsWith(root.path + File.separator) || f.length() > MAX_REF_BYTES) continue
            val content = try { f.readText() } catch (e: Exception) { continue }
            if (content.indexOf('\u0000') >= 0) continue
            val shown = if (from != null) {
                val lines = content.lines()
                lines.subList((from - 1).coerceIn(0, lines.size), (to ?: from).coerceIn(0, lines.size)).joinToString("\n")
            } else content
            blocks.append("<file path=\"").append(rel).append("\">\n```").append(f.extension).append('\n')
                .append(shown.trimEnd()).append("\n```\n</file>\n")
        }
        return if (blocks.isEmpty()) text else "$text\n\n<referenced_files>\n$blocks</referenced_files>"
    }

    // ---------------------------------------------------------------- skills, rules, import

    private fun sendSkills() = post("skills", JsonObject().apply {
        add("items", gson.toJsonTree(ProjectInstructions.skills(project.basePath)))
    })

    private fun sendRules() = post("rules", JsonObject().apply {
        add("items", gson.toJsonTree(ProjectInstructions.rules(project.basePath)))
    })

    private fun displayPath(f: File): String {
        val base = project.basePath ?: return f.path
        return try {
            f.relativeTo(File(base)).path.replace(File.separatorChar, '/').takeIf { !it.startsWith("..") } ?: f.path
        } catch (e: Exception) {
            f.path
        }
    }

    private fun scanInstructions() {
        val base = project.basePath
        val items = if (base == null) emptyList() else InstructionsImporter.scan(base)
        post("instructionsPreview", JsonObject().apply {
            add("items", JsonArray().also { arr ->
                items.forEach {
                    arr.add(JsonObject().apply {
                        addProperty("kind", it.kind)
                        addProperty("name", it.name)
                        addProperty("source", displayPath(it.source))
                        addProperty("target", displayPath(it.target))
                    })
                }
            })
        })
    }

    /** Re-scans on the Kotlin side: the page never supplies paths. */
    private fun importInstructions() {
        val base = project.basePath ?: return
        val res = InstructionsImporter.copy(base, InstructionsImporter.scan(base))
        PluginLog.info("import", "copied ${res.copied.size}, skipped ${res.skipped.size}")
        post("instructionsImported", JsonObject().apply {
            addProperty("moved", res.copied.size)
            add("skipped", JsonArray().also { arr ->
                res.skipped.forEach { (item, reason) ->
                    arr.add(JsonObject().apply {
                        addProperty("name", item.name)
                        addProperty("reason", reason)
                    })
                }
            })
        })
        sendSkills()
        sendRules()
        post("commands", JsonObject().apply { add("items", gson.toJsonTree(SlashCommands.list(base))) })
    }

    private fun sendKeyStatus() {
        post("keyStatus", JsonObject().apply { addProperty("hasKey", ApiKeyStore.get() != null) })
    }

    private fun pooled(onFail: (() -> Unit)? = null, block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            block()
        } catch (e: Exception) {
            PluginLog.warn("bridge", "Background task failed: ${e.javaClass.simpleName}")
            onFail?.invoke()
        }
    }

    private fun lineJson(l: PluginLog.Line) = JsonObject().apply {
        addProperty("time", l.time)
        addProperty("level", l.level)
        addProperty("source", l.source)
        addProperty("message", l.message)
    }

    private fun relRef(f: VirtualFile): String {
        val base = project.guessProjectDir()
        return "@" + (base?.let { VfsUtilCore.getRelativePath(f, it) } ?: f.path)
    }

    /** 📎 menu: Files… / Directory… / Image… */
    private fun attach(kind: String) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor: FileChooserDescriptor = when (kind) {
                "dir" -> FileChooserDescriptorFactory.createSingleFolderDescriptor()
                "image" -> FileChooserDescriptor(true, false, false, false, false, true)
                    .withFileFilter { it.extension?.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") }
                else -> FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            }.withTitle(I18n.tr("GapGPT: Add to Message", "GapGPT: افزودن به پیام"))
            FileChooser.chooseFiles(descriptor, project, project.guessProjectDir()) { files ->
                val refs = files.joinToString(" ") { f -> relRef(f) + if (f.isDirectory) "/" else "" }
                if (refs.isNotBlank()) insertIntoInput("$refs ", inline = true)
            }
        }
    }

    private fun sendRecentFiles() {
        val files = runCatching { EditorHistoryManager.getInstance(project).fileList.reversed() }.getOrDefault(emptyList())
        post("recentFiles", JsonObject().apply {
            add("items", JsonArray().apply {
                files.filter { it.isInLocalFileSystem }.take(30).forEach { add(relRef(it).removePrefix("@")) }
            })
        })
    }

    private fun runIdeAction(id: String) {
        val am = ActionManager.getInstance()
        val action = am.getAction(id)
        if (action == null) {
            notify(
                I18n.tr(
                    "This action is not available in your IDE ($id). The Git plugin may be disabled.",
                    "این عمل در IDE شما در دسترس نیست ($id). شاید پلاگین Git فعال نباشد.",
                ),
                NotificationType.WARNING,
            )
            return
        }
        am.tryToExecute(action, null, browser.component, "GapGPT", true)
    }

    private fun insertAtCaret(text: String) {
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                notify(I18n.tr("No editor is open.", "هیچ ویرایشگری باز نیست."), NotificationType.WARNING)
                return@invokeLater
            }
            WriteCommandAction.runWriteCommandAction(project, "Insert Code from GapGPT", null, {
                val caret = editor.caretModel.primaryCaret
                if (caret.hasSelection()) {
                    editor.document.replaceString(caret.selectionStart, caret.selectionEnd, text)
                } else {
                    editor.document.insertString(caret.offset, text)
                }
            })
        }
    }

    private fun openFile(path: String, line: Int) {
        if (path.isBlank()) return
        ApplicationManager.getApplication().invokeLater {
            val f = File(path).let { if (it.isAbsolute) it else File(project.basePath ?: "", path) }
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) ?: return@invokeLater
            OpenFileDescriptor(project, vf, maxOf(0, line - 1), 0).navigate(true)
        }
    }

    // ---------------------------------------------------------------- IDE -> JS

    private fun sendInit() {
        val s = GapGptSettings.get()
        post("init", JsonObject().apply {
            addProperty("dark", !JBColor.isBright())
            addProperty("bg", hex(UIUtil.getPanelBackground()))
            addProperty("fg", hex(UIUtil.getLabelForeground()))
            addProperty("fontSize", s.fontSize)
            addProperty("sendOnEnter", s.sendOnEnter)
            addProperty("projectPath", project.basePath ?: "")
            addProperty("model", s.model)
            add("models", gson.toJsonTree(s.knownModels.toList()))
            addProperty("shareSelection", s.shareSelection)
            addProperty("autoFollow", s.autoFollow)
            addProperty("useGraphify", s.useGraphify)
            addProperty("language", I18n.lang)
        })
    }

    private fun post(kind: String, payload: JsonObject) {
        if (kind in RECORDED) recordEvent(kind, payload)
        val js = "window.bridge && window.bridge.receive(${gson.toJson(kind)}, ${gson.toJson(payload)});"
        synchronized(lock) {
            if (!pageReady) {
                pending.add(js)
                return
            }
        }
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("GapGPT")
            .createNotification(text, type).notify(project)
    }

    private fun buildHtml(): String {
        fun res(name: String): ByteArray =
            ChatPanel::class.java.getResourceAsStream("/webview/$name")?.use { it.readBytes() } ?: ByteArray(0)

        val html = String(res("chat.html"), Charsets.UTF_8)
        val css = String(res("chat.css"), Charsets.UTF_8)
        val js = String(res("chat.js"), Charsets.UTF_8)
        val font = Base64.getEncoder().encodeToString(res("Vazirmatn.woff2"))
        return html
            .replace("/*__CSS__*/", css.replace("__FONT_BASE64__", font))
            .replace("/*__JS__*/", js)
    }

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    override fun dispose() {
        PluginLog.removeListener(logListener)
        turns.values.forEach { t ->
            t.finished.set(true)
            t.handle?.cancel()
            t.run.cancel()
            t.approvals.values.forEach { it.complete("deny") }
        }
        turns.clear()
        conversations.clear()
        ChatPanelHolder.getInstance(project).let { if (it.panel === this) it.panel = null }
    }

    companion object {
        private val CHAT_ID = Regex("c\\d{1,6}")
        private val MODEL_ID = Regex("[\\w.:/\\- ]{1,100}")
        private val FILE_REF = Regex("(?<![\\w@])@([\\w.\\-/\\\\]+\\.[A-Za-z0-9]{1,8})(?:#L(\\d+)(?:-(\\d+))?)?")
        private const val MAX_INPUT_CHARS = 200_000
        private const val MAX_REF_FILES = 5
        private const val MAX_REF_BYTES = 200_000L
        private const val MAX_TOOL_RESULT = 60_000
        private const val MAX_TRANSCRIPT = 5_000
        private val RECORDED = setOf("delta", "toolStart", "toolEnd", "todos", "notice", "error", "done")
        private const val MIN_CONTEXT_LIMIT = 8_000L
        /** Tool outputs kept in full when old ones are pruned. */
        private const val KEEP_TOOL_RESULTS = 6
    }
}
