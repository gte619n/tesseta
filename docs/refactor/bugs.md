# Out-of-scope bugs found during the refactor

Logged here per the guardrails; not fixed inline unless they block a slice.

| ID | Found in slice | Bug | Repro | Status |
|---|---|---|---|---|
| BUG-01 | Slice 2 | core-data instrumented tests crashed the whole run: the main manifest's Hilt `ReminderBootReceiver` merges into the test APK (plain `Application`), so an OS protected broadcast throws "Hilt BroadcastReceiver must be attached to an @HiltAndroidApp Application" before test 0. | `./gradlew :core-data:connectedDebugAndroidTest` on an emulator that dispatches BOOT_COMPLETED/package broadcasts. | FIXED inline (blocked the slice) — receivers stripped from androidTest manifest (DEC-10). |
