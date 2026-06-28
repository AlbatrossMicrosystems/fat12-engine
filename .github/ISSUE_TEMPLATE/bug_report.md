---
name: Bug report
about: Report incorrect behaviour in the FAT12 engine
title: ''
labels: bug
assignees: ''
---

## Summary

A clear, concise description of the bug.

## To reproduce

Steps or a minimal code snippet that triggers the problem. If you can, drive it through
the in-memory device fixture (`InMemoryBlockDevice`) so it's runnable in a unit test:

```kotlin
// e.g. format -> writeFile -> readFile, and what you observed
```

## Expected behaviour

What you expected to happen.

## Actual behaviour

What actually happened — include the exception/stack trace or the wrong bytes/result.

## Environment

- fat12-engine version / commit:
- JDK version (`java -version`):
- OS:

## Correctness-contract impact

Does this involve a write/`mkdir`/`rename`/`delete`/set-label/set-attributes path, i.e.
the verify-after-write + rollback contract? (yes / no / unsure)

## Additional context

Anything else that might help — volume geometry, file names, image dumps, etc.
