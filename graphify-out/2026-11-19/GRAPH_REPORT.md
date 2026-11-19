# Graph Report - gapgpt-intellij  (2026-11-19)

## Corpus Check
- 60 files · ~49,084 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 9 file(s) not represented in the graph (top: .properties 2, .xml 2, (none) 1)

## Summary
- 927 nodes · 2186 edges · 92 communities (18 shown, 74 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 92 edges (avg confidence: 0.88)
- Token cost: 45,394 input · 0 output

## Graph Freshness
- Built from commit: `414c9271`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- chat.js
- ایجنت چت
- Conversation
- McpConfig.kt
- SelectionActions.kt
- McpClient.kt
- GapGptConfigurable.kt
- McpManager
- ImportInstructionsAction.kt
- SelectionTracker.kt
- AgentTools
- CLAUDE.md
- GapGptUsage
- CodeTools.kt
- ChatPanel
- GapGptClient
- GraphifySetup
- New feature checklist
- Task workflow: plan → approve → implement → review → document
- New feature checklist
- GapGptPricing
- Confirm before edit
- Confirm before edit
- ProjectInstructions
- GitInfo.kt
- project-memory-2.md
- PluginLog
- graphify.md
- .claude/rules/plugin-git.md
- .claude/skills/debug-hypotheses/SKILL.md
- .claude/skills/explore-feature/SKILL.md
- .claude/skills/senior-review/SKILL.md
- .gapgpt/rules/plugin-git.md
- .gapgpt/skills/debug-hypotheses/SKILL.md
- .gapgpt/skills/explore-feature/SKILL.md
- .gapgpt/skills/senior-review/SKILL.md

## God Nodes (most connected - your core abstractions)
1. `ChatPanel` - 79 edges
2. `AgentTools` - 46 edges
3. `receive()` - 35 edges
4. `GapGptClient` - 34 edges
5. `Conversation` - 26 edges
6. `ChatMessage` - 25 edges
7. `PluginLog` - 25 edges
8. `GraphifySetup` - 21 edges
9. `McpManager` - 20 edges
10. `CodeTools` - 18 edges

## Surprising Connections (you probably didn't know these)
- `پیمایش کد (`CodeTools`)` --references--> `CodeTools`  [INFERRED]
  docs/chat-agent.md → src/main/kotlin/ir/gapgpt/agent/CodeTools.kt
- `۵. کار با ایجنت` --references--> `GapGptClient`  [INFERRED]
  docs/getting-started.md → src/main/kotlin/ir/gapgpt/api/GapGptClient.kt
- `۱. درخواست` --references--> `ApiKeyStore`  [INFERRED]
  docs/gapgpt-api.md → src/main/kotlin/ir/gapgpt/settings/ApiKeyStore.kt
- `Plugin don'ts (IntelliJ plugin, Kotlin + JCEF webview)` --references--> `esc()`  [INFERRED]
  .claude/rules/plugin-donts.md → src/main/resources/webview/chat.js
- `Plugin don'ts (IntelliJ plugin, Kotlin + JCEF webview)` --references--> `esc()`  [INFERRED]
  .gapgpt/rules/plugin-donts.md → src/main/resources/webview/chat.js

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Chat webview UI regions** — src_main_resources_webview_chat_topbar, src_main_resources_webview_chat_messages, src_main_resources_webview_chat_status, src_main_resources_webview_chat_composer [EXTRACTED 1.00]
- **Persian text rendering support** — readme_rtl_bidi_rendering, readme_vazirmatn_font, readme_jcef_html_panel, src_main_resources_webview_vazirmatn_ofl_sil_open_font_license [INFERRED 0.85]

## Communities (92 total, 74 thin omitted)

### Community 1 - "chat.js"
Cohesion: 0.08
Nodes (91): 4. Webview, 4. Webview, acceptMention(), acceptSlash(), activate(), addUser(), attachMenu(), autoGrow() (+83 more)

### Community 2 - "ایجنت چت"
Cohesion: 0.05
Nodes (34): context و خلاصه‌سازی, graphify (فقط با درخواست کاربر), MCP, ابزارها, استقلال از ابزارهای دیگر, ایجنت چت, حالت طرح (plan mode), دستورهای `/` (+26 more)

### Community 3 - "Conversation"
Cohesion: 0.10
Nodes (6): ChatMessage, Conversation, ToolCall, Meta, Session, SessionStore

### Community 5 - "SelectionActions.kt"
Cohesion: 0.05
Nodes (29): ChatPanel.kt (JCEF browser + JS-Kotlin bridge), Claude Code CLI (@anthropic-ai/claude-code), Claude Farsi IntelliJ Plugin, Code Block Copy / Insert-into-Editor Buttons, Editor Context Menu Actions (Send Selection / Explain / Review), Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems), Gradle Build (buildPlugin / runIde), Headless Claude CLI (claude -p --output-format stream-json) (+21 more)

### Community 6 - "McpClient.kt"
Cohesion: 0.08
Nodes (8): await(), HttpMcpClient, McpCallResult, McpClient, McpException, McpTool, StdioMcpClient, Server

### Community 7 - "GapGptConfigurable.kt"
Cohesion: 0.06
Nodes (11): ارتباط با API گپ‌جی‌پی‌تی, فایل‌ها, نمای کلی, چک‌لیست اشکال‌یابی, ۱. درخواست, ۲. پاسخ, ۳. خطاها, ۴. فهرست مدل‌ها (+3 more)

### Community 12 - "ImportInstructionsAction.kt"
Cohesion: 0.15
Nodes (4): ImportInstructionsAction, ImportResult, InstructionItem, InstructionsImporter

### Community 13 - "SelectionTracker.kt"
Cohesion: 0.08
Nodes (6): ProjectFileSearch, SelectionTracker, CaretListener, FileEditorManagerListener, SelectionListener, Snapshot

### Community 15 - "AgentTools"
Cohesion: 0.14
Nodes (9): AgentTools, Kind, COMMAND, EDIT, MCP, READ, Prepared, Result (+1 more)

### Community 19 - "GapGptUsage"
Cohesion: 0.13
Nodes (5): GapGptUsage, State, Totals, GapGptSettings, State

### Community 20 - "CodeTools.kt"
Cohesion: 0.11
Nodes (3): Answer, CodeTools, PsiRecursiveElementWalkingVisitor

### Community 23 - "ChatPanel"
Cohesion: 0.10
Nodes (5): ChatPanel, Listener, Listener, ChatSession, Turn

### Community 24 - "GapGptClient"
Cohesion: 0.14
Nodes (8): ApiException, Balance, GapGptClient, Handle, Listener, Part, Reply, ToolCallAccumulator

### Community 29 - "New feature checklist"
Cohesion: 0.25
Nodes (7): 1. Backend / state, 2. IDE integration, 3. Bridge (`ui/ChatPanel.kt`), 5. Registration, Build order, New feature checklist, New feature scaffold

### Community 30 - "Task workflow: plan → approve → implement → review → document"
Cohesion: 0.25
Nodes (7): 1. Analyze & plan, 2. Explain the plan and wait for approval, 3. Implement, 4. Review, 5. Document the work, 6. Report, Task workflow: plan → approve → implement → review → document

### Community 31 - "New feature checklist"
Cohesion: 0.25
Nodes (7): 1. Backend / state, 2. IDE integration, 3. Bridge (`ui/ChatPanel.kt`), 5. Registration, Build order, New feature checklist, New feature scaffold

### Community 35 - "ProjectInstructions"
Cohesion: 0.23
Nodes (6): ProjectInstructions, Rule, Skill, I18n, Command, SlashCommands

### Community 42 - "PluginLog"
Cohesion: 0.07
Nodes (17): 1. Analyze & plan (strong model), 2. Explain the plan and wait for approval, 3. Delegate to cheaper coding models, 4. Review the subagents' code, 5. Document the work, 6. Report, Task workflow: plan → approve → delegate → review → document, Build (+9 more)

## Knowledge Gaps
- **11 isolated node(s):** `READ`, `EDIT`, `COMMAND`, `MCP`, `Composer (RTL textarea, Send/Stop buttons)` (+6 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 260 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **74 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PluginLog` connect `PluginLog` to `ChatPanel.kt`, `Conversation`, `McpConfig.kt`, `McpClient.kt`, `AgentTools.kt`, `McpManager`, `ImportInstructionsAction.kt`, `ChatPanel`, `GapGptClient`, `GraphifySetup`?**
  _High betweenness centrality (0.166) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `receive()` (e.g. with `4. Webview` and `New feature scaffold`) actually correct?**
  _`receive()` has 4 INFERRED edges - model-reasoned connections that need verification._
- **What connects `READ`, `EDIT`, `COMMAND` to the rest of the system?**
  _11 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ChatPanel.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.06666666666666667 - nodes in this community are weakly interconnected._
- **Why does `AgentTools` connect `AgentTools` to `ChatPanel.kt`, `AgentTools.kt`, `McpManager`, `CodeTools.kt`, `ChatPanel`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Should `chat.js` be split into smaller, more focused modules?**
  _Cohesion score 0.07709906199954245 - nodes in this community are weakly interconnected._
- **Why does `ChatPanel` connect `ChatPanel` to `ChatPanel.kt`, `SelectionActions.kt`, `PluginLog`, `McpManager`, `SelectionTracker.kt`, `AgentTools`, `GapGptClient`?**
  _High betweenness centrality (0.103) - this node is a cross-community bridge._