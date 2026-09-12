# 观已 Mirra｜Start Experience Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Start 重构为只负责“现在如何开始学习”的六级状态行动入口，同时保持 Phase 1 页码、Intent/Session 状态机和冷启动异常恢复语义不变。

**Architecture:** 继续使用现有 `LearningItemRepository` 与 `StudyWorkflowRepository`。`StartViewModel` 组合 Room Flow，并通过纯函数解析为六种互斥的 sealed content state；新增的最近正常阅读只是 DAO 投影，不建立 Analytics 层。首本书可选主线在现有 Room 数据库事务内完成，First Action 复用现有字段并由应用层提供动态 fallback。

**Tech Stack:** Kotlin、Jetpack Compose、Navigation 3、Coroutines / Flow、Room 2.8.5、单 Android `app` Module、手工 `AppContainer`。

**Spec:** `docs/PRODUCT_SPEC.md` 的 Learning Item、First Action、导航/Start 与 Phase 1 恢复规则；`docs/DECISIONS.md` 中 2026-09-11 的三项 Start Experience Correction 决策。

## Global Constraints

- 本计划是独立 Start Experience Correction，不实现 Module 2C 或 Module 2D。
- `currentPage` 始终表示“当前可继续阅读的位置”，任何展示与启动不得自动加 1。
- 同进程合法 Active Session 可以继续；新进程遗留 Active Session 仍由 bootstrap recovery 标记为 `ABNORMAL`。
- Start 只管行动；Knowledge 管内容；Mine 管个人记录。
- Start 始终只有一个最强主 CTA，不提供全局“+”、内容管理或数据 Dashboard。
- 创建首本书时“设为主线”默认开启但由用户明确确认；创建与主线写入必须同事务。
- 不修改 Room Schema v2，不新增 Entity/Column/Index，不创建 Migration。
- 不新增 Gradle Module、DI Framework、Analytics Service、StartEngine 空抽象或第三方依赖。
- Phase 1、2A、2B 的数据、状态机、Note 自动保存与图片文件流程必须回归通过。

---

## 0. Task Contract

### Scope

- `StartViewModel` 六级状态与优先级解析；
- Start 页面信息层级、文案、唯一主 CTA 与弱操作；
- 同进程 Active Session / Active Intent 恢复入口；
- 主线与非主线 IN_PROGRESS 的开始路径；
- 空库首本书引导与可选主线原子创建；
- 最近一次正常 Session 的轻量投影；
- First Action 创建、展示、fallback 与内容详情编辑；
- Navigation 与自动化/模拟器回归。

### Out of Scope

任何 Room Schema 变化、Session recovery 改动、Topic/Search、Analytics/预测、SessionSegment、DND/Usage Access/Overlay、AI、Start 全局创建菜单、Learning Item 完整管理列表或视觉设计系统重建。

### Dependencies

- `LearningItemEntity.currentPage/mainlineSlot/firstAction/status`；
- `StudySessionEntity.activeSlot/endType/startedAt/endedAt/currentPage`；
- `StudyIntentEntity.activeSlot`；
- 现有 `createIntent()`、`abandonIntent()`、`SessionManager` 与 bootstrap recovery；
- `CreateLearningItemScreen`、`PreparationScreen`、`SessionScreen` 和 Navigation 3；
- Room Schema v2 与现有测试容器。

### Acceptance and Verification

完整条件见第 18 节。实施阶段按“纯状态模型 → DAO/Repository → First Action 与创建流程 → Compose/Navigation → 全量回归”推进，每段先写失败测试再实现。

## 1. 当前事实分析

规划基线：

- 分支 `codex/phase-2b-image-notes`，HEAD/远程为 `5948904`；
- 当前工作区已有未提交的 Module 2C 规划文档变更，实施 Start 时必须保留并与 Start commit 分离；
- `MirraDatabase.version = 2`，已注册且仅注册 `MIGRATION_1_2`；
- 本规划回合不修改任何 Kotlin、Schema 或 Migration。

### StartScreen / StartViewModel

当前 `StartUiState` 只有 `mainline`、`activeIntent`、`activeSession` 三项，页面只有四个分支：继续当前阅读、继续启动、从主线开始、创建第一本书。

现有问题：

- 没有区分“无主线但有 IN_PROGRESS”“只有 PAUSED/COMPLETED”“完全空库”；
- 无主线时会错误地把所有情况都导向创建第一本书；
- Active Intent 只能继续，Start 没有直接取消弱操作；
- Mainline 只显示名称和页码，没有 First Action、总页数、进度或最近正常阅读；
- Active Session 没有 Learning Item 名称、当前页和已进行时间；
- `begin()` 固定从 mainline 创建 Intent，不能从用户明确选中的非主线内容开始。

### Repository / DAO

- `LearningItemRepository.observeAll()` 已提供所有状态数据，`observeMainline()` 提供主线；六级状态可复用 `observeAll()`，无需新表或数据库 View；
- `LearningItemRepository.create()` 当前不在完整事务中、总是 `mainlineSlot = null`，并把自动 First Action 固化为创建时页码；
- `StudyWorkflowRepository.createIntent()` 已在事务内两次确认 Learning Item 为 IN_PROGRESS，并保护唯一 Active Intent/Session；Start 必须复用，不能直接创建 Session；
- `StudyWorkflowRepository.abandonIntent()` 已实现幂等放弃，可直接供 Start 的弱操作使用；
- `SessionDao` 目前只有最新 Summary 查询，没有“最近正常 Session + Note 数量”投影；
- `recoverInterruptedSession()` 在 UI 创建前由 `container.startup.await()` 执行，因此新进程不会把遗留 Active Session 交给 Start；同进程导航返回时仍可观察合法 Active Session。

### First Action

- Schema v2 的 `firstAction` 为非空 TEXT；
- 当前创建 UI 没有输入项，Repository 总是写入“拿起《名称》，翻到第创建页码页。”；
- 当前只有 Preparation 直接读取字段，Start 尚未展示；
- 旧数据在阅读进度推进后可能仍包含创建时页码，不能把这段静态默认文案当作用户自定义内容。

## 2. 六级状态模型

Loading/Error 不计入六级产品状态；顶层状态把加载、提交和错误与六种互斥内容状态分开：

```kotlin
data class StartUiState(
    val content: StartContentState? = null,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface StartContentState {
    data class ActiveSession(
        val sessionId: String,
        val learningItemName: String,
        val currentPage: Int,
        val elapsedMinutes: Long,
    ) : StartContentState

    data class ActiveIntent(
        val intentId: String,
        val learningItemName: String,
        val firstAction: String,
    ) : StartContentState

    data class Mainline(
        val item: StartLearningItem,
        val recentReading: RecentReadingSnapshot?,
    ) : StartContentState

    data class ChooseInProgress(
        val items: List<StartLearningItem>,
        val selectedItemId: String?,
        val setSelectedAsMainline: Boolean,
        val recentReading: RecentReadingSnapshot?,
    ) : StartContentState

    data class NoInProgress(
        val totalItemCount: Int,
    ) : StartContentState

    data object EmptyLibrary : StartContentState
}

data class StartLearningItem(
    val id: String,
    val name: String,
    val currentPage: Int,
    val totalPages: Int,
    val progressPercent: Int,
    val firstAction: String,
)
```

`progressPercent` 只用于弱化展示，按 `currentPage * 100 / totalPages` 限制在 0..100；不形成新统计指标。

## 3. 状态优先级计算逻辑

在 `StartUiState.kt` 提供无副作用解析函数，输入全部来自已加载的实体快照：

```kotlin
internal fun resolveStartContent(
    items: List<LearningItemEntity>,
    activeIntent: StudyIntentEntity?,
    activeSession: StudySessionEntity?,
    selectedItemId: String?,
    setSelectedAsMainline: Boolean,
    recentReading: RecentReadingSnapshot?,
    nowMillis: Long,
): StartResolution
```

固定顺序：

1. `activeSession != null`：按 `learningItemId` 找到对应 Item，返回 `ActiveSession`；即使同时出现异常 Active Intent，也不显示创建/继续 Intent；
2. 无 Session 且 `activeIntent != null`：返回 `ActiveIntent`；
3. 找到 `mainlineSlot != null && status == IN_PROGRESS`：返回 `Mainline`；
4. 过滤全部 `status == IN_PROGRESS`，按 `updatedAt DESC, id ASC` 排序；非空则返回 `ChooseInProgress`；
5. 没有 IN_PROGRESS 但 `items.isNotEmpty()`：返回 `NoInProgress`；
6. `items.isEmpty()`：返回 `EmptyLibrary`。

安全规则：

- PAUSED/COMPLETED 即使因旧数据异常带有 `mainlineSlot`，也不能进入 Mainline 状态；不在 UI 中自动修复或选择其他主线；
- `selectedItemId` 不属于当前 IN_PROGRESS 集合时视为未选择；
- Active Session/Intent 找不到对应 Learning Item 属于数据异常，返回 `StartResolution.DataError`，禁止创建新流程；Intent 异常状态仍允许调用既有“取消本次启动”释放槽位；
- `elapsedMinutes = max(0, nowMillis - startedAt) / 60_000`；不把后台或冷启动时间推断为有效专注时间；
- 所有页码原样使用实体 `currentPage`，无任何 `+1`。

ViewModel 使用一个每 30 秒发出当前时钟的轻量 Flow，仅在 Start 被订阅时更新 Active Session 的分钟数；不新增计时 Service。

## 4. Active Session / Intent 数据来源

沿用：

```kotlin
workflow.observeActiveSession(): Flow<StudySessionEntity?>
workflow.observeActiveIntent(): Flow<StudyIntentEntity?>
learningItems.observeAll(): Flow<List<LearningItemEntity>>
```

通过 `items.associateBy(id)` 补齐 Active Session/Intent 的 Learning Item 名称和 First Action，避免误用 mainline：非主线内容也可以拥有合法 Intent/Session。

行为：

- Active Session 主 CTA只调用 `onOpenSession(sessionId)`；
- Active Intent 主 CTA只调用 `onOpenIntent(intentId)`；
- Active Intent 弱操作调用 `workflow.abandonIntent(intentId)`，成功后等待 Room Flow 自然进入下一优先级状态；
- 两个状态均不调用 `createIntent()`；
- 提交中禁用相关按钮，避免重复点击；Repository 唯一槽位仍是最终保护。

## 5. Mainline 与 IN_PROGRESS 查询

首版不新增专用 `StartRepository` 或数据库 View：

- `observeAll()` 已由 `mainlineSlot DESC, updatedAt DESC` 排序，数据规模是个人书库；
- ViewModel 在内存中筛选合法 mainline 和 IN_PROGRESS 列表；
- 状态变化由同一个 Learning Item Flow 推动，避免同时维护 `observeMainline()` 与 `observeAll()` 造成短暂不一致；
- Active Intent/Session 仍来自各自独立 Flow，并由优先级解析器保证最安全状态优先。

State 4 操作：

```kotlin
fun selectLearningItem(itemId: String)
fun setSelectedAsMainline(enabled: Boolean)
fun beginSelected(onIntentReady: (String) -> Unit)
```

- 初始没有选中项，主 CTA“开始学习”显示但禁用；
- 点选某本书只更新 ViewModel 内存状态，不写数据库；
- “设为主线”默认关闭；
- 开始时调用扩展后的 `createIntent(itemId, setAsMainline)`；若选项开启，主线写入和 Intent 创建在同一事务完成；关闭时不改变任何 mainline；
- 不把临时选择持久化到 DataStore，进程重启后重新选择是可接受的。

## 6. 最近一次正常 Session 投影

新增非 Entity 查询模型：

```kotlin
data class RecentReadingSnapshot(
    val sessionId: String,
    val learningItemId: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
    val noteCount: Int,
)
```

`SessionDao` 查询：

```sql
SELECT s.id AS sessionId,
       s.learningItemId AS learningItemId,
       s.startedAt AS startedAt,
       s.endedAt AS endedAt,
       MAX(0, s.endedAt - s.startedAt) AS durationMillis,
       COUNT(n.id) AS noteCount
FROM study_sessions AS s
LEFT JOIN notes AS n ON n.sessionId = s.id
WHERE s.learningItemId = :learningItemId
  AND s.endType = 'NORMAL'
  AND s.endedAt IS NOT NULL
GROUP BY s.id
ORDER BY s.endedAt DESC, s.id DESC
LIMIT 1
```

Repository API：

```kotlin
fun observeLatestNormalReading(learningItemId: String): Flow<RecentReadingSnapshot?>
```

`StartViewModel` 从基础六级状态推导当前需要上下文的 itemId，仅对 Mainline 或 State 4 已选内容使用 `flatMapLatest` 订阅。切换选择会取消旧查询。

展示固定为一行：

- 有记录：`上次阅读 42 分钟 · 3 条笔记`；
- 少于一分钟：`上次阅读不足 1 分钟 · 3 条笔记`；
- 无记录：不显示虚假占位统计。

不读取 ABNORMAL、START_INCOMPLETE 或未结束 Session；不计算趋势、平均值、速度、连续天数或预测，不增加 Analytics 层。

## 7. 首本书创建 + 可选 mainline 事务

扩展接口，保留旧调用的默认行为：

```kotlin
suspend fun create(
    name: String,
    totalPages: Int,
    currentPage: Int = 1,
    firstAction: String? = null,
    setAsMainline: Boolean = false,
): LearningItemEntity
```

实现全部放入一个 `database.withTransaction`：

1. trim/校验名称、总页数、当前页；
2. `firstAction?.trim().orEmpty()`，空字符串表示使用动态 fallback；
3. 若 `setAsMainline`，先 `clearMainline(now)`；
4. 插入新 IN_PROGRESS Item，`mainlineSlot = 1` 或 `null`；
5. 读取并返回最终实体；
6. 任一步失败全部回滚，旧主线也恢复。

创建不是因为数据库为空而自动设主线；是否为空只决定 Start 显示哪个入口。最终 `setAsMainline` 只能来自用户在创建页确认的复选项。

为 State 4 的可选主线开始扩展现有 API：

```kotlin
suspend fun createIntent(
    learningItemId: String,
    setAsMainline: Boolean = false,
): StudyIntentEntity
```

在现有事务中先处理过期 Intent/检查合法 Active 状态，再复核目标 Item 为 IN_PROGRESS；只有即将创建新 Intent 时才根据显式参数清理并设置 mainline，随后插入 Intent。若已有未过期 Active Intent，直接返回既有 Intent且不得改 mainline。事务失败时主线和 Intent 一起回滚。

普通 Mainline、Learning Item 详情等旧入口使用默认 `false`，不产生额外主线写入。

## 8. First Action 复用方案

不新增字段。增加纯 Kotlin `FirstActionResolver`：

```kotlin
object FirstActionResolver {
    fun resolve(item: LearningItemEntity): String
    fun defaultFor(name: String, currentPage: Int): String =
        "拿起《${name.trim()}》，翻到第 $currentPage 页。"
}
```

解析规则：

1. `firstAction.trim()` 为空：使用 `defaultFor(name, currentPage)`；
2. 匹配旧版精确自动格式 `拿起《当前名称》，翻到第 <数字> 页。`：视为旧自动值，使用当前 `currentPage` 重新生成；
3. 其他非空文本：视为用户自定义，原样 trim 后展示；
4. 默认格式始终使用 `currentPage`，禁止加 1。

旧版没有 First Action 编辑入口，因此现有精确自动格式可以安全视为系统生成值。以后若用户输入同样的标准格式，它仍按“随进度变化的翻页动作”解释；固定页码需求应使用不同的自定义表达。

创建页：

- 增加“第一次动作（可选）”输入；
- 留空时展示动态建议 `拿起《名称》，翻到第 currentPage 页。`，数据库保存空字符串作为 fallback；
- 从空库 Start 进入时显示默认开启的“设为主线”复选项；用户点击创建即是明确确认；
- 从 Knowledge 进入时保持原流程，不默认设主线。

编辑：

```kotlin
suspend fun updateFirstAction(itemId: String, firstAction: String?): LearningItemEntity
```

- 位于 Learning Item 详情，不放在 Start；
- trim 后空字符串表示恢复动态默认；
- Repository 事务内确认 Item 存在，并复用 `ensureNoActiveWorkflow(itemId)`；存在 Active Intent/Session 时拒绝，避免准备流程中的动作被改变；
- 更新 `updatedAt`，不修改状态、主线或页码；
- Start 与 `PreparationScreen` 都必须调用同一 resolver，避免文案漂移。

## 9. Compose 页面结构

视觉方向：克制、安静、大面积留白、黑白/中性色为主、少卡片、文字层级优先。沿用 Material 3 组件，不新建视觉框架。

统一顺序：

```text
观已 Mirra（弱品牌）
状态标题
Learning Item 名称
当前进度 / 最近正常阅读（弱）
第一步 + First Action
大面积留白
唯一主 CTA
必要的弱操作
```

各状态：

- Active Session：`正在学习`、书名、`当前第 X 页`、`已进行 N 分钟`、主 CTA；
- Active Intent：`准备开始`、书名、First Action、主 CTA，下面是文字/Outlined 弱操作“取消本次启动”；
- Mainline：`今天继续`、书名、`X / Y 页 · Z%`、最近阅读一行、`第一步`、First Action、主 CTA；
- ChooseInProgress：`选择一本继续`，以简洁可点行而非重卡片列出进行中内容；选中后展示 First Action，弱复选项“设为主线”，底部主 CTA；
- NoInProgress：`暂无正在学习的内容`、说明文字、主 CTA“查看学习内容”；不列暂停/完成项；
- EmptyLibrary：`开始你的第一次学习`、两行说明、主 CTA“添加第一本书”。

百分比、最近阅读和列表行不得使用与主 CTA 相同的强调色/尺寸。Start 不显示 Note、Topic、图片、搜索、周报或图表。

## 10. Navigation 变化

保留现有入口：

- Active Session → `SessionRoute(sessionId)`；
- Active Intent / 新 Intent → `PreparationRoute(intentId)`；
- State 5 → 清理当前顶层 back stack 并选择 `TopLevelDestination.Knowledge`。

新增一个语义明确的 onboarding route，避免给现有 Knowledge 创建流程塞来源标志：

```kotlin
@Serializable data object CreateFirstLearningItemRoute : NavKey
```

两个 route 复用同一 `CreateLearningItemScreen`：

- `CreateLearningItemRoute`：现有 Knowledge 入口；默认不设主线，创建后仍进入 Learning Item 详情；
- `CreateFirstLearningItemRoute`：Start 空库入口；显示默认开启的主线选项，创建后清理创建页并返回 `TopLevelDestination.Start`。

若首本书取消主线勾选，返回后自然进入 State 4；不额外导航到详情，不自动改变选择。

同进程从 Session 按现有返回逻辑到 Start 时，仍合法的 Active Session 由 State 1 捕获；冷启动则必须等待 `container.startup.await()` 完成，ABNORMAL recovery 先于 Compose。

## 11. 文案与 CTA 状态表

| 优先级 | 状态标题 | 核心内容 | 主 CTA | 弱操作 | 目标 |
|---|---|---|---|---|---|
| 1 | 正在学习 | 书名、当前页、已进行时间 | 继续学习 | 无 | 现有 Session |
| 2 | 准备开始 | 书名、First Action | 继续准备 | 取消本次启动 | Preparation / abandon |
| 3 | 今天继续 | 主线、进度、最近阅读、First Action | 开始学习 | 无 | 新 Intent → Preparation |
| 4 | 选择一本继续 | IN_PROGRESS 轻量列表、选中项 First Action | 开始学习 | 设为主线 | 新 Intent → Preparation |
| 5 | 暂无正在学习的内容 | 恢复或添加说明 | 查看学习内容 | 无 | Knowledge |
| 6 | 开始你的第一次学习 | 首次使用说明 | 添加第一本书 | 无 | 首本书创建 |

主 CTA 文案不使用“我想开始”“创建第一本书”“继续当前阅读”“继续启动”等旧版本变体。全 App 测试同步使用本表文案。

## 12. 错误与空状态 UX

- 初次 Flow 尚未发出完整数据：显示低干扰加载，不先闪现“添加第一本书”；
- `createIntent()` 失败：保持当前 Item/选择和复选项，主 CTA恢复可用，显示 Repository 中文错误；
- State 4 未选 Item：主 CTA禁用，不自动选第一项；
- 选中的 Item 被并发暂停/完成：Flow 清除选择，页面重新列出合法项；若没有则进入 State 5；
- Active Intent 取消失败：保留 ActiveIntent 状态并允许重试；
- Active Intent 的 Item 异常缺失：显示数据错误，保留“取消本次启动”弱操作，禁止新建 Intent；
- Active Session 的 Item 异常缺失：显示安全错误和“继续学习”入口（Session 页面自行读取/显示），不创建新流程；
- 最近阅读查询失败：不阻塞开始主流程，只隐藏该辅助行并记录页面级非阻塞错误；
- 首本书创建失败：停留创建页，保留输入和主线勾选；事务保证不会出现半创建/半主线；
- First Action 空白不是错误，明确使用动态 fallback；自定义内容不做鼓励语或 AI 改写；
- State 5 只导航到 Knowledge，不在 Start复制暂停恢复 UI。

## 13. 冷启动 / 强停行为

### 同进程

用户从 Active Session 页面回到 Start，数据库 Active Slot 仍存在时：

```text
observeActiveSession() 有值
→ 六级解析优先 State 1
→ 显示继续学习
→ 点击打开同一个 sessionId
```

### 新进程 / 冷启动

```text
MirraApplication 创建 AppContainer
→ MainActivity 等待 container.startup
→ recoverInterruptedSession()
→ 遗留 Active Session 在事务内写 ABNORMAL、释放 activeSlot
→ Compose 创建 StartViewModel
→ observeActiveSession() 为 null
→ 按 Intent/Mainline/其他内容继续解析
```

Start Correction 不改变上述顺序，不增加“恢复为 Active”、时间阈值或后台 Service。ABNORMAL Session 不进入最近正常阅读投影，也不推进 Learning Item 正式进度。

Active Intent 在新进程中若未超过现有 30 分钟规则仍可继续；超时由现有 bootstrap 标记 TIMEOUT，再进入下一优先级状态。

## 14. 测试计划

### JVM

`StartStateResolverTest`：

- 同时有 Session/Intent/Mainline 时 Session 胜出；
- 无 Session 时 Intent 胜出；
- 仅合法 IN_PROGRESS mainline 进入 State 3；
- PAUSED/COMPLETED 带异常 mainlineSlot 仍不进入主任务；
- 无 mainline + 多个 IN_PROGRESS 进入 State 4 且不默认选择；
- 选择只改变内存 state，不改变实体 mainlineSlot；
- 无 IN_PROGRESS 但有其他 Item 进入 State 5；
- 空列表进入 State 6；
- active workflow 指向非主线 Item 时显示正确名称；
- elapsedMinutes 对负时间归零；
- 所有状态页码保持 X，不产生 X+1。

`FirstActionResolverTest`：

- 空字段使用当前页动态 fallback；
- 旧版自动格式中的旧页码替换为当前页；
- 自定义动作原样优先；
- 名称、页码与标点格式确定；
- 永不使用 `currentPage + 1`。

### Room / Repository Instrumented

`StartExperienceLearningItemRepositoryTest`：

- 创建 + mainline 开启成功；
- 关闭时只创建、不改变现有主线；
- insert 故障时 clearMainline 一并回滚；
- 创建无效输入时不改变主线；
- 并发/重复设置仍由唯一 mainlineSlot 保护；
- First Action 空白、自定义值与 updateAt；
- Active Intent/Session 时编辑 First Action 被拒绝。

`StartExperienceWorkflowRepositoryTest`：

- State 4 未勾主线时创建 Intent 不改 mainline；
- 勾选时 set mainline + create Intent 原子成功；
- 已有 Active Intent 时不得因为新请求改变 mainline；
- Intent insert 故障时 mainline 更新回滚；
- 非 IN_PROGRESS 目标继续被事务内拒绝。

`RecentReadingProjectionTest`：

- 取同一 Item 最新 NORMAL Session；
- 计算 duration 和独立 Note 数量；
- ABNORMAL、START_INCOMPLETE、Active 和其他 Item 排除；
- 相同 endedAt 以 id 稳定选择；
- 无正常记录返回 null；
- 数据库关闭重开后结果一致。

### Compose / Navigation

`StartExperienceFlowTest` 覆盖：

- Active Session 同进程返回显示“继续学习”并打开原 sessionId；
- Active Intent 显示“继续准备”，取消后进入下一一层状态且不重复 Intent；
- Mainline 显示 X/Y、First Action、最近正常阅读和“开始学习”；
- 多个 IN_PROGRESS 选择后开始，不勾选不改变主线；勾选后设置主线；
- State 5 打开 Knowledge，不显示内容管理列表；
- State 6 打开首本书创建，主线默认开启；创建后回 Start；
- 首本书关闭主线后回 State 4；
- Start 不存在全局“+”、Note、Topic、图片、搜索或 Dashboard 元素；
- 页面每个状态只有一个强调主按钮。

更新 `PhaseOneLearningLoopTest`：首次入口改为“添加第一本书”，默认主线确认后直接返回 Start，再点击“开始学习”；其余 Intent → Session → Note → Summary 保持原流程。

### 强停 / 冷启动与回归

- 创建 Active Session → 强停 App → 冷启动 → Session 为 ABNORMAL，Start 不显示“继续学习”；
- 同一进程从 Session 返回 Start → 显示“继续学习”；
- 未超时 Active Intent 冷启动 → 显示“继续准备”；
- 完整 Phase 1、Learning Item 生命周期、Note CRUD/自动保存、图片导入/Caption/删除回归；
- 关闭 Wi-Fi/移动数据后六级状态、创建和开始流程完整可用。

最终命令：

```powershell
.\gradlew testDebugUnitTest
.\gradlew connectedDebugAndroidTest
.\gradlew lintDebug
.\gradlew assembleDebug
```

## 15. 预计新增 / 修改文件

### 新增

- `app/src/main/java/com/guanyi/mirra/feature/start/StartUiState.kt`
- `app/src/main/java/com/guanyi/mirra/feature/start/StartViewModel.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/model/RecentReadingSnapshot.kt`
- `app/src/main/java/com/guanyi/mirra/domain/FirstActionResolver.kt`
- `app/src/test/java/com/guanyi/mirra/feature/start/StartStateResolverTest.kt`
- `app/src/test/java/com/guanyi/mirra/domain/FirstActionResolverTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/StartExperienceLearningItemRepositoryTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/StartExperienceWorkflowRepositoryTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/RecentReadingProjectionTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/StartExperienceFlowTest.kt`
- `docs/checkpoints/2026-09-11-start-experience-correction.md`（仅在实现与验证完成后创建）

### 修改

- `app/src/main/java/com/guanyi/mirra/feature/start/StartScreen.kt`（移出 ViewModel/state，保留 Compose）
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemScreens.kt`
- `app/src/main/java/com/guanyi/mirra/feature/session/PreparationScreen.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/LearningItemDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/SessionDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/LearningItemRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`
- `app/src/main/java/com/guanyi/mirra/navigation/Routes.kt`
- `app/src/main/java/com/guanyi/mirra/MirraApp.kt`
- `app/src/androidTest/java/com/guanyi/mirra/PhaseOneLearningLoopTest.kt`
- 受创建 API 默认参数、文案或导航影响的既有测试
- `docs/CURRENT_STATE.md` 与最终 checkpoint

### 明确不修改

- `MirraDatabase.kt` 的实体列表与 `version = 2`；
- `Migrations.kt` 和 `AppContainer` migration 注册；
- `app/schemas/.../1.json`、`2.json`；
- Intent/Session Entity、唯一槽位、恢复事务与图片文件系统；
- Module 2C 规划和任何 Topic/Search 文件；
- Gradle 依赖与 Module 结构。

## 16. Schema 不变验证

实现前记录：

```powershell
Get-FileHash app/schemas/com.guanyi.mirra.data.local.MirraDatabase/1.json
Get-FileHash app/schemas/com.guanyi.mirra.data.local.MirraDatabase/2.json
```

实现后必须证明：

- `MirraDatabase.version` 仍为 2；
- `@Database(entities=...)` 列表不变；
- `Migrations.kt` 无 diff，没有 `MIGRATION_2_3`；
- `1.json`、`2.json` SHA-256 与实施前一致；
- `git diff -- app/schemas app/src/main/java/com/guanyi/mirra/data/local/MirraDatabase.kt app/src/main/java/com/guanyi/mirra/data/local/Migrations.kt` 为空；
- 新增的 `RecentReadingSnapshot` 只是查询投影，没有 `@Entity`；
- APK 覆盖安装后既有 v2 数据可直接读取，无清库或重装要求。

如果 Room schema export 因无意变更产生 diff，实施必须停止并还原该变更，不能创建 Migration 规避本模块边界。

## 17. 过度设计检查

- 六种产品状态由一个 sealed interface 表达，不引入通用状态机库；
- 优先级是纯函数，不新增 StartEngine、Reducer 框架或事件总线；
- 最近阅读是一个 DAO projection，不建立 Analytics Service、缓存表或统计流水线；
- 进行中列表复用 `observeAll()`，个人规模无需新索引、Paging 或数据库 View；
- Session elapsed 只用订阅期间的轻量时钟 Flow，不新增后台计时 Service；
- First Action 复用字段和一个 resolver，不加“是否自动生成”列；
- 首本书使用现有 Room transaction 和唯一主线槽位，不改 Schema；
- 新 onboarding route 只解决不同返回目标，不复制创建页面；
- 不触碰 Topic/Search、预测、系统专注权限或图片模块。

结论：所有新增类型均直接服务于 Start 六级状态、最近阅读或 First Action 一致性，没有未来式空抽象。

## 18. 最终验收标准

1. Start 严格按 Active Session → Active Intent → Mainline IN_PROGRESS → 其他 IN_PROGRESS → 只有暂停/完成 → 空库解析。
2. 同进程合法 Active Session 显示书名、当前页、已进行时间和唯一 CTA“继续学习”，点击回到原 Session。
3. 冷启动遗留 Session 仍标记 ABNORMAL 并释放 Active Slot，Start 不恢复它。
4. Active Intent 显示 First Action 和“继续准备”，可通过弱操作安全放弃，不重复创建 Intent。
5. Mainline 只有 IN_PROGRESS 才能成为正常 Start 主任务。
6. Mainline 状态显示名称、X/Y、弱进度、最近正常阅读、First Action 和“开始学习”。
7. 所有页码与 First Action 使用 `currentPage` 原值，不存在 `currentPage + 1`。
8. 无 Mainline 但有 IN_PROGRESS 时可明确选择本次内容；选择本身不写 mainline。
9. State 4 “设为主线”默认关闭；开启后主线写入和 Intent 创建同事务，失败全部回滚。
10. PAUSED/COMPLETED 不进入正常 Start 主任务；只有这些内容时跳转 Knowledge。
11. 完全空库才显示“添加第一本书”。
12. 首本书创建页“设为主线”默认开启但可关闭；创建与可选主线同事务。
13. 首本书创建成功返回 Start：开启主线进入 State 3，关闭进入 State 4。
14. First Action 支持创建填写、Learning Item 详情编辑、空值动态 fallback，并在 Start/Preparation 一致展示。
15. 存在该 Item 的 Active Intent/Session 时不能编辑 First Action。
16. 最近阅读只取当前显示 Item 最新 NORMAL 已结束 Session，并显示时长和 Note 数；ABNORMAL 不参与。
17. Start 没有全局“+”、Note/Topic/图片/搜索管理、图表、趋势或自律分数。
18. 每个内容状态只有一个最强主 CTA；空、错误、提交状态可理解且可恢复。
19. `createIntent()` 的 IN_PROGRESS 与唯一 Active 事务保护保持有效，所有开始入口仍走 Intent → Preparation → Session。
20. Room Schema 保持 v2，Entity/Column/Index/Migration 与 `1.json`、`2.json` 均不变。
21. Phase 1、2A、2B 自动化回归、JVM、Room/Instrumented、Compose、lintDebug、assembleDebug 全部通过。
22. API 37 覆盖安装、完全离线、同进程 Session 返回、强停/冷启动与首次用户流程通过。
23. 实现提交只包含 Start Correction 相关代码、测试和文档，不混入 Module 2C，不含敏感信息或调试残留。
24. 更新 CURRENT_STATE 与独立 checkpoint，提交并 Push 后停止；不自动进入 Module 2C 或 2D。

## 19. 测试驱动实施顺序

### Task 1: 六级状态与 First Action 纯函数

**Files:**
- Create: `feature/start/StartUiState.kt`、`domain/FirstActionResolver.kt` 及对应 JVM tests
- Modify: 暂不修改 Compose

**Interfaces:**
- Produces: 第 2、3、8 节的 sealed state、resolver 与 `resolveStartContent()`。

- [ ] 写六级优先级、非法主线、非主线 Active workflow、页码不加 1 和 First Action fallback 的失败测试。
- [ ] 运行 `./gradlew testDebugUnitTest`，确认新增类型缺失导致失败。
- [ ] 实现最小纯函数与数据模型。
- [ ] 再运行 JVM tests，确认全部通过。
- [ ] 提交 `feat(start): define action-first start states`。

### Task 2: 最近正常阅读与创建事务

**Files:**
- Create: `RecentReadingSnapshot.kt` 和三个 Repository/DAO Instrumented tests
- Modify: `LearningItemDao.kt`、`SessionDao.kt`、两个 Repository

**Interfaces:**
- Consumes: 现有 Room v2 Entity 和唯一槽位。
- Produces: `observeLatestNormalReading()`、带 `firstAction/setAsMainline` 的 `create()`、带 `setAsMainline` 的 `createIntent()`、`updateFirstAction()`。

- [ ] 记录 schema 1/2 SHA-256，写事务回滚、NORMAL 投影和 First Action 编辑保护失败测试。
- [ ] 运行目标 Instrumented tests，确认新增 API/查询缺失导致失败。
- [ ] 实现最小 DAO 查询和 Repository 事务；不改 Entity/Database/Migration。
- [ ] 运行目标测试及现有 Learning Item/Workflow tests。
- [ ] 核对 schema hash 和 database diff 为空。
- [ ] 提交 `feat(start): add atomic onboarding data flow`。

### Task 3: StartViewModel 与六级交互

**Files:**
- Create: `feature/start/StartViewModel.kt`
- Modify: `feature/start/StartScreen.kt`
- Test: `StartExperienceFlowTest.kt` 的 ViewModel/Compose 前半部分

**Interfaces:**
- Consumes: Task 1 state resolver、Task 2 repositories。
- Produces: select、toggle、begin、abandon 与订阅期间 elapsed update。

- [ ] 写 Session/Intent/Mainline/选择/空状态和按钮防重失败测试。
- [ ] 运行目标 tests，确认旧四分支 UI 不满足断言。
- [ ] 实现 ViewModel Flow 组合和六种克制 Compose 布局。
- [ ] 运行 JVM/Compose tests，确认六级文案与唯一 CTA 通过。
- [ ] 提交 `feat(start): redesign the action home`。

### Task 4: 首次创建、First Action 编辑与 Navigation

**Files:**
- Modify: `LearningItemScreens.kt`、`PreparationScreen.kt`、`Routes.kt`、`MirraApp.kt`
- Test: `StartExperienceFlowTest.kt`、`PhaseOneLearningLoopTest.kt`

**Interfaces:**
- Consumes: `CreateFirstLearningItemRoute`、Task 2 创建 API、`FirstActionResolver`。
- Produces: 空库 → 创建 → Start 的短路径及详情编辑。

- [ ] 写主线默认开启/关闭、返回 Start、动态 First Action 和 Preparation 一致性的失败测试。
- [ ] 运行目标 Compose tests，确认旧导航路径失败。
- [ ] 复用创建页面实现 onboarding 参数和详情编辑；Preparation 改用 resolver。
- [ ] 更新 Phase 1 首次流程测试文案但保留原业务断言。
- [ ] 运行 Start/Phase 1/2A/2B Compose 回归。
- [ ] 提交 `feat(learning): streamline first learning setup`。

### Task 5: 全量验证与独立冻结

**Files:**
- Modify: `docs/CURRENT_STATE.md`
- Create: `docs/checkpoints/2026-09-11-start-experience-correction.md`

**Interfaces:**
- Consumes: 前四个任务的完整 Start 闭环。
- Produces: 可复核的验收证据。

- [ ] 运行 JVM、全部 Instrumented/Compose、lintDebug 和 assembleDebug。
- [ ] 核对 `MirraDatabase.version = 2`、Migration 无 diff、schema hash 不变。
- [ ] API 37 覆盖安装并完成离线六态、同进程 Session、强停冷启动和 Phase 1/2A/2B 回归。
- [ ] 检查 Git diff、临时文件、调试输出和敏感信息；与 Module 2C 未提交规划明确分离。
- [ ] 按实际证据更新 CURRENT_STATE/checkpoint，未运行项标记 Not Run 和原因。
- [ ] 创建独立 Conventional Commit，并只在用户授权范围内 Push 当前功能分支。
- [ ] 停止，不进入 Module 2C 或 Module 2D。

## 20. 规划自检

- 用户要求的 18 项分别由第 1–18 节覆盖，实施顺序位于第 19 节。
- 六级状态、数据来源、CTA、导航、异常和转换均有确定定义。
- currentPage、Active Session 冷启动、Intent 转换和唯一槽位没有被重新解释。
- First Action 的旧数据、空值、自定义与活跃流程编辑冲突均已覆盖。
- 最近阅读严格为单次 NORMAL Session 投影，没有引入 Analytics。
- Schema v2、已有实体、Migration 与导出文件均明确不可改变。
- 计划未包含 Topic/Search、2D、Phase 3 或未来模块。

本计划等待用户验收和单独实施授权；当前回合不修改业务代码、不创建 Migration、不实施 Start 页面。
