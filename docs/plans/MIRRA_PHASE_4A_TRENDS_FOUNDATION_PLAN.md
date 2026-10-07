# Phase 4A — Trends Foundation execution contract

Status: accepted / frozen — PASS WITH NOTES. Independent review conclusion confirmed by the user: `REVIEW_COMPLETE — PASS WITH NOTES`. Implementation authorization and acceptance: 2026-10-07. Parent: `cc5180762d9f735754ce3a5b0759089aefe6dcc9`. Branch: `codex/phase-4a-trends-foundation`. Stop before 4B; 4B has not started and requires separate authorization.

Accepted production HEAD: `e799997002d7c8d4dbb833c7452d194e8bad5d9f`. Accepted validation HEAD: `614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693`. Task5 validation is not the last production-code commit.

This records the user's approved implementation contract and its code mapping, now accepted after ChatGPT independent code/evidence review. The progress ledger preserves implementation-time execution evidence; it is not a new product plan or a claim that Codex performed the independent acceptance. This Acceptance Freeze changes documents only and does not rerun tests or devices. See the checkpoint's [Independent Acceptance and Phase 4A Freeze](../checkpoints/2026-10-07-phase-4a-trends-foundation.md#independent-acceptance-and-phase-4a-freeze) for the complete accepted scope and retained PASS WITH NOTES.

## Frozen contract

- Start cohort is Intent.createdAt. Conversion = CONVERTED / (CONVERTED + ABANDONED + TIMEOUT); open excluded. Later Session outcomes never undo conversion. Bad associations retain conversion but expose unavailable latency/stable and data issues.
- Typical start/stable/recovery durations use exact median with overflow-safe midpoint. Stable is independent of Start Conversion: `stableStartedAt == null` is unconfirmed, not failed; no Stable success score/rate.
- Maintain cohort is Session.endedAt. Reuse unchanged SessionTimelineValidator; only COMPLETE_TRUSTED enters complete aggregates. Reuse its effectiveFocusMillis, preserve trusted zero versus unavailable. Deep is actual persisted DEEP_FOCUS only.
- Recover requires same-session RECOVERY.relatedSegmentId -> DISTRACTION. Success links to adjacent new FOCUS; interrupted links to old RECOVERY with confirmation within successor DISTRACTION start..Session end. Missing/conflicting/broken evidence is UNKNOWN, not failed. Break/Allowance returns are not primary attempts. ABNORMAL excluded from primary Recover.
- One AnalyticsTimeContext per load, local 7/30/90 current/previous natural-day windows, ALL has no previous. Counts/durations absolute deltas; fractions compare percentage points, never relative growth from zero.
- Bounded read snapshot: Intent JOIN page; Session page + Context/Segment/Event batches use <=800 Session IDs (not a <=800 Segment/Event-row bound). LIMIT 801 lookahead avoids a trailing empty query at exact multiples; raw facts released per batch. ALL exact medians retain O(N) scalar duration samples, not complete histories or constant-space.
- Global history single JOIN, `(endedAt DESC, id DESC)`, LIMIT 51, pageSize50, cursor at returned row50. Retain NORMAL / ABNORMAL / PARTIAL / NONE / legacy ended Sessions. Existing SessionSearchDetailRoute/ReadingRecord reused; no second detail implementation. Fixed `snapshotNow` is not a long-lived SQLite snapshot across pages; late older endedAt facts may require re-entry for full visibility.
- Mine only flat '查看趋势'/'阅读记录' entries. No Start change, top tab, Dashboard, charts, scores, ranking, streak, AI judgement, subjective state, Insights, Backup or Export.
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
- Task4: Trends VM6/6 GREEN; final Compose19 + navigation2 GREEN21/21. Explicit cohort labels, all ranges, unknown/empty/errors, narrow/double-font accessibility and own-origin Back verified.
- Task5: completed execution. Full JVM 374/374; one complete unfiltered connected run: 333 discovered / 324 actual PASS / 9 unmet assumptions / 0 business failures, including all 39 new 4A cases actually PASS. lint/build, frozen schema checks, 18-current-file cover-install preservation, ordinary/offline cold start and actual empty navigation/back paths verified. Controlled nonempty detail navigation uses real in-memory Room; extra manual fixture creation did not complete and is not claimed PASS. See checkpoint/evidence for ANR/frame/bridge failures and all NOT RUN boundaries. Final validation commit is separate from Tasks 1–4; no 4B.

## Acceptance Freeze

Phase 4A is accepted / frozen — PASS WITH NOTES. The original internal reviews and targeted/full execution records above remain historical; user-confirmed ChatGPT independent review is the acceptance authority, not a newly executed Codex test run. Room v4 / schemas1–4 / Migration1→2→3→4 remain unchanged, with no Entity/Column/Index migration or Schema v5. v4 schema-file SHA-256: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` (not Room identityHash).

All eight PASS WITH NOTES, the 4 opt-in and 5 permission-prerequisite NOT RUN cases, and historical failures are retained in the checkpoint's acceptance section. The evidence index remains the original Task5 execution snapshot, not an override of this formal status. No long-term decision changed; DECISIONS and the Phase4 Master Plan are unchanged. No production/test/resource/configuration changes, no new execution PASS counts, no Phase4B.
