package ir.gapgpt.ui

import ir.gapgpt.I18n
import ir.gapgpt.context.ProjectInstructions

/** Slash commands offered when the user types `/` in the chat input. */
object SlashCommands {

    data class Command(val name: String, val description: String, val source: String)

    private val BUILT_IN
        get() = listOf(
            Command("clear", I18n.tr("Start a new conversation", "شروع گفتگوی جدید"), "builtin"),
            Command("new", I18n.tr("Start a new conversation", "شروع گفتگوی جدید"), "builtin"),
            Command("resume", I18n.tr("Resume a saved conversation", "ادامهٔ یک گفتگوی ذخیره‌شده"), "builtin"),
            Command("mcp", I18n.tr("Show MCP servers and their tools (/mcp reload restarts them)", "نمایش سرورهای MCP و ابزارهایشان (/mcp reload آن‌ها را دوباره اجرا می‌کند)"), "builtin"),
            Command("usage", I18n.tr("Show account credit, model price and local usage (/usage reset clears the counters)", "نمایش اعتبار حساب، قیمت مدل و مصرف محلی (/usage reset شمارنده‌ها را صفر می‌کند)"), "builtin"),
            Command("graphify", I18n.tr("Build the code graph, or answer with it: /graphify <question>", "ساخت گراف کد، یا پاسخ با آن: ‎/graphify <سؤال>"), "builtin"),
            Command("compact", I18n.tr("Summarize the conversation to free context (optional: what to focus on)", "خلاصه کردن گفتگو برای آزاد کردن context (اختیاری: روی چه چیزی تمرکز شود)"), "builtin"),
        )

    /** Built-ins plus the skills found in `.gapgpt/` (project and user). Blocking file I/O: call off the EDT. */
    fun list(projectDir: String?): List<Command> {
        val out = ArrayList(BUILT_IN)
        ProjectInstructions.skills(projectDir).forEach { out += Command(it.name, it.description, it.source) }
        return out.distinctBy { it.name }
    }
}
