# GapGPT — پلاگین IntelliJ برای Claude Code با پشتیبانی کامل فارسی

پنل گفتگو با Claude Code داخل IDEهای JetBrains (IntelliJ IDEA، Android Studio، PyCharm، WebStorm و ...)،
با نمایش درست متن فارسی، راست‌به‌چپ و متن‌های ترکیبی فارسی/انگلیسی.

## چرا؟
پلاگین رسمی Claude Code، خود CLI را داخل ترمینال IDE اجرا می‌کند و ترمینال، حروف فارسی را جدا‌جدا و برعکس نشان می‌دهد.
این پلاگین Claude Code را در حالت headless (`claude -p --output-format stream-json`) اجرا می‌کند
و خروجی را در یک پنل HTML (JCEF) نمایش می‌دهد که از RTL و bidi به‌درستی پشتیبانی می‌کند.

## امکانات
- پنل گفتگوی راست‌به‌چپ با فونت **وزیرمتن** (داخل پلاگین)
- هر پاراگراف جهت خودش را از اولین حرف تشخیص می‌دهد (`unicode-bidi: plaintext`)؛ کد و مسیر فایل‌ها همیشه LTR
- نمایش زنده (streaming) پاسخ، نمایش ابزارهایی که Claude اجرا می‌کند (خواندن/ویرایش فایل، اجرای دستور ...)
- دکمه‌های **کپی** و **درج در ویرایشگر** برای هر بلوک کد
- کلیک روی نام فایل در پاسخ ← باز شدن فایل در IDE
- کلیک راست روی کد ← **GapGPT** ← ارسال انتخاب / توضیح کد / بررسی کد
- ادامهٔ گفتگو با `--resume` و دکمهٔ «گفتگوی جدید»
- تنظیمات: مسیر CLI، مدل، حالت دسترسی، پراکسی HTTPS، پاسخ به فارسی، تبدیل «ي/ك» عربی به «ی/ک»

## پیش‌نیاز
پلاگین دو «موتور پاسخ‌گویی» دارد که از منوی مدل در پایین چت یا از تنظیمات انتخاب می‌شوند:

1. **GapGPT API** (پیش‌فرض): اتصال مستقیم به `https://api.gapgpt.app/v1` (سازگار با OpenAI) با **همهٔ مدل‌های گپ‌جی‌پی‌تی**
   (GPT، Claude، Gemini، DeepSeek، Grok، Qwen و …). فهرست مدل‌ها زنده از `/v1/models` خوانده می‌شود.
   فقط یک **کلید API** از [پنل توسعه‌دهندگان](https://gapgpt.app/platform-v2/tokens) لازم است که در
   **Settings › Tools › GapGPT** وارد می‌شود (دکمهٔ «تست اتصال»). این حالت هیچ ارتباطی با Claude Code ندارد و روی آن اثری نمی‌گذارد.
   این موتور یک **agent** کامل است: با function calling فایل‌ها را می‌خواند، جستجو می‌کند (`Read`، `Glob`، `Grep`، `LS`)،
   ویرایش می‌کند (`Edit`، `Write`)، دستور اجرا می‌کند (`Bash`) و خطاهای IDE را می‌بیند (`getDiagnostics` و …).
   هر ویرایش و دستور طبق «حالت دسترسی» از شما اجازه گرفته می‌شود (با پیش‌نمایش Diff) و قوانین محافظ (Guard) روی همه اعمال می‌شود.
   اگر مدلی function calling را پشتیبانی نکند، خودکار به حالت فقط‌گفتگو برمی‌گردد.
2. **Claude Code CLI**: اجرای `claude` نصب‌شده با حساب و تنظیمات خودش، دقیقاً مثل قبل:
   ```bash
   npm install -g @anthropic-ai/claude-code
   claude        # یک بار اجرا کنید و login کنید
   ```

JDK 21 برای build (IDE باید نسخهٔ 2024.2 یا جدیدتر باشد)

## ساخت و نصب
```bash
./gradlew buildPlugin          # در ویندوز: gradlew.bat buildPlugin
```
فایل `build/distributions/gapgpt-intellij-0.1.0.zip` ساخته می‌شود.
در IDE: **Settings › Plugins › ⚙ › Install Plugin from Disk…** و انتخاب همان zip.

برای اجرای یک IDE آزمایشی همراه با پلاگین:
```bash
./gradlew runIde
```

> اگر از ایران build می‌کنید و دانلود وابستگی‌ها از `cache-redirector.jetbrains.com` یا Maven Central
> مشکل داشت، پراکسی را در `~/.gradle/gradle.properties` تنظیم کنید:
> ```properties
> systemProp.https.proxyHost=127.0.0.1
> systemProp.https.proxyPort=10809
> ```

## استفاده
- پنل **GapGPT** در نوار سمت راست IDE (یا `Ctrl+Alt+Shift+L`)
- انتخاب کد و `Ctrl+Alt+Shift+K` ← کد با مسیر و شمارهٔ خطوط به کادر ورودی اضافه می‌شود
- تنظیمات: **Settings › Tools › GapGPT**

### حالت‌های دسترسی
| حالت | توضیح |
|---|---|
| `default` | ابزارهایی که نیاز به تأیید دارند (ویرایش، اجرای دستور) رد می‌شوند |
| `acceptEdits` | (پیش‌فرض) ویرایش فایل‌ها مجاز است |
| `plan` | فقط تحلیل و برنامه‌ریزی، بدون تغییر |
| `bypassPermissions` | همه چیز بدون پرسش — فقط در پروژه‌های قابل‌اعتماد |

در حالت headless امکان پرسیدن تأیید در لحظه وجود ندارد؛ اگر ابزاری رد شود، پنل پیغام می‌دهد.

## ساختار پروژه
```
src/main/kotlin/ir/gapgpt/
  cli/ClaudeCliRunner.kt        اجرای claude، خواندن stream-json، --resume
  ui/ChatPanel.kt               JCEF browser + پل JS ↔ Kotlin
  ui/ChatToolWindowFactory.kt   ثبت پنل
  actions/SelectionActions.kt   اکشن‌های منوی ویرایشگر
  settings/                     تنظیمات
src/main/resources/webview/
  chat.html / chat.css / chat.js  رابط گفتگو (markdown، RTL، استریم)
  Vazirmatn.woff2                 فونت (مجوز OFL)
```

## ایده‌های توسعه
- تأیید تعاملی ابزارها از طریق `--permission-prompt-tool` و یک MCP server داخلی
- نمایش diff ویرایش‌ها با Diff Viewer خود IDE
- تاریخچهٔ گفتگوها (`claude --resume` لیست جلسات)
- ارسال خودکار خطاهای IDE (Problems) به Claude
