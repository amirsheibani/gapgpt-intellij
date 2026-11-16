# Graph Report - gapgpt-intellij  (2026-11-16)

## Corpus Check
- 26 files · ~24,322 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 9 file(s) not represented in the graph (top: .properties 2, .xml 2, (none) 1)

## Summary
- 528 nodes · 1166 edges · 27 communities (7 shown, 20 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 33 edges (avg confidence: 0.85)
- Token cost: 45,394 input · 0 output

## Graph Freshness
- Built from commit: `a17eb965`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- ChatPanel
- Webview Chat Renderer (JS)
- Plugin Concepts & Docs
- ChatPanel.kt
- SelectionActions.kt
- DependencyScanner.kt
- Settings UI Configurable
- IdeMcpServer.kt
- DiffPreview.kt
- SelectionTracker.kt
- PermissionBroker
- IdeTools.kt
- CLAUDE.md

## God Nodes (most connected - your core abstractions)
1. `ChatPanel` - 40 edges
2. `receive()` - 28 edges
3. `chat()` - 24 edges
4. `esc()` - 23 edges
5. `renderInfo()` - 19 edges
6. `toIde()` - 18 edges
7. `IdeMcpServer` - 18 edges
8. `PluginLog` - 17 edges
9. `PermissionBroker` - 16 edges
10. `SelectionTracker` - 15 edges

## Surprising Connections (you probably didn't know these)
- `chat.html Webview Template` --implements--> `RTL / Bidi Rendering (unicode-bidi: plaintext)`  [INFERRED]
  src/main/resources/webview/chat.html → README.md
- `ChatPanel.kt (JCEF browser + JS-Kotlin bridge)` --references--> `chat.html Webview Template`  [INFERRED]
  README.md → src/main/resources/webview/chat.html
- `Thinking Status Indicator` --conceptually_related_to--> `Live Streaming Response and Tool Use Display`  [INFERRED]
  src/main/resources/webview/chat.html → README.md
- `Topbar (status dot, New Chat, Settings buttons)` --implements--> `Conversation Resume (--resume) and New Chat`  [INFERRED]
  src/main/resources/webview/chat.html → README.md
- `Messages Area with Empty State and Suggestions` --references--> `Editor Context Menu Actions (Send Selection / Explain / Review)`  [EXTRACTED]
  src/main/resources/webview/chat.html → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Chat webview UI regions** — src_main_resources_webview_chat_topbar, src_main_resources_webview_chat_messages, src_main_resources_webview_chat_status, src_main_resources_webview_chat_composer [EXTRACTED 1.00]
- **CLI runner -> JCEF ChatPanel -> chat.html webview pipeline** — src_main_kotlin_ir_gapgpt_cli_claudeclirunner, readme_chatpanel, src_main_kotlin_ir_gapgpt_ui_chattoolwindowfactory, src_main_resources_webview_chat_chat_html [INFERRED 0.85]
- **Persian text rendering support** — readme_rtl_bidi_rendering, readme_vazirmatn_font, readme_jcef_html_panel, src_main_resources_webview_vazirmatn_ofl_sil_open_font_license [INFERRED 0.85]

## Communities (27 total, 20 thin omitted)

### Community 0 - "ChatPanel"
Cohesion: 0.05
Nodes (11): CliInfo, Action, BLOCK, WARN, Decision, Guard, Rule, Line (+3 more)

### Community 1 - "Webview Chat Renderer (JS)"
Cohesion: 0.06
Nodes (113): acceptMention(), acceptSlash(), activate(), addTool(), addUser(), agentFor(), alertNotice(), attachMenu() (+105 more)

### Community 2 - "Plugin Concepts & Docs"
Cohesion: 0.07
Nodes (24): ChatPanel.kt (JCEF browser + JS-Kotlin bridge), Claude Code CLI (@anthropic-ai/claude-code), Claude Farsi IntelliJ Plugin, Code Block Copy / Insert-into-Editor Buttons, Editor Context Menu Actions (Send Selection / Explain / Review), Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems), Gradle Build (buildPlugin / runIde), Headless Claude CLI (claude -p --output-format stream-json) (+16 more)

### Community 4 - "ChatPanel.kt"
Cohesion: 0.05
Nodes (6): ClaudeConfig, GitInfo, GapGptSettings, State, Command, SlashCommands

### Community 5 - "SelectionActions.kt"
Cohesion: 0.11
Nodes (7): ExplainSelectionAction, OpenChatAction, ReviewSelectionAction, SelectionToClaudeAction, SendSelectionAction, ChatPanelHolder, ChatToolWindowFactory

### Community 13 - "SelectionTracker.kt"
Cohesion: 0.13
Nodes (5): SelectionTracker, CaretListener, FileEditorManagerListener, SelectionListener, Snapshot

### Community 14 - "PermissionBroker"
Cohesion: 0.13
Nodes (6): Allow, Decision, Deny, Pending, PermissionBroker, Ui

## Knowledge Gaps
- **8 isolated node(s):** `BLOCK`, `WARN`, `Composer (RTL textarea, Send/Stop buttons)`, `Code Block Copy / Insert-into-Editor Buttons`, `Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems)` (+3 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 114 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **20 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ChatPanel` connect `ChatPanel` to `ChatPanel.kt`, `SelectionActions.kt`, `DependencyScanner.kt`, `SelectionTracker.kt`, `PermissionBroker`?**
  _High betweenness centrality (0.093) - this node is a cross-community bridge._
- **What connects `BLOCK`, `WARN`, `Composer (RTL textarea, Send/Stop buttons)` to the rest of the system?**
  _8 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ChatPanel` be split into smaller, more focused modules?**
  _Cohesion score 0.051615051615051616 - nodes in this community are weakly interconnected._
- **Why does `PluginLog` connect `ChatPanel` to `Plugin Concepts & Docs`, `ChatPanel.kt`, `DependencyScanner.kt`, `IdeMcpServer.kt`, `PermissionBroker`?**
  _High betweenness centrality (0.036) - this node is a cross-community bridge._
- **Should `Webview Chat Renderer (JS)` be split into smaller, more focused modules?**
  _Cohesion score 0.05690312738367658 - nodes in this community are weakly interconnected._
- **Why does `IdeMcpServer` connect `IdeMcpServer.kt` to `Plugin Concepts & Docs`, `IdeTools.kt`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Should `Plugin Concepts & Docs` be split into smaller, more focused modules?**
  _Cohesion score 0.06504065040650407 - nodes in this community are weakly interconnected._