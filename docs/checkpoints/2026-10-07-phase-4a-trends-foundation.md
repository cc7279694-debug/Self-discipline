# Phase 4A — Trends Foundation

Status: accepted / frozen — PASS WITH NOTES. User-confirmed independent review conclusion: `REVIEW_COMPLETE — PASS WITH NOTES`. Parent Planning Freeze: `cc5180762d9f735754ce3a5b0759089aefe6dcc9`.

## Goal and scope

Truthful Start/Maintain/Recover trends and flat Mine entries plus paged global reading history. Inherit frozen Phase2/3, ReadingRecord, EffectiveReading, Validator, Mirra Blue and brand. Room v4 remains unchanged.

## Execution evidence

The authorized five Tasks completed execution before independent acceptance. The entries below preserve that implementation/validation history; user-confirmed ChatGPT independent acceptance is appended under Independent Acceptance and Phase 4A Freeze. See [execution contract](../plans/MIRRA_PHASE_4A_TRENDS_FOUNDATION_PLAN.md) and [minimal evidence index](../evidence/phase4a/README.md). The evidence index remains the Task5 execution snapshot; its original awaiting-review status does not override the later formal acceptance.

- Domain TDD: empty aggregation stub produced27/27 expected failures, followed by2/2 added cases. Targeted GREEN29/29, 0failure/error/skipped. Initial compile interference from an incompletely saved parallel ViewModel signature was not counted as assertion RED.
- Trends/History ViewModel stubs:6/6 expected failures each; implementation follows these actual failures, not a claimed green run.
- Domain internal review raised missing/NONE context and conflicting local recovery coverage.6 extra tests produced5 expected failures and1 protection control, then35/35 GREEN. Earlier confirmed recovery remains valid before later monitoring loss; incomplete/conflicting local proof is UNKNOWN. Frozen Validator/monitoring were not changed. This is internal engineering review, not ChatGPT independent acceptance.
- ViewModel targeted GREEN12/12. Empty repository produced Room RED4/4. Separate installed test runner produced history Room4 + Compose10 + navigation2 expected failures16/16. The initial combined Gradle class filter only executed TrendsRepositoryTest; other classes are not claimed as executed by that invocation.
- Dedicated existing API37 AVD identified and booted without wipe/clear/uninstall; no physical commands. A pre-existing Launcher input-dispatch ANR was observed in local system diagnostics, not attributed to Mirra. Retain it as environment evidence.

## Task 2 — bounded data sources

- Room targeted GREEN 4/4, 0failure/error/skipped. Actual SELECT budgets at0/1/800/801/1601 facts match2/5/5/10/15. One transaction per trends load; batches <=800 Session IDs (not <=800 Segment/Event rows),801lookahead, cursor at the last consumed fact. Shared history JOIN/projection included in this DAO-foundation commit so each commit compiles independently.
- Benchmark at10,001 Intents +10,001 ended Sessions: controlled fixture creation25,964ms; reads1,582/1,217/1,162ms,65 SELECTs each. Java heap deltas+3,047,424/+1,183,744/-3,776,512 bytes are GC-sensitive sampled deltas, not peak memory or a constant-space claim. Exact medians retain scalar samples only; raw facts are batch-bounded.
- First benchmark hit runTest's default one-minute total timeout while seeding and reading. Preserve this failure; benchmark-only timeout explicitly3minutes and seed/read measurements separated. No business assertion, production timeout or schema was changed.
- EXPLAIN: Intent/Session cohort pages scan and temporary sort; Intent→Session uses the existing intentId index; Context uses its PK; Segment/Event batches use existing(sessionId,time)indexes plus partial temporary ordering. Measured10k reads are bounded on IO; no demonstrated need for a new index at this scope. Larger histories remain a performance risk requiring measurement/review, not an unapproved index.
- UI integration initial run33cases/3failures: test incorrectly expected start-date time instead of endedAt, and actions did not first locate virtualized LazyColumn items. Subsequent corrected15/15 History and21/21 Trends/navigation runs are listed below; retain original failures.

## Task 3 — global reading history

- History VM6/6 GREEN. Corrected installed runner History Room4 + Compose10 + History→existing ReadingRecord navigation1 =15/15 PASS, no failure/skip. No production fix was needed for the prior test date/scroll failures.
- One business SELECT per page,51lookahead/50returned, cursor at50;121identical-endedAt rows traverse3pages without omissions/duplicates. Legacy/NONE/PARTIAL/ABNORMAL retained; Active/future/not-ended excluded.
- Pagination uses a fixed time cutoff, not a database snapshot lasting across user navigation. A later StageB commit with older endedAt may appear below an existing cursor; the ViewModel deduplicates displayed IDs. No claim of frozen multi-page database contents.
- 320/360/411dp ×font1/2: row/load-more/return reachable with>=48dp actions. Existing detail is reused, returns to global history and then Mine; before/after learning-fact tables unchanged.

## Task 4 — flat trends experience

- Trends VM6/6 GREEN. Installed runner Trends Compose19 + navigation2 =21/21 PASS,0failure/skip. Includes explicit cohort labels, 7/30/90/ALL, true0/unavailable/accumulating, open Intent, independent Stable, known local Recovery, load/retry/refresh and ALL without comparison.
- Six320/360/411dp ×font1/2 viewports verify48dp range/refresh/return actions and long values. Mine retains its frozen recent7summary, then two light entries. No chart/card wall/new top tab or Start change.
- Internal read-only final review: Critical0/Important0/Minor0 after two conservative domain fixes and cohort-copy fix. This is not user/ChatGPT independent acceptance.
- Task5 final execution results follow. Historical targeted green runs above are not stitched together to replace the complete run.

## Task 5 — final validation

- Full unfiltered JVM 374/374 PASS, 0 failure/error/skipped. lintDebug 0 errors / 9 existing warnings / 1 hint; assembleDebug and assembleDebugAndroidTest PASS. No production code changed after these commands.
- One complete, unfiltered connected invocation finished in 23m05s, Gradle exit 0; raw suites summed execution time 1,318.609sec. 333 discovered / 324 actual PASS / 0 actual business assertion failures / 9 unmet assumptions. All 39 new 4A Room/Compose/navigation cases actually PASS. Runner reports 333 tests / 0 failed / 0 ignored; raw XML records 9 failures / 0 errors / 0 skipped, all nine explicit AssumptionViolatedException bodies. This is not 333/333 PASS.
- Four opt-in fixtures (pending prepare/assert, preservation seed/assert) were not opted in: NOT RUN. Five permission-prerequisite platform cases (three DND, overlay attach/remove, notification posting) did not execute assertions: NOT RUN. No permission was granted just to change these counts. Historical actual DND/intervention proofs remain historical, not newly executed here.
- Final measured business SELECT counts at 0/1/800/801/1601 facts: 2/5/5/10/15; read elapsed 5/6/130/93/181ms. 10,001 Intents + 10,001 ended Sessions: seed 25,721ms separately; reads 1,770/1,737/1,746ms, 65 SELECT each; sampled heap deltas +2,772,992/+2,174,976/-2,289,664 bytes. QueryCallback asserts all business queries are off the main Looper. EXPLAIN and memory caveats remain as Task2; no necessary new index demonstrated, no schema expansion.
- Rechecked exact Schema1–4 hashes and Git blobs against parent; MirraDatabase.version4 and migrations unchanged. No Entity/Manifest/permission/dependency/theme/brand/Start/Validator/Effective/Phase2/Closeout/DND/monitoring changes. Internal review is not independent product acceptance.
- Initial controlled screenshots were obscured by a real Pixel Launcher ANR dialog despite Compose semantic tests passing. Those raw screenshots remain locally, not clean visual evidence. Dismissed the observed system error dialog; launcher returned without ANR. First immediate UI dump after dismissal/start returned null; stale dump was not used as proof. Full-run recapture produced some blank/wrong-frame screenshots (empty/font2, Mine displayed Trends, History still loading); these also remain local and are not mislabeled clean evidence. Final inspected seven images distinguish four controlled projection/Room-detail images from three installed empty-state screenshots. Narrow/double-font behavior remains semantic Compose evidence, not a usable narrow-screen pixel screenshot.
- Explicit API37/qemu1/boot1 device guard, dedicated AVD only; no physical commands, wipe, clear or uninstall. Final APK cover install returned Success. Before first launch, 18 current database/files/preferences paths had identical SHA-256 before/after. This proves that specific cover installation's current files, not historically removed old data. Normal COLD/ok launch 5,249ms; later successful offline COLD/ok 6,274ms.
- Actual installed routes: Start EmptyLibrary, Knowledge (search/notes/content), Mine (two new entries), Trends (all four ranges; empty unavailable/accumulating), empty global History. Hardware Back from Trends/History returns Mine. Nonempty History→existing detail→History→Mine is verified with real in-memory Room + Compose and before/after learning fact equality; no private data used.
- First offline attempt read default network too early during its transition and immediate UI bridge returned null; no offline route PASS claimed from that attempt, network restored in finally. Repeated actual check waited for active default network=none, then completed all empty routes/Back, with none still confirmed at end and no system error dialog. Original network restored and read back: airplane0/Wi-Fi1/mobile-data1. No DND/Overlay/Usage/notification permission changes.
- Optional extra manual nonempty fixture attempt: ADB text injection did not create the requested field values. Cancelled the form, verified Start EmptyLibrary again; no book/Intent/Session/history was fabricated. Cause unconfirmed, no production repair. That extra manual nonempty closeout/history scenario is NOT RUN, not substituted by mocked facts.
- Performance/UI boundary: cumulative installed gfxinfo across cold start/Mine/Trends was 18 frames / 17 janky; not an isolated Trends-entry benchmark. API37 AVD rendering/bridge instability persists as an observation. DAO off-main assertions do not prove zero UI jank, and these graphics observations do not demonstrate a required database index. No unsupported root-cause claim or frozen-core modification.
- Final Debug APK: `build/deliverables/Mirra-Phase4A-debug.apk`, 16,488,839 bytes, version0.1.0/code1, applicationId `com.guanyi.mirra`; SHA-256 `CA6D97546D1D5A0012B5DF8C51C820EA5E944EF8CCF47091009B47EA18E21CC5`. Production build HEAD `e799997002d7c8d4dbb833c7452d194e8bad5d9f`; final Task5 adds test-only measurements and documentation/evidence, no production changes. APK/raw logs/device properties remain local, not in Git.

## Git delivery ledger

| Task | Commit | Responsibility |
| --- | --- | --- |
| 1 | `e533c04e3562087b7ab58270d83cf4bddea6a5d8` | Pure truthful aggregation |
| 2 | `76a53540c0365dba4106d81a7c3fd2f41c4a55b8` | Bounded read sources / compile-safe shared history DAO |
| 3 | `8e692354506e0caaa306fed0229f709f775a2eb8` | Global history / existing detail reuse |
| 4 | `e799997002d7c8d4dbb833c7452d194e8bad5d9f` | Flat trends / Mine wiring |
| 5 | `614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693` | Measurement assertions, checkpoint, current state, redacted evidence; not a production-code SHA |

Only `codex/phase-4a-trends-foundation` is authorized for Push; no main merge, force push, release or 4B. Final local/remote SHA and clean-worktree verification are reported after Git execution, not invented here.

## Known limitations inherited

Historical RED/ANR/recovery/data-preservation limitations remain in their original records. API23–36/OEM/full physical matrix/TalkBack/release-Play/real power loss/manual system-clock modification: NOT RUN. OnePlus13T personal feedback is not compatibility acceptance.

## Stop point

Phase 4A accepted / frozen — PASS WITH NOTES. Accepted production HEAD `e799997002d7c8d4dbb833c7452d194e8bad5d9f`; accepted validation HEAD `614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693`. Phase4B has not started; stop and await separate explicit authorization.

## Independent Acceptance and Phase 4A Freeze

2026-10-07 — The user confirmed ChatGPT independent code/evidence review completed with `REVIEW_COMPLETE — PASS WITH NOTES`. Phase 4A is formally accepted / frozen. This document-only synchronization does not perform another independent product review or rerun Gradle / JVM / connected / AVD / lint / build / physical devices.

- Accepted production HEAD: `e799997002d7c8d4dbb833c7452d194e8bad5d9f` (Task4, last production-code commit).
- Accepted validation HEAD: `614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693` (Task5, tests/measurement/documents/evidence only; not the last production-code SHA).
- Branch: `codex/phase-4a-trends-foundation`. The separate Acceptance Freeze document commit is reported from Git after commit/Push; it replaces neither accepted HEAD.

### Accepted capabilities

- Start: `CONVERTED / (CONVERTED + ABANDONED + TIMEOUT)`; open Intent excluded. Later NORMAL/ABNORMAL/PENDING or other Session outcomes do not rewrite Start success. Intent→Session latency is separate.
- Stable: independent of Start Conversion; `stableStartedAt == null` means unconfirmed, not failure. No Stable success score/rate.
- Maintain: reuse unchanged frozen `SessionTimelineValidator`; only `COMPLETE_TRUSTED` aggregates effective focus, actual persisted Deep Focus, distraction count and distraction duration. Trusted0 and unavailable remain distinct.
- Recover: Primary attempts only originate from DISTRACTION. Outcomes are SUCCESS / INTERRUPTED / UNKNOWN; UNKNOWN is excluded from the failure denominator. Break/Allowance recoveries do not enter Primary Recover. Complete local evidence already proving an outcome survives subsequent Session monitoring loss.
- Time: 7/30/90 local natural days and ALL; Start cohort uses Intent.createdAt, Maintain/Recover use Session.endedAt. ALL has no previous comparison.
- Global History: pageSize50, `(endedAt DESC, id DESC)` keyset; retain NORMAL/ABNORMAL/PARTIAL/NONE/legacy ended Session; reuse existing ReadingRecord Detail, not a second Session detail.
- Mine: flat '查看趋势'/'阅读记录' entries. No Dashboard/chart/Score/ranking/streak/AI judgement.

### PASS WITH NOTES — retained, not resolved

1. ALL exact median retains O(N) scalar samples. Raw Session/Context/Segment/Event facts are processed and released in batches of <=800 Session IDs, not a guarantee of <=800 Segment/Event rows. Current10k evidence is acceptable; no constant-space claim.
2. Global History's fixed `snapshotNow` is not a long-lived SQLite snapshot across pages. Late durable facts with old endedAt may require re-entering the page for full visibility; no immutable multi-page snapshot claim.
3. EXPLAIN retains cohort scans/temporary sorts. Measurements of10,001 Intents+10,001 Sessions do not demonstrate a necessary new index. Room staysv4; larger data sets require fresh measurement and, if needed, separate Schema/index review.
4. Cumulative AVD gfxinfo contains significant jank observation, but is not an isolated Trends benchmark. Do not attribute it to the database or4A, or claim zero-jank.
5. Extra manual nonempty installed journey remains NOT RUN. Existing real in-memory Room+Compose nonempty History→existing ReadingRecord detail evidence remains automation evidence, not a substitute manual PASS.
6. 320/360/411dp+fontScale1/2 has semantic Compose verification. There is no clean320/font2 pixel screenshot; do not claim one.
7. API23–36/full OEM/physical matrix/TalkBack/release-Play/real power loss/manual system-clock change remain NOT RUN. API37 AVD results do not extrapolate to these environments; OnePlus13T daily feedback is not compatibility acceptance.
8. Historical Launcher/SystemUI ANR, Recovery>120s observation and data-preservation limitations remain unchanged. In particular, the Recovery observation is not FIXED or ROOT CAUSE RESOLVED; the18-file hash equality only proves the specific cover installation's current files, not historical old-installation preservation.

### Accepted execution evidence — not rerun for this Freeze

- JVM:374/374 PASS.
- Complete connected:333 discovered /324 actual PASS /9 unmet assumptions /0 actual business assertion failures. Raw XML's9 assumption failures and runner's0 failed/0 ignored remain separately described above; not333/333 PASS.
- Four opt-in fixtures:NOT RUN. Five permission-prerequisite platform cases:NOT RUN. None of these nine is counted as PASS.
- New4A Room/Compose/navigation:39 actual PASS.
- lintDebug:0 errors /9 existing warnings /1 hint.
- assembleDebug /assembleDebugAndroidTest:PASS.
- These are accepted implementation/validation execution records, not new results from the independent review or the document-freeze turn. Original RED/failures/environment observations remain preserved.

### Frozen data and scope boundary

Room version4; Schema1–4 unchanged; Migration1→2→3→4 unchanged. No Entity/Column/Index migration and no Schema v5. v4 Schema file SHA-256: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`; this is a file hash, not Room identityHash. Phase2/3/Closeout/Validator/Effective/Monitoring/DND/theme/brand semantics remain frozen.

Only CURRENT_STATE, this checkpoint and the4A execution contract are synchronized. No new long-term decision, no DECISIONS or Phase4 Master Plan rewrite, no implementation/test/schema/configuration/resource changes. Phase4B has not started and still requires separate explicit authorization. Stop after document commit/Push and clean-tree/SHA verification.
