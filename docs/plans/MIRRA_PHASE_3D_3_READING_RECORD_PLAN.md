# Mirra Phase 3D-3｜结果、时间线与历史回看 Implementation Plan

> 逐 Task 使用 Red → 最小实现 → Green → reviewable commit；本文件是计划，不授权实施。下述新增类型均为读取/展示模型，不是 Room Entity。

**Goal:** 刚结束、单书历史和Search三个入口展示同一真实阅读记录，并在书籍详情使用有效优先、Phase 2回退的节奏。

**Architecture:** 单场 ReadingRecordRepository读取既有事实，ReadingRecordService复用3D-2 Validator生成统一projection；一个ReadingRecordViewModel和ReadingRecordContent由现有Summary/Session detail路由复用。书籍详情并列计算旧预测与新有效源，只选择显示口径、不改变算法。

**Tech Stack:** Kotlin、Flow/ViewModel、Compose/Lifecycle、Navigation 3、Room v4、冻结MirraTheme/Components、现有DndUserActions。

**Spec:** [MIRRA_PHASE_3D_DESIGN.md](MIRRA_PHASE_3D_DESIGN.md) §17–24、26、28–33；唯一设计基线 `874d80318c56661218fd03579ba2f1253cc35440`。

## Global Constraints

- 从已经独立验收通过的3D-2 freeze SHA创建 `codex/phase-3d-reading-record`；3D-1/2未冻结不得进入本包。
- Room4、schemas1–4、Migration、Entity/Column/Index不变；不新增依赖、权限或DI框架。
- 不再改Closeout、行为阈值、DND ownership、Coverage、READY、FGS；仅消费已完成接口。
- COMPLETE_TRUSTED才有整场有效时间和整场次数摘要；可信0显示0，不可用不写0。
- generatedSummary继续存储/搜索，但不作为新结果主视觉统计源。
- 相邻连续FOCUS/DEEP_FOCUS仅在展示合并；无package名fallback，不读取真实App内容/通知或现时安装名称。
- Mirra Blue灰白主体，蓝色只行动/选择，统计文本平面；不默认Material紫色、不写品牌Hex、不建统计Card系统。
- Start不接Analytics，Mine不做Dashboard；不增加分数、专注百分比、排名、评价、图表或slogan。
- 有效窗口成立与未来预测可用分开；自然日期仍由原Phase2 calendar pace/门槛/robustCV决定。
- 全部路径相对 `C:/Users/CDD/Documents/ChatGPT/Mirra`；所有测试/截图都是未来执行项，不是当前PASS。

## Review Focus

| 危险场景 | 具体测试 |
| --- | --- |
| Summary/History/Search分别算指标，读到不同数值 | Task4 `threeEntryPointsRenderTheSameRecordProjection` |
| PARTIAL把局部次数或Focus分钟包装成整场数据 | Task2/3 `partialTimelineNeverClaimsWholeSessionCounts` |
| 可信0用“不足1分钟”或“监测不完整”替代 | Task3 `trustedZeroFocusRendersZeroMinutes` |
| label缺失泄露包名或查询现有安装名 | Task2 `missingRiskLabelNeverFallsBackToPackageName` |
| effective window存在但速度0/日期不可用，被切回overall | Task5 `availableEffectiveWindowDoesNotFallbackWhenPredictionIsUnavailable` |
| history/搜索导航重开Session或返回链错乱 | Task4 `historyAndSearchNeverReopenTheRecordedSession` |
| DND失败提示在旧历史反复显示或触发新apply | Task3 `dndWarningOnlyAppearsOnJustSavedResultAndUsesReleaseRetry` |
| 窄屏/大字体隐藏完成/返回/重试 | Task6 `recordActionsRemainReachableAcrossWidthsAndFontScale` |

## Task 1：Reading Record 只读Source

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/data/local/model/ReadingRecordSource.kt`
- Create: `app/src/main/java/com/guanyi/mirra/data/repository/ReadingRecordRepository.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt`、`app/src/main/java/com/guanyi/mirra/data/local/dao/NoteDao.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`、`app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`
- Test/Create: `app/src/androidTest/java/com/guanyi/mirra/data/ReadingRecordRepositoryTest.kt`

**Interfaces**

```kotlin
data class ReadingRecordSource(
    val session: StudySessionEntity, val context: SessionFocusContextEntity?,
    val segments: List<SessionSegmentEntity>, val riskSnapshots: List<SessionRiskAppSnapshotEntity>,
    val noteCount: Int,
)
interface ReadingRecordRepository { fun observe(sessionId: String): Flow<ReadingRecordSource?> }
class DefaultReadingRecordRepository(database: MirraDatabase) : ReadingRecordRepository
// FocusDao
fun observeRiskSnapshots(sessionId: String): Flow<List<SessionRiskAppSnapshotEntity>>
// NoteDao
fun observeNonBlankCountForSession(sessionId: String): Flow<Int>
// AppContainer与TestAppContainer同步提供
val readingRecordRepository: ReadingRecordRepository
```

- [ ] **Red:** `recordSourceUsesExistingFactsAndNonBlankNoteCount`：Session/context/segments/snapshots真实关联；空正文/纯空格Note不计；不读取全部Note正文。`recordSourceMissingSessionIsNull`；`recordSourceRefreshesAfterNoteOrDndMetadataChanges`；`singleRecordUsesFixedQueryBudget`。
- [ ] **实现:** 复用workflow/SessionDao的单Session Flow、FocusDao.observeContext/observeSegments，加snapshot和Note count，共固定5条Flow（不是每段一次）。count采用 `TRIM(content) != ''`，不修改Phase2原count查询。未知Session返回null，缺context保留source供不可用说明。
- [ ] **实现:** Source不保存新字段、不写数据库、不读取当前安装App。组合缺项/未闭合只进入Validator的不可用判断；结束事实由3D-1冻结，元数据刷新可以重新派生。
- [ ] **Green / Commit:** 真Room tests通过；`feat(records): observe a unified local reading record source`。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.data.ReadingRecordRepositoryTest" --no-daemon
```

## Task 2：统一事实Projection与人类时间线

**Files:** Create `app/src/main/java/com/guanyi/mirra/domain/ReadingRecordModels.kt`、`app/src/main/java/com/guanyi/mirra/domain/ReadingRecordService.kt`；Test/Create `app/src/test/java/com/guanyi/mirra/domain/ReadingRecordProjectionTest.kt`。

**Interfaces**

```kotlin
enum class HumanReadingSegment { READING, BREAK, TEMPORARY_USE, DISTRACTION, RECOVERING, UNMONITORED }
data class HumanReadingInterval(val type: HumanReadingSegment, val startedAt: Long, val endedAt: Long, val appLabel: String?)
data class ReadingSegmentCounts(val breaks: Int, val allowances: Int, val distractions: Int)
data class ReadingRecordProjection(
    val sessionId: String, val startPage: Int, val endPage: Int?, val pagesRead: Long,
    val totalDurationMillis: Long?, val noteCount: Int, val endType: SessionEndType?,
    val trust: TimelineTrust, val monitoringStatus: MonitoringCoverage?, val effectiveFocusMillis: Long?,
    val wholeSessionCounts: ReadingSegmentCounts?, val timeline: List<HumanReadingInterval>,
    val dndLifecycle: DndLifecycle?,
)
class ReadingRecordService(private val validator: SessionTimelineValidator = SessionTimelineValidator()) {
    fun project(source: ReadingRecordSource): ReadingRecordProjection
}
```

- [ ] **Red:** `continuousFocusAndDeepFocusMergeOnlyInPresentation`：相邻精确衔接→一条READING，原segments/list/type/Validator值不变；gap或其他段隔开不合并。
- [ ] **Red:** `countsComeFromSegmentsNotFocusEventsOrClicks`：Break/Allowance/Distraction各取原Segment数；没有读取FocusEvent或回执的输入。
- [ ] **Red:** `partialTimelineNeverClaimsWholeSessionCounts`：PARTIAL/NONE/STRUCTURE_INVALID的wholeSessionCounts和effective=null，但真实closed正持续段可查看；可信0时有效0与可信计数保持。
- [ ] **Red:** `riskLabelsUseHistoricalSnapshot`、`missingRiskLabelNeverFallsBackToPackageName`：空/缺snapshot均“风险 App”；不能用com.xxx作普通文案，输入无需Android Context。
- [ ] **Red:** `malformedTimelineIsMarkedAndNeverRepaired`：open/zero不伪造终点；gap不补Focus，真实边界不clip/shift；`oldRecordRetainsPagesDurationAndNotesWithoutFakeFocus`。
- [ ] **实现:** 调Validator，再计算总时长/页数的安全差值；总时长与effective分开。全部closed正持续段按真实起点排序供展示；损坏时间线保留不可用说明，不自动修复原事实。
- [ ] **实现:** label从snapshot映射；FOCUS/DEEP→READING、BREAK→BREAK、ALLOWANCE→TEMPORARY_USE、DISTRACTION→DISTRACTION、RECOVERY→RECOVERING、UNMONITORED→UNMONITORED。只合并连续同标签READING，不合并次数事件。
- [ ] **Green / Commit:** projection+validator JVM通过；`feat(records): derive human readable trusted reading records`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ReadingRecordProjectionTest' --tests '*SessionTimelineValidatorTest' --no-daemon
```

## Task 3：共享ReadingRecordContent与DND弱提示

**Files**

- Create: `app/src/main/java/com/guanyi/mirra/feature/session/ReadingRecordViewModel.kt`、`app/src/main/java/com/guanyi/mirra/feature/session/ReadingRecordContent.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/feature/session/SessionSummaryScreen.kt`、`app/src/main/java/com/guanyi/mirra/feature/knowledge/SearchScreen.kt`
- Modify: `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`、`app/src/main/java/com/guanyi/mirra/MirraApp.kt`、`app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`
- Test/Create: `app/src/test/java/com/guanyi/mirra/feature/session/ReadingRecordViewModelTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/ReadingRecordUiTest.kt`

**Interfaces**

```kotlin
class ReadingRecordViewModel(sessionId: String, repository: ReadingRecordRepository, service: ReadingRecordService) : ViewModel
// uiState: StateFlow<ReadingRecordUiState>，Loading / Ready(record) / Missing / Error
@Composable fun ReadingRecordContent(record: ReadingRecordProjection, expanded: Boolean, onToggleTimeline: () -> Unit, modifier: Modifier = Modifier)
@Composable fun SessionSummaryScreen(viewModel: ReadingRecordViewModel, dndActions: DndUserActions, onViewRecord: (String) -> Unit, onDone: () -> Unit)
@Composable fun SessionSearchDetailScreen(viewModel: ReadingRecordViewModel, onBack: () -> Unit)
// AppContainer同一实例
val readingRecordService: ReadingRecordService
```

旧 SessionSummaryViewModel / SessionSearchDetailViewModel 的独立读取/计算退出，由同一ReadingRecordViewModel取代；不再 notes.size 或generatedSummary分支计算统计。两种Screen只提供不同标题/退出行为。

- [ ] **Red:** `trustedRecordShowsDurationFocusNotesAndCounts`、`trustedZeroFocusRendersZeroMinutes`、`partialAndNoneUseHonestChineseMessages`、`invalidStructureUsesRecordIncompleteMessage`、`readingRecordDoesNotExposeInternalEnumsScoresOrPackages`。
- [ ] **Red:** `defaultRecordIsCollapsedAndCanShowRealTimeline`、`missingRecordOffersReturnNotEndAgain`、`dndWarningOnlyAppearsOnJustSavedResultAndUsesReleaseRetry`。
- [ ] **实现:** Summary标题“本次阅读已保存”；detail标题“阅读记录”。默认页码、总时长、笔记；可信时有效专注/完整次数，按钮“查看本次记录”“完成”。展开列真实区间，中文映射“阅读/休息/临时使用/分心/正在回到学习/监测中断”，显示本地时间、不展示内部事件。
- [ ] **实现:** 可信effective=0精确“0 分钟”；正值不足1min可“不足 1 分钟”。PARTIAL提示“本次手机监测不完整，未生成有效专注时间”；NONE明确“未开启手机监测”；FULL结构无效用“本次记录不完整，未生成有效专注时间”。ABNORMAL保持异常标签、无有效数据，不显示“正常已保存”事实误导。
- [ ] **实现:** plain LazyColumn/既有Mirra组件，不新Card系统。expanded用rememberSaveable(sessionId)；Loading/Missing/Error不冒充0结果，不展示raw异常。统计数值只从projection取。
- [ ] **实现:** 仅刚结束结果/保存界面显示RELEASE_PENDING/RELEASE_FAILED弱提示“Mirra 勿扰状态需要处理”；调用既有DndUserActions接口`suspend fun retryRelease(): Unit`及`fun settingsIntent(): Intent`，前者已委托reconcile并refresh，设置页返回时调用`refresh()`。不调用apply，不修改preference。旧History/Search不持续提示历史DND失败。
- [ ] **Green / Commit:** ViewModel与Compose通过；`feat(records): share mirra reading record content`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ReadingRecordViewModelTest' --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.ReadingRecordUiTest" --no-daemon
```

## Task 4：Summary / History / Search 三入口同源

**Files:** Modify `app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemReadingSection.kt`、`app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemScreens.kt`、`app/src/main/java/com/guanyi/mirra/MirraApp.kt`；Test/Create `app/src/androidTest/java/com/guanyi/mirra/ReadingRecordNavigationTest.kt`；Test/Modify `app/src/androidTest/java/com/guanyi/mirra/MirraAppTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/ModuleTwoDFlowTest.kt`（本Task只适配新增onOpenSession参数，保留全部旧断言）。

**Interfaces:** `LearningItemDetailScreen(..., onOpenSession: (String) -> Unit)`、`LearningItemReadingSection(..., onOpenSession: (String) -> Unit)`、`SessionHistoryRow(item: SessionHistoryUi, onClick: () -> Unit, modifier: Modifier = Modifier)`。

- [ ] **Red:** `threeEntryPointsRenderTheSameRecordProjection`：同一Room fixture从Summary、书籍history、Search SESSION进入，页码/时长/effective/counts/label/interval逐项相同；`historyAndSearchNeverReopenTheRecordedSession`：无manager.start、无Intent/Session/Segment插入。
- [ ] **Red:** `historyBackReturnsToLearningItemAndSearchBackReturnsToResults`，Summary完成回Start不恢复Session；异常历史仍可点击查看且明确排除有效指标。
- [ ] **实现:** 保留 Routes.kt 现有SessionSummaryRoute和SessionSearchDetailRoute，不新建第二种记录路由。History与Search均打开SessionSearchDetailRoute(sessionId)；Summary“查看本次记录”也打开该route，返回Summary；共同注入Task3同一VM/service/content。
- [ ] **Green / Commit:** 导航与原MirraAppTest通过；`feat(records): open unified records from reading history`。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.ReadingRecordNavigationTest,com.guanyi.mirra.MirraAppTest" --no-daemon
```

## Task 5：书籍详情有效优先、Phase 2回退

**Files:** Modify `app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemScreens.kt`（LearningItemDetailViewModel在此）、`app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemReadingSection.kt`、`app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`、`app/src/main/java/com/guanyi/mirra/MirraApp.kt`、`app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`；Test/Create `app/src/test/java/com/guanyi/mirra/feature/knowledge/LearningItemEffectivePaceTest.kt`；Test/Modify `app/src/androidTest/java/com/guanyi/mirra/ModuleTwoDFlowTest.kt`。

**Interfaces:** LearningItemDetailViewModel原参数保留并增加 `effectiveService: EffectiveReadingService`；AppContainer提供同实例service。原ReadingAnalyticsRepository已经由3D-2扩展。内部 `buildAnalyticsUi(..., effective: EffectiveReadingEstimate?): LearningItemAnalyticsUi`，保持现有UI DTO字段，不加数据库状态或用户模式。

- [ ] **Red:** `availableEffectiveWindowDoesNotFallbackWhenPredictionIsUnavailable`：有效window成立、speed0/PAUSED/COMPLETED/自然日期不合格，都仍显示有效速度事实，不改成overall。
- [ ] **Red:** `insufficientEffectiveWindowKeepsExistingPhaseTwoPace`：只有window不成立才用原UI；`naturalDateIsIdenticalBeforeAndAfterEffectiveWiring`：effective输入改变不影响既有自然range/置信度；`effectiveAndOverallSpeedsAreNotShownTogether`。
- [ ] **实现:** 与现有timeContext联动读最近30个本地自然日effective源，复用同一个now/zone生成所有窗口；旧source/prediction独立计算。有效源失败明确错误并不冒充window不足或静默替换成已通过的有效结果。
- [ ] **实现:** window成立显示“有效阅读速度约 X 页/小时”“根据最近 N 天 M 次完整阅读”；remaining可用才显示“预计还需约 Y 有效阅读”。自然日期继续取Phase2 predict；window不成立保留旧overall/ordinary remaining UI。predict不可用不是window不足。
- [ ] **Green / Commit:** 新pace JVM、ModuleTwoDFlowTest和Phase2 pure回归通过；`feat(analytics): prefer qualified effective pace in book details`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LearningItemEffectivePaceTest' --tests '*CompletionPredictionServiceTest' --tests '*ReadingAnalyticsServiceTest' --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.ModuleTwoDFlowTest" --no-daemon
```

## Task 6：UI可达性与3D-3 Gate

**Files:** Test/Modify `app/src/androidTest/java/com/guanyi/mirra/ReadingRecordUiTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/ReadingRecordNavigationTest.kt`；Update `docs/checkpoints/2026-10-04-module-3d-3.md`、`docs/CURRENT_STATE.md`。

**Interfaces:** 无新业务接口；验收以上ReadingRecord、pace、原Closeout UI。

- [ ] **Red/Green:** `recordActionsRemainReachableAcrossWidthsAndFontScale` 参数320/360/411dp、fontScale1/2，LazyColumn performScrollTo可访问展开/完成/返回/DND retry；实际touch≥48dp。不删断言/无理由加超时。
- [ ] 真实截图Summary FULL、PARTIAL/NONE、展开timeline、书籍有效节奏；核对灰白/蓝色克制、无Card墙/品牌硬编码/紫色/内部术语。Start六级及Mine原平面7天摘要不变。
- [ ] 完整单次JVM、connected、lint、assemble；覆盖安装与离线Summary→History→Search回看。Room4/Schema1–4/hash不变；checkpoint区分自动化/真实人工/NOT RUN。
- [ ] **Commit / Push:** `test(records): verify shared reading record experience`，Push本包分支，local==remote、clean；输出 `[3D_3_COMPLETE]`，等待独立review/freeze，不进入3D-4。

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
git diff --check
Get-FileHash -Algorithm SHA256 app/schemas/com.guanyi.mirra.data.local.MirraDatabase/4.json
```

Expected: 本包实际全量无失败、跳过如实记；hash仍 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。TalkBack仅真实执行才PASS；API23–36/OEM/完整实体机/release未执行继续NOT RUN。
