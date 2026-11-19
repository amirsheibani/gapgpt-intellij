<div dir="rtl" lang="fa">

# قوانین، اسکیل‌ها و دکمهٔ کپی

این سند توضیح می‌دهد که GapGPT قوانین و اسکیل‌های پروژه را از کجا می‌خواند و دکمهٔ «کپی قوانین و اسکیل‌ها» چه کاری انجام می‌دهد.

مرتبط: [راهنمای شروع](getting-started.md) · [ارتباط با API گپ‌جی‌پی‌تی](gapgpt-api.md) · [README](../README.md)

---

## نمای کلی

<div dir="ltr">

```
[Skills/Rules tab: Import button]      [Tools › GapGPT: Import Rules & Skills]
            │ scanInstructions                        │
            ▼                                         ▼
   ChatPanel.handleFromJs ──► InstructionsImporter.scan(projectDir)   (pooled thread)
            │ instructionsPreview                     │ Yes/No dialog
            ▼                                         ▼
   preview in webview ──importInstructions──► scan again ──► InstructionsImporter.copy()
                                                         │  Files.copy + rewrite (copy only) + VFS refresh
                                                         ▼
                                     instructionsImported {moved, skipped} → refresh tabs
```

</div>

---

## ۱. ساختار `.gapgpt/`

<div dir="ltr">

```
.gapgpt/
  AGENTS.md                 main project instructions
  rules/**/*.md             always added to the system prompt
  skills/<name>/SKILL.md    run with /<name> args ($ARGUMENTS)
  commands/**/*.md          run with /<name>
  agents/**/*.md            only listed
  mcp.json                  MCP servers (see chat-agent.md)
```

</div>

معادل سطح کاربر در `~/.gapgpt/` است. اگر اسکیل پروژه و اسکیل کاربر هم‌نام باشند، اسکیل پروژه برنده می‌شود.

## ۲. ساخت system prompt

پرامپت سیستم در هر درخواست از نو ساخته می‌شود (در `ChatPanel.sendMessage`) و این بخش‌ها را به همین ترتیب دارد:

1. پرامپت زبان (`Prompts.languagePrompt`)، اگر گزینهٔ پاسخ هم‌زبان با کاربر روشن باشد
2. `ProjectInstructions.systemPrompt()`: اول قوانین کاربر، بعد قوانین پروژه، و در آخر فهرست اسکیل‌ها
3. پرامپت اضافهٔ کاربر از تنظیمات

سقف هر فایل ۲۰ هزار کاراکتر و سقف کل ۶۰ هزار کاراکتر است. بیشتر از آن بریده می‌شود و یک یادداشت به انتهایش اضافه می‌شود.

## ۳. اجرای اسکیل

اگر پیام با `/name args` شروع شود و اسکیلی با آن نام وجود داشته باشد، `ProjectInstructions.expandSkill` بدنهٔ اسکیل را بدون front matter برمی‌گرداند. در آن بدنه `$ARGUMENTS` با `args` جایگزین می‌شود و همین متن به‌جای پیام کاربر فرستاده می‌شود.

## ۴. دکمهٔ کپی

| منبع | مقصد |
|---|---|
| `.claude/rules/**/*.md` | `.gapgpt/rules/…` |
| `.claude/skills/<name>/` (کل پوشه) | `.gapgpt/skills/<name>/` |
| `.claude/commands/**`، `.claude/agents/**` | `.gapgpt/commands/…`، `.gapgpt/agents/…` |
| `AGENTS.md`، `CLAUDE.md`، `.claude/CLAUDE.md` | به ترتیب `.gapgpt/rules/project-memory.md`، `project-memory-2.md` و `project-memory-3.md` |
| `CLAUDE.local.md` | `.gapgpt/rules/local-memory.md` |
| `.cursor/rules/**`، `.cursorrules`، `.windsurfrules`، `.github/copilot-instructions.md` | `.gapgpt/rules/…` |

- عملیات **کپی** است. فایل‌های اصلی جابه‌جا، ویرایش یا حذف نمی‌شوند تا ابزارهای دیگر (Claude Code، Cursor، Copilot و …) بدون تداخل کار کنند.
- موردی که مقصدش از قبل وجود دارد دیگر پیشنهاد نمی‌شود، پس کپی تکراری ساخته نمی‌شود.
- فقط در محتوای نسخه‌های کپی‌شده، ارجاع به `.claude/` به `.gapgpt/` تغییر می‌کند و ارجاع به `CLAUDE.md` به `AGENTS.md`.
- `.claude/settings*.json` کپی **نمی‌شوند**.
- اگر مقصد از قبل وجود داشته باشد، آن مورد رد می‌شود و منبعش سر جایش می‌ماند.
- symlinkها و مسیرهای بیرون از ریشهٔ پروژه نادیده گرفته می‌شوند. مسیرها هم هرگز از webview گرفته نمی‌شوند و Kotlin خودش دوباره اسکن می‌کند.

---

## فایل‌ها

| فایل | نقش |
|---|---|
| `context/InstructionsImporter.kt` | اسکن و کپی؛ تنها فایلی که نام پوشه‌های ابزارهای دیگر را می‌داند |
| `context/ProjectInstructions.kt` | خواندن قوانین و اسکیل‌ها، ساخت system prompt، اجرای اسکیل |
| `actions/ImportInstructionsAction.kt` | اکشن منوی Tools |
| `ui/ChatPanel.kt` | پیام‌های `scanInstructions` / `importInstructions` و `skills` / `rules` |
| `webview/chat.js` | تب‌های Skills و Rules، پیش‌نمایش و دکمهٔ کپی |

## چک‌لیست اشکال‌یابی

- **تب‌ها خالی است:** آیا `.gapgpt/` در ریشهٔ پروژه (`project.basePath`) وجود دارد؟
- **بعد از کپی، فایل‌ها در Project View دیده نمی‌شوند:** refresh مربوط به VFS ناهمگام است؛ یک بار **File › Reload All from Disk** بزنید.
- **موردی در فهرست نیامد:** مقصدش در `.gapgpt/` از قبل وجود دارد. اگر نسخهٔ تازه را می‌خواهید، فایل مقصد را حذف کنید و دوباره کپی را بزنید.
- **لاگ:** تب Log، با فیلتر `import`.

</div>
