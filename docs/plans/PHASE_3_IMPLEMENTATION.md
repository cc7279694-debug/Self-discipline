# Phase 3｜专注干预与分心恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不硬锁手机、不破坏 Phase 1/2 数据语义的前提下，让一次阅读 Session 能维持专注、识别明确风险行为、提供合法临时使用与渐进摩擦，并把用户稳定带回学习。

**Architecture:** 延续单 Android App Module、手动 `AppContainer` 与 UI → Domain/Service → Repository → Room 的结构。Room v4 保存不可变 Session 规则快照、风险 App 快照、状态区间、点事件和系统恢复状态；纯 Kotlin `InterventionEngine` 决策，Android capability adapter 与一个用户可见的前台服务只负责采集/执行。所有权限均可拒绝，Session 主闭环始终可离线运行。

**Tech Stack:** Kotlin、Jetpack Compose、Navigation 3、Room 2.8.5 / SQLite、DataStore、Coroutines / Flow、Android UsageStatsManager、NotificationManager / AutomaticZenRule、TYPE_APPLICATION_OVERLAY、NotificationCompat、Foreground Service。

**Spec:** `docs/PRODUCT_SPEC.md` 的“专注状态”“手机分心干预”“DND 与通知”“Break 与 Recovery”“Session 结束”“SessionSegment”和“Phase 3”章节。

## Global Constraints

- 当前冻结基线为 `2d2156c2a647130fc030ebbcd7cf56b2d5155d50`；Phase 2 实现远程 SHA 为 `734750bfe9fabc13d99091356ae0e4ffd1b8dc82`。
- Room 从 Schema v3 非破坏性迁移到 v4；`1.json`、`2.json`、`3.json` 不得改变，禁止 destructive migration。
- `unlock != distraction`；解锁只产生事实事件，不能直接打开 Distraction 区间。
- Temporary Allowance 与 Break 是合法状态，不计作失败；不强制番茄钟。
- 不使用 Accessibility Service、VPN、Device Admin、NotificationListener 或硬锁机；用户始终可以返回、暂停或结束 Session。
- Overlay 不可用或展示失败时必须尝试 Notification fallback；Overlay、Notification、In-App 都只是 delivery channel，不参与 Session 或 Segment 正确性。
- DND 只在系统明确授权时启用；Android 15+ Closeout 只停用 Mirra-owned AutomaticZenRule，不读取、停用或覆盖用户及其他 App 的 Zen Rule。旧 API 回退也必须 ownership-safe。
- 微信等普通通知由系统 DND 处理；不读取通知内容，不实现联系人或“重要微信”白名单。
- Phase 2 的 Session 总时长、overall reading speed、remaining reading time 与 natural completion prediction 含义不变。
- `UNMONITORED` 是一等 Segment；FGS/Usage/事件流缺口不得猜成 Focus 或 Distraction。effective focus time 只来自从开始到结束完整且可信的 Segment 覆盖，不能把 Session 总时长换名后复用。
- Start 六级状态、Intent → Preparation → Session、`currentPage` 不加 1、唯一 Active Intent/Session/mainline 和异常 Session 恢复规则不变。
- UI 使用现有 Mirra Theme Token / Component；灰白主体、蓝色仅表达行动/选择/进度，普通信息保持平面。
- 不新增 Gradle Module、DI Framework、云端、AI、Analytics 持久化缓存或事件总线。

---

## 1. Task Contract

### Goal

形成完整闭环：

```text
Session 开始
→ 可选 DND + 前台监测
→ 正常 Focus
→ 解锁只记录
→ 风险 App 持续出现才判定 Distraction
→ Break / Temporary Allowance 保持合法
→ 渐进摩擦
→ 离开风险 App 后进入 Recovery
→ 连续稳定后回到 Focus
→ Closeout
→ Session 正常保存
→ 撤销 Mirra DND
```

### Scope

- SessionSegment、FocusEvent、RiskApp、SessionFocusContext、SessionRiskAppSnapshot。
- Room v3 → v4 Migration。
- DND、Usage Access、Overlay、Notification fallback 与前台服务 capability。
- 风险 App 配置、渐进摩擦、Break、Temporary Allowance、Recovery、Stable Start、Deep Focus、Closeout。
- 完整监测覆盖下的 effective focus time、effective reading speed、remaining effective reading time。
- 权限说明、降级、冷启动、进程死亡、设备重启与真机验证。

### Out of Scope

- Accessibility Service 式硬锁、VPN、Device Owner、阻止紧急退出。
- 读取通知内容、联系人判断、微信白名单、通话控制。
- 自动选择风险 App、AI 判断分心、情绪识别、地理位置、麦克风或摄像头监控。
- 强制 Pomodoro、自动安排 Break、仅按固定长时间自动结束 Session。
- Phase 4 Start/Maintain/Recover 长期趋势、异常提醒、综合评分、完整全局 Session Dashboard。
- Backup / Restore 格式升级、云同步、账号、后台任务框架或新主题。

### Dependencies

- `StudyWorkflowRepository` / `SessionManager` 的唯一 Active Session 与结束事务。
- `ReadingAnalyticsRepository` 与 Phase 2D 的历史投影。
- `AppContainer.startup` 的 bootstrap recovery。
- `MirraTheme`、`MirraPrimaryButton`、`MirraSecondaryButton`、`MirraTextAction`、`MirraSurface`。

---

## 2. 当前 Phase 2 架构复用分析

| 当前事实 | Phase 3 复用方式 | 不应做的事 |
|---|---|---|
| `StudySessionEntity.activeSlot` 全局唯一 | 继续作为 Session 活跃真相；Segment 不另建第二套 Session 状态 | 不以 Service 是否运行替代数据库状态 |
| `SessionManager` 是 UI 的 Session 入口 | 增加 break/allowance/closeout 命令并委托 Focus Repository | 不让 Compose 直接调用 DAO 或 Android system API |
| `DefaultStudyWorkflowRepository.startSession()` 已有事务 | 同一事务插入 FocusContext、风险 App 快照和初始 FOCUS Segment | 不在事务外拼装关键 Session 数据 |
| `finishSession()` 原子保存页码和 Summary | 拆成 beginCloseout / completeCloseout；最终保存仍保持一个 Room 事务 | 不在点击结束时先撤销 Mirra DND 再保存数据库 |
| `recoverInterruptedSession()` 冷启动即 ABNORMAL | 扩展为关闭遗留 Segment、标记监测中止并撤销 Mirra-owned rule / ownership-safe rollback | 不恢复死亡进程遗留 Session 为 Active |
| `ReadingAnalyticsRepository` 已避免 N+1 | 增加 Segment 聚合 projection，一次查询得到每 Session 有效时间 | 不逐个 Session 查询 Segment |
| DataStore 已保存主题/顶层页面 | 仅保存 Mirra AutomaticZenRule ID 与权限教育 UI 偏好 | 不把 Segment、风险 App、恢复状态放 DataStore |
| 手动 `AppContainer` | 注入 capability interface 与 coordinator | 不引入 Hilt/Koin |
| Mirra Blue 已冻结 | Session/权限/风险 App 页面复用现有 token 与组件 | 不创建第二套统计卡片或写死品牌色 |

结论：现有 Schema 无法可靠保存区间、风险 App 配置和 DND 恢复意图，因此 Phase 3 必须升级 Schema v4；无需修改既有 v3 表或改写 Phase 2 数据。

---

## 3. Phase 3 核心状态模型

### 3.1 持久化 Segment 状态

`SessionSegmentType`：

- `FOCUS`：用户处于学习意图内，未处于合法休息或已确认干扰。
- `DEEP_FOCUS`：连续满足多信号规则的 Focus 子区间；与 FOCUS 互斥，不重叠计时。
- `BREAK`：用户主动开始的 5 或 10 分钟合法休息。
- `TEMPORARY_ALLOWANCE`：针对一个明确风险 App 和理由的 3–5 分钟合法使用。
- `DISTRACTION`：风险 App 在无合法 allowance/break 时持续前台超过确认窗口。
- `RECOVERY`：风险 App 已离开，但尚未连续稳定到可重新认定 Focus。
- `UNMONITORED`：系统无法可靠说明该区间内的状态；既不算 Focus，也不算 Distraction。

任意时刻最多一个未结束 Segment。`activeSlot = 1` 的唯一索引提供数据库级保护。

### 3.2 非持久化候选态

`RiskCandidate(packageName, firstSeenAt, lastSeenAt)` 只存在于监测进程内，不是 Segment：

- 风险 App 首次出现后 10 秒内离开：只记 `RISK_APP_BRIEF_VISIT` Event，不判定 Distraction。
- 持续达到约 10 秒：后续成功查询必须仍能看到“该风险 App 是最后一个 ACTIVITY_RESUMED / MOVE_TO_FOREGROUND，且期间没有其他 App 前台、屏幕关闭或事件查询失败”的证据，Repository 才把当前 FOCUS/DEEP_FOCUS 从 `firstSeenAt` 截断，并从同一时间打开 DISTRACTION。
- 选择合法 Temporary Allowance：候选态结束，直接从用户确认时间打开 allowance，不产生 Distraction。
- 任意其他 package 的前景事件都会取消 candidate；Launcher、SystemUI、权限页只作为中性过渡，不确认风险持续。查询失败、返回不可解释结果或观测间隔超限时取消 candidate 并进入 UNMONITORED。

这种回写起点必须限制在当前 Segment 的 `startedAt..now` 范围内，禁止负时长和重叠。

### 3.3 转换表

| 当前 Segment | 事件 | 下一 Segment | 事务结果 |
|---|---|---|---|
| 无 | Session 创建 | FOCUS | 创建唯一活动 Segment |
| FOCUS | 满足 Deep Focus | DEEP_FOCUS | 关闭 FOCUS，打开 DEEP_FOCUS |
| FOCUS/DEEP_FOCUS | 用户开始休息 | BREAK | 关闭当前段，打开定时 BREAK |
| FOCUS/DEEP_FOCUS | 用户允许风险 App | TEMPORARY_ALLOWANCE | 关闭当前段，保存 package/reason/deadline |
| FOCUS/DEEP_FOCUS | 风险 App确认持续 10 秒 | DISTRACTION | 以前景首次确认时间切段 |
| BREAK | 用户结束或到期且无风险 App | FOCUS | 关闭 BREAK，打开 FOCUS |
| ALLOWANCE | 用户结束或到期且目标 App 已离开 | RECOVERY | 进入稳定恢复，不直接算 Focus |
| ALLOWANCE | 到期且目标 App 仍在前台 10 秒 | DISTRACTION | 保护恢复后确认分心 |
| DISTRACTION | 风险 App 离开 | RECOVERY | 自动开始 Recovery |
| RECOVERY | 连续稳定 90 秒 | FOCUS | 记录 `RECOVERY_SUCCEEDED` |
| RECOVERY | 风险 App 再次确认 | DISTRACTION | 记录 `RECOVERY_INTERRUPTED` |
| 任意活动段 | 监测能力丢失或不可解释事件缺口 | UNMONITORED | 从最后可信时刻开始，整场 coverage 永久降为 PARTIAL |
| UNMONITORED | 能力恢复且用户确认继续学习 | FOCUS | 后续可以重新监测，但本 Session 仍为 PARTIAL |
| 任意活动段 | beginCloseout | 无 | 关闭段、持久化 Closeout pending |
| 无且 closeout pending | 取消 Closeout | FOCUS | 恢复 Session 和监测 |

非法转换明确拒绝，不做隐式修复；重复到达同一目标的系统事件按 event key 幂等忽略。

### 3.4 首版冻结阈值

这些数值进入每个 Session 的 `FocusRuleSnapshotV1`，计划验收后实施不得静默调整：

| 规则 | 首版值 |
|---|---:|
| Usage event 轮询 | 2 秒 |
| 同包前景事件去重 | 10 秒 |
| 风险 App 确认窗口 | 10 秒 |
| 第一次摩擦等待 | 0 秒 |
| 第二次摩擦等待 | 5 秒 |
| 第三次及以后 | 15 秒，上限不再增长 |
| 回复消息 allowance | 3 分钟 |
| 查资料 allowance | 5 分钟 |
| 临时处理事情 allowance | 5 分钟 |
| “就想看看” allowance | 3 分钟 |
| allowance 延长 | 最多 1 次，每次 2 分钟，需再次确认 |
| 用户主动 Break | 5 或 10 分钟；可提前结束 |
| Recovery 稳定窗口 | 90 秒 |
| Stable Start 稳定窗口 | 120 秒 |
| Deep Focus | 连续 Focus 15 分钟，且最近 10 分钟屏幕保持非交互、期间无解锁/Break/Allowance/Distraction |
| 持久化 heartbeat | 15 秒 |
| 最大可解释观测间隔 | 6 秒（3 个 poll）；屏幕已知关闭不视为缺口 |

不根据“分心超过固定时长”自动结束 Session。长时间 Distraction 只持续提供“返回学习 / 结束本次学习”入口，避免再次引入未经确认的自动结束产品规则。

---

## 4. Room Schema v4

### 4.1 `risk_apps`

| Column | Type | Rule |
|---|---|---|
| `packageName` | TEXT | Primary Key，真实 Android package name |
| `labelSnapshot` | TEXT | NOT NULL，仅用于包卸载后仍能解释历史 |
| `createdAt` | INTEGER | NOT NULL |
| `updatedAt` | INTEGER | NOT NULL |

删除一条代表用户取消风险标记；不自动添加任何包。

### 4.2 `session_focus_contexts`

| Column | Type | Rule |
|---|---|---|
| `sessionId` | TEXT | Primary Key，FK → `study_sessions.id` ON DELETE CASCADE |
| `snapshotVersion` | INTEGER | NOT NULL，首版为 1 |
| `pollIntervalMillis` | INTEGER | NOT NULL，2000 |
| `riskEventDedupeMillis` | INTEGER | NOT NULL，10000 |
| `riskConfirmMillis` | INTEGER | NOT NULL，10000 |
| `secondFrictionMillis` | INTEGER | NOT NULL，5000 |
| `thirdPlusFrictionMillis` | INTEGER | NOT NULL，15000 |
| `replyAllowanceMillis` | INTEGER | NOT NULL，180000 |
| `researchAllowanceMillis` | INTEGER | NOT NULL，300000 |
| `temporaryTaskAllowanceMillis` | INTEGER | NOT NULL，300000 |
| `casualAllowanceMillis` | INTEGER | NOT NULL，180000 |
| `allowanceExtensionMillis` | INTEGER | NOT NULL，120000 |
| `maxAllowanceExtensions` | INTEGER | NOT NULL，1 |
| `shortBreakMillis` | INTEGER | NOT NULL，300000 |
| `longBreakMillis` | INTEGER | NOT NULL，600000 |
| `recoveryStableMillis` | INTEGER | NOT NULL，90000 |
| `stableStartMillis` | INTEGER | NOT NULL，120000 |
| `deepFocusMillis` | INTEGER | NOT NULL，900000 |
| `deepFocusScreenOffMillis` | INTEGER | NOT NULL，600000 |
| `heartbeatMillis` | INTEGER | NOT NULL，15000 |
| `maxObservationGapMillis` | INTEGER | NOT NULL，6000 |
| `usageAccessAtStart` | INTEGER | NOT NULL boolean |
| `dndAccessAtStart` | INTEGER | NOT NULL boolean |
| `overlayAccessAtStart` | INTEGER | NOT NULL boolean |
| `notificationAccessAtStart` | INTEGER | NOT NULL boolean |
| `monitoringStatus` | TEXT | `FULL / PARTIAL / NONE` |
| `monitoringLostAt` | INTEGER | nullable |
| `priorDndInterruptionFilter` | INTEGER | nullable，诊断/回退使用 |
| `dndRuleId` | TEXT | nullable，仅 Mirra-owned rule |
| `dndLifecycle` | TEXT | `NOT_APPLIED / ACTIVE / RELEASE_PENDING / RELEASED / APPLY_FAILED / RELEASE_FAILED` |
| `closeoutState` | TEXT | `ACTIVE / PENDING / COMPLETED / ABORTED` |
| `requestedEndPage` | INTEGER | nullable |
| `closeoutStartedAt` | INTEGER | nullable |
| `lastHeartbeatAt` | INTEGER | NOT NULL |
| `createdAt` | INTEGER | NOT NULL |
| `updatedAt` | INTEGER | NOT NULL |

这些规则列在 Session 创建事务中一次写入，进行中不可修改。Repository 将该行映射为 `FocusRuleSnapshotV1`；不使用 JSON，Migration 与数据库测试可以逐字段验证快照语义，也不新增序列化依赖。

### 4.3 `session_risk_app_snapshots`

| Column | Type | Rule |
|---|---|---|
| `sessionId` | TEXT | FK → Session CASCADE，复合主键第一列 |
| `packageName` | TEXT | 复合主键第二列 |
| `labelSnapshot` | TEXT | NOT NULL |

不外键关联实时 `risk_apps`，保证用户后来取消风险标记不会改写历史 Session 规则。

### 4.4 `session_segments`

| Column | Type | Rule |
|---|---|---|
| `id` | TEXT | Primary Key |
| `sessionId` | TEXT | FK → Session CASCADE，INDEX |
| `type` | TEXT | `FOCUS / DEEP_FOCUS / BREAK / TEMPORARY_ALLOWANCE / DISTRACTION / RECOVERY / UNMONITORED` |
| `startedAt` | INTEGER | NOT NULL |
| `endedAt` | INTEGER | nullable；不得早于 startedAt |
| `packageName` | TEXT | nullable，仅 Allowance/Distraction 来源 |
| `reason` | TEXT | nullable，仅明确用户理由 |
| `plannedEndAt` | INTEGER | nullable，Break/Allowance deadline |
| `extensionCount` | INTEGER | NOT NULL，默认 0 |
| `relatedSegmentId` | TEXT | nullable，Recovery 指向来源 Distraction；不设自引用 FK，避免历史修复循环 |
| `activeSlot` | INTEGER | nullable；活动段为 1，UNIQUE INDEX |

索引：`INDEX(sessionId, startedAt)`、`UNIQUE(activeSlot)`。

### 4.5 `focus_events`

点事件与时间区间分开：

| Column | Type | Rule |
|---|---|---|
| `id` | TEXT | Primary Key |
| `sessionId` | TEXT | FK → Session CASCADE |
| `type` | TEXT | `USER_PRESENT / SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE / RISK_APP_BRIEF_VISIT / RISK_APP_CONFIRMED / INTERVENTION_SHOWN / INTERVENTION_UNAVAILABLE / PERMISSION_LOST / RECOVERY_SUCCEEDED / RECOVERY_INTERRUPTED` |
| `occurredAt` | INTEGER | NOT NULL |
| `packageName` | TEXT | nullable |
| `segmentId` | TEXT | nullable；不依赖 FK |
| `deliveryChannel` | TEXT | nullable；仅 `OVERLAY / NOTIFICATION / IN_APP`，不可见时保持 null |

索引：`INDEX(sessionId, occurredAt)`。不保存通知内容、联系人、窗口文本或用户在其他 App 中的内容。

### 4.6 Migration(3,4)

- 只创建上述 5 张表与必要索引；不 ALTER 既有表。
- v3 用户升级后新表为空；旧 Session 不补造 Segment，也不产生虚假 effective focus time。
- `MirraDatabase.version = 4`，注册 `MIGRATION_3_4`，导出 `4.json`。
- 使用真实 `3.json` 做 v3→v4；另做 v1→v2→v3→v4 连续迁移。
- 校验 Learning Item、Intent、Session、Note、Image、Topic、CrossRef、SearchFts 全部保留且可读。

---

## 5. Repository 与事务 API

### 5.1 Focus Repository

```kotlin
interface FocusRepository {
    fun observeContext(sessionId: String): Flow<SessionFocusContextEntity?>
    fun observeActiveSegment(sessionId: String): Flow<SessionSegmentEntity?>
    fun observeSegments(sessionId: String): Flow<List<SessionSegmentEntity>>
    fun observeRiskApps(): Flow<List<RiskAppEntity>>
    suspend fun replaceRiskApp(packageName: String, label: String)
    suspend fun removeRiskApp(packageName: String)
    suspend fun transition(command: SegmentTransitionCommand): SegmentTransitionResult
    suspend fun recordEvent(event: FocusEventInput)
    suspend fun updateHeartbeat(sessionId: String, at: Long)
    suspend fun markMonitoringLost(sessionId: String, at: Long)
    suspend fun beginCloseout(sessionId: String, endPage: Int): CloseoutSnapshot
    suspend fun cancelCloseout(sessionId: String)
    suspend fun completeCloseout(sessionId: String): StudySessionEntity
}
```

### 5.2 Session 创建事务

`startSession()` 在原事务内按顺序：

1. 再次验证 Intent active、未超时、Learning Item IN_PROGRESS、无 Active Session。
2. 插入 `StudySessionEntity`。
3. 读取当前 `risk_apps` 并插入 Session 快照。
4. 插入 immutable `FocusRuleSnapshotV1` 与 capability-at-start context。
5. 插入唯一活动 FOCUS Segment。
6. `markConverted()` Intent。
7. commit 后由 Session UI 启动前台服务；服务成功后才尝试 DND。

系统 capability 调用不放进 Room 事务。FGS 或 DND 失败不回滚合法 Session，而是持久化降级状态并向用户解释。

### 5.3 Segment 转换事务

每个 `transition()`：

1. 验证 Session 仍 active 且不在 Closeout PENDING。
2. 加载唯一活动 Segment。
3. 校验转换表和 command 时间；`at = max(current.startedAt, min(command.at, now))`。
4. 条件更新当前段 `endedAt` 与 `activeSlot = null`；受影响行必须为 1。
5. 插入新段 `activeSlot = 1`；需要时插入 FocusEvent。
6. Recovery success 或 Stable Start 同事务更新对应事实；监测缺口则从最后可信时刻切到 UNMONITORED，并把 context 永久降为 PARTIAL。

唯一索引负责防重复，Repository 将重复系统事件转为幂等 no-op；用户命令重复则返回明确 current state。

### 5.4 Closeout 事务

`beginCloseout()`：先由 ViewModel flush Note 草稿；事务内验证页码不倒退、关闭活动 Segment、保存 requestedEndPage 与 PENDING，Session 仍保留 activeSlot。

`cancelCloseout()`：PENDING → ACTIVE 并打开新 FOCUS；前台服务恢复。其他状态拒绝。

`completeCloseout()`：单个 Room 事务内复用 Phase 1 规则完成：

- finalPage = max(session.currentPage, requestedEndPage)
- Session NORMAL finish、释放 activeSlot
- Learning Item progress 单调推进
- 原规则式 generatedSummary 保存并重新索引
- FocusContext `COMPLETED`；若 DND 曾 ACTIVE，则写 `RELEASE_PENDING`

commit 后调用 `DndController.releaseForSession()`：API 24+ 只停用 Mirra-owned rule，API 23 才执行 ownership-safe rollback。成功写 RELEASED，失败写 RELEASE_FAILED 并在下次启动重试。Summary 页面在 release 尝试完成后出现；失败时显示轻量“Mirra 专注勿扰可能仍开启，点此处理”，但阅读数据已经安全保存。

---

## 6. Domain / Service 设计

### 6.1 `InterventionEngine`

纯 Kotlin、无 Android 依赖：

```kotlin
interface InterventionEngine {
    fun reduce(state: InterventionState, signal: FocusSignal, rules: FocusRuleSnapshotV1): InterventionDecision
}
```

输入只包含归一化事实：screen interactive、user present、foreground package、permission loss、deadline、用户 reason/action。输出为：NoOp、StartCandidate、ConfirmDistraction、ShowIntervention(level, wait)、StartAllowance、StartRecovery、CompleteRecovery、Start/EndBreak、PromoteDeepFocus、MarkStableStart。

所有阈值来自 Session snapshot，不从全局设置实时读取。

### 6.2 `FocusSessionCoordinator`

连接 Engine、Repository 和 capability；串行处理信号，使用 `Mutex` 防止同一轮 poll、receiver 与 UI 命令交错。它不持有业务真相：每次关键决策前重新读 active Session/Segment。

### 6.3 时间模型

- 数据库与 UsageEvents 使用 epoch millis。
- 进程内等待、poll、allowance/recovery countdown 使用 `elapsedRealtime()` 防系统时间跳变。
- 写数据库前映射回 epoch，并 clamp 到当前 Segment 范围。
- 进程死亡后不恢复活动 Session，因此不尝试跨重启续用 monotonic deadline。

---

## 7. Android Capability 封装

### 7.1 DND

```kotlin
interface DndController {
    fun status(): DndCapabilityStatus
    fun openSettings(): Intent
    suspend fun applyForSession(sessionId: String): DndApplyResult
    suspend fun releaseForSession(context: SessionFocusContextEntity): DndReleaseResult
}
```

- API 24+：创建/复用 Mirra-owned `AutomaticZenRule`，使用 priority interruption filter；Session 时激活，结束时只停用 Mirra 自己的 rule。绝不遍历、关闭或修改用户及其他 App 的 rule；系统如何合并多条规则由 Android 决定。
- API 35+：明确走 AutomaticZenRule；不使用“恢复全局 DND”表述，也不依赖 `setInterruptionFilter()` 改全局状态。
- API 23：无 AutomaticZenRule，授权后保存 prior filter 与 Mirra 实际施加的 filter；结束时只有当前 filter 仍等于 Mirra 施加值才恢复 prior。用户中途改过时保留用户新状态。
- 来电/闹钟由用户系统 DND policy 决定；Mirra 不维护联系人白名单。
- 所有 SecurityException / rule 被用户删除 / 权限中途撤销都转为 typed failure，Session 不崩溃。

### 7.2 Usage Access

```kotlin
interface UsageAccessMonitor {
    fun status(): UsageAccessStatus
    fun openSettings(): Intent
    suspend fun events(fromExclusive: Long, toInclusive: Long): List<DeviceUsageSignal>
}
```

- 用 AppOpsManager + UsageStatsManager 实时复核 special access，不相信缓存。
- API 29+ 读取 `ACTIVITY_RESUMED/PAUSED`；API 21–28 读取兼容的 `MOVE_TO_FOREGROUND/BACKGROUND`。
- API 28+ 可读取 SCREEN_INTERACTIVE/NON_INTERACTIVE；同时在 Service 生命周期内动态注册 screen/user-present receiver，边沿去重后归一化。
- Android R+ 设备未解锁时 queryEvents 可为空；屏幕锁定期间视为合法 non-interactive 信号，不误判为 permission loss。
- 只保存边沿和业务事件，不保存完整系统使用历史。

### 7.3 Risk App catalog

- 不申请 `QUERY_ALL_PACKAGES`。
- 候选列表由可见 launcher activities 与最近 UsageStats package 交集/并集组成；已知 package 才通过 PackageManager 获取 label/icon。
- 排除 Mirra 自身；系统设置/桌面默认不自动选中。
- 用户必须明确勾选，每次变更只影响未来 Session，当前 Session 使用 snapshot。

### 7.4 Overlay 与 Notification

```kotlin
interface InterventionPresenter {
    fun present(model: InterventionPrompt): DeliveryReceipt
    fun dismiss(sessionId: String)
}
```

`DeliveryReceipt` 只能是 `VISIBLE_OVERLAY / VISIBLE_NOTIFICATION / VISIBLE_IN_APP / UNAVAILABLE`。只有系统确认 Overlay 已添加、通知权限和 channel 均允许且 notify 成功，或当前 Activity 实际展示 in-app prompt，才能记录 visible；FGS Task Manager notice 不等于干预已送达。

优先级：

1. `Settings.canDrawOverlays()` 为真 → `TYPE_APPLICATION_OVERLAY`。
2. Overlay addView/update 失败 → 立即发高优先级干预 Notification。
3. API 33+ POST_NOTIFICATIONS 被拒绝或 channel 关闭 → Notification 结果必须为 UNAVAILABLE；FGS Task Manager notice 不标记为用户可见干预，App 返回后可实际展示 in-app prompt。

Overlay 只包含当前 Learning Item、已进行时间、返回学习、合法临时使用、结束 Session；等待按钮只延迟“继续使用”，不拦截 Home/Back/紧急操作。Notification actions 使用 immutable explicit PendingIntent；不从后台直接弹 Activity。

### 7.5 Foreground Service

`FocusMonitoringService`：

- 只由用户在可见界面完成明确的 Session start 动作后启动；不由 boot receiver、后台任务、通知接收器或进程恢复偷偷启动。
- `START_NOT_STICKY`；系统杀死、Task Manager Stop 或 Force Stop 后不自动复活，也不伪装 Session 连续。
- API 26+ 使用 `startForegroundService`；API 23–25 使用 `startService`。
- target 34+ 声明 `foregroundServiceType="specialUse"`、`FOREGROUND_SERVICE_SPECIAL_USE` 和明确 subtype；这是 Play 审核风险，发布前必须复核商店政策。
- 常驻通知显示“阅读进行中”、当前书名、返回 Session、结束入口。
- IO dispatcher 每 2 秒查询增量 UsageEvents；15 秒 heartbeat；不在主线程轮询。连续成功观测间隔超过 6 秒且无法由已知 screen-non-interactive 状态解释时，从最后可信时刻写 UNMONITORED。
- 停止顺序：停止 poll/receiver → dismiss overlay → 保留 DND 给 Closeout 完成路径 → complete 后 stopForeground/stopSelf。

---

## 8. Progressive Friction 与 Temporary Allowance

风险 App 的 `confirmedOpenCount` 按当前 Session + package 计算，不跨 Session，不把 10 秒内 brief visit 计入：

- 第 1 次：显示当前阅读任务，无等待。
- 第 2 次：必须选理由，等待 5 秒后才能继续使用。
- 第 3 次及以后：必须选理由，等待 15 秒；不继续无限增长。

理由与 allowance：

- 回复消息：3 分钟。
- 查资料：5 分钟。
- 临时处理事情：5 分钟。
- 就想看看：3 分钟。

Allowance 绑定触发 package。其他风险 App 仍会触发保护。可延长一次 2 分钟，明确二次确认；重复延长事务拒绝。到期后恢复风险检测，不把合法 allowance 计入 Distraction、Recovery 失败或 effective focus。

---

## 9. Unlock、Break、Recovery 与 Stable Start

### Unlock

- `ACTION_USER_PRESENT` 只记录 USER_PRESENT 并更新 UI/通知“当前正在阅读…”；不会切 Segment。
- 频繁解锁只提升下一次实际风险 App 干预文案层级，不单独产生 Distraction。
- 首版“频繁”定义为 5 分钟内至少 3 次，只影响提示，不进入长期评分。
- Launcher、SystemUI、Permission Settings、Mirra 自身及系统切换中间态不属于风险 App；只作为中性过渡信号。

### Break

- Session 页面提供“休息 5 分钟 / 10 分钟”；完全由用户主动触发。
- Break 内使用手机属于合法休息，不计 Distraction；到期仍停留风险 App 时重新进入 10 秒 candidate。
- 可以提前结束；结束后进入 RECOVERY 90 秒，而不是直接声明恢复。

### Recovery

- 风险 App 离开或 Break/Allowance 结束时打开 RECOVERY。
- “稳定”要求连续 90 秒没有风险 App，且存在屏幕已关闭/手机已放下的正向证据，或 Mirra Session 页面前台。屏幕关闭本身是实体书阅读的重要正向证据，不要求 Mirra 持续前台。
- 停留桌面或未知普通 App 不累计稳定时间；再次风险 App 会中断 Recovery。
- 达标事务关闭 RECOVERY、打开 FOCUS、记录成功；点“回到学习”只帮助导航，不直接成功。
- 90 秒只是观测窗口，不禁用手机、不阻止操作，也不构成 UI 倒计时锁。

### Stable Start / Deep Focus

- 仅当 `monitoringStatus == FULL` 才自动写 `stableStartedAt`。
- Session 开始或 Recovery 后的 FOCUS 连续满足 120 秒、无 Break/Allowance/Distraction 且屏幕非交互或 Mirra 前台，首次写入 stableStartedAt；幂等且不可覆盖。
- Session 在用户完成 start 动作后立即创建并计时；120 秒仅是后续 milestone，不延迟或阻止 Session 开始。
- Deep Focus 只在 FULL coverage 下判定：连续 Focus 15 分钟、最近 10 分钟屏幕非交互、期间无解锁/中断；转换为 DEEP_FOCUS，不显示概率。
- 无 Usage Access、监测中断或权限撤销时保持 nullable，不用纯时间替代。

---

## 10. 有效阅读指标

`EffectiveSessionProjection` 通过单个 SQL 聚合 Session + Segment：

```text
effectiveFocusMillis = SUM(duration of FOCUS + DEEP_FOCUS)
distractionMillis    = SUM(duration of DISTRACTION)
breakMillis          = SUM(duration of BREAK)
allowanceMillis      = SUM(duration of TEMPORARY_ALLOWANCE)
recoveryMillis       = SUM(duration of RECOVERY)
unmonitoredMillis    = SUM(duration of UNMONITORED)
```

资格：Session NORMAL、context monitoringStatus FULL、没有 UNMONITORED、所有 Segment 已闭合、从 Session.startedAt 到 endedAt 连续无空洞且不重叠、effectiveFocusMillis > 0。否则历史仍显示 coverage gap，但不生成 effective speed。PARTIAL 一旦写入不可恢复为 FULL，即便之后监测恢复。

```text
effectiveReadingSpeed = 合格 Session 总 pagesRead / 总 effectiveFocusTime
remainingEffectiveReadingTime = remainingPages / effectiveReadingSpeed
```

- pagesRead 继续 `max(0, endPage - startPage)`，不 +1。
- 0-page Session 的 effective time 进入 denominator。
- 使用 7→14→30 天和至少 3 个合格 Session / 30 分钟有效时间选窗。
- 不修改 Phase 2 overall speed 或 natural completion prediction；Learning Item Detail 将两类指标明确分区命名。
- Phase 3 不新增 Analytics 表，不把 effective speed 写回 Session。

---

## 11. Closeout、冷启动与异常恢复

### 正常 Closeout

```text
flush draft
→ beginCloseout DB transaction
→ 展示轻量 Closeout（页码 + 区间事实）
→ completeCloseout DB transaction
→ release Mirra DND
→ Session Summary
```

Closeout 页面不是 Dashboard，只展示结束页、Session 总时长、完整覆盖时的有效专注、Break/Distraction/Recovery 次数和确认结束。用户可以返回 Session。

### 新进程 / Crash

`FocusRecoveryCoordinator.reconcileAtStartup()` 替换分散 bootstrap：

1. Room 事务找到遗留 Active Session。
2. 在 `max(openSegment.startedAt, lastHeartbeatAt)` 关闭原活动 Segment；若该点早于 recovery time，则补一段闭合的 UNMONITORED 到 recovery time。
3. Session 按既有规则结束为 ABNORMAL，不推进 Learning Item，不生成正常 Summary，并明确标记 coverage gap。
4. Context 标记 ABORTED；DND ACTIVE → RELEASE_PENDING。
5. 事务后幂等撤销 Mirra-owned Zen Rule、关闭 Overlay/Notification。
6. DND 失败保留 RELEASE_FAILED，下一次启动再试并向用户显示入口。

不会因为 heartbeat 年龄引入“超过 N 小时才 abnormal”的阈值。

### 设备重启

- `BOOT_COMPLETED` receiver 最多使用 `goAsync` 调用同一个轻量 recovery coordinator，撤销遗留 Mirra rule 并记录 gap；绝不重启 Session 或 FGS。正确性不能依赖 receiver 一定收到，下一次 App 启动必须再次幂等 reconcile。
- 不声明 directBootAware；credential-encrypted Room 未解锁时不访问。
- `ACTION_MY_PACKAGE_REPLACED` 可复用同一 reconcile，处理覆盖安装中断。
- Force Stop、Task Manager Stop、进程死亡或 FGS 非正常消失都不能承诺后台自动继续。下一次 Mirra 启动以 lastHeartbeat 为最后可信点，补 UNMONITORED 并执行异常恢复。

### 死亡窗口

| 死亡点 | 恢复结果 |
|---|---|
| Session DB commit 前 | 无 Session，无 DND |
| Session commit 后、FGS 前 | 下次启动标 ABNORMAL；整段记 UNMONITORED；DND 未应用 |
| DND apply 后、状态写入前 | 启动根据 Mirra rule ID 幂等停用 |
| Segment close 前 | 用 lastHeartbeatAt 截断可信段，其后补 UNMONITORED，Session ABNORMAL |
| completeCloseout commit 前 | Session 仍 active，启动标 ABNORMAL |
| completeCloseout commit 后、Mirra DND release 前 | Session NORMAL 保留，启动重试 release |
| release 成功、DB 标记前 | 再次停用 own rule 为幂等，然后标 RELEASED |

---

## 12. Android 权限与版本矩阵

| Capability | API 23 | API 24–28 | API 29–32 | API 33 | API 34 | API 35–37 |
|---|---|---|---|---|---|---|
| Usage Access | Settings special access；MOVE_TO_FOREGROUND | 同左 | ACTIVITY_RESUMED；R+ 锁定时 query 可空 | 同左 | 同左 | 可继续兼容 long-range queryEvents，不强绑 API35 overload |
| DND | policy access + prior filter 回退 | Mirra-owned AutomaticZenRule | 同左 | 同左 | 同左 | 必须以 own AutomaticZenRule 思维实现，不假设可改全局 DND |
| Overlay | ACTION_MANAGE_OVERLAY_PERMISSION | 同左 | 同左 | 同左 | 同左 | 同左，厂商可额外限制 |
| Notification | 无运行时权限 | 无运行时权限 | 无运行时权限 | POST_NOTIFICATIONS | 同左 | 同左 |
| Foreground Service | startService + foreground notification | API26+ startForegroundService | API31+ 禁止普通后台启动；从可见 Session 启动 | 同左 | specialUse type + permission | 同左 |
| Boot recovery | BOOT_COMPLETED | 同左 | 同左 | 同左 | 同左 | 同左；不从 boot 启动 FGS |

Manifest 预计新增：`PACKAGE_USAGE_STATS`、`ACCESS_NOTIFICATION_POLICY`、`SYSTEM_ALERT_WINDOW`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`，以及 non-exported Service/Receiver 和 launcher `<queries>`。不增加 Accessibility、QUERY_ALL_PACKAGES、读取通知或联系人权限。

权限页面每次 `ON_RESUME` 实时重查，不把“曾经授权”当当前能力。

---

## 13. 无权限降级体验

| 缺失能力 | 仍可用 | 明确不可用 |
|---|---|---|
| Usage Access | Intent、Session、计时、Note、页码、手动 Break、可选 DND；缺口记 UNMONITORED | 风险 App 自动识别、Stable Start/Recovery 自动成功、Deep Focus、effective 指标 |
| DND Access | 完整 Segment/风险识别/Overlay | 自动静音；展示“系统通知仍可能打扰” |
| Overlay | Notification fallback | 不宣称 Overlay 已送达 |
| POST_NOTIFICATIONS | 有 Overlay 时仍可干预；FGS 可启动但通知抽屉不可见 | 无 Overlay 时只有返回 Mirra 后 in-app prompt；Session 前明确告知 |
| Overlay + Notification 都缺 | Session、DND、Distraction 事实与 Segment 继续记录 | 标记 intervention unavailable；不结束 Session、不伪造 Recovery |
| FGS 启动失败 | 原 Phase 1/2 Session | 后台监测、DND apply 与 effective 指标；立刻标 monitoring NONE |
| 权限中途撤销 | Session 不结束，已有数据保留 | 相应 capability 停止；写 PERMISSION_LOST，monitoring PARTIAL |

权限申请是上下文式、逐项解释、可跳过；不在首次启动制造全屏权限墙。

---

## 14. Compose 与 Navigation 变化

### Session

- 顶部：当前 Segment 文案、Session 总时长；完整覆盖时显示“专注保护已开启”。
- 平面 Focus status 区：Focus / Break / 临时使用 / 分心后恢复，不满屏 Card。
- 操作：`休息`、出现风险提示时 `临时使用`、`结束本次阅读`。
- 权限降级只显示一条可操作 banner，不反复弹窗。
- Note、页码和自动保存布局不改变业务顺序。

### Intervention Overlay / Notification

- 当前书名、First Action、已进行时间。
- `回到学习` 为最强 CTA；`临时使用` 次级；`结束本次学习` 为始终可达文字操作。
- Level 2/3 显示理由与 5/15 秒等待，倒计时不能伪装成硬锁。

### Closeout

- 新增 `SessionCloseoutRoute(sessionId)`；原 Session 的结束按钮先进入 Closeout。
- 新增 `SessionCloseoutScreen/ViewModel`；确认后进入既有 Summary。
- Start 遇到同进程 PENDING Closeout 仍视为 Active Session，继续时导航 Closeout，而非创建新 Intent。

### Mine / 设置

- Mine 最近 7 天摘要不改成 Dashboard。
- 在摘要下增加平面入口：`专注保护`。
- `FocusSettingsScreen` 展示四项实时能力、用途、状态与系统设置入口。
- `RiskAppScreen` 只负责明确勾选风险 App；无自动推荐或默认勾选。

### Mirra Blue

- Primary CTA 继续 `MirraPrimaryButton`；选中/进度使用 `MirraTheme.colors.accent`。
- 权限状态同时使用 icon + 文案，不只依赖颜色。
- 普通设置、风险 App 和历史 Segment 使用平面 row/divider；Soft Neumorphism 仅用于关键 CTA/Focus 状态。
- Touch target ≥48dp；Overlay 和通知文案短、无 slogan、无虚假概率。

---

## 15. 错误与恢复 UX

- Usage 查询暂时失败：保留 Session，重试一次；持续失败标 PARTIAL，不猜测前台 App。
- Overlay addView / update 失败：立即 Notification fallback；不 crash Service。
- Notification channel 被关闭：设置页与 Session banner 明确展示，点击进入系统设置。
- 所有外部 channel 不可用：保存 INTERVENTION_UNAVAILABLE Event；事实状态继续，不能因此改变 Segment、结束 Session 或声明 Recovery。
- DND apply 失败：不停止 Session；显示“勿扰未开启”。
- Mirra DND release 失败：阅读记录已保存；Summary 显示可重试和系统设置入口，启动继续 reconcile。
- 风险 App 卸载：实时列表显示“已卸载”；历史 snapshot 不删除。
- 系统时钟倒退：倒计时使用 elapsedRealtime；持久化区间 clamp，拒绝负时长。
- Segment 唯一约束冲突：重新读取当前态并返回幂等结果；不得 destructive 修复。

---

## 16. 测试计划

### JVM pure-function

- 每个合法/非法 Segment 转换。
- 解锁不会产生 Distraction。
- brief risk visit <10s 不分心；只有后续可观测证据持续支持约 10s 才从 firstSeenAt 回写。中间出现系统过渡态不算风险，证据缺失转 UNMONITORED。
- 1/2/3+ 次摩擦为 0/5/15 秒且封顶。
- 四类 allowance、一次延长、第二次延长拒绝。
- Break 合法且不计 Distraction。
- Recovery 点击不成功；连续 90 秒合格信号才成功；风险 App 中断。
- Stable Start 需要 FULL coverage + 120 秒多信号；无权限保持 null。
- Deep Focus 多信号与中断规则。
- epoch 倒退、elapsedRealtime deadline、重复事件幂等。
- effective 聚合排除 Break/Allowance/Distraction/Recovery/UNMONITORED，0 页进入 denominator，PARTIAL/NONE 或覆盖空洞不预测。

### Room / Migration

- v3→v4 真实迁移；v1→v2→v3→v4 连续迁移。
- v1/v2/v3 Schema 文件 hash 不变；生成 `4.json`。
- 所有 Phase 1/2 表与数据完整保留；新表为空。
- 每 Session 一个 context、一个 risk snapshot set、全局一个 active segment。
- startSession 同事务创建 Session/context/snapshot/FOCUS；任何插入失败全部回滚。
- 转换区间无重叠、时间单调、重复 signal 幂等。
- begin/cancel/complete Closeout 与 Session activeSlot。
- crash recovery 关闭 Segment、ABNORMAL、不推进页码。
- effective aggregate 单查询，不产生 N+1。

### Android Instrumented / capability fake

- fake DND apply/restore 成功、拒绝、撤销、SecurityException、幂等 retry。
- fake UsageEvents API28/29 分支、锁屏 null、重复/乱序事件。
- Overlay success → failure → notification fallback。
- API33 notification denied 降级。
- FGS/Usage 非正常缺口生成 UNMONITORED，之后恢复也不把 Session 升回 FULL。
- FGS start/stop、notification action、receiver 注册/释放、monitoring heartbeat。
- Boot receiver 调用 recovery 而不重启 Session。
- Compose：权限状态、风险 App 选择、Session Segment、Break、Allowance、Closeout、恢复失败提示。
- Start 六级状态、Phase 1/2A/2B/Start/2C/Theme/2D 全量回归。

### 模拟器矩阵

- API 23：DND prior filter 回退、旧 Usage event、Service 启动。
- API 29：ACTIVITY_RESUMED 与 Overlay。
- API 33：POST_NOTIFICATIONS allow/deny。
- API 34：specialUse FGS declaration/runtime。
- API 35 与 API 37：AutomaticZenRule 行为、后台启动限制、完整回归。

### 实体真机必须验证

至少一台 Android 13+ 真机；发布前建议再加一台有激进后台管理的非 AOSP 厂商设备：

1. 手工授予/拒绝/撤销 Usage、DND、Overlay、Notification。
2. 选择真实非关键 App 为 Risk App，验证 10 秒确认与 0/5/15 秒摩擦。
3. Overlay 在目标 App 上显示；关闭 Overlay 后 Notification fallback。
4. Notification 被拒绝时的真实降级。
5. 用户已有 DND / 其他 Zen Rule → Session → Closeout：只撤销 Mirra rule；API23 回退验证用户中途手改 DND 不被覆盖。
6. 3/5 分钟 allowance、一次延长、到期保护恢复。
7. 屏幕锁定阅读、USER_PRESENT、短暂解锁不误判。
8. Distraction → Recovery 90 秒 → Focus；中途重新开风险 App 失败。
9. 系统杀进程、Task Manager Stop、划掉任务、设备重启、App Force Stop 后不自动续监；重新启动识别 monitoring gap 和 UNMONITORED。
10. 无网络完整可用、APK 覆盖安装保留 v3 数据。

模拟器可以验证数据库、UI、权限分支和 AOSP 行为；厂商后台保活、真实 UsageEvents 时序、真实 Overlay 层级、DND 合并和 Force Stop 限制必须以真机结果为准。未具备第二台厂商设备时最终报告必须写 `Not Run`，不能推断通过。

---

## 17. 预计新增 / 修改文件

### 新增

- `app/src/main/java/com/guanyi/mirra/data/local/entity/FocusEntities.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/model/EffectiveSessionProjection.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/FocusRepository.kt`
- `app/src/main/java/com/guanyi/mirra/domain/FocusModels.kt`
- `app/src/main/java/com/guanyi/mirra/domain/InterventionEngine.kt`
- `app/src/main/java/com/guanyi/mirra/domain/FocusSessionCoordinator.kt`
- `app/src/main/java/com/guanyi/mirra/domain/EffectiveReadingService.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/DndController.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/UsageAccessMonitor.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/RiskAppCatalog.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/InterventionPresenter.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/FocusNotificationController.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/FocusMonitoringService.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/FocusBootReceiver.kt`
- `app/src/main/java/com/guanyi/mirra/platform/focus/FocusRecoveryCoordinator.kt`
- `app/src/main/java/com/guanyi/mirra/feature/focus/FocusSettingsScreen.kt`
- `app/src/main/java/com/guanyi/mirra/feature/focus/RiskAppScreen.kt`
- `app/src/main/java/com/guanyi/mirra/feature/session/SessionFocusSection.kt`
- `app/src/main/java/com/guanyi/mirra/feature/session/SessionCloseoutScreen.kt`
- 对应 JVM / Room / Migration / Instrumented / Compose 测试文件
- `app/schemas/com.guanyi.mirra.data.local.MirraDatabase/4.json`

### 修改

- `Entities.kt`（只在必要时共享 enum；优先保持 Focus enum 独立）
- `Converters.kt`
- `MirraDatabase.kt`
- `Migrations.kt`
- `SessionDao.kt`
- `StudyWorkflowRepository.kt`
- `ReadingAnalyticsRepository.kt`
- `SessionManager.kt`
- `ReadingAnalyticsModels.kt` / `ReadingAnalyticsService.kt`（只接线 effective 结果，不改 overall 公式）
- `AppPreferencesRepository.kt`（仅 Mirra Zen Rule ID / 教育偏好）
- `AppContainer.kt` / `TestAppContainer.kt`
- `SessionScreen.kt` / `SessionSummaryScreen.kt`
- `LearningItemReadingSection.kt` / `LearningItemScreens.kt`
- `ProfileScreen.kt`
- `Routes.kt` / `MirraApp.kt`
- `AndroidManifest.xml`
- `strings.xml`
- `CURRENT_STATE.md`、必要的 `DECISIONS.md` 与 Phase 3 checkpoint（仅实施完成后）

### 不修改

- Room `1.json` / `2.json` / `3.json`。
- Phase 2 的 SearchFts、图片文件模型、Topic 数据结构、overall/natural prediction 公式。
- Theme palette/token 定义，除非发现可复现的无障碍缺陷并另行说明。

---

## 18. 分模块实施顺序

### Module 3A：Schema v4 + 纯状态机

**Produces:** v4 五表、Migration、FocusRepository、InterventionEngine、事务测试；不调用系统权限。

- [ ] 先写 v3→v4 和连续迁移失败测试，确认 v3 数据样本完整。
- [ ] 新增 Entity/DAO/Migration/4.json，以最小 SQL 通过迁移测试。
- [ ] 写 Segment 转换、幂等、时间边界和唯一 active segment 测试。
- [ ] 实现 FocusRepository 与纯 InterventionEngine。
- [ ] 运行 JVM + Room + 全量 Migration，检查旧 Schema hash。
- [ ] 独立验收后再进入 3B。

### Module 3B：Capability + 权限与风险 App

**Produces:** DND/Usage/Overlay/Notification adapter、Risk App UI、FGS 骨架、权限降级；不启用渐进摩擦 UI。

- [ ] 使用 fake 先定义每个 capability success/denied/revoked/error contract。
- [ ] 实现实时 capability status 与 Settings intent。
- [ ] 实现无 QUERY_ALL_PACKAGES 的 app catalog 和 risk snapshot。
- [ ] 实现 specialUse FGS、常驻通知、2 秒 poll、15 秒 heartbeat。
- [ ] 实现 Mirra-owned AutomaticZenRule 与 API23 回退、幂等 restore。
- [ ] 完成 API23/33/34/35/37 模拟器矩阵和第一轮真机权限验收。
- [ ] 独立验收后再进入 3C。

### Module 3C：干预、Allowance、Break 与 Recovery

**Produces:** 10 秒确认、0/5/15 摩擦、Overlay→Notification、3/5 分钟 allowance、Break、90 秒 Recovery、Stable/Deep Focus。

- [ ] 先写 reducer 决策表和倒计时测试。
- [ ] 接线 coordinator 串行 signal 与 Repository transition。
- [ ] 实现 Overlay/Notification UI 和始终可达退出路径。
- [ ] 实现 Session Focus UI、Break、allowance/extension。
- [ ] 实现 Recovery、Stable Start、Deep Focus 多信号规则。
- [ ] 运行 Compose、Service、模拟器与真机风险 App 闭环。
- [ ] 独立验收后再进入 3D。

### Module 3D：Closeout、恢复与有效指标

**Produces:** 两阶段 Closeout、Mirra DND release、crash/boot recovery、effective metrics、Phase 3 全量回归。

- [ ] 先写 begin/cancel/complete Closeout 与死亡窗口测试。
- [ ] 实现 Closeout 页面和 Mirra DND release retry。
- [ ] 实现 startup/boot/package-replaced reconcile。
- [ ] 写单查询 Segment aggregate 和 effective service 测试。
- [ ] 在 Learning Item Detail / Summary 增加平面有效指标，不改 Mine 为 Dashboard。
- [ ] 完成所有 JVM/Room/Instrumented/Compose/lint/build/APK/offline/真机验证。
- [ ] 更新文档、创建 Phase 3 checkpoint、独立 commit/push 后停止；不进入 Phase 4。

每个 Module 单独验收。实现阶段仍应按测试驱动拆成小 commit；本计划不授权任何 Module 编码。

---

## 19. 过度设计检查

- 5 张新表分别承载长期配置、不可变 Session 快照、包含 UNMONITORED 的区间、点事件和跨系统恢复；不能安全合并为现有 Session 列，也没有为 Phase 4 建空表。
- 一个前台 Service 是后台风险识别的最小可靠 Android 载体；不增加 WorkManager、事件总线或多进程。
- `InterventionEngine` 纯函数与 Android adapter 分离是为了设备差异和测试，不增加抽象层级链。
- 不采集通知内容、不枚举所有包、不引入传感器持续采样；Deep Focus 使用已有屏幕/解锁/风险 App 信号。
- 不做自动结束、不做无限升级摩擦、不做风险 App 推荐、不做复杂白名单。
- 规则快照使用明确列，不使用 JSON；没有为未来规则创建通用 metadata 或键值系统。

---

## 20. 最终验收标准

1. v3→v4 与 v1→v2→v3→v4 非破坏性迁移通过，旧业务数据全部保留。
2. `1.json`、`2.json`、`3.json` 不变，新增真实 `4.json`，无 destructive migration。
3. 同时最多一个 Active Session 和一个 active Segment；所有 Segment 不重叠且时间非负，监测缺口明确写 UNMONITORED。
4. Session 创建原子写入 FocusContext、风险 App 快照与初始 FOCUS。
5. 解锁只记录，不直接判定 Distraction。
6. 风险 App brief visit 不误判；只有持续观测证据支持约 10 秒才确认并正确回写起点，系统中间态不算风险，缺证据不外推。
7. Progressive friction 严格为 0/5/15 秒并封顶，始终允许紧急退出。
8. Temporary Allowance 为合法状态，时长/单次延长正确，其他风险 App 仍受保护。
9. Break 用户主动、非强制；Break/Allowance 不计 Distraction 或 effective focus。
10. Recovery 必须连续稳定 90 秒；点击按钮或仅离开风险 App不算成功，屏幕关闭/手机放下可以提供正向证据且不限制操作。
11. Session 立即开始；Stable Start 120 秒只是 milestone。Stable Start/Deep Focus 只在 FULL monitoring 的多信号门槛下产生，不显示概率。
12. Overlay 成功可用；失败自动 Notification fallback；Android 13+ 通知拒绝不得记录 visible success；双缺失时明确 intervention unavailable。
13. Usage/DND/Overlay/Notification 任一拒绝、撤销或异常均不破坏 Session 主闭环。
14. Android 15+ DND 只停用 Mirra-owned rule，不触碰全局或其他 rule；旧 API 仅在当前值仍是 Mirra 施加值时 ownership-safe restore。
15. begin/cancel/complete Closeout 事务正确，Note flush、页码单调、Summary/Search 不回归。
16. crash/Task Manager Stop/强停/冷启动/重启遗留 Session 不自动续监，仍为 ABNORMAL，不恢复 active、不推进页码，缺口为 UNMONITORED，并撤销 Mirra rule。
17. effective focus time 只来自全程连续 FULL coverage 的 FOCUS+DEEP_FOCUS；存在 UNMONITORED、空洞或 PARTIAL 时不显示预测。
18. effective reading speed 与 remaining effective time 正确，Phase 2 overall/natural 指标结果不变。
19. Start 六级状态及 Phase 1、2A、2B、Start Correction、2C、Theme、2D 全量回归通过。
20. Mirra Blue 层级、无障碍 touch target、文字/CTA 对比和无默认 Material Accent 泄漏通过视觉检查。
21. JVM、Room、Migration、Instrumented、Compose、`lintDebug`、`assembleDebug`、APK 覆盖安装和完全离线闭环全部通过。
22. API 23/29/33/34/35/37 适用模拟器检查完成；至少一台 Android 13+ 真机完成权限、Overlay、Usage、DND、Recovery、强停/重启闭环。
23. 最终报告明确区分模拟器、真机和未运行项；不能用 fake capability 代替真实系统验收。
24. Phase 3 完成后停止，不自动进入 Phase 4。

---

## 21. Android 官方事实依据

- UsageStats `queryEvents` 需要 PACKAGE_USAGE_STATS；Android R 起设备未解锁时可能返回 null：`https://developer.android.com/reference/android/app/usage/UsageStatsManager`。
- API 29 使用 ACTIVITY_RESUMED/PAUSED，旧 MOVE_TO_FOREGROUND/BACKGROUND 已废弃但用于旧版本兼容：`https://developer.android.com/reference/android/app/usage/UsageEvents.Event`。
- DND policy access 通过 `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` 与 `isNotificationPolicyAccessGranted()`：`https://developer.android.com/reference/android/app/NotificationManager`。
- target Android 15+ 不应假设可直接改写全局 DND，应使用 App-owned AutomaticZenRule：`https://developer.android.com/about/versions/15/behavior-changes-15`。
- API 23+ Overlay 需要用户在系统设置明确授权并以 `Settings.canDrawOverlays()` 检查：`https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW`。
- Android 13+ POST_NOTIFICATIONS 被拒后 FGS 仍可启动，但通知不会出现在通知抽屉：`https://developer.android.com/develop/ui/compose/notifications/notification-permission`。
- Android 12+ 普通后台启动 FGS 受限；本计划只从用户可见的 Session 流程启动：`https://developer.android.com/develop/background-work/services/fgs/launch`。
- Android 14+ FGS 必须声明类型；本用例需 `specialUse` 并接受 Play 审核：`https://developer.android.com/develop/background-work/services/fgs/service-types`。

## 22. 当前授权边界

本文件只做 Phase 3 工程规划。未授权修改业务代码、Room version、Manifest、Gradle 依赖、系统权限或设备状态；计划验收后仍须按 Module 3A → 3D 分别授权实施。
