# Phase 3D-3 evidence

Captured on 2026-10-05 in the dedicated `Mirra_API_37` AOSP AVD (Android 17 / API 37). These are actual emulator pixels, not concept images. Only dedicated test books, Notes and synthetic historical labels appear; no serial or private content is included.

## Controlled fixture / automation evidence

The following screenshots are produced by `ReadingRecordUiTest` / `ReadingRecordNavigationTest`, not by a real 30-minute monitored reading. The FULL/PARTIAL/NONE states are deliberately supplied facts and **do not prove monitoring or physical-device capability**.

| Screenshot | What it proves visually |
| --- | --- |
| [summary-full](reading-record-evidence/summary-full.png) | Shared normally-ended record: 18 pages, 30 minutes total, 20 minutes trusted focus, 2 Notes and true whole-session counts. |
| [summary-partial](reading-record-evidence/summary-partial.png) | Incomplete monitoring is explained, not replaced with zero effective time. |
| [summary-none](reading-record-evidence/summary-none.png) | No monitoring is stated explicitly; ordinary pages/duration/Notes remain readable. |
| [expanded timeline](reading-record-evidence/timeline-expanded.png) | Human segment labels with real fixture boundaries, local timestamps and historical App-label snapshot; no package names or internal enums. |
| [history record](reading-record-evidence/history-record.png) | The same source/projection is rendered from book history. |
| [search record](reading-record-evidence/search-record.png) | The same source/projection is rendered from a SESSION search result. |
| [effective book pace](reading-record-evidence/book-effective-pace.png) | Real in-memory Room fixture with 3 recent trusted Sessions: 54 pages/hour, 4h52m effective remaining time; original natural-date threshold remains independent. |

The capture roots use the actual MirraTheme and safe-inset Scaffold. Six controlled viewport tests separately assert actual 320/360/411dp width at fontScale 1/2, scroll and click expand/complete/back/DND-release retry, and check touch height >=48dp (pixel rounding tolerance 0.1dp). These are automation assertions, not TalkBack or physical-phone testing.

## Actual offline App path

These three screenshots are from the installed Debug App and a new dedicated book, created through real UI while Wi-Fi/mobile data were off. No database facts were manually inserted for this path. The Session was intentionally unmonitored because Usage Access remained at its original state.

- [Offline Summary](offline-summary.png): actual 1→3 pages, 1 Note, normally ended, no monitoring/effective time.
- [Offline History record](offline-history.png): same closed facts reached through the book history.
- [Offline Search record](offline-search.png): numeric query `3` hits the newly generated Session Summary; same shared reading record, not a Note or book-detail result.

The Note was observed as automatically saved before confirmation. Real read-only checks confirmed NORMAL/NONE/COMPLETED, no Active Session, preserved original mainline and old-page Note, and unchanged closed facts after Force Stop/cold start. Cover install used `install -r` only and preserved the preceding dedicated data. Brief initial UI-dump readiness failures are retained in the checkpoint, not hidden as product PASS.

## Boundaries

- The fixture date in timeline screenshots is intentional; no system-clock modification was performed.
- Original first-pass capture inspection revealed one missing Theme wrapper in the book test harness. Only the test root was corrected; production theme, brand color and business code were not changed for this refinement. Final screenshots are recaptured by the full gate.
- Verbose logs, UI hierarchy dumps and any local database checks remain outside tracked evidence. APKs are build artifacts, not committed.
- API23–36, OEM / full physical-device matrix, TalkBack, release/Play, real power loss and actual clock changes remain `NOT RUN`.
- OnePlus 13T daily use is not claimed as compatibility PASS. 3D-4 has not started.
