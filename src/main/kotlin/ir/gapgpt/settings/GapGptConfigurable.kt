package ir.gapgpt.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import ir.gapgpt.settings.I18n.t
import javax.swing.JLabel

/** Settings › Tools › GapGPT. Labels follow the interface language (applied the next time the page opens). */
class GapGptConfigurable : BoundConfigurable("GapGPT") {

    private val s get() = GapGptSettings.get()

    override fun createPanel(): DialogPanel = panel {
        lateinit var keyField: JBPasswordField
        lateinit var urlField: JBTextField
        lateinit var status: JLabel

        group(t("Language", "زبان")) {
            row(t("Interface language:", "زبان رابط:")) {
                comboBox(UI_LANGUAGES.keys.toList(), textListCellRenderer { UI_LANGUAGES[it] ?: it })
                    .bindItem({ s.uiLanguage }, { s.uiLanguage = it ?: "en" })
                    .comment(t("Also switchable from the language button next to Send.", "از دکمهٔ زبان کنار «ارسال» هم قابل تغییر است."))
            }
            row(t("Reply language:", "زبان پاسخ:")) {
                comboBox(REPLY_LANGUAGES.keys.toList(), textListCellRenderer { (REPLY_LANGUAGES[it] ?: (it.orEmpty() to it.orEmpty())).let { p -> t(p.first, p.second) } })
                    .bindItem({ s.replyLanguage }, { s.replyLanguage = it ?: "auto" })
                    .comment(t("Auto: the model answers in the language you write in (English or Persian).",
                        "خودکار: مدل به همان زبانی که می‌نویسید (فارسی یا انگلیسی) جواب می‌دهد."))
            }
        }
        group(t("GapGPT Account", "حساب GapGPT")) {
            row(t("Engine:", "موتور پاسخ‌گویی:")) {
                comboBox(BACKENDS.keys.toList(), textListCellRenderer { (BACKENDS[it] ?: (it.orEmpty() to it.orEmpty())).let { p -> t(p.first, p.second) } })
                    .bindItem({ s.backend }, { s.backend = it ?: GapGptSettings.BACKEND_GAPGPT })
                    .comment(t(
                        "GapGPT: talks to the GapGPT API directly with all its models; does not touch Claude Code. " +
                            "Claude Code: runs your installed CLI with its own account and settings, unchanged.",
                        "GapGPT: اتصال مستقیم به API گپ‌جی‌پی‌تی با همهٔ مدل‌ها؛ هیچ تأثیری روی Claude Code ندارد. " +
                            "Claude Code: اجرای CLI نصب‌شده با حساب و تنظیمات خودش، بدون هیچ تغییری.",
                    ))
            }
            row(t("API key:", "کلید API:")) {
                keyField = passwordField()
                    .align(AlignX.FILL)
                    .bindText({ GapGptApi.apiKey }, { GapGptApi.apiKey = it })
                    .comment(t(
                        "Create one in the <a href=\"https://gapgpt.app/platform-v2/tokens\">developer panel › API keys</a>. Stored in the IDE Password Safe.",
                        "از <a href=\"https://gapgpt.app/platform-v2/tokens\">پنل توسعه‌دهندگان › کلیدهای API</a> بسازید. در Password Safe ذخیره می‌شود.",
                    ))
                    .component
            }
            row(t("API URL:", "آدرس API:")) {
                urlField = textField()
                    .align(AlignX.FILL)
                    .bindText(s::gapgptBaseUrl)
                    .comment(t("Default", "پیش‌فرض") + ": ${GapGptApi.DEFAULT_BASE_URL} · CDN: ${GapGptApi.CDN_BASE_URL}")
                    .component
            }
            row(t("GapGPT model:", "مدل GapGPT:")) {
                textField()
                    .bindText(s::gapgptModel)
                    .comment(t(
                        "Model id, e.g. claude-sonnet-5-5, gpt-5.6-sol or gemini-2.5-pro — full list in the model menu of the chat",
                        "شناسهٔ مدل، مثلاً claude-sonnet-5-5، gpt-5.6-sol یا gemini-2.5-pro — فهرست کامل در منوی مدل داخل چت",
                    ))
            }
            row {
                checkBox(t("Allow tools: read and edit files, run commands and check IDE diagnostics",
                    "اجازهٔ استفاده از ابزارها: خواندن و ویرایش فایل‌ها، اجرای دستور و بررسی خطاهای IDE"))
                    .bindSelected(s::gapgptTools)
                    .comment(t("Every edit and command asks you first, according to the permission mode and the Guard rules.",
                        "هر ویرایش و دستور طبق «حالت دسترسی» و قوانین محافظ (Guard) از شما اجازه گرفته می‌شود."))
            }
            row {
                button(t("Test Connection", "تست اتصال")) {
                    status.text = t("Checking…", "در حال بررسی…")
                    val key = String(keyField.password)
                    val url = GapGptApi.normalizeBaseUrl(urlField.text)
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val msg = GapGptApi.testConnection(url, key)
                        ApplicationManager.getApplication().invokeLater({ status.text = msg }, ModalityState.any())
                    }
                }
                status = label("").component
            }
        }
        group(t("Claude Code Connection", "اتصال به Claude Code")) {
            row(t("claude CLI path:", "مسیر claude CLI:")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::cliPath)
                    .comment(t("Empty = auto-detect (PATH, ~/.local/bin, ~/.claude/local …)",
                        "خالی = تشخیص خودکار (PATH، ‎~/.local/bin، ‎~/.claude/local و ...)"))
            }
            row(t("Claude Code model:", "مدل Claude Code:")) {
                textField()
                    .bindText(s::model)
                    .comment(t("Empty = default model. e.g. sonnet or opus", "خالی = مدل پیش‌فرض. مثال: sonnet یا opus"))
            }
            row(t("Permission mode:", "حالت دسترسی:")) {
                comboBox(PERMISSION_MODES)
                    .bindItem({ s.permissionMode }, { s.permissionMode = it ?: "default" })
                    .comment(t(
                        "default: asks before every edit and command · acceptEdits: edits without asking · " +
                            "plan: planning only, no changes · bypassPermissions: everything allowed (careful)",
                        "default: برای هر ویرایش و دستور از شما اجازه گرفته می‌شود · acceptEdits: ویرایش فایل‌ها بدون پرسش · " +
                            "plan: فقط برنامه‌ریزی، بدون تغییر · bypassPermissions: همه چیز مجاز (با احتیاط)",
                    ))
            }
            row {
                checkBox(t("Open proposed changes in the Diff Viewer automatically", "نمایش خودکار تغییرات پیشنهادی در Diff Viewer"))
                    .bindSelected(s::autoOpenDiff)
            }
            row {
                checkBox(t("Share the open file and selection with the model", "اشتراک خودکار فایل باز و متن انتخاب‌شده با مدل"))
                    .bindSelected(s::shareSelection)
            }
            row(t("HTTPS proxy:", "پراکسی HTTPS:")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::httpsProxy)
                    .comment(t("Optional, e.g. http://127.0.0.1:10809", "اختیاری، مثال: http://127.0.0.1:10809"))
            }
            row(t("Extra arguments:", "آرگومان‌های اضافه:")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::extraArgs)
                    .comment(t("e.g. --add-dir ../shared", "مثال: --add-dir ../shared"))
            }
        }
        group(t("Appearance", "ظاهر")) {
            row {
                checkBox(t("Convert Arabic «ي» and «ك» to Persian «ی» and «ک»", "تبدیل «ي» و «ك» عربی به «ی» و «ک» فارسی"))
                    .bindSelected(s::normalizeArabicChars)
            }
            row {
                checkBox(t("Send with Enter (new line with Shift+Enter)", "ارسال با Enter (خط جدید با Shift+Enter)"))
                    .bindSelected(s::sendOnEnter)
            }
            row(t("Font size:", "اندازه فونت:")) {
                intTextField(10..28).bindIntText(s::fontSize)
            }
        }
    }

    companion object {
        val UI_LANGUAGES = linkedMapOf("en" to "English", "fa" to "فارسی")
        val REPLY_LANGUAGES = linkedMapOf(
            "auto" to ("Auto (same as my message)" to "خودکار (همان زبان پیام من)"),
            "en" to ("Always English" to "همیشه انگلیسی"),
            "fa" to ("Always Persian" to "همیشه فارسی"),
        )
        val BACKENDS = linkedMapOf(
            GapGptSettings.BACKEND_GAPGPT to ("GapGPT API (all models)" to "GapGPT API (همهٔ مدل‌ها)"),
            GapGptSettings.BACKEND_CLAUDE to ("Claude Code CLI" to "Claude Code CLI"),
        )
        val PERMISSION_MODES = listOf("default", "acceptEdits", "plan", "bypassPermissions")
    }
}
