# Mirra Phase 3D-1｜安全结束学习 Implementation Plan

> 执行者按 Task 逐项完成 Red → 最小实现 → Green → reviewable commit；可使用既有执行计划工作流。此文件是计划，不是实施授权。本回合不执行任何下列测试或代码改动。

**Goal:** 最终确认时精确冻结结束事实，完成 ACTIVE → PENDING → COMPLETED，并使失败、取消和冷启动都不能恢复已结束学习。

**Architecture:** 现有 controller mutex 内先 settle，再连续调用两个 Room 事务；退出事实锁后独立清理干预、监测和 Mirra DND。Repository 判断 durable Closeout，ViewModel 只组织保存/确认/重试，不复制行为状态机。

**Tech Stack:** Kotlin、Coroutines/Flow、Room 2.8.x / SQLite v4、Compose、Navigation 3、既有 Mirra Theme / Android capability。

**Spec:** [MIRRA_PHASE_3D_DESIGN.md](MIRRA_PHASE_3D_DESIGN.md) §2–9、25–29、31–33；原始设计 commit `874d80318c56661218fd03579ba2f1253cc35440`；本计划以该规范本次用户批准的 Plans Review 修订为准。

## Global Constraints

- 从用户独立验收并确认的 Phase 3D Plans freeze commit 创建 `codex/phase-3d-closeout`；用实际冻结 SHA，不使用当前计划编写分支的浮动 HEAD 代替。
- Room version = 4；Entity、Table、Column、Index、Migration、schemas 1–4 不变；不新增依赖、Service、权限、DI framework 或 Gradle module。
- 3C 阈值、Coverage 不可逆、READY、Usage/FGS 架构、DND ownership 和渠道语义不变；只增加结束资格和接线。
- Phase 2 analytics 原公式不变；本包不实现有效指标、统一时间线 UI 或后续包。
- ClockSample 来自最终确认，不重采结束 wall time；Android API 不进入 Room transaction / monitoring mutex。
- 正常结束边界精确等于 final wall；只有明确 backward clock 样本证据与已 durable loss 同时成立时，才按 DESIGN §4.1 的 safe boundary 结束。普通过早 boundary 仍拒绝；PARTIAL 永不恢复 FULL。
- Stage A 成功后永久逻辑结束；ABORTED 不使用，禁止 cancel/reopen。PENDING 仍占唯一 Session 槽位，不允许新开一场。
- 附件是本次任务约束；旧 Phase 3 cancelCloseout 方案已由 DESIGN SPEC 替换。所有路径相对仓库根 `C:/Users/CDD/Documents/ChatGPT/Mirra`。

## Review Focus

| 失败场景 | 指向测试 |
| --- | --- |
| A 已提交但调用者收到 cancellation，误按“未结束”继续读 | Task 3 `cancellationAfterUnknownBeginCommitStillCleansPending` |
| PENDING 保留 activeSlot，迟到 heartbeat/risk/页码继续写 | Task 2 `pendingRejectsEveryFocusWriteEntryWithOriginalReturnSemantics` |
| 6 秒失监恰好遇到最终确认，为保 FULL 漏 settlement | Task 3 `realSixSecondGapSettlesBeforeCloseout` |
| 系统时间后退，durable UNMONITORED 起点高于 final wall，用户无法结束 | Task 1/3 `backwardClockJumpStillAllowsCloseoutAtLastDurableBoundary` |
| 以任意 PARTIAL / 过早输入伪装 clock jump，通用 clamp 掩盖错误 | Task 1/3 `ordinaryBoundaryBeforeActiveSegmentStillFailsWithoutClockJumpEvidence` |
| B 保存失败，重试采用新的结束时间或启动变 ABNORMAL | Task 1/4 `completeFailureRetainsPendingAndRetryUsesOriginalBoundary` |
| DND 已 prepare，结束后迟到 activation 写回 ACTIVE | Task 2 `lateApplyAfterActivationBeforeLifecycleWriteIsCompensated` |
| 清理失败/阻塞占事实锁，或导致其他清理不执行 | Task 3 `blockedAndroidCleanupDoesNotHoldFactsMutexOrRoomTransaction` |
| 草稿/页码尚在保存时弹确认、重建或 Back 回到假阅读 | Task 5 `confirmationWaitsForInFlightDraftAndPageWrites`、`recreatedPendingOnlyOffersRetry` |

## 已核对的事实与文件职责

当前 `StudyWorkflowRepository.finishSession()` 单事务内采 clock、夹低结束页；`updateCurrentPage()` 没有 Closeout guard。v4 context 已有全部结束字段。`DefaultSessionManager` 的 finish callback 仍在 controller 锁内执行 `MonitoringPlatformRuntime.releaseSession()`，后者包含系统清理。新实现必须迁移这些路径，不能只加结果页。

当前全部 `observeActiveSession()` / activeSlot 查询表达“槽位占用”；不全局过滤 PENDING。页面/行为是否仍可学习另检查 context ACTIVE。`DndController.kt`、`AndroidDndSystem.kt`、Entity、Migration、`SessionSegmentStateMachine.kt` 和 READY 代码保持冻结。

**Backward clock 前置风险（源码推导，尚未运行新测试）：** 既有 `clock jump clears candidate and never persists a backward boundary` 使用FakeFacts，只证明controller传入loss(2000,2000)，不证明真实Room提交。当前FocusRepository.markMonitoringLost在活动FOCUS起点1000、可信点2000、clock100时，外层允许保留2000，但随后monitoringGap(loadState(...))仍以now100校验，可能拒绝loss。已存在UNMONITORED或trusted恰等于活动起点的分支不同。Task1只验证用户指定的“loss已durable”夹具；Task3必须另验证真实Repository loss前提。若该路径失败且需改冻结核心，保存失败并停止请求授权，不用runtime proof、通用clamp或伪造durable事实掩盖；本轮不修生产代码。

## Task 1：Room Closeout Facts

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/data/local/model/CloseoutSnapshot.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/domain/monitoring/MonitoringModels.kt`（只增加非持久化 Closeout clock evidence DTO，不改监测规则）
- Modify: `app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`
- Test/Create: `app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDCloseoutRepositoryTest.kt`
- Test/Modify: `app/src/androidTest/java/com/guanyi/mirra/data/StudyWorkflowRepositoryTest.kt`

**Interfaces**

```kotlin
data class CloseoutSnapshot(val sessionId: String, val closeoutStartedAt: Long, val requestedEndPage: Int)
// MonitoringModels.kt：只由controller在既有串行边界内产生，不是Entity或UI状态
data class BackwardClockCloseoutEvidence(
    val sessionId: String,
    val lastTrustedSample: ClockSample,
    val regressionSample: ClockSample,
    val durableLossBoundary: Long,
    val unmonitoredSegmentId: String,
)
// StudyWorkflowRepository
suspend fun beginCloseout(
    sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
    backwardClockEvidence: BackwardClockCloseoutEvidence? = null,
): CloseoutSnapshot
suspend fun completeCloseout(sessionId: String): StudySessionEntity
suspend fun getCloseoutState(sessionId: String): FocusCloseoutState?
fun observeCloseoutState(sessionId: String): Flow<FocusCloseoutState?>
suspend fun getCloseoutSnapshot(sessionId: String): CloseoutSnapshot?
// FocusDao
suspend fun markCloseoutPending(sessionId: String, requestedEndPage: Int, at: Long): Int
suspend fun markCloseoutCompleted(sessionId: String, at: Long): Int
```

Task 1 增加接口，现有旧调用暂留以保持每个中间 commit 可编译；Task 3 统一迁移所有正常生产/测试调用并删除公开 `finishSession(sessionId,endPage)`。旧方法不增加用途；未完成 Task 3 不得作为新 Closeout 发布。

`getCloseoutSnapshot`读取既有context：PENDING/COMPLETED且必需字段齐全返回durable snapshot，ACTIVE返回null；PENDING字段损坏明确失败而非返回当前时间。供Task5重建后冻结时长/恢复请求页；不新增数据库字段。

`beginCloseout` 的时间输入始终是原始 final confirmation wall，不由 UI / manager 预先 clamp；返回 snapshot 才是正式冻结边界。可选 evidence 的产生职责属 Task3；默认 null 保持普通入口严格拒绝过早边界。DTO 只补现有串行结束接线，不新增持久化 loss 原因、事件系统或第二套监测算法。

- [ ] **Red:** 编写以下真 Room 测试并运行，确认是缺少 Closeout 行为而失败。

| 测试 | 精确断言 |
| --- | --- |
| `beginCloseoutFreezesExactSampleAndReleasesOnlySegmentSlot` | 遍历全部 7 种 Segment；A 后 PENDING、requested page、time 完全等于输入，末段 endedAt 相等；Segment slot 清空、Session slot 仍 1 |
| `sameBoundaryCloseoutDeletesZeroDurationSegmentWithoutInventedMillis` | active.startedAt==boundary 删除该段，无虚构 1ms；此前闭合段不变 |
| `backwardClockJumpStillAllowsCloseoutAtLastDurableBoundary` | start1000；连续可信事实至2000、active UNMONITORED.start2000；final wall100且elapsed单调；明确 backward evidence、PARTIAL、monitoringLostAt2000 → A/B boundary均2000，NORMAL/COMPLETED；不写100/重试时间，删除零时长未知段、不制造1ms；PARTIAL及失监事实保留，不具备完整有效指标资格 |
| `ordinaryBoundaryBeforeActiveSegmentStillFailsWithoutClockJumpEvidence` | 无明确 clock evidence 时 boundary<active.startedAt 或 Session.startedAt 明确失败；段、context和进度不变。另含权限失监形成PARTIAL/UNMONITORED但无backward证据，以及evidence与Session/loss boundary不匹配的负例；不得通用clamp |
| `endPageBelowPersistedProgressIsRejected` | 当前42，请求40 → `IllegalArgumentException`，文案“不能低于已经记录的阅读位置”；超 totalPages 同样失败 |
| `beginFailureRollsBackDecisionButRetainsPreviouslyCommittedMonitoringLoss` | A 失败回滚 PENDING/关段；独立已提交的 PARTIAL/UNMONITORED 保留 |
| `completeFailureRetainsPendingAndRetryUsesOriginalBoundary` | B 写失败保留 A；恢复后 complete NORMAL/COMPLETED，结束时间不是重试时间，进度/summary/FTS 同步 |
| `duplicateBeginAndCompleteNeverRewriteBoundary` | 重复 begin 返回已存 snapshot（不采纳新请求）；complete 重复返回原 Session，不重写进度/summary/FTS |

- [ ] **最小实现:** A 在同一 `withTransaction` 复核 active Session、ACTIVE context、Learning Item、1..totalPages、最新 currentPage；重读活动段及已有最后闭合段边界。普通路径精确使用final wall，低于Session / active / latest closed boundary即拒绝，不沿用旧helper的`else delete`。没有活动段或读出的最后闭合终点越过活动起点时明确失败，不借例外修复历史；不在Closeout另建整场可信性算法。
- [ ] **最小实现:** 只有controller按已有jump条件明确确认的backward样本证据匹配当前Session，样本elapsed不倒退且wall明确后退，且事务内PARTIAL、`monitoringLostAt == active.startedAt == evidence.durableLossBoundary`、active类型UNMONITORED及id匹配、durable boundary高于final wall时，采用 `max(final wall, session.startedAt, latestDurableTimelineBoundary)`。latest boundary取活动段startedAt与已有最后闭合段终点，全部来自同一事务；已有闭合终点不得越过活动起点，不用max掩盖损坏结构。不读取UI缓存或当前系统时间，不在Repository另立jump阈值。然后close/delete末段并条件ACTIVE→PENDING，整体提交。相等的零时长UNMONITORED删除，不造1ms；保留PARTIAL/loss，B只读该snapshot。仅gap/撤权/普通旧样本不产生此例外资格。
- [ ] **最小实现:** B 只读持久化 boundary/page，复核不倒退，在事务内调用既有 SessionDao.finish、advanceProgress、SummaryEngine、SearchIndexWriter.reindexSession，最后条件 `PENDING → COMPLETED`。PENDING 缺少必需字段明确失败并保留事实；已 COMPLETED+NORMAL 返回原事实；其他状态拒绝。
- [ ] **Green:** 运行上述测试及原 StudyWorkflowRepositoryTest；故障注入只在测试内用临时 SQLite trigger 的 `RAISE(ABORT)`，每例移除/关闭隔离 DB；不进生产 Schema、Migration 或真实用户 DB。
- [ ] **Commit:** `feat(focus): persist precise closeout decisions`。

验证命令（未来执行，显式设置专用 AVD serial 为进程内 `$env:ANDROID_SERIAL`，不写入 Git）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.data.ModuleThreeDCloseoutRepositoryTest,com.guanyi.mirra.data.StudyWorkflowRepositoryTest" --no-daemon
git diff --check
```

## Task 2：冻结后拦截学习写入及迟到 DND

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/data/repository/ActiveLearningFactGuard.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/repository/FocusRepository.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/repository/InterventionReceiptRepository.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/repository/RoomDndStateStore.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`（仅 apply/release facade 资格）
- Test/Create: `app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDPendingGuardsTest.kt`
- Test/Modify: `app/src/androidTest/java/com/guanyi/mirra/data/RoomDndStateStoreTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/data/InterventionReceiptRepositoryTest.kt`

**Interfaces:** 新内部纯 predicate `isLearningFactWritable(session: StudySessionEntity?, context: SessionFocusContextEntity?): Boolean` = session slot1、endedAt/endType 空、context ACTIVE。调用者必须在自己的 Room transaction 内读取并复核，不靠事务外结果；不改变原公开 API 的拒绝方式。

| 入口 | PENDING / COMPLETED 后原语义 |
| --- | --- |
| updateCurrentPage | check 异常，不修改持久进度 |
| runtimeFacts / applyBehavior | null |
| confirmRisk / recordBriefRiskVisit / exitRisk | false |
| transition / loadState / markStableStarted / completeRecovery / promoteDeepFocus | check 异常 |
| recordEvent / updateHeartbeat / markMonitoringLost | check 异常；不回填历史 |
| InterventionReceiptRepository.isEligible / record | false；POSTED 仍不代表 SHOWN |
| DndRecord.active / prepare / lifecycle ACTIVE | false / check 异常；release metadata 仍可写 |

- [ ] **Red:** `pendingRejectsEveryFocusWriteEntryWithOriginalReturnSemantics` 对以上全部入口逐项 snapshot DB（Session/context/segments/events），调用后完全相等；`pageCommitBeforeBeginRejectsStaleEndPage` 与 `beginCommitBeforePageUpdateRejectsLateWrite` 用两种提交顺序证明 guard 位于事务内。
- [ ] **Red:** `pendingInvalidatesLateReceipts` 验证 IN_APP、OVERLAY、UNAVAILABLE 不追加回执；`pendingOwnedRuleAppearsInRecoveryQuery` 验证仍占 slot 的 PENDING 可释放；`pendingSessionCannotPrepareOrRetryApplyDnd` 验证下一场 preference 不影响本场关闭资格。
- [ ] **Red:** `lateApplyAfterActivationBeforeLifecycleWriteIsCompensated` 用真实 Room store+fake DndSystem barrier，在 prepare 前、prepare 后、activate 后制造 A 提交；最终不得有 ACTIVE durable状态/残留 Mirra rule，Session边界/coverage不变。已进入系统调用的竞态由既有 core compensation + 幂等 release 收尾，不宣称跨 DB/Android 瞬时原子。
- [ ] **Red:** `latePostStartHookCannotRecaptureClosedSessionPresentationOrApplyDnd`：configureSessionPresentationAndDnd延迟至PENDING/COMPLETED后才调用，不能capture该旧Session或开启DND。同步DndSystem的barrier使用两条受控工作线程与有限等待，不在单线程runTest里阻塞等待自己。
- [ ] **实现:** FocusRepo 复用 predicate，不改 threshold / state machine。Closeout A 的关段发生在 PENDING 写前；不要机械给 cleanup SQL 加 ACTIVE guard 使 A 自己无法关段。
- [ ] **实现:** RoomDndStateStore.get 把 PENDING 判 inactive；prepare 与 `setLifecycle(ACTIVE)` 事务复核，后者被拒会触发冻结 core 已有 compensation。RELEASE_PENDING/RELEASE_FAILED/RELEASED、失败补偿 metadata 不受学习写 guard 阻挡。
- [ ] **实现:** `listDndRecoveryContexts()` 将 `s.activeSlot IS NULL OR c.closeoutState='PENDING'` 纳入，仍要求 owned metadata 且非 RELEASED。AppContainer 迟到 apply 返回后 fresh-read，已逻辑结束则委托现有 DndController.release，不新增 ownership 实现。
- [ ] **实现:** AppContainer现有post-start facade在configure前读取durable closeout资格，configure/apply后再读；已逻辑结束委托既有channels cleanup/release与DND release。保留channels自身generation/epoch语义，不修改Presenter/FGS/READY；不能把activeSlot占用当作展示资格。
- [ ] **Green:** 运行本任务三组 Room 测试及既有 DndController/DndUserActions JVM 回归。
- [ ] **Commit:** `fix(focus): reject learning writes after closeout freeze`。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.data.ModuleThreeDPendingGuardsTest,com.guanyi.mirra.data.RoomDndStateStoreTest,com.guanyi.mirra.data.InterventionReceiptRepositoryTest" --no-daemon
.\gradlew.bat :app:testDebugUnitTest --tests '*DndControllerTest' --tests '*DndUserActionsTest' --no-daemon
```

## Task 3：最终确认串行化、取消补偿与锁外清理

**Files**

- Modify: `app/src/main/java/com/guanyi/mirra/domain/monitoring/BoundSessionMonitoringController.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/domain/monitoring/MonitoringModels.kt`（复用Task1的evidence DTO）
- Modify: `app/src/main/java/com/guanyi/mirra/platform/focus/MonitoringPlatformRuntime.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/domain/SessionManager.kt`
- Create: `app/src/main/java/com/guanyi/mirra/domain/SessionFinishResult.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`、`app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/feature/session/SessionScreen.kt`（先接新 finish 签名；完整确认 UI 属 Task 5）
- Test/Create: `app/src/test/java/com/guanyi/mirra/domain/SessionManagerCloseoutTest.kt`
- Test/Modify: `app/src/test/java/com/guanyi/mirra/domain/monitoring/BoundSessionMonitoringControllerTest.kt`、`app/src/test/java/com/guanyi/mirra/domain/monitoring/FocusSessionActionsTest.kt`
- Test/Modify: `app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`、`app/src/test/java/com/guanyi/mirra/feature/session/SessionFocusViewModelTest.kt`，并迁移现有直接 finish 的 Room/Flow 测试调用（通过调用清单核验，不删断言）。

**Interfaces**

```kotlin
// RuntimeFactsPort（只读）；RepositoryRuntimeFactsPort复用FocusRepository.observeContext(...).first()
suspend fun closeoutState(sessionId: String): FocusCloseoutState?
// BoundSessionMonitoringController；提供可空clock证据与仅锁内同步调用的纯内存失效hook
suspend fun <T> closeoutWithFacts(sessionId: String, sample: ClockSample, block: suspend (BackwardClockCloseoutEvidence?, () -> Unit) -> T): T
// MonitoringPlatformRuntime
suspend fun <T> closeoutWithMonitoringFacts(sessionId: String, sample: ClockSample, block: suspend (BackwardClockCloseoutEvidence?, () -> Unit) -> T): T
sealed interface SessionFinishResult {
    data class Completed(val session: StudySessionEntity) : SessionFinishResult
    data class PendingRetry(val sessionId: String) : SessionFinishResult
}
// SessionManager
suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample): SessionFinishResult
suspend fun retryPendingFinish(sessionId: String): SessionFinishResult
```

沿用 DefaultSessionManager，不新增第二个 coordinator。新增构造参数 `closeoutWithMonitoringFacts: suspend (String, ClockSample, suspend (BackwardClockCloseoutEvidence?, () -> Unit) -> SessionFinishResult) -> SessionFinishResult` 与 `cleanupClosedSession: suspend (String) -> Unit`；通用runtime方法由AppContainer薄lambda适配为该具体结果类型，保留原start回调。manager只将原sample.wall与controller evidence传给A，不自行算safe boundary。旧两个finish callbacks和旧finishWithFacts正常生产路径退出。cleanup函数在AppContainer顺序委托现有intervention/runtime/DND，分别尝试，单步失败不跳过后两步。MonitoringPlatformRuntime的`releaseSession(sessionId)`原签名与ownership不变，只调整调用位置。

- [ ] **Red:** `realSixSecondGapSettlesBeforeCloseout`：sample=最后健康查询+6000ms，先 durable loss 再 A，最终 PARTIAL/UNMONITORED；`olderConfirmSampleDoesNotInventMonitoringLoss` 保留 1ms inversion 语义，旧 sample仍执行最终命令。
- [ ] **Red:** `backwardClockJumpStillAllowsCloseoutAtLastDurableBoundary` 在controller/manager接线层复现Task1的1000→2000→final100 fixture；分别覆盖最终settlement发生jump和此前onSample已保存jump。沿用原 `clock jump clears candidate and never persists a backward boundary`，验证样本单调、证据只在loss提交后给A、snapshot2000、零未知段删除、NORMAL/COMPLETED与PARTIAL不变；B失败/重试不重采时间、不回填Focus。3D-2接入时继续验证effective unavailable。
- [ ] **Red:** `ordinaryBoundaryBeforeActiveSegmentStillFailsWithoutClockJumpEvidence` 在接线层证明6秒gap、权限撤销、1ms旧样本或其他Session证据不能生成backward例外，普通非法boundary仍拒绝。durable loss写入失败则仍阻断A，不能只凭runtime标记结束。
- [ ] **前置真Room验证:** 在ModuleThreeDCloseoutRepositoryTest中增加 `backwardClockJumpLossIsDurableBeforeCloseout`，使用真实FocusRepository/RepositoryRuntimeFactsPort与注入clock，覆盖FOCUS起点1000、可信点2000、wall100的loss→A链路（非仅fake）。此项与已durable夹具分列；若触发上述冻结核心校验冲突，停止并报告，不把它计为Closeout例外已通过，不自行修改状态机或放宽guard。
- [ ] **Red:** `closeoutRacesRiskAndBehaviorWithoutReopeningSegments` 与 `doubleFinalConfirmUsesOneBoundary`：barrier 控制提交顺序，只有边界前已 durable 确认才留下事实；未确认 candidate 不补证。
- [ ] **Red:** `cancellationBeforeBeginCommitLeavesReadingActive`、`cancellationAfterUnknownBeginCommitStillCleansPending`、`cancellationAfterCompletePreservesOriginalNormalResult`：不能只靠 begun 局部布尔判断；回查 durable 状态决定 cleanup。
- [ ] **Red:** `blockedAndroidCleanupDoesNotHoldFactsMutexOrRoomTransaction`：fake Android cleanup 挂起时另一 controller命令可完成且测试 hook 内 `database.inTransaction()==false`；`cleanupFailureDoesNotSkipMonitorAndDndRelease` 验证三步独立。
- [ ] **Red:** `beginCommittedThenCompleteFailsClearsPromptBeforeCleanupStarts`：A成功B失败、锁外cleanup挂起时prompt/evidence/candidate已失效；未知A-commit取消走finally durable复查同样失效。
- [ ] **实现:** 在现有mutex下unresolvedLoss guard → settleMonitoring → A → 调用纯内存失效hook → B。hook只清prompt/evidence/candidate/计时runtime，不访问Android API、不执行异步平台cleanup；不是第二套状态机。PENDING后refresh仍返回Unit并清runtime，action返回EXPIRED，不重开段。
- [ ] **实现:** 既有onSample/settlement明确判定backward跳变时，只在同一binding / mutex内保留最后可信与回退的真实ClockSample对，不从heartbeat拼elapsed、不把已夹高的wall当原始sample；成功持久化loss并回读匹配PARTIAL/lostAt/活动UNMONITORED后才形成BackwardClockCloseoutEvidence。提供前核对当前binding/generation与保留证据属于同一场。只做此结束证据接线，既有jump阈值、轮询/FGS、旧样本保护和FULL→PARTIAL规则不改；旧页面sample早于最新query不算jump。不能从generic gapReason字符串、PARTIAL或已失监推断backward。此前已经lost时仍可提供原已确认证据，不再次写loss；binding切换/释放或结束runtime失效后清除，不能泄漏给下一场。
- [ ] **实现:** controller锁内try/finally用有限NonCancellable查询RuntimeFactsPort.closeoutState，PENDING/COMPLETED再次幂等失效，覆盖A已提交但return被取消、未能调用hook的路径；非关闭状态不丢合法阅读runtime。查询失败保持错误可见，不以失败读伪造FULL/结束成功；锁外manager亦回查决定cleanup。
- [ ] **实现:** B 失败返回 PendingRetry，A 失败抛原错误且保留 ACTIVE 监测。retry 只 complete 原 PENDING，不重新 settle/begin/采 ClockSample；已 COMPLETED 返回原记录。
- [ ] **实现:** exception/cancellation 收尾在有限 `NonCancellable` 区段中回查 getCloseoutState；只有 PENDING/COMPLETED 才在事实锁外清理。取消仍向调用者传播，结果通过 durable state 恢复，不把整个流程置为不可取消。
- [ ] **实现:** 删除公开旧 finishSession 与无 sample 的 manager.finish；所有生产结束入口（阅读页、应用内/跨应用“结束”）共用新流程。`rg` 列出的旧调用必须迁移：旧低结束页被夹高的预期改为明确拒绝，保留 updatePage 单向/旧页Note断言。
- [ ] **Green / Commit:** 两组 controller 核心测试、SessionManagerCloseoutTest、既有 SessionStartCoordinatorTest 通过后 `feat(focus): serialize precise closeout and cleanup`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SessionManagerCloseoutTest' --tests '*BoundSessionMonitoringControllerTest' --tests '*FocusSessionActionsTest' --tests '*SessionStartCoordinatorTest' --no-daemon
rg -n 'finishSession\(|finishWithFacts|finishWithMonitoringFacts' app/src
```

最后一条检查输出应无正常生产绕过入口；历史文档不在检查范围。新 API 编译适配全部完成才提交，不能留下无法编译的中间提交。

## Task 4：PENDING 优先启动恢复

**Files:** Modify `app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`、`app/src/main/java/com/guanyi/mirra/domain/SessionManager.kt`、`app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`；Create `app/src/main/java/com/guanyi/mirra/domain/SessionRecoveryResult.kt`；Test/Create `app/src/test/java/com/guanyi/mirra/di/CloseoutStartupRecoveryTest.kt`；Test/Modify `app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDCloseoutRepositoryTest.kt`。

**Interfaces:** Repository / SessionManager的`suspend fun recoverInterruptedSession(): SessionRecoveryResult`同步适配。AppContainer.startup仍是`Deferred<Unit>`，PendingRetry不是启动崩溃；Session页面通过durable context暴露重试，不丢失错误分类。

```kotlin
sealed interface SessionRecoveryResult {
    data object Ready : SessionRecoveryResult
    data class PendingRetry(val sessionId: String, val cause: Throwable) : SessionRecoveryResult
}
```

- [ ] **Red:** `startupCompletesPendingBeforeAbnormalRecovery`：原 boundary NORMAL、无新段/FGS；`failedPendingRecoveryNeverBecomesAbnormalOrRestartsMonitoring`：保留 PENDING 与原页码、仍执行 channels cleanup/DND reconcile/images/search；`ordinaryActiveRecoveryStillEndsAbnormalWithUnmonitoredGap` 保留3A规则；`startupFailureDoesNotHideOwnedDndRelease` 验证清理不是成功路径附属。
- [ ] **Red:** `startupChannelCleanupFailureStillRunsDndAndStorageBootstrap`：startupCleanup抛异常后DND reconcile、images和search仍各自尝试；ordinary recovery失败同样进入cleanup，错误保持可观测，cancellation不被当普通成功吞掉。
- [ ] **实现:** 先读取 occupied Session/context，PENDING 单独调用 complete；失败返回 PendingRetry并禁止进入该 Session 的普通 ABNORMAL 分支。非 PENDING 按既有异常恢复；过期 Intent 处理独立保留。成功完成后的 summary/FTS 与正常 B 一致。
- [ ] **实现:** AppContainer先处理PENDING结果并清理其渠道/DND，再普通异常恢复的既有清理，再图片/FTS。startup清理各步独立尝试，不能因第一步抛错跳过DND；普通recovery失败也在finally尝试cleanup。失败Pending保留重试；cancellation有限收尾后传播，不后台启动FGS。
- [ ] **Green / Commit:** 上述 startup JVM 与 Room 测试通过，`fix(focus): recover pending closeout before interrupted reading`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*CloseoutStartupRecoveryTest' --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.data.ModuleThreeDCloseoutRepositoryTest" --no-daemon
```

## Task 5：保存门槛、确认框及重建/Back

**Files:** Modify `app/src/main/java/com/guanyi/mirra/feature/session/SessionScreen.kt`（SessionViewModel 当前就在此文件）、`app/src/main/java/com/guanyi/mirra/MirraApp.kt`、`app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`；Create `app/src/main/java/com/guanyi/mirra/feature/session/SessionFinishUiState.kt`；Test/Create `app/src/test/java/com/guanyi/mirra/feature/session/SessionCloseoutViewModelTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/SessionCloseoutUiTest.kt`；Test/Modify `app/src/test/java/com/guanyi/mirra/feature/session/SessionFocusViewModelTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/SessionFocusUiTest.kt`。

**Interfaces:** finishUi 为 `StateFlow<SessionFinishUiState>`，状态固定 Idle、PreparingConfirmation、Confirming(endPageText)、Saving、SaveFailed(logicallyClosed,message)、Completed(sessionId)。ViewModel 增加 `requestFinishConfirmation(): Unit`、`changeEndPage(text: String): Unit`、`cancelFinishConfirmation(): Unit`、`confirmFinish(onCompleted: (String) -> Unit): Unit`、`retryFinish(onCompleted: (String) -> Unit): Unit`；旧公开直接 finish 移除。内部 `suspend fun flushPendingEdits(): Unit`、跟踪 pending page writes 与正在执行的 save，Closeout Flow 使用 Task1接口。

- [ ] **Red:** `confirmationWaitsForInFlightDraftAndPageWrites`：延迟保存/page barrier未释放时不弹框；成功后默认最新持久化页；`draftFlushFailureKeepsSessionActive`：明确失败文案、不开框、计时监测仍继续。
- [ ] **Red:** `continueReadingDoesNotCaptureEndSampleOrFinish`、`finalConfirmationCapturesExactlyOneClockSample`、`lowerEndPageShowsExplicitErrorWithoutCallingFinish`、`partialPageInputKeepsPersistedNoteDefault`（40→4→42 与旧页35 Note保留）。
- [ ] **Red:** `recreatedPendingOnlyOffersRetry`、`pendingBackCannotResumeOrFlushLearningDraft`、`secondFinishTapDoesNotSubmitAgain`、`externalEndActionUsesTheSameConfirmationGate`、`pendingSlotRoutesToSavingInsteadOfReading`。
- [ ] **Red:** `recreatedPendingUsesFrozenBoundaryAndRequestedPage`：新ViewModel只读enum和snapshot恢复固定时长/请求页；当前wall推进不改变显示。损坏snapshot显示保存错误，不用now代填。
- [ ] **实现:** 分开 delayed debounce 与 in-flight save，先取消未开始的延迟，再 await实际保存（saveMutex序列化）；所有提交中的 page jobs成功后读取 durable page。Preparing/Confirming 时不允许继续改草稿造成确认后的未保存文本；“继续阅读”恢复编辑。500ms自动保存语义与 draftId 保持，不修改 NoteRepository。
- [ ] **实现:** 第一点击不采 end sample。最终确认同步切 Saving 禁重复，再调用注入的 clockSample（真实wall/elapsed）并传 manager；A失败允许回 Confirming/继续读，PENDING失败只能重试。不在最后确认后又运行 saveDraft。
- [ ] **实现:** Saving/PENDING时生命周期flush、evidence、页码/Note编辑、Break/Allowance操作停止；Back不导航回假Active。重建订阅observeCloseoutState并调用getCloseoutSnapshot；PENDING显示SaveFailed/重试、恢复请求页，COMPLETED幂等进Summary。显示时长截至snapshot.closeoutStartedAt，不随now增长。
- [ ] **实现:** MirraApp薄 route gate 将 occupied PENDING 指向既有 SessionRoute保存页面（包括冷启动PendingRetry）；不改Start六级resolver、IA或插入Analytics。不得从旧 Intent fast-path启动/绑定该 PENDING Session。正常Completed导航既有SessionSummaryRoute。
- [ ] **Green / Commit:** ViewModel 与Compose测试通过，`feat(session): confirm closeout after saving pending edits`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SessionCloseoutViewModelTest' --tests '*SessionFocusViewModelTest' --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.SessionCloseoutUiTest,com.guanyi.mirra.SessionFocusUiTest" --no-daemon
```

## Task 6：3D-1 Gate 与 checkpoint

**Files:** Update `docs/checkpoints/2026-10-04-module-3d-1.md`、`docs/CURRENT_STATE.md`；`docs/DECISIONS.md` 仅新增真实长期决定，保留历史；本任务不加功能。

**Interfaces:** 无新增生产接口，验收上面全部接口及实际调用清单。

- [ ] **Red/Green gate:** 如全量暴露真实回归，先保存失败证据、补对应最小失败测试，再修复本包范围后重新完整运行；不得删测试/拼局部绿灯。
- [ ] 在稳定且显式指定的专用 API37 AVD，运行完整 JVM、connected、lint、build；APK覆盖安装、普通/断网冷启动，至少正常确认与PENDING受控恢复。无 wipe-data / pm clear / uninstall；实际设备未授权不操作。
- [ ] 核验 Room4、schemas1–4及哈希；DND core、READY、状态机阈值与Phase2 formulas diff为空。checkpoint记录失败/跳过/Not Run和actual test totals，不沿用旧223/178当新结果。
- [ ] **Commit / Push:** `test(focus): record precise closeout validation`，Push本包分支，确认local==remote、clean，输出 `[3D_1_COMPLETE]` 后停止等待独立 review/freeze。不得进入3D-2。

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
git diff --check
Get-FileHash -Algorithm SHA256 app/schemas/com.guanyi.mirra.data.local.MirraDatabase/4.json
```

Expected: 全量单次无失败；skipped明确记录，专项前提缺失不记平台PASS；v4 hash `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。API23–36/OEM/完整实体机/TalkBack/release未实际执行继续NOT RUN。
