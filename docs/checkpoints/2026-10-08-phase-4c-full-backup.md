# Phase 4C — Full Backup & Restore delivery

## Authorization and delivery state

User authorized the complete usable feature from `3579cb74f327b6e243771285d901f639452bcd07` on `codex/phase-4c-full-backup`. Internal steps do not require separate acceptance freezes. Phase 4C-0 / 1A remain inherited accepted foundations; current delivery is **complete / awaiting independent review**. Corrected-APK installed bootstrap, offline restore, full regression and quiescence checks are complete. Implementation/tests commit: `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f`; this validation-document commit is separate. This is not independent acceptance or a new freeze.

Goal: Mine → Data Management → SAF full backup → input verification → confirmed complete restore, with old-or-new resource selection and fail-closed recovery. Room v4 / schemas 1–4 / migrations remain frozen. No Phase 4D, main merge, release or physical-device operation.

## Implementation delta

- Operation-scoped repository gates and external Camera lease; retired resource generations cannot admit late writes.
- Pending editor freeze/flush before storage draining; lifecycle-owned preferences instance closure and portable staging preferences.
- Strict format-v1 archive, trusted reconstruction of all 12 authoritative tables and referenced JPEGs; runtime ownership excluded.
- Durable private restore journal and bootstrap before Room/DataStore/startup cleanup; FileProvider checks the pending journal before access.
- Flat Data Management UI, Android document pickers, privacy warning, complete replacement confirmation and fixed non-sensitive error codes.

Implementation and executed evidence are separated below; intermediate RED and interrupted runs are retained, not relabeled final PASS.

## Actual TDD / verification history

- First combined targeted JVM run: 44 executed, 24 expected stub failures, 20 pass. Archive, ManagedPreferences and RestoreJournal behavioral RED established; gate/UI tests supplied the passes.
- Subsequent integration compilation found missing pending-editor/API wiring, directory-open flag incompatibility, wrong DND API name, FileProvider nullability and incomplete synthetic Room fixture constructor arguments. These were build diagnostics, **not Android test runs**.
- First snapshot Android run: 12 behavioral RED failures; expanded final targeted run reached 14 actual GREEN.
- Full service Android initial run: 4 tests / 2 stub failures. Two overbroad exception-catch tests were strengthened to demand the real failure; final 5 tests actually passed.
- Reader lifecycle: 2 tests / 1 composition-disposal RED, then actual GREEN after synchronous reader teardown.
- Search budget: 4 tests / 3 expected failures plus frozen-output pass, then 4/4 GREEN. Journal decision checksum similarly had a recorded RED before correction.
- Permanent Room epoch: 2 tests / 1 actual failure, reproduced twice in a healthy environment. Generated DAO reads stayed closed, but stale `withTransaction` reopened the helper. `EpochOpenHelperFactory` fixes the cause without relaxing the assertion; targeted final GREEN **2/2**.
- Verified rollback outcome: 11 Journal tests / 1 expected typed-outcome RED, then latest Journal suites passed. This distinguishes full OLD recovery from pointer absence after NEW cleanup-sync failure.
- Intermediate unfiltered JVM: **542 executed / 1 failure**, caused by coroutine stacktrace recovery copying an exception while retaining its original cause. Test-only correction checks original throwable identity in the cause chain, preserving failure propagation and safety expectations; that iteration's fresh unfiltered run was **542/542 PASS**, zero failure/error/skipped. Final post-correction 553/553 is separately recorded below. The earlier failure remains recorded.
- A PowerShell/Gradle comma-separated class argument was observed to select only the first class. Those XML runs are counted at their actual selected size, not the requested multi-class count. Direct runner results are separate below.
- Historical Launcher/SystemUI ANR, the unresolved prior Recovery >120s observation and prior installation-preservation limits remain in inherited checkpoints; this feature does not relabel them fixed or caused by a particular environment.

No prior module's full connected result is reused for this implementation; the final fresh unfiltered run below covers the final source tree.

## Test environment and safety

Only the dedicated `Mirra_API_37_Phase4B_Final` AVD is in scope. Boot completion and API37 were checked before use. Every ADB/test action must select that AVD explicitly; do not publish its serial. Keep APKs installed after instrumentation; no wipe-data, pm clear, uninstall or device-time changes. Synthetic fixtures live in bounded test-private directories. Existing installation data must be preserved and actual preservation claims must name their measured scope.

API23–36, full OEM / physical compatibility, OnePlus 13T, TalkBack, release / Play, physical power loss and manual system-clock changes remain **NOT RUN**. Android/fake fault tests are not physical durability guarantees.

## User-visible functionality

- Mine keeps its flat structure and adds **数据管理**. No dashboard, new account or network dependency.
- **创建完整备份** prepares/validates a private package before `CreateDocument`; saving to the chosen URI is followed by fresh readback, SHA comparison and package validation.
- **选择并验证备份** uses `OpenDocument`, copies input into isolated bounded storage, and displays creation time, format/schema and item/note/image counts. Inspection alone does not change current facts.
- **恢复这份备份** requires a separate full-replacement confirmation. No merge; cancellation before confirmation preserves current data. Restoring blocks business interaction.
- Caller/UI cancellation cannot cancel the application-owned switch. New owners open only after one complete generation is selected. Unresolved recovery has a fail-closed screen and explicit retry, not an empty database or a false success message.
- Fixed non-sensitive errors distinguish active learning, unfinished cleanup, low space, invalid archive, read/write failure, maintenance busy and unresolved restore; no private exception/path text is shown.
- Privacy text: private notes/images, unencrypted file, user-chosen location, no automatic upload; the chosen provider may itself use a network.
- Compose tests include 320dp/double-font reachability and confirmation. Committed screenshots are actual controlled AVD UI, not concept images or proof of every layout.

## Snapshot and portable data contract

Exactly **12 authoritative tables** are copied with stored IDs, text/pages/timestamps, rules, coverage and behavioral history; analytics/focus facts are not recomputed:

`learning_items`, `study_intents`, `study_sessions`, `notes`, `image_assets`, `topics`, `note_topic_cross_refs`, `risk_apps`, `session_focus_contexts`, `session_risk_app_snapshots`, `session_segments`, `focus_events`.

- One maintenance window covers a source transaction and referenced-media capture. A trusted fresh Room v4 DB is built from those rows; the live SQLite main file is **not** copied as a purported WAL-safe backup.
- Only DB-referenced `images/<UUID>.jpg` JPEGs enter the package, with byte/metadata validation. Camera/import temporaries, trash, unrelated files and orphans are excluded.
- `search_fts` is derived and rebuilt using the unchanged frozen engine. Raw/NFKC/lowercase text budgets are checked before large token allocation; Chinese search is checked after restore.
- Portable preferences are exactly `last_destination`, `theme_id`, `dnd_enabled`, `cross_app_intervention_enabled`, each with value and presence. One strict snapshot of the selected DataStore is used; errors do not become fallback defaults.
- The **export copy** normalizes `dndRuleId` / `priorDndInterruptionFilter` to null and `dndLifecycle` to NOT_APPLIED. The original DB is unchanged. FGS/Overlay/token/permission/device ownership is not portable.
- Valid legacy ended Sessions without Context and abnormal/zero-page history are preserved, not turned into trusted coverage. The frozen deleted-zero-length Segment/event exception is accepted only at the verified COMPLETED end boundary.
- Format 1 / schema 4 / ownership normalization v1 only; no bare DB, arbitrary old installation or unknown format. Installation migrations are not backup compatibility.

### Validation limits

Archive checks reject traversal/aliases, duplicate or unknown entries/metadata keys, symlinks, malformed UTF-8, central/local ZIP inconsistencies, unsupported methods/ZIP64, SHA/length mismatch and unsupported versions. Incoming SQLite is read-only/query-only and checked against trusted schema/columns/identity/relations; triggers/views or incoming SQL are never published.

Enforced limits: archive 3GiB; expanded contents 4GiB; DB 512MiB; metadata 2MiB; portable preferences 16KiB; JPEG 16MiB / 2560px edge; 10,003 entries / at most 10,000 images; ratio 200; 100,000 rows/table / 1,000,000 rows total; 1MiB row-text budget; 10-minute operation budget. Actual reads are bounded, not only headers. These are rejection limits, **not** maximum-size performance evidence.

## Admission, draining and storage owners

`StorageMaintenanceGate` belongs to one captured Room/preferences/files epoch. Nested work validates and reuses its registered permit. Detached/late callbacks cannot silently obtain a replacement generation; a retired owner stays retired.

| Audited public writer group | Count | Production admission |
|---|---:|---|
| Learning Item | 6 | GateLearningItemRepository |
| Intent / Session / Closeout / recovery | 9 | GateStudyWorkflowRepository, plus outer GateSessionManager |
| Note | 4 | GateNoteRepository |
| Image / Camera / reconciliation | 7 | GateImageRepository |
| Topic | 4 | GateTopicRepository |
| Focus / risk facts | 13 | GateFocusRepository |
| DND persisted state | 2 | GateDndStateStore; whole platform operation also registered |
| Intervention receipt | 1 | GateInterventionReceiptRepository; eligibility chain also registered |
| Search / FTS repair | 2 | GateSearchRepository |
| Preferences | 4 | GateAppPreferencesRepository |
| **Total** | **52** | Captured DI wrappers, not an unused coordinator |

The audited 49 DAO write helpers remain inside existing transaction/compensation chains. The admission JVM test exercises 50 ordinary calls; the two Camera callbacks require an original outstanding lease and have Android import/cancel/mismatched-callback tests. This is not 52 full Android user journeys.

- PendingEditRegistry freezes synchronous edit admission before Main-dispatched flush; waits debounce/import/caption work; retires old editors before replacement. Flush failure propagates and reopens editing without proceeding into maintenance.
- Camera lease spans target creation, external callback, import/discard and compensation. Missing callback prevents EXCLUSIVE instead of being assumed finished.
- Service facts/watchdog/channel jobs capture their original gate and are joined. No request/binding/unfinished job may remain. Awaited presenter cleanup checks no attached Overlay or Mirra intervention notification.
- Eligibility is checked initially **and inside EXCLUSIVE**. Active Intent/Session/PENDING/unresolved ownership refuses backup/restore without auto-ending learning; admission/drain closes the TOCTOU interval.
- Old compositions/ViewModels detach before owner closure. ManagedPreferences admits one canonical-path owner, cancels/joins before rebind, and writes the portable candidate in one edit to a private staging file. Original rollback retains the entire raw protobuf and unknown keys/types/absence.
- Room checkpoint/closure is awaited; EpochOpenHelperFactory permanently seals the old helper against late transactional/raw reopening.

## Journal and startup safety

MirraApplication handles private `noBackupFilesDir/full-restore/restore.journal` before Room, DataStore, runtime reconciliation and image/FTS cleanup. RestoreFileProvider checks the journal even before Application.onCreate and also checks the process blocked gate; its existing Camera path is not broadened.

1. Input and staging are validated, then revalidated at confirmation before effects.
2. EXCLUSIVE retires the old gate, detaches readers and closes all storage/runtime owners.
3. Immutable OLD/NEW DB, complete preferences and images are copied and hashed. OLD preserves all original preference data, not only portable keys.
4. Decision records carry UUID, sequence, state, OLD/NEW fingerprints and checksum. File sync, observable Android rename and parent-directory sync errors propagate.
5. Each resource is published through a staged copy while safety snapshots remain intact. **DB + DataStore + images are not one atomic filesystem transaction**; partial selection never opens business access.
6. Before COMMITTED, bootstrap selects complete OLD; after COMMITTED/CLEANUP_PENDING it validates complete NEW. Corrupt decisions, missing rollback snapshots or uncertain durability stay blocked.
7. Only verified complete durable rollback emits RestoreRolledBackException. Pointer absence alone cannot prove OLD because cleanup may have removed the pointer after NEW commit.
8. New owners open only after selection/startup. A committed NEW result is not rolled back for final housekeeping failure.

| State | Responsibility before normal stores open |
|---|---|
| PREPARED / SWITCHING / ROLLING_BACK | Verify all OLD materials, publish complete OLD, record rollback |
| ROLLED_BACK | Verify selected OLD and finish decision cleanup |
| COMMITTED / CLEANUP_PENDING | Verify selected NEW and finish cleanup; no OLD selection for housekeeping |
| Invalid/torn decision or rollback failure | Preserve recovery material, block editing and allow explicit safe retry |

## Safety-audit blocker disposition

4C-0 / 1A documents remain historical snapshots. This records the full-feature delta, not ChatGPT independent acceptance.

| Item | Implemented closure / evidence | Final delivery evidence |
|---|---|---|
| SB1 | 52 wrapper calls plus startup/runtime/Camera/full compensation; editor freeze/flush; generation/Camera tests; reader-epoch 2/2 | Final 383 actual connected PASS; actual quiet-state preparation admitted |
| SB2 | Strict snapshot, one-edit candidate, singleton teardown, raw OLD rollback; 8 unknown preference types on Android; installed OLD/NEW owner cycles | Final connected; corrected installed bootstrap 4/4 and offline SAF 5/5 invocations |
| SB3 | Journal-first Application, early Provider gate, composition/VM disposal, permanent helper retirement; production installed OLD/NEW bootstrap 4 actual invocations | Final connected; corrected bootstrap 4/4; successful post-suite cold start |
| SB4 | Immutable OLD/NEW, strict fsync/rename, checksum, fail-closed/typed rollback; Android 11-boundary matrix plus 22 external process-cycle invocations | Final connected; typed rollback JVM; installed and external process cycles, not hardware power-loss proof |
| SB5 | Service job/lifecycle/binding proof, joined channel cleanup, Overlay/notification absence and DND clue/access/rule checks | Actual final AVD: no monitoring/Overlay/notification/owned-rule record or Journal; formal preparation/runtime guard admitted |
| SG6 | Archive/schema/relations/JPEG validation, WAL snapshot, FTS, bounded input, actual SAF round-trip and verified baseline restoration | Final connected / normal-offline APK smoke / actual confirmed SAF restore |

## Targeted actual results

| Check | Executed result / proof boundary |
|---|---|
| BackupArchive JVM | 13/13 PASS |
| BackupSearchBudget JVM | 4/4 PASS; frozen output plus expansion bounds |
| ManagedPreferences JVM | 5/5 PASS; not Android physical durability |
| RestoreJournal + safety JVM, latest XML | 9+2 actual PASS / 0 failure/error/skipped |
| BackupDatabaseSnapshot Android | 14/14 actual PASS; 12 tables/JPEG/search/WAL/malformed input |
| Direct Android multi-class execution | **24/24 actual PASS**, OK(24 tests), all successful method statuses: service 5 + lifecycle 2 + Data Management Compose 9 + navigation 1 + Camera 3 + Android Journal 4 |
| Android Journal matrix | One of those four tests loops 11 real Android file/Room/DataStore boundaries: 9 OLD + 2 NEW, 8 unknown OLD preference types. Injected Error bypasses compensation; not external process death or physical power loss |
| Larger fixture | Android test round-trips 1,000 Notes, all 12 tables, a JPEG, preferences and FTS; not 10,000-image/max-size evidence |
| Permanent Room reader epoch | 2/2 actual targeted GREEN after twice-reproduced RED |

The 24 direct tests are not inferred from a 5-test Gradle XML. Targeted runs are not added to fabricate a full-suite count; opt-in/permission assumptions are not PASS.

## Installed formal SAF round-trip

Only the dedicated API37 AVD was selected. Baseline contained no authoritative rows and qualified for a portable safety snapshot. An opt-in fixture saved that baseline privately, then added exactly one synthetic row per authoritative table, a JPEG and controlled four-key preferences.

1. Actual Mine → Data Management → create → Android CreateDocument cancellation: truthful cancellation UI and unchanged data.
2. Create again; actual save of `Mirra-4C-SAF-roundtrip.zip`, fresh readback/hash/package verification and saved confirmation.
3. Fixture mutates synthetic records only after checking the entire expected frame was still unchanged, including after export cancellation.
4. Actual OpenDocument cancellation; a separate assertion confirms the full mutated frame remains unchanged.
5. Actual document choice → validated summary → **恢复这份备份** → **确认完整替换** → application success message.
6. Restored assertion verifies every authoritative column/ID, referenced JPEG byte hash, four preference values/presence and search. **PASS**; no recomputation or substitute facts.

Prepare, mutate, cancellation assertion and restored assertion are **four actual opt-in invocations**, not four different test methods or a substitute for the actual SAF interactions. Safe `restore_baseline` cleanup also actually passed **1/1**, verified the full original frame and removed only that run's cache. The [evidence index](../evidence/phase4c/README.md) links actual screenshots. This is preservation of the measured original baseline, not proof of arbitrary private user data or physical-device compatibility.

## External process death and installed production bootstrap

- **Sandbox matrix: 22/22 actual PASS / 0 assumptions.** Each of the 11 durable boundaries had a real prepare → host force-stop → cold-start → assertion sequence, with new-process evidence and complete OLD/NEW resource checks. Sandbox assertions explicitly call sandbox recovery before sandbox stores open. This is **not** production Application bootstrap evidence.
- **Installed production bootstrap: 4/4 actual PASS / 0 assumptions**, two separate cycles: `images:published` before commit selects complete OLD; `COMMITTED` selects complete NEW. Each cycle actually executes prepare, external force-stop/cold-start and assertion.
- The installed assertion waits for **MirraApplication's own early recovery** rather than manually recovering the installed journal. It checks complete 12-table facts, all original preference types/values/presence, JPEGs and candidate FTS. The host replacement then restores the preserved original installed resources, verifies the original frame, and deletes only that fixture's cache.
- These cycles prove actual Android process-restart behavior at the selected points; they are not physical power cuts or every possible filesystem/hardware fault.

## Final gate — fresh results only

| Required evidence | Current record |
|---|---|
| Full unfiltered JVM after bootstrap/cache corrections | **553/553 PASS; 0 failure/error/skipped** |
| Reader epoch targeted GREEN | **2/2 actual PASS** |
| Installed production bootstrap: precommit OLD / committed NEW after final corrections | **4/4 actual invocations PASS; original baseline restored** |
| External sandbox process-stop cycles | **22/22 actual invocations PASS, all 11 boundaries; 0 assumptions** |
| Final offline installed SAF round-trip plus baseline restoration | **5/5 actual invocations PASS; formal SAF UI observed; full original frame checked; run cache removed** |
| Full unfiltered connected XML/runner actual / assumption counts | **397 discovered; 383 actual executed/PASS; 14 unmet assumptions; 0 actual business failure/error; Gradle success 19m28s** |
| lintDebug / assembleDebug / test APK | **PASS: lint 0 errors / 15 warnings / 1 hint; both APK builds PASS** |
| Final cover-install / normal and offline Start–Knowledge–Mine / network restore | **PASS; 16 measured current files unchanged across install before launch; normal and offline cold start/three entries observed; network restored 0/1/1** |
| APK path / bytes / SHA-256 | **`build/deliverables/Mirra-Phase4C-debug.apk`, 17,258,080 bytes; `877E5E521C363E8EEF719F4C75B158FA336E3CABEF2A584CFC8ED1180548AA35`** |
| Final installed quiescence after connected | **PASS: no Journal / monitoring / Overlay / active notification / Mirra-owned rule record; actual formal prepare → SAF → cancel succeeded; network 0/1/1; no ANR since final boot** |
| Diff/schema/secrets / implementation-validation commits | **Checks PASS; implementation/tests `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f`; this separate validation/evidence commit. Exact final remote HEAD/local equality/clean tree are in the delivery report and branch, not a self-referential SHA in this file** |

## Data and safety boundaries

- Room remains **v4**. Schemas 1–4 / Migration 1→2→3→4 have no current diff; no Entity/Column/Index migration or v5. Actual v4 **schema file SHA-256** checked: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`, not Room identityHash.
- Frozen analytics, timeline trust, Closeout facts, monitoring evidence, READY, DND policy and behavior thresholds are not redesigned; changes are storage lifecycle/admission and thin backup UI.
- Permissions/device ownership are not restored. API29–34 enabled owned rules whose inactive condition cannot be proven are conservatively refused; not a compatibility PASS.
- SAF readback proves returned bytes at that time, not permanent provider durability, offline behavior or cloud retention.
- Low-space tests use controlled available-space values, not a physically exhausted filesystem; later IO errors still propagate.
- Post-decision cleanup IO failure may retain harmless private operation material; interrupted backup work may occupy storage. Not mixed business data, but **not** complete automatic orphan cleanup.
- JVM/AVD cannot guarantee recovery after hardware corrupts all copies. Real power loss remains NOT RUN.
- Lint grew from 9 to **15 warnings**: six new findings are EmptySuperCall (2), UsableSpace (2), NewerVersionAvailable (1), UseTomlInstead (1). They are recorded, not relabeled existing or zero warnings. The task does not upgrade dependencies to satisfy a newer-version suggestion.

## Delivery status

The authorized complete feature and its final execution gates are complete. Delivery state: **`PHASE_4C_COMPLETE_AWAITING_REVIEW`**. No currently unresolved architecture safety blocker was identified in the implemented scope; platform/durability limits below remain explicit. Stop for independent review, not an automatic accepted/frozen decision; do not start 4D. No main merge or release.

### Final bootstrap responsiveness correction — verified

The final source check found that `runBlocking(Dispatchers.IO)` in `Application.onCreate` still blocks its Main caller while a pending Journal hashes/copies/fsyncs potentially large resources. The earlier OLD/NEW installed cycles prove selection safety for their small data, not large-recovery startup responsiveness. Actual targeted RED: 7 tests / 3 assertion failures / 4 passes. The minimal correction keeps ordinary synchronous construction compatible, while pending recovery runs on the maintenance IO scope before owners construct or OPEN is published. Actual targeted GREEN: 9/9 / zero failure/error/skipped, including cancellation before dispatch and pending-query failure. A completed intermediate full connected run used the pre-correction APK: 396 discovered / 382 actual PASS / 14 unmet assumptions / 0 actual business failure/error; Gradle exit 0, 25m27s. Do not relabel that run final post-correction evidence. Final JVM/build, installed/bootstrap and fresh complete connected subsequently passed with their exact distinct counts recorded here.

### Final restored-image cache isolation — verified

Fixed Coil 3.6.2 local bytecode confirms that the default File cache key does not incorporate file modification time. Replacing a storage generation can restore different JPEG bytes at the same path/ID; a retained Singleton memory cache can then display the former bitmap. Actual Room/Compose/Coil RED: 1/1 failed because restored blue JPEG bytes still displayed red pixels (red=0.99607843, blue=0). The unchanged pixel assertion then actually passed 1/1 after a shared per-container request namespace was added to all three image entry points. Two cache-key JVM regressions also pass. Ordinary recomposition retains the namespace; a replacement container uses a new one, so late old decoding cannot populate the new owner's key. Activity recreation may conservatively miss cache. No image bytes/metadata, UI design, storage format or dependency is changed. Fresh unfiltered JVM after both final corrections: **553/553, zero failure/error/skipped**; lint **0 errors / 15 warnings / 1 hint**, debug/test APK builds PASS. The complete connected and installed/post-correction gates remain separately recorded.

### Post-correction installed verification and environment history

- Final APK: installed production precommit OLD / committed NEW bootstrap cycles again actually passed **4/4 prepare/assert invocations**, with external force-stop/cold-start and verified restoration of all original resources.
- Final APK: actual offline SAF round-trip (`Mirra-4C-final-offline.zip`) passed prepare, mutation, cancelled-import preservation, restored-frame assertion and original-baseline cleanup: **5/5 invocations**. The real CreateDocument/OpenDocument interactions and separate full-replace confirmation were observed; all 12-table columns/IDs, JPEG bytes, portable preference value/presence and search matched. Active default network was `none`.
- Afterwards, final navigation smoke encountered hanging UIAutomator/ADB console on the dedicated AVD. No Mirra business assertion failed; original installed data had already been restored and verified. The last recorded ANR concerned NexusLauncher, not Mirra. The two hung snapshot clients and graceful console-close request were interrupted; only the command-line-verified dedicated emulator/QEMU pair was stopped, then the **same AVD** restarted with no wipe, data clear, uninstall, snapshot load/save or filesystem reset. At that point final navigation/network restoration and full connected were still required; the subsequent smoke/network result is recorded next. Preserve this environment interruption instead of presenting an uninterrupted run.
- After same-AVD restart, boot completed, last ANR was empty, and actual Start/Knowledge/Mine navigation responded. Final explicit `install -r` succeeded; **16 current DB/files hashes** matched before/after installation prior to launch. This proves that measured installation's current files, not arbitrary historical data preservation. Actual normal cold start and then offline cold start (active default network `none`) succeeded; all three entries were individually observed offline. Original airplane/Wi-Fi/mobile settings **0/1/1** were restored and read back. A new unfiltered connected run started only after those healthy checks.
- At the 2026-10-09 execution-session reset, that latest Gradle session ceased to exist; emulator/QEMU/Java processes had exited and no complete connected XML remained. It is an **incomplete environment-interrupted run**, not full PASS or an identified business assertion failure. Staged implementation, the final APK and already completed round-trip/553-JVM evidence remained intact. Only the same dedicated AVD was started again with unchanged userdata and no wipe/reset/snapshot import. After the initial cold-start timeout, the already running MainActivity and real Profile/Data Management UI responded normally; no ANR was recorded since boot. The subsequent final complete gate is recorded next.

### Final complete connected and quiescence — actual execution

- One fresh **unfiltered** `connectedDebugAndroidTest`, with the dedicated API37 target and leave-APKs-installed parameter: Gradle exit 0 / **BUILD SUCCESSFUL in 19m28s**. No parallel ADB UI interaction, JVM run or production edits during it.
- Final XML: **397 distinct testcase nodes / 383 actual executed and passed / 0 actual business assertion failures / 0 errors / 14 unmet assumptions**. XML represents those 14 assumptions as failure nodes (native skipped nodes = 0); this does not make them PASS or business failures. Never report 397/397 PASS.
- The 14 are **nine opt-in fixture methods + five permission prerequisites**. Of the nine, five are this delivery's SAF/sandbox/installed-bootstrap methods and were actually executed separately as recorded above (5/5, 22/22, 4/4 invocations, not five ordinary full-suite passes). Four historical 3D opt-in methods and five DND/Overlay/Notification permission cases remain **NOT RUN in this final full run**; no automatic permission changes were made to make them green.
- Final ordinary full suite includes snapshot 14, service 5, Android Journal 4, Camera 3, Data Management Compose 9, navigation 1, permanent reader epoch 2, lifecycle 2 and restored-image real-pixel 1 — all actual assertions passed. These are included in 383, not added again to inflate the total.
- Post-suite actual production cold start: Status ok / COLD / 4,141ms. Real Mine/Data Management rendered. The formal **创建完整备份** path completed preparation and opened Android CreateDocument, so its in-barrier Active Intent / Session / PENDING / runtime guard admitted the current quiet state. Back cancelled; UI truthfully stated current data unchanged and returned to normal operation. One transient UIAutomator dump during the Activity transition had no snapshot; a fresh read succeeded without resending the action.
- Readback before/after that cancellation: no private restore Journal, monitoring ServiceRecord, Mirra application-overlay window, active Mirra notification or Mirra condition-ID rule record. Network settings remain original **0/1/1**; last ANR is empty since the final boot. No physical device was operated; no wipe/clear/uninstall/system-time modification.
- Final source gate: 68 implementation/test App files; no credential pattern or raw DB/archive/user image; schema 1–4 blobs match the authorized base; no new Manifest permission. These are Codex implementation checks, **not** independent ChatGPT acceptance.
