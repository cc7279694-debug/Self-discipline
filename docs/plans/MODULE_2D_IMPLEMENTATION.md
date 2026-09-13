# 观已 Mirra｜Module 2D 阅读分析与完成预测实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本计划通过用户验收前不得实施。

**Goal:** 完全使用现有 Room Schema v3 的 Learning Item、Session 与 Note 事实，为单本书提供阅读历史、近期整体阅读速度、预计剩余阅读时间和可信的自然完成日期范围，并在“我的”展示最近 7 天基础阅读摘要。

**Architecture:** 延续单 Android App Module 与 `Compose → ViewModel → ReadingAnalyticsService / CompletionPredictionService → ReadingAnalyticsRepository → Room`。Repository 只提供原始只读投影，所有资格判断、窗口选择和预测均由纯 Kotlin 确定性函数完成；不保存聚合结果，不建立 Analytics Framework。

**Tech Stack:** Kotlin、Jetpack Compose、Navigation 3、Coroutines / Flow、Room 2.8.5、SQLite、`java.time`、core library desugaring、手工 `AppContainer`。

**Spec:** `docs/PRODUCT_SPEC.md`、`docs/DECISIONS.md`、`docs/plans/PHASE_2_IMPLEMENTATION.md`、`docs/checkpoints/2026-09-12-module-2c.md`

## Global Constraints

- 只实施 Module 2D；完成后停止，不进入 Phase 3。
- Room 必须保持 Schema v3；不得新增 Entity、Table、Column、Index 或 Migration，`1.json`、`2.json`、`3.json` 均不得变化。
- 统计只使用 Session 总时长，文案只能称为“阅读时长”“Session 时长”“整体阅读速度”，不得称为有效专注时间或有效阅读速度。
- 不实现 SessionSegment、DND、Usage Access、Risk App、Overlay、Recovery、Start / Maintain / Recover 模型、效率异常提醒、AI、机器学习、云同步或 Review。
- 不建立持久化统计缓存、后台聚合作业、WorkManager、Paging、事件总线、规则 DSL 或通用预测框架。
- UI 不直接访问 DAO；所有数据查询经 Repository，算法通过纯 Kotlin Service 执行。
- Start 六级状态、`currentPage` 语义、Intent / Session 状态机、图片补偿、Topic 与 FTS 行为必须无回归。

---

## 0. Task Contract

### Goal

用户能看清最近真实阅读事实，并在数据足够时得到两个不同答案：真正坐下来还需读多久，以及按近期真实频率大约何时自然读完。

### Scope

- 单本 Learning Item 的进度、最近阅读、Session 历史、近期整体阅读速度；
- 预计剩余阅读时间；
- 符合门槛时的自然完成日期范围与高/中/低可信度；
- “我的”最近 7 天 Session、阅读时长、推进页数、Note 数量和前 7 天事实比较；
- 只读 Room projection、纯 Kotlin 统计/预测服务、ViewModel 与 Compose 展示；
- 本地时区、DST、异常数据防御与全量回归。

### Out of Scope

专注分段、有效专注时间、跨书速度比较、能力评分、行为诊断、目标日期、催促、异常提醒、图表系统、全局 Session History、长期 30/90 天 Dashboard、数据库缓存或任何 Phase 3/4 能力。

### Dependencies

- 当前分支 `codex/phase-2c-topic-search`，HEAD `e677551`；
- `LearningItemEntity`、`StudySessionEntity`、`NoteEntity`；
- `SessionEndType.NORMAL / ABNORMAL` 和 `LearningItemStatus`；
- 现有 `LearningItemDetailScreen`、`ProfileScreen`、Navigation 3 与手工 `AppContainer`；
- Room v3 schema export 与现有 JVM / Instrumented / Compose 测试基础。

### Acceptance / Verification

先以 JVM 测试冻结算法，再增加只读投影与 Room 测试，最后接入两个页面并全量回归。实现阶段不得用 UI 示例反推或覆盖本计划中的公式。

## 1. 当前数据与架构复用分析

### 1.1 可直接复用的数据

`LearningItemEntity` 已有：

- `status`：决定是否允许显示未来预测；
- `totalPages/currentPage`：计算进度与剩余页数；
- `name`：页面标题；
- `completedAt`：只保留事实，本模块不参与预测公式。

`StudySessionEntity` 已有：

- `learningItemId`；
- `startedAt/endedAt`；
- `startPage/endPage`；
- `endType`；
- `generatedSummary`。

`NoteEntity` 已有 `sessionId`、`createdAt`，因此可以一次 JOIN 得到每个 Session 的 Note 数，并独立统计最近新增 Note。Session 外 Note 同样可计入“新增 Note 数量”。

### 1.2 现有查询与页面

- `SessionDao.observeLatestNormalReading()` 已支持 Start 的单条轻量投影，继续保留，不把它扩成 Analytics API。
- `LearningItemDetailViewModel` 当前组合 Learning Item 与最近 Summary；2D 只追加阅读分析流，不改变生命周期操作或开始入口。
- `ProfileScreen` 当前只有本地数据说明，是“我的”7 天摘要的最小接入点。
- `SessionSearchDetailRoute` 继续只服务搜索结果；2D 阅读历史直接在 Learning Item 详情内展开，不新增全局 Session History 路由。
- `AppContainer` 继续手工装配两个纯服务和一个只读 Repository；不引入 DI Framework。

### 1.3 需要增加的最小边界

- `ReadingSessionProjection`：一次返回 Session 历史所需原始字段及 Note 数量；
- `ReadingAnalyticsRepository`：隐藏 DAO，提供单书最近 30 天、单书历史和全局最近 14 天事实流；
- `ReadingAnalyticsService`：资格筛选、窗口、速度、历史和 7 天摘要；
- `CompletionPredictionService`：剩余阅读时间、日历推进速度、日期范围和可信度；
- Learning Item 阅读区与“我的”7 天摘要。

不复用 `SearchFts` 做统计，不修改 Session 写路径，不新增 Analytics 表。

## 2. 有效 Session 精确定义

唯一有效统计条件：

```kotlin
fun isQualified(session: ReadingSessionProjection): Boolean =
    session.endType == SessionEndType.NORMAL &&
        session.endedAt != null &&
        session.endPage != null &&
        session.endedAt > session.startedAt
```

因此以下均排除全部统计与预测：

- Active Session；
- `ABNORMAL`；
- `EARLY`、`AUTO`、`START_INCOMPLETE`；
- `endedAt <= startedAt`；
- 缺少 `endedAt` 或 `endPage`。

合格 Session 的计算：

```text
durationMillis = endedAt - startedAt
pagesRead = max(0, endPage - startPage)
```

实现时先转为 `Long` 再相减：`max(0L, endPage.toLong() - startPage.toLong())`。明确不 `+1`，也不修改任何原始 Session；避免异常 Int 边界在派生计算中溢出。

### 零推进 Session

`pagesRead = 0` 但其余条件合格时：

- 保留为正常阅读历史；
- 计入有效 Session 数、阅读日和总阅读时长；
- 计入整体速度 denominator，页数 numerator 增加 0；
- 计入 7/14/30 天窗口的 3 Session / 30 分钟门槛；
- 计入波动样本，单次速度为 0；
- 不增加总推进页数或 calendar pace。

理由：这是一次真实发生的正常阅读，但没有可观测页码推进。排除其时长会虚增整体速度；将它自动改成异常则会改写用户事实。

## 3. DAO Projection 与 Repository 查询设计

新增只读模型：

```kotlin
data class ReadingSessionProjection(
    val sessionId: String,
    val learningItemId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val startPage: Int,
    val endPage: Int?,
    val endType: SessionEndType?,
    val noteCount: Int,
)
```

`SessionDao` 增加三个语义明确的 JOIN 查询：

```kotlin
@Query("""
    SELECT s.id AS sessionId, s.learningItemId, s.startedAt, s.endedAt,
           s.startPage, s.endPage, s.endType, COUNT(n.id) AS noteCount
    FROM study_sessions s
    LEFT JOIN notes n ON n.sessionId = s.id AND TRIM(n.content) != ''
    WHERE s.learningItemId = :learningItemId
      AND s.endedAt IS NOT NULL
    GROUP BY s.id
    ORDER BY s.endedAt DESC, s.id DESC
""")
fun observeHistory(learningItemId: String): Flow<List<ReadingSessionProjection>>

@Query("""
    SELECT s.id AS sessionId, s.learningItemId, s.startedAt, s.endedAt,
           s.startPage, s.endPage, s.endType, COUNT(n.id) AS noteCount
    FROM study_sessions s
    LEFT JOIN notes n ON n.sessionId = s.id AND TRIM(n.content) != ''
    WHERE s.endedAt >= :fromInclusive AND s.endedAt <= :toInclusive
    GROUP BY s.id
    ORDER BY s.endedAt DESC, s.id DESC
""")
fun observeEndedBetween(
    fromInclusive: Long,
    toInclusive: Long,
): Flow<List<ReadingSessionProjection>>

@Query("""
    SELECT s.id AS sessionId, s.learningItemId, s.startedAt, s.endedAt,
           s.startPage, s.endPage, s.endType, COUNT(n.id) AS noteCount
    FROM study_sessions s
    LEFT JOIN notes n ON n.sessionId = s.id AND TRIM(n.content) != ''
    WHERE s.learningItemId = :learningItemId
      AND s.endedAt >= :fromInclusive AND s.endedAt <= :toInclusive
    GROUP BY s.id
    ORDER BY s.endedAt DESC, s.id DESC
""")
fun observeEndedForItemBetween(
    learningItemId: String,
    fromInclusive: Long,
    toInclusive: Long,
): Flow<List<ReadingSessionProjection>>
```

历史只列已结束 Session；Active Session 已由 Start / Session 页面管理，不在历史重复展示。ABNORMAL 和其他已结束类型保留在历史中并标识，但 Service 不纳入统计。

`NoteDao` 增加纯计数：

```kotlin
@Query("SELECT COUNT(*) FROM notes WHERE createdAt >= :fromInclusive AND createdAt < :toExclusive AND TRIM(content) != ''")
fun observeCreatedCountBetween(fromInclusive: Long, toExclusive: Long): Flow<Int>
```

Note 数量是独立内容事实：Session 内、Session 外 Note 都计入；即使 Note 所属 Session 后来被标为 ABNORMAL，Note 本身仍是用户真实留下的内容，不随 Session 统计资格被抹去。ABNORMAL 不会增加 Session 数、时长或页数。

最小 Repository：

```kotlin
data class SevenDaySource(
    val sessions: List<ReadingSessionProjection>,
    val currentNoteCount: Int,
    val previousNoteCount: Int,
)

interface ReadingAnalyticsRepository {
    fun observeHistory(learningItemId: String): Flow<List<ReadingSessionProjection>>
    fun observeRecentForItem(
        learningItemId: String,
        fromInclusive: Long,
        toInclusive: Long,
    ): Flow<List<ReadingSessionProjection>>
    fun observeFourteenDaySource(
        fromInclusive: Long,
        currentPeriodStart: Long,
        toExclusive: Long,
    ): Flow<SevenDaySource>
}
```

`observeRecentForItem` 使用 `observeEndedForItemBetween()`，不先加载整段历史再过滤。现有 `learningItemId` 索引可服务主过滤；首版不为 `endedAt` 新增索引，因为这会违反 Schema v3 不变约束。全局 14 天查询可能扫描本地 Session 表，个人数据规模可接受，需用 10,000 条数据做烟测记录。

## 4. 时间、自然日与 DST 设计

为了在 `minSdk 23` 使用 `java.time`，实施时只增加 Android 官方 core library desugaring：

```kotlin
// gradle/libs.versions.toml
desugarJdkLibs = "2.1.5"
android-desugar-jdk-libs = { module = "com.android.tools:desugar_jdk_libs", version.ref = "desugarJdkLibs" }

// app/build.gradle.kts
compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
}
```

这是兼容库配置，不是新业务框架。Android 官方说明 core library desugaring 可在低 API 使用 `java.time`；2.1.5 要求 AGP 8+，当前 AGP 9.4 满足。不得顺便升级任何其他依赖。

时间注入只保留一个小边界：

```kotlin
data class AnalyticsTimeContext(val now: Instant, val zoneId: ZoneId)

class AnalyticsTimeProvider(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    fun snapshot() = AnalyticsTimeContext(clock.instant(), zoneProvider())
}
```

- Service 方法接收 `AnalyticsTimeContext`，内部不调用 `System.currentTimeMillis()`；
- 测试使用 `Clock.fixed()` 和固定 `ZoneId`；
- ViewModel 初始化及页面 `ON_RESUME` 时重新取得 snapshot，因此设备时区变化后重新进入页面会按新时区计算；
- 不监听常驻广播，不增加后台任务。

自然日窗口统一按 Session `endedAt` 所在设备本地日期归属：

```text
7 天 = 今天 + 前 6 个本地自然日
14 天 = 今天 + 前 13 个本地自然日
30 天 = 今天 + 前 29 个本地自然日
```

起点使用 `LocalDate.atStartOfDay(zoneId).toInstant()`；终点为 snapshot 的 `now`。日期加减使用 `LocalDate.plusDays/minusDays`，禁止用 `24 * 60 * 60 * 1000`，因此 DST 的 23/25 小时日仍只算一个自然日。

阅读日数为合格 Session `endedAt` 对应 `LocalDate` 去重数。数据跨度：

```text
inclusiveSpanDays = DAYS.between(firstReadingDate, lastReadingDate) + 1
```

所以选定 7 天窗口中，只有最早和最晚有效记录覆盖窗口首尾日期时才可能达到跨度 7 天。

## 5. ReadingAnalyticsService API

```kotlin
data class QualifiedSession(
    val sessionId: String,
    val learningItemId: String,
    val endedDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val startPage: Int,
    val endPage: Int,
    val duration: Duration,
    val pagesRead: Long,
    val noteCount: Int,
)

data class SelectedAnalyticsWindow(
    val days: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val sessions: List<QualifiedSession>,
    val totalDuration: Duration,
    val totalPagesRead: Long,
    val readingDays: Int,
    val inclusiveDataSpanDays: Int,
    val overallPagesPerHour: Double,
)

data class ReadingPeriodSummary(
    val sessionCount: Int,
    val totalDuration: Duration,
    val totalPagesRead: Long,
    val noteCount: Int,
)

data class SevenDayComparison(
    val current: ReadingPeriodSummary,
    val previous: ReadingPeriodSummary,
)

class ReadingAnalyticsService {
    fun qualify(
        sessions: List<ReadingSessionProjection>,
        time: AnalyticsTimeContext,
    ): List<QualifiedSession>

    fun selectAnalyticsWindow(
        sessions: List<ReadingSessionProjection>,
        time: AnalyticsTimeContext,
    ): SelectedAnalyticsWindow?

    fun buildSevenDayComparison(
        sessions: List<ReadingSessionProjection>,
        currentNoteCount: Int,
        previousNoteCount: Int,
        time: AnalyticsTimeContext,
    ): SevenDayComparison
}
```

`qualify()` 还必须排除 `endedAt > now` 的未来记录；时间戳异常只影响统计结果，不回写数据库。

## 6. 7 / 14 / 30 天窗口算法

```kotlin
private val candidateDays = listOf(7, 14, 30)

fun selectAnalyticsWindow(...): SelectedAnalyticsWindow? =
    candidateDays.firstNotNullOfOrNull { days ->
        val startDate = today.minusDays(days - 1L)
        val samples = qualified.filter { it.endedDate in startDate..today }
        val duration = samples.fold(Duration.ZERO) { total, it -> total + it.duration }
        if (samples.size >= 3 && duration >= Duration.ofMinutes(30)) {
            buildWindow(days, samples)
        } else null
    }
```

固定规则：

- 7 天满足即选 7 天，不为了让自然完成日期通过而继续扩到 14/30 天；
- 7 天不足才看 14 天，14 天不足才看 30 天；
- 30 天仍不足返回 `null`；
- 窗口日历推进速度 denominator 永远是选定的 7、14 或 30 个自然日，不是有阅读的天数，也不是数据跨度；
- 边界起点 `00:00` 包含，`now` 包含，未来记录排除。

## 7. 加权整体阅读速度

```text
overallPagesPerHour
= totalPagesRead * 3_600_000.0 / totalDurationMillis
```

禁止：

```text
average(session.pagesRead / session.duration)
```

示例：10 分钟读 10 页、50 分钟读 20 页：

```text
正确 = 30 页 / 1 小时 = 30 页/小时
错误 = (60 + 24) / 2 = 42 页/小时
```

速度只在同一本 Learning Item 内计算，不产生跨书比较或全局能力指标。总页数为 0 时 `overallPagesPerHour = 0.0`，UI 显示“最近暂无页码推进”，不显示预测。

## 8. CompletionPredictionService API

```kotlin
enum class PredictionConfidence { HIGH, MEDIUM, LOW }

enum class PredictionUnavailableReason {
    ITEM_NOT_IN_PROGRESS,
    INVALID_TOTAL_PAGES,
    ALREADY_AT_LAST_PAGE,
    INSUFFICIENT_WINDOW,
    ZERO_READING_SPEED,
    INSUFFICIENT_SESSIONS,
    INSUFFICIENT_READING_DAYS,
    INSUFFICIENT_SPAN,
    NO_RECENT_READING,
    EXCESSIVE_VARIABILITY,
    LOW_CONFIDENCE,
    ARITHMETIC_OUT_OF_RANGE,
}

data class CompletionDateRange(
    val earliest: LocalDate,
    val latest: LocalDate,
)

data class CompletionPrediction(
    val remainingPages: Long,
    val overallPagesPerHour: Double?,
    val estimatedRemainingReadingTime: Duration?,
    val calendarPagesPerDay: Double?,
    val naturalCompletionRange: CompletionDateRange?,
    val confidence: PredictionConfidence?,
    val unavailableReasons: Set<PredictionUnavailableReason>,
    val sourceWindowDays: Int?,
    val sourceSessionCount: Int,
)

class CompletionPredictionService {
    fun predict(
        item: LearningItemEntity,
        window: SelectedAnalyticsWindow?,
        time: AnalyticsTimeContext,
    ): CompletionPrediction
}
```

原因集合只供 ViewModel 选择克制文案和测试断言，不在 UI 展示技术枚举。

## 9. 剩余页数与预计剩余阅读时间

```text
remainingPages = max(0L, totalPages.toLong() - currentPage.toLong())
estimatedRemainingHours = remainingPages / overallPagesPerHour
```

显示条件：

- `status == IN_PROGRESS`；
- `totalPages > 0`；
- `remainingPages > 0`；
- 已选出窗口；
- `overallPagesPerHour > 0` 且为有限数。

内部使用 Double，最终 `Duration` 向上取整到整分钟，避免展示秒级虚假精确度。文案：不足 1 小时为“约 X 分钟”，否则“约 H 小时 M 分钟”。必须使用“预计剩余阅读时间”。

所有累计页数与剩余页数使用 `Long`。若计算结果不是有限正数、向上取整后的分钟数超出 `Long`/`Duration` 可表达范围，或预计日期天数超过 `LocalDate.MAX` 与 today 的差值，则返回 `ARITHMETIC_OUT_OF_RANGE` 并隐藏对应预测，不抛异常、不截断成任意产品上限，也不修改业务数据。

## 10. Calendar Reading Pace

```text
calendarPagesPerDay = selectedWindow.totalPagesRead / selectedWindow.days
estimatedDaysRemaining = remainingPages / calendarPagesPerDay
centerDays = ceil(estimatedDaysRemaining)
```

未阅读自然日通过固定 denominator 自动计入。禁止使用 `readingDays` 做 denominator，也禁止用整体阅读速度直接推自然日期。

## 11. 波动判断规则与阈值提案

单次 Session 速度：

```text
sessionPagesPerHour = pagesRead * 3_600_000 / durationMillis
```

普通变异系数：

```text
CV = populationStandardDeviation(sessionSpeeds) / mean(sessionSpeeds)
```

为满足“单次异常不能直接判定趋势”，不直接使用全样本 CV，而使用一个简单的 leave-one-out 稳健值：

```text
robustCV = 删除每一条样本后分别计算 CV，再取其中最小值
```

样本至少 5 条（自然完成日期本身的最低门槛）；0 页 Session 的速度为 0 并保留。若子样本均值为 0，则该子样本 CV 视为正无穷；整体 calendar pace 为 0 时会更早返回无预测。

**建议冻结阈值：`robustCV > 0.75` 判定为波动过大。**

解释：即使移除最偏离的一次阅读，剩余样本的标准差仍超过均值的 75%，说明不稳定不是由单个偶发值造成，完成日期已缺乏足够解释力。

固定案例：

| Session 速度（页/小时） | 结果 |
|---|---|
| `18, 20, 22, 19, 21` | 稳定，允许继续判断 |
| `20, 20, 20, 20, 100` | 单次异常，移除后 CV 为 0，不判波动过大 |
| `0, 20, 20, 20, 20` | 单次零推进，不单独判波动过大 |
| `1, 1, 1, 50, 50, 50` | 移除任一条后仍剧烈分散，判波动过大 |

该规则只决定是否显示自然完成日期，不产生“阅读速度异常”提醒，不写入数据库。

## 12. 自然完成日期资格、Confidence 与范围

### 12.1 硬性资格

只有同时满足以下条件才继续计算：

- Learning Item 为 `IN_PROGRESS`；
- `remainingPages > 0`；
- selected window 非空；
- 至少 5 个合格 Session；
- 至少 3 个不同阅读日；
- `inclusiveDataSpanDays >= 7`；
- 最近 14 个自然日内至少一个合格 Session；
- `calendarPagesPerDay > 0`；
- `robustCV <= 0.75`。

### 12.2 可信度评分

评分只用于高/中/低类别，不显示数字：

| 指标 | 0 分 | 1 分 | 2 分 | 3 分 |
|---|---:|---:|---:|---:|
| Session 数 | `<5` | `5–7` | `8–11` | `>=12` |
| 阅读日数 | `<3` | `3–4` | `5–7` | `>=8` |
| 数据跨度 | `<7` | `7–13` | `14–20` | `>=21` |
| robustCV | `>0.75` | `(0.50,0.75]` | `(0.25,0.50]` | `<=0.25` |
| 新鲜度 | `8–14 天`为 0 | `4–7 天`为 1 | `0–3 天`为 2 | 不适用 |

总分：

```text
HIGH   = 11–14
MEDIUM = 7–10
LOW    = 0–6
```

硬性资格失败时不计算日期；资格通过但 `LOW` 时同样隐藏日期，只保留预计剩余阅读时间，并显示轻提示“近期节奏仍在积累，暂不估算完成日期”。这是 PRODUCT_SPEC 中“可信度太低不显示完成日期”的落实。

### 12.3 确定性日期范围

只有 HIGH / MEDIUM 输出范围：

```text
centerDate = today + ceil(estimatedDaysRemaining)
HIGH marginDays = max(1, ceil(centerDays * 0.10))
MEDIUM marginDays = max(2, ceil(centerDays * 0.20))
earliest = today + max(1, centerDays - marginDays)
latest = today + centerDays + marginDays
```

可信度已经包含波动、新鲜度、Session 数、阅读日与跨度，因此范围不再叠加第二套概率模型。不得显示单日承诺、百分比或 Deadline。

## 13. PAUSED / COMPLETED / remaining = 0

| 状态 | 历史与事实统计 | 速度 | 剩余时间 | 自然完成日期 |
|---|---|---|---|---|
| `IN_PROGRESS` | 显示 | 数据足够时显示 | 满足条件时显示 | HIGH/MEDIUM 且硬门槛通过时显示 |
| `PAUSED` | 保留显示 | 可显示已有近期事实 | 隐藏 | 隐藏 |
| `COMPLETED` | 保留显示 | 可显示已有近期事实 | 隐藏 | 隐藏 |

- `currentPage == totalPages` 或 `currentPage > totalPages`：`remainingPages = 0`，显示“已到最后一页”，不显示未来预测，不自动完成 Learning Item。
- `totalPages <= 0`：进度与预测不可用，显示克制错误状态；Analytics 不修改原值。
- Progress 百分比使用安全 Double 计算并限制到 `0..100`，不得沿用可能溢出或除零的 UI 整数表达式。

## 14. Learning Item 详情 UI

`LearningItemDetailViewModel` 新的组合状态：

```kotlin
data class LearningItemDetailUiState(
    val item: LearningItemEntity? = null,
    val lastSummary: String? = null,
    val analytics: LearningItemAnalyticsUi? = null,
    val history: List<SessionHistoryUi> = emptyList(),
    val isAnalyticsLoading: Boolean = true,
    val analyticsError: String? = null,
)
```

页面保持一个纵向详情，不增加 Dashboard 或图表。顺序：

1. 名称、状态与现有开始/生命周期操作；
2. 阅读进度：`156 / 320 页 · 48%`；
3. 最近阅读：最近一条合格 Session，例如“昨天 · 42 分钟 · 18 页”；
4. 最近阅读速度：“最近约 22 页/小时”，副文案“根据最近 14 天 6 次有效阅读”；
5. 预计剩余阅读时间；
6. 自然完成日期范围与可信度，或一行数据不足说明；
7. 阅读历史。

已有 Start/暂停/完成/主线/First Action/Note 操作不得被预测信息抢占主 CTA 层级。分析错误只让分析区显示“暂时无法计算，阅读记录仍然安全”，不阻塞内容管理和开始阅读。

## 15. Session 阅读历史 UI

历史按 `endedAt DESC, id DESC`，每条显示：

- 本地日期；
- Session 时长；
- `startPage → endPage`；
- `pagesRead`；
- Note 数量。

状态标签：

- NORMAL：不额外强调；
- ABNORMAL：“异常结束 · 不参与统计”；
- EARLY/AUTO/START_INCOMPLETE：使用对应弱标签并注明“不参与统计”。

异常页码显示原始范围，同时派生 `pagesRead = 0`，不得把原始 `endPage` 改成 `startPage`。首版直接展示已结束历史，不加 Paging；若历史很长，用 `LazyColumn` 嵌入详情需避免与外层 `verticalScroll` 冲突，因此计划采用单一 `LazyColumn` 承载整个详情与历史 items。

## 16. “我的”7 天摘要 UI

新增 `ProfileViewModel`，页面只显示一组克制事实：

- 有效阅读 Session 数；
- 总阅读时长；
- 总推进页数；
- 新增 Note 数量。

比较使用同一设备时区的相邻自然日区间：

```text
当前：今天及前 6 天
前期：当前窗口之前的 7 个完整自然日
```

Service 同时计算四项 delta；首版 UI 只用一行弱文案展示阅读时长差异，例如“阅读时间比前 7 天多 32 分钟 / 少 18 分钟 / 与前 7 天相同”。不解释效率、自律或专注力，不增加图表、连续天数、建议或评分。

空状态：“最近 7 天还没有正常结束的阅读记录。”即使 Session 指标为空，新增 Note 数仍可如实展示。

## 17. ViewModel 与刷新策略

新增：

```kotlin
class LearningItemAnalyticsViewModel // 不单独创建；逻辑合并进现有 LearningItemDetailViewModel
class ProfileViewModel
```

`LearningItemDetailViewModel` 注入：

- `ReadingAnalyticsRepository`；
- `ReadingAnalyticsService`；
- `CompletionPredictionService`；
- `AnalyticsTimeProvider`。

`ProfileViewModel` 注入同一 Repository、AnalyticsService、TimeProvider，不依赖 PredictionService。

ViewModel 在初始化和页面 `ON_RESUME` 调用 `refreshTimeContext()`，以新 snapshot 重建查询边界；Room Flow 负责数据库变化后的刷新。无需每分钟 ticker，日期文案在页面恢复时更新即可。

## 18. Navigation 变化

无新增 Route。

- Learning Item 阅读分析与历史直接进入现有 `LearningItemDetailRoute`；
- “我的”摘要直接进入既有 `TopLevelDestination.Profile`；
- Search 的最小 Session Summary 页面保持不变；
- 不建设全局 Session History 页面或详情路由。

`MirraApp.kt` 只修改 ViewModel 装配参数，不改变 back stack 行为。

## 19. 错误、空状态与异常数据防御

- 无 Session / 不足窗口：隐藏速度与预测，显示“再完成几次阅读后，这里会出现近期节奏”。
- 速度为 0：显示“最近暂无页码推进”，隐藏两个预测。
- 日期资格不足：仍可显示剩余阅读时间；自然日期区域只给最主要的一条原因。
- LOW confidence：显示低可信度说明，但不显示日期范围。
- PAUSED / COMPLETED：保留历史、近期事实，隐藏未来预测。
- `endPage < startPage`：pagesRead 为 0，原始历史范围仍展示。
- `duration <= 0`、未来 endedAt、缺页、非 NORMAL：排除统计，不修改记录。
- `currentPage > totalPages`：remaining 取 0，百分比 clamp 到 100。
- DAO/Room 读取失败：页面其他功能仍可用；提供轻量重试，不暴露 SQL。
- 所有 Double 结果必须检查 `isFinite()`，除零返回不可用结果。

## 20. 性能方案

- 单书预测只读取该书最近 30 个自然日的已结束 Session；历史使用一个 Session + Note COUNT JOIN，避免 N+1。
- “我的”只读取最近 14 个自然日 Session，并由两个 SQL COUNT 得到 Note 数。
- 算法复杂度除 leave-one-out CV 外均为 O(n)；CV 为 O(n²)，但 n 仅是单书 30 天 Session。实现可用预计算 sum/sumSquares 优化为 O(n)，但只有代码仍清晰时采用；不得为此创建统计框架。
- 不读图片、Topic、FTS 或 Note 正文。
- 不增加 Paging、缓存表、后台任务或新索引。
- Instrumented 烟测写入 10,000 条历史 Session，验证查询完成、结果正确且没有 N+1；记录耗时但不设脆弱的毫秒 CI 门槛。

## 21. 自动化测试计划

### 21.1 JVM｜ReadingAnalyticsServiceTest

- NORMAL 完整 Session 被统计；
- ABNORMAL、Active、EARLY/AUTO/START_INCOMPLETE、duration <= 0、缺 endPage、未来 endedAt 排除；
- `endPage - startPage` 不加 1；反向页码为 0；
- 零推进 Session 计 Session、时长、阅读日和 denominator，但页数为 0；
- 10 分钟 10 页 + 50 分钟 20 页得到 30 页/小时，不得到 42；
- 7 天满足直接选择；不足扩 14，再不足扩 30；30 天不足返回 null；
- 窗口起始日 00:00 包含，早 1ms 排除；
- selected window denominator 固定 7/14/30；
- Asia/Shanghai 日期归属；America/New_York DST 春/秋变化仍按自然日计数；
- 年末跨年日期跨度正确；
- 当前/前 7 天 Session、时长、页数与 Note 数比较正确；
- ABNORMAL 不增加 Session/时长/页数，独立 Note 仍按 createdAt 计数。

### 21.2 JVM｜CompletionPredictionServiceTest

- 正常剩余页数、剩余时间和 calendar pace；
- `remainingPages = 0`、总速度 0、窗口不足；
- PAUSED / COMPLETED 隐藏未来预测；
- totalPages <= 0、currentPage > totalPages；
- 5 Session / 3 阅读日 / inclusive span 7 天通过；各项少 1 时失败；
- 最近 14 天无有效阅读失败；
- 单一极端值与单一零推进不触发 high variability；
- 多组持续分散样本 `robustCV > 0.75` 隐藏日期；
- Confidence 分别命中 HIGH / MEDIUM / LOW 的边界分数；
- LOW 隐藏日期，HIGH/MEDIUM 日期范围符合 10%/20% 规则；
- range 最早日期不早于明天；
- 非有限速度、超出 `Duration` 或 `LocalDate` 可表达范围时返回 `ARITHMETIC_OUT_OF_RANGE`，不抛异常；
- 所有结果有限且确定，相同输入重复计算完全一致。

### 21.3 Room / Instrumented｜ReadingAnalyticsRepositoryTest

- 单书历史倒序且包含 ABNORMAL 标记；
- JOIN Note count 正确，无 Note 为 0；
- 最近 30 天只返回对应 Learning Item 和时间范围；
- 最近 14 天全局数据与两个 Note COUNT 边界正确；
- Session 外 Note 被计入新增 Note；
- ABNORMAL 关联 Note 仍保留内容计数，但 Session 指标由 Service 排除；
- 10,000 Session 烟测不出现 N+1 或主线程数据库访问；
- 关闭重开文件数据库后结果一致。

### 21.4 Compose / Instrumented

- Learning Item 详情显示安全进度、最近阅读、速度与依据；
- 数据不足隐藏预测；
- HIGH/MEDIUM 显示日期范围和文字可信度；LOW 不显示范围；
- PAUSED / COMPLETED 不显示未来预测但保留历史；
- remaining = 0 显示“已到最后一页”且不会自动完成；
- 历史按倒序，ABNORMAL 明确标识“不参与统计”；
- “我的”显示 7 天四项摘要和前 7 天事实比较；
- 空状态与 analytics error 不阻塞页面既有操作；
- Start 六级状态、`currentPage` 不加 1、Intent/Session CTA 无回归。

### 21.5 全量回归

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

API 37 模拟器还需验证：

- APK 覆盖安装后 v3 旧数据直接读取，不触发 Migration；
- 完全离线打开 Learning Item 与“我的”统计；
- 新完成 Session 后分析自动刷新；
- 创建 Active Session 后强停、冷启动仍标记 ABNORMAL，且不进入统计；
- Phase 1 完整学习闭环；
- 2A Note、2B 图片补偿、2C Topic/FTS 搜索回归；
- 网络状态测试后恢复。

## 22. 预计新增 / 修改文件

### 新增

- `app/src/main/java/com/guanyi/mirra/data/local/model/ReadingSessionProjection.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/ReadingAnalyticsRepository.kt`
- `app/src/main/java/com/guanyi/mirra/domain/AnalyticsTimeProvider.kt`
- `app/src/main/java/com/guanyi/mirra/domain/ReadingAnalyticsModels.kt`
- `app/src/main/java/com/guanyi/mirra/domain/ReadingAnalyticsService.kt`
- `app/src/main/java/com/guanyi/mirra/domain/CompletionPredictionService.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemReadingSection.kt`
- `app/src/main/java/com/guanyi/mirra/feature/profile/ProfileViewModel.kt`
- `app/src/test/java/com/guanyi/mirra/domain/ReadingAnalyticsServiceTest.kt`
- `app/src/test/java/com/guanyi/mirra/domain/CompletionPredictionServiceTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/ReadingAnalyticsRepositoryTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/ModuleTwoDFlowTest.kt`
- `docs/checkpoints/2026-09-13-module-2d.md`（仅在实现与验证完成后创建）

### 修改

- `gradle/libs.versions.toml`：只增加 `desugar_jdk_libs 2.1.5`；
- `app/build.gradle.kts`：只启用 core library desugaring；
- `app/src/main/java/com/guanyi/mirra/data/local/dao/SessionDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/NoteDao.kt`
- `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`
- `app/src/main/java/com/guanyi/mirra/MirraApp.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemScreens.kt`
- `app/src/main/java/com/guanyi/mirra/feature/profile/ProfileScreen.kt`
- `app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`
- 受 `AppContainer` 接口新增依赖影响的测试装配；
- `docs/CURRENT_STATE.md`（仅在实现验证完成后更新）。

### 明确不修改

- `MirraDatabase.kt` 的 entities 与 `version = 3`；
- `Migrations.kt`；
- `app/schemas/.../1.json`、`2.json`、`3.json`；
- 所有 Entity；
- Session 完成、异常恢复、页码推进和 SearchFts 写路径；
- Navigation routes；
- 图片目录与文件补偿；
- PRODUCT_SPEC / DECISIONS，除非用户先验收并要求把本计划数学口径正式冻结。

## 23. Schema v3 不变验证

实施前后必须执行：

```powershell
git diff -- app/schemas/com.guanyi.mirra.data.local.MirraDatabase/1.json
git diff -- app/schemas/com.guanyi.mirra.data.local.MirraDatabase/2.json
git diff -- app/schemas/com.guanyi.mirra.data.local.MirraDatabase/3.json
rg -n "version = 3" app/src/main/java/com/guanyi/mirra/data/local/MirraDatabase.kt
rg -n "Migration\(" app/src/main/java/com/guanyi/mirra/data/local/Migrations.kt
```

预期：三个 schema diff 均为空；数据库仍为 v3；Migration 只有既有 1→2、2→3。DAO projection、查询与 core library desugaring 不改变 Room Schema。

## 24. 过度设计检查

- 两个 Service 分开是因为“描述历史事实”和“生成未来预测”有不同隐藏规则；二者仍是小型纯 Kotlin 类，不形成框架。
- 只增加一个只读 Repository，避免 UI/Service 直接依赖 DAO；不建立 Analytics 数据库或缓存。
- 自然日期使用固定窗口、稳健 CV、离散可信度和确定性范围，不使用概率模型、Monte Carlo 或机器学习。
- “我的”只展示四个 7 天事实和一条比较，不建设 Dashboard、图表或行为评价。
- 阅读历史只存在于 Learning Item 详情，不提前建设 Phase 4 全局 Session History。
- 没有新 Route、Gradle Module、DI Framework、后台任务或事件系统。
- core library desugaring 是 minSdk 23 使用标准 `java.time` 的必要兼容配置，避免自行实现 DST 日历算法；不借机升级依赖。
- 30 天与 14 天按需查询足以支持个人规模；无证据前不增加索引或 Paging。

结论：本计划新增边界均由当前 2D 需求直接驱动，Room Schema v3 足以正确实现，不需要 Migration。

## 25. Module 2D 最终验收标准

1. 只有 `NORMAL + endedAt/endPage 完整 + endedAt > startedAt` 的 Session 进入统计。
2. Active、ABNORMAL、EARLY、AUTO、START_INCOMPLETE 和异常时间记录完全排除统计预测，但已结束历史事实保留。
3. `pagesRead = max(0, endPage - startPage)`，绝不 `+1`，不修改原始数据。
4. 零推进正常 Session 计入 Session、阅读日、时长和速度 denominator，不增加页数。
5. 整体速度使用总页数/总时长，不平均每次 Session 速度，且只在同一本书内计算。
6. Window 严格按 7→14→30 天选择；3 Session、30 分钟门槛和本地自然日边界正确。
7. 30 天仍不足时不显示速度预测；速度为 0 时不显示剩余时间或自然日期。
8. 剩余页数、预计剩余阅读时间和向上取整到分钟的文案正确。
9. calendar pace 使用固定窗口自然日数，包含未阅读日。
10. 自然日期满足 5 Session、3 阅读日、跨度 7 天、14 天新鲜度、正 pace 与波动门槛。
11. `robustCV > 0.75` 隐藏日期，单次极端或单次零推进不会直接判定波动过大。
12. Confidence 按确定性评分输出高/中/低；LOW 不显示日期，不显示百分比。
13. HIGH/MEDIUM 分别使用 10%/20% 日期范围，最早不早于明天，不显示单日承诺或 Deadline。
14. PAUSED/COMPLETED 保留历史和已有速度事实，但不显示未来预测。
15. currentPage 达到/超过 totalPages 时显示“已到最后一页”，不自动完成。
16. Learning Item 详情显示进度、最近阅读、历史、速度依据、剩余时间和可信日期范围。
17. ABNORMAL 历史明确标识“不参与统计”。
18. “我的”显示最近 7 天 Session、时长、页数、Note 与前 7 天事实比较，不输出行为判断。
19. 时区、跨年和 DST 测试通过；Service 不直接读取系统当前时间。
20. 查询没有明显 N+1；10,000 Session 烟测完成且 UI 不在主线程计算/查询。
21. Room 始终为 Schema v3，三个历史 JSON 无变化，不存在新 Migration 或 destructive fallback。
22. 全部 JVM、Room、Instrumented、Compose、lintDebug、assembleDebug 通过。
23. API 37 APK 覆盖安装、完全离线、强停/冷启动和 Phase 1/2A/2B/Start/2C 回归通过。
24. 只包含 Module 2D 相关改动，无敏感信息、调试残留或未来模块空实现。
25. 完成后更新 CURRENT_STATE 与 Module 2D checkpoint，独立 Conventional Commit 并在用户授权范围内 Push 功能分支。
26. 极端数值不能导致溢出或崩溃；超出 `Duration`/`LocalDate` 表达范围时只隐藏对应预测并返回明确原因。
26. 完成报告后停止；不得自动进入 Phase 3。

## 26. 测试驱动实施顺序

### Task 1：时间边界、统计与预测纯函数

**Files:**

- Create: `domain/AnalyticsTimeProvider.kt`
- Create: `domain/ReadingAnalyticsModels.kt`
- Create: `domain/ReadingAnalyticsService.kt`
- Create: `domain/CompletionPredictionService.kt`
- Test: 两个对应 JVM test
- Modify: `gradle/libs.versions.toml`、`app/build.gradle.kts`

**Produces:** 第 4–13 节所有纯函数、数据类型和冻结公式。

- [ ] 写资格、页数、加权速度、窗口、DST、零推进、CV、confidence 和 range 的失败测试。
- [ ] 运行 `testDebugUnitTest`，确认新增测试因实现缺失而失败。
- [ ] 只加入 core library desugaring 2.1.5，并实现最小纯 Kotlin 代码。
- [ ] 运行 JVM tests 与 lint，确认 API 23 无 `NewApi` 问题。
- [ ] 检查没有 Analytics Framework、缓存或系统时间散落调用。

### Task 2：只读 Room projection 与 Repository

**Files:**

- Create: `ReadingSessionProjection.kt`
- Create: `ReadingAnalyticsRepository.kt`
- Create/Test: `ReadingAnalyticsRepositoryTest.kt`
- Modify: `SessionDao.kt`、`NoteDao.kt`、AppContainer/TestAppContainer

**Consumes:** Task 1 的时间范围；**Produces:** 第 3 节 Repository API。

- [ ] 写历史、时间边界、Note count、ABNORMAL 和 10,000 行烟测失败测试。
- [ ] 运行目标 Instrumented test，确认查询/API 尚不存在导致失败。
- [ ] 实现两个 JOIN、Note COUNT 和最小 Repository combine。
- [ ] 运行 Room tests，确认排序、边界与无 N+1。
- [ ] 比较三个 schema JSON 和数据库版本，确认完全不变。

### Task 3：Learning Item 阅读详情

**Files:**

- Create: `LearningItemReadingSection.kt`
- Modify: `LearningItemScreens.kt`、`MirraApp.kt`
- Test: `ModuleTwoDFlowTest.kt`

**Consumes:** Repository、两个 Service；**Produces:** 第 14–15 节详情与历史 UI。

- [ ] 写正常、数据不足、LOW、PAUSED、COMPLETED、最后一页和 ABNORMAL 历史 Compose 失败测试。
- [ ] 运行目标 Compose tests，确认 2D 信息尚未展示。
- [ ] 扩展现有 ViewModel，使用单一 LazyColumn 实现最小 UI。
- [ ] 运行 2D、2A、Start、Phase 1 Compose 回归。
- [ ] 核对开始阅读仍走 Intent，currentPage 未加 1。

### Task 4：“我的”7 天基础摘要

**Files:**

- Create: `ProfileViewModel.kt`
- Modify: `ProfileScreen.kt`、`MirraApp.kt`
- Test: `ModuleTwoDFlowTest.kt`

**Consumes:** `SevenDayComparison`；**Produces:** 第 16–17 节摘要 UI。

- [ ] 写四项摘要、前 7 天比较、空状态和 ABNORMAL 排除失败测试。
- [ ] 实现 Profile ViewModel 与克制事实卡片，不加图表或评价。
- [ ] 运行 Profile/Navigation/Start Compose tests。
- [ ] 验证 Session 外 Note 与异常 Session 所属 Note 的独立内容计数语义。

### Task 5：全量验收与 Phase 2 收尾

**Files:**

- Modify: `docs/CURRENT_STATE.md`
- Create: `docs/checkpoints/2026-09-13-module-2d.md`

**Produces:** 可复核的 Module 2D 与 Phase 2 完成证据。

- [ ] 运行第 21.5 节全部命令并记录实际数量、耗时与失败修复。
- [ ] 在 API 37 执行覆盖安装、离线、完整学习闭环、强停/冷启动及 2A/2B/2C 回归。
- [ ] 确认三个 schema JSON、数据库版本、Migration 列表均无变化。
- [ ] 检查 Git diff、临时文件、调试输出、敏感信息和 Scope Guard。
- [ ] 按真实证据更新 CURRENT_STATE/checkpoint；未运行项明确写 Not Run。
- [ ] 独立提交 `feat(analytics): add reading pace predictions` 并在明确授权下 Push `codex/phase-2d-reading-analytics`。
- [ ] 停止，等待正式验收，不进入 Phase 3。

## 27. 规划自检

- Spec coverage：用户要求的 24 项规划输出分别由第 1–25 节覆盖，实施顺序见第 26 节。
- 数学口径：有效样本、零推进、加权速度、窗口、calendar pace、CV、confidence、range 均有唯一公式与案例。
- 时间口径：本地自然日、边界、跨年、DST、时区刷新和测试注入均已定义。
- Schema coverage：只改 DAO 查询，不改 Entity/Database/Migration/schema JSON。
- Scope coverage：未规划专注分段、行为诊断、异常提醒、AI、云或 Phase 3/4 功能。
- Type consistency：Repository、Service、ViewModel 所消费/产出的类型名称在前文唯一。
- Placeholder scan：没有待定占位；实施日期与分支、依赖版本、阈值和命令均已明确。
- Overdesign：没有持久化聚合、框架、后台任务、Paging、新 Route 或通用规则系统。

本计划完成后必须等待用户验收和单独实施授权；当前回合不得修改业务代码、Room Schema 或开始 Module 2D 实现。
