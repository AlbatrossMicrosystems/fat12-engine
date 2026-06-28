---
name: Feature request
about: Suggest an addition or improvement to the FAT12 engine
title: ''
labels: enhancement
assignees: ''
---

## Summary

A clear, concise description of the feature or improvement.

## Motivation

What problem does this solve? What can't you do today, or what's awkward about the
current API?

## Proposed API / change

If you have a concrete shape in mind, sketch it:

```kotlin
// proposed signatures / constants / behaviour
```

## Scope check

This repo is the `:core` engine only (the Android demonstrator is separate). Is the
request in scope for `:core`?

## Correctness-contract impact

Would this touch a write/rollback path, or is it additive (new pure helpers, constants,
read-only accessors)? Additive changes are the easiest to land.

## Alternatives considered

Any other approaches you thought about.

## Additional context

Links to the FAT spec, related issues, or examples from other tools.
