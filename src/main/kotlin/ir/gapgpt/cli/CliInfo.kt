package ir.gapgpt.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ir.gapgpt.PluginLog
import ir.gapgpt.settings.GapGptSettings
import java.io.File
import java.util.concurrent.TimeUnit

/** One-off, local (no model call) queries to the Claude CLI: account status, context breakdown, logout. */
object CliInfo {

    private fun run(workDir: String?, stdin: String?, vararg args: String, timeoutSec: Long = 60): String? {
        val exe = ClaudeCliRunner.locateCli(GapGptSettings.get().cliPath) ?: return null
        return try {
            val pb = ProcessBuilder(listOf(exe) + args)
            workDir?.let { pb.directory(File(it)) }
            pb.environment()["NO_COLOR"] = "1"
            val p = pb.start()
            if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else out
        } catch (e: Exception) {
            PluginLog.warn("cli", "claude ${args.firstOrNull()} failed: ${e.message}")
            null
        }
    }

    /** `claude auth status` → email, plan, provider… */
    fun account(): JsonObject? {
        val out = run(null, null, "auth", "status", "--json", timeoutSec = 20) ?: return null
        return runCatching { JsonParser.parseString(out.substring(out.indexOf('{'))).asJsonObject }.getOrNull()
    }

    /**
     * Runs the local `/usage` command (no tokens are spent) and parses lines like
     * "Current week (Fable): 5% used · resets Nov 21 at 7:30am (Asia/Tehran)".
     */
    fun usage(workDir: String?): JsonObject? {
        val out = run(workDir, "/usage", "-p", "--output-format", "json") ?: return null
        val text = runCatching {
            JsonParser.parseString(out.substring(out.indexOf('{'))).asJsonObject.get("result").asString
        }.getOrNull() ?: return null
        val windows = JsonArray()
        val re = Regex("""^\s*(Current [^:]+):\s*(\d+(?:\.\d+)?)%\s*used(?:\s*[·•-]\s*resets\s+(.+))?$""", RegexOption.MULTILINE)
        re.findAll(text).forEach { m ->
            windows.add(JsonObject().apply {
                addProperty("label", m.groupValues[1].trim())
                addProperty("percent", m.groupValues[2].toDouble())
                addProperty("resets", m.groupValues[3].trim())
            })
        }
        return JsonObject().apply {
            add("windows", windows)
            text.substringAfter("What's contributing", "").takeIf { it.isNotBlank() }
                ?.let { addProperty("insights", "What's contributing$it".trim()) }
            text.lineSequence().firstOrNull { it.isNotBlank() }?.let { addProperty("headline", it.trim()) }
        }
    }

    fun version(): String? = run(null, null, "--version", timeoutSec = 15)?.trim()?.substringBefore(' ')

    fun logout(): Boolean = run(null, null, "auth", "logout", timeoutSec = 30) != null

    /**
     * Runs the local `/context` command (no tokens are spent) for [sessionId] (or a fresh session)
     * and parses its markdown into { model, used, total, percent, categories:[{name,tokens,percent}] }.
     */
    fun context(workDir: String?, sessionId: String?): JsonObject? {
        val args = mutableListOf("-p", "--output-format", "json")
        if (sessionId != null) args += listOf("--resume", sessionId)
        val out = run(workDir, "/context", *args.toTypedArray()) ?: return null
        val md = runCatching {
            JsonParser.parseString(out.substring(out.indexOf('{'))).asJsonObject.get("result").asString
        }.getOrNull() ?: return null

        val res = JsonObject()
        Regex("""\*\*Model:\*\*\s*(\S+)""").find(md)?.let { res.addProperty("model", it.groupValues[1]) }
        Regex("""\*\*Tokens:\*\*\s*([\d.]+[kKmM]?)\s*/\s*([\d.]+[kKmM]?)\s*\((\d+)%\)""").find(md)?.let {
            res.addProperty("used", tokens(it.groupValues[1]))
            res.addProperty("total", tokens(it.groupValues[2]))
            res.addProperty("percent", it.groupValues[3].toInt())
        }
        val cats = JsonArray()
        val section = md.substringAfter("### Estimated usage by category", "").substringBefore("###")
        Regex("""^\|\s*([^|]+?)\s*\|\s*([\d.]+[kKmM]?)\s*\|\s*([\d.]+)%\s*\|""", RegexOption.MULTILINE).findAll(section).forEach { m ->
            cats.add(JsonObject().apply {
                addProperty("name", m.groupValues[1])
                addProperty("tokens", tokens(m.groupValues[2]))
                addProperty("percent", m.groupValues[3].toDouble())
            })
        }
        res.add("categories", cats)
        return res
    }

    private fun tokens(s: String): Long {
        val n = s.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: return 0
        return when (s.lastOrNull()?.lowercaseChar()) {
            'k' -> (n * 1_000).toLong()
            'm' -> (n * 1_000_000).toLong()
            else -> n.toLong()
        }
    }
}
