# Confirm before edit

Before changing existing code, show the proposed change and wait for an explicit approval. Do not apply that edit in the same turn.

This covers Kotlin, the webview (`chat.html` / `chat.css` / `chat.js`), `plugin.xml`, and the Gradle files. A file the user just asked to create (such as this rule) may be written immediately.

After approval, change only what was shown.

## Before reporting done

Re-read the whole diff against the plan and the project rules, and run `./gradlew buildPlugin` (see `plugin-donts.md` for the JDK). Fix anything that is wrong before reporting the work as done.
