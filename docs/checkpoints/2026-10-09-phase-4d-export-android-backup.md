# Phase 4D — JSON / CSV export and Android backup policy

## Authorization and baseline

- User authorized the complete usable Phase 4D in one delivery; internal tasks do not require separate acceptance freezes. Do not enter 4E, merge main or release.
- Parent implementation/validation baseline: `62ce659029fb2269902a07145c8cdc87379016f5`; Phase 4C independent review: user-reported `REVIEW_COMPLETE — PASS WITH NOTES`.
- Branch: `codex/phase-4d-export-android-backup`. Room v4, schemas 1–4 and migrations remain unchanged.

## Delivered implementation

- Readable export reuses the frozen Full Backup service's validated, isolated snapshot. It does not copy the live database or invent another historical interpretation. This conservative reuse temporarily includes referenced JPEGs in private staging; the final JSON/CSV includes metadata only. Existing activity/cleanup gates and snapshot size limits apply.
- Explicit field whitelist; omit device ownership, private image paths and internal active-slot plumbing. Stream one SQLite cursor row at a time. JSON preserves exact text/null/zero; CSV records spreadsheet-safe text encoding, fixed columns and units.
- Data Management adds flat JSON/CSV entries, explicit sensitive-data confirmation and SAF save/cancel/error handling alongside the existing complete backup/restore UI.
- System automatic backup is denied with `allowBackup=false`, literal `fullBackupContent=false` for the complete legacy scheme, and modern XML exclusions for all 9 domains separately in cloud, D2D and cross-platform extraction. There is no new BackupAgent. Rules are not an OEM transport guarantee.

## TDD / verification history

The following entries preserve the intermediate state when recorded. Historical “pending / in progress” statements are superseded by the final GREEN and installed-journey section below, not the current delivery status.

- Initial targeted Android compilation caught an incorrect test reference to the existing top-level `AUTHORITATIVE_TABLES`; corrected the test reference before obtaining behavioral RED. No source facts or frozen contracts changed.
- Codec + policy initial behavioral RED: 29/29 failed against stubs/original policy. ViewModel RED: 13 executed / 12 expected failures / 1 compatibility pass. Service Android RED: 8/8 failed against the unimplemented service. The two initial compilation corrections (top-level constant and heterogeneous array type) are not behavioral RED.
- Intermediate codec/UI/old-ViewModel JVM 49 tests: 47 pass / 2 test-assumption errors. Corrected README case checking and schema JSON's omitted `notNull=false` interpretation without changing production assertions; subsequent 49/49 PASS.
- A targeted Android service run selected only that class despite a requested comma-separated class filter: 9 executed / 8 PASS / 1 cleanup-publication regression failure. The service was corrected to finish upstream snapshot cleanup and check current ownership before publishing; a further owner-retirement regression was added. Final full-suite verification of these corrections is pending below. Do not count unselected classes as executed.
- An uncommitted deny-BackupAgent prototype and its isolated tests were discarded after source inspection showed the simpler literal legacy `false` strategy; its failed prototype run is not final policy evidence and did not touch user stores. No new framework remains.
- Initial full JVM: **595/595 PASS**, zero failure/error/skipped. Export Compose targeted: **8/8 actual PASS**, including real Android activity-result registry, 320dp/fontScale2/48dp semantic checks and cancellation cleanup. These are controlled UI tests, not actual DocumentsUI save evidence.
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: PASS. Lint **0 errors / 17 warnings / 1 hint**: 15 inherited from 4C plus 2 `UsableSpace` warnings in the export service. Space checks intentionally use conservative available space, do not claim clearable-cache allocation or provider free-space reservation. No dependency upgrade/suppression was used.
- Full unfiltered connected and installed actual SAF/4C round-trip remain in progress. No historical connected result is substituted for this run.

### Internal review correction before the final gate

- Internal Codex review (not ChatGPT independent acceptance) found a P2 export-only process-death retention gap. The first full connected run was intentionally stopped before completion; it is **INCOMPLETE**, not PASS or an identified business assertion failure. After cancellation the dedicated emulator process was no longer connected. Restarted exactly `Mirra_API_37_Phase4B_Final` with unchanged userdata, no snapshot/wipe/clear/uninstall. API37/boot1 and the existing target package were read back before proceeding; actual network settings remained 0/1/1.
- Added private `ExportWorkspace`: one random identity per process, shared across storage generations; operation directories use `<process UUID>_<operation UUID>`. Startup IO and each preparation reclaim only prior-process or legacy UUID operation directories inside the dedicated export base. Current-process work, unknown names and ordinary files are retained. Frozen canonical/direct-entry cleanup checks apply; list/delete/sync failures propagate. No business resource, Restore Journal or 4C flow is changed.
- Workspace JVM TDD: **10/10 actual RED** against the stub, no compilation errors; final GREEN pending below. Path-escape and deletion failures are controlled fault injection, not Windows symlink privilege or Android power-loss proof.
- Two export-error ViewModel tests obtained **2/2 actual RED**. `WRITE_OR_READ_FAILED` now uses the phase-specific export fallback (preparation does not invent an external file; saving warns of a possible partial external document). Invalid prepared data asks for a new export, not a full backup. Existing Android save-error test was strengthened with the typed error; final GREEN pending below.
- A preceding filter used the superseded first test name and actually executed only the other one, failing as expected. It is not recorded as two tests. No production assertions were deleted/relaxed and no long timeout was added.

## Boundaries

No entity/schema/migration, frozen reading/Closeout/Monitoring/DND/Trends/Insights algorithm, permission, dependency or physical-device changes are authorized. API23–36 runtime, OEM/physical matrix, TalkBack, release/Play and real system transport remain NOT RUN unless actually executed and separately evidenced. Dedicated AVD data must be preserved; no wipe, clear or uninstall.

## Post-review GREEN and installed official journeys

- Internal read-only source re-review confirmed the P2 process-owned workspace and P3 typed export errors were corrected; no further Critical/Important finding was reported. This is Codex internal review, **not** ChatGPT independent acceptance. Workspace **10/10 GREEN**, export ViewModel **15/15 GREEN**, codec **26/26 GREEN**, source policy **3/3 GREEN** in the final full JVM run.
- Final unfiltered JVM: **607/607 actual PASS**, zero failure/error/skipped. `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: PASS; lint **0 errors / 17 warnings / 1 hint** (15 inherited plus 2 conservative `UsableSpace` checks). No assertion relaxation, dependency change or warning suppression.
- Installed only on the positively identified dedicated API37 AVD. Target and test APKs used `install -r`; **19 current private files** had equal before/after SHA-256 values before launching. This proves that particular coverage installation, not preservation across unrelated historical installations/framework removals. No physical device, clear, wipe or uninstall was used.
- Used the unchanged opt-in `InstalledBackupJourneyFixture` to protect the initial installed baseline, then add synthetic rows with original IDs across **all 12 authoritative tables**, a referenced JPEG and four portable preferences. Its recorded initial baseline was empty across those tables. No real private payload is published.
- With Wi-Fi and mobile data both disabled, official Mine → Data Management → privacy confirmation → Android DocumentsUI saved actual JSON and CSV ZIP. JSON was **13,228 bytes**; CSV ZIP **7,485 bytes**, with 12 CSVs plus metadata/schema/README. Local parsing compared **all 105 projected fields** across the two artifacts; counts, IDs, nulls, units and exclusion metadata were checked. Real-Room service tests separately compare the whitelist against every original DB field and exercise Chinese, quotes/newlines, formula strings and source preservation. Local exported documents are ignored evidence, not Git attachments.
- Both JSON and CSV actual SAF Back/cancel returned the truthful cancellation message; private export file count was **0** afterward. No current business change was observed.
- Real process-death cleanup: prepare JSON and leave DocumentsUI open → private prepared-file count **1** → force-stop only Mirra → the first launch request encountered the old DocumentsUI task without starting Mirra; did not mistake this for completed cleanup → leave old picker → actually cold-start Mirra → private export file count **0**. The second observation is startup/prepare reclamation, not a promise of instantaneous or hardware-durable zero remnants.
- Official 4C regression: create/save a complete backup through DocumentsUI, then fixture `mutate`. Before mutation it asserted the entire 12-table canonical frame, referenced JPEG bytes and four value/presence preferences were unchanged after the export/save/cancel/force-stop operations. The fixture then changed only its synthetic note/JPEG/theme.
- Actual OpenDocument cancellation was followed by `assert` with expected mutated data: PASS. Selected the actual complete backup, displayed its successful validation, separately confirmed full replacement, and observed restored-success UI. A second fixture `assert` verified all original IDs, fields, relationships, timeline, JPEG bytes, preferences and rebuilt search. Finally `restore_baseline` used the official restore service and verified the exact original baseline, cleaning only this fixture's owned cache.
- Five separate opt-in instrumentation invocations (`prepare`, `mutate`, mutated `assert`, restored `assert`, `restore_baseline`): **5 actual executions / 5 PASS / 0 assumptions or failures**. They are not inferred from default full-suite assumptions.
- Network readback: original airplane/Wi-Fi/mobile **0/1/1** → offline **0/0/0** → restored **0/1/1**. No platform permission was changed for Phase 4D.
- Final single unfiltered connected run completed in **16m29s**, `BUILD SUCCESSFUL`, explicitly retaining installed APKs and targeting only the dedicated AVD. **418 discovered / 404 actual PASS / 14 unmet assumptions / 0 actual business assertion failures or errors**. Initial incomplete connected history remains above; it is not spliced into this final run.
- The runner's XML represents the 14 assumptions as raw `<failure>` text nodes (`AssumptionViolatedException`), with suite skipped attributes 0. Inspected their actual messages and classified them explicitly; do not call this 418/418 PASS or claim XML literally has zero failure nodes. An initial PowerShell property-based parser did not extract those text-only nodes correctly; XPath `SelectSingleNode('failure').InnerText` produced the accurate classification. This was a reporting-reader correction, not a new test/business failure.
- All new Android suites actually executed: `DataExportServiceTest` **10/10**, `DataExportUiTest` **8/8**, `AndroidBackupPolicyResourceTest` **3/3** (21 total, zero assumptions). The three resource tests parse the installed binary manifest and compiled XML; they do not invoke a system transport.
- Existing 4C automated Android regressions in the same full run: BackupDatabaseSnapshot **14/14**, FullBackupService **5/5**, RestoreJournal **4/4**, DataManagementUi **9/9**, DataManagementNavigation **1/1**. The five actual installed journey stage invocations above remain separate evidence.
- The 14 default assumptions are **9 opt-in fixtures** (4 historical 3D, 1 installed 4C journey, 2 installed bootstrap, 2 sandbox restore-process) and **5 permission prerequisites** (3 DND, 1 overlay, 1 notification). Only the installed journey was opted in and actually executed this turn; the other 8 opt-in methods and 5 permission-required methods are **NOT RUN this turn**, not inferred PASS from historical results. No new platform permissions were granted to manufacture results.
- Post-suite cold start and Start / Knowledge / Mine / Data Management smoke passed. Read-only installed SQLite checks: **0 active Intent / 0 active Session / 0 PENDING / 0 ACTIVE-or-pending DND context**, `quick_check=ok`, all 12 authoritative tables total **0** matching the recorded original baseline. No monitoring service or restore journal remained; private production export files remained 0 and original network 0/1/1 was retained. No physical device was touched.
- Two reviewed, actual installed generic UI screenshots are linked in the evidence index. They contain no note/body/history or device identifier and are not the automation fixture's nonempty-data screenshots.

### Artifact and resource freeze

Final debug artifact (not committed): `build/deliveries/phase4d/Mirra-phase4d-debug-20261009.apk`, **17,473,097 bytes**, SHA-256 **82E27709B45D9AC4FD4E76FE8F9046A9A110BF2893B90C6E1251DAD0F50A375B**. It is byte-identical to the APK used for the installed official journeys and built for the final suite; no release is created.

Schema file hashes (not Room identityHash), unchanged from parent:

| Schema | SHA-256 |
|---|---|
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

### Known limits retained

- Readable export inherits frozen 4C activity/cleanup refusal and snapshot validation; staging temporarily includes referenced JPEGs before projecting metadata only. Extra temporary space/I/O and 4C image validation are deliberate, not a new JPEG-free snapshot engine.
- Each table is streamed through one cursor; limits are 100,000 rows/table, 1,000,000 total rows, 1 MiB per raw/encoded row, 512 MiB expanded/final output and a 10-minute cooperative processing budget. Oversized input **fails**, never truncates user text. Frozen synchronous snapshot and arbitrary SAF-provider blocking cannot be given a hard real-time interruption guarantee.
- CSV apostrophe safety is for initial spreadsheet import, not universal immunity after editing/re-saving; CSV tools may collapse null/empty distinction. JSON retains exact original text/null and both formats remain ineligible for Full Restore.
- Fault-injected low space/provider/workspace failures and host JVM concurrency do not prove physical disk exhaustion, arbitrary provider durability or real hardware power-loss safety. A partially written external SAF document may remain on failure; the UI warns and live facts are not mutated.
- Compiled/source Android policy checks are distinct from actual system transport. Cloud/D2D/cross-platform/ADB backup or restore transport, API23–36, OEM/physical/TalkBack/release/Play and real power loss/manual clock remain **NOT RUN**. No OnePlus result is claimed.

## Delivery state

**Phase 4D complete / awaiting independent review.** JSON and CSV ZIP are available in the official UI; actual offline SAF save/cancel and the existing full-backup restore round-trip were verified. Room v4, schema/migrations and frozen business cores are unchanged. No Phase 4E, main merge or release. This is not accepted/frozen and does not replace ChatGPT independent review.

Implementation + tests HEAD: `5a5cbde268791d828f70a4126a872aaead455aca` (`feat(data): add readable exports and safe android backup policy`), directly based on `62ce659029fb2269902a07145c8cdc87379016f5`. Validation documentation is a separate subsequent commit; its exact SHA and final local/remote equality are reported after commit/push. Only source/tests, this checkpoint/current state and minimal reviewed evidence are committed. The APK, private export/backup artifacts, raw XML/device logs and identifiers are not committed.
