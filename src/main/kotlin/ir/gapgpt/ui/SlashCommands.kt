package ir.gapgpt.ui

import java.io.File

/** Slash commands offered when the user types `/` in the chat input. */
object SlashCommands {

    data class Command(val name: String, val description: String, val source: String)

    private val BUILT_IN = listOf(
        Command("clear", "شروع گفتگوی جدید", "builtin"),
        Command("compact", "خلاصه‌سازی گفتگو برای آزاد کردن context", "builtin"),
        Command("review", "بررسی تغییرات فعلی کد", "builtin"),
        Command("init", "ساخت فایل CLAUDE.md برای این پروژه", "builtin"),
    )

    /** Built-ins plus custom commands from `.claude/commands` (project and user) and skills. */
    fun list(projectDir: String?): List<Command> {
        val out = ArrayList(BUILT_IN)
        val home = System.getProperty("user.home")
        val dirs = listOfNotNull(projectDir?.let { File(it, ".claude/commands") to "project" }, File(home, ".claude/commands") to "user")
        for ((dir, source) in dirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "md" }.forEach { f ->
                val name = f.relativeTo(dir).path.removeSuffix(".md").replace(File.separatorChar, ':')
                out += Command(name, description(f), source)
            }
        }
        val skillDirs = listOfNotNull(projectDir?.let { File(it, ".claude/skills") to "project" }, File(home, ".claude/skills") to "user")
        for ((dir, source) in skillDirs) {
            dir.listFiles()?.filter { File(it, "SKILL.md").isFile }?.forEach { d ->
                out += Command(d.name, description(File(d, "SKILL.md")), source)
            }
        }
        return out.distinctBy { it.name }
    }

    /** `description:` from the front matter, else the first non-empty line. */
    private fun description(f: File): String {
        val lines = runCatching { f.useLines { it.take(40).toList() } }.getOrDefault(emptyList())
        lines.firstOrNull { it.trimStart().startsWith("description:") }
            ?.substringAfter("description:")?.trim()?.trim('"', '\'')
            ?.let { return it.take(120) }
        return lines.dropWhile { it.trim() == "---" || it.isBlank() }
            .firstOrNull { it.isNotBlank() && !it.contains(":") || it.startsWith("#") }
            ?.trim()?.trimStart('#')?.trim()?.take(120).orEmpty()
    }
}
