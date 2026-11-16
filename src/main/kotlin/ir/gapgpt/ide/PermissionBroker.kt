package ir.gapgpt.ide

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import ir.gapgpt.PluginLog
import ir.gapgpt.settings.GapGptSettings
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers Claude's permission prompts (`--permission-prompt-tool`) by asking the user in the chat
 * panel, and — for file edits — in the IDE diff viewer. Every call first goes through [Guard].
 * State is kept per chat (each chat tab runs its own Claude process).
 */
@Service(Service.Level.PROJECT)
class PermissionBroker(private val project: Project) {

    /** Implemented by the chat panel. */
    interface Ui {
        fun showPermission(chatId: String, request: JsonObject)
        fun resolvePermission(chatId: String, id: String, outcome: String)
        fun guardChanged()
    }

    private sealed class Decision {
        data class Allow(val always: Boolean) : Decision()
        data class Deny(val message: String) : Decision()
    }

    private class Pending(val chatId: String, val future: CompletableFuture<Decision>, val preview: DiffPreview?)

    private val gson = Gson()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val sessionAllowed = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    var ui: Ui? = null

    private fun allowed(chatId: String) = sessionAllowed.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }

    /** Called from the MCP server thread; blocks until the user decides. Returns the JSON the CLI expects. */
    fun decide(args: JsonObject, chatId: String): String {
        val toolName = args.get("tool_name")?.asString.orEmpty()
        val input = args.getAsJsonObject("input") ?: JsonObject()

        // Our own IDE tools are always fine.
        if (toolName.startsWith("mcp__${IdeMcpServer.SERVER_NAME}__")) return allow(input)

        val rule = Guard.evaluate(chatId, toolName, input, project.basePath)
        val target = Guard.target(toolName, input)
        if (rule != null && rule.action == Guard.Action.BLOCK) {
            Guard.record(chatId, rule, toolName, target, "blocked")
            ui?.guardChanged()
            return deny(
                "Blocked by the IDE Guard rule '${rule.id}' (${rule.description}). " +
                    "Do not try to work around it; explain to the user what you wanted to do and why.",
            )
        }

        val isEdit = toolName in DiffPreview.EDIT_TOOLS
        if (rule == null && (toolName in allowed(chatId) || (isEdit && EDITS_KEY in allowed(chatId)))) return allow(input)

        val ui = this.ui ?: return deny("The IDE chat panel is not open, so the user could not be asked.")
        val id = args.get("tool_use_id")?.takeIf { it.isJsonPrimitive }?.asString ?: UUID.randomUUID().toString()
        val preview = if (isEdit) DiffPreview.compute(project, toolName, input) else null
        val p = Pending(chatId, CompletableFuture(), preview)
        pending[id] = p

        ui.showPermission(chatId, JsonObject().apply {
            addProperty("id", id)
            addProperty("tool", toolName)
            addProperty("isEdit", isEdit)
            add("input", input)
            if (rule != null) {
                addProperty("guardRule", rule.id)
                addProperty("guardText", rule.description)
            }
            if (preview != null) {
                addProperty("hasDiff", true)
                addProperty("file", preview.filePath)
                addProperty("newFile", preview.isNewFile)
                addProperty("added", preview.stats.first)
                addProperty("removed", preview.stats.second)
            }
        })
        if (preview != null && GapGptSettings.get().autoOpenDiff) openDiff(id)

        val decision = try {
            p.future.get()
        } catch (e: InterruptedException) {
            Decision.Deny("Cancelled by the user.")
        } catch (e: CancellationException) {
            Decision.Deny("Cancelled by the user.")
        } catch (e: Exception) {
            Decision.Deny("Permission prompt failed: ${e.message}")
        } finally {
            pending.remove(id)
            preview?.close()
        }

        if (rule != null) {
            Guard.record(chatId, rule, toolName, target, if (decision is Decision.Allow) "allowed" else "denied")
            ui.guardChanged()
        }
        return when (decision) {
            is Decision.Allow -> {
                if (decision.always && rule == null) allowed(chatId) += if (isEdit) EDITS_KEY else toolName
                val modified = preview?.userModified() == true
                ui.resolvePermission(chatId, id, if (modified) "allowedModified" else "allowed")
                PluginLog.info("permission", "allowed $toolName")
                allow(preview?.finalInput() ?: input)
            }
            is Decision.Deny -> {
                ui.resolvePermission(chatId, id, "denied")
                PluginLog.info("permission", "denied $toolName")
                deny(decision.message)
            }
        }
    }

    /** User clicked a button in the chat card. [action]: allow | always | deny | diff */
    fun respond(id: String, action: String, reason: String? = null) {
        val p = pending[id] ?: return
        when (action) {
            "allow" -> p.future.complete(Decision.Allow(always = false))
            "always" -> p.future.complete(Decision.Allow(always = true))
            "deny" -> p.future.complete(
                Decision.Deny(
                    reason?.takeIf { it.isNotBlank() }?.let { "The user rejected this and said: $it" }
                        ?: "The user rejected this tool call. Ask them how to proceed instead of retrying.",
                ),
            )
            "diff" -> openDiff(id)
        }
    }

    private fun openDiff(id: String) {
        val p = pending[id] ?: return
        val preview = p.preview ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || p.future.isDone) return@invokeLater
            preview.open(onAccept = { respond(id, "allow") }, onReject = { respond(id, "deny") })
        }
    }

    /** Rejects outstanding prompts of [chatId] (or of all chats). */
    fun cancelAll(chatId: String? = null) {
        pending.values.filter { chatId == null || it.chatId == chatId }
            .forEach { it.future.complete(Decision.Deny("Cancelled by the user.")) }
    }

    /** New conversation in [chatId]: forget its "always allow" choices. */
    fun resetSession(chatId: String) {
        cancelAll(chatId)
        sessionAllowed.remove(chatId)
    }

    private fun allow(input: JsonObject) = gson.toJson(JsonObject().apply {
        addProperty("behavior", "allow")
        add("updatedInput", input)
    })

    private fun deny(message: String) = gson.toJson(JsonObject().apply {
        addProperty("behavior", "deny")
        addProperty("message", message)
    })

    companion object {
        private const val EDITS_KEY = "<edits>"

        fun getInstance(project: Project): PermissionBroker = project.getService(PermissionBroker::class.java)
    }
}
