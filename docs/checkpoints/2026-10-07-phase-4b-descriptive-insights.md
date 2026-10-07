# Phase 4B — Descriptive Insights

Status: Phase4B **complete / awaiting independent review**. Task4 Fresh AVD classification: **VALIDATION_RECOVERED**. Not accepted/frozen; no Phase4C. Date: 2026-10-07. Earlier failed Gates below are retained historical records, not overwritten.

## Goal and inherited baseline

Same-book sustained effective reading pace change, shown only when noteworthy in existing Learning Item Detail. Parent Phase 4A Freeze: `a57a7807b7acd0e1d7b8e49f29ef84d639a471b1`; 4A production `e799997002d7c8d4dbb833c7452d194e8bad5d9f`, validation `614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693`. See [approved execution contract](../plans/MIRRA_PHASE_4B_DESCRIPTIVE_INSIGHTS_PLAN.md).

## Approved rules for this delivery

90 local natural days under one now/zone snapshot; newest three qualified, positive-page/effective-focus NORMAL COMPLETE_TRUSTED samples all within 30 natural days and ≥30min together. Next at most ten qualified baseline samples, ≥5 and ≥60min; no overlap or cross-book data. Weighted sum-pages/sum-focus ratio ≤0.70 / ≥1.30 plus all three individually below/above baseline gives slower/faster. Other results hidden. Relative change rounds to nearest5%; >100% is not rendered as a precise percentage. No cause or subjective quality inference.

Frozen qualification, trust, Phase 2/3D/4A and existing navigation are reused. Independent 90-day insight subscription delegates the **same existing loader implementation**; existing 30-day effective subscription remains unchanged. Two subscriptions have separate bounded initial query budgets, not a claimed shared three-query total. No new loader, table, preference key, index or migration.

## Verification evidence

### Task1 — pure domain

Commit `f46b5a7679e52c084a55e72739f27af3343639cf` (`feat(insights): add reading pace change rules`). Public entry `ReadingPaceInsightService.analyze(itemId, EffectiveReadingSource, AnalyticsTimeContext)` reuses frozen qualification and exact timestamps; exposes optional insight/reason, readonly recent/baseline evidence, weighted ratio, nearest5% numeric projection and >100% flag. BigInteger cross-products preserve exact 0.70/1.30 and strict individual comparisons; checked Long sums reject overflow. No Chinese copy in domain.

- Behavioral RED:38 discovered /34 expected failures (compiled stubs).
- First GREEN:38/38.
- Association RED:47 discovered /3 expected failures (orphan keys, duplicate Intent association).
- Next run:46/47, one invalid zero-page fixture retained its copied Intent ID. Only fixture identity corrected; no assertions weakened. This failed attempt is retained, not rewritten as GREEN.
- Final focused GREEN:47/47,0failure/error/skip.
- Full unfiltered JVM:421/421,0failure/error/skip; root independently parsed all XML totals. Run log `task1-full-jvm.log` remains local under ignored `.gradle/phase4b-validation`.

### Task2 — bounded source

Commit `fd7b86d93a2d22fa90de646536540ba1f39c87fe` (`feat(insights): add bounded pace insight source`). `ReadingInsightRepository.observeForItem(itemId, time): Flow<ReadingPaceInsightResult>` delegates frozen effective source, local midnight today−89 through captured now; classification runs upstream on Default dispatcher. Errors propagate for UI isolation, not raw error display.

RED:1 actual expected failure in trusted8-session cohort against compiled unavailable skeleton. First GREEN8/8; callback broadened to count all relevant business SELECTs and assert off-main, then final8/8 actualPASS,0failure/error/skip/assumption. Root parsed retained final XML. Canonical final run log/XML and minimal raw performance logs remain local ignored; first raw performance logcat was replaced by the final run, not claimed retained.

| In-window Session IDs | Initial business SELECTs |
| --- | --- |
| 0 | 1 |
| 800 | 3 |
| 801 | 5 |
| 1601 | 7 |
| 10001 | 27 |

Nonempty budget `1+2*ceil(N/800)` for this **one subscription**; existing detail analytics have additional independent subscriptions. Child binds≤800; 801-ID Context-only or Segment-only invalidation each reloads its2batches, no parent/opposite-table SELECT. Table-level invalidation is not per-row. Parent mutation budget not separately measured here.

Canonical10001-session read+materialization+qualification+classification:1737/1086/1705ms,27SELECTs each;100older facts excluded; all counted queries off main. Synthetic API37 AVD, not an OEM/production guarantee. O(N) matching facts, not constant-space or13rawrows. Large-fixture runTest budget matches existing3minute fixture tests; each collection has60second real timeout; first run had passed before that budget alignment.

Room covers NORMAL/FULL/trusted, PARTIAL/NONE/ABNORMAL/missing/gap/overlap/UNMONITORED/future/zero-page/other-book exclusion; stable timestamp/ID ordering and90-day exact bind bounds. All18seeded history rows remain stored. No schema/index/frozen-file changes.

### Task3 — existing Learning Item Detail

Production commit `5cbff38853c069b02772b6062d5a7e96a1f9fb13` (`feat(knowledge): show descriptive pace insight`), seven source/wiring/test files. Existing detail gets an optional neutral three-text section after reading analytics and before history. No Card, icon, action, new route or Start/Mine changes. Exact recommended slower/faster descriptions use the domain's rounded percentage; raw changes above 100% display “超过 100%”.

The independent insight Flow uses the same captured now/zone as the existing base generation; emits null before loading, clears stale insight on refresh (including the same clock value), rejects mismatched generations and rethrows cancellation. Repository construction, source reads and upstream classification errors are isolated from the base detail. Existing 30-day effective/history constructors and query contracts are preserved; production and test containers explicitly inject the thin insight repository.

- JVM behavioral RED:10 discovered,2 pass,8 expected assertion failures; compiled placeholder, not a compile failure.
- Focused GREEN:18/18 (10 new ViewModel +8 unchanged effective-pace contracts).
- Full unfiltered JVM:431/431,0failure/error/skip. Root independently parsed XML. The subsequent change only strengthened an Android navigation assertion; production/JVM files unchanged after this run.
- Initial Android test compilation used an incorrect positional context constructor, corrected to named fields; not counted as behavioral RED.
- Initial Compose RED was **incomplete**:8 XML entries/failures, including expected missing text, a placeholder-related loading timeout and environment interruption. SystemUI/wmshell fatal, missing activity service, ADB offline/transport errors are retained, not treated as eight completed business failures. A data-preserving reboot did not restore it; only the verified dedicated AVD process was restarted without snapshot load/save, wipe, clear, uninstall, permission changes or physical-device operations. This did not establish an installed-data hash preservation chain.
- Subsequent clean Compose RED:1 slower expected assertion failure and a separate1 faster expected assertion failure. A multi-method filter selected only the first method; not claimed as three executions.
- First complete new UI-class GREEN:10/10; final complete-row navigation rerun:10/10,0failure/error/skip,exit0. No changes followed the final run.

Six semantic viewport cases cover320/360/411dp × fontScale1/2 with a long book name, slower→faster live updates, hidden/loading/failure, scrolling and actual Notes/Back callbacks. Existing Start/Notes/Back targets measured≥48dp. A real in-memory Room/container route seeds8 trusted Sessions, opens Knowledge→existing Detail and uses system Back to return; full rows across Learning Item/Intent/Session/Context/Segment match before/after, no Active Session created. This is controlled automation, not an installed nonempty manual journey or pixel screenshot. No Task3 screenshot captured. Final test logs/XML remain locally under ignored `.gradle/phase4b-validation/task3-*`.

### Task4 — initial failed Gate (retained history)

Fresh full unfiltered JVM:431/431 actualPASS,50 XML classes,0failure/error/skip. First full unfiltered connected:exit1,21m29s,351discovered across62classes,341actualPASS,9unmet assumptions (4 opt-in/5 permission),1 unchanged lifecycle assertion failure. Raw XML failures=10/errors=0/skipped=0; assumptions are not business PASS. The failing `SessionCloseoutUiTest.savingBeforeDurablePendingDoesNotClaimReadingEnded` reaches `ActivityScenario.close()` in its finally block and reports requested DESTROYED/last PAUSED; all18new4B Room/UI cases pass. This full run is **FAIL**, not full PASS assembled from focused runs.

Retained per-test timeline shows PAUSED, about75s system_server ActivityTaskManager lock contention/long Binder calls, then STOPPED/DESTROYED and teardown failure. No FATAL/ANR/DeadSystem indicator in that test log. Correlation is not a confirmed environment root cause; no frozen code/test/timeout changed. At this failure boundary, diagnosis and lint/build were still pending; their subsequent results are recorded separately below. The finally exception can obscure an earlier exception, so this failure does not itself prove preceding business assertions passed. Internal whole-production-diff review found no code issues, but explicitly did not treat this runtime Gate as passed.

Standalone lintDebug/assembleDebug/assembleDebugAndroidTest:exit0,2m58s. Lint XML:0errors/9existingwarnings/1hint. Post-build164baseline frozen files and Schema1–4 hashes match; productionHEAD remains `5cbff38853c069b02772b6062d5a7e96a1f9fb13`. Same-code, same healthy AVD, one unchanged focused lifecycle diagnostic:1/1 actualPASS,0failure/error/skip,22.307s; no restart/grant/code/test/timeout changes. Its PAUSED→STOPPED→DESTROYED path completed; about1.151s system-window contention was observed. This does **not** supersede the failed full suite or confirm a root cause. Root authorized exactly one subsequent complete unfiltered attempt, not unbounded reruns.

That retry:exit1,15m9s; **incomplete/aborted**, one aggregate XML contains18suites and72recordedtestentries, rawfailures6/errors0/skipped0. Classification:

Times below are device-log labels; timezone was not independently verified.

| Retry recorded outcome | Count | Evidence boundary |
| --- | --- | --- |
| Actual completed PASS | 66 | Each nonfailure record matches its own per-test finished marker; not a complete351-test full-suite PASS |
| Unmet opt-in assumptions | 4 | NOT RUN; not business PASS |
| Completed actual assertion failure | 1 | Existing `ModuleTwoAFlowTest.learningItemCanPauseResumeAndCompleteWithoutRestoringMainline`, line53 |
| Empty interrupted failure node | 1 | Existing `ReadingRecordNavigationTest.summaryExpandsInlineAndDoneReturnsStartWithoutRestoringSession`; no completed exception body |

The completed assertion expects “状态：进行中” in the merged semantics tree; diagnostic text says one matching node exists in the unmerged tree. This is an actual test failure, not proof that persisted LearningItem status is wrong, and not proof of an environment-only issue. The aborted record must not be counted as a second completed business assertion.71of72records have per-test finished markers (66PASS+4assumptions+1actualassertion); the empty failure has none.279intendedcases have no retry record; no final run-finished summary was captured, and previous summaries are not reused.

Retry-window device logs at12:31:26 retain Mirra-process JNI `NewStringUTF called with pending exception ... DeadSystemRuntimeException / DeadSystemException`, plus AndroidRuntimeFATAL on `pool-7-thread-1` and `wmshell.main` with “The system died”. This is a new observed interruption, not merely inherited history. The earlier completed ModuleTwoA assertion occurred at12:23:21; the later system failure does not establish that earlier assertion's root cause. Exact logs remain local/private.

Gradle also reports `Failed to list ... additional_test_output ... Transport endpoint is not connected` during `/sdcard/Android/media/com.guanyi.mirra/additional_test_output` collection. This exact file endpoint error alone does **not** establish ADB offline/transport disconnection or a product root cause. Post-run readonly target check still reports online and logcat can be read, but SystemUI pid is absent. The later health log explicitly repeats `Since 'activity' could not be found` and `Could not find 'aidl/activity'` at12:32:49onward. A later bounded capture without newFATAL/DeadSystem entries does not prove the retry was healthy or that activity was present. These observations are not a confirmed causal explanation of the complete assertion/abort chain. No environment recovery or mutation was attempted.

Retry's new UI class10/10 has nonfailure records, but new Room8, all five permission-prerequisite cases and the original failed Closeout method were not reached. New Room/UI18actualPASS are established by the **first** complete run (and focused runs), not assembled into a retry full-suite claim. First351-entry FAIL, unchanged focused1/1, and incomplete72-entry retry are retained as three distinct runs. No code, assertion, timeout, permission, fixture or environment setting changed between the complete attempts.

**Gate decision at that original stop: BLOCKED.** Further Gradle/ADB mutations, focused reruns, environment resets and code repairs stopped. Only readonly diagnostics/post-check and accurate documentation continued. There was no `PHASE_4B_COMPLETE_AWAITING_REVIEW` declaration, final validation commit or Push at that boundary. Independent diagnosis had to decide a new bounded verification or authorized minimal repair; internal source approval did not settle the runtime failures. Separately authorized later attempts and the recovered current Gate are recorded below.

## Data gate

Pre-implementation: Room version4; Schema1–4 file hashes match parent.

| Schema file | SHA-256 |
| --- | --- |
| 1.json | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2.json | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3.json | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4.json | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

These are **schema file SHA-256**, not Room identityHash.

Post-build check repeats all four hashes against the parent freeze. Room version4 and Migration1→2→3→4 remain unchanged. Complete enumeration checks164 existing frozen production/resource/schema/build paths, excluding only the three intentional existing production wiring/detail files; all164 have empty parent→productionHEAD diffs. No Entity/Column/Index change, persistent insight table or preference key. Existing frozen repositories, EffectiveReadingService, SessionTimelineValidator, Phase2/3D/4A, ReadingRecord, Closeout, monitoring/READY/FGS/DND, Recovery/Stable/Deep and navigation sources remain unchanged. This is source/schema evidence, not an invented installed production-DB inspection.

## Earlier build artifacts (pre-stabilization)

Standalone assembleDebug and assembleDebugAndroidTest succeeded against the implementation at that earlier boundary; already-current output tasks were UP-TO-DATE, not claimed as a clean rebuild. APKs were local outputs, not Git content.

| Absolute path | Bytes | SHA-256 |
| --- | --- | --- |
| `C:/Users/CDD/Documents/ChatGPT/Mirra/app/build/outputs/apk/debug/app-debug.apk` | 16319720 | `61229A0E0C49855A4D9D67F400711993422F42C1B424F2FDFDA83C0AAD7D7E86` |
| `C:/Users/CDD/Documents/ChatGPT/Mirra/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2442162 | `A803D450A5C8936A5A8FE737E013FAA234A1E9A153480BBED695B4A3058D262D` |

## Local evidence index and review boundary

Canonical execution records remain in ignored `.gradle/phase4b-validation/`, not uploaded raw logs. The sanitized checkpoint and execution/code mapping plan were retained **uncommitted** at the original blocked stop; no final evidence commit was claimed then. This authorization publishes the complete history and recovered Gate as a separate validation-document commit. Device identifiers, private logcat, full dumpsys and user data are not committed.

- `task1-*`: domain RED/GREEN and full421 JVM; `task2-*`: final8 Room cases, query budgets and synthetic timing.
- `task3-*`: ViewModel RED/GREEN, full431 JVM, incomplete environment-interrupted Compose RED, separate clean RED and final10-case UI/navigation XML.
- `task4-full-jvm-results/`: fresh50-class/431-test full JVM; `task4-full-connected-results/`: first full FAIL retained separately, one aggregate XML containing62 suites/classes.
- `task4-lifecycle-focused-diagnostic-results/`: only-method1/1 diagnostic; never read as a full suite result.
- `task4-full-connected-retry-results/`: the one authorized unfiltered retry, incomplete72-entry FAIL; kept separate from first complete351-entry FAIL.
- `task4-lint-results-debug.xml`, `task4-*-schema-hashes.log`, `task4-*-frozen-diff.log`: actual lint issues, hashes and complete frozen-path gates. Raw per-test/device logs stay private/local.
- `task4-final-*-diff.log`, `task4-final-schema-hashes.log`, `task4-final-apk-hashes.log`: historical post-retry164-path/source/hash checks, all unchanged at that boundary. Both APK byte lengths and hashes matched the earlier artifact table then; the later test-only stabilization APK is recorded separately below.

Internal Task1/2/3 reviews approved their actual diffs. Whole production range `a57a780..5cbff38` source review found no blocking or minor finding, but kept runtime validation as a separate Gate. These are Codex internal checks, **not** the user's independent ChatGPT review; no accepted/frozen declaration is made. Root independently parsed first/focused/retry XML nodes and retained assumptions, actual failures and interruption separately.

## Original Git and blocked stop point (historical)

- Branch: `codex/phase-4b-descriptive-insights`, parent freeze `a57a7807b7acd0e1d7b8e49f29ef84d639a471b1`.
- Local implementation commits: `f46b5a7679e52c084a55e72739f27af3343639cf`, `fd7b86d93a2d22fa90de646536540ba1f39c87fe`, `5cbff38853c069b02772b6062d5a7e96a1f9fb13`; production HEAD is the third commit.
- Task4 validation commit: **not created**. Phase4B branch: **not pushed** as a completed delivery; no local/remote equality is asserted.
- Working tree: not clean, containing only this new checkpoint, new4B execution plan and modified `docs/CURRENT_STATE.md`; implementation/test files remain committed and unchanged after validation.
- Stop for independent failure/diagnostic review. No merge/main/release/self-freeze/4C; no abandoned or overwritten user work.

## Known limitations and evidence boundary

- API23–36 full matrix, full OEM/physical matrix, TalkBack, release/Play, real power loss and manual real-system-clock modification remain NOT RUN. No OnePlus13T operation; personal daily-use feedback is not compatibility PASS.
- Historical Launcher/SystemUI ANR, Recovery >120s observation, earlier installation-framework removal and limited cover-install preservation evidence remain in inherited checkpoints; not silently erased or resolved here.
- 4A O(N) scalar median, fixed-cutoff history pagination (not a long-lived SQLite snapshot), cohort scans/temp sorts and non-isolated cumulative jank notes remain unchanged.
- New installed-data before/after hash chain, installed/nonempty manual 4B journey and inspected 4B pixel screenshots: NOT RUN. Preservation installation flags are configuration, not evidence that private files were hash-verified. The controlled in-memory Room/Compose route and six semantic viewport cases do not establish an installed manual or pixel journey.
- Internal task review is not the user's independent ChatGPT acceptance. The original full connected Gate was blocked; the separately authorized recovered Gate below permits complete / awaiting independent review, not accepted/frozen. No Phase4C starts here.

## Later forensics and test-only stabilization — retained history

After the original Run A and Run B, a separately authorized forensics turn published the existing production candidate `5cbff38853c069b02772b6062d5a7e96a1f9fb13` without the three dirty validation documents. Unchanged Run A and Run B exact methods each passed3/3 targeted; this did not replace either original failed full. An initial `log=true` runner probe was a dry run, not business PASS.

The **Forensics Fresh Full** was on the old `Mirra_API_37_Final` userdata, not the new AVD used below. Runner declared351; `ExternalInterventionSessionUiTest.staleRequestDoesNotOpenReasons` failed its expected book-title assertion under an observed healthy platform. The separate bookName Flow's initial blank value was a possible missing test readiness precondition, not captured proof of a production bug. The full was stopped. Host cancellation did not stop instrumentation; the initial21starts/20finishes/19PASS collector snapshot was provisional. Final device-stop-bounded counts:27started/26finished/25actualPASS/1actualfailure/1unfinished/324notstarted,0assumptions,0finalXML, no run-finished summary. The unfinished insight UI case was intentionally stopped, not another observed business failure. This **VALIDATION_BUSINESS_FAILURE** history remains; no full PASS was assembled from targeted results.

Approved test-only stabilization `f000ca03d5eb03646d76f199fe2cb899979dfe68` changed only `ModuleTwoAFlowTest.kt` and `ExternalInterventionSessionUiTest.kt`: wait for exact async UI status/bookName within existing5000ms waits, then retain all original business assertions. No sleep, retry rule, timeout increase, unmerged-tree workaround or production change. The Closeout lifecycle test remains unchanged. Production tree object `e534248ce19d62d6ea44847951e9e9d32d8e1f78` is identical at production candidate and test commit.

On that old AVD one actual patched ModuleTwoA invocation printed `OK (1 test)`,59.034instrumentseconds/63.471hostseconds,exit0. A collector with no usable timestamped current runner records wrongly generated `VALIDATION_BUSINESS_FAILURE`; original JSON and explicitly corrected summary are both preserved. Zero captured fatal markers from an empty capture was not affirmative health evidence. Subsequent readonly inventory, repeated at2026-10-07T22:06:45.7415686+08:00, found no ADB device entries and no emulator/QEMU process, while the ADB server existed. Cause remained unknown; no inspected WER match does not prove crash absence. Classification was **VALIDATION_ENVIRONMENT_BLOCKED**; no final full ran in that stabilization authorization. Only the independent two-file test commit was pushed; the three original dirty documents remained byte-identical.

Private originals remain in `.gradle/phase4b-validation/forensics-20261007/FORENSICS_REPORT.md`, its raw evidence, and `stabilization-20261007/STABILIZATION_REPORT.md`, including the wrong collector output and correction. No failure history, old userdata or snapshot was deleted to obtain the new Gate.

## Fresh AVD Recovery + Final Validation — 2026-10-07

### Authority, host and identity

User authorized one fresh AVD with installed API37 image; no production/test repair, no physical-device operation, no third AVD or rerun-until-green. Starting local/remote HEAD both `f000ca03d5eb03646d76f199fe2cb899979dfe68`; only the three original dirty docs existed. Their hashes were captured before this turn, and no document was updated until all fresh gates passed.

- New AVD: `Mirra_API_37_Phase4B_Final`, newly created userdata, no clone/import/snapshot restore. Old `Mirra_API_37_Final` was not repaired, reset, deleted or used for the final Gate.
- Actual Android17 / API37 / x86_64, image `system-images;android-37.0;google_apis;x86_64`, revision6; build `CE2A.260420.019`; qemu/ranchu virtual identity, not inferred from the old build.
- Emulator37.1.11.0, build15917651; existing WHPX/JDK17/tooling. The installed sdkmanager wrapper redirects to unavailable Android CLI update, but native avdmanager list/create worked; no SDK update/install was attempted.
- Initial free space approximately C4.4GiB/E94.3GiB; this new AVD's default10GiB userdata was placed on E. No old AVD or user file was removed for space. Launch disabled snapshot load/save, with2048MiB guest memory. Host-RAM suggestion warning was retained, not represented as a fatal error.
- First boot was slow: a bounded5-minute polling window elapsed before boot completed; a later readonly check found it had naturally completed. No restart/repair was used. Only then did the required four health rounds begin.
- Four healthy observations at22:32:54.7965859,22:33:25.9185138,22:33:46.0195502,22:34:06.8987531+08:00: actual span **72.102s**. Boot1, adb and AM/PM/WM/Power responsive, system_server/SystemUI present, last ANR “No ANR has occurred”, no retained current DeadSystem/missing-service markers. Installed MainActivity resolved before tests.

Native commands and Gradle's verified `ANDROID_SERIAL` device filtering targeted only this newly identified AVD; no other emulator or physical device was operated. Serial stays private/local. No wipe-data, pm clear, uninstall, clock/permission/network modification, DB/marker import or assertion/timeout/source change occurred.

### Build, install and independent targeted gates

Preparatory debug/test APK build exit0 (1m36s, outputs up-to-date); both APKs installed via `adb install -r`, both Success. This is fresh-environment installation, not an old private-data preservation claim.

Each method below had three **independent real instrumentation invocations**, not `log=true`; each invocation started/passed exactly1, nativeexit0,0failure/assumption, healthy pre/post and unchanged system_server:

| Exact method | Independent result | Instrument seconds 1 / 2 / 3 |
| --- | --- | --- |
| `ModuleTwoAFlowTest.learningItemCanPauseResumeAndCompleteWithoutRestoringMainline` | 3/3 actual PASS | 15.217 / 16.196 / 16.676 |
| `ExternalInterventionSessionUiTest.staleRequestDoesNotOpenReasons` | 3/3 actual PASS | 9.030 / 9.808 / 13.049 |
| unchanged `SessionCloseoutUiTest.savingBeforeDurablePendingDoesNotClaimReadingEnded` | 3/3 actual PASS, including teardown | 15.992 / 15.049 / 16.695 |

4B regression: focused JVM **57/57** (47domain +10ViewModel),0failure/error/skip; actual Room **8/8** (68.517instrumentseconds), actual Compose **10/10** (63.987instrumentseconds),0failure/assumption. No frozen business logic or test assertion was weakened.

Fresh unfiltered JVM: **431/431 PASS**,50XMLclasses,0failure/error/skip; test task actually rerun, prerequisites up-to-date, exit0 (1m7s). Final lint/build group exit0 (2m40s): lint **0errors/9existingwarnings/1hint**, assembleDebug/assembleDebugAndroidTest PASS. Lint's Hint severity was separately counted after inspecting XML; no invented zero-hint claim. These results belong to this authorization, not reused older green output.

### Host task-selection failure, then unique actual full

At22:52:49.1195949+08:00 an unquoted dotted `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` was split by the host shell. Gradle failed at **task selection**, exit1,46.334hostseconds: task `.injected.androidTest.leaveApksInstalledAfterRun=true` not found. There were **0Android runner starts/finishes**, no connected execution or assertion. Pre/post Android health remained normal. This is retained as a host invocation error, not Mirra business failure or emulator collapse.

Quoting that property corrected the invocation; at22:58:08.8374448+08:00 the **sole actual full unfiltered connected execution** began. No prior full test failure was rerun; no class filter, retry rule or permission grant. Only the fresh AVD was selected. The mistakenly copied `final-full-results/` from the earlier host error contains stale build outputs and is explicitly excluded. Canonical fresh outputs are isolated in `final-full-execution-1-results/`; logcat is filtered by this run's epoch boundary to exclude targeted-test buffer history.

| Final actual full field | Actual result |
| --- | ---: |
| Discovered / unique XML cases | 351 / 351 |
| Actual assertion PASS | 342 |
| Unmet assumptions | 9 |
| Opt-in NOT RUN | 4 |
| Permission prerequisite NOT RUN | 5 |
| Actual business failures / errors | 0 / 0 |
| Other skipped / unfinished / not started | 0 / 0 / 0 |
| Runner unique start / finish | 351 / 351 |
| Final runner run-finished marker | 1 |
| Raw XML failures / errors / skipped | 9 / 0 / 0 |
| Transport abort / observed DeadSystem | 0 / 0 |
| Gradle exit | 0, BUILD SUCCESSFUL |
| Host task elapsed | 1046.447s (17m26.447s) |
| Sum of XML testcase durations | 943.964s (not wall-clock full duration) |

All raw XML failure bodies above are `AssumptionViolatedException`, not actual assertion failures. Runner assumption markers also equal9, failure markers0. **Not351/351PASS**. The nine exact cases remain:

- Opt-in4: `ModuleThreeDCloseoutCrashFixtureTest.assertRecoveredFixture/preparePendingFixture`; `ModuleThreeDDataPreservationTest.assertPreservationFixture/seedPreservationFixture`.
- Permission5: `AndroidDndSystemTest.accessibleNonMirraRuleIsIgnored/controlledPolicyChangeIsRejected/ownedRuleIsReusableAndNeverChangesGlobalPolicy`; `AndroidInterventionChannelsTest.grantedOverlayAttachUpdateAndRepeatedRemoveAreSafe/grantedNotificationIsPostedButNeverFullScreen`.

Actual full pre-health22:58:08.1115367 and post-health23:15:37.4341973+08:00: both healthy, boot1, same system_server, SystemUI and all checked services responsive, MainActivity resolve succeeds, last ANR empty. One complete runner summary and all351paired tests agree with fresh XML. No fatal system collapse or transport abort observed within this actual run. This new clean Gate does **not** establish a universal absence of platform faults or the causes of earlier failures.

### Frozen data/source gate and current artifacts

Room remains4; all four schema file SHA-256 values exactly match the Data gate table above. Migration1→2→3→4, Entity/Column/Index, Manifest and dependencies unchanged. These are file hashes, not identityHash. All164 enumerated inherited frozen paths remain unchanged from the parent, excluding the three intentional already-committed 4B wiring/detail production changes. Current production tree equals `5cbff38...`; current two-file test delta belongs only to the already-pushed `f000ca0...`. **This validation authorization changes no app/test/config/schema source.**

| Current local artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 16319720 | `61229A0E0C49855A4D9D67F400711993422F42C1B424F2FDFDA83C0AAD7D7E86` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2442162 | `6B826F471F855B2E85CA0F266610881894B23A0B8BB0E96C41D4AD9291A441E8` |

APKs remain local, not committed. Cover-install flags/package presence on fresh userdata do not prove old installed data preservation. Installed/nonempty manual insight journey, pixel screenshot inspection and private-file before/after hash chain remain NOT RUN.

### Current publication and stop

Classification: **VALIDATION_RECOVERED**. Phase4B **complete / awaiting independent review**, not accepted/frozen. Production candidate `5cbff38853c069b02772b6062d5a7e96a1f9fb13`, test stabilization `f000ca03d5eb03646d76f199fe2cb899979dfe68`, and this validation-document commit are separate facts. Only CURRENT_STATE, this checkpoint, the4B execution plan and sanitized evidence index are included in `test(insights): validate phase 4b`; exact validation SHA/local-remote equality is reported after publication rather than embedding a circular self-SHA here.

Private canonical fresh records remain `.gradle/phase4b-validation/fresh-avd-20261007/`: identity/health/targeted summaries, isolated full JVM XML, lint XML, host error record, actual-full guard/result, fresh connected XML, epoch-filtered runner and case classification. Public [evidence index](../evidence/phase4b/README.md) contains only sanitized metadata/counters. No device serial, full dumpsys/logcat or private user data is uploaded. An internal read-only evidence audit is not ChatGPT independent acceptance.

API23–36 full matrix, full OEM/physical compatibility, TalkBack, release/Play, real power loss/manual real clock change remain NOT RUN. OnePlus13T was not operated; daily-use feedback is not compatibility PASS. All inherited ANR, Recovery>120s, data-preservation/marker,4A performance and screenshot limits remain. No Phase4C, merge/main, release or self-freeze.
