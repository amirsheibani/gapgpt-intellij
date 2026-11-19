---
name: debug-hypotheses
description: Find the root cause of an error by proposing 3 evidence-backed hypotheses (file + line) without changing code; the user picks which one to go deeper on.
argument-hint: <error / stack trace / description>
disable-model-invocation: true
---

# Debug hypotheses

Error:

```
$ARGUMENTS
```

- **Do not change anything yet.**
- Find the root cause.
- Give the **3 main hypotheses**, each with evidence from the code (`path:line`).
- Stop and let the user choose which hypothesis to look into more deeply.

Follow-up flow (wait for the user at each step):

1. "Look deeper at hypothesis N": investigate only that one. Still no fix.
2. "Hypothesis confirmed, apply the fix": show the fix first (per the confirm-before-edit rule), then change only the files that need it.

Reply in Persian.
