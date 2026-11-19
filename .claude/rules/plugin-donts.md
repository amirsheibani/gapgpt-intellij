# Plugin don'ts (IntelliJ plugin, Kotlin + JCEF webview)

Unless the user **explicitly** asks otherwise:

- Do **not** do I/O, process work, network calls, or file walks on the EDT. Use `ApplicationManager.getApplication().executeOnPooledThread { … }`. Touch Swing / editors / PSI write actions only on the EDT (`invokeLater`, `WriteCommandAction`).
- Do **not** leak resources. Anything with a lifecycle (processes, HTTP requests, JCEF browsers, listeners, message-bus connections) must be tied to a `Disposable` (`Disposer.register`). Kill processes with their whole tree (`toHandle().descendants()`).
- Do **not** hardcode user-facing strings. Use `I18n.tr("English", "فارسی")` in Kotlin, and the existing i18n table in `chat.js` for the webview.
- Do **not** use `println` / `System.out`. Use `PluginLog` (shown in the in-plugin log view) or `logger<T>()`.
- Do **not** store secrets (API keys, tokens) in code, settings XML, or logs. Use `PasswordSafe`.
- Do **not** trust messages coming from the webview. Validate the `type` and fields in `ChatPanel.handleFromJs` before acting (opening files, running actions, writing to disk).
- Do **not** inject unescaped text into the webview. Send data as JSON through `post(kind, payload)`. In `chat.js`, render with `esc()` / `markdown()`, never with raw `innerHTML` of user or model text.
- Do **not** break RTL / bidi. Code, paths, and commands stay LTR; paragraphs keep `unicode-bidi: plaintext`.
- Do **not** add heavy third-party dependencies. The IntelliJ platform already ships Gson, the Kotlin stdlib, and `java.net.http`.
- Do **not** hand-edit `build/`, `.intellijPlatform/`, or `graphify-out/` (rebuild the graph with `graphify update .`).
- Do **not** sweep the whole repo. Scope reading to the touched package plus the relevant `docs/`.
- Do **not** change files when the user asked for explore / plan / no edits.
- Do **not** invent a new folder layout. Follow the existing packages: `api/`, `agent/`, `chat/`, `context/`, `mcp/`, `ide/`, `ui/`, `settings/`, `actions/`, `src/main/resources/webview/`.

## Build

Gradle fails on the default Java 25. Build with JDK 21:

```bash
JAVA_HOME="/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin
```

Add `--offline` if the dependencies are already cached.

## Preferred instead

| Avoid | Use |
|-------|-----|
| Blocking call on the EDT | `executeOnPooledThread` + `invokeLater` back to the EDT |
| New HTTP / JSON library | `java.net.http.HttpClient` + Gson |
| Custom settings file | `PersistentStateComponent` (`*Settings`) + `*Configurable` |
| Secret in settings | `PasswordSafe` + `CredentialAttributes` |
| Hardcoded UI text | `I18n.tr(en, fa)` |
| `println` | `PluginLog.info/warn/debug(tag, msg)` |
