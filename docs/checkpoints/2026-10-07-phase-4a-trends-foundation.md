# Phase 4A — Trends Foundation

Status: in progress / not accepted. Parent Planning Freeze: `cc5180762d9f735754ce3a5b0759089aefe6dcc9`.

## Goal and scope

Truthful Start/Maintain/Recover trends and flat Mine entries plus paged global reading history. Inherit frozen Phase2/3, ReadingRecord, EffectiveReading, Validator, Mirra Blue and brand. Room v4 remains unchanged.

## Execution evidence

Implementation and verification are in progress. See [execution contract](../plans/MIRRA_PHASE_4A_TRENDS_FOUNDATION_PLAN.md).

- Domain TDD: empty aggregation stub produced27/27 expected failures, followed by2/2 added cases. Targeted GREEN29/29, 0failure/error/skipped. Initial compile interference from an incompletely saved parallel ViewModel signature was not counted as assertion RED.
- Trends/History ViewModel stubs:6/6 expected failures each; implementation follows these actual failures, not a claimed green run.
- Domain internal review raised missing/NONE context and conflicting local recovery coverage.6 extra tests produced5 expected failures and1 protection control, then35/35 GREEN. Earlier confirmed recovery remains valid before later monitoring loss; incomplete/conflicting local proof is UNKNOWN. Frozen Validator/monitoring were not changed. This is internal engineering review, not ChatGPT independent acceptance.
- ViewModel targeted GREEN12/12. Empty repository produced Room RED4/4. Separate installed test runner produced history Room4 + Compose10 + navigation2 expected failures16/16. The initial combined Gradle class filter only executed TrendsRepositoryTest; other classes are not claimed as executed by that invocation.
- Dedicated existing API37 AVD identified and booted without wipe/clear/uninstall; no physical commands. A pre-existing Launcher input-dispatch ANR was observed in local system diagnostics, not attributed to Mirra. Retain it as environment evidence.

## Task 2 — bounded data sources

- Room targeted GREEN 4/4, 0failure/error/skipped. Actual SELECT budgets at0/1/800/801/1601 facts match2/5/5/10/15. One transaction per trends load; batches <=800,801lookahead, cursor at the last consumed fact. Shared history JOIN/projection included in this DAO-foundation commit so each commit compiles independently.
- Benchmark at10,001 Intents +10,001 ended Sessions: controlled fixture creation25,964ms; reads1,582/1,217/1,162ms,65 SELECTs each. Java heap deltas+3,047,424/+1,183,744/-3,776,512 bytes are GC-sensitive sampled deltas, not peak memory or a constant-space claim. Exact medians retain scalar samples only; raw facts are batch-bounded.
- First benchmark hit runTest's default one-minute total timeout while seeding and reading. Preserve this failure; benchmark-only timeout explicitly3minutes and seed/read measurements separated. No business assertion, production timeout or schema was changed.
- EXPLAIN: Intent/Session cohort pages scan and temporary sort; Intent→Session uses the existing intentId index; Context uses its PK; Segment/Event batches use existing(sessionId,time)indexes plus partial temporary ordering. Measured10k reads are bounded on IO; no demonstrated need for a new index at this scope. Larger histories remain a performance risk requiring measurement/review, not an unapproved index.
- UI integration initial run33cases/3failures: test incorrectly expected start-date time instead of endedAt, and offscreen LazyColumn actions used non-keyset-aware scroll selection. Test corrections will be verified, not counted as PASS yet.

## Task 3 — global reading history

- History VM6/6 GREEN. Corrected installed runner History Room4 + Compose10 + History→existing ReadingRecord navigation1 =15/15 PASS, no failure/skip. No production fix was needed for the prior test date/scroll failures.
- One business SELECT per page,51lookahead/50returned, cursor at50;121identical-endedAt rows traverse3pages without omissions/duplicates. Legacy/NONE/PARTIAL/ABNORMAL retained; Active/future/not-ended excluded.
- Pagination uses a fixed time cutoff, not a database snapshot lasting across user navigation. A later StageB commit with older endedAt may appear below an existing cursor; the ViewModel deduplicates displayed IDs. No claim of frozen multi-page database contents.
- 320/360/411dp ×font1/2: row/load-more/return reachable with>=48dp actions. Existing detail is reused, returns to global history and then Mine; before/after learning-fact tables unchanged.

## Known limitations inherited

## Task 4 — flat trends experience

- Trends VM6/6 GREEN. Installed runner Trends Compose19 + navigation2 =21/21 PASS,0failure/skip. Includes explicit cohort labels, 7/30/90/ALL, true0/unavailable/accumulating, open Intent, independent Stable, known local Recovery, load/retry/refresh and ALL without comparison.
- Six320/360/411dp ×font1/2 viewports verify48dp range/refresh/return actions and long values. Mine retains its frozen recent7summary, then two light entries. No chart/card wall/new top tab or Start change.
- Internal read-only final review: Critical0/Important0/Minor0 after two conservative domain fixes and cohort-copy fix. This is not user/ChatGPT independent acceptance.
- Task5 remains pending: full unfiltered regression, current-file cover preservation, actual offline/cold-start paths, schema/frozen scope, screenshots and final Push.

Historical RED/ANR/recovery/data-preservation limitations remain in their original records. API23–36/OEM/full physical matrix/TalkBack/release-Play/real power loss/manual system-clock modification: NOT RUN. OnePlus13T personal feedback is not compatibility acceptance.

## Stop point

After all4A gates, report complete / awaiting independent review and stop; no4B.
