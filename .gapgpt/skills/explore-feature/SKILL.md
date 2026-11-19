---
name: explore-feature
description: Explore one feature of this IntelliJ plugin read-only and explain its architecture and flow (webview → ChatPanel bridge → backend/IDE services → back to the webview), without editing any files.
argument-hint: <feature> (e.g. permissions, diff preview, skills tab, settings)
---

# Explore only (no edits)

Feature: `$ARGUMENTS`

Scope your reading to the packages involved in this feature:

- `src/main/kotlin/ir/gapgpt/api/` + `chat/`: backend (GapGPT HTTP client, conversation history)
- `src/main/kotlin/ir/gapgpt/context/`: project rules & skills (`.gapgpt/`) and the importer
- `src/main/kotlin/ir/gapgpt/ide/`: IDE integration (selection tracking, project file search)
- `src/main/kotlin/ir/gapgpt/ui/ChatPanel.kt`: JS ↔ Kotlin bridge (`handleFromJs`, `post`)
- `src/main/resources/webview/chat.js`: UI rendering and the messages it sends
- `src/main/kotlin/ir/gapgpt/settings/`, `actions/`, `src/main/resources/META-INF/plugin.xml`
- related `docs/` if any

Then:

1. Explain the current architecture of the feature.
2. Explain the flow end to end (user action in webview → message type → Kotlin handler → backend / IDE API → event posted back → `chat.js` handler), citing `path:line`. Note which steps run on the EDT and which on a pooled thread.
3. **Do not change any files.**
4. At the end, only suggest which files would need to change for the next step.

Reply in Persian.
