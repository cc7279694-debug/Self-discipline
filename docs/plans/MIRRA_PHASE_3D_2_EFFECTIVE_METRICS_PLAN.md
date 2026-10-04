# Mirra Phase 3D-2｜可信时间线与有效指标 Implementation Plan

> 按 Task 执行 Red → 最小实现 → Green → reviewable commit；此文件不授权执行。本回合不运行下述测试、不修改任何 app/ 文件。

**Goal:** 从完整可信的已结束时间线派生有效专注、有效速度和剩余有效阅读时间，不改变 Phase 2 的任何历史口径。

**Architecture:** 纯 Kotlin validator 组合既有 SegmentTimelinePolicy；Room 只读批量源提供 Session/context/segments；独立 EffectiveReadingService 做本地自然日窗口与加权聚合。派生结果不写数据库、不加入 UI redesign。

**Tech Stack:** Kotlin、java.time（现有 desugaring）、Coroutines/Flow、Room 2.8.x / SQLite v4、既有 JVM/Android test infrastructure。

**Spec:** [MIRRA_PHASE_3D_DESIGN.md](MIRRA_PHASE_3D_DESIGN.md) §10–16、28–33；唯一设计基线 `874d80318c56661218fd03579ba2f1253cc35440`。

## Global Constraints

- 前置：3D-1 已经用户独立验收并冻结。取得该真实 freeze SHA后创建 `codex/phase-3d-effective-metrics`；不提前并行实施。
- Room4、schemas1–4、Migration、Entity/Column/Index不变；不新增依赖、持久化 analytics 表、缓存版本或新的 DI framework。
- 仅 NORMAL、完整可信时间线产生有效指标；UNMONITORED 永远不是 Focus；FULL 单独不能证明可信。
- 仅 FOCUS+DEEP_FOCUS 求时长，Deep不加权；可信0与不可用null分开；pagesRead=max(0,end-start)，不+1。
- 速度为总页数/总有效时长，不平均每场速度；零页正focus样本保留分母。
- 7→14→30，第一个≥3合格Session且≥30分钟有效专注窗口成立；不降低门槛。
- Phase 2 ReadingAnalyticsService、CompletionPredictionService 原公式/样本保留；Mine/Start/UI不在本包迁移。
- 所有路径相对 `C:/Users/CDD/Documents/ChatGPT/Mirra`；新增的 DTO/服务不是 Entity。

## Review Focus

| 危险输入 | 具体测试 |
| --- | --- |
| FULL但缺段、open、gap、overlap或lostAt非空仍算有效 | Task1 `fullFlagCannotOverrideBrokenTimelineOrLostCoverage` |
| 可信0被当Unavailable，或Unknown被显示0 | Task1 `trustedZeroIsAValueButUnavailableIsNull` |
| 0页样本被丢弃而夸大速度 | Task3 `zeroPagePositiveFocusRetainsDenominator` |
| 全0页、极值溢出产生Infinity/NaN或预测 | Task3 `allZeroPagesKeepZeroSpeedWithoutRemainingTime`、`outOfRangeArithmeticNeverProducesNonFiniteValues` |
| 单场逐条查段、超过SQLite绑定预算截断样本 | Task2 `oneAndFortySessionsUseSameBatchQueryBudget`、`largeSourceUsesBoundedBatchesWithoutDroppingSessions` |
| 时区/DST或未来数据改变窗口资格 | Task3 `effectiveWindowsRespectLocalDaysDstAndFutureCutoff` |
| 有效预测不可用使旧Phase2 formula或速度口径改变 | Task4 `phaseTwoFixturesKeepAllOriginalOutputs` |

## Task 1：SessionTimelineValidator

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/domain/SessionTimelineValidator.kt`
- Test/Create: `app/src/test/java/com/guanyi/mirra/domain/SessionTimelineValidatorTest.kt`
- Reuse unchanged: `app/src/main/java/com/guanyi/mirra/domain/SegmentTimelinePolicy.kt`、`app/src/main/java/com/guanyi/mirra/data/local/entity/FocusEntities.kt`

**Interfaces**

```kotlin
enum class TimelineTrust { COMPLETE_TRUSTED, MONITORING_INCOMPLETE, STRUCTURE_INVALID, SESSION_INELIGIBLE }
data class SessionTimelineAnalysis(
    val trust: TimelineTrust,
    val effectiveFocusMillis: Long?,
    val breakCount: Int,
    val allowanceCount: Int,
    val distractionCount: Int,
)
class SessionTimelineValidator(private val policy: SegmentTimelinePolicy = SegmentTimelinePolicy()) {
    fun analyze(session: StudySessionEntity, context: SessionFocusContextEntity?, segments: List<SessionSegmentEntity>): SessionTimelineAnalysis
}
```

这里只列契约，不提前实现函数体。counts 是真实 Segment 个数；无完整资格时只代表可读事实，不授权 UI 冒充整场总次数。

- [ ] **Red:** 下列参数化用例及独立断言失败后再实现。

| 测试 | 断言 |
| --- | --- |
| `onlyFocusAndDeepFocusContributeWithoutWeights` | 完整七类构造中有效=sum FOCUS/DEEP，其他五类不加；有UNMONITORED则整体有效null |
| `exactClosedContinuousTimelineIsTrusted` | NORMAL、FULL、lostAt=null、精确首尾、正段长 → COMPLETE_TRUSTED |
| `fullFlagCannotOverrideBrokenTimelineOrLostCoverage` | gap、overlap、late first、early last、open、zero、倒序、越界分别不可用；lostAt非空也不可用 |
| `unmonitoredPartialNoneAndMissingContextAreIncomplete` | 无论Focus段多少，均null，不补造coverage |
| `abnormalActiveAndNonPositiveSessionAreIneligible` | ABNORMAL/active/ended<=started均SESSION_INELIGIBLE |
| `oldSessionWithoutSegmentsDoesNotInventEffectiveTime` | 旧正常Session无context→MONITORING_INCOMPLETE；FULL且空段→STRUCTURE_INVALID；不改旧Session |
| `trustedZeroIsAValueButUnavailableIsNull` | 完整只有Break/Allowance/Recovery → 有效0；PARTIAL同段→null |

- [ ] **最小实现:** 判定次序固定：Session不合格→SESSION_INELIGIBLE；缺context、非FULL、lostAt非空或UNMONITORED→MONITORING_INCOMPLETE；其余open/缺段/结构错误→STRUCTURE_INVALID；全部通过→COMPLETE_TRUSTED。便于3D-3稳定中文说明。
- [ ] **最小实现:** segments按 startedAt、id 的副本排序；不能移动边界。先确认每段closed，再转SegmentInterval并调用现有policy.requireValid；不复制gap/overlap算法。聚合使用checked减法/加法，非法/溢出归结构错误，无NaN/Infinity。
- [ ] **Green:** 新Validator与原SegmentTimelinePolicyTest全部通过，Entity与policy不改。
- [ ] **Commit:** `feat(analytics): validate complete trusted session timelines`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SessionTimelineValidatorTest' --tests '*SegmentTimelinePolicyTest' --no-daemon
```

## Task 2：批量 EffectiveReadingSource

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/data/local/model/EffectiveReadingSource.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/local/dao/SessionDao.kt`、`app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/repository/ReadingAnalyticsRepository.kt`
- Test/Create: `app/src/androidTest/java/com/guanyi/mirra/data/EffectiveReadingRepositoryTest.kt`
- Test/Modify: `app/src/androidTest/java/com/guanyi/mirra/data/ReadingAnalyticsRepositoryTest.kt`

**Interfaces**

```kotlin
data class EffectiveReadingSource(
    val sessions: List<StudySessionEntity>,
    val contexts: Map<String, SessionFocusContextEntity>,
    val segments: Map<String, List<SessionSegmentEntity>>,
)
// ReadingAnalyticsRepository: 原API保持不变
fun observeEffectiveRecentForItem(learningItemId: String, fromInclusive: Long, toInclusive: Long): Flow<EffectiveReadingSource>
// SessionDao: SELECT s.*，沿用现有inclusive结束时间谓词/排序
fun observeEndedEntitiesForItemBetween(learningItemId: String, fromInclusive: Long, toInclusive: Long): Flow<List<StudySessionEntity>>
// FocusDao: 批量 IN，segments按sessionId,startedAt,id排序
fun observeContextsForSessions(sessionIds: List<String>): Flow<List<SessionFocusContextEntity>>
fun observeSegmentsForSessions(sessionIds: List<String>): Flow<List<SessionSegmentEntity>>
```

- [ ] **Red:** `oneAndFortySessionsUseSameBatchQueryBudget`：Room QueryCallback只计目标Session/context/segment业务SELECT，首次稳定source在1/40场均1+2条，不把bootstrap、Room invalidation辅助SQL混进结果。
- [ ] **Red:** `largeSourceUsesBoundedBatchesWithoutDroppingSessions`：800/801/1601场分别1+2/1+4/1+6初始查询预算；每场与每段均保留，不靠limit丢样本；`emptySessionSourceDoesNotQueryFocusTables` →空source且无IN空查询。
- [ ] **Red:** `effectiveSourceKeepsInclusiveBoundsAndItemIsolation`、`sourceRefreshesWhenContextOrSegmentsChange`、`incompleteFlowSourceCannotBecomeTrusted`：旧/异常Session仍在source由Validator决定，字段缺失/未闭合只可不可用；反应式变化必须重新派生。
- [ ] **最小实现:** parents Flow flatMapLatest，捕获本次父集合，以最多800 IDs分片（兼容最低SQLite参数预算），combine各批context/segment Flow，再按sessionId建Map。不逐Session query，不读取Note或risk label。父集合为空直接flowOf空源。
- [ ] **最小实现:** ended事实来自同一父集合，不混用缓存Session；3D-1已禁止结束后学习事实写。Flow缺项/新源未收齐时保守不可用，不能因context FULL就先显示有效值。DND metadata变化不改变统计真相。
- [ ] **Green:** 检查实际QueryCallback与批次预算，不将现有10k历史测试的名字当作查询计数证据；完整保留原Phase2查询测试。
- [ ] **Commit:** `feat(analytics): load effective reading facts in bounded batches`。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.data.EffectiveReadingRepositoryTest,com.guanyi.mirra.data.ReadingAnalyticsRepositoryTest" --no-daemon
```

这是分片批量预算，不宣称任意N都绝对三条SQL；没有每场一个SELECT的N+1。测试计数在隔离DB、写fixture后清计数、无并发写的首次稳定读取上测。

## Task 3：EffectiveReadingService 与数值边界

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/domain/EffectiveReadingModels.kt`、`app/src/main/java/com/guanyi/mirra/domain/EffectiveReadingService.kt`
- Test/Create: `app/src/test/java/com/guanyi/mirra/domain/EffectiveReadingServiceTest.kt`
- Reuse unchanged: `app/src/main/java/com/guanyi/mirra/domain/AnalyticsTimeProvider.kt`

**Interfaces**

```kotlin
data class QualifiedEffectiveSession(val sessionId: String, val learningItemId: String, val endedDate: LocalDate, val pagesRead: Long, val effectiveFocusMillis: Long)
data class SelectedEffectiveAnalyticsWindow(
    val days: Int, val startDate: LocalDate, val endDate: LocalDate,
    val sessions: List<QualifiedEffectiveSession>, val totalPagesRead: Long,
    val totalEffectiveFocusMillis: Long, val effectivePagesPerHour: Double,
)
enum class EffectiveEstimateUnavailableReason { INSUFFICIENT_WINDOW, ARITHMETIC_OUT_OF_RANGE }
data class EffectiveReadingEstimate(
    val window: SelectedEffectiveAnalyticsWindow?, val remainingPages: Long,
    val remainingEffectiveReadingTime: Duration?, val unavailableReason: EffectiveEstimateUnavailableReason?,
)
class EffectiveReadingService(private val validator: SessionTimelineValidator = SessionTimelineValidator()) {
    fun qualify(source: EffectiveReadingSource, time: AnalyticsTimeContext): List<QualifiedEffectiveSession>
    fun selectWindow(sessions: List<QualifiedEffectiveSession>, time: AnalyticsTimeContext): SelectedEffectiveAnalyticsWindow?
    fun estimate(item: LearningItemEntity, source: EffectiveReadingSource, time: AnalyticsTimeContext): EffectiveReadingEstimate
}
```

- [ ] **Red:** `weightedSpeedUsesTotalPagesOverTotalFocus`：三场10页/10min、5页/25min、5页/25min →20页/小时，不是每场均值28，且满足≥3场；`zeroPagePositiveFocusRetainsDenominator`：30页/30min与两场0页/15min →30页/小时，3场/60min均保留。
- [ ] **Red:** `trustedZeroFocusIsExcludedOnlyFromVelocitySamples`：单场有效0仍可记录，但不计速度窗口；`allZeroPagesKeepZeroSpeedWithoutRemainingTime`：3场/30min→window成立、speed0、remaining null且无非有限值。
- [ ] **Red:** `selectsFirstSevenFourteenOrThirtyDayEligibleWindow`：3场边界与1,800,000ms边界（少1ms不成立），按7→14→30取首个；`effectiveWindowsRespectLocalDaysDstAndFutureCutoff`：使用Asia/Shanghai和有DST时区、当地午夜、跨年、未来endedAt排除，按结束自然日归属。
- [ ] **Red:** `pausedCompletedAndLastPageKeepSpeedButSuppressFutureTime`：状态只影响未来时间，不抹可信速度；`remainingEffectiveTimeRoundsUpToWholeMinutes`：remaining75页、有效速度30页/h→150min；正不足1min→1min。
- [ ] **Red:** `outOfRangeArithmeticNeverProducesNonFiniteValues`：Long累加/时间差极值、总页0、剩余0、安全分钟换算；range error返回 typed unavailable，不展示NaN/Infinity。
- [ ] **最小实现:** qualify只接收NORMAL、ended/start正时长、不来自未来、结束页存在、Validator可信且effective>0；Long页差max0不+1。保留所有0页正时长样本，既有3C符合条件即用，旧无段不补造。
- [ ] **最小实现:** 自然日 start=today-(days-1)，end=today，沿用AnalyticsTimeContext.zoneId、now；总effective是checked SUM，速度单位统一pages*3,600,000/totalMillis。首个≥3且≥1,800,000ms窗口可成立，即使速度0。
- [ ] **最小实现:** `selectWindow` 的null只表示门槛不足；checked arithmetic越界抛`ArithmeticException`，由公开展示入口`estimate`捕获并返回`ARITHMETIC_OUT_OF_RANGE`，不吞其他异常。`estimate`是UI唯一调用入口；计算失败不能伪装成样本不足或有效0。
- [ ] **最小实现:** 只在IN_PROGRESS、totalPages>0、remainingPages>0、speed>0且安全时生成ceil整分钟Duration；不要调用effective速度计算日历日期。本Service不带中文UI文案。
- [ ] **Green / Commit:** 新pure JVM tests通过，`feat(analytics): derive weighted effective reading estimates`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*EffectiveReadingServiceTest' --tests '*SessionTimelineValidatorTest' --no-daemon
```

## Task 4：Phase 2 不变与3D-2 Gate

**Files:** Test/Modify `app/src/test/java/com/guanyi/mirra/domain/ReadingAnalyticsServiceTest.kt`、`app/src/test/java/com/guanyi/mirra/domain/CompletionPredictionServiceTest.kt`；Test/Create `app/src/test/java/com/guanyi/mirra/domain/EffectiveAnalyticsCompatibilityTest.kt`；Update `docs/checkpoints/2026-10-04-module-3d-2.md`、`docs/CURRENT_STATE.md`；不修改Phase2 service实现。

**Interfaces:** 使用既有 ReadingAnalyticsService.selectAnalyticsWindow、CompletionPredictionService.predict 与本包 estimate并行计算，测试明确输入相同 fixture，不让两者共享可变结果。

- [ ] **Red:** `phaseTwoFixturesKeepAllOriginalOutputs` 使用旧总时长/速度、ordinary remaining、calendar pace、自然日期range/confidence全字段固定expected；`oldSessionsParticipateOnlyInOverallAnalytics` 保留旧无段参与旧口径；`existingThreeCTrustedSessionsQualifyWithoutVersionExclusion`；`effectiveUnavailableDoesNotChangeNaturalPrediction`。
- [ ] **最小实现:** 只补上述测试/本包必要修正，不更改 ReadingAnalyticsService、CompletionPredictionService 的公式、leave-one-out robustCV/0.75 或eligibility。窗口不足是fallback事实；未来时间不可用不等于窗口不足。
- [ ] **Green:** 全量单次JVM、connected、lint、assemble；不拼局部结果。Room4/Schema hash/Phase2 production formula diff保持不变；全部计数来自本包实际执行。
- [ ] **Commit / Push:** `test(analytics): preserve phase two reading semantics`，Push `codex/phase-3d-effective-metrics`，核local==remote与clean；输出 `[3D_2_COMPLETE]`，等待独立review/freeze，不进入3D-3。

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
git diff --check
Get-FileHash -Algorithm SHA256 app/schemas/com.guanyi.mirra.data.local.MirraDatabase/4.json
```

Expected: 全量无失败，跳过/平台前提诚实记录；v4 hash `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`；UI仍旧外观，本包没有UI验收PASS声明。未执行API/OEM/实体机/TalkBack/release继续NOT RUN。
