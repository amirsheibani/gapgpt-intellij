# Graph Report - gapgpt-intellij  (2026-11-18)

## Corpus Check
- 35 files · ~40,981 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 9 file(s) not represented in the graph (top: .properties 2, .xml 2, (none) 1)

## Summary
- 691 nodes · 1637 edges · 51 communities (16 shown, 35 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 53 edges (avg confidence: 0.85)
- Token cost: 45,394 input · 0 output

## Graph Freshness
- Built from commit: `7c398400`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- chat.js
- Claude Farsi IntelliJ Plugin
- GapGptTools
- SelectionActions.kt
- Settings UI Configurable
- IdeMcpServer.kt
- DiffPreview.kt
- SelectionTracker.kt
- .decide
- receive
- CLAUDE.md
- GapGptChatRunner
- toIde
- ChatPanel
- chat
- ClaudeCliRunner
- receive
- esc
- onClaude
- renderInfo
- i18n.js
- file
- IdeTools.kt
- GitInfo.kt
- GapGptSettings
- PluginLog
- DependencyScanner
- ProjectFileSearch
- ClaudeConfig.kt

## God Nodes (most connected - your core abstractions)
1. `ChatPanel` - 47 edges
2. `receive()` - 34 edges
3. `GapGptTools` - 27 edges
4. `GapGptChatRunner` - 25 edges
5. `esc()` - 25 edges
6. `chat()` - 24 edges
7. `toIde()` - 24 edges
8. `renderInfo()` - 22 edges
9. `PluginLog` - 19 edges
10. `GapGptSettings` - 18 edges

## Surprising Connections (you probably didn't know these)
- `chat.html Webview Template` --implements--> `RTL / Bidi Rendering (unicode-bidi: plaintext)`  [INFERRED]
  src/main/resources/webview/chat.html → README.md
- `ChatPanel.kt (JCEF browser + JS-Kotlin bridge)` --references--> `chat.html Webview Template`  [INFERRED]
  README.md → src/main/resources/webview/chat.html
- `Thinking Status Indicator` --conceptually_related_to--> `Live Streaming Response and Tool Use Display`  [INFERRED]
  src/main/resources/webview/chat.html → README.md
- `Topbar (status dot, New Chat, Settings buttons)` --implements--> `Conversation Resume (--resume) and New Chat`  [INFERRED]
  src/main/resources/webview/chat.html → README.md
- `GapGptTools` --calls--> `IdeTools`  [INFERRED]
  src/main/kotlin/ir/gapgpt/api/GapGptTools.kt → src/main/kotlin/ir/gapgpt/ide/IdeTools.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Chat webview UI regions** — src_main_resources_webview_chat_topbar, src_main_resources_webview_chat_messages, src_main_resources_webview_chat_status, src_main_resources_webview_chat_composer [EXTRACTED 1.00]
- **CLI runner -> JCEF ChatPanel -> chat.html webview pipeline** — src_main_kotlin_ir_gapgpt_cli_claudeclirunner, readme_chatpanel, src_main_kotlin_ir_gapgpt_ui_chattoolwindowfactory, src_main_resources_webview_chat_chat_html [INFERRED 0.85]
- **Persian text rendering support** — readme_rtl_bidi_rendering, readme_vazirmatn_font, readme_jcef_html_panel, src_main_resources_webview_vazirmatn_ofl_sil_open_font_license [INFERRED 0.85]

## Communities (51 total, 35 thin omitted)

### Community 1 - "chat.js"
Cohesion: 0.14
Nodes (24): acceptMention(), acceptSlash(), addUser(), autoGrow(), closeMention(), closeSlash(), codeBlock(), hideWelcome() (+16 more)

### Community 2 - "Claude Farsi IntelliJ Plugin"
Cohesion: 0.11
Nodes (22): ChatPanel.kt (JCEF browser + JS-Kotlin bridge), Claude Code CLI (@anthropic-ai/claude-code), Claude Farsi IntelliJ Plugin, Code Block Copy / Insert-into-Editor Buttons, Editor Context Menu Actions (Send Selection / Explain / Review), Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems), Gradle Build (buildPlugin / runIde), Headless Claude CLI (claude -p --output-format stream-json) (+14 more)

### Community 5 - "SelectionActions.kt"
Cohesion: 0.07
Nodes (11): ExplainSelectionAction, OpenChatAction, ReviewSelectionAction, SelectionToClaudeAction, SendSelectionAction, Balance, GapGptApi, Model (+3 more)

### Community 13 - "SelectionTracker.kt"
Cohesion: 0.13
Nodes (5): SelectionTracker, CaretListener, FileEditorManagerListener, SelectionListener, Snapshot

### Community 14 - ".decide"
Cohesion: 0.09
Nodes (12): Action, BLOCK, WARN, Decision, Guard, Rule, Allow, Decision (+4 more)

### Community 15 - "receive"
Cohesion: 0.21
Nodes (17): chat(), firstPendingCard(), guardTabCount(), parseResetText(), receive(), refreshChatUi(), renderCtx(), renderGuardBody() (+9 more)

### Community 19 - "GapGptChatRunner"
Cohesion: 0.10
Nodes (12): Call, GapGptChatRunner, Step, StepCall, ToolsUnsupported, Usage, GapGptPricing, Limits (+4 more)

### Community 20 - "toIde"
Cohesion: 0.39
Nodes (9): filteredLog(), handleResult(), renderGuard(), renderLogBody(), renderLogView(), renderView(), renderVulns(), requestUsage() (+1 more)

### Community 23 - "ChatPanel"
Cohesion: 0.10
Nodes (3): CliInfo, ChatPanel, Listener

### Community 24 - "chat"
Cohesion: 0.21
Nodes (15): activate(), alertNotice(), clearChat(), closeChat(), freshUsage(), gitAsk(), mkTarget(), moreMenu() (+7 more)

### Community 25 - "ClaudeCliRunner"
Cohesion: 0.16
Nodes (3): ChatBackend, ClaudeCliRunner, Listener

### Community 26 - "receive"
Cohesion: 0.20
Nodes (15): attachMenu(), backendItems(), closeMenu(), effortMenu(), filterModelMenu(), gapModelMenu(), menuItem(), modelMenu() (+7 more)

### Community 31 - "esc"
Cohesion: 0.25
Nodes (11): countLines(), diffHtml(), editDiff(), esc(), previewLines(), renderGit(), renderRules(), resultText() (+3 more)

### Community 32 - "onClaude"
Cohesion: 0.15
Nodes (22): addTool(), agentFor(), chatStatus(), displayName(), estTok(), handleAssistant(), handleStream(), handleToolResults() (+14 more)

### Community 33 - "renderInfo"
Cohesion: 0.18
Nodes (18): cap(), fmtInt(), fmtNum(), fmtReset(), gapFamily(), gapWindow(), limitLabel(), liveOut() (+10 more)

### Community 34 - "i18n.js"
Cohesion: 0.36
Nodes (9): apply(), attrs(), setLang(), skipped(), start(), t(), textNode(), tr() (+1 more)

## Knowledge Gaps
- **8 isolated node(s):** `BLOCK`, `WARN`, `Composer (RTL textarea, Send/Stop buttons)`, `Code Block Copy / Insert-into-Editor Buttons`, `Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems)` (+3 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 139 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **35 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ChatPanel` connect `ChatPanel` to `ChatPanel.kt`, `SelectionActions.kt`, `PluginLog`, `DependencyScanner`, `SelectionTracker.kt`, `.decide`?**
  _High betweenness centrality (0.085) - this node is a cross-community bridge._
- **What connects `BLOCK`, `WARN`, `Composer (RTL textarea, Send/Stop buttons)` to the rest of the system?**
  _8 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ChatPanel.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.05555555555555555 - nodes in this community are weakly interconnected._
- **Why does `GapGptTools` connect `GapGptTools` to `GapGptChatRunner`, `IdeTools.kt`, `GapGptTools.kt`?**
  _High betweenness centrality (0.033) - this node is a cross-community bridge._
- **Should `chat.js` be split into smaller, more focused modules?**
  _Cohesion score 0.13675213675213677 - nodes in this community are weakly interconnected._
- **Why does `PluginLog` connect `PluginLog` to `ChatPanel.kt`, `SelectionActions.kt`, `GapGptChatRunner.kt`, `GapGptTools.kt`, `Guard.kt`, `.decide`, `ChatPanel`?**
  _High betweenness centrality (0.029) - this node is a cross-community bridge._
- **Should `Claude Farsi IntelliJ Plugin` be split into smaller, more focused modules?**
  _Cohesion score 0.1067193675889328 - nodes in this community are weakly interconnected._