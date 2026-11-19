package ir.gapgpt.chat

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import ir.gapgpt.PluginLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saved conversations, one JSON file per session under the IDE system directory (not in the project, so nothing
 * ends up in VCS). A session holds the model history ([ChatMessage]s + compaction summary) and the transcript the
 * chat page shows (user / assistant / tool / notice entries).
 */
object SessionStore {

    class Meta(val id: String, val title: String, val updated: Long, val messages: Int)

    class Session(
        val id: String,
        val title: String,
        val created: Long,
        val updated: Long,
        val summary: String?,
        val messages: List<ChatMessage>,
        val transcript: JsonArray,
    )

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val ID = Regex("[A-Za-z0-9-]{8,64}")
    private const val MAX_SESSIONS = 100

    private fun dir(project: Project): File =
        File(PathManager.getSystemPath(), "gapgpt/sessions/" + project.locationHash)

    private fun file(project: Project, id: String): File? = if (ID.matches(id)) File(dir(project), "$id.json") else null

    /** Writes atomically (temp file + move) and keeps at most [MAX_SESSIONS] files. Blocking I/O. */
    @Synchronized
    fun save(project: Project, s: Session) {
        val f = file(project, s.id) ?: return
        try {
            f.parentFile.mkdirs()
            val json = JsonObject().apply {
                addProperty("version", 1)
                addProperty("id", s.id)
                addProperty("title", s.title)
                addProperty("created", s.created)
                addProperty("updated", s.updated)
                s.summary?.let { addProperty("summary", it) }
                add("messages", JsonArray().apply { s.messages.forEach { add(messageJson(it)) } })
                add("transcript", s.transcript)
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(json), Charsets.UTF_8)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            prune(project)
        } catch (e: Exception) {
            PluginLog.warn("sessions", "Could not save session: ${e.javaClass.simpleName}")
        }
    }

    fun load(project: Project, id: String): Session? {
        val f = file(project, id)?.takeIf { it.isFile } ?: return null
        return try {
            val o = JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject
            Session(
                id = id,
                title = o.str("title"),
                created = o.long("created"),
                updated = o.long("updated"),
                summary = o.get("summary")?.takeIf { it.isJsonPrimitive }?.asString,
                messages = (o.get("messages") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseMessage) }.orEmpty(),
                transcript = o.get("transcript") as? JsonArray ?: JsonArray(),
            )
        } catch (e: Exception) {
            PluginLog.warn("sessions", "Could not read session $id: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Newest first. Reads only the small header fields of each file. */
    fun list(project: Project): List<Meta> {
        val files = dir(project).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.take(MAX_SESSIONS).mapNotNull { f ->
            try {
                val o = JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject
                Meta(o.str("id"), o.str("title"), o.long("updated"), (o.get("messages") as? JsonArray)?.size() ?: 0)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun delete(project: Project, id: String) {
        file(project, id)?.delete()
    }

    private fun prune(project: Project) {
        val files = dir(project).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_SESSIONS).forEach { it.delete() }
    }

    // ---------------------------------------------------------------- JSON

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.long(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L

    private fun messageJson(m: ChatMessage) = JsonObject().apply {
        addProperty("role", m.role)
        addProperty("content", m.content)
        if (m.toolCalls.isNotEmpty()) add("toolCalls", JsonArray().apply {
            m.toolCalls.forEach { c ->
                add(JsonObject().apply {
                    addProperty("id", c.id)
                    addProperty("name", c.name)
                    addProperty("arguments", c.arguments)
                })
            }
        })
        m.toolCallId?.let { addProperty("toolCallId", it) }
    }

    private fun parseMessage(o: JsonObject): ChatMessage? {
        val role = o.str("role").takeIf { it in setOf("user", "assistant", "tool") } ?: return null
        val calls = (o.get("toolCalls") as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.let { ToolCall(it.str("id"), it.str("name"), it.str("arguments")) }
        }.orEmpty()
        return ChatMessage(role, o.str("content"), calls, o.get("toolCallId")?.takeIf { it.isJsonPrimitive }?.asString)
    }
}
