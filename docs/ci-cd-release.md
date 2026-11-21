<div dir="rtl" lang="fa">

# CI/CD و انتشار نسخه (GitHub Actions)

بیلد خودکار پلاگین روی هر push و PR، و ساخت GitHub Release همراه فایل zip پلاگین با push کردن یک tag.

مرتبط: [فهرست مستندات](README.md)

---

## نمای کلی

<div dir="ltr">

```
push main / pull_request
        │
        ▼
build.yml ──► setup JDK 21 ──► ./gradlew buildPlugin
        │
        ▼
artifact "plugin-zip"  (build/distributions/*.zip)

git push origin vX.Y.Z
        │
        ▼
release.yml ──► tag == pluginVersion ? ──no──► fail
        │ yes
        ▼
./gradlew buildPlugin ──► gh release create vX.Y.Z *.zip --generate-notes
```

</div>

---

## CI: بیلد روی هر تغییر

فایل `.github/workflows/build.yml` روی push به `main` و روی هر PR اجرا می‌شود:

1. checkout کد
2. نصب JDK 21 (temurin)؛ مشکل Java 25 روی runner پیش نمی‌آید.
3. `gradle/actions/setup-gradle` برای cache وابستگی‌ها
4. `./gradlew buildPlugin`
5. آپلود فایل zip با نام artifact `plugin-zip`. این فایل از صفحهٔ run در تب Actions قابل دانلود است.

اگر چند push پشت سر هم روی یک branch انجام شود، run قبلی لغو می‌شود (`concurrency`).

---

## CD: انتشار نسخهٔ جدید

1. مقدار `pluginVersion` را در `gradle.properties` بالا ببرید و commit کنید.
2. tag هم‌نام با نسخه بسازید و push کنید:

<div dir="ltr">

```bash
git tag v0.3.0
git push origin v0.3.0
```

</div>

3. فایل `.github/workflows/release.yml` اجرا می‌شود:
   - بررسی می‌کند که tag (بدون `v`) با `pluginVersion` یکی باشد؛ در غیر این صورت متوقف می‌شود.
   - پلاگین را بیلد می‌کند.
   - با `gh release create` یک Release با یادداشت‌های خودکار از commitها می‌سازد و فایل `gapgpt-intellij-<version>.zip` را ضمیمه می‌کند.

نصب از Release: فایل zip را دانلود کنید، سپس در IntelliJ از مسیر **Settings → Plugins → ⚙ → Install Plugin from Disk…** نصب کنید.

secret اضافه‌ای لازم نیست؛ فقط `GITHUB_TOKEN` پیش‌فرض با `permissions: contents: write` استفاده می‌شود.

---

## فایل‌ها

| فایل | نقش |
|------|-----|
| `.github/workflows/build.yml` | CI: بیلد روی push/PR و آپلود artifact |
| `.github/workflows/release.yml` | CD: بررسی نسخه، بیلد و ساخت GitHub Release با zip |
| `gradle.properties` | منبع نسخه (`pluginVersion`) |
| `build.gradle.kts` | تنظیمات پلاگین IntelliJ Platform و `jvmToolchain(21)` |

---

## چک‌لیست debug

- **Release با خطای «does not match pluginVersion» متوقف شد:** tag و `pluginVersion` یکی نیستند. tag را پاک کنید (`git push --delete origin vX.Y.Z` و `git tag -d vX.Y.Z`)، نسخه را درست کنید و دوباره tag بزنید.
- **خطای «release already exists»:** برای همین tag قبلاً Release ساخته شده؛ آن را از GitHub پاک کنید یا نسخهٔ جدید بزنید.
- **خطای 403 در ساخت Release:** در Settings → Actions → General بخش Workflow permissions را بررسی کنید؛ `permissions: contents: write` در خود workflow باید کافی باشد مگر اینکه سازمان آن را محدود کرده باشد.
- **بیلد روی runner fail شد ولی لوکال سالم است:** لوکال را بدون `--offline` و با JDK 21 اجرا کنید تا مشکل دانلود وابستگی‌ها یا cache مشخص شود.

---

## فایل‌های مرتبط

- `gradle.properties`
- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`

</div>
