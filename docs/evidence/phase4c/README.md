# Phase 4C evidence index

Execution window: 2026-10-08–09 (Asia/Shanghai). Branch `codex/phase-4c-full-backup`; authorized baseline `3579cb74f327b6e243771285d901f639452bcd07`; implementation/tests `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f`. Final execution gates are complete: **awaiting independent review**, not acceptance/freeze. This separate validation-document commit is identifiable from the current branch and delivery report.

See the [single delivery checkpoint](../../checkpoints/2026-10-08-phase-4c-full-backup.md) for contracts, RED history, limits and final-gate status. No raw DB/archive, private note/image, serial, full logcat or dumpsys is committed here.

## Actual installed UI screenshots

Real screenshots from the dedicated API37 AVD with controlled synthetic data, not generated concepts, fixture-only Compose captures, OEM proof or coverage of every layout. Visual inspection of all four found only generic UI, synthetic counts/settings and creation time; no private text or device identifiers.

| Image | Actual UI step |
|---|---|
| [01-data-management.png](01-data-management.png) | Mine's formal Data Management route, privacy warning and backup/verify actions |
| [02-validated-backup.png](02-validated-backup.png) | Actual SAF-selected validated package; format 1/schema 4, one synthetic item/note/image, current data unchanged |
| [03-confirm-replace.png](03-confirm-replace.png) | Separate full-replacement/no-merge confirmation and safety-snapshot statement |
| [04-restored-result.png](04-restored-result.png) | Newly selected application generation reopened after restore; success message on Mine |

## Executed evidence available

- Formal installed SAF UI: create/save, export cancel, synthetic mutation, import cancel without change, validate chosen file, explicit replacement and success.
- Four opt-in invocations (prepare, mutate, cancelled-import assertion, restored assertion) actually passed. Restored frame covers all columns/IDs of 12 authoritative tables, JPEG bytes, four preference values/presence and search. Safe original-baseline cleanup also passed 1/1, verified the full original frame and removed only that run's cache.
- Android snapshot 14/14; archive JVM 13/13; search-budget JVM 4/4; preferences JVM 5/5; latest Journal JVM 9+2 with zero failure/error/skipped.
- Direct Android run 24/24 actual PASS: service 5, reader lifecycle 2, Data Management Compose 9, navigation 1, Camera 3, Android Journal 4. Comma-filtered Gradle XML selected only one class and is not reported as 24 tests.
- Android Journal loops 11 injected-death boundaries using real Room/DataStore/JPEG/fsync: 9 OLD, 2 NEW. In-process injection is not external force-stop or physical power loss.
- Larger Android test: 1,000 synthetic Notes / all 12 tables / JPEG / preferences / FTS. No maximum-size performance claim.
- Permanent reader epoch targeted 2/2 actual GREEN after the stale-transaction helper reopening was reproduced twice.
- Sandbox external process matrix: 11 prepare → force-stop → cold-start → assert cycles, 22/22 actual PASS / zero assumptions. This is sandbox recovery, not production bootstrap.
- Installed production bootstrap: precommit images-published → OLD and COMMITTED → NEW, 4/4 actual prepare/assert invocations. Assertions wait for Application's own early journal recovery, check all facts/media/preferences/FTS, then safely restore the original installed resources and remove only fixture cache.
- Intermediate pre-correction unfiltered JVM 542/542, zero failure/error/skipped; final post-bootstrap/cache-correction unfiltered JVM **553/553** with zero failure/error/skipped. Lint 0 errors / 15 warnings / 1 hint (six newly recorded warnings); debug and test APK builds PASS. The real cache pixel test failed red before the correction and passed blue afterwards, 1/1 actual Android GREEN.
- Final corrected APK: production installed OLD/NEW bootstrap **4/4** actual invocations again passed; the original complete raw resources were restored. Final offline formal SAF export/cancel/mutation/import-cancel/validated confirmed restore plus baseline cleanup **5/5** actual fixture invocations passed, with actual local document-picker interactions and active default network `none`.

## Final actual execution record

| Evidence | Status |
|---|---|
| Full JVM | **553/553 PASS after final corrections; 0 failure/error/skipped** |
| Full connected actual vs assumptions | **397 discovered / 383 actual executed and PASS / 14 unmet assumptions / 0 actual business failure/error; one unfiltered Gradle success, 19m28s** |
| Production installed bootstrap OLD/NEW after process stop | **4/4 actual invocations PASS; original resources restored** |
| External sandbox process-stop cycles | **22/22 actual PASS; 11 boundaries; 0 assumptions** |
| Final offline SAF round-trip and baseline restoration | **5/5 actual invocations PASS; original frame checked** |
| lint / build | **PASS; lint 0 errors / 15 warnings / 1 hint** |
| Cover install / normal and offline smoke | **PASS; 16 current file hashes unchanged before launch; normal/offline cold start and Start–Knowledge–Mine observed; network restored 0/1/1** |
| Final APK path / bytes / SHA-256 | **`build/deliverables/Mirra-Phase4C-debug.apk`; 17,258,080 bytes; `877E5E521C363E8EEF719F4C75B158FA336E3CABEF2A584CFC8ED1180548AA35`** |
| Post-suite quiescence / formal guard | **PASS: no Journal/monitor/Overlay/active notification/Mirra-owned rule record; formal preparation → CreateDocument → cancel succeeded, ordinary App reopened** |
| Implementation/validation commits | **Implementation/tests `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f`; this validation/evidence commit. Exact final remote/local HEAD and clean tree are in the delivery report, not substituted for the production SHA** |

Room v4, schemas 1–4/migrations unchanged. Actual v4 **schema file SHA-256**: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` (not Room identityHash).

## Limits

API23–36 / full OEM / full physical matrix / OnePlus 13T / TalkBack / release-Play / real power loss / manual clock change remain **NOT RUN**. Historical RED, environment failures, assumptions and preservation limits remain in the checkpoint. SAF readback is not provider durability; controlled low-space tests are not physical exhaustion; API37 is not Android-all-versions PASS. No physical user device was operated.

Final navigation was interrupted by a hanging dedicated AVD after the successful restored-frame/baseline assertions. Only the verified dedicated emulator process pair was restarted, using unchanged userdata and no snapshot/wipe/uninstall. After healthy boot, all three normal/offline entries and network restoration were actually verified again. A later execution-session reset terminated the full connected attempt and emulator/Java processes without a complete XML; that attempt is incomplete environment evidence, not PASS or an identified business failure. The same AVD was restarted with unchanged userdata and normal Mirra/SystemUI response verified before the fresh unfiltered run. Its complete **397/383/14** result above is final post-correction evidence; the earlier **396/382/14** remains intermediate.

The final 14 XML failure nodes are AssumptionViolatedException, not actual business failures or PASS: nine opt-in methods and five DND/Overlay/Notification permission prerequisites. Five 4C opt-in methods were actually executed separately in the recorded product/process journeys; four historical 3D opt-in methods and five permission cases were NOT RUN in this full run. Native skipped XML nodes are zero; that encoding does not erase the 14 unmet assumptions. All 397 testcase names are distinct. The final ordinary suite's real assertions include the restored-image pixel regression and storage/Room/UI tests.
