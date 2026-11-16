# Graph Report - gapgpt-intellij  (2026-11-16)

## Corpus Check
- 29 files · ~28,342 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 9 file(s) not represented in the graph (top: .properties 2, .xml 2, (none) 1)

## Summary
- 582 nodes · 1333 edges · 33 communities (9 shown, 24 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 46 edges (avg confidence: 0.85)
- Token cost: 45,394 input · 0 output

## Graph Freshness
- Built from commit: `f6fe21d4`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Webview Chat Renderer (JS)
- Plugin Concepts & Docs
- .run
- SelectionActions.kt
- DependencyScanner.kt
- Settings UI Configurable
- IdeMcpServer.kt
- DiffPreview.kt
- SelectionTracker.kt
- .decide
- IdeTools.kt
- CLAUDE.md
- .add
- ChatPanel
- PluginLog
- .post
- GapGptSettings

## God Nodes (most connected - your core abstractions)
1. `ChatPanel` - 43 edges
2. `receive()` - 31 edges
3. `chat()` - 24 edges
4. `esc()` - 23 edges
5. `toIde()` - 22 edges
6. `renderInfo()` - 19 edges
7. `PluginLog` - 18 edges
8. `GapGptChatRunner` - 17 edges
9. `IdeMcpServer` - 17 edges
10. `GapGptSettings` - 16 edges

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

## Communities (33 total, 24 thin omitted)

### Community 1 - "Webview Chat Renderer (JS)"
Cohesion: 0.05
Nodes (122): acceptMention(), acceptSlash(), activate(), addTool(), addUser(), agentFor(), alertNotice(), attachMenu() (+114 more)

### Community 2 - "Plugin Concepts & Docs"
Cohesion: 0.08
Nodes (22): ChatPanel.kt (JCEF browser + JS-Kotlin bridge), Claude Code CLI (@anthropic-ai/claude-code), Claude Farsi IntelliJ Plugin, Code Block Copy / Insert-into-Editor Buttons, Editor Context Menu Actions (Send Selection / Explain / Review), Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems), Gradle Build (buildPlugin / runIde), Headless Claude CLI (claude -p --output-format stream-json) (+14 more)

### Community 5 - "SelectionActions.kt"
Cohesion: 0.09
Nodes (7): ExplainSelectionAction, OpenChatAction, ReviewSelectionAction, SelectionToClaudeAction, SendSelectionAction, ChatPanelHolder, ChatToolWindowFactory

### Community 6 - "DependencyScanner.kt"
Cohesion: 0.09
Nodes (5): ClaudeConfig, Dep, DependencyScanner, Command, SlashCommands

### Community 7 - "Settings UI Configurable"
Cohesion: 0.11
Nodes (3): GapGptApi, Model, GapGptConfigurable

### Community 13 - "SelectionTracker.kt"
Cohesion: 0.12
Nodes (5): SelectionTracker, CaretListener, FileEditorManagerListener, SelectionListener, Snapshot

### Community 14 - ".decide"
Cohesion: 0.13
Nodes (6): Allow, Decision, Deny, Pending, PermissionBroker, Ui

### Community 19 - ".add"
Cohesion: 0.12
Nodes (5): ChatBackend, GapGptChatRunner, Reply, ClaudeCliRunner, Listener

### Community 23 - "PluginLog"
Cohesion: 0.12
Nodes (8): Action, BLOCK, WARN, Decision, Guard, Rule, Line, PluginLog

## Knowledge Gaps
- **8 isolated node(s):** `BLOCK`, `WARN`, `Composer (RTL textarea, Send/Stop buttons)`, `Code Block Copy / Insert-into-Editor Buttons`, `Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems)` (+3 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 125 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **24 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ChatPanel` connect `ChatPanel` to `ChatPanel.kt`, `SelectionActions.kt`, `DependencyScanner.kt`, `SelectionTracker.kt`, `.decide`, `.add`, `PluginLog`, `.handleFromJs`, `.post`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **What connects `BLOCK`, `WARN`, `Composer (RTL textarea, Send/Stop buttons)` to the rest of the system?**
  _8 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ChatPanel.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.08695652173913043 - nodes in this community are weakly interconnected._
- **Why does `PluginLog` connect `PluginLog` to `ChatPanel.kt`, `Plugin Concepts & Docs`, `.run`, `SelectionActions.kt`, `DependencyScanner.kt`, `.decide`, `.add`, `.handleFromJs`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Should `Webview Chat Renderer (JS)` be split into smaller, more focused modules?**
  _Cohesion score 0.052258064516129035 - nodes in this community are weakly interconnected._
- **Why does `SelectionTracker` connect `SelectionTracker.kt` to `ChatPanel.kt`?**
  _High betweenness centrality (0.030) - this node is a cross-community bridge._
- **Should `Plugin Concepts & Docs` be split into smaller, more focused modules?**
  _Cohesion score 0.08275862068965517 - nodes in this community are weakly interconnected._