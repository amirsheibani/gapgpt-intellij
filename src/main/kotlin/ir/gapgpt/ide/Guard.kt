package ir.gapgpt.ide

import com.google.gson.JsonObject
import ir.gapgpt.PluginLog
import ir.gapgpt.settings.GapGptSettings
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * Safety rules applied to every tool call that reaches the IDE permission prompt.
 *  - BLOCK rules deny the call outright (unless the exact command is whitelisted).
 *  - WARN rules force a manual confirmation even when the tool was "always allowed".
 */
object Guard {

    enum class Action { BLOCK, WARN }

    data class Rule(
        val id: String,
        val category: String,
        val action: Action,
        val description: String,
        /** Tools the rule applies to; empty = Bash only. */
        val tools: Set<String> = setOf("Bash"),
        val matches: (target: String, projectDir: String?) -> Boolean,
    )

    data class Decision(
        val time: String,
        val chat: String,
        val ruleId: String,
        val category: String,
        val tool: String,
        val target: String,
        /** blocked | allowed | denied | whitelisted | disabled */
        val outcome: String,
    )

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val FILE_TOOLS = setOf("Read", "Edit", "MultiEdit", "Write", "NotebookEdit")
    private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

    val RULES: List<Rule> = listOf(
        Rule("rm-root", "destructive", Action.BLOCK, "حذف بازگشتی ریشه، خانه یا کل پوشه (rm -rf / ~ * ..)") { t, _ ->
            rx("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*|-[a-z]*f[a-z]*r[a-z]*|--recursive\s+--force|-r\s+-f|-f\s+-r)\s+(/|~|${'$'}HOME|\*|\.\.?/?)(\s|$)""").containsMatchIn(t)
        },
        Rule("disk-wipe", "destructive", Action.BLOCK, "فرمت یا بازنویسی دیسک (mkfs, dd به /dev)") { t, _ ->
            rx("""\bmkfs(\.\w+)?\b|\bdd\b[^|]*\bof=/dev/""").containsMatchIn(t)
        },
        Rule("fork-bomb", "destructive", Action.BLOCK, "fork bomb") { t, _ -> t.contains(":(){") || t.contains(":|:&") },
        Rule("chmod-777-root", "destructive", Action.BLOCK, "تغییر سطح دسترسی کل سیستم (chmod/chown -R روی /)") { t, _ ->
            rx("""\bch(mod|own)\s+-R\s+\S+\s+/(\s|$)""").containsMatchIn(t)
        },
        Rule("pipe-to-shell", "remote-code", Action.BLOCK, "اجرای مستقیم اسکریپت از اینترنت (curl/wget | sh)") { t, _ ->
            rx("""\b(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(ba|z|k)?sh\b""").containsMatchIn(t)
        },
        Rule("git-force-push", "git", Action.WARN, "push اجباری (git push --force)") { t, _ ->
            rx("""\bgit\s+push\b.*(\s--force(-with-lease)?\b|\s-f\b)""").containsMatchIn(t)
        },
        Rule("git-reset-hard", "git", Action.WARN, "دور ریختن تغییرات (git reset --hard / clean -fd / checkout .)") { t, _ ->
            rx("""\bgit\s+(reset\s+--hard|clean\s+-[a-z]*f|checkout\s+(--\s+)?\.(\s|$)|restore\s+\.(\s|$))""").containsMatchIn(t)
        },
        Rule("sudo", "privilege", Action.WARN, "اجرای دستور با sudo") { t, _ -> rx("""(^|[;&|]\s*)sudo\s""").containsMatchIn(t) },
        Rule("publish", "release", Action.WARN, "انتشار پکیج (npm publish, cargo publish, ...)") { t, _ ->
            rx("""\b(npm|yarn|pnpm|cargo|flutter\s+pub|dart\s+pub|gem|twine)\s+publish\b|\bgradle\w*\s+publish""").containsMatchIn(t)
        },
        Rule("secrets-read", "secrets", Action.WARN, "دسترسی به فایل‌های محرمانه (.env، کلید SSH، credentials)",
            tools = setOf("Bash") + FILE_TOOLS) { t, _ ->
            rx("""(^|[/\s'"])(\.env(\.\w+)?|id_(rsa|ed25519|ecdsa)|[\w.-]+\.pem|credentials(\.json)?|\.netrc|\.npmrc|\.pypirc)(\s|$|['"])|\.aws/|\.ssh/""").containsMatchIn(t)
        },
        Rule("outside-project", "scope", Action.WARN, "ویرایش فایل خارج از پوشهٔ پروژه", tools = EDIT_TOOLS) { t, dir ->
            dir != null && t.startsWith("/") && !File(t).absolutePath.startsWith(File(dir).absolutePath + File.separator) &&
                !t.startsWith(System.getProperty("java.io.tmpdir")) && !t.startsWith("/tmp/")
        },
    )

    private val log = ArrayDeque<Decision>()
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** What a tool call is about: the command for Bash, the path for file tools. */
    fun target(tool: String, input: JsonObject): String = when (tool) {
        "Bash" -> input.get("command")?.asString.orEmpty()
        else -> (input.get("file_path") ?: input.get("notebook_path") ?: input.get("path"))?.asString.orEmpty()
    }

    /** The first matching enabled rule, or null. Logs "disabled" when only a disabled rule matched. */
    fun evaluate(chat: String, tool: String, input: JsonObject, projectDir: String?): Rule? {
        val s = GapGptSettings.get()
        val t = target(tool, input)
        if (t.isBlank()) return null
        val hits = RULES.filter { tool in it.tools && runCatching { it.matches(t, projectDir) }.getOrDefault(false) }
        if (hits.isEmpty()) return null
        if (!s.guardEnabled) {
            record(chat, hits.first(), tool, t, "disabled")
            return null
        }
        val active = hits.firstOrNull { it.id !in s.guardDisabledRules }
        if (active == null) {
            record(chat, hits.first(), tool, t, "disabled")
            return null
        }
        if (t in s.guardWhitelist) {
            record(chat, active, tool, t, "whitelisted")
            return null
        }
        return active
    }

    fun record(chat: String, rule: Rule, tool: String, target: String, outcome: String) {
        val d = Decision(LocalTime.now().format(fmt), chat, rule.id, rule.category, tool, target.take(500), outcome)
        synchronized(log) {
            log.addLast(d)
            while (log.size > 500) log.removeFirst()
        }
        PluginLog.info("guard", "$outcome · ${rule.id} · $tool: ${target.take(200)}")
    }

    fun decisions(): List<Decision> = synchronized(log) { log.toList() }

    fun whitelist(target: String) {
        val s = GapGptSettings.get()
        if (target.isNotBlank() && target !in s.guardWhitelist) s.guardWhitelist = s.guardWhitelist + target
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        val s = GapGptSettings.get()
        s.guardDisabledRules = if (enabled) s.guardDisabledRules - id else (s.guardDisabledRules + id).distinct()
    }
}
