package ir.gapgpt.context

import java.io.File

/** Reads project/user level instructions from `.gapgpt/` (and `~/.gapgpt/`). Blocking file reads. */
object ProjectInstructions {
    const val DIR = ".gapgpt"

    data class Skill(val name: String, val description: String, val path: String, val source: String)
    data class Rule(val name: String, val path: String, val scope: String, val lines: Int, val preview: String)

    private const val MAX_FILE = 20_000
    private const val MAX_TOTAL = 60_000
    private const val MAX_SKILLS_IN_PROMPT = 100

    private fun userDir(): File = File(System.getProperty("user.home") ?: "", DIR)
    private fun projectDir(projectDir: String?): File? = projectDir?.takeIf { it.isNotBlank() }?.let { File(it, DIR) }

    private fun mdFiles(dir: File): List<File> = runCatching {
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.extension.equals("md", true) }.sortedBy { it.path }.toList()
    }.getOrDefault(emptyList())

    private fun readText(f: File): String? = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()

    private val frontMatter = Regex("^\\uFEFF?---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(\\r?\\n|$)", RegexOption.DOT_MATCHES_ALL)

    private fun stripFrontMatter(text: String): String {
        val m = frontMatter.find(text) ?: return text
        return text.substring(m.range.last + 1).trimStart('\r', '\n')
    }

    private fun describe(text: String): String {
        val fm = frontMatter.find(text)
        if (fm != null) {
            val d = fm.groupValues[1].lines().firstOrNull { it.trimStart().startsWith("description:") }
            val v = d?.substringAfter("description:")?.trim()?.trim('"', '\'')
            if (!v.isNullOrBlank() && v != ">" && v != "|") return v.take(220)
        }
        val body = stripFrontMatter(text)
        val line = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.all { c -> c == '#' } }
        return line?.trimStart('#', ' ')?.take(220) ?: ""
    }

    private fun collectSkills(base: File?, source: String, out: MutableList<Skill>, seen: MutableSet<String>) {
        if (base == null) return
        runCatching {
            base.resolve("skills").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
                val f = File(d, "SKILL.md")
                if (f.isFile && seen.add(d.name)) {
                    out += Skill(d.name, describe(readText(f).orEmpty()), f.absolutePath, source)
                }
            }
        }
        val cmds = base.resolve("commands")
        for (f in mdFiles(cmds)) {
            val name = f.relativeTo(cmds).invariantSeparatorsPath.removeSuffix(".md").replace('/', ':')
            if (seen.add(name)) out += Skill(name, describe(readText(f).orEmpty()), f.absolutePath, source)
        }
    }

    fun skills(projectDir: String?): List<Skill> {
        val out = mutableListOf<Skill>()
        val seen = mutableSetOf<String>()
        runCatching { collectSkills(projectDir(projectDir), "project", out, seen) }
        runCatching { collectSkills(userDir(), "user", out, seen) }
        return out
    }

    private fun collectRules(base: File?, scope: String, prefix: String, out: MutableList<Rule>) {
        if (base == null) return
        val files = mutableListOf<Pair<File, String>>()
        val agents = File(base, "AGENTS.md")
        if (agents.isFile) files += agents to scope
        mdFiles(File(base, "rules")).forEach { files += it to "$scope-rule" }
        for ((f, sc) in files) {
            val text = readText(f) ?: continue
            val all = text.lines()
            val preview = all.filter { it.isNotBlank() }.take(8).joinToString("\n").take(800)
            val rel = f.relativeTo(base).invariantSeparatorsPath
            out += Rule("$prefix$DIR/$rel", f.absolutePath, sc, all.size, preview)
        }
    }

    fun rules(projectDir: String?): List<Rule> {
        val out = mutableListOf<Rule>()
        runCatching { collectRules(projectDir(projectDir), "project", "", out) }
        runCatching { collectRules(userDir(), "user", "~/", out) }
        return out
    }

    fun systemPrompt(projectDir: String?): String {
        // user first, then project, so project instructions can override.
        val ordered = rules(projectDir).sortedBy { if (it.scope.startsWith("user")) 0 else 1 }
        val skills = skills(projectDir)
        if (ordered.isEmpty() && skills.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("The following project instructions and rules apply. Follow them.\n")
        var total = 0
        var truncated = false
        for (r in ordered) {
            if (total >= MAX_TOTAL) { truncated = true; break }
            var text = readText(File(r.path)) ?: continue
            if (text.isBlank()) continue
            if (text.length > MAX_FILE) text = text.take(MAX_FILE) + "\n[... truncated ...]"
            val room = MAX_TOTAL - total
            if (text.length > room) { text = text.take(room) + "\n[... truncated ...]"; truncated = true }
            sb.append("\n## ").append(r.name).append("\n\n").append(text.trim()).append("\n")
            total += text.length
        }
        if (truncated) sb.append("\n[Some instructions were truncated because of size limits.]\n")

        if (skills.isNotEmpty()) {
            sb.append("\n## Available skills\n\nThe user can invoke these as /<name> [arguments]:\n")
            skills.take(MAX_SKILLS_IN_PROMPT).forEach { s ->
                sb.append("- /").append(s.name)
                if (s.description.isNotBlank()) sb.append(" — ").append(s.description)
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun expandSkill(projectDir: String?, name: String, args: String): String? {
        val n = name.trim().removePrefix("/")
        val skill = skills(projectDir).firstOrNull { it.name == n } ?: return null
        val raw = readText(File(skill.path)) ?: return null
        val body = stripFrontMatter(raw).trim()
        return if (body.contains("\$ARGUMENTS")) body.replace("\$ARGUMENTS", args)
        else if (args.isNotBlank()) "$body\n\nArguments: $args"
        else body
    }
}
