# Mirra V1 — Phase 4E final validation

## Contract and current state

User authorized one complete final reliability/delivery pass from `4066b898620b375303a4c1fb27e4bea64caae808` on `codex/phase-4e-v1-final-validation`. Phase 4C and 4D have user-reported independent acceptance **PASS WITH NOTES**; their historical execution and limits remain intact. Room v4, schemas 1–4, migrations and Backup Format v1 are fixed.

**Complete / awaiting independent review, not accepted/frozen.** No main merge, release, physical device operation, existing-AVD wipe/clear/uninstall, or V2 work. The two minimal evidence-confirmed corrections preserve frozen product/data rules. Historical pending statements below are chronological records, superseded only by the final gate section, not deleted.

## Execution checklist

- [x] Verify dedicated API37 environments, protect installed baselines and permission/network state.
- [x] Actual learning-content / Intent / Session / Note / image / Topic / search / result journeys.
- [x] Available/unavailable monitoring, interruption/recovery, permissions and ownership cleanup.
- [x] Safe opt-in PENDING / journal OLD-NEW / preservation scenarios with original-baseline restoration.
- [x] Actual offline SAF full-backup round-trip and JSON/CSV save/cancel; original-fact comparison.
- [x] Large-history and data/media round-trip measurements; recorded semantic layout/font and actual back/picker reachability.
- [x] Final-source targeted and full unfiltered JVM / connected, lint and APK builds.
- [x] Final cover installation, cold/offline start, quiescence, APK checksum and delivery evidence.

Only actual execution will replace these unchecked items. Assumptions, environment interruptions and unavailable system transports are not PASS.

## Initial environment and investigation

Git matched the requested clean 4D validation baseline and the new 4E branch was created directly from it. The dedicated AVD was not running. Started the existing `Mirra_API_37_Phase4B_Final` without snapshot load/save or data reset; boot/health checks are pending. Other devices are excluded, and serials/raw logs stay in ignored local evidence.

Dedicated AVD booted as Android 17 / API37, build `CE2A.260420.019`; actual cold start completed and last-ANR readback was empty. Original network airplane/Wi-Fi/mobile was 0/1/1. Initial Usage Access and Overlay app-op modes were default, notification permission was false. No permission has yet been changed; authoritative DND API preflight still required before any grant. No other device was operated.

The unchanged installed journey fixture protected the original baseline and seeded synthetic rows: `prepare` **1 actual PASS**. Its saved original baseline is retained privately until all installed scenarios finish. It must not be overwritten or removed to obtain a green test.

**Confirmed cleanup regression:** official full-backup creation reached DocumentsUI; `full-backup-work` went from 0 files to **8 files / 736 KiB allocated**. Force-stopped only Mirra, left the old picker and performed a true COLD launch; the same 8 files/736 KiB remained. This is private plaintext-copy retention/storage use, not live-data corruption or external upload. Corrective tests must go RED before production repair; Journal/resource switching is outside the repair.

Initial debug/test builds passed. The initial build had compiled before the new service regression tests were written, so the following direct service run actually executed only the **5 existing cases, all PASS**; it is **not** the new-test RED run. A scoped `install -r` preserved all **29 current private-file SHA-256 values before first launch**, including the protected synthetic fixture. This proves that installation only, not historical retention or final-source validation.

Historical 3D fixture guards have been minimally extended, test-only, to permit the explicit dedicated Phase4B AVD while retaining API37/ranchu/scenario and exact actual-name equality. Production business assertions are unchanged. Further actual opt-in execution remains pending.

## Focused regression RED

Recompiled the new Android tests and installed only the test APK over the existing test package. `FullBackupServiceTest` actually executed **12 cases: 6 PASS / 6 assertion failures**, with no assumptions. The six failures reproduce prior-process/legacy workspace retention, unsafe symbolic entries not rejected before preparation, unsuccessful reclamation not preventing publication, and cancelled prepare/inspect return boundaries leaving undisclosed handles. These are expected pre-fix RED results, not environment failures. The existing six compatibility paths remain green; no assertions were weakened.

Original DND access was also confirmed through the real API: the three platform tests reported **3 unmet assumptions / 0 actual platform execution**, because policy access was absent. The runner's `OK (3 tests)` is not a business PASS. No system permission has yet been granted.

`DataManagementLifecycleTest` executed **6 cases / 6 pre-fix failures** on real Android `Uri`, dispatcher and `ViewModelStore.clear`, with controlled private synthetic service resources. Five failures show the cleared owner does not clean waiting backup/validated candidate handles; the active-save case already cleaned safely once but retained a cancelled-result message on the destroyed owner, so its null-result assertion also failed. That last result is not evidence of premature deletion or data loss. The fix waits for the original job and its finally, then discards only still-owned captured handles; unresolved restore/cleanup errors remain preserved. The save test awaits both handle release and the lifecycle cleanup terminal state, rather than racing its original finally. No deadline or business assertion was relaxed.

## Corrective GREEN and actual offline data management

After the minimal service/workspace/startup/ViewModel correction, both classes passed together: **18 actual PASS / 0 failure / 0 assumptions**, 4.69 s. Peer focused source review found no blocking issue in cleanup boundaries or lock ordering; that review is not the independent ChatGPT acceptance. Only temporary `full-backup-work` is reclaimed, never the separate `full-restore` Journal, OLD/NEW safety generations, or installed-baseline cache. Same-process handles survive replacement-container construction. The corrected APK cover install preserved the same 29 current private-file hashes before first launch.

Reproduced the original installed picker/process-stop scenario: **8 temporary files → force-stop → true COLD start → 0 files**. The unrelated original portable baseline remained privately protected. Wi-Fi/mobile were explicitly set 0/0 (original 1/1) for the actual SAF journeys; airplane stayed 0.

Official Mine → Data Management → system DocumentsUI, not fixture-written SAF files:

- Full Backup v1 saved and re-opened: 219,438 bytes.
- JSON saved: 13,228 bytes; CSV ZIP saved: 7,502 bytes.
- JSON/CSV privacy confirmation and restore full-replacement confirmation actually displayed.
- Both export pickers cancelled through Android Back; UI correctly reported cancellation, readable-export temporary files returned to 0.
- Read-only SQLite capture versus saved JSON/CSV: **12 tables / 12 synthetic rows / 105 fields per format / 13 relationships matched**, including 8 NULLs. CSV ZIP has 12 CSVs plus metadata/schema/README (15 entries), fixed ordering and no JPEG/internal path/runtime ownership fields. This small UI dataset has no formula prefixes or special multiline/quote text; those boundaries are separately covered by the writer/large-data Android tests, not invented as UI proof.
- Installed fixture mutation first verified the whole pre-export canonical digest was unchanged, then changed only its controlled note/JPEG/theme. Actual backup inspection and confirmation cancellation retained that mutated digest (**1 actual assertion PASS**).
- Repeated official SAF inspection and final full-replacement confirmation restored the original synthetic dataset. **1 actual assertion PASS** verified every authoritative column/ID, JPEG bytes, all four portable preference values/presence and FTS. The UI reported successful storage re-open. No data was recalculated or fabricated.

Host UI orchestration initially looked for the restore button before scrolling/validation settled; bounded UI inspection plus scrolling located the real button. This was not a business assertion failure or application defect.

## Intermediate regression and performance

Current production source: complete unfiltered JVM **607/607 PASS**, 0 failure/error/skipped; lint **0 errors / 18 warnings / 1 hint** (includes a new nonfunctional `EmptySuperCall` warning on lifecycle cleanup); debug and Android-test APK builds PASS. Final connected and final post-test packaging remain pending, so these do not constitute completion yet.

Three actual Android performance tests passed in 78.55 s: 10,001-cohort Trends, 10,001-window Insights, and the new isolated large-note/media formal backup/JSON/CSV/restore round-trip. The latter uses **1,000 long Chinese notes / 16 real 256×256 JPEGs / 1,026 rows across 12 tables / 1,923,030 UTF-8 note bytes / 180,053 JPEG bytes**. Original IDs/fields/bytes/preferences and rebuilt Chinese FTS all matched after actual sandbox replacement. Process-wide Java heap/PSS are sampled every 100 ms, not true peak memory or an arbitrary-scale/OEM SLA. Details are in the compact performance evidence; no private archives or raw logs are committed.

The actual final-source APK was locally decoded: `allowBackup=false`, `fullBackupContent=false`, and the compiled data-extraction resource contains cloud/D2D/ios cross-platform 9-domain exclusions each (27 total), no includes. This is APK-policy parsing, **not transport execution**. Manifest, policy resources, Schema 1–4, Room v4 and migrations are unchanged from the 4D baseline.

## External process interruption evidence

All 11 sandbox journal boundaries ran prepare → external Mirra force-stop → true application COLD start → assertion in a new process: **22 actual invocations PASS**. Boundaries are PREPARED, SWITCHING, database removed/published, preferences removed/published, images removed/published, VERIFIED, COMMITTED, CLEANUP_PENDING. The first nine select complete OLD; COMMITTED/CLEANUP_PENDING select complete NEW. These are real Android process transitions over isolated synthetic resources, not physical power loss.

Two installed bootstrap boundaries ran before normal storage owners opened: images published selected OLD; COMMITTED selected NEW (**4 actual prepare/assert invocations PASS**). The fixture subsequently restored the saved original raw database, all original preference keys/types/presence and image resources; these are separate from portable cross-device restore guarantees.

PENDING prepare → external force-stop → true COLD application startup → assertion passed (**2 actual invocations**). Readback showed NORMAL / COMPLETED, `endedAt == closeoutStartedAt`, the requested end page, and no active Session. It was not converted to ABNORMAL. A separately guarded preservation fixture seeded controlled data, covered the installed APK and checked it after true COLD launch (**2 actual invocations PASS**). These synthetic additions remain inside the separately protected outer installed baseline until final restoration.

## Actual installed learning journey

Official UI created a 200-page book at page 40, selected it as mainline, paused and resumed it, then followed Intent → First Action → Session. Real Room readback showed FULL / DND ACTIVE / closeout ACTIVE. Updating page 42 and autosaving a note for page 35 retained progress 42 and the draft through Break and Recovery.

- Five-minute Break was started and ended early through UI; durable timeline contained BREAK, then RECOVERY rather than immediate FOCUS.
- Real focused Recovery lasted **91,260 ms**, then automatically entered FOCUS without downgrade or a user-declared success.
- A roughly three-second Chrome visit did not create DISTRACTION; a subsequent visit over the confirmation threshold did. The application prompt was actually visible after returning. An owned overlay window was found on the risk-app path; this alone does not prove a visually unobscured overlay or Notification SHOWN.
- First-episode Allowance offered the four projected purposes, granted three-minute reply allowance, then its explicit confirmation added two minutes exactly once (`extensionCount=1`). The extension action disappeared; earlier DISTRACTION history remained. Early ending produced RECOVERY.
- Revoking Usage Access durably produced PARTIAL / UNMONITORED. Regrant did not restore FULL. Only then did normal closeout finish at page 44. Readback: NORMAL / COMPLETED, exact stored closeout boundary and DND RELEASED. ReadingRecord displayed the actual sequence and withheld effective focus for incomplete monitoring.

Host-only SQL column/name quoting mistakes and an initially stale UI hierarchy were corrected without changing application code or data; they are not business assertion failures. UI capture now uses a unique fresh hierarchy and rejects a failed dump rather than reading a previous one. No clock change or database Segment manipulation was used.

A second real FULL Session confirmed Stable Start after **123,005 ms** from its actual start, then closed normally with effective focus displayed, one saved note and no fabricated break/distraction counts. A subsequent active Session was externally force-stopped; true COLD startup recorded ABNORMAL / PARTIAL with no resumed learning. A separate denied-Usage Session began NONE / UNMONITORED and ended normally while retaining reading availability. Deep Focus was not actually waited for its threshold in this manual journey; its frozen-rule automated tests are separate evidence.

An existing note imported the newly staged synthetic JPEG through the actual Android Photo Picker, including its explicit Done action. Preview displayed one image; system Back returned to the note/list/book. UI created and linked a new Topic, and knowledge search returned that Topic. Book completion retained page 46 rather than rewriting it to the total 200. Nonempty global History showed NORMAL / ABNORMAL / FULL / PARTIAL / NONE records; opening a record and Back returned to the original History/Mine paths. Trends reflected the actual eight converted intents and one confirmed Stable Start. These are controlled installed facts, not daily-use/OEM evidence.

Actual AVD 320dp width (840 pixels at 420dpi), fontScale 2: Data Management scrolled to backup/restore/JSON/CSV/Back and its export confirmation buttons remained reachable. Display override and fontScale were subsequently restored to original 1080×2400 / 1.0. The complete 320/360/411 × fontScale 1/2 matrix is separately semantic Compose evidence, not six sets of physical screenshots. Export preparation at this point was correctly refused before launching SAF because the following historical compatibility defect was encountered; it is not a successful picker run.

## Historical backup compatibility regression

After the unchanged preservation opt-in inserted its explicit pre-3D trusted historical sample, official backup/export preparation was rejected. Readback proved no active workflow, no unresolved DND record, STOPPED/unbound monitoring and an inactive owned rule. The sample legitimately has ended NORMAL, context ACTIVE and **both closeout boundary fields absent**, as the old 3C contract requires; preservation assertions explicitly prohibit backfilling it.

`BackupValidation.validateWorkflow` instead rejected every NORMAL + ACTIVE context, inadvertently imposing the new two-stage closeout shape on valid old histories. This is a backup admission compatibility defect, not evidence of unsafe runtime cleanup or permission failure. A narrow RED → GREEN correction will retain historical facts while continuing to reject active Session/Segment, PENDING, ACTIVE with a boundary, and malformed COMPLETED facts. The protected original installed baseline is retained until that path can safely restore it; no direct database repair or fixture deletion is used to bypass validation.

The new real-Room/formal-package compatibility case actually ran before the correction: **1 executed / 1 expected assertion failure**, caused by `OWNED_CLEANUP`. The minimal validator correction removes only the prohibition on ended NORMAL + ACTIVE history; paired-null boundaries, ACTIVE-with-boundary refusal, exact COMPLETED facts and all live-state guards remain. The positive case checks both naturally trusted and structurally incomplete historical samples without backfilling their facts; a second case retains 11 malformed/live-state rejections. Actual corrected execution and final gates are still pending at this point.

## Corrected final-source gate

The full backup and lifecycle classes passed together after the compatibility correction: **20 actual PASS / 0 failure / 0 assumptions**, including the legacy formal round-trip and all 11 invalid-state guards. The corrected cover installation preserved **23 measured current private-file hashes before first launch**. This is that particular cover-install proof, not historical or raw preference-file durability proof.

Final-source unfiltered JVM: **607/607 PASS**, 0 failure/error/skipped. `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` passed (0 lint errors / 18 warnings / 1 hint); the additional lifecycle `EmptySuperCall` warning is not relabeled historical. A source-focused Codex peer review found no blocking compatibility issue; it is not independent ChatGPT acceptance.

With the legitimate old history still unchanged, official backup preparation now reached DocumentsUI; cancelling returned the truthful cancellation message. Actual 320dp/fontScale 2 JSON privacy confirmation → DocumentsUI (filename and SAVE reachable) → Android Back also succeeded. Display size/font were restored to 1080×2400/1.0. A configuration recreation returned to Mine as the existing navigation behavior; the host reopened Data Management rather than treating a missing button on Mine as a production failure.

The final unfiltered connected gate is now running. The original installed baseline remains privately protected through that run: ordinary theme lifecycle tests can transiently write installed preferences, so this report does **not** claim that all ordinary tests avoid production preference writes. Final recovery will verify complete original authoritative facts, referenced JPEG bytes and the four portable preference values/presence before removing only the test-owned baseline cache.

The first host invocation failed task selection before any device tests ran: PowerShell split the unquoted APK-preservation property into a task name. Its 35-second Gradle failure is retained privately, not counted as a connected business/environment assertion or a test run. The property is now passed as one quoted argument; APK preservation remains mandatory. This is a corrected invocation, not retrying failed business tests until green.

## Connected environment interruption

The correctly invoked unfiltered run was interrupted after **7m59s**, not PASS. Its partial XML contains 73 testcase nodes: **68 completed PASS, 4 explicit historical opt-in assumptions, 1 empty failure node** for `ModuleTwoAFlowTest#learningItemCanPauseResumeAndCompleteWithoutRestoringMainline`. There is no completed business assertion stack for that last case. The runner also reports `/sdcard` output transport disconnected.

The per-test system log shows the old system server terminated by signal 9 and zygote exited because system server died; subsequent AudioFlinger/zygote restarted and `cmd` could not find activity service. Readback confirmed a new system-server process. This is a platform/process interruption, not sufficient evidence of a Mirra business bug. No active installed Intent/Session remains; private baseline is retained and `/data` has 8.4 GiB available. Full raw reports are copied only into ignored local evidence before any rerun. No test assertion, timeout, source data or production code is changed in response. A controlled reboot of the same dedicated AVD, without wipe/reset/snapshot replacement, will rebuild system/FUSE state before a separately recorded clean full gate.

The same AVD was rebooted without clearing data. Boot completed; Downloads and the runner's external-output directory were readable again, temporary grants and the protected cache survived. The first immediate post-boot launch wait timed out; later fresh hierarchy showed a responsive Mine page, with no ANR. After stabilization, a separate true COLD launch completed **2,826 ms / Status OK / no ANR**. The independently restarted unfiltered gate uses the unchanged source/APKs and the quoted APK-preservation property. A failed host permission-subcommand and a nonauthoritative dumpsys pattern are not treated as permission readback; actual settings/API-based evidence remains the authority.

The second full run was interrupted after **15m05s**, not PASS. Partial XML contains **106 nodes / 101 completed PASS / 4 opt-in assumptions / 1 runtime failure** at `ReadingRecordUiTest#recordActionsReachableAt320dpNormalFont`. The app's ART FinalizerWatchdog reported `ReferenceQueueDaemon` timeout targeting `HardwareRenderer.DestroyContextRunnable`; main was in native renderer destruction, ReferenceQueue in `nDeleteProxy`, and the runner in `ActivityScenario.close()` at the unchanged test's finally. No complete native RenderThread backtrace establishes the lower-level root cause. This is not a business assertion failure, but it also is not proof that every cause is environmental. No source, assertions or deadlines were changed to make it green; this unchanged screen/test does not call the four repaired backup/lifecycle paths.

The old AVD's protected original baseline was restored through the production service: **1 actual PASS**, verifying all 12 authoritative tables, referenced JPEG bytes and four portable preference values/presence, before removal of only this fixture's saved baseline. Readback showed no active workflow and no learning items in that original baseline. Original Usage/Overlay modes (`default`), notification permission (false), DND access (false), network (airplane/Wi-Fi/mobile 0/1/1), and display/font (1080×2400 / 1.0) were restored. The original DND API preflight again reported three unmet assumptions, not platform PASS. No monitoring service or overlay was present; the owned rule was inactive. Device-local DND-used reconciliation state is intentionally not deleted to fake a never-used device. The old AVD was then closed, preserving its disk and all original data.

A separately named `Mirra_API_37_Phase4E_Final` AVD was created from the already installed API37 Google APIs x86_64 image, with no image download, reset or overwrite of the old AVD. It retains 1080×2400/420dpi and uses explicit SwiftShader with Vulkan disabled, with a 3,072 MB launch memory request (host had 5.92 GiB available after the old emulator closed). Guest `/proc/meminfo` reported 4,008,504 KiB MemTotal; the launch request is not mislabeled as measured guest memory. This is a controlled environment change, not a confirmed renderer root-cause fix. Actual learning/SAF and performance evidence above belongs to the old 2 GiB AVD; the upcoming complete gate will be separately attributed to this new environment.

The new AVD completed its first boot and installed the same target/test APKs. Initial COLD launch was 9,900 ms during first-install initialization, not a steady-state benchmark. Usage/Overlay were default, notifications false, airplane/Wi-Fi/mobile 0/1/1 and font 1.0. A host orchestration mistake overlapped two initial direct instrumentation launches: the first original-DND preflight ended `Process crashed`, so it is not counted as prerequisite or business evidence. No grants had occurred. After the baseline preparation completed (**1 actual PASS**, separately protected new run), a serial original-DND preflight proved **3 unmet assumptions / 0 actual execution**. Only then were Mirra's four temporary capabilities granted and checked. No other package/device was changed.

The emulator-generated runtime hardware file reports **4,096 MB RAM / 4 cores / GPU enabled SwiftShader / 576 MB VM heap**. The emulator adjusted the 3,072 MB launch request; runtime configuration and guest MemTotal, not the request alone, describe this gate's environment.

On the fresh environment, the previously stalled renderer-cleanup method plus all DND/intervention platform methods actually ran together: **9 PASS / 0 failures / 0 assumptions**, 10.767 s. This confirms those specific assertions completed under the new configuration, not a complete suite or a proven native root cause. The unchanged final-source full unfiltered connected run is now executing with the APK-preservation property and explicit dedicated target.

## Final complete gate and protected handoff

The unchanged final-source unfiltered connected run on `Mirra_API_37_Phase4E_Final` completed **BUILD SUCCESSFUL / exit 0 / 16m02s**. Fresh XML contains **435 discovered and unique methods, 426 actual PASS, 9 unmet opt-in assumptions, 0 actual failure, 0 error, 0 additional skip and 0 unfinished**. All eight DND/intervention methods actually passed, including the five formerly permission-blocked methods. The new media round-trip, historical compatibility correction, lifecycle cleanup tests, history measurement and previously stalled renderer method are present and passed. An initial host XML collector mistakenly read PowerShell's adapted failure property instead of its XML node and mislabeled the nine assumptions; reading the node text corrected the classification, without changing or rerunning a test.

The nine ordinary-suite opt-in assumptions are not PASS. Their actual specialized execution is separately recorded above: the four historical PENDING/preservation methods and all five 4C installed/sandbox fixture methods were executed with safe prerequisites. None of those nine eligible methods is unexecuted for this phase. Targeted/specialized invocations are not added to the ordinary suite's 426 total. The two interrupted full attempts and the renderer root-cause uncertainty remain preserved; they are not rewritten as successful runs or proof of an environmental-only cause.

Post-suite baseline restore used the formal production service and passed (**1 actual invocation**): every original authoritative field/ID, referenced JPEG and four portable preference values/presence matched before the saved cache was removed. Restored permissions read back as Usage/Overlay `default`, notifications false, and DND access false (real API: **3 unmet assumptions / 0 business execution**, not PASS). Original network was 0/1/1 and display/font 1080×2400/420dpi/1.0. Current Zen configuration contained no Mirra-owned rule and global Zen was OFF; historical dump mentions are not treated as current active rules.

Final target cover installation preserved **14 measured current private-file SHA-256 values before first launch**. Normal true COLD launch completed **7,374 ms**, with successful activity response. A further separately protected smoke baseline allowed actual offline navigation without losing preference presence: Wi-Fi/mobile 0/0, active default network `none`, true COLD **3,186 ms**, Start/Knowledge/Mine and Data Management reachable, all four actual backup/validate/JSON/CSV controls present, Android Back functional. An immediate fresh hierarchy read initially returned a null root; after the UI settled, the actual tree was read. A host lookup used the wrong restore label before inspecting the unchanged screen's real `选择并验证备份`; its corrected actual control check passed. These are host observation mistakes, not weakened business assertions or hidden application failures.

The smoke baseline prepare and final formal restoration each passed (**2 actual invocations**). Final readback: original empty 12-table baseline, no active Intent/Session/PENDING, no owned service/overlay/notification, no current owned rule, no restore Journal/work files, zero backup work files and zero saved-baseline files. Original network/permissions/font were still restored. Both dedicated AVDs were closed with their disks retained; no physical device was operated. Device-local reconciliation history is not deleted to simulate a never-used device.

### Final verification summary

| Gate | Actual result |
|---|---|
| Targeted corrected backup/lifecycle classes | 20 actual PASS / 0 failure / 0 assumptions |
| Complete unfiltered JVM | 607/607 PASS / 0 failure / error / skip |
| Complete unfiltered connected | 435 discovered / 426 actual PASS / 9 opt-in assumptions / 0 actual failure / error / unfinished |
| DND/intervention in the complete run | 8 actual PASS; five permission prerequisites genuinely satisfied |
| lintDebug | 0 errors / 18 warnings / 1 hint; lifecycle `EmptySuperCall` warning is new and nonfunctional |
| assembleDebug / assembleDebugAndroidTest | PASS |
| Official offline SAF backup/restore/JSON/CSV | Actual UI save/cancel/confirm, 12-table raw facts/JPEG/preferences/FTS verified |
| Process-death recovery | 11 sandbox journal boundaries, two installed bootstrap decisions, PENDING and preservation actual execution |
| Performance | 10,001-cohort Trends/Insights/History; 1,000 long notes / 16 JPEGs formal sandbox round-trip; bounded observed timings in evidence TSV |
| Final install/lifecycle | Cover install, normal/offline COLD, three main pages/Data Management, original baseline/state restoration PASS |
| Room / Backup Format | v4 / v1 unchanged; schemas 1–4 and migrations unchanged |

### Schema and APK

Schema file SHA-256 (not Room identityHash):

- 1.json: `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1`
- 2.json: `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D`
- 3.json: `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205`
- 4.json: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`

Debug APK: `build/deliveries/v1/Mirra-v1-debug-20261009.apk`, **17,473,097 bytes**, SHA-256 **`185A1635DC81C1B1538116F891ED1F929E20188B39786E272CA4907BE9502F4F`**. It is a local installable artifact, not committed, released or a production compatibility claim. Compiled automatic-backup restrictions match the unchanged 4D policy; actual transports remain unexecuted.

Implementation + tests: **`bc2efd27f8b2e8a53f56102b4c031c2acdfaaa1d`**, `fix(data): harden backup lifecycle and legacy compatibility`. Production scope is four files: temporary backup resource lifecycle, container housekeeping, disposed ViewModel cleanup and legitimate pre-3D history admission. Five Android test files provide regression/process-guard/performance evidence; no frozen learning, DND, monitoring, statistics, schema, migration, manifest, policy or dependency is modified. The separate validation-document commit and verified remote HEAD are reported in the final handoff rather than creating a self-referential commit SHA in this document.

### Known limits and NOT RUN

- Native renderer root cause remains unconfirmed; a fresh full PASS does not erase the two older interruptions. Software-rendered Debug cold-launch observations are not production startup SLAs.
- ALL exact medians retain O(N) scalar samples; observed SQL scans/sorts remain. Timings and sampled/endpoint process memory do not prove constant space, true peak memory or arbitrary-scale performance.
- Low-space/provider/IO failure inputs are controlled tests, not physically filling the disk. Android process-stop recovery is not physical power-loss proof. Temporary-file cleanup is not secure erasure; unknown resources or failed cleanup are retained conservatively rather than deleting unrelated data.
- Actual layout proof is the semantic Compose matrix plus installed 320dp/fontScale 2 Data Management and recorded picker/back checks. No full clean six-viewport pixel screenshot matrix or TalkBack proof is claimed.
- Manually waiting the Deep Focus threshold and a separate installed controlled-service-stop/query-gap-near-closeout journey were not executed in this phase; frozen automated rules are separate evidence.
- API23–36 full matrix, physical/OEM compatibility, TalkBack, release/Play, physical hardware power loss, manual real system-clock changes and actual cloud/D2D/cross-platform/ADB/OEM backup transports remain **NOT RUN**. OnePlus daily-use feedback is not upgraded to compatibility PASS.
- Codex peer reviews are scoped internal source checks, not ChatGPT independent acceptance. No private rows, serials, raw logs, database/preferences, SAF archives, Journal or original baseline is committed.

**MIRRA_V1_FINAL_VALIDATION_COMPLETE_AWAITING_REVIEW.** Stop at independent review; no V2, main merge, release or self-declared freeze.
