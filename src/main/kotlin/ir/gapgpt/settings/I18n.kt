package ir.gapgpt.settings

/** Interface language of the plugin's own UI (English by default). The webview has its own i18n.js. */
object I18n {
    val isFa: Boolean get() = runCatching { GapGptSettings.get().uiLanguage == "fa" }.getOrDefault(false)

    /** Picks the English or Persian text for the current interface language. */
    fun t(en: String, fa: String): String = if (isFa) fa else en

    /**
     * System-prompt instruction for the reply language.
     * auto = answer in the language the user writes in (Persian or English).
     */
    fun replyInstruction(mode: String): String = when (mode) {
        "fa" -> PERSIAN
        "en" -> "Always answer in English, even if the user writes in another language."
        else -> AUTO
    }

    private const val CODE_RULES =
        "Keep source code, identifiers, file paths, shell commands, and technical terms that have no common " +
            "Persian equivalent in English. Never put Persian text inside code blocks except for string literals or " +
            "comments the user asked for. Do not mix scripts inside a single word, and put each Persian or English " +
            "sentence on its own line or paragraph when it helps right-to-left / left-to-right rendering."

    const val AUTO =
        "Reply in the same language as the user's latest message: write in fluent, natural Persian (Farsi) when " +
            "the user writes in Persian, and in English when the user writes in English. $CODE_RULES"

    const val PERSIAN =
        "The user prefers Persian (Farsi). Always write your explanations and answers in fluent, natural Persian " +
            "unless the user explicitly writes in or asks for another language. $CODE_RULES"
}
