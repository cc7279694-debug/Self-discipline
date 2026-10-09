# Phase 4D evidence index

Completed delivery / awaiting independent review: 2026-10-09 (Asia/Shanghai). Branch `codex/phase-4d-export-android-backup`. See the [delivery checkpoint](../../checkpoints/2026-10-09-phase-4d-export-android-backup.md) for the task contract, failure history, implementation and proof limits. This is not acceptance/freeze.

Implementation + tests: `5a5cbde268791d828f70a4126a872aaead455aca`. This evidence index and the final execution record are committed separately; the delivery report identifies that validation SHA and remote equality. The final source was not changed after the recorded regression.

## Primary sources

| Source | Evidence scope |
|---|---|
| [Android Auto Backup](https://developer.android.com/identity/data/autobackup?hl=en) | Version boundaries, independent cloud/D2D/cross-platform modes, recursive paths and the nine documented storage domains; OEM limitations of `allowBackup=false`. |
| [AOSP Android 17 FullBackup](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/app/backup/FullBackup.java) | `BackupScheme`: literal `fullBackupContent=false` disables the legacy scheme; unsupported debugger destination does not parse the XML modes. |
| [AOSP Android 17 BackupEligibilityRules](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/backup/java/com/android/server/backup/utils/BackupEligibilityRules.java) | Debuggable ADB eligibility can ignore `allowBackup`; cross-platform eligibility requires peer configuration. Source inspection is not an executed transport test. |
| [OWASP CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection) | Untrusted spreadsheet text, ASCII/fullwidth formula markers, separators, quotes and control characters. Initial-import mitigation is not universal protection after editing/re-saving or across all spreadsheet software. |

## System-backup policy applicability

Mirra uses `minSdk=23`, `targetSdk=37`, `allowBackup=false`, literal `fullBackupContent=false` and the modern `mirra_data_extraction_rules` resource. No custom BackupAgent, permissions or dependencies are introduced by this policy.

| Platform | Configured policy |
|---|---|
| API 23–30 | Opt out with `allowBackup=false`; disable the entire legacy Full Backup / Restore scheme with boolean `fullBackupContent=false`, rather than an enabled XML resource ID. |
| API 31+ | Explicit all-domain exclusions in both `cloud-backup` and `device-transfer`; D2D is covered separately because some implementations ignore the general opt-out flag. |
| Android 16 QPR2 / API 36.1+ | Also explicitly exclude all domains in `cross-platform-transfer platform="ios"`. There is no iOS peer and no `platform-specific-params` opt-in. |

Each modern mode has nine recursive `path="."` exclusions, without any `<include>`:

`root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, `device_sharedpref`.

The directory-wide rules cover Room main/WAL/SHM/journal files, DataStore, JPEGs, temporary files, restoration journals, runtime ownership and future files within those domains. Cache/no-backup locations are also standard framework exclusions. Manual validated full backup remains the portable recovery path; readable JSON/CSV exports do not replace it.

AOSP's debugger `ADB_BACKUP` destination can admit a debuggable app despite `allowBackup=false`, then bypass both XML rule formats. The selected boolean `fullBackupContent=false` makes the legacy scheme disabled before default file traversal, closing that source-confirmed fallback. This is a source/configuration conclusion, not a measured debugger transport result.

## Actual execution status

| Gate | Current recorded status |
|---|---|
| Post-review full JVM | **607/607 PASS**; 0 failures, errors or skipped tests |
| Targeted UI | **8/8 PASS** |
| Lint | **PASS**; 0 errors, 17 warnings, 1 hint. The warnings comprise 15 inherited from Phase 4C and 2 `UsableSpace` warnings. |
| `assembleDebug` / `assembleDebugAndroidTest` | **PASS** |
| Full single unfiltered connected | **418 discovered / 404 actual PASS / 14 unmet assumptions / 0 actual assertion failures or errors**; 16m29s, build successful, APK-preservation flag used |
| Installed compiled manifest/XML checks | **3/3 actual PASS**, in the full run; no transport invoked |
| Installed SAF export journeys | **PASS** on API 37, offline, through the formal UI and DocumentsUI; details below |
| Phase 4C full-backup round-trip regression | **PASS** on the installed API 37 app; details below |
| Final delivered APK / checksum | **PASS**; `build/deliveries/phase4d/Mirra-phase4d-debug-20261009.apk`, 17,473,097 bytes; SHA-256 `82E27709B45D9AC4FD4E76FE8F9046A9A110BF2893B90C6E1251DAD0F50A375B` |

Source/JVM XML checks prove the source-policy contract. Executed installed compiled manifest/resource checks establish APK packaging, resource binding and explicit exclusions. Neither invokes a backup transport, proves actual cloud/D2D/iOS transfer or demonstrates every OEM's behavior. The compiled resource result is recorded separately from the full connected aggregate; unmet assumptions are not PASS.

All new Android cases executed: export service **10**, export UI **8**, compiled policy **3**, total **21/21 actual PASS**. The full XML reports 14 raw `failure` nodes containing `AssumptionViolatedException`, with skipped attributes 0; the accurate distinction is 9 opt-in fixtures and 5 unmet permissions, not business assertion failures or 418/418 PASS. Five separate actual installed 4C journey invocations are recorded below; the other 8 opt-in methods and 5 permission-required cases remain NOT RUN this turn. Existing 4C snapshot/service/journal/UI/navigation suites passed in the same full run.

### Installed journeys and data preservation

- The formal export UI completed JSON save, CSV save, JSON cancellation and CSV cancellation through DocumentsUI while offline. After cancellation, private export temporary files were **0**.
- The actual JSON (13,228 bytes) and CSV ZIP (7,485 bytes) were parsed locally: all **105 fields** in the 12 one-row tables matched, and **13 relational links** matched original fixture IDs. The JSON exclusion metadata says no image bytes, no runtime ownership and not a backup. These local exports are ignored and not published. Real-Room golden tests independently compare all projected fields against source rows and check Chinese/special text and formula handling.
- With a prepared JSON export awaiting the SAF picker, the app was force-stopped. After exiting the old picker and performing a true cold start, prior-process temporary files decreased **1 → 0**.
- The official Phase 4C installed fixture ran `prepare`, `mutate`, `assert-mutated`, `assert-restored` and `restore-baseline`: **five invocations, each with one actual PASS**. Before mutation, the canonical digest matched across all 12 tables, JPEGs and 4 portable preferences.
- The actual UI completed create backup → OpenDocument cancel → validate → explicit replacement restore. All fields, IDs, relations, timeline, JPEGs, portable preferences and search matched after restoration. The final original baseline was restored.
- A cover installation with `install -r` preserved the hashes of the **19 files present at that check**. This is evidence for those current files, not proof of historical retention.
- Recorded network state changed **0/1/1 → 0/0/0 (offline) → 0/1/1 (restored)**. No physical device was operated.

Internal read-only review found P2 export orphan cleanup and P3 typed-error issues. Both were repaired, with failing-then-passing tests and a source re-review. This is internal review evidence, **not ChatGPT acceptance**.

### Final installed smoke and reviewed screenshots

After full regression, cold start and Start / Knowledge / Mine / Data Management were exercised. Installed read-only checks found zero active Intent/Session/PENDING/pending DND contexts, `quick_check=ok`, no monitoring service or restore journal, zero private export files, and total authoritative rows zero matching the original baseline. Network remained restored. Only the dedicated AVD was used.

- [Official Data Management UI](data-management.png): actual installed, empty/original baseline, normal network restored, 1080×2400. The JSON/CSV buttons are reachable alongside unchanged full backup/restore.
- [Actual export privacy confirmation](export-consent.png): warns private notes and risk-app names/packages, unencrypted files, missing JPEG and non-restorable format. Cancelled after capture; no new export was prepared.

These are generic installed screenshots, not nonempty automation fixtures or proof of OEM/TalkBack/320dp pixel coverage. Both were visually checked before publication; no private payload or device serial is included.

## Readable-export limits

JSON exports use a **12-table, 105-field whitelist**. CSV exports contain the **12 tables plus metadata, schema and README**. Both formats enforce 100,000 rows per table, 1,000,000 total rows, 1 MiB per encoded row and 512 MiB output. The 10-minute limit is cooperative cancellation, **not a hard timeout on a document provider**. Existing Phase 4C JPEG staging adds its own I/O constraints; readable-export limits do not remove that cost.

## Not run / proof limits

- **NOT RUN:** API 23–36 runtime matrix, physical devices, OEM matrix, TalkBack and release/Play verification.
- **NOT RUN:** actual cloud, D2D, cross-platform and ADB backup/restore transport journeys.
- No guarantee is made against privileged historical ADB restore pre-clearing app data, OS/OEM forced clearing, root or run-as access. The [AOSP FullAdbRestoreEngine](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/backup/java/com/android/server/backup/adb/FullAdbRestoreEngine.java) has system-side pre-clear behavior; the app's exclusion policy is not authority over privileged deletion.
- No private raw database, archive, note/image payload, credential, device identifier or unfiltered device log belongs in this evidence index. Only reviewed synthetic/public evidence and accurate execution summaries should be linked here.
