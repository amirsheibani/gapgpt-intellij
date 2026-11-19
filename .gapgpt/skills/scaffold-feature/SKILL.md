---
name: scaffold-feature
description: Design a new feature for this IntelliJ plugin across backend, IDE integration, ChatPanel bridge, webview, and plugin.xml. Lists the files to create/change layer by layer, without writing code yet.
argument-hint: <feature_name>
---

# New feature scaffold

New feature: `$ARGUMENTS`

Read first:
- `README.md` (project structure)
- `src/main/kotlin/ir/gapgpt/ui/ChatPanel.kt`: how `handleFromJs` dispatches message types and how `post(kind, payload)` sends events
- `src/main/resources/webview/chat.js`: `toIde(...)` calls, `receive()` dispatch, the i18n table
- `src/main/kotlin/ir/gapgpt/settings/*Settings.kt` + `*Configurable.kt`
- the closest existing feature (e.g. the skills/rules tabs or the rules & skills importer)

Then design the feature:
- List the files to create/change, **layer by layer**, using the checklist below.
- **Do not write code yet.**

After the user approves the structure, implement it layer by layer (use `/implement`).

## New feature checklist

### 1. Backend / state
- [ ] Logic in `api/` / `chat/` / `context/` / `ide/` as a plain class or `object`; heavy work on a pooled thread
- [ ] New settings fields in `*Settings.State` (+ UI in `*Configurable`); secrets in `PasswordSafe`, never in `State`

### 2. IDE integration
- [ ] IDE APIs (editors, VFS, PSI, notifications) wrapped in `ide/`; EDT for UI/write actions, `ReadAction` for PSI reads
- [ ] New `AnAction` in `actions/` if it has a menu entry / shortcut

### 3. Bridge (`ui/ChatPanel.kt`)
- [ ] New message type in `handleFromJs` with validated fields
- [ ] New event `post("<kind>", json)` for results
- [ ] Anything with a lifecycle registered on the panel's `Disposable`

### 4. Webview
- [ ] `chat.js`: `toIde({type: …})` sender, a `receive()` case for the event, rendering via `esc()` / `markdown()`
- [ ] `chat.css` styles that keep RTL/bidi working
- [ ] i18n strings for both `en` and `fa`

### 5. Registration
- [ ] `plugin.xml`: actions, services, configurables, notification groups
- [ ] `I18n.tr(en, fa)` for every Kotlin UI string

### Build order

backend/state → project instructions → IDE integration → bridge → webview → plugin.xml → `./gradlew buildPlugin` (JDK 21) → `./gradlew runIde` for a manual check.

Reply in Persian.
