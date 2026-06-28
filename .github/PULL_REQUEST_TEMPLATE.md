<!--
Thanks for contributing! Please fill in the sections below and tick the checklist.
See CONTRIBUTING.md for build/test instructions and the correctness contract.
-->

## What

Briefly describe what this PR changes and why.

Closes #<!-- issue number -->

## How

Key implementation notes a reviewer should know (design decisions, trade-offs, anything
non-obvious).

## Correctness contract

- [ ] This change does **not** touch a write / `mkdir` / `rename` / `delete` /
      set-label / set-attributes path, **or** it does and I've explained below why the
      verify-after-write + rollback contract is preserved.

<!-- If it touches a write/rollback path, explain here: -->

## Checklist

- [ ] Tests added or updated for the change.
- [ ] `./gradlew :core:test` passes locally.
- [ ] Change is scoped to the `:core` engine (the Android demonstrator is a separate repo).
- [ ] Golden images under `core/src/testFixtures/resources/golden/` are unchanged (or the PR explains why they were regenerated, per `testdata/README.md`).
- [ ] PR is focused on one logical change.
