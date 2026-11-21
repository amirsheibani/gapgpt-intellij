<div dir="rtl" lang="fa">

# دکمه‌های پیام کاربر و تب‌های گفتگو

روی هر پیام کاربر دو دکمهٔ کپی و اجرای مجدد ظاهر می‌شود، و تب‌های گفتگو در یک ردیف قابل اسکرول نمایش داده می‌شوند و هنگام انتظار برای تأیید چشمک می‌زنند.

مرتبط: [فهرست مستندات](README.md) · [ایجنت چت](chat-agent.md)

---

## نمای کلی

<div dir="ltr">

```
send(text) ──► addUser(T, text)
                 ├─ d._text = text          (raw text, kept off the DOM)
                 └─ .uacts  [⧉ copy] [↻ rerun]

click [data-uact]
   ├─ copy  ──► toIde({ type: "copy", text }) ──► ChatPanel.handleFromJs "copy"
   │                                              └─ CopyPasteManager.setContents
   └─ rerun ──► send(d._text)   (ignored while c.busy)

onToolApproval ─► .tool .approval added   ─► renderTabs() ─► pill.waiting (blink)
onToolEnd      ─► .tool .approval removed ─► renderTabs() ─► pill back to normal
```

</div>

---

## کپی و اجرای مجدد

- دکمه‌ها فقط با بردن موس روی پیام دیده می‌شوند (`opacity`) و در انتهای ردیف می‌نشینند؛ در RTL خودکار جابه‌جا می‌شوند.
- **کپی**: متن خام پیام کپی می‌شود و دکمه یک ثانیه `✓` نشان می‌دهد.
- **اجرای مجدد**: همان متن در گفتگوی فعال دوباره ارسال می‌شود.
- وقتی گفتگو مشغول است، `syncBusyUi()` کلاس `busy` را روی `#chatLogs` می‌گذارد و دکمهٔ اجرای مجدد غیرفعال می‌شود؛ `send()` هم در حالت مشغول کاری نمی‌کند.

## استایل تب‌های گفتگو

- همهٔ تب‌ها در یک ردیف (`flex-wrap: nowrap`) با اسکرول افقی نازک (۴ پیکسل) هستند.
- هر تب فقط خط پایین دارد؛ تب فعال خط و پس‌زمینهٔ رنگ تأکید (`--accent`) دارد.
- بعد از هر رندر، تب فعال با `scrollIntoView` در دید قرار می‌گیرد.
- ترتیب تب‌ها تغییر نمی‌کند و دکمهٔ بستن (×) مثل قبل کار می‌کند.

## حالت انتظار (waiting)

گفتگویی که در لاگ آن کادر تأیید باز (`.tool .approval`) وجود دارد، کلاس `waiting` می‌گیرد: نقطهٔ آن زرد (`.dot.warn`) و تب با انیمیشن `tabblink` چشمک می‌زند. کادر تأیید در `onToolApproval` اضافه و در `onToolEnd` حذف می‌شود و در هر دو جا `renderTabs()` صدا زده می‌شود. در `closeTools` هم کادرها حذف می‌شوند و `setBusy` تب‌ها را دوباره رندر می‌کند.

## امنیت

متن پیام فقط با `textContent` رندر و در property جاوااسکریپت (`_text`) نگه‌داری می‌شود، نه در attribute یا `innerHTML`. پیام `copy` از قبل در `ChatPanel.kt` وجود داشت و Kotlin تغییری نکرد.

---

## فایل‌ها

| فایل | نقش |
|------|-----|
| `src/main/resources/webview/chat.js` | `.uacts` در `addUser()`، هندلر `[data-uact]`، کلاس `busy` در `syncBusyUi()`، حالت `waiting` و scroll در `renderTabs()` |
| `src/main/resources/webview/chat.css` | استایل `.uacts`، تب‌های `#chatTabs`، `.dot.warn` و `tabblink` |
| `src/main/kotlin/ir/gapgpt/ui/ChatPanel.kt` | (بدون تغییر) پیام `copy` |

## چک‌لیست دیباگ

- دکمه دیده نمی‌شود؟ موس را روی پیام ببرید.
- اجرای مجدد کار نمی‌کند؟ بررسی کنید `c.busy` نباشد.
- تب چشمک نمی‌زند؟ وجود `.tool .approval` در لاگ و صدا زده شدن `renderTabs()` را بررسی کنید.

</div>
