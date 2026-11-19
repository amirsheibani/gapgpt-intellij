package ir.gapgpt.ide

import com.google.gson.JsonObject
import ir.gapgpt.I18n
import java.io.File
import java.util.concurrent.TimeUnit

/** Repository overview for the Git tab, read with the `git` CLI. */
object GitInfo {

    private fun git(dir: String, vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("git") + args).directory(File(dir)).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.close()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else if (p.exitValue() == 0) out.trim() else null
    } catch (e: Exception) {
        null
    }

    fun overview(projectDir: String?): JsonObject = JsonObject().apply {
        val dir = projectDir ?: return@apply addProperty("error", I18n.tr("The project has no directory.", "پروژه پوشه ندارد."))
        val root = git(dir, "rev-parse", "--show-toplevel")
            ?: return@apply addProperty("error", I18n.tr("This project is not a Git repository (or git is not installed).", "این پروژه یک مخزن Git نیست (یا git نصب نیست)."))
        addProperty("root", root)
        addProperty("branch", git(dir, "rev-parse", "--abbrev-ref", "HEAD") ?: "")
        addProperty("head", git(dir, "rev-parse", "HEAD") ?: "")
        addProperty("headSubject", git(dir, "log", "-1", "--pretty=%s") ?: "")
        val status = git(dir, "status", "--porcelain").orEmpty()
        addProperty("changed", status.lines().count { it.isNotBlank() })
        val upstream = git(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if (upstream != null) {
            addProperty("tracking", upstream)
            git(dir, "rev-list", "--left-right", "--count", "HEAD...@{u}")?.split(Regex("\\s+"))?.let {
                if (it.size == 2) {
                    addProperty("ahead", it[0].toIntOrNull() ?: 0)
                    addProperty("behind", it[1].toIntOrNull() ?: 0)
                }
            }
            git(dir, "merge-base", "HEAD", "@{u}")?.let { addProperty("divergedAt", it.take(7)) }
        }
        val log = git(dir, "log", "-8", "--pretty=%h\u0001%s\u0001%an\u0001%ar").orEmpty()
        add("log", com.google.gson.JsonArray().apply {
            log.lines().filter { it.isNotBlank() }.forEach { l ->
                val p = l.split('\u0001')
                add(JsonObject().apply {
                    addProperty("hash", p.getOrElse(0) { "" })
                    addProperty("subject", p.getOrElse(1) { "" })
                    addProperty("author", p.getOrElse(2) { "" })
                    addProperty("when", p.getOrElse(3) { "" })
                })
            }
        })
    }
}
