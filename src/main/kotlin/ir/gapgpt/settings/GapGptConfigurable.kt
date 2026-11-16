package ir.gapgpt.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class GapGptConfigurable : BoundConfigurable("GapGPT") {

    private val s get() = GapGptSettings.get()

    override fun createPanel(): DialogPanel = panel {
        group("اتصال به Claude Code") {
            row("مسیر claude CLI:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::cliPath)
                    .comment("خالی = تشخیص خودکار (PATH، ‎~/.local/bin، ‎~/.claude/local و ...)")
            }
            row("مدل:") {
                textField()
                    .bindText(s::model)
                    .comment("خالی = مدل پیش‌فرض. مثال: sonnet یا opus")
            }
            row("حالت دسترسی:") {
                comboBox(PERMISSION_MODES)
                    .bindItem({ s.permissionMode }, { s.permissionMode = it ?: "default" })
                    .comment(
                        "default: برای هر ویرایش و دستور از شما اجازه گرفته می‌شود · acceptEdits: ویرایش فایل‌ها بدون پرسش · " +
                            "plan: فقط برنامه‌ریزی، بدون تغییر · bypassPermissions: همه چیز مجاز (با احتیاط)"
                    )
            }
            row {
                checkBox("نمایش خودکار تغییرات پیشنهادی در Diff Viewer").bindSelected(s::autoOpenDiff)
            }
            row {
                checkBox("اشتراک خودکار فایل باز و متن انتخاب‌شده با Claude").bindSelected(s::shareSelection)
            }
            row("پراکسی HTTPS:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::httpsProxy)
                    .comment("اختیاری، مثال: http://127.0.0.1:10809")
            }
            row("آرگومان‌های اضافه:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(s::extraArgs)
                    .comment("مثال: --add-dir ../shared")
            }
        }
        group("زبان و ظاهر") {
            row {
                checkBox("پاسخ‌ها به فارسی باشد").bindSelected(s::respondInPersian)
            }
            row {
                checkBox("تبدیل «ي» و «ك» عربی به «ی» و «ک» فارسی").bindSelected(s::normalizeArabicChars)
            }
            row {
                checkBox("ارسال با Enter (خط جدید با Shift+Enter)").bindSelected(s::sendOnEnter)
            }
            row("اندازه فونت:") {
                intTextField(10..28).bindIntText(s::fontSize)
            }
        }
    }

    companion object {
        val PERMISSION_MODES = listOf("default", "acceptEdits", "plan", "bypassPermissions")
    }
}
