# Graph Report - gapgpt-intellij  (2026-11-19)

## Corpus Check
- 60 files · ~50,441 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 10 file(s) not represented in the graph (top: (none) 2, .properties 2, .xml 2)

## Summary
- 929 nodes · 2183 edges · 98 communities (20 shown, 78 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 74 edges (avg confidence: 0.89)
- Token cost: 45,394 input · 0 output

## Graph Freshness
- Built from commit: `351bac7d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- chat.js
- راهنمای شروع GapGPT
- Conversation
- McpConfig.kt
- SelectionActions.kt
- McpClient.kt
- ایجنت چت
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
- ApiKeyStore.kt
- GapGptPricing
- Confirm before edit
- Confirm before edit
- ProjectInstructions
- GitInfo.kt
- ارتباط با API گپ‌جی‌پی‌تی
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
- قوانین، اسکیل‌ها و دکمهٔ کپی
- chat-agent.md
- project-memory-2.md

## God Nodes (most connected - your core abstractions)
1. `ChatPanel` - 79 edges
2. `AgentTools` - 46 edges
3. `receive()` - 36 edges
4. `GapGptClient` - 34 edges
5. `Conversation` - 26 edges
6. `ChatMessage` - 25 edges
7. `PluginLog` - 25 edges
8. `GraphifySetup` - 21 edges
9. `McpManager` - 19 edges
10. `chat()` - 19 edges

## Surprising Connections (you probably didn't know these)
- `پیمایش کد (`CodeTools`)` --references--> `CodeTools`  [INFERRED]
  docs/chat-agent.md → src/main/kotlin/ir/gapgpt/agent/CodeTools.kt
- `۵. کار با ایجنت` --references--> `GapGptClient`  [INFERRED]
  docs/getting-started.md → src/main/kotlin/ir/gapgpt/api/GapGptClient.kt
- `۱. درخواست` --references--> `ApiKeyStore`  [INFERRED]
  docs/gapgpt-api.md → src/main/kotlin/ir/gapgpt/settings/ApiKeyStore.kt
- `Implement after confirm` --references--> `PluginLog`  [INFERRED]
  .claude/skills/implement/SKILL.md → src/main/kotlin/ir/gapgpt/PluginLog.kt
- `Implement after confirm` --references--> `PluginLog`  [INFERRED]
  .gapgpt/skills/implement/SKILL.md → src/main/kotlin/ir/gapgpt/PluginLog.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Chat webview UI regions** — src_main_resources_webview_chat_topbar, src_main_resources_webview_chat_messages, src_main_resources_webview_chat_status, src_main_resources_webview_chat_composer [EXTRACTED 1.00]
- **Persian text rendering support** — readme_rtl_bidi_rendering, readme_vazirmatn_font, readme_jcef_html_panel, src_main_resources_webview_vazirmatn_ofl_sil_open_font_license [INFERRED 0.85]

## Communities (98 total, 78 thin omitted)

### Community 1 - "chat.js"
Cohesion: 0.07
Nodes (100): 4. Webview, 1. Backend / state, 2. IDE integration, 3. Bridge (`ui/ChatPanel.kt`), 4. Webview, 5. Registration, Build order, New feature checklist (+92 more)

### Community 2 - "راهنمای شروع GapGPT"
Cohesion: 0.14
Nodes (14): از داخل ویرایشگر, از فایل zip, بررسی کلید پیش از نصب پلاگین (اختیاری), دستورهای `/`, راهنمای شروع GapGPT, ساخت zip از سورس, نمای کلی, چک‌لیست اشکال‌یابی (+6 more)

### Community 3 - "Conversation"
Cohesion: 0.12
Nodes (6): ChatMessage, Conversation, ToolCall, Meta, Session, SessionStore

### Community 5 - "SelectionActions.kt"
Cohesion: 0.05
Nodes (29): ChatPanel.kt (JCEF browser + JS-Kotlin bridge), Claude Code CLI (@anthropic-ai/claude-code), Claude Farsi IntelliJ Plugin, Code Block Copy / Insert-into-Editor Buttons, Editor Context Menu Actions (Send Selection / Explain / Review), Future Ideas (permission-prompt-tool MCP, IDE diff viewer, session history, auto-send IDE problems), Gradle Build (buildPlugin / runIde), Headless Claude CLI (claude -p --output-format stream-json) (+21 more)

### Community 6 - "McpClient.kt"
Cohesion: 0.09
Nodes (7): await(), HttpMcpClient, McpCallResult, McpClient, McpException, McpTool, StdioMcpClient

### Community 7 - "ایجنت چت"
Cohesion: 0.20
Nodes (10): context و خلاصه‌سازی, graphify (فقط وقتی کاربر روشنش کند), MCP, ابزارها, استقلال از ابزارهای دیگر, ایجنت چت, حالت طرح (plan mode), دستورهای `/` (+2 more)

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
Cohesion: 0.14
Nodes (5): GapGptUsage, State, Totals, GapGptSettings, State

### Community 20 - "CodeTools.kt"
Cohesion: 0.11
Nodes (3): Answer, CodeTools, PsiRecursiveElementWalkingVisitor

### Community 23 - "ChatPanel"
Cohesion: 0.08
Nodes (7): ۲. ساخت system prompt, Prompts, ChatPanel, Listener, Listener, ChatSession, Turn

### Community 24 - "GapGptClient"
Cohesion: 0.14
Nodes (8): ApiException, Balance, GapGptClient, Handle, Listener, Part, Reply, ToolCallAccumulator

### Community 26 - "GraphifySetup"
Cohesion: 0.06
Nodes (3): GraphifySetup, Backgroundable, GapGptConfigurable

### Community 29 - "New feature checklist"
Cohesion: 0.25
Nodes (7): 1. Backend / state, 2. IDE integration, 3. Bridge (`ui/ChatPanel.kt`), 5. Registration, Build order, New feature checklist, New feature scaffold

### Community 30 - "Task workflow: plan → approve → implement → review → document"
Cohesion: 0.25
Nodes (7): 1. Analyze & plan, 2. Explain the plan and wait for approval, 3. Implement, 4. Review, 5. Document the work, 6. Report, Task workflow: plan → approve → implement → review → document

### Community 35 - "ProjectInstructions"
Cohesion: 0.23
Nodes (6): ProjectInstructions, Rule, Skill, I18n, Command, SlashCommands

### Community 40 - "ارتباط با API گپ‌جی‌پی‌تی"
Cohesion: 0.25
Nodes (8): ارتباط با API گپ‌جی‌پی‌تی, فایل‌ها, نمای کلی, چک‌لیست اشکال‌یابی, ۲. پاسخ, ۳. خطاها, ۴. فهرست مدل‌ها, ۵. هزینه و اعتبار حساب

### Community 42 - "PluginLog"
Cohesion: 0.07
Nodes (17): 1. Analyze & plan (strong model), 2. Explain the plan and wait for approval, 3. Delegate to cheaper coding models, 4. Review the subagents' code, 5. Document the work, 6. Report, Task workflow: plan → approve → delegate → review → document, Build (+9 more)

### Community 95 - "قوانین، اسکیل‌ها و دکمهٔ کپی"
Cohesion: 0.29
Nodes (7): فایل‌ها, قوانین، اسکیل‌ها و دکمهٔ کپی, نمای کلی, چک‌لیست اشکال‌یابی, ۱. ساختار `.gapgpt/`, ۳. اجرای اسکیل, ۴. دکمهٔ کپی

## Knowledge Gaps
- **11 isolated node(s):** `COMMAND`, `EDIT`, `MCP`, `READ`, `Composer (RTL textarea, Send/Stop buttons)` (+6 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 261 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **78 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PluginLog` connect `PluginLog` to `ChatPanel.kt`, `McpConfig.kt`, `McpManager.kt`, `McpClient.kt`, `ImportInstructionsAction.kt`, `file`, `ChatPanel`, `GapGptClient`, `GraphifySetup`?**
  _High betweenness centrality (0.161) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `receive()` (e.g. with `4. Webview` and `New feature scaffold`) actually correct?**
  _`receive()` has 4 INFERRED edges - model-reasoned connections that need verification._
- **What connects `COMMAND`, `EDIT`, `MCP` to the rest of the system?**
  _11 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ChatPanel.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.06451612903225806 - nodes in this community are weakly interconnected._
- **Why does `ChatPanel` connect `ChatPanel` to `ChatPanel.kt`, `SelectionActions.kt`, `PluginLog`, `McpManager`, `SelectionTracker.kt`, `AgentTools`, `GapGptClient`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Should `chat.js` be split into smaller, more focused modules?**
  _Cohesion score 0.06590739357729648 - nodes in this community are weakly interconnected._
- **Why does `AgentTools` connect `AgentTools` to `ChatPanel.kt`, `AgentTools.kt`, `McpManager`, `CodeTools.kt`, `ChatPanel`, `McpManager.kt`?**
  _High betweenness centrality (0.110) - this node is a cross-community bridge._