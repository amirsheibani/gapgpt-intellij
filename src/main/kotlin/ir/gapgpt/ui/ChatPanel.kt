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
import com.intellij.openapi.command.WriteCommandAction
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import ir.gapgpt.PluginLog
import ir.gapgpt.api.ChatBackend
import ir.gapgpt.api.GapGptChatRunner
import ir.gapgpt.api.GapGptPricing
import ir.gapgpt.api.GapGptUsage
import ir.gapgpt.cli.ClaudeCliRunner
import ir.gapgpt.cli.CliInfo
import ir.gapgpt.cli.ClaudeConfig
import ir.gapgpt.ide.DependencyScanner
import ir.gapgpt.ide.GitInfo
import ir.gapgpt.ide.Guard
import ir.gapgpt.ide.IdeMcpServer
import ir.gapgpt.ide.PermissionBroker
import ir.gapgpt.ide.ProjectFileSearch
import ir.gapgpt.ide.SelectionTracker
import ir.gapgpt.settings.GapGptApi
import ir.gapgpt.settings.GapGptSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JComponent

/**
 * The GapGPT tool window: a JCEF page hosting several chats (tabs). Each chat has its own
 * Claude CLI process ([ClaudeCliRunner]); messages to the page carry the chat id.
 */
class ChatPanel(private val project: Project) : Disposable, PermissionBroker.Ui {

    private val gson = Gson()
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var pageReady = false
    private val pending = ConcurrentLinkedQueue<String>()
    private val lock = Any()

    private val runners = ConcurrentHashMap<String, ChatBackend>()
    private val broker = PermissionBroker.getInstance(project)
    private val tracker = SelectionTracker.getInstance(project)
    private val logListener: (PluginLog.Line) -> Unit = { post("logLine", lineJson(it)) }

    @Volatile
    private var depInventory: List<DependencyScanner.Dep>? = null

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, jsQuery)

        jsQuery.addHandler { raw ->
            try {
                handleFromJs(JsonParser.parseString(raw).asJsonObject)
            } catch (e: Exception) {
                PluginLog.warn("bridge", "JS message failed: ${e.message}")
                post("error", JsonObject().apply { addProperty("text", "خطای داخلی: ${e.message}") })
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
                pooled { sendAccount() }
                // /usage reports claude.ai subscription limits; only meaningful for the Claude Code engine.
                pooled { if (!isGapGpt()) CliInfo.usage(project.basePath)?.let { post("usage", it) } }
                pooled { sendGapGptModels(false) }
                pooled { sendGapGptStatus(null, false) }
            }
        }, browser.cefBrowser)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { sendInit() })

        broker.ui = this
        tracker.addListener(this) { snap -> post("context", contextJson(snap)) }
        PluginLog.addListener(logListener)

        browser.loadHTML(buildHtml())
    }

    private fun isGapGpt() = GapGptSettings.get().backend == GapGptSettings.BACKEND_GAPGPT

    /** The chat's engine; replaced (fresh conversation) when the user switched engines since. */
    private fun runner(chat: String): ChatBackend {
        val want = isGapGpt()
        runners[chat]?.let { r ->
            if ((r is GapGptChatRunner) == want || r.isRunning) return r
            r.resetSession()
            runners.remove(chat)
        }
        return runners.getOrPut(chat) { newRunner(chat, want) }
    }

    private fun newRunner(chat: String, gapgpt: Boolean): ChatBackend {
        val listener = object : ClaudeCliRunner.Listener {
            override fun onEvent(event: JsonObject) = post("claude", JsonObject().apply {
                addProperty("chat", chat)
                add("event", event)
            })

            override fun onStderr(line: String) = post("stderr", chatJson(chat).apply { addProperty("text", line) })

            override fun onExit(exitCode: Int, gotResult: Boolean) {
                broker.cancelAll(chat)
                // Claude may have changed files on disk: make the IDE pick them up.
                VirtualFileManager.getInstance().asyncRefresh(null)
                if (gapgpt) pooled { sendGapGptStatus(chat, true) }
                post("exit", chatJson(chat).apply {
                    addProperty("code", exitCode)
                    addProperty("gotResult", gotResult)
                })
            }

            override fun onStartFailed(message: String) = post("error", chatJson(chat).apply { addProperty("text", message) })
        }
        return if (gapgpt) GapGptChatRunner(project, chat, listener) else ClaudeCliRunner(project.basePath, listener)
    }

    private fun chatJson(chat: String) = JsonObject().apply { addProperty("chat", chat) }

    // ---------------------------------------------------------------- PermissionBroker.Ui

    override fun showPermission(chatId: String, request: JsonObject) =
        post("permission", request.deepCopy().apply { addProperty("chat", chatId) })

    override fun resolvePermission(chatId: String, id: String, outcome: String) = post("permissionResolved", chatJson(chatId).apply {
        addProperty("id", id)
        addProperty("outcome", outcome)
    })

    override fun guardChanged() = post("guard", guardJson())

    // ---------------------------------------------------------------- IDE context (selection sharing)

    private fun contextJson(snap: SelectionTracker.Snapshot?) = JsonObject().apply {
        if (snap == null) return@apply
        addProperty("file", snap.relPath)
        addProperty("startLine", snap.startLine)
        addProperty("endLine", snap.endLine)
        addProperty("hasSelection", snap.text != null)
    }

    /** Prepends what the user is looking at in the IDE, like the official plugin's selection sharing. */
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

    private fun handleFromJs(msg: JsonObject) {
        val chat = msg.get("chat")?.asString ?: "c1"
        fun str(k: String) = msg.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        when (msg.get("type")?.asString) {
            "send" -> {
                val text = str("text")
                if (text.isBlank()) return
                val r = runner(chat)
                if (r.isRunning) {
                    post("error", chatJson(chat).apply { addProperty("text", "پاسخ قبلی هنوز در حال اجراست.") })
                    return
                }
                sendInit()
                val prompt = if (msg.get("shareContext")?.asBoolean != false && !text.startsWith("/")) withIdeContext(text) else text
                pooled {
                    if (r is GapGptChatRunner) r.send(prompt, null)
                    else r.send(prompt, IdeMcpServer.getInstance(project).ensureStarted(chat))
                }
            }
            "stop" -> {
                broker.cancelAll(chat)
                runners[chat]?.stop()
            }
            "clearChat" -> {
                broker.resetSession(chat)
                runners[chat]?.resetSession()
            }
            "closeChat" -> {
                broker.resetSession(chat)
                runners.remove(chat)?.resetSession()
                IdeMcpServer.getInstance(project).forgetChat(chat)
            }
            "permission" -> broker.respond(str("id"), str("action"), msg.get("reason")?.asString)
            "fileSearch" -> {
                val reqId = msg.get("reqId")?.asInt ?: 0
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
            "setMode" -> GapGptSettings.get().permissionMode = str("value")
            "setModel" -> GapGptSettings.get().let {
                if (isGapGpt()) {
                    it.gapgptModel = str("value")
                    pooled { sendGapGptStatus(chat, false) }
                } else it.model = str("value")
            }
            "gapgptStatus" -> pooled { sendGapGptStatus(chat, msg.get("refresh")?.asBoolean == true) }
            "gapgptUsageReset" -> {
                GapGptUsage.get().reset()
                pooled { sendGapGptStatus(chat, false) }
            }
            "setBackend" -> {
                val b = if (str("value") == GapGptSettings.BACKEND_CLAUDE) GapGptSettings.BACKEND_CLAUDE else GapGptSettings.BACKEND_GAPGPT
                GapGptSettings.get().backend = b
                PluginLog.info("backend", "engine -> $b")
                sendInit()
                pooled { sendAccount() }
                if (b == GapGptSettings.BACKEND_GAPGPT) pooled {
                    sendGapGptModels(false)
                    sendGapGptStatus(chat, false)
                }
            }
            "gapgptModels" -> pooled { sendGapGptModels(msg.get("refresh")?.asBoolean == true) }
            "setEffort" -> GapGptSettings.get().effort = str("value")
            "setLanguage" -> {
                GapGptSettings.get().uiLanguage = if (str("value") == "fa") "fa" else "en"
                PluginLog.info("ui", "interface language -> ${GapGptSettings.get().uiLanguage}")
            }
            "setAutoFollow" -> GapGptSettings.get().autoFollow = msg.get("value")?.asBoolean ?: true
            "attach" -> attach(str("kind"))
            "listCommands" -> pooled {
                post("commands", JsonObject().apply { add("items", gson.toJsonTree(SlashCommands.list(project.basePath))) })
            }
            "copy" -> CopyPasteManager.getInstance().setContents(StringSelection(str("text")))
            "openUrl" -> str("url").takeIf { it.startsWith("http") }?.let { BrowserUtil.browse(it) }
            "insertCode" -> insertAtCaret(str("text"))
            "openFile" -> openFile(str("path"), msg.get("line")?.asInt ?: 0)
            "settings" -> ApplicationManager.getApplication().invokeLater {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "GapGPT")
                sendInit()
            }

            // ----- Session tab
            "session" -> {
                val sid = runners[chat]?.sessionId
                if (runners[chat] is GapGptChatRunner || (runners[chat] == null && isGapGpt())) {
                    post("sessionInfo", chatJson(chat).apply { sid?.let { addProperty("sessionId", it) } })
                    pooled { sendAccount() }
                    pooled { sendGapGptStatus(chat, false) }
                    return
                }
                pooled {
                    post("sessionInfo", chatJson(chat).apply {
                        CliInfo.context(project.basePath, sid)?.let { add("context", it) }
                        CliInfo.version()?.let { addProperty("version", it) }
                        sid?.let { addProperty("sessionId", it) }
                    })
                }
                pooled { sendAccount() }
            }
            "logout" -> ApplicationManager.getApplication().invokeLater {
                val ok = Messages.showYesNoDialog(
                    project, "از حساب Claude خارج شوید؟ برای استفادهٔ دوباره باید در ترمینال «claude» را اجرا و وارد شوید.",
                    "GapGPT", Messages.getQuestionIcon(),
                ) == Messages.YES
                if (ok) pooled {
                    CliInfo.logout()
                    PluginLog.info("account", "Logged out of Claude")
                    sendAccount()
                }
            }

            "usage" -> pooled { if (isGapGpt()) sendGapGptStatus(chat, true) else CliInfo.usage(project.basePath)?.let { post("usage", it) } }

            // ----- Skills / Rules tabs
            "skills" -> {
                val paths = msg.getAsJsonArray("pluginPaths")?.mapNotNull { it.asString } ?: emptyList()
                pooled { post("skills", ClaudeConfig.skills(project.basePath, paths)) }
            }
            "rules" -> pooled { post("rules", ClaudeConfig.rules(project.basePath)) }
            "createFile" -> {
                val path = str("path")
                if (path.isNotBlank()) pooled {
                    val f = File(path)
                    if (!f.exists()) {
                        f.parentFile?.mkdirs()
                        f.writeText(str("content"))
                    }
                    openFile(f.absolutePath, 1)
                    post("rules", ClaudeConfig.rules(project.basePath))
                }
            }

            // ----- Git tab
            "git" -> pooled { post("git", GitInfo.overview(project.basePath)) }
            "ideAction" -> ApplicationManager.getApplication().invokeLater { runIdeAction(str("id")) }

            // ----- Guard tab
            "guard" -> post("guard", guardJson())
            "guardToggle" -> {
                GapGptSettings.get().guardEnabled = msg.get("value")?.asBoolean ?: true
                post("guard", guardJson())
            }
            "guardRule" -> {
                Guard.setRuleEnabled(str("id"), msg.get("enabled")?.asBoolean ?: true)
                post("guard", guardJson())
            }
            "guardWhitelist" -> {
                Guard.whitelist(str("target"))
                post("guard", guardJson())
            }
            "guardUnwhitelist" -> {
                val s = GapGptSettings.get()
                s.guardWhitelist = s.guardWhitelist - str("target")
                post("guard", guardJson())
            }

            // ----- Log tab
            "log" -> post("log", JsonObject().apply {
                add("lines", JsonArray().apply { PluginLog.snapshot().forEach { add(lineJson(it)) } })
                addProperty("debug", PluginLog.debugEnabled)
            })
            "logDebug" -> {
                PluginLog.debugEnabled = msg.get("value")?.asBoolean ?: false
                PluginLog.info("log", "debug logging " + if (PluginLog.debugEnabled) "on" else "off")
            }

            // ----- Vulnerabilities tab
            "vulnInventory" -> pooled {
                val deps = DependencyScanner.inventory(project.basePath)
                depInventory = deps
                post("vulnInventory", JsonObject().apply {
                    addProperty("count", deps.size)
                    add("items", JsonArray().apply {
                        deps.take(3000).forEach { d ->
                            add(JsonObject().apply {
                                addProperty("ecosystem", d.ecosystem)
                                addProperty("name", d.name)
                                addProperty("version", d.version)
                                addProperty("file", d.file)
                            })
                        }
                    })
                })
            }
            "vulnScan" -> pooled {
                val deps = depInventory ?: DependencyScanner.inventory(project.basePath)
                val res = try {
                    DependencyScanner.scan(deps)
                } catch (e: Exception) {
                    PluginLog.warn("vulns", "Scan failed: ${e.message}")
                    JsonObject().apply { addProperty("error", e.message ?: e.javaClass.simpleName) }
                }
                post("vulnResult", res)
            }
        }
    }

    private fun pooled(block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            block()
        } catch (e: Exception) {
            PluginLog.warn("bridge", "Background task failed: ${e.message}")
        }
    }

    private fun sendAccount() {
        if (isGapGpt()) {
            val hasKey = GapGptApi.apiKey.isNotBlank()
            post("account", JsonObject().apply {
                addProperty("loggedIn", hasKey)
                if (hasKey) addProperty("email", "GapGPT API")
                addProperty("apiProvider", "GapGPT · " + GapGptApi.baseUrl())
                addProperty("authMethod", "API key")
            })
            return
        }
        val acc = CliInfo.account() ?: JsonObject().apply { addProperty("error", "claude پیدا نشد") }
        post("account", acc)
    }

    private fun sendGapGptModels(refresh: Boolean) {
        if (!isGapGpt()) return
        val res = JsonObject()
        try {
            res.add("items", JsonArray().apply {
                GapGptApi.listModels(refresh).forEach { m ->
                    add(JsonObject().apply {
                        addProperty("id", m.id)
                        addProperty("owner", m.ownedBy)
                    })
                }
            })
        } catch (e: Exception) {
            PluginLog.warn("gapgpt", "model list failed: ${e.message}")
            res.addProperty("error", e.message ?: e.javaClass.simpleName)
        }
        post("gapgptModels", res)
    }

    /**
     * Prices, limits, account credit and per-model token usage of the GapGPT engine.
     * [refresh] re-reads the credit from the API (otherwise a value up to a minute old is used).
     */
    private fun sendGapGptStatus(chat: String?, refresh: Boolean) {
        if (!isGapGpt()) return
        val model = GapGptSettings.get().gapgptModel.trim().ifBlank { GapGptApi.DEFAULT_MODEL }
        if (refresh) GapGptApi.invalidateBalance()
        val res = JsonObject()
        res.addProperty("model", model)
        GapGptPricing.price(model)?.let { p ->
            res.add("price", JsonObject().apply {
                addProperty("provider", p.provider)
                addProperty("input", p.input)
                p.cached?.let { addProperty("cached", it) }
                addProperty("output", p.output)
            })
        }
        res.addProperty("priceAsOf", GapGptPricing.asOf)
        val apiModel = runCatching { GapGptApi.listModels() }.getOrNull()?.firstOrNull { it.id == model }
        val lim = GapGptPricing.limits(model, apiModel)
        res.add("limits", JsonObject().apply {
            addProperty("contextWindow", lim.contextWindow)
            lim.maxOutput?.let { addProperty("maxOutput", it) }
            addProperty("estimated", lim.estimated)
        })
        runCatching { GapGptApi.balance() }.getOrNull()?.let { b ->
            res.add("balance", JsonObject().apply {
                b.limit?.let { addProperty("limit", it) }
                b.used?.let { addProperty("used", it) }
                b.remaining?.let { addProperty("remaining", it) }
            })
        }
        (chat?.let { runners[it] } as? GapGptChatRunner)?.lastRateLimit?.takeIf { it.isNotEmpty() }?.let { rl ->
            res.add("rateLimit", JsonObject().apply { rl.forEach { (k, v) -> addProperty(k.removePrefix("x-ratelimit-"), v) } })
        }
        val usage = GapGptUsage.get()
        res.addProperty("since", usage.since)
        res.add("usage", JsonArray().apply {
            usage.all().entries.sortedByDescending { it.value.lastUsed }.forEach { (id, t) ->
                add(JsonObject().apply {
                    addProperty("model", id)
                    addProperty("requests", t.requests)
                    addProperty("input", t.input)
                    addProperty("cached", t.cached)
                    addProperty("output", t.output)
                    addProperty("reasoning", t.reasoning)
                    addProperty("cost", t.cost)
                    addProperty("priced", GapGptPricing.price(id) != null)
                    addProperty("lastUsed", t.lastUsed)
                })
            }
        })
        post("gapgptStatus", res)
    }

    private fun guardJson() = JsonObject().apply {
        val s = GapGptSettings.get()
        addProperty("enabled", s.guardEnabled)
        add("whitelist", gson.toJsonTree(s.guardWhitelist))
        add("rules", JsonArray().apply {
            Guard.RULES.forEach { r ->
                add(JsonObject().apply {
                    addProperty("id", r.id)
                    addProperty("category", r.category)
                    addProperty("action", r.action.name.lowercase())
                    addProperty("description", r.description)
                    addProperty("enabled", r.id !in s.guardDisabledRules)
                })
            }
        })
        add("decisions", gson.toJsonTree(Guard.decisions()))
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
            }.withTitle("GapGPT: افزودن به پیام")
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
            notify("این عمل در IDE شما در دسترس نیست ($id). شاید پلاگین Git فعال نباشد.", NotificationType.WARNING)
            return
        }
        am.tryToExecute(action, null, browser.component, "GapGPT", true)
    }

    private fun insertAtCaret(text: String) {
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                notify("هیچ ویرایشگری باز نیست.", NotificationType.WARNING)
                return@invokeLater
            }
            WriteCommandAction.runWriteCommandAction(project, "Insert Code from Claude", null, {
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
            addProperty("permissionMode", s.permissionMode)
            addProperty("backend", s.backend)
            addProperty("lang", s.uiLanguage)
            addProperty("model", if (s.backend == GapGptSettings.BACKEND_GAPGPT) s.gapgptModel else s.model)
            addProperty("effort", s.effort)
            addProperty("shareSelection", s.shareSelection)
            addProperty("guardEnabled", s.guardEnabled)
            addProperty("autoFollow", s.autoFollow)
        })
    }

    private fun post(kind: String, payload: JsonObject) {
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
        val js = String(res("i18n.js"), Charsets.UTF_8) + "\n" + String(res("chat.js"), Charsets.UTF_8)
        val font = Base64.getEncoder().encodeToString(res("Vazirmatn.woff2"))
        return html
            .replace("/*__CSS__*/", css.replace("__FONT_BASE64__", font))
            .replace("/*__JS__*/", js)
    }

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    override fun dispose() {
        PluginLog.removeListener(logListener)
        if (broker.ui === this) broker.ui = null
        broker.cancelAll()
        runners.values.forEach { it.stop() }
        runners.clear()
        ChatPanelHolder.getInstance(project).let { if (it.panel === this) it.panel = null }
    }
}
