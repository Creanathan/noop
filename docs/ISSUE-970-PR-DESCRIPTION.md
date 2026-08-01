## Summary

Fixes #970.

Sleep editing previously derived the wake calendar day from bedtime and wake time-of-day. Users could not reliably correct a record such as:

```text
Recorded:  July 28 22:30 → July 29 07:30
Correct:   July 29 02:30 → July 29 07:30
```

This change lets Apple and Android users edit the wake date and time explicitly and preserves the selected end timestamp through the existing persistence and analytics pipeline.

## Changes

- Apple `SleepTimeEditor` now exposes the wake date and time and saves the selected timestamp directly.
- Android wake editing now selects a date followed by a time and stores the complete selected timestamp.
- Replaced Android's derived-date `withWakeTime` draft operation with `withWakeCandidate`.
- Retained existing bedtime correction, endpoint validation, disjoint-window confirmation, atomic Save behavior, stage re-clipping, persistence, and rescore flow.
- Added Android regression coverage for explicit wake dates and invalid wake-before-bed input.
- No database schema, migration, networking, Bluetooth, or protocol changes.

## Validation status

- `git diff --check` passed.
- The branch is based on current `main`.
- Android tests and compilation are documented but remain pending because the development environment used to prepare this PR has no configured Android SDK.
- Apple build and simulator validation remain pending because they require macOS/Xcode.

See `docs/ISSUE-970-VALIDATION.md` for exact commands and the emulator/simulator checklist. This PR is intentionally opened as a draft until platform validation is completed.

## Manual validation checklist

- [ ] Android unit tests pass.
- [ ] Android Kotlin compilation passes.
- [ ] Android emulator verifies date selection, cancel, invalid-window validation, save/reopen, and disjoint confirmation.
- [ ] Swift package tests pass.
- [ ] Apple app target builds on macOS.
- [ ] Apple simulator verifies the same flow.
- [ ] Duration, stage totals, persistence, and logical-day grouping reflect edited endpoints.
- [ ] Screenshots from demo/test data are added before marking ready for review.
