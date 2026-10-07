# Mirra Phase 3D｜学习结束、可信记录与有效阅读数据设计规范

版本：v1；确认日期：2026-10-04。

历史设计落库时状态：用户已确认的正式 DESIGN SPEC；当时尚未实施 Phase 3D。首次设计落库已完成，当时仅按 Plans Review 修订时钟回退结束例外与 Summary 原地展开，未进入 3D-1～3D-4。

> 2026-10-07 当前导航：原 Phase 3D 已完成并保留正式冻结记录。新授权仅以 [3D-1 Closeout Revision](MIRRA_PHASE_3D_1_CLOSEOUT_REVISION.md) 替代本规范的首击 flush 与 A/B/cleanup 顺序；原不可逆结束、数据保护和既有后续模块不变。正文中的“尚未实施/未来”是规划时背景，不代表当前实现状态；当前事实以 `../CURRENT_STATE.md` 为准。

## 0. 来源、基线与权限

- 用户确认来源：ChatGPT 对话“Mirra 观己”及本回合附件“Mirra Phase 3D｜正式设计规范落库”。附件与对话最后确认的指令正文一致；早期讨论中的不同顺序以最后确认版为准。
- 冻结分支：`codex/phase-3c-final-validation`。
- 冻结 HEAD：`1ae493ed320cee8984681d7df9ba5968b206b3d7`。
- 3C-4 冻结实现：`da461423dbbe17f0a875373d7a4c38f2a1830788`；整个 Module 3C 已正式冻结。
- 设计分支：`codex/phase-3d-design`，从上述冻结 HEAD 建立。
- Room 保持 v4，schemas 1–4 与 Migration 不变。不得修改 3C 冻结实现、生产代码、测试、Manifest、Gradle 或数据库。
- 本规范描述将来获授权后应实现的行为，不把设计写成当前已具备能力。实施计划须在用户复核已提交的书面规范后另行编写。
- Plans Review 修订基线：`codex/phase-3d-plans` / `664a7ce13d10a9d3f887ce486d938b6c390304a4`。本次用户批准的两项修订以本文件和对应计划的修订内容为准，不改变 Room v4 或其他冻结语义。

本规范在 3D 范围内明确替换旧 `PHASE_3_IMPLEMENTATION.md` 的可取消 Closeout、恢复 FOCUS、关闭过程中崩溃一律 ABNORMAL 等设计。原文件作为历史保留；其他冻结产品语义继续继承，不据此扩大 Scope。

## 1. Phase 3D 目标

一次学习结束后，准确保存这次真实发生的过程，只在数据足够可信时展示有效专注时间和有效阅读节奏。3D 不加强手机约束。

核心链路：用户结束学习 → 精确冻结结束时间 → 安全完成 Session → 检查整场时间线 → 从真实 Segment 派生有效指标 → 简单展示本次结果与历史。

## 2. 用户结束体验与页码

1. 第一次点击“结束本次阅读”不结束 Session。先等待当前非空 Note 草稿 flush 成功，然后显示轻量确认框：

   > 结束本次阅读？
   >
   > 这次读到：第 X 页
   >
   > 继续阅读　｜　结束本次阅读

2. 页码默认使用当前持久化 `currentPage`，不加 1。可编辑为合法的更后页码，不得低于数据库已经记录的位置；较小输入必须明确提示“不能低于已经记录的阅读位置”，不能静默夹成另一数字。
3. 最终确认前，选择“继续阅读”只关闭确认框，Session、计时、监测和原行为状态继续。确认框不堆放统计信息。
4. 最终确认按钮点击时采集唯一 `ClockSample`。正常时钟下，其 wall time 就是正式结束边界；只有第 4.1 节明确的 backward wall-clock jump 例外才允许采用更后的 durable boundary。阶段 A 成功保存该决定后，当前 Session 永久不能恢复；继续阅读必须新建一场 Session。
5. 例如正常时钟下最终确认于 20:00:00，则 Session 和最后一段均结束于 20:00:00。数据库处理、清理提醒、停止 FGS、释放 DND 与结果页停留都不能增加阅读时间；异常例外也不能使用重试或清理时的新时间。
6. 阅读进度只前进；`Note.pageNumber` 仍可填写旧页。当前 62 页给第 35 页补笔记，不推进或回退 Session / Learning Item 进度。

## 3. Closeout 复用 Room v4

使用现有 `SessionFocusContextEntity` 的 `closeoutState: FocusCloseoutState`、`requestedEndPage`、`closeoutStartedAt`，不新增字段或 `CLOSEOUT` Segment。

| Closeout 状态 | 设计语义 | 允许操作 |
| --- | --- | --- |
| `ACTIVE` | 尚未持久化最终结束决定 | 正常学习；最终确认前可继续阅读 |
| `PENDING` | 结束时间和请求页已冻结，等待完成保存 | 只重试完成保存和清理，不恢复阅读 |
| `COMPLETED` | 正常结束已完整保存 | 返回已完成结果，不能再写学习事实 |
| `ABORTED` | 保留现有枚举定义 | 3D 首版不使用“确认后取消结束”流程 |

唯一新结束链为 `ACTIVE → PENDING → COMPLETED`；不使用 `PENDING → ACTIVE`。确认点击与 durable commit 必须区分：阶段 A 未成功时没有已保存结束决定；阶段 A 成功后不可撤销。

## 4. 两阶段数据库结束协议

### 4.1 阶段 A：冻结结束事实

输入：`sessionId`、`requestedEndPage`、最终确认时的 `ClockSample`。

必须经过现有 `BoundSessionMonitoringController` 串行事实边界。取得 controller mutex 后先执行现有 `settleMonitoring(finalConfirmSample)`：真实 6 秒健康查询缺口或既有时钟跳变条件成立时，先持久化 loss、降级 `PARTIAL + UNMONITORED`，再结束。3C 旧页面样本 Acceptance Patch 继续有效，不能为保住 FULL 抢先结束。

随后在一个 Room transaction 内：

- 复核 Session 仍 active、context 的 `closeoutState == ACTIVE`。
- 复核请求页在书籍合法范围且 `requestedEndPage >=` 最新持久化 `currentPage`。
- 重新读取当前 active Segment 与已有最后闭合 Segment boundary，遵守现有时间边界，不写倒序或虚构时长，不使用 UI 缓存。
- 正常时钟下写 `closeoutState = PENDING`、用户请求页和 `closeoutStartedAt = finalConfirmSample.wallNowMillis`；异常边界只按下列唯一例外处理。
- 将最后活动 Segment 精确关闭在 `closeoutStartedAt` 并释放其 active slot。
- `activeSegment.startedAt < closeoutStartedAt` 时正常 close；相等时使用既有零时长保护删除该零时长活动段，不虚构 1ms。

阶段 A 失败不得留下半份结束决定；已独立落库的真实失监不能被回滚成 FULL。阶段 A 成功意味着学习事实永久停止，尚保留 Session active slot 不代表仍在阅读。

#### 唯一例外：明确 backward wall-clock jump

如果最终 settlement 或此前同一监测事实串行边界已经明确观测到系统 wall time 后退，且对应 monitoring loss 已持久化、coverage 已降为 PARTIAL、当前活动段为 UNMONITORED，并且 durable timeline boundary 高于最终确认 wall time，阶段 A 不得让用户等待时钟追上或因此拒绝结束。

```text
latestDurableTimelineBoundary = max(activeSegment.startedAt, 已有最后闭合 Segment boundary)
safeCloseoutBoundary = max(finalConfirmSample.wallNowMillis,
                          session.startedAt,
                          latestDurableTimelineBoundary)
```

这些 durable facts 必须在阶段 A transaction 内重新读取并校验；没有闭合段时使用活动段起点。正常路径仍直接使用最终确认 wall time，不执行通用 clamp。普通 `boundary < activeSegment.startedAt / session.startedAt` 且没有明确 backward clock / durable loss 证据时，仍拒绝为冲突或数据错误。

Room v4 没有持久化 loss 原因字段。实现必须把 controller 在本进程、同一 mutex 中明确观测的 backward 样本证据，与事务内的 PARTIAL / monitoringLostAt / UNMONITORED 事实共同核验；只凭 PARTIAL、任意失监或 UI 布尔值不能启用此例外。不新增字段或索引，也不改变既有 clock-jump 阈值、旧样本保护或失监规则。阶段 A 一旦提交，重试和冷启动只读已经冻结的 boundary，不重新申请例外。

异常路径的 `context.closeoutStartedAt` 与 `Session.endedAt` 都使用 safe boundary，最终仍 NORMAL / COMPLETED；coverage 保持 PARTIAL，不能生成完整有效指标。若活动 UNMONITORED 的起点正好等于 safe boundary，删除该零时长段，不制造 1ms、不回填未知时间为 Focus。不得改写已有闭合历史、恢复 FULL 或采用 retry 时刻。

### 4.2 阶段 B：完成正常 Session

阶段 A 后立即执行第二个 Room transaction，中间不调用 Android 系统 API、不等待用户。

只使用已经持久化的 `closeoutStartedAt` 和 `requestedEndPage`，不能重采 `System.currentTimeMillis()` 当结束时间。在事务中复核 finalPage 不低于持久化进度，并原子完成：

1. `StudySession.endedAt = closeoutStartedAt`、`endPage = finalPage`、`endType = NORMAL`、`activeSlot = null`。
2. 推进 `LearningItem.currentPage`。
3. 生成原有 rule-based `generatedSummary`，保持 Phase 1 / 2 与搜索兼容。
4. 同步更新该 Session 的派生 Search index。
5. 写 `context.closeoutState = COMPLETED`。

重复 complete 必须幂等：同一 Session 已为 COMPLETED / NORMAL 时返回原完成事实，不能再次推进、重复生成 Session、改写结束时间或重复业务副作用。

## 5. Closeout 与页码并发

两个事务之间不允许晚到的 `updateCurrentPage()` 修改该 Session。`StudyWorkflowRepository.updateCurrentPage()` 将来必须在自己的 Room transaction 内复核 `context.closeoutState == ACTIVE`，不是只由 UI 禁用。

| 先提交的动作 | 后续结果 |
| --- | --- |
| 页码更新 | 阶段 A 读取最新 currentPage，重新校验请求结束页；低于该位置则明确拒绝 |
| 阶段 A 写入 PENDING | 后到页码更新拒绝，不改变请求页或已冻结边界 |

Note 的旧页记录规则保持独立，不能以 Note 页码反向影响正式进度。

## 6. 数据事务与 Android API 分离

Room transaction 和 monitoring 事实 mutex 内不得等待 WindowManager、NotificationManager、stopService、DND 系统 API 或用户输入。

正常顺序：草稿 flush 成功 → 展示确认 → 用户最终确认 → controller 串行 settlement → 阶段 A → 阶段 B → 数据事实完成并退出事实锁 → 清外部提醒 → release monitoring → release Mirra DND → Summary / Reading Record。

Android 操作失败不能回滚已正确结束的 Session，也不能重开 Segment 或更改结束时间。FGS、DND 与展示通道仍沿用既有 ownership，不新建平行实现。

## 7. 阶段 A 成功、阶段 B 失败

PENDING 是可恢复中间状态：Session 可能仍占 active slot，但没有 active Segment，`closeoutStartedAt` 已永久固定。

- 不再累计阅读时间、不重新建立 FOCUS、不恢复监测、不生成新风险行为。
- UI 显示正在保存，失败时提供重试；没有“继续本场阅读”。
- 重试只执行 complete，使用原边界和请求页；不得重新 begin 或采集新结束时间。
- 退出事实锁后仍清理 Overlay / intervention Notification、停止绑定监测并按现有安全策略释放 Mirra DND。清理失败保留可重试事实，不改变 PENDING。
- PENDING 持有唯一活动槽位期间不另建 Session；同进程返回只能到保存 / 重试，不冒充 Active reading。

## 8. 冷启动恢复

将来不能先无条件把所有 active Session 记为 ABNORMAL。顺序必须是：

1. 查找 `closeoutState == PENDING` 的 Session，幂等 complete。
2. 使用原 `closeoutStartedAt` 完成 NORMAL，不把启动时间计入阅读；不恢复 active reading / FGS。
3. 清外部 intervention，对相关 Mirra DND 执行 reconcile / release。
4. 然后才处理普通遗留 active Session，沿既有 `ABNORMAL`、`PARTIAL / UNMONITORED` 与 ownership-safe cleanup 规则。
5. 继续既有图片 / 搜索 bootstrap；普通异常 Session 的 DND 清理仍保留。

阶段 A 未持久化就死亡仍属普通异常结束；阶段 A 成功后死亡则恢复已保存的正常结束决定。若 PENDING complete 再失败，仍保留 PENDING 重试，不能改成 ABNORMAL 或恢复阅读。

## 9. Note 草稿保存门槛

第一下结束按钮先 await 当前草稿 flush；成功才显示最终确认。失败显示“笔记保存失败，请重试”，Session 仍 ACTIVE，时间和监测继续，用户可以重试或取消。

这正式替换早期讨论中的“最终确认后再救未保存笔记”。最终确认后不提供返回 Session 编辑的入口；保留现有 500ms 自动保存与生命周期 flush，不承诺瞬时进程死亡下未落盘输入绝对不丢。

## 10. 完整可信时间线

使用纯 Kotlin `SessionTimelineValidator` 或等价职责模块。输入为 StudySession、SessionFocusContext 和全部 SessionSegments；可复用现有 `SegmentTimelinePolicy` 的边界检查，不复制平行算法。

只有以下条件全部成立才返回 `COMPLETE_TRUSTED`：

- `endType == NORMAL`、`endedAt != null` 且 `endedAt > startedAt`。
- `monitoringStatus == FULL` 且 `monitoringLostAt == null`。
- 时间线非空，所有参与 Segment 已关闭且每段 `endedAt > startedAt`。
- 第一段起点等于 Session.startedAt，最后一段终点等于 Session.endedAt。
- 所有段都位于 Session 边界内；相邻 `previous.endedAt == next.startedAt`。
- 无 gap、overlap、零时长或越界，不存在 UNMONITORED。

FULL 只是必要条件，不是完整证明。结构异常不得自动修复、不补 Focus、不补 gap；原始事实可以查看，但不能包装成完整指标。

## 11. 有效专注时间

只对 COMPLETE_TRUSTED Session 派生：

`effectiveFocusMillis = SUM(FOCUS duration + DEEP_FOCUS duration)`。

BREAK、TEMPORARY_ALLOWANCE、DISTRACTION、RECOVERY、UNMONITORED 均不计入。DEEP_FOCUS 不加权，只是 Focus 子类型。

| 情况 | 用户结果 |
| --- | --- |
| 完整可信且有效时长为 0 | 有效专注 0 分钟，这是合法可信值 |
| 手机监测不完整 | 本次手机监测不完整，未生成有效专注时间 |
| FULL 但时间线结构异常 | 本次记录不完整，未生成有效专注时间 |

不可用不能写成 0；ABNORMAL 不生成有效指标。

## 12. 有效阅读速度

合格 Session 必须同时为 NORMAL、COMPLETE_TRUSTED、`effectiveFocusMillis > 0`。

- `pagesRead = max(0, endPage - startPage)`，不加 1。
- `effectiveReadingSpeed = 合格 Session 总 pagesRead / 总 effectiveFocusMillis`，按单位换算为页/小时。
- 不平均每场 Session speed。
- 0 页推进但正有效时长仍进入聚合 denominator，不静默过滤。
- 完整可信但 0 有效时长可显示单场结果，不参与速度聚合。

剩余页数继续为 `max(0, totalPages - currentPage)`；预计剩余有效阅读时间为剩余页数 / 有效速度。遵守既有数值安全：总推进为 0 不产生剩余时间预测，不能除零或暴露 NaN / Infinity。

## 13. 有效数据窗口

依次尝试 7 天 → 14 天 → 30 天，选择第一个同时具有至少 3 个合格 Session、总有效专注至少 30 分钟的窗口。仍不足则 effective analytics unavailable，不能放宽门槛换取数字。

沿用 Phase 2 的本地时区 / 自然日与非未来数据边界，不建立另一套日期口径；只替换合格样本与有效时长分母。

## 14. Phase 2 指标保留原含义

不得重定义现有 `ReadingAnalyticsService` 的 Session 总阅读时长、overall reading speed、普通剩余阅读时间或 natural completion prediction。

| 展示条件 | 默认书籍节奏 |
| --- | --- |
| 有效数据窗口成立 | 有效阅读速度约 X 页/小时；符合预测条件时，预计还需约 Y 有效阅读时间 |
| 有效数据不足，窗口不成立 | 自然回退既有 Phase 2 整体阅读节奏 |

不默认堆两套速度，不增加手动模式切换或技术性“样本不足 2/3”说明。预测不可用不等于有效数据窗口不成立，不能仅因不允许预测未来而切换速度口径。既有 PAUSED / COMPLETED 不预测未来、零推进不预测等边界继续生效；合格窗口可以保留可信速度事实，剩余时间只在原有预测边界满足时显示。

自然完成日期始终使用 Phase 2 calendar pace，保留完整自然日分母、资格门槛、波动阈值与置信度规则；不能用 effective speed 直接推日历日期。

## 15. 派生结果不持久化

数据库只存事实。不得新增 effectiveFocusMillis / effectiveReadingSpeed 字段、focusScore、analytics cache table 或另一份统计真相。所有 effective metrics 查询后重新派生。

`generatedSummary` 继续持久化以保持已有记录和搜索兼容，但不作为新结果页主视觉统计来源。

## 16. 查询不产生 N+1

一次读取近期 Session，加一次读取这些 Session 的 Segment，以及必要的 context / risk snapshot 批量 projection，在内存按 sessionId 聚合；等价的单查询 projection 也允许。

禁止每场 Session 再分别查 Segment / label。查询数量应不随 Session 数线性增长，必须在实施测试中证明；不为此新增统计表。

## 17. 结果页

将现有 `SessionSummaryScreen` 升级为统一阅读记录内容。以下为真实数据存在时的格式示例，不填造示例数值：

> 本次阅读已保存
>
> 40 → 62 页
>
> 阅读 48 分钟 · 有效专注 36 分钟 · 笔记 3 条
>
> 1 次休息 · 1 次临时使用 · 1 次分心
>
> 查看本次记录　｜　完成

默认简洁，点击“查看本次记录”只切换当前 `ReadingRecordContent.expanded`，在 SessionSummaryRoute 原地展开真实时间线；不 push SessionSearchDetailRoute，不增加 back stack。不显示专注率、分数、排名、好坏评价或 Dashboard 卡片墙。

## 18. 监测不完整的结果

仍正常显示已保存页码、Session 总阅读时长和笔记数，增加“本次手机监测不完整，未生成有效专注时间”，仍允许查看记录。整场未监测的历史可明确显示“未开启手机监测”。

监测不完整不能阻止普通阅读结果，也不能把无资格有效时长替换成 0；时间线结构异常使用第 11 节的记录不完整提示。

## 19. 人类可读时间线

| 内部类型 | 用户标签 |
| --- | --- |
| FOCUS | 阅读 |
| DEEP_FOCUS | 阅读 |
| BREAK | 休息 |
| TEMPORARY_ALLOWANCE | 临时使用 · App 名 |
| DISTRACTION | 分心 · App 名 |
| RECOVERY | 正在回到学习 |
| UNMONITORED | 监测中断 |

相邻 FOCUS / DEEP_FOCUS 只有展示标签相同且时间连续时才合并为一个“阅读”区间；不改变原 Segment。按时间顺序展示真实有持续时间的段，不展示内部英文枚举或系统事件日志。

## 20. 风险 App 名称与隐私

优先使用 `SessionRiskAppSnapshotEntity.labelSnapshot`。缺少历史 label 时显示“风险 App”，普通用户界面不直接显示包名。不读取 App 内容、消息、网页或通知正文，不以当前安装名称替换历史事实。

## 21. 结果次数

休息次数取 BREAK Segment 数；临时使用次数取 TEMPORARY_ALLOWANCE Segment 数；分心次数取 DISTRACTION Segment 数。

不以提醒数、按钮点击数或 FocusEvent 数计数。UI 合并阅读区间不改变原事实的次数。

## 22. 统一阅读记录详情

使用一个 reusable `ReadingRecordContent` 或等价 UI / domain projection，统一服务：

1. 刚结束的 Session Summary，标题“本次阅读已保存”。
2. Learning Item 阅读历史详情，标题“阅读记录”。
3. Search 阅读记录详情，标题“阅读记录”。

三入口必须使用同一 ReadingRecordProjection、ReadingRecordViewModel / ReadingRecordContent 和可信性判断，不各自实现统计算法。Summary 在原 route 内展开；History / Search 仍进入现有 `SessionSearchDetailRoute(sessionId)`，该详情页也默认简洁并原地展开。共用数据不要求三入口 route 结构相同，展开状态属于各自页面；不借此改变既有完成 / 返回行为。

## 23. 书籍详情阅读节奏

有足够有效数据时示例：

> 阅读节奏
>
> 有效阅读速度约 23 页/小时
>
> 根据最近 7 天 4 次完整阅读
>
> 预计还需约 4 小时有效阅读
>
> 预计 10月12日–10月14日自然读完

日期仍须独立满足 Phase 2 预测门槛；示例不意味着一定显示日期。没有足够有效数据时自动保留 Phase 2 现有节奏，不增加用户模式选择。

## 24. Mine 与视觉边界

Mine 不扩成 Dashboard，不增加长期专注分数、分心/App 排行、打卡、每日图表、成就、专注百分比或 slogan。本阶段边界是单次记录与书籍节奏，Start 不接 Analytics。

新增结果 / 历史 UI 继承冻结 Mirra Blue 的 Theme Token 与 Components：灰白主体、蓝色用于行动与选中、统计和文本保持平面，不新增统计 Design System 或品牌硬编码。

## 25. 结束并发与迟到事件

最终结束与 monitoring loss、risk confirmation、behavior actions 共用现有 controller 串行事实边界。

- begin 前先 settlement；真实 gap 已到期则先 durable loss，再 closeout。
- 阶段 A commit 后，旧 callback / 页面证据 / heartbeat / 用户行为不能改冻结边界、重开 Segment 或恢复 Active reading。
- 尚未成为 durable RISK_APP_CONFIRMED 的 candidate 不由 closeout 猜测或补确认；只使用结束边界前已按冻结规则建立的 durable facts。
- 双击最终确认只产生同一 durable 结束决定；重试完成而非重建结束时间。
- 运行连续性仍用 monotonic time，历史事实用 wall time。明显 wall-clock jump 继续保守失监，时间线不得倒序、重叠或靠改写历史修饰；明确后退且已 durable loss 时，结束边界只使用第 4.1 节唯一例外，不作普通 boundary clamp。

## 26. Android 系统清理与 DND

阶段 A 成功即让旧 Prompt / 外部动作失效。阶段 A / B 完成，或已经进入可重试 PENDING 后，退出事实锁再清 Overlay 与 intervention Notification、释放 monitoring binding / 停止 FGS、释放 Mirra DND。

继续继承 stale PendingIntent 防护：旧通知与旧 action 不得复活 Session、创建 Allowance、恢复监测或改变时间线。进程死亡不后台续监。

DND release 失败不能修改 NORMAL / PENDING 边界，沿现有 ownership-safe retry；不操作用户或其他 App 的规则。若仍有待处理状态，结果 / 保存界面弱提示“Mirra 勿扰状态需要处理”，提供“重试”，必要时“去系统设置”。不因系统清理失败要求用户重新结束。

## 27. 明确替换的旧设计

旧 `PHASE_3_IMPLEMENTATION.md` 的 beginCloseout → Closeout page → cancelCloseout → reopen FOCUS → 恢复前台监测，整套用户语义由本规范替换。

同样替换早期对话中阶段 A 与 B 之间先调用系统清理、最终确认后才 flush 草稿，以及“complete commit 前死亡一律 ABNORMAL”的安排。

正式设计只有：最终确认前仍 ACTIVE，可继续读；阶段 A 成功后永久冻结，只有完成保存。两个数据库事务连续执行，中间不调用 Android API；冷启动先处理 PENDING，再处理普通异常 Session。不新增 CLOSEOUT Segment。

## 28. 旧历史数据兼容

旧 Session 无 Segment 或无完整 coverage 时不补造 effective focus，继续保留总阅读时长、页码、Note 和 Phase 2 overall analytics。

已有 3C Session 若天然满足 NORMAL + FULL + 完整连续 Segment，可以从原事实派生有效指标；不以创建版本人为排除。不回填或改写已关闭历史。

## 29. 架构职责及当前代码事实

| 职责 | 设计边界 |
| --- | --- |
| Closeout Coordinator | 最终 ClockSample、controller 串行 begin/complete、退出事实锁后的 Android 清理、retry / startup recovery |
| Closeout Repository / methods | 只负责 Room 结束事实、事务和幂等，不调用 Android API |
| SessionTimelineValidator / 等价模块 | 纯 Kotlin 可信性、effectiveFocusMillis、Segment counts 与时间线 projection 资格 |
| EffectiveReadingService | 7/14/30 有效窗口、有效速度和剩余有效时间，不改 Phase 2 service |
| ReadingRecordProjection | Summary / History / Search 共用事实 |

以上是职责，不要求提前创建空类、独立 Gradle module 或 DI framework。实施计划阶段再逐项确定文件与 API。

冻结 HEAD 的只读核对确认：

- `data/local/entity/FocusEntities.kt` 已有 Closeout 字段、coverage、7 种 Segment 和 risk label snapshot；当前字段是预留事实模型，不代表两阶段 Closeout 已实现。
- `data/repository/StudyWorkflowRepository.kt` 当前仍单事务 finish，内部采结束时间，低结束页使用 maxOf；当前 updatePage 尚未检查 Closeout，恢复仍先将遗留活动 Session 标 ABNORMAL。这些是将来 3D 的明确修改点，不在本回合改动。
- `domain/monitoring/BoundSessionMonitoringController.kt` 已有 mutex、settlement 和旧样本竞态保护；新结束命令必须接入该事实边界，不建立第二套控制器。
- `domain/SegmentTimelinePolicy.kt` 已有时间线结构校验，可以由新可信性职责复用，补 Session NORMAL / monitoringLostAt 等资格，不替换 3A 状态机。
- `di/AppContainer.kt` 当前启动恢复及系统 cleanup 次序将来按第 8 节调整；DND ownership 本身保持冻结。

## 30. 未来开发包与停止点

| 包 | 未来授权范围 |
| --- | --- |
| 3D-1 安全结束学习 | Note flush gate、二次确认、合法结束页、precise boundary、begin/complete、PENDING startup recovery、monitoring/intervention/DND cleanup、并发测试 |
| 3D-2 可信时间线与有效指标 | Validator、有效专注、时间线 projection、有效速度/剩余时间、7/14/30 窗口、Phase 2 回归 |
| 3D-3 结果与历史 | Unified Reading Record、Summary、展开时间线、label snapshot、Learning Item history / Search 入口、有效优先/回退 UI、布局与可达性 |
| 3D-4 最终联调 | 全量测试、API37 AVD、离线、强停、Closeout crash windows、DND retry、stale actions、覆盖安装、Debug APK、checkpoint 与独立验收 |

每包独立授权、独立验收后停止，不自动开始下一包。不按旧模型名称划分产品架构。本文件仅设计拆分，不是实施任务脚本。

## 31. 必须证明的验收场景

### Closeout

- 从 FOCUS、DEEP_FOCUS、BREAK、TEMPORARY_ALLOWANCE、DISTRACTION、RECOVERY、UNMONITORED 与 NONE Session 结束，最后 Segment / Session 共用正式边界。
- 双确认；closeout 与 monitoring loss / risk confirmation / page update 并发；阶段 A 失败；阶段 A 成功后进程死亡；阶段 B 失败与幂等 retry。
- 明确 backwards wall jump：Session 起点 1000、最后可信 / 活动 UNMONITORED 起点 2000、最终 wall 100、elapsed 单调前进、PARTIAL / monitoringLostAt 2000。结束成功且 A / Session 边界为 2000，NORMAL / COMPLETED；零时长 UNMONITORED 删除，不写 100 / 重试时间、不生成完整有效指标。无明确 clock-jump 证据的普通过早边界仍拒绝。
- DND release 失败不污染数据库；旧外部 action 在 PENDING / 已结束后不复活；系统 cleanup 不占事务或事实锁。

### Timeline / effective

- exact continuous、gap、overlap、首段 late、末段 early、open / zero-duration / out-of-bounds Segment。
- UNMONITORED、PARTIAL、NONE、ABNORMAL、monitoringLostAt 非空、旧 Session 无 Segment。
- 可信但零有效时长；零页但正 focus；所有样本零页；多个 Session 加权聚合；7/14/30 与本地时区边界；不产生 N+1。
- Phase 2 总时长、overall speed、剩余时间、自然完成日期、旧数据保持原语义。

### UI / final integration

- FULL、PARTIAL、NONE、零有效时长、结构异常均诚实展示；阅读区间合并；label 正常/缺失；history/search/刚结束三个入口一致。
- Summary“查看本次记录”展开真实时间线但 route / back stack 不变；History / Search 仍走统一 detail route。三入口比较同一 projection，不强制导航结构相同。
- 320/360/411dp、fontScale 2、关键按钮可达，TalkBack 仅实际执行后才记结果。
- 最终链路：Start → Session → Break → risk App → Overlay/Notification → Allowance → Recovery → 阅读 → 最终结束 → 结果 → 历史。
- 未来完整 JVM / Room / Migration / Compose / Instrumented / lint / build、离线、覆盖安装数据保留、Force Stop / 冷启动与 Closeout crash 窗口分别留下证据。
- 本回合不执行上述未来测试；API23–36、OEM、实体设备完整矩阵、TalkBack、release 的既有 NOT RUN 保留。API37 AVD 不外推；一加 13T daily-use smoke 不升级兼容性 PASS。

## 32. Scope Guard

不得实现 AI summary / AI focus 判断、cloud、backup、hardware / wearable、score、ranking、habit streak、新监测权限、hard lock、主题扩展、Mine Dashboard 或 boot-time monitoring resume。

不得修改冻结的 3C 行为语义：0/5/15、Allowance、Break、Recovery 90 秒、Stable / Deep 规则、coverage 不可逆、DND ownership、Overlay / Notification delivery 意义。结果不把通知 POSTED 当用户可见，不绕过 DND。

## 33. Schema Guard

`MirraDatabase.version = 4`；不新增 Entity / Table / Column / Index / Migration，不生成 Schema v5，不修改 `1.json`～`4.json`。

v4 SHA-256：`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。

若将来实施确实发现现有 v4 无法安全表达必要事实，立即停止并报告具体数据缺口，不能自行升级 v5、改写历史、清库或修改冻结核心。

## 34. 设计交付与授权状态

本规范是用户已确认的设计基线。首次设计落库仅新增本文件，提交 `docs(focus): add phase 3d design spec`，推送 `codex/phase-3d-design`；该步骤已完成。

当前 Plans Review 只修订用户限定的设计 / 计划文档，不修改状态 / 决策文件来冒充实现进度，不修改业务 / 测试 / 数据库，也不开始 3D-1～3D-4。后续实施仍须另获明确授权。

## 35. 规范交付自检

提交前逐项核验：

- ACTIVE → PENDING → COMPLETED 与 PENDING 优先冷启动恢复一致，不提供确认后取消。
- 正式 end boundary 与最后保留 Segment boundary 一致，不计入清理时长；正常时钟使用最终确认 wall，明确 backward 例外使用事务内 durable boundary，普通过早边界仍拒绝，零时长不伪造。
- Note flush 在确认框之前，保存失败仍 ACTIVE。
- stage A / B 连续数据库事务，Android API 在事实锁之外；PENDING 不再产生学习事实。
- page update 的事务 guard 与晚到 callback / stale action 防护明确。
- effective 只来自完整可信时间线；可信 0 与 unavailable 分开，0 页分母不删。
- Phase 2 overall / calendar semantics 不变，统一 projection、派生不持久化、无 N+1。
- Summary 原地展开，不新增详情路由；History / Search 继续现有 detail route，同源不等于同导航栈。
- 旧 cancelCloseout 和早期不一致顺序明确 superseded，Room v4 不变。
- 核心语义无待定占位，设计与当前实现区分，无提前实施。
- `git diff --check`、文件范围、秘密模式、Schema hash 与 local/remote SHA 核验，工作区干净。

本回合验证只证明文档落库和冻结边界未改，不证明 Phase 3D 功能已实现或测试已通过。
