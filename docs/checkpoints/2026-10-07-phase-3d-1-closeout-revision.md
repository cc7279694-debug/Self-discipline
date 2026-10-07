# Phase 3D-1 — New closeout ordering

## Goal and baseline

Implement only the user-authorized 3D-1 revision, not a replay of already completed Phase 3D. Base `c39f135e09d48e27573dbb331c0bf57a9e075499`; branch `codex/phase-3d-closeout-v2`. Preserve the earlier formal freeze `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18`, its history, existing later-module implementations and Inner Window brand resources.

The new [revision contract](../plans/MIRRA_PHASE_3D_1_CLOSEOUT_REVISION.md) locally supersedes the original 3D-1 flush/cleanup ordering. This revision does not newly implement the attachment's future five-session/60-minute statistics, partial-focus presentation or global history changes; existing later-module implementations are inherited unchanged.

## Implementation

- First tap checks ACTIVE and opens the editable confirmation seeded from durable currentPage, without forced Note save/page retry/end-clock sampling. Continue/Back cancels only the dialog. Normal 500ms autosave and lifecycle flush remain enabled while ACTIVE/Confirming, although editing stays disabled behind the dialog; Saving and durable PENDING still prohibit new ordinary saves.
- Final tap gates concurrent commands, awaits actual pending saves/page outcomes and flushes the latest Note with the same draft ID. A failed Note blocks any new page-repair attempt; the temporary dialog end-page is never passed to updatePage. Failure retains ACTIVE, draft and edited end-page, with a retryable error.
- After successful save, re-read latest progress and totalPages, rejecting explicit lower/upper-bound errors without sampling a closeout clock. Generate one existing ClockSample, then enter the existing monitoring facts boundary for settlement → Stage A → memory invalidation.
- PENDING is irreversible, keeps the occupied slot, closes the final Segment at the durable boundary, and rejects late page/Segment/heartbeat/event/behavior writes via the unchanged guard.
- Release the facts mutex before attempting the existing owned channels/monitor/DND cleanup. Then Stage B completes the original snapshot atomically as NORMAL/COMPLETED. Ordinary cleanup errors/timeouts do not replace a successful result; caller cancellation still propagates after durable compensation. Cleanup is attempted at most once per invocation once closure is established.
- Existing B-failure retry, PENDING-first startup recovery, ACTIVE→ABNORMAL startup path, malformed-state defense, rollback proof and Summary/navigation behavior remain. No second lock/coordinator/repository was added.

## Changed interfaces / files

Production behavior changes are confined to `SessionScreen.kt` (existing SessionViewModel) and `SessionManager.kt` (existing coordinator). `flushPendingEdits(noteFirst: Boolean = false)` preserves normal leave ordering while enabling the closeout Note-first gate. SessionManager public API remains unchanged. `BoundSessionMonitoringController.kt` has a single comment update, no executable change.

Tests: `SessionCloseoutViewModelTest.kt`, `SessionManagerCloseoutTest.kt`, `SessionCloseoutUiTest.kt`, `PhaseOneCorrectionTest.kt`, `ModuleThreeDCloseoutRepositoryTest.kt`, the legacy DND cleanup-order assertion in `ModuleThreeBMonitoredStartRepositoryTest.kt`, and the explicit asynchronous-detail readiness condition in `PhaseOneLearningLoopTest.kt`. Existing tests that encoded first-tap flush now assert the same durable Note at the new final-confirm boundary; business assertions are retained, not skipped or weakened.

## Verification (current execution)

- Before revision: full JVM 318/318.
- RED: targeted old production 38 cases, 19 expected assertion failures. These demonstrate the old confirmation/cleanup contract, not unexplained business/environment failures.
- GREEN: targeted ViewModel 28/28 + coordinator 12/12; subsequent true caller-cancellation test added and covered by the full run.
- First full JVM before review correction: 325/325, 0 failed/error/skipped.
- Internal code review found one Important issue: opening Confirming could suppress ordinary autosave/lifecycle flush. Two behavioral regressions were added first, both RED because no Note was saved; the save eligibility was separated from edit eligibility. Updated targeted ViewModel 30/30 + coordinator 13/13 = 43/43, 0 failed/error/skipped.
- First full connected: 294 discovered, 283 PASS, 2 actual failures and 9 unmet assumptions (4 opt-in fixtures, 5 platform permissions); Gradle exit 1, not a clean PASS. The DND cleanup test encoded B-before-cleanup and was corrected to assert durable PENDING/closed Segment/occupied slot during cleanup, then exact NORMAL/COMPLETED/FULL after B. The legacy learning-loop Summary wait timed out at 5000ms; its cause has not been established.
- Controlled diagnostic rerun actually executed only one LearningLoop test (not the requested second class, because the Windows batch multi-class filter did not select it). Summary and two-Note/progress assertions passed, so the original Summary timeout was not reproduced or declared fixed. A later immediate Detail assertion failed: merged semantics had no Detail current-page text while unmerged semantics did, consistent with the existing asynchronous Room/route loading. The test now waits for both the real Detail page text and its unique full summary in the merged tree, keeping the original assertions and 5000ms limit. No sleep, expanded timeout, unmerged-tree substitute or production navigation change. Temporary diagnostics were removed before the final suite.
- Corrected-tree full JVM: 327/327, 0 failed/error/skipped. The next connected attempt was interrupted, not a full PASS: 27 discovered records, 22 completed PASS, 4 explicit opt-in assumptions and one empty/incomplete `ModuleTwoAFlowTest.activeIntentBlocksPauseAndShowsReason` failure record. Gradle exit 1 / 5m55s. Additional-output extraction and independent `ls /sdcard/Android` both reported `Transport endpoint is not connected`; ADB shell, boot_completed and SystemUI remained available, lastanr empty. This is confirmed emulator shared-storage/FUSE failure, not proof of an ADB transport disconnect or a business assertion defect. No production repair or timeout relaxation. After a normal no-wipe reboot, API37/qemu/boot completed, live SystemUI, readable shared storage and preserved current installation were rechecked before the new full run.
- lintDebug / assembleDebug PASS (fresh execution, exit 0). Lint XML: 0 errors, 9 existing warnings, 1 hint.
- Rebooted full connected completed, exit 1 / 25m43s: 294 discovered, 283 PASS, 2 actual failures, 9 unmet assumptions. All cases reached a terminal report; this is not a clean PASS. The original Summary timeout and old DND ordering assertion passed in this run; the former still has no established root cause. The two failures were immediate merged-node `assertExists` in `SessionCloseoutUiTest`: the upper-bound error and the recreated PENDING heading. Source inspection showed the first wait observed `vm.error` before `restoreAfterFinishFailure()` awaited Room and restored Confirming; the second asserted immediately after setContent although PENDING snapshot loading is asynchronous. Both reports noted a matching unmerged node, which alone does not prove a transient update. The tests now wait for the actual default-merged UI state under the unchanged 5000ms bound, retaining every original error/Back/Note/Room/retry/boundary assertion, without changing production, using unmerged finders or increasing timeouts. The corrected class and another unfiltered full run are required; the prior failed run remains history.
- Readiness class rerun: 7 total, 6 PASS, 1 actual failure, 0 assumptions; exit 1 / 7m30s. PENDING recreation passed, but the upper-bound rendered-state wait still timed out at the unchanged 5000ms. The readiness change is therefore not sufficient proof of a fix. A temporary, single-case state/Text/bounds diagnostic was added to rethrow the original failure and distinguish state loss, persistent merged-tree behavior or delayed rendering; no environment/production root cause is asserted from this result. The diagnostic was removed after the controlled software-renderer single-case run, before the final class/full runs.
- Final fresh unfiltered connected on the software backend: 294 discovered, 285 PASS, 9 unmet assumptions, 0 actual business assertion failures; connected / lintDebug / assembleDebug Gradle invocation exited 0 / 20m20s. HTML test duration 15m8.33s. Do not report 294/294 assertions PASS: HTML raw failures=9 / skipped=0, all nine bodies are `AssumptionViolatedException`; runner reports 294 / 0 failed / 0 ignored. These emitted counts are retained as-is, with the nine unexecuted prerequisite cases listed separately, not silently rewritten as XML skipped=9. Final lint: 0 errors, 9 existing warnings, 1 hint. The JVM 327/327 execution is separate and unchanged.

Key automated scenarios: Note failure/retained temporary page/post-save clock; unchanged draft ID; pending page failure/retry; latest progress revalidation; lower/upper bounds; duplicate final confirmation; truthful Saving; PENDING recreation/Back; A/cleanup/B ordering; failed or timed-out cleanup still NORMAL; cancellation after A; B failure/retry original snapshot; actual Room/controller late-writer race and lock-free cleanup; existing normal/abnormal startup and DND guards.

UI failure/barrier tests use controlled test repositories/clock plus real in-memory Room. They are automation evidence, not a manual full Monitoring/DND platform closure.

### Additional UI diagnostics and environment comparison

- The next single-case diagnostic failed earlier, at the lower-bound error assertion, before reaching the upper-bound diagnostic. It reported `ComposeNotIdleException` / Espresso's existing 60-second idle limit, exit 1 / 9m02s. No upper-bound VM/tree snapshot was obtained, so neither earlier missing-node failure is declared resolved.
- That single-case log also contains approximately 237–249-second rendering stalls in multiple non-Mirra system processes, including the system UI/input stack. Runtime hardware configuration used the host graphics backend. This supports investigating the renderer for that run; it does not prove that every prior assertion failure was environmental.
- The same dedicated AVD was shut down normally and relaunched with a temporary SwiftShader software backend, without wipe, snapshot restore, permission changes or system-time changes. The first relaunch was rejected while the old emulator was still exiting; no second writable instance started. After the old instance exited, the software-backend launch proceeded. This is a controlled environment comparison, not a production repair or a compatibility PASS.
- The upper/lower-bound single-case rerun on the software backend passed 1/1, 0 failure/skipped, Gradle exit 0 / 3m54s (case duration 27.001s), under the unchanged readiness/idle limits and business assertions. No failure diagnostic was emitted. This positive comparison does not establish the earlier merged-tree failures' root cause; the full class and unfiltered suite remain separate gates.
- After removing all temporary diagnostics, the complete `SessionCloseoutUiTest` class passed 7/7, 0 failure/skipped, Gradle exit 0 / 4m20s (test duration 1m44.55s). This covers the real Room-backed Note failure/retry, lower/upper page bounds, precise post-save clock, Saving, PENDING recreation/Back and route defenses. These seven also passed in the final unfiltered run. The earlier failed runs are not relabeled PASS and their exact root causes remain unconfirmed.

## APK / additional smoke

- Final APK: `build/deliverables/Mirra-Closeout-v2-debug.apk`, 16,181,527 bytes; SHA-256 `45C6A488ECB33D58850599B95779C1284C33A6D7E96A7112811730BC092D8696`. It matches `app/build/outputs/apk/debug/app-debug.apk`; build artifacts are local/ignored, not committed.
- Dedicated API37/qemu/boot/AVD identity was checked before any cover-install command. Cover installation succeeded with `install -r`, without uninstall or data clear. Before/after app-owned database/files fingerprints both exited 0, covered 17 current files, and had zero differences. This establishes current-file preservation during this cover install only, not preservation/recovery of the old pre-first-run installed dataset.
- Cold launch returned `Status: ok`, `LaunchState: COLD`; Mirra Start/EmptyLibrary rendered. Additional manual accessibility/interaction smoke is **DEGRADED**: Pixel Launcher and then SystemUI ANR dialogs appeared over the app; selecting Wait did not establish an unobstructed interactive state. The retained last-ANR data identifies launcher/system UI, not a Mirra ANR; available AndroidRuntime log did not contain a Mirra fatal. No production repair, permission/time/network change or claim of a resolved environment root cause.
- Network-off manual smoke was not repeated in this revision (NOT RUN); prior offline records are historical, not this execution. Physical/OnePlus devices were not touched. Local screenshots and raw logs stay ignored; only aggregate, de-identified facts are committed.

## Data and inherited boundaries

Room v4; no Entity/Table/Column/Index/Migration change. Schema 1–4 unchanged:

v4 Room identity hash remains `2ff08bb15376ae09645ec00c373c2f82`.

| Schema | SHA-256 |
| --- | --- |
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

Phase 2 analytics, effective metrics/ReadingRecord, 3A/3B/3C behavior, DND ownership, READY, coverage and all threshold rules unchanged. No permission/Manifest/dependency/resource change. No manual data reset/uninstall, Schema generation or physical-device command; the first run's framework package removal and preservation uncertainty are disclosed below.

## Evidence limits and review

Dedicated API37 AVD boot/qemu/SystemUI healthy, lastanr empty before the run. Original DND access absent, Usage/Overlay default, notifications denied; no permission grant in this revision. Assumption-only platform cases must not be called PASS. No repeated opt-in crash/preservation fixture execution in this scope.

The first full run omitted the existing `android.injected.androidTest.leaveApksInstalledAfterRun=true` command-line option. Framework teardown removed the target package (confirmed by `pm path`), so prior AVD installed-data preservation cannot be claimed for this run. Package removal is established; whether all old data was retained or lost is not verified. No manual uninstall, pm clear, wipe, raw database restore or physical-device action was executed. The omission is retained as an execution error, not a production-code fix; subsequent connected runs explicitly preserve installation. Earlier snapshots are not silently substituted for the unverified prior installed-data state.

The five permission-blocked platform cases and four opt-in fixture cases are NOT RUN in this revision, not business PASS. API23–36 full matrix, full physical/OEM compatibility, TalkBack, release/Play, real hardware power loss and manually changed real system time remain NOT RUN. OnePlus 13T daily-use feedback remains only personal smoke. Historical RED/environment records and local evidence files are retained; the prior AVD's installed preservation markers cannot be claimed intact after the framework teardown described above.

Coroutines timeouts bound cooperative suspend operations, not synchronous Binder preemption. Existing cleanup owners retain release-failure metadata and startup reconciliation; no promise of an absolute Android shutdown latency.

This Phase 3D-1 Closeout Revision is now `accepted / frozen` following the user's independent review, `PASS WITH NOTES`. Do not newly freeze all Phase 3D or implement 3D-2/3/4/Phase4 without separate authorization.

## Independent Acceptance and Limited Freeze

Date: 2026-10-07. The user completed independent review and formally accepted this limited revision with **PASS WITH NOTES**. Accepted implementation HEAD: `085543ffd0b5d03859565577a8ee277036fc50f9`; branch: `codex/phase-3d-closeout-v2`.

Only **Phase 3D-1 Closeout Revision** is `accepted / frozen`. The historical overall Phase 3D formal freeze `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18` and all earlier evidence remain intact; this is not a new freeze of the whole Phase 3D.

### Frozen closeout order and rules

First end tap → open editable confirmation only → final confirmation → save latest Note → revalidate page after successful save → sample the unique end time → Stage A: ACTIVE → PENDING and close the final Segment → immediately invalidate this Session's in-memory behavior → outside the facts lock, best-effort owned intervention / monitoring / DND cleanup → Stage B: NORMAL + COMPLETED → result page.

- A Note save failure leaves the Session ACTIVE, retaining the draft and temporary confirmation page; no end boundary is accepted.
- PENDING means the Session has already ended in business terms and can never resume reading. `closeoutStartedAt` is the sole end boundary; complete / retry use the original snapshot without resampling.
- PENDING cold-start recovery continues normal settlement, never ABNORMAL. A leftover ACTIVE that never reached PENDING still follows the existing ABNORMAL recovery.
- Cleanup failure cannot undo the ended Session fact. PENDING blocks new Break / Allowance / Recovery / Segment / learning facts.
- Room stays v4 with schemas 1–4 unchanged; no new Schema or Migration. Phase 2 analytics, 3A/3B/3C frozen semantics, DND ownership and existing 3D-2/3/4 implementations remain unchanged.

### Accepted evidence and retained notes

- Previously committed implementation execution: JVM **327/327 PASS**; connected **294 discovered / 285 actual PASS / 9 unmet assumptions / 0 actual business assertion failures**. This is not 294/294 PASS. The nine remain four opt-in fixtures plus five permission prerequisites; the raw HTML/runner accounting above is retained without rewriting assumptions as executed assertions.
- lintDebug / assembleDebug PASS are previously committed execution evidence. This documentation-only freeze did **not** rerun Gradle or AVD, perform device operations, or produce new business-test results.
- Additional manual emulator smoke remains **DEGRADED**, due to Launcher/SystemUI ANR obstruction; neither the accepted review nor this freeze converts it into a full manual PASS.
- The first connected execution omitted the keep-installed parameter and the framework removed the target package. Old installed-data preservation remains unverified. The subsequent 17 unchanged current-file hashes prove only current-file preservation during that specific cover install, not preservation of the earlier installed dataset.
- physical/OEM compatibility, API23–36 full matrix, TalkBack, release/Play, real hardware power loss and manual real system-clock changes remain **NOT RUN**. API37 AVD evidence cannot be extrapolated to those environments; OnePlus 13T feedback remains personal smoke only.
- All prior RED results, actual failed assertions, unmet assumptions, environment interruptions and unresolved root causes above remain historical evidence; none is deleted or relabeled by this acceptance.

The existing [revision contract](../plans/MIRRA_PHASE_3D_1_CLOSEOUT_REVISION.md#independent-acceptance) receives the same acceptance status, not a second specification. This task ends with a separate documentation commit and push; no production/test code changes and no re-entry into 3D-2/3/4 or Phase 4.
