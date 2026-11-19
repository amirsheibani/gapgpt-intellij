# Confirm before edit

Before changing existing code, show the proposed change and wait for an explicit approval. Do not apply that edit in the same turn.

This covers Kotlin, the webview (`chat.html` / `chat.css` / `chat.js`), `plugin.xml`, and the Gradle files. A file the user just asked to create (such as this rule) may be written immediately.

After approval, change only what was shown.

## Subagents

A subagent spawned by the main session under a plan the user approved there may apply exactly the edits listed in its prompt without asking again. The main session states in the prompt that the plan was approved; anything outside that list still needs approval.

The main session stays responsible for that code. Before reporting the work as done, it must review every subagent diff line by line against the plan and the project rules, and run `./gradlew buildPlugin` (see `plugin-donts.md` for the JDK). It fixes anything that is wrong or sends it back.
