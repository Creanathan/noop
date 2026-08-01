# Issue #970 validation record

Issue: [Editing sleep log time and DAY](https://github.com/ryanbr/noop/issues/970)

## Scope validated

The change is limited to the sleep endpoint editor:

- Apple wake editing now preserves an explicitly selected date and time.
- Android wake editing now selects and stores an explicit date and time.
- Existing endpoint validation, disjoint-window confirmation, atomic Save behavior, and repository persistence APIs are unchanged.
- No schema, migration, networking, or device-protocol changes were made.

## Static validation completed

- `git diff --check` passed.
- The final commit contains only the four intended source/test files for the fix:
  - `Strand/Screens/SleepView.swift`
  - `android/app/src/main/java/com/noop/ui/SleepScreen.kt`
  - `android/app/src/main/java/com/noop/ui/SleepTimeEditDraft.kt`
  - `android/app/src/test/java/com/noop/ui/SleepTimeEditDraftTest.kt`
- No references remain to the removed derived-wake APIs (`resolvedWake` or `withWakeTime`).
- The Android regression tests cover explicit wake dates, including a later date, and invalid wake-before-bed input.
- Commit: `ed6ab9a7 Allow editing sleep wake dates`

## Automated validation blocked by this Windows environment

The focused Android test command was attempted:

```text
cd android
./gradlew testFullDebugUnitTest --tests "com.noop.ui.SleepTimeEditDraftTest"
```

Gradle downloaded successfully, but the build stopped before compilation because no Android SDK is configured:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
or by setting the sdk.dir path in android/local.properties.
```

No Android SDK was found in the standard local directories, and no `ANDROID_HOME` or `ANDROID_SDK_ROOT` variable is set.

Apple package and app-target builds were not run because this validation host is Windows and does not have Xcode/macOS toolchains.

## Release validation required on macOS/Android-capable host

1. Configure JDK 17 and Android SDK platform/build tools required by `CLAUDE.md`.
2. Run the focused Android test, Kotlin compilation, and full Android unit suite:

   ```bash
   cd android
   ./gradlew testFullDebugUnitTest --tests "com.noop.ui.SleepTimeEditDraftTest"
   ./gradlew compileFullDebugKotlin
   ./gradlew testFullDebugUnitTest
   ```

3. Run the Swift package tests and compile the Apple app target:

   ```bash
   cd Packages/StrandAnalytics
   swift test
   cd ../..
   xcodegen generate
   xcodebuild -project Strand.xcodeproj -scheme Strand \
     -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build
   ```

4. On an Android emulator and Apple simulator, use demo/preview data or a backed-up test database and verify:
   - A record such as July 28 22:30 → July 29 07:30 can be corrected to July 29 02:30 → July 29 07:30.
   - A wake date selected several days later remains the selected date after Save and reopen.
   - An end before the start cannot be saved.
   - Cancel discards date and time changes.
   - Disjoint-window confirmation still appears.
   - Duration, stage totals, persistence after refresh, and logical-day grouping reflect the edited endpoints.
   - At least one non-UTC timezone and one daylight-saving transition are checked.

A clean result from those host/device gates is required before calling the change release-ready or submitting it upstream. This Windows validation is intentionally reported as incomplete rather than implying that platform builds or device behavior were verified.
