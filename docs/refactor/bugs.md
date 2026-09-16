# Out-of-scope bugs found during the refactor

Logged here per the guardrails; not fixed inline unless they block a slice.

| ID | Found in slice | Bug | Repro | Status |
|---|---|---|---|---|
| BUG-01 | Slice 2 | core-data instrumented tests crashed the whole run: the main manifest's Hilt `ReminderBootReceiver` merges into the test APK (plain `Application`), so an OS protected broadcast throws "Hilt BroadcastReceiver must be attached to an @HiltAndroidApp Application" before test 0. | `./gradlew :core-data:connectedDebugAndroidTest` on an emulator that dispatches BOOT_COMPLETED/package broadcasts. | FIXED inline (blocked the slice) — receivers stripped from androidTest manifest (DEC-10). |
| BUG-02 | Slice 3 | `MedicationController.update` and `changeDose` append a UUID-keyed `MedicationHistory` row per call with no dedupe, so a replayed PUT/POST (offline outbox) duplicates the medication **history log** even though the medication document itself converges (SET_SEMANTICS). | Log a med, edit it offline, let the outbox replay the PUT after a lost response → two identical history rows. | OPEN — needs a deterministic history-row id derived from the mutation; out of scope for slice 3 (primary resource is safe). |
