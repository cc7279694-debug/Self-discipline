# Phase 4B — Descriptive Insights implementation contract

Status: Phase4B complete / awaiting independent review; Task4 Fresh AVD final classification **VALIDATION_RECOVERED**. Not accepted or frozen. No Phase4C authorization or implementation.
Parent freeze: `a57a7807b7acd0e1d7b8e49f29ef84d639a471b1`.
Branch: `codex/phase-4b-descriptive-insights`.

This records the approved execution contract, not a new Phase 4 plan.

## Global constraints

- Read-only, same-book effective reading pace comparison. No causes, subjective state, scores, ranking, notifications, persistence, dismissal, backup/export or Phase 4C.
- Reuse frozen `EffectiveReadingService.qualify()` and `SessionTimelineValidator`; neither algorithm may change.
- Qualified samples: current book, NORMAL, non-future ended Session, COMPLETE_TRUSTED, positive pages and effective focus.
- One immutable `AnalyticsTimeContext`: latest 90 **local natural days**, inclusive today; never 90×24 hours.
- Stable `(endedAt DESC, sessionId DESC)` order. Recent = newest **3 qualified samples**, all within latest 30 natural days, total focus ≥30min.
- Baseline = next **at most 10** qualified samples, never overlapping recent, ≥5 samples and ≥60min, still within 90 days.
- Both speeds = sum pages / sum effective focus. Ratio ≤0.70 plus all three individual speeds strictly below baseline → SLOWER; ratio ≥1.30 plus all three strictly above baseline → FASTER. Otherwise hidden.
- Guard overflow, invalid timestamps, non-finite speed and duplicate/corrupt associations without modifying facts. Zero-page samples excluded only from insights, not frozen effective analytics/history.
- PAUSED/COMPLETED may display this historical description if freshness holds.
- Relative absolute change rounded to nearest 5%; raw change >100% displays “超过 100%”.
- Only SLOWER/FASTER appear in existing Learning Item Detail, using neutral Mirra Blue typography, no warning/reward decoration. Copy says “最近 3 次可比较阅读”. Loading/unavailable/failure hidden; insight failure cannot fail the rest of detail.
- Room v4; Entities, columns, indexes, Schema 1–4 and Migration chain unchanged. v4 file SHA-256: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` (not Room identityHash).
- Phase 2/3D/4A, Closeout, ReadingRecord, monitoring/DND and navigation remain frozen. Need for schema/index or frozen semantic change requires stop and review.

## Task 1 — Pure domain

Implement `domain/insights/ReadingPaceInsightService.kt` and focused models, with no Chinese strings. Use frozen effective qualification rather than duplicating trust rules. Preserve exact end timestamps for tie ordering without changing frozen QualifiedEffectiveSession.

TDD: exactly 3/only 2 recent; recent 29:59/30:00; baseline 4/5,59:59/60:00,max10; 90/30-day boundaries and timezone; same-book isolation; candidate excluded from baseline; weighted not per-session average; ratio exactly0.70/1.30; slower/faster/band/mixed direction; zero pages/focus; overflow/nonfinite guards; invalid/duplicate associations; paused/completed.

Commit: `feat(insights): add reading pace change rules`.

## Task 2 — Bounded data source

Thin independent `ReadingInsightRepository` delegates current-book 90-day facts to existing `ReadingAnalyticsRepository.observeEffectiveRecentForItem()`. No second Context/Segment loader. Existing source batches IDs at ≤800; no per-session N+1.

TDD Room: normal/full/trusted; PARTIAL/NONE/ABNORMAL/gap/overlap/UNMONITORED/future/zero-page/other-book exclusion; stable ordering; >800 IDs, query budget and measured bounded data performance. No new index.

Commit: `feat(insights): add bounded pace insight source`.

## Task 3 — Existing book detail

Wire optional insight in `LearningItemDetailViewModel`, `LearningItemDetailUiState`, existing detail screen and existing containers. No alternate detail or route.

Neutral section near reading pace/history; titles “近期阅读节奏变慢” / “近期阅读节奏变快”; body “最近 3 次可比较阅读，比此前这本书的有效阅读速度低约 30%。” / “高约 35%。”; scope note “仅比较完整监测且可计算有效阅读速度的记录。”

ViewModel/Compose TDD: slower/faster/hidden/loading; independent failure isolation; long book names; 320/360/411dp × fontScale1/2; scroll/Back/≥48dp existing actions. Do not redesign Start/Mine/Knowledge.

Commit: `feat(knowledge): show descriptive pace insight`.

## Task 4 — Final validation and evidence

No new feature. Full unfiltered JVM and connected, lintDebug, assembleDebug and test APK as necessary. Use dedicated API37 AVD only; preserve installed data (`leaveApksInstalledAfterRun=true`), no wipe-data/pm clear/uninstall or physical-device operations. No new permission grant merely to make a suite green.

Report actual discovered/pass/failure/error/assumption counts; default opt-in and permission-prerequisite skipped cases remain NOT RUN unless actually executed. Check schema hashes and frozen-file diff; update checkpoint and CURRENT_STATE to complete / awaiting independent review, never self-freeze.

Commit: `test(insights): validate phase 4b`; push current branch only. Keep production and validation HEAD distinct. Retain historical NOT RUN, ANR/jank and preservation evidence limits.

Recovered Gate (2026-10-07): fresh `Mirra_API_37_Phase4B_Final`, Android17/API37 Google APIs x86_64, build `CE2A.260420.019`; four initial health rounds span72.102s. Three specified methods each3/3 independent actualPASS; focused4B JVM57/57, Room8/8, Compose10/10. Full JVM431/431,0failure/error/skip; lint0errors/9existingwarnings/1hint; both APK builds PASS. Unique actual unfiltered connected:351discovered/342actualPASS/9unmet assumptions/0businessfailure/0error/0unfinished, Gradleexit0,1046.447hostseconds. 351unique runner start/finish records plus final run-finished match the fresh XML. Four opt-in and five permission-prerequisite cases remain NOT RUN. Raw XML stores9assumptions as failure nodes,0errors/0skipped; not351/351PASS. Full pre/post health is normal, no system_server restart, ANR, DeadSystem or transport abort observed.

Historical Gate failures remain separate: first complete351-entry FAIL,72-entry incomplete retry/DeadSystem, later27-start Forensics Fresh Full stale-title failure, old AVD/QEMU disappearance and collector misclassification. Approved test-only stabilization `f000ca03d5eb03646d76f199fe2cb899979dfe68` changes only async test readiness; production stays `5cbff38853c069b02772b6062d5a7e96a1f9fb13`. This authorization changed neither production nor tests. One preceding host Gradle invocation failed at task selection due an unquoted dotted property, with0Androidtests started; quoting the property launched the sole actual full execution, not rerun-until-green. Stale copied outputs from the host failure are excluded. No old AVD repair/data import, permission grant, wipe/clear/uninstall or physical-device operation. Validation publication contains documents and sanitized evidence only; independent acceptance remains pending. See checkpoint for complete history and limitations.

## Code mapping and evidence

Existing source: `data/repository/ReadingAnalyticsRepository.kt` (Session query plus two ≤800-ID batched child flows). Domain qualification: `domain/EffectiveReadingService.kt`; trust: `domain/SessionTimelineValidator.kt` (unchanged).

| Responsibility | Actual implementation / verification |
| --- | --- |
| Pure rules and readonly evidence | `domain/insights/ReadingPaceInsightService.kt`, `ReadingPaceInsightModels.kt`; JVM `ReadingPaceInsightServiceTest.kt` (47 cases) |
| Existing batched facts under the 90-day snapshot | `data/repository/ReadingInsightRepository.kt`; Room `ReadingInsightRepositoryTest.kt` (8 cases) |
| Optional detail projection, immutable time generation, failure/cancellation isolation | Existing `feature/knowledge/LearningItemScreens.kt`; JVM `LearningItemInsightTest.kt` (10 new cases) |
| Neutral conditional section | `feature/knowledge/LearningItemInsightSection.kt`; Compose `LearningItemInsightUiTest.kt` (10 cases) |
| Existing application and test container wiring | `di/AppContainer.kt`, `MirraApp.kt`, androidTest `TestAppContainer.kt`; existing navigation is reused |

Source/test names above resolve under `app/src/main/java/com/guanyi/mirra`, `app/src/test/java/com/guanyi/mirra`, or `app/src/androidTest/java/com/guanyi/mirra` as appropriate. Production HEAD: `5cbff38853c069b02772b6062d5a7e96a1f9fb13`. Per-task TDD, query/performance measurements, failure history and final Gate are recorded in [checkpoint](../checkpoints/2026-10-07-phase-4b-descriptive-insights.md). Internal reviews do not replace independent acceptance.
