# Task workflow: plan → approve → implement → review → document

Use this workflow whenever the user asks for a task that changes code (feature, fix, refactor). It does not apply to questions, explore-only, review-only, or debug-hypotheses requests, where no code is changed.

## 1. Analyze & plan

- Scope reading to the touched packages + relevant `docs/` (see `plugin-donts.md`).
- The plan must contain:
  - Goal and root cause / design decision, with `path:line` evidence.
  - Files to create/change, **layer by layer**:
    1. backend (`api/GapGptClient.kt`, `chat/`, settings state)
    2. project instructions (`context/`)
    3. IDE integration (`ide/`, `actions/`)
    4. bridge (`ui/ChatPanel.kt`: `handleFromJs` message types and `post(kind, …)` events)
    5. webview (`chat.js` / `chat.css` / `chat.html`, i18n table)
    6. registration (`plugin.xml`, `*Configurable`, `I18n.tr` strings)
  - If the work is split into parts, which files each part touches and the order (parts that touch `ChatPanel.kt`, `chat.js`, or `plugin.xml` must not run at the same time).
  - How it will be verified (review checklist, `./gradlew buildPlugin` with JDK 21, manual check in `./gradlew runIde` if UI changed).
  - The doc that will be written or updated in `docs/`.

## 2. Explain the plan and wait for approval

- Explain the plan to the user in Persian and **stop**. Do not edit code until the user explicitly approves (e.g. "اوکی").
- If the user changes the plan, revise it and get approval again.
- If the task is trivial (one small edit), say so and suggest doing it directly. The user decides.
- An approved plan counts as approval for the edits it lists (`plugin-confirm-before-edit.md`). Anything outside the plan needs new approval.

## 3. Implement

- Change only the files in the approved plan, following the closest existing feature and `plugin-donts.md`.
- Do not commit.

## 4. Review

- Re-read every diff against the plan and the project rules (layering, don'ts, naming, RTL/i18n), plus the `senior-review` focus areas: threading/EDT, disposal and leaks, error handling, webview bridge security, secrets.
- Run `./gradlew buildPlugin` with JDK 21 and fix compile errors / warnings introduced by the change.

## 5. Document the work

- Write or update a doc in `docs/`. If a doc already covers the topic, update it instead of creating a new one.
  - Filename: kebab-case `feature-topic.md`.
  - Wrap the whole file in `<div dir="rtl" lang="fa">` … `</div>`. Put code blocks and flow diagrams inside `<div dir="ltr">`.
  - Structure: a title with a one-line purpose, links to related docs, `---` separators, an overview flow diagram, step-by-step sections, a files table (role of each file), scenarios / a debug checklist, and related files.
  - Add the doc to the table in `docs/README.md`.

## 6. Report

Report to the user in Persian: what changed (files), review findings and fixes, build results (with the output if anything failed), and the doc link. Do not commit unless asked.
