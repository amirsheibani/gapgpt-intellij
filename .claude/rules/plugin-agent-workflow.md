# Task workflow: plan → approve → delegate → review → document

Use this workflow whenever the user asks for a task that changes code (feature, fix, refactor). It does not apply to questions, explore-only, review-only, or debug-hypotheses requests, where no code is changed.

The user explicitly asked for this workflow, so spawning subagents is expected here.

## 1. Analyze & plan (strong model)

- Do the analysis and planning in the main session (Opus). For a large read-only sweep, a `Plan` / `Explore` subagent with `model: "opus"` is fine, but the final plan is yours.
- Scope reading to the touched packages + relevant `docs/` (see `plugin-donts.md`). Use the knowledge graph first (`graphify query` / `path` / `explain`), then the IntelliJ index tools (`mcp__idea__search_symbol`, `mcp__idea__analyze_calls`).
- The plan must contain:
  - Goal and root cause / design decision, with `path:line` evidence.
  - Files to create/change, **layer by layer**:
    1. backend (`api/` client, `agent/` tools, `chat/` / `context/` / `mcp/`, settings state)
    2. IDE integration (`ide/`, `actions/`)
    3. bridge (`ui/ChatPanel.kt`: `handleFromJs` message types and `post(kind, …)` events)
    4. webview (`chat.js` / `chat.css` / `chat.html`, i18n table)
    5. registration (`plugin.xml`, `*Configurable`, `I18n.tr` strings)
  - How the work is split into subagent tasks: for each task, its files, the model, and whether it runs in parallel or after another task.
  - How you will verify (review checklist, `./gradlew buildPlugin` with JDK 21, manual check in `./gradlew runIde` if UI changed).
  - The doc you will write or update in `docs/`.

## 2. Explain the plan and wait for approval

- Explain the plan to the user in Persian and **stop**. Do not spawn coding subagents or edit code until the user explicitly approves (e.g. "اوکی").
- If the user changes the plan, revise it and get approval again.
- If the task is trivial (one small edit), say so in the plan and suggest doing it directly without subagents. The user decides.
- An approved plan counts as approval for the edits it lists (`plugin-confirm-before-edit.md`). Anything outside the plan needs new approval.

## 3. Delegate to cheaper coding models

- Spawn `general-purpose` subagents with:
  - `model: "sonnet"` for normal coding tasks.
  - `model: "haiku"` only for mechanical tasks (i18n strings, renames, boilerplate copying an existing file).
- Subagents start cold. Every prompt must be self-contained:
  - The approved part of the plan for that task, and the exact files it may touch (and must not touch).
  - The reference files to copy patterns from (e.g. `settings/*Settings.kt` + `*Configurable.kt`, an existing message type in `ChatPanel.handleFromJs` and its handler in `chat.js`).
  - Read the project rules first. Key ones: nothing blocking on the EDT, everything with a lifecycle tied to a `Disposable`, `I18n.tr(en, fa)` for UI text, `PluginLog` for logging, secrets only in `PasswordSafe`, validate webview messages, escape everything rendered in the webview, keep RTL intact.
  - Do not commit; report back the changed files and anything left undone.
- Run independent tasks in parallel only if their file sets do not overlap. `ChatPanel.kt`, `chat.js`, and `plugin.xml` are shared hot spots: give each to one task only, or run those tasks in order.

## 4. Review the subagents' code

- Read every diff yourself (`git diff`) against the plan and the project rules (layering, don'ts, naming, RTL/i18n). Also check the `senior-review` focus areas: threading/EDT, disposal and process leaks, error handling, webview bridge security, secrets.
- Run `./gradlew buildPlugin` with JDK 21 and fix compile errors / warnings introduced by the change.
- After code changes, run `graphify update .`.
- Send problems back to the same subagent with `SendMessage`, or fix small ones yourself. Do not accept code that breaks project rules.

## 5. Document the work

- Write or update a doc in `docs/`. If a doc already covers the topic, update it instead of creating a new one.
  - Filename: kebab-case `feature-topic.md`.
  - Wrap the whole file in `<div dir="rtl" lang="fa">` … `</div>`. Put code blocks and flow diagrams inside `<div dir="ltr">`.
  - Structure: a title with a one-line purpose, links to related docs, `---` separators, an overview flow diagram, step-by-step sections, a files table (role of each file), scenarios / a debug checklist, and related files.
  - Add the doc to the table in `docs/README.md` (create it if missing).

## 6. Report

Report to the user in Persian: what changed (files), which subagent/model did which part, review findings and fixes, build results (with the output if anything failed), and the doc link. Do not commit unless asked.
