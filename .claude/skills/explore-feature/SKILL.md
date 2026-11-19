---
name: explore-feature
description: Explore one feature of this IntelliJ plugin read-only and explain its architecture and flow (webview → ChatPanel bridge → backend/IDE services → back to the webview), without editing any files.
argument-hint: <feature> (e.g. permissions, diff preview, skills tab, settings)
disable-model-invocation: true
---

# Explore only (no edits)

Feature: `$ARGUMENTS`

Start with the knowledge graph: `graphify query "<feature>"`, then `graphify path` / `graphify explain` for the key symbols. Read raw files only for the lines the graph located. Then use the IntelliJ index tools (`mcp__idea__search_symbol`, `mcp__idea__analyze_calls`) for call chains.

Scope your reading to the packages involved in this feature:

- `src/main/kotlin/ir/gapgpt/api/`: backend (GapGPT HTTP client)
- `src/main/kotlin/ir/gapgpt/agent/`: agent tools (files, commands, code index)
- `src/main/kotlin/ir/gapgpt/chat/`, `context/`, `mcp/`: conversation, prompts, sessions, project rules/skills, MCP servers
- `src/main/kotlin/ir/gapgpt/ide/`: IDE integration (selection, file search, git, graphify)
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
