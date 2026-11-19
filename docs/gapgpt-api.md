<div dir="rtl" lang="fa">

# ارتباط با API گپ‌جی‌پی‌تی

این سند توضیح می‌دهد که پیام از پنل گفتگو چطور به `api.gapgpt.app` می‌رسد و پاسخ چطور برمی‌گردد.

مرتبط: [راهنمای شروع](getting-started.md) · [قوانین، اسکیل‌ها و دکمهٔ کپی](instructions-import.md) · [README](../README.md)

---

## نمای کلی

<div dir="ltr">

```
chat.js  ──send {chat,text}──►  ChatPanel.sendMessage
                                   │ skill expand / @file refs / IDE context / ي→ی
                                   │ Conversation.snapshot(systemPrompt)
                                   ▼
                              GapGptClient.send()   (pooled thread)
                                   │ POST {baseUrl}/chat/completions
                                   │ Authorization: Bearer <PasswordSafe>
                                   ▼
                       SSE (data: …) or JSON reply
                                   │
chat.js  ◄── delta / done / error / busy ──┘
```

</div>

---

## ۱. درخواست

<div dir="ltr">

```json
{"model": "claude-sonnet-5-5", "messages": [...], "max_completion_tokens": 8000, "stream": true}
```

</div>

- نشانی پایه پیش‌فرض `https://api.gapgpt.app/v1` است و نشانی جایگزین CDN هم `https://api.gapapi.com/v1` است. اگر فقط نام میزبان را وارد کنید، `/v1` خودکار به انتهایش اضافه می‌شود.
- اگر تنظیم Streaming خاموش باشد، `stream` فرستاده نمی‌شود.
- کلید API فقط از `ApiKeyStore`، یعنی همان PasswordSafe خود IDE، خوانده می‌شود و هیچ‌وقت لاگ نمی‌شود.
- redirect غیرفعال است تا هدر کلید به میزبان دیگری نرود.
- پراکسی را از تنظیمات `HTTPS proxy` می‌گیرد.

## ۲. پاسخ

- **`text/event-stream`:** خط‌های `data:` خوانده می‌شوند و `choices[0].delta.content` به‌صورت `delta` به UI فرستاده می‌شود. با رسیدن `[DONE]`، رویداد `done` ارسال می‌شود.
- **JSON:** `choices[0].message.content` یک‌جا نمایش داده می‌شود.
- **`usage`:** اگر سرور `usage` بفرستد، مقدارهای `prompt_tokens` / `completion_tokens` / `total_tokens` نمایش داده می‌شوند. در حالت استریم ممکن است سرور `usage` نفرستد. هزینهٔ تخمینی هر پاسخ در بخش «هزینه و اعتبار حساب» توضیح داده شده است.

## ۳. خطاها

| وضعیت | پیام |
|---|---|
| بدون کلید | «کلید API تنظیم نشده است» |
| 401 | کلید نامعتبر |
| 402 | اعتبار حساب گپ‌جی‌پی‌تی کافی نیست؛ حساب را در gapgpt.app شارژ کنید |
| 403 (JSON) | دسترسی به مدل یا کلید مجاز نیست |
| 403 (صفحهٔ HTML) | فایروال یا پراکسی جلوی درخواست را گرفته |
| 404 | نشانی یا نام مدل اشتباه |
| 429 | سهمیه یا محدودیت تعداد درخواست |
| 5xx | خطای سرور |
| شبکه | بررسی شبکه یا پراکسی |

بعد از خطا، آخرین پیام کاربر از تاریخچه حذف می‌شود تا ارسال دوباره تکراری نشود. دکمهٔ توقف و همچنین بسته شدن پنل (`dispose`) درخواست در جریان را لغو می‌کنند.

## ۴. فهرست مدل‌ها

`GapGptClient.listModels()` درخواست `GET {baseUrl}/models` را با همان هدر `Authorization: Bearer <key>` می‌فرستد. پاسخ OpenAI-compatible است (`{"object":"list","data":[{"id":"claude-sonnet-5-5",...}]}`) و همهٔ مدل‌های گپ‌جی‌پی‌تی را برمی‌گرداند (GPT، Claude، Gemini، DeepSeek، Grok، Qwen و …).

- مدل‌های غیرچت (embedding، `tts`، `whisper` و تولید تصویر و ویدیو) حذف و بقیه بر اساس نام مرتب می‌شوند.
- نتیجه در `GapGptSettings.knownModels` ذخیره می‌شود.
- **Settings › Tools › GapGPT:** فیلد Model یک ComboBox قابل ویرایش است. دکمهٔ «دریافت مدل‌ها» از کلید و نشانی پایه‌ای استفاده می‌کند که در همان صفحه نوشته شده، حتی اگر هنوز Apply نشده باشد.
- **پنل چت:** منوی مدل فهرست را با امکان جستجو نشان می‌دهد. اولین بار که باز شود، فهرست را خودکار دریافت می‌کند و گزینهٔ «به‌روزرسانی فهرست مدل‌ها» هم دارد. نام دلخواه هم با Enter پذیرفته می‌شود.
- خطاها همان پیام‌های جدول بالا هستند، از جمله پیام اعتبار ناکافی برای HTTP 402.

## ۵. هزینه و اعتبار حساب

- **هزینهٔ هر پاسخ:** خط مصرف زیر هر پاسخ، علاوه بر توکن‌ها، هزینهٔ تخمینی را به دلار (USD) نشان می‌دهد. قیمت‌ها از فایل همراه پلاگین `src/main/resources/gapgpt/pricing.json` می‌آیند که نسخه‌ای از صفحهٔ قیمت‌های gapgpt.app/platform-v2/pricing است. برای مدلی که در این فهرست نباشد، هزینه نمایش داده نمی‌شود.
- **مصرف هر مدل:** `api/GapGptUsage.kt` مصرف هر مدل را روی همین دستگاه نگه می‌دارد و با بستن IDE از بین نمی‌رود (فایل `gapgpt-usage.xml`).
- **دستور `/usage`:** اعتبار حساب، قیمت مدل فعلی و جدول مصرف هر مدل را نشان می‌دهد. اعتبار از endpointهای سبک OpenAI به نام `/dashboard/billing/subscription` و `/dashboard/billing/usage` خوانده می‌شود. اگر سرور این endpointها را نداشته باشد، بخش اعتبار نمایش داده نمی‌شود.
- **دستور `/usage reset`:** شمارنده‌های محلی را پاک می‌کند. این کار روی حساب واقعی شما اثری ندارد.
- هزینه فقط یک **تخمین** است؛ مبلغ نهایی همان است که در پنل gapgpt.app می‌بینید.

---

## فایل‌ها

| فایل | نقش |
|---|---|
| `api/GapGptClient.kt` | HTTP، خواندن SSE و JSON، لغو درخواست، متن خطاها، فهرست مدل‌ها، اعتبار حساب |
| `api/GapGptPricing.kt` | خواندن قیمت‌ها از `gapgpt/pricing.json` و محاسبهٔ هزینهٔ تخمینی |
| `api/GapGptUsage.kt` | مصرف محلی هر مدل (`gapgpt-usage.xml`) |
| `chat/Conversation.kt` | تاریخچهٔ هر چت (حداکثر ۲۰۰ پیام و ۶۰۰ هزار نویسه؛ بعد از آن خلاصه‌سازی) |
| `chat/Prompts.kt` | پرامپت ایجنت، حالت طرح، graphify، پرامپت زبان، تبدیل «ي/ك» |
| `settings/ApiKeyStore.kt` | ذخیرهٔ کلید در PasswordSafe |
| `settings/GapGptSettings.kt` و `GapGptConfigurable.kt` | نشانی پایه، مدل و فهرست مدل‌ها (`knownModels`)، توکن، استریم، پراکسی |
| `ui/ChatPanel.kt` | پل JS ↔ Kotlin |

## چک‌لیست اشکال‌یابی

- **HTTP 401:** کلید را در Settings › Tools › GapGPT دوباره وارد کنید.
- **HTTP 402:** اعتبار حساب کافی نیست؛ حساب را در gapgpt.app شارژ کنید. ساخت کلید: [راهنمای شروع](getting-started.md).
- **هزینه نمایش داده نمی‌شود:** مدل در `pricing.json` نیست. اعتبار در `/usage` نمایش داده نمی‌شود: سرور endpointهای billing را ندارد.
- **403 با صفحهٔ HTML:** فایروال یا پراکسی را بررسی کنید؛ در صورت نیاز از نشانی CDN (`https://api.gapapi.com/v1`) استفاده کنید.
- **پاسخ یک‌جا می‌آید، نه زنده:** سرور `stream` را نادیده گرفته است؛ این حالت مشکلی ایجاد نمی‌کند.
- **HTTP 400 به خاطر پارامترها:** Streaming را خاموش کنید یا `max_completion_tokens` را کم کنید.
- **لاگ:** تب Log، با فیلتر `api`.

</div>
