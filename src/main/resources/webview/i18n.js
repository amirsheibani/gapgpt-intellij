/*
 * GapGPT webview i18n (English / Persian).
 *
 * The UI is written in Persian; for English, a MutationObserver translates UI text nodes and
 * title / placeholder attributes in place using the dictionary below. Chat content (user and model
 * messages), code, tool output and inputs are never touched. Switching language is live: original
 * Persian text is remembered on each node and restored for "fa".
 *
 * Matching is whole-word (a key never matches inside a longer Persian word), ZWNJ-insensitive and
 * accepts Arabic ي/ك for Persian ی/ک. Longer keys win. PATTERNS handle sentences with numbers.
 */
(function () {
  "use strict";

  const D = {
    // ---- tabs / views
    "گفتگو": "Chat", "کارها": "Workloads", "کارها (Workloads)": "Workloads", "محافظ": "Guard", "آسیب‌پذیری‌ها": "Vulnerabilities",
    "لاگ": "Log", "نشست": "Session", "اسکیل‌ها": "Skills", "قوانین": "Rules", "تنظیمات": "Settings", "بیشتر": "More",
    "گفتگوی جدید": "New chat", "شروع گفتگوی جدید": "Start a new chat", "جستجو در گفتگو": "Search in chat",
    "جستجو در گفتگو… (Enter: بعدی)": "Search in chat… (Enter: next)", "قبلی": "Previous", "بعدی": "Next", "بستن": "Close",
    "ارسال (Enter)": "Send (Enter)", "توقف (Esc)": "Stop (Esc)", "افزودن فایل، پوشه یا تصویر": "Add files, folder or image",
    "مدل": "Model", "حالت دسترسی": "Permission mode", "حالت دسترسی (Shift+Tab)": "Permission mode (Shift+Tab)",
    "میزان تلاش (effort)": "Effort", "محافظ (Guard)": "Guard", "زبان رابط": "Interface language",
    "از Claude بپرسید، یا / برای دستورها و @ برای فایل‌ها": "Ask anything — / for commands, @ for files",
    "خوش آمدید!": "Welcome!", "نکته‌هایی برای شروع": "Tips to get started",
    "سؤال بپرسید، کد را ویرایش کنید یا دستور اجرا کنید — به فارسی.": "Ask questions, edit code or run commands — in English or Persian.",
    "ساختار این پروژه را توضیح بده": "Explain the structure of this project",
    "باگ‌های احتمالی کد را پیدا کن": "Find possible bugs in the code",
    "برای این پروژه یک README فارسی بنویس": "Write a README for this project",
    "بررسی تغییرات فعلی کد": "Review the current code changes",
    "پاسخ را متوقف می‌کند.": "stops the response.", "حالت دسترسی را عوض می‌کند،": "switches the permission mode,",
    "به فایل‌ها و با": "for files and", "به دستورها دسترسی دارید.": "for commands.",
    "تغییرات پیشنهادی در Diff Viewer نمایش داده می‌شوند تا بپذیرید یا رد کنید.": "Proposed changes open in the Diff Viewer so you can accept or reject them.",

    // ---- status
    "در حال فکر کردن…": "Thinking…", "در حال نوشتن…": "Writing…", "در حال کار…": "Working…", "منتظر تأیید شما…": "Waiting for your approval…",
    "در حال": "Running", "اجرای": "running", "توکن": "tokens", "esc برای توقف": "esc to stop",
    "در حال خواندن…": "Loading…", "در حال اسکن…": "Scanning…", "در حال خواندن مخزن…": "Reading repository…",
    "⎿  متوقف شد.": "⎿  Stopped.", "⎿  گفتگوی جدید شروع شد.": "⎿  New conversation started.",
    "✓ آماده — Claude Code": "✓ Ready — Claude Code", "به IDE متصل شد": "connected to the IDE", "اجرا شد": "started",
    "✓ آماده — GapGPT API": "✓ Ready — GapGPT API", "خطا": "Error", "نامشخص": "unknown", "مرحله": "steps",
    "claude با کد": "claude exited with code", "خارج شد.": ".",
    "یک بار در ترمینال دستور «claude» را اجرا و وارد حساب خود شوید.": "Run `claude` once in a terminal and sign in.",
    "پاسخ قبلی هنوز در حال اجراست.": "The previous response is still running.", "خطای داخلی": "Internal error",
    "✓ کپی شد": "✓ Copied", "کپی": "Copy", "درج": "Insert", "درج در ویرایشگر": "Insert into editor",

    // ---- tools
    "خواندن": "Read", "نوشتن": "Write", "ویرایش": "Edit", "اجرای دستور": "Run command", "جستجوی فایل": "Find files",
    "جستجو در کد": "Search code", "فهرست": "List", "دریافت صفحه": "Fetch page", "جستجوی وب": "Web search",
    "زیرعامل": "Subagent", "فهرست کارها": "Todo list", "ویرایش نوت‌بوک": "Edit notebook",
    "بررسی خطاهای IDE": "IDE diagnostics", "فایل‌های باز IDE": "Open editors", "انتخاب فعلی IDE": "Current selection",
    "باز کردن فایل در IDE": "Open file in IDE", "پوشه‌های پروژه": "Project folders",
    "خط خوانده شد": "lines read", "نتیجه‌ای پیدا نشد": "No results", "نتیجه پیدا شد": "results found",
    "خط در": "lines in", "نوشته شد": "written", "به‌روزرسانی شد": "updated", "خط اضافه": "added", "خط حذف": "removed",
    "انجام شد": "Done", "نویسه": "chars", "بدون خروجی": "No output", "مشاهدهٔ زیرعامل ›": "View subagent ›",
    "خط (برای نمایش کلیک کنید)": "lines (click to show)", "خط": "lines",

    // ---- permission card
    "دستور Bash": "Bash command", "ساخت فایل": "Create file", "ویرایش فایل": "Edit file",
    "آیا اجرا شود؟": "Run this command?", "آیا این فایل ساخته شود؟": "Create this file?", "آیا این تغییرات اعمال شود؟": "Apply these changes?",
    "آیا اجازه داده شود؟": "Allow this?", "بله": "Yes", "بله، و ویرایش‌ها را در این گفتگو دیگر نپرس": "Yes, and don't ask again for edits in this chat",
    "بله، و برای": "Yes, and don't ask again for", "در این گفتگو دیگر نپرس": "in this chat",
    "خیر، و به Claude بگو چه کار متفاوتی بکند (esc)": "No, and tell the model what to do differently (esc)",
    "کلیدهای ۱/۲/۳ یا ↑↓ و Enter · Esc برای رد": "Keys 1/2/3 or ↑↓ and Enter · Esc to reject",
    "مشاهده در Diff Viewer": "Open in Diff Viewer", "پذیرفته شد (با ویرایش‌های شما)": "Accepted (with your edits)",
    "اجازه داده شد": "Allowed", "رد شد": "Rejected", "⛨ هشدار محافظ": "⛨ Guard warning",
    "پذیرش تغییرات": "Accept changes", "رد تغییرات": "Reject changes", "نسخهٔ فعلی": "Current version",
    "پیشنهاد Claude (قابل ویرایش)": "Proposed change (editable)", "(فایل جدید)": "(new file)",

    // ---- model / mode / effort menus
    "پیش‌فرض": "Default", "مدل پیش‌فرض حساب شما": "Your account's default model", "(آخرین نسخه)": "(latest)",
    "قوی‌ترین؛ برای سخت‌ترین و طولانی‌ترین کارها": "Most capable; for the hardest, longest tasks",
    "بهترین برای کارهای روزمره و پیچیده": "Best for everyday and complex work",
    "سریع‌ترین؛ برای پاسخ‌های کوتاه و کارهای ساده": "Fastest; for short answers and simple tasks",
    "آخرین مدل خانوادهٔ Fable": "Latest Fable model", "Opus برای برنامه‌ریزی، Sonnet برای اجرا": "Opus for planning, Sonnet for execution",
    "context یک میلیون توکنی": "1M-token context", "انتخاب نسخهٔ مشخص": "Pick a specific version", "مدل دیگر": "Other model",
    "نسخه": "versions", "شناسهٔ مدل، مثلاً claude-opus-5-5 و Enter": "Model id, e.g. claude-opus-5-5, then Enter",
    "موتور پاسخ‌گویی": "Engine", "همهٔ مدل‌های گپ‌جی‌پی‌تی (GPT، Claude، Gemini، DeepSeek و …) — مستقل از Claude Code":
      "All GapGPT models (GPT, Claude, Gemini, DeepSeek …) — independent of Claude Code",
    "اجرای Claude Code نصب‌شده با حساب و تنظیمات خودش": "Runs your installed Claude Code with its own account and settings",
    "در حال دریافت فهرست مدل‌ها…": "Loading model list…", "تلاش دوباره": "Retry", "جستجوی مدل": "Search models",
    "↻ به‌روزرسانی فهرست مدل‌ها": "↻ Refresh model list", "سایر": "Other",
    "هر بار بپرس": "Ask every time", "برای هر ویرایش و دستور اجازه گرفته می‌شود": "Asks before every edit and command",
    "پذیرش خودکار ویرایش‌ها": "Auto-accept edits", "ویرایش فایل‌ها بدون پرسش؛ دستورها با پرسش": "Edits without asking; commands still ask",
    "حالت برنامه‌ریزی": "Plan mode", "فقط بررسی و برنامه؛ هیچ تغییری اعمال نمی‌شود": "Investigate and plan only; no changes",
    "بدون محدودیت": "Bypass permissions", "همه چیز بدون پرسش (خطرناک — محافظ هم کار نمی‌کند)": "Everything without asking (dangerous)",
    "نپرس": "Don't ask", "هر چه نیاز به اجازه دارد خودکار رد می‌شود": "Anything that needs permission is denied",
    "خودکار": "Auto", "Claude خودش دربارهٔ اجازه‌ها تصمیم می‌گیرد": "The model decides about permissions",

    // ---- more menu / attach
    "دنبال کردن خودکار اسکرول": "Auto-follow scroll", "پاک کردن این گفتگو (/clear)": "Clear this chat (/clear)",
    "آسیب‌پذیری وابستگی‌ها": "Dependency vulnerabilities", "لاگ پلاگین": "Plugin log", "بستن این گفتگو": "Close this chat",
    "خروج از حساب Claude": "Sign out of Claude", "فایل‌ها…": "Files…", "پوشه…": "Folder…", "تصویر…": "Image…",
    "فایل‌های اخیر": "Recent files", "جستجوی فایل‌های اخیر…": "Search recent files…", "فایلی پیدا نشد": "No files found",
    "اول فایلی را در ویرایشگر باز کنید.": "Open a file in the editor first.", "هیچ ویرایشگری باز نیست.": "No editor is open.",
    "همراه پیام فرستاده": "sent with the message", "می‌شود": "", "نمی‌شود": "not", "برای تغییر کلیک کنید.": "Click to toggle.",

    // ---- info bar / session / usage
    "وارد نشده": "Not signed in", "فعال": "on", "غیرفعال": "off", "روشن": "On", "خاموش": "Off",
    "نشست فعلی": "Current session", "هفتگی": "Weekly", "همهٔ مدل‌ها": "all models",
    "محدودیت‌های پلن": "Plan limits", "در حال خواندن با دستور /usage …": "Reading /usage …", "بازنشانی تا": "Resets in",
    "چه چیزی مصرف را بالا می‌برد؟": "What's driving usage?", "↻ به‌روزرسانی مصرف": "↻ Refresh usage",
    "در حال محاسبه با دستور /context …": "Computing /context …", "حساب": "Account", "ایمیل": "Email", "سازمان": "Organization",
    "پلن": "Plan", "ارائه‌دهنده": "Provider", "روش ورود": "Auth method", "انقضای ورود": "Login expires",
    "پوشهٔ کاری": "Working dir", "شناسهٔ نشست": "Session id", "↻ تازه‌سازی": "↻ Refresh", "وضعیت": "Status",
    "از حساب Claude خارج شوید؟ برای استفادهٔ دوباره باید در ترمینال «claude» را اجرا و وارد شوید.":
      "Sign out of Claude? To use it again, run `claude` in a terminal and sign in.",
    "اعتبار": "Credit", "مصرف": "Used", "این مدل": "This model", "مدل انتخابی": "Selected model",
    "Context این گفتگو": "Context of this chat", "(تخمینی)": "(estimated)", "پنجرهٔ Context": "Context window",
    "حداکثر خروجی": "Max output", "اعلام نشده": "not published", "قیمت ورودی": "Input price", "قیمت ورودی کش‌شده": "Cached input price",
    "قیمت خروجی": "Output price", "قیمت‌ها از صفحهٔ قیمت‌گذاری GapGPT": "Prices from the GapGPT pricing page",
    "اندازهٔ context را API اعلام نکرده و تخمینی است": "the API does not report the context size; this is an estimate",
    "اعتبار و محدودیت‌های حساب": "Account credit & limits", "اعتبار مصرف‌شده": "Credit used", "باقی‌مانده": "remaining",
    "مصرف (۱۰۰ روز اخیر)": "Usage (last 100 days)", "سقف اعتبار": "Credit limit", "بدون سقف": "No limit",
    "اطلاعات اعتبار از API دریافت نشد.": "Credit information is not available from the API.",
    "محدودیت نرخ (آخرین درخواست)": "Rate limits (last request)", "↻ به‌روزرسانی": "↻ Refresh",
    "مصرف توکن به تفکیک مدل": "Token usage by model", "از": "from", "پاک کردن آمار": "Reset stats",
    "هنوز با موتور GapGPT پیامی فرستاده نشده است.": "No messages sent with the GapGPT engine yet.",
    "هزینه‌ها تخمین محلی بر اساس جدول قیمت‌اند؛ مبلغ قطعی را پنل GapGPT نشان می‌دهد.":
      "Costs are a local estimate from the price table; the GapGPT dashboard shows the exact amount.",
    "موتور": "Engine", "آدرس": "Endpoint", "توکن‌های این گفتگو": "Tokens in this chat", "خروجی": "output", "هزینهٔ این گفتگو": "Cost of this chat",

    // ---- workloads / subagents
    "هنوز زیرعاملی (Task) در این گفتگو اجرا نشده.": "No subagent (Task) has run in this chat yet.",
    "هنوز پیامی از این زیرعامل نیامده.": "No messages from this subagent yet.",
    "نگه‌داشتن کارهای تمام‌شده به مدت": "Keep finished workloads for", "۵ دقیقه": "5 min", "۱۵ دقیقه": "15 min", "۱ ساعت": "1 hour", "همیشه": "Forever",
    "در این گفتگو چیزی اینجا نیامده.": "Nothing here in this chat yet.",

    // ---- git
    "این پروژه یک مخزن Git نیست (یا git نصب نیست).": "This project is not a Git repository (or git is not installed).",
    "مخزن": "Repository", "شاخه": "Branch", "دنبال‌کردن": "Tracking", "جلوتر (ahead)": "Ahead", "عقب‌تر (behind)": "Behind",
    "نقطهٔ جدا شدن": "Diverged at", "بدون upstream": "no upstream", "فایل‌های تغییرکرده": "Changed files",
    "نسبت به آخرین fetch شما شمرده شده است.": "Counted against your last fetch.", "commitهای اخیر": "Recent commits",
    "عملیات IDE": "IDE actions", "عملیات": "Actions", "شاخه‌ها": "Branches", "از Claude بخواه": "Ask the model",
    "Commit با Claude": "Commit with the model", "بررسی تغییرات (/review)": "Review changes (/review)",
    "تغییرات فعلی را بررسی کن (git status و git diff) و با یک پیام commit دقیق و مناسب commit کن. push نکن.":
      "Review the current changes (git status and git diff) and commit them with a precise, fitting commit message. Do not push.",
    "این عمل در IDE شما در دسترس نیست": "This action is not available in your IDE",
    "شاید پلاگین Git فعال نباشد.": "Maybe the Git plugin is disabled.", "برگرداندن این فایل با Claude": "Revert this file with the model",
    "تغییرات فایل": "Changes of file",

    // ---- guard
    "محافظ فعال": "Guard enabled", "همهٔ قوانین": "All rules", "همهٔ دسته‌ها": "All categories", "مسدود": "Block", "هشدار": "Warn",
    "مسدود شده": "Blocked", "اجازه داده شده": "Allowed", "رد شده": "Denied", "لیست سفید": "Whitelist", "تصمیم‌ها": "Decisions",
    "لاگ محافظ (Guard)": "Guard log", "افزودن به لیست سفید": "Add to whitelist", "حذف": "Remove", "فقط این گفتگو": "This chat only", "همه": "All",
    "لاگ ۵۰۰ مورد آخر کل پروژه را نگه می‌دارد.": "The log keeps the last 500 decisions of the project.",
    "محافظ هر درخواستی را که برای اجازه به IDE می‌آید با قوانین زیر می‌سنجد: قوانین «مسدود» بلافاصله رد می‌شوند و قوانین «هشدار» حتی اگر قبلاً «همیشه اجازه» داده باشید دوباره از شما می‌پرسند. در حالت «بدون محدودیت» درخواستی به IDE نمی‌آید.":
      "Guard checks every permission request that reaches the IDE against the rules below: \"Block\" rules are denied immediately and \"Warn\" rules ask again even if you chose \"always allow\".",
    "حذف بازگشتی ریشه، خانه یا کل پوشه (rm -rf / ~ * ..)": "Recursive delete of root, home or a whole folder (rm -rf / ~ * ..)",
    "فرمت یا بازنویسی دیسک (mkfs, dd به /dev)": "Format or overwrite a disk (mkfs, dd to /dev)",
    "تغییر سطح دسترسی کل سیستم (chmod/chown -R روی /)": "Change permissions of the whole system (chmod/chown -R on /)",
    "اجرای مستقیم اسکریپت از اینترنت (curl/wget | sh)": "Run a script straight from the internet (curl/wget | sh)",
    "push اجباری (git push --force)": "Force push (git push --force)",
    "دور ریختن تغییرات (git reset --hard / clean -fd / checkout .)": "Discard changes (git reset --hard / clean -fd / checkout .)",
    "اجرای دستور با sudo": "Run a command with sudo", "انتشار پکیج (npm publish, cargo publish, ...)": "Publish a package (npm publish, cargo publish, ...)",
    "دسترسی به فایل‌های محرمانه (.env، کلید SSH، credentials)": "Access secret files (.env, SSH keys, credentials)",
    "ویرایش فایل خارج از پوشهٔ پروژه": "Edit a file outside the project folder",

    // ---- vulnerabilities
    "اسکن ناموفق بود": "Scan failed", "اجازه و اسکن همین حالا": "Allow and scan now", "چه چیزی از این دستگاه خارج می‌شود": "What leaves this machine",
    "نمایش فهرست دقیق ارسالی": "Show the exact list sent", "فهرست دقیق": "Exact list", "مورد": "items", "مورد آسیب‌پذیر": "vulnerable",
    "مورد دیگر": "more", "جزء بررسی شد —": "components checked —", "تعداد اجزا": "Components", "فیلتر": "Filter", "نمایش": "Show", "یعنی چه": "what this means",
    "بررسی این پروژه در یک پایگاه دادهٔ آسیب‌پذیری یعنی فرستادن فهرست وابستگی‌هایش به یک سرویس خارجی. این کار هنوز انجام نشده و تا وقتی همین‌جا اجازه ندهید انجام نمی‌شود.":
      "Checking this project against a vulnerability database means sending its dependency list to an external service. This has not happened yet and won't until you allow it here.",
    "نام هر وابستگی که پروژه استفاده می‌کند — مستقیم و غیرمستقیم.": "The name of every dependency the project uses — direct and transitive.",
    "نسخهٔ دقیق هرکدام، همان‌طور که lockfile تعیین کرده.": "The exact version of each, as pinned by the lockfile.",
    "اکوسیستم هرکدام: npm، PyPI، Pub، crates.io، Go یا Packagist.": "The ecosystem of each: npm, PyPI, Pub, crates.io, Go or Packagist.",
    "هیچ چیز دیگر: نه محتوای فایل، نه مسیرهای پروژه، نه نام مخزن، نه حساب، نه هیچ شناسه‌ای.": "Nothing else: no file contents, project paths, repository name, account or identifiers.",
    "درخواست بدون احراز هویت است، اما از آدرس IP شما فرستاده می‌شود.": "The request is unauthenticated, but it is sent from your IP address.",
    "فهرست کامل وابستگی‌ها با نسخه‌های دقیق، تقریباً اثر انگشت یک کد خصوصی است.": "A full dependency list with exact versions is almost a fingerprint of private code.",
    "به هر کسی که درخواست را ببیند نشان می‌دهد کدام نسخه‌های آسیب‌پذیر را دارید.": "It shows anyone who sees the request which vulnerable versions you have.",
    "تا اینجا اجازه ندهید چیزی فرستاده نمی‌شود و هیچ اسکنی خودکار شروع نمی‌شود — نه هنگام باز شدن، نه build، نه زمان‌بندی.":
      "Nothing is sent until you allow it here, and no scan starts automatically — not on open, build or schedule.",
    "lockfileهای پشتیبانی‌شده: package-lock.json، pubspec.lock، requirements.txt (==)، poetry.lock، Cargo.lock، go.sum، composer.lock":
      "Supported lockfiles: package-lock.json, pubspec.lock, requirements.txt (==), poetry.lock, Cargo.lock, go.sum, composer.lock",
    "پروژه پوشه ندارد.": "The project has no folder.", "چیزی پیدا نشد.": "Nothing found.",

    // ---- log
    "ثبت Debug": "Debug logging", "خط · حداکثر ۲۰۰۰ خط نگه داشته می‌شود · debug": "lines · keeps at most 2000 lines · debug",
    "خط‌ها": "lines",

    // ---- skills / rules
    "اسکیل‌ها، دستورها و عامل‌ها": "Skills, commands & agents", "دستورهای سفارشی": "Custom commands", "عامل‌ها (subagents)": "Agents (subagents)",
    "هر چیزی که Claude در این پروژه می‌تواند از آن استفاده کند:": "Everything the model can use in this project:",
    "پروژه،": "project,", "و پلاگین‌های نصب‌شده. «اجرا» دستور را در کادر پیام می‌گذارد.": "and installed plugins. \"Run\" puts the command in the message box.",
    "اجرا": "Run", "فعال در این نشست": "Active in this session", "پلاگین‌های فعال": "Active plugins", "پروژه": "Project", "کاربر": "User",
    "پلاگین": "Plugin", "محلی (شخصی، داخل git نمی‌رود)": "Local (personal, not in git)", "کاربر (همهٔ پروژه‌ها)": "User (all projects)",
    "پوشهٔ والد": "Parent folder", "قانون پروژه (.claude/rules)": "Project rule (.claude/rules)", "قانون کاربر (~/.claude/rules)": "User rule (~/.claude/rules)",
    "حافظهٔ خودکار": "Auto memory", "دستورالعمل‌ها (CLAUDE.md)": "Instructions (CLAUDE.md)", "این پروژه هنوز CLAUDE.md ندارد.": "This project has no CLAUDE.md yet.",
    "Claude این فایل‌ها را در شروع هر نشست می‌خواند و از آن‌ها پیروی می‌کند.": "These files are read at the start of every session and followed.",
    "ساخت با Claude (/init)": "Create with the model (/init)", "ساخت فایل خالی": "Create empty file", "ساخت فایل CLAUDE.md برای این پروژه": "Create a CLAUDE.md for this project",
    "قوانین دسترسی (permissions)": "Permission rules", "ابزارها و دستورهایی که Claude بدون پرسش مجاز (allow)، با پرسش (ask) یا ممنوع (deny) است.":
      "Tools and commands the model may use without asking (allow), after asking (ask) or never (deny).",
    "قوانین deny از همه مهم‌ترند.": "deny rules take precedence.", "مجاز": "Allow", "با پرسش": "Ask", "ممنوع": "Deny",
    "حالت پیش‌فرض": "Default mode", "پوشه‌های اضافه": "Additional folders", "هیچ فایل settings.json پیدا نشد.": "No settings.json found.",
    "قانون دسترسی ندارد.": "has no permission rules.", "Hookها": "Hooks", "هیچ hookی تعریف نشده.": "No hooks defined.",
    "دستورهایی که در رویدادهای Claude (مثل قبل یا بعد از هر ابزار) خودکار اجرا می‌شوند.": "Commands that run automatically on events (e.g. before or after each tool).",
    "خالی است.": "is empty.", "خلاصه‌سازی گفتگو برای آزاد کردن context": "Summarize the chat to free up context",

    // ---- engine / API errors (sent from the IDE)
    "کلید API گپ‌جی‌پی‌تی وارد نشده است. آن را در Settings › Tools › GapGPT وارد کنید.": "No GapGPT API key. Enter it in Settings › Tools › GapGPT.",
    "کلید API گپ‌جی‌پی‌تی وارد نشده است (Settings › Tools › GapGPT)": "No GapGPT API key (Settings › Tools › GapGPT)",
    "کلید API نامعتبر است": "Invalid API key", "اعتبار حساب کافی نیست": "Insufficient account credit",
    "تعداد درخواست‌ها بیش از حد مجاز است": "Too many requests", "از ابزارها (function calling) پشتیبانی نمی‌کند؛ فقط گفتگو انجام می‌شود.":
      "does not support tools (function calling); chat only.",
    "فایل اجرایی claude پیدا نشد. ابتدا Claude Code را نصب کنید": "The claude executable was not found. Install Claude Code first",
    "یا مسیر آن را در Settings › Tools › GapGPT وارد کنید.": "or set its path in Settings › Tools › GapGPT.",
    "اجرای claude ناموفق بود": "Failed to start claude", "claude پیدا نشد": "claude not found",
    "این IDE از JCEF پشتیبانی نمی‌کند.": "This IDE does not support JCEF.",
  };

  /** Sentences that contain numbers or names. */
  const PATTERNS = [
    [/^(\d[\d,]*) خط خوانده شد$/, (m) => "Read " + m[1] + " lines"],
    [/^(\d[\d,]*) نتیجه پیدا شد$/, (m) => m[1] + " results found"],
    [/^(\d[\d,]*) خط در (.+) نوشته شد$/, (m) => "Wrote " + m[1] + " lines to " + m[2]],
    [/^(.+) به‌روزرسانی شد: (\d+) خط اضافه، (\d+) خط حذف$/, (m) => "Updated " + m[1] + ": " + m[2] + " added, " + m[3] + " removed"],
    [/^در حال (.+)…$/, (m) => { const x = tr(m[1]); return x.charAt(0).toUpperCase() + x.slice(1) + "…"; }],
    [/^مدل (.+) از ابزارها \(function calling\) پشتیبانی نمی‌کند؛ فقط گفتگو انجام می‌شود\.$/, (m) => "Model " + m[1] + " does not support tools (function calling); chat only."],
    [/^جستجوی مدل \((\d+) مدل\)…$/, (m) => "Search models (" + m[1] + ")…"],
    [/^اعتبار: (\S+) از (\S+)$/, (m) => "Credit: " + m[1] + " of " + m[2]],
    [/^مصرف: (\S+)$/, (m) => "Used: " + m[1]],
    [/^(\S+) از (\S+) · باقی‌مانده (\S+)$/, (m) => m[1] + " of " + m[2] + " · " + m[3] + " left"],
    [/^(\d+) مرحله$/, (m) => m[1] + " steps"],
  ];

  // ------------------------------------------------------------------ matching
  const PERSIAN = /[؀-ۿ]/;
  const norm = (s) => s.replace(/‌/g, "").replace(/ي/g, "ی").replace(/ك/g, "ک").replace(/\s+/g, " ").trim();
  const NORM = new Map();
  Object.keys(D).forEach((k) => NORM.set(norm(k), D[k]));
  const NPAT = PATTERNS.map(([re, fn]) => [new RegExp(re.source.replace(/‌/g, "‌?")), fn]);

  /** One regex of all keys (longest first), whole words only, ZWNJ- and ی/ي-insensitive. */
  const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const keyRe = (k) => esc(norm(k))
    .replace(/([\u0600-\u06FF])(?=[\u0600-\u06FF])/g, "$1\u200c?") // optional ZWNJ between letters
    .replace(/\u06CC/g, "[\u06CC\u064A]").replace(/\u06A9/g, "[\u06A9\u0643]") // ی/ي and ک/ك
    .replace(/ /g, "[\\s\u200c]+");
  const ALL = new RegExp("(?<![؀-ۿ‌])(?:" +
    Object.keys(D).filter((k) => PERSIAN.test(k)).sort((a, b) => norm(b).length - norm(a).length).map(keyRe).join("|") +
    ")(?![؀-ۿ‌])", "g");

  function tr(text) {
    if (!text || !PERSIAN.test(text)) return text;
    const lead = text.match(/^\s*/)[0];
    const trail = text.match(/\s*$/)[0];
    const core = text.trim();
    const n = norm(core);
    if (NORM.has(n)) return lead + NORM.get(n) + trail;
    for (const [re, fn] of NPAT) {
      const m = core.match(re);
      if (m) return lead + fn(m) + trail;
    }
    return text.replace(ALL, (w) => {
      const v = NORM.get(norm(w));
      return v === undefined ? w : v;
    }).replace(/ {2,}/g, " ");
  }

  // ------------------------------------------------------------------ DOM
  /** Never translated: chat messages, code, tool output, previews and anything the user typed. */
  const SKIP = ".md, .msg.user, pre, code, textarea, input, .tool .result pre, .preview, .diff, .perm pre, .lines, .rows .code, .log, [data-noi18n]";
  const ATTRS = ["title", "placeholder", "aria-label"];
  let lang = "en";
  let applying = false;

  /** UI chrome that lives inside chat content (code block toolbar) is still translated. */
  const ALLOW = ".codeblock .bar";
  function skipped(el) {
    if (!el || !el.closest) return true;
    return !!el.closest(SKIP) && !el.closest(ALLOW);
  }

  function textNode(n) {
    const p = n.parentElement;
    if (!p || skipped(p)) return;
    const cur = n.nodeValue;
    if (n.__i18nOut !== undefined && cur === n.__i18nOut) {
      // Restoring the Persian original must not write when nothing would change: in Blink even an
      // identical assignment queues a characterData record, which brings this function back in
      // through the observer, writes again, and spins the render process at 100% CPU forever —
      // the panel then paints once and looks frozen. The remembered source can also be missing
      // again once the app has rewritten the node itself.
      if (lang === "fa" && n.__i18nSrc !== undefined && n.nodeValue !== n.__i18nSrc) {
        n.nodeValue = n.__i18nSrc;
      }
      return;
    }
    if (n.__i18nSrc !== undefined && cur === n.__i18nSrc && lang === "fa") return;
    // New or changed by the app: remember the Persian original.
    if (!PERSIAN.test(cur)) {
      n.__i18nSrc = undefined;
      return;
    }
    n.__i18nSrc = cur;
    if (lang === "en") {
      n.__i18nOut = tr(cur);
      if (n.__i18nOut !== cur) n.nodeValue = n.__i18nOut;
    }
  }

  function attrs(el) {
    if (skipped(el) && !(el.matches && el.matches("textarea, input"))) return;
    if (el.closest && el.closest(".md, .msg.user, .tool .result pre")) return;
    const src = el.__i18nAttrs || (el.__i18nAttrs = {});
    ATTRS.forEach((a) => {
      const v = el.getAttribute(a);
      if (v === null) return;
      const s = src[a];
      if (s && (v === s.out || v === s.src)) {
        const want = lang === "en" ? s.out : s.src;
        if (v !== want) el.setAttribute(a, want);
        return;
      }
      if (!PERSIAN.test(v)) return;
      src[a] = { src: v, out: tr(v) };
      if (lang === "en" && src[a].out !== v) el.setAttribute(a, src[a].out);
    });
  }

  function walk(root) {
    if (root.nodeType === 3) return textNode(root);
    if (root.nodeType !== 1) return;
    if (root.matches && root.matches(".msg.user, pre, .diff")) return;
    attrs(root);
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
      acceptNode: (n) => (n.nodeType === 1 && n.matches("pre, code, .msg.user, .diff, [data-noi18n]") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
    });
    let n;
    while ((n = w.nextNode())) {
      if (n.nodeType === 3) textNode(n);
      else attrs(n);
    }
  }

  function apply() {
    applying = true;
    try {
      document.documentElement.lang = lang;
      document.documentElement.dir = lang === "fa" ? "rtl" : "ltr";
      walk(document.body);
    } finally {
      applying = false;
    }
  }

  const observer = new MutationObserver((muts) => {
    if (applying) return;
    applying = true;
    try {
      for (const m of muts) {
        if (m.type === "characterData") textNode(m.target);
        else if (m.type === "attributes") attrs(m.target);
        else m.addedNodes.forEach(walk);
      }
    } finally {
      applying = false;
    }
  });

  function start() {
    observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
    apply();
  }
  if (document.body) start();
  else document.addEventListener("DOMContentLoaded", start);

  window.I18N = {
    get lang() {
      return lang;
    },
    setLang(l) {
      lang = l === "fa" ? "fa" : "en";
      apply();
    },
    /** Translates a UI string for the current language (Persian source). */
    t(s) {
      return lang === "en" ? tr(s) : s;
    },
  };
})();
