# Phase 4A — Trends Foundation execution contract

Status: in progress. Implementation authorization: 2026-10-07. Parent: `cc5180762d9f735754ce3a5b0759089aefe6dcc9`. Branch: `codex/phase-4a-trends-foundation`.

This records the user's approved implementation contract and its code mapping; it is not a new product plan or independent acceptance.

## Frozen contract

- Start cohort is Intent.createdAt. Conversion = CONVERTED / (CONVERTED + ABANDONED + TIMEOUT); open excluded. Later Session outcomes never undo conversion. Bad associations retain conversion but expose unavailable latency/stable and data issues.
- Typical start/stable/recovery durations use exact median with overflow-safe midpoint. Stable is a recorded milestone, never a null-as-failure success rate.
- Maintain cohort is Session.endedAt. Reuse unchanged SessionTimelineValidator; only COMPLETE_TRUSTED enters complete aggregates. Reuse its effectiveFocusMillis, preserve trusted zero versus unavailable. Deep is actual persisted DEEP_FOCUS only.
- Recover requires same-session RECOVERY.relatedSegmentId -> DISTRACTION. Success links to adjacent new FOCUS; interrupted links to old RECOVERY with confirmation within successor DISTRACTION start..Session end. Missing/conflicting/broken evidence is UNKNOWN, not failed. Break/Allowance returns are not primary attempts. ABNORMAL excluded from primary Recover.
- One AnalyticsTimeContext per load, local 7/30/90 current/previous natural-day windows, ALL has no previous. Counts/durations absolute deltas; fractions compare percentage points, never relative growth from zero.
- Bounded read snapshot: Intent JOIN page; Session page + Context/Segment/Event batches <=800. LIMIT 801 lookahead avoids a trailing empty query at exact multiples; raw facts released per batch. Exact medians retain only scalar duration samples, not complete histories.
- Global history single JOIN, descending (endedAt,id), LIMIT 51, return 50, cursor at returned row50. Existing SessionSearchDetailRoute/ReadingRecord reused.
- Mine only flat '查看趋势'/'阅读记录' entries. No Start change, top tab, charts, scores, subjective state, Insights, Backup or Export.
- Room v4, schemas1–4/Migrations/frozen Phase2/3 rules unchanged. A measured necessary index or frozen contract change requires STOP/review.

## Execution tasks / interfaces

| Task | Mapping | Verification |
| --- | --- | --- |
| 1 | domain/trends models, windows, bounded accumulator; frozen Validator reused | JVM RED→GREEN for conversion/medians/trust/recovery/time boundaries |
| 2 | IntentDao/SessionDao/FocusDao read-only pages and TrendsRepository | Room RED→GREEN; SELECT budgets0/1/800/801/1601; EXPLAIN and10k+ measurements |
| 3 | lightweight GlobalReadingHistoryRepository + profile History ViewModel/Screen + route | Room keyset50/51/same-ms plus state/back/detail reuse |
| 4 | TrendsViewModel/Screen, AppContainer/TestAppContainer and Mine callbacks | JVM/Compose load/error/selection, 320/360/411dp × font1/2, >=48dp |
| 5 | final integration and evidence, no new capability | unfiltered JVM/connected, lint/build, schema hashes/scope, controlled screenshots, install/offline |

Shared interfaces: Task1 source/projection consumed by Task2 and Task4; Task3 history repository consumed by Task4 container/navigation. These are additive, not replacements for frozen analytics/detail engines.

## Safety and evidence

Only explicitly identified dedicated API37 AVD is eligible for device commands; no physical devices, wipe, clear or uninstall. Preserve installed data with keep-installed connected parameter and cover install. Tests use in-memory Room/sandbox fixtures. Production failures, environment issues and unmet assumptions are reported separately.

Historical Phase3 evidence remains unchanged. API23–36/OEM/full physical matrix/TalkBack/release-Play remain NOT RUN unless actually executed. AOSP results do not imply OnePlus13T compatibility.

## Progress ledger

- Task1: implemented; stub RED27/27 plus2/2, targeted GREEN29/29. Internal review found2 conservative Recovery gaps;6 added tests produced5 expected failures and1 passing earlier-result control, then final targeted GREEN35/35 (0failure/error/skipped). No independent user acceptance claimed.
- Task2: bounded repository Room RED4/4→GREEN4/4; measured10,001-fact reads1,582/1,217/1,162ms with65 SELECTs. Shared history JOIN/projection belongs to this compile-safe DAO foundation. No new index required by current evidence; larger-scale scans remain a measured follow-up risk.
- Task3: VM6/6 GREEN; History Room4/Compose10/history-navigation1 GREEN15/15 after date/scroll test corrections. Reuses existing detail; fixed cutoff is not a long-lived multi-page DB snapshot.
- Task4: Trends VM6/6 GREEN; Compose18/18 in the first UI run, with a cohort-copy assertion added after review. Trends navigation integration GREEN pending.
- Task5: pending. No final completion claims before fresh full regression and integration evidence.
