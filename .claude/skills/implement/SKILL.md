---
name: implement
description: Implement an approved plan (after /explore-feature or /scaffold-feature) following this plugin's conventions, touching only the agreed files.
argument-hint: [extra notes]
disable-model-invocation: true
---

# Implement after confirm

The plan from the previous step is approved. Implement it now. Extra notes: $ARGUMENTS

- Change only the files that were listed and agreed on.
- Before any edit, show the final list of files you will change.
- Follow the closest existing feature and the project rules (`plugin-donts.md`):
  - nothing blocking on the EDT; pooled thread + `invokeLater` back
  - everything with a lifecycle tied to a `Disposable`
  - `I18n.tr(en, fa)` / the `chat.js` i18n table for UI text
  - `PluginLog` for logging, `PasswordSafe` for secrets
  - validate webview messages, escape everything rendered in the webview, keep RTL intact
- Do not hand-edit `build/`, `.intellijPlatform/`, or `graphify-out/`.
- After the changes:
  - run `JAVA_HOME="/Applications/IntelliJ IDEA CE.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin` and fix what you broke
  - run `graphify update .`
  - if the UI changed, suggest checking it in `./gradlew runIde`

Reply in Persian.
