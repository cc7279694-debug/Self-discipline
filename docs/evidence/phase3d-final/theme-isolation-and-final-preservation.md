# Theme test isolation and final preservation revalidation

Date: 2026-10-06. Environment: dedicated Mirra_API_37 AOSP AVD, Android 17 / API 37. No physical-device operations. Raw logs, device transport, permission dumps and temporary tools remain local only.

## Scope and accepted baselines

- Accepted image isolation patch: `9cfae55898ad6a1a21b0afb8a943a81599ad9ef8`; retained unchanged.
- Theme patch: `5331661f24e0d1f01a89deeeaaeafbb3d6a3a891`, `test(theme): restore persisted preference after lifecycle test`. Only `MainActivityThemeLifecycleTest.kt` changed; no production or docs in this commit.
- Production, JVM source, Room v4, schemas 1–4, Migration, Manifest and Gradle remain frozen. No Phase 3D freeze is authorized.

## Theme isolation: actual RED and GREEN

The old test wrote BLUE unconditionally in `finally`, overwriting the real installed NIGHT preference. This is a test-harness defect, not a production theme defect.

- RED: the new initial-NIGHT regression actually executed once and failed once, `expected NIGHT but was BLUE`. Its outer cleanup restored the actual original preference even after failure.
- GREEN: capture `themeId.first()` before mutations; NIGHT + real Activity recreate assertions and BLUE/light-status-bar assertion remain inside one `try`; `finally` restores the exact original value, then explicitly asserts restoration.
- Two additional real-installed regressions prepare NIGHT / BLUE separately. Each verifies that the lifecycle checks leave its initial value intact, then restores the outer original value.
- Directed actual execution: 5 executed / 5 passed / 0 failed / 0 assumptions: three lifecycle tests and two independent `AppPreferencesThemeTest` cases. Duration 77.505 seconds.
- Two command-preparation attempts executed no tests (old test APK before compile completed; unquoted comma-separated class argument). Retained locally, excluded from RED/GREEN counts.

## Real preference writer audit

All AndroidTest owners were inspected. No additional non-restoring real-installed preference writer was found.

- `MainActivityThemeLifecycleTest`: repaired exact-original restore.
- `ModuleThreeDDataPreservationTest`: explicit opt-in before real container acquisition; marker captures four original preferences; assertion cleanup restores and verifies them. Ordinary full run does not execute seed/assert.
- `AppPreferencesThemeTest`: independent UUID-named cache DataStore, not installed preferences.
- `TestAppContainer` and DND/profile/other UI fixtures: fake preferences or callback-only state, not installed DataStore.
- MainActivity restoration uses the existing non-persisting destination path; recreating Activity does not change the stored destination.

Read-only host snapshots decode the actual installed preferences file using locally verified AndroidX protobuf fields. Risk hashes reproduce the fixture's sorted-column/type-tag/big-endian protocol and query the live database read-only with WAL, not a stale copied database. Only the four scoped preference values, synthetic JPEG metadata and hashes are reported.

## v2 failure remains failure; exact risk cleanup

The explicit, temporary `risk_cleanup` AndroidTest helper required API37 dedicated-AVD opt-in, evidence key `storage-isolation-v2`, FAILED marker, valid fixture package and no Active Session / Intent. It additionally proved that the risk table excluding that one package already matched the original hash. Removal used only production `focusRepository.removeRiskApp()`.

- Cleanup: 1 actually executed / 1 passed / 0 assumptions.
- Full risk hash before: `22069e34ca338fbe6c468f4b412a1de2c1803cd7ca4eaf51bc59c8e1428d038e` (2 rows).
- Full risk hash after / marker original: `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8` (1 row).
- v2 marker state remains `FAILED_PREFERENCES_RESTORED`, byte SHA unchanged: `b81af0e97b2a2d489928cf530084aa500b532fd3081243dedb04fd47eb3c437c`.
- Business/media/preference frame excluding the removed risk row stayed identical. v2 JPEG remains 818 bytes / SHA `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`.
- The helper was removed before the theme commit and final test APK rebuild; no permanent cleanup endpoint or raw DELETE was added.
- Legacy missing JPEG, ImageAsset and marker remain untouched. Neither legacy nor v2 was reseeded, repaired or retried to manufacture success.

## New v3 baseline

`storage-isolation-v3` is a new independent real-installed-storage fixture, not a retry of v2.

- `data_seed`: 1 actual execution / 1 passed / 0 failures / 0 assumptions, 1.352 seconds.
- Initial marker state: `SEEDED`; SHA `33b6d2db91000c1f24427337644aa54a0f359e78a0d67550028e6f29d4e4ae64`.
- Baseline frame hash: `82c2f7bcea143addbe6362c70335413b2d17453b052f848eec95b215a2afb91d`.
- Real preference baseline: destination `knowledge`, theme `night`, DND `true`, cross-app `true`.
- Pre-seed risk hash: `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8`.
- Seeded full risk hash: `f42506b413e469d67a99efffd4c4ae1f26a5fa205af0127323c9b35cba65968a`.
- JPEG: `images/b8f4a4a9-c114-4ae2-9537-81b2bd964083.jpg`, 818 bytes, SHA `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`.
- Four temporary permissions were granted only after reliable original-state capture, then independently read back. Immediate pre-full snapshot confirmed the seeded preferences, image and risk hash, with no active workflow.

## Fresh final gates

The one authorized unfiltered connected attempt did **not** complete. Gradle exited 1 after 5m34s; final-output collection reported `Software caused connection abort`. No second full run or targeted rerun was attempted.

| Report meaning | Actual result |
| --- | --- |
| Raw XML reported tests / failure nodes / errors / skipped | 21 / 5 / 0 / 0; this is a partial report, not full discovery |
| Completed PASS | 16 |
| Default opt-in assumptions | 4, each explicit fixture prerequisite |
| Remaining empty failure node | `ModuleTwoAFlowTest.createNoteDoesNotPersistBlankPlaceholder`, 120.410s, no assertion stack/message |
| Non-assumption test entries | 17: 16 PASS plus 1 interrupted/undetermined entry; not 17 PASS |
| Historic PhaseOne timeout cases | Not reached; neither a new PASS nor the specified recurring-timeout STOP condition |
| DND3 / channel5 platform tests and lifecycle tests in this full run | Not reached; directed theme5/5 remains separate evidence |

At 03:25:57–58 UTC, local per-test logs show system-component fatal exceptions, followed by the instrumentation thread's JNI `NewStringUTF` abort with pending `DeadSystemRuntimeException / DeadSystemException`. A subsequent read found the Android `activity` service unavailable; it later reappeared without a reboot or reset. These are observed environment/runtime facts, not a proven Mirra business assertion failure or a permanent root-cause diagnosis. Full logs and partial XML are retained locally only.

The automatically captured immediate post-run snapshot, **before any install**, matches the pre-run snapshot exactly: v3 JPEG path/818 bytes/SHA, knowledge/night/true/true preferences, full risk hash and SEEDED marker byte hash. This proves preservation through this **partial interrupted attempt**, not through a complete clean suite.

The incomplete full run blocks `install-r`, v3 `data_assert`, fresh final JVM/lint/assemble, exact final APK, offline/visual final gates and 3D-4 completion. No expected values were relaxed, marker rewritten, fixture reseeded or tests repaired to obtain green results. v3 remains an unconsumed SEEDED marker, not an assertion PASS.

### Exact restoration after interruption

Four original permission states and network were restored and read back: Usage/Overlay default, DND access=false, POST=false with original flags, Wi-Fi/mobile-data=1/1. A transient launch attempt during system-service recovery returned activity-not-found; package metadata later confirmed the installed debuggable 0.1.0/code1 package and MainActivity resolver remained present. No reinstall of the production candidate was used to address it.

Under the user's section 16 restoration authorization, a second local-only opt-in helper required `validation_restore` plus the exact v3 key. Before execution, Active Session / Intent / Segment were confirmed 0/0/0. It restored only marker.originalPreferences through the production preference repository and removed only marker.fixture.riskPackage through `focusRepository.removeRiskApp()`. Before removal, the whole risk table excluding that one row equaled the original hash; after removal, the full table equaled it. The helper asserted exact preference readback, unchanged business/media frame excluding those two intended changes, and unchanged marker bytes.

- Restoration-only helper: 1 actual execution / 1 passed / 0 assumptions, 1.036 seconds. This is **not** `data_assert` or preservation PASS.
- Original preferences: knowledge / blue / false / false.
- Original full risk hash restored: `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8`, one original row.
- v3 remains SEEDED with SHA `33b6d2db91000c1f24427337644aa54a0f359e78a0d67550028e6f29d4e4ae64`; its chain is interrupted and unconsumed. The restored settings are intentionally no longer its seed preference baseline. Do not consume/reseed this marker to manufacture success.
- v2 remains FAILED_PREFERENCES_RESTORED with its original SHA; legacy marker SHA remains `6bbd53c86d2cd35dd1b99e74af79e28925c9335cce45e9c73ffc1de54a7fd445`. The old missing JPEG remains missing; no old row or image was repaired or deleted.
- Final v3 JPEG remains the exact same relative path, 818 bytes and SHA; no Active Session/Intent/Segment, monitoring FGS, Overlay or intervention notification. Mirra-owned rule STATE_FALSE; Zen OFF.
- Both one-time helpers were removed from source. The originally verified theme-patched test APK was restored; no restoration entry point is retained in the installed test package or Git.
- Final Room/schema readback: version4 and all four exported hashes match the freeze, including v4 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`.

**Stop: environment/runtime interrupted the full suite. Phase 3D-4 remains incomplete and Phase 3D is not frozen.** The theme patch and precise cleanup have actual evidence; a complete clean full suite and subsequent new preservation/final delivery gates remain outstanding. No physical devices, resets, uninstall, data clearing, system-time changes or production repairs were used.

## Retained limitations

Historical image loss, v2 preference failure, earlier full-suite timeouts, command errors, and the observed-once / not-reproduced Recovery anomaly remain in the earlier evidence. This patch does not repair old images, modify production or claim Recovery root cause resolution.

API23–36 full matrix, OEM/full physical compatibility, TalkBack, release/Play, real hardware power loss, real manual system-time change, real OS query-gap injection and actual 15-minute Deep Focus remain NOT RUN. API37 results do not imply PASS elsewhere. OnePlus 13T daily use is not compatibility acceptance.
