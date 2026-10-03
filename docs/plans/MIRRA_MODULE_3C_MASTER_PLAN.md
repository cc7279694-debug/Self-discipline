# Mirra Module 3C｜分心干预、临时允许、休息与回归学习 — 总设计与执行计划

> 供用户一次审阅后交给 Codex 分段执行。推荐使用现有 executing-plans / TDD 工作流，不自动开启并行代理或重复规划。
> 本文件是方案草案，不是已经落地的产品能力；下载本文件不代表 GitHub 仓库已被修改。用户把文末开工指令交给 Codex 后，才授权其中指定的阶段。

**Goal:** 从“能记录分心”推进到“分心时有提醒，合理使用手机有出口，休息后能回到学习”，形成个人可用的完整 3C 闭环。

**Architecture:** 复用 3B 的观测链、绑定、统一事实写入边界、DND 和 Room v4。纯 Kotlin 规则决定行为，Repository 持久化真实区间，UI/Overlay/Notification 仅展示与提交用户动作。不得另建一套 Session、监测 Service 或全局事件框架。

**Tech Stack:** 现有 Kotlin、Compose、Navigation 3、Room、DataStore、Coroutines、Android 平台 API；不在本轮升级依赖、SDK、Gradle 或增加云端。

**Spec:** 仓库 `docs/plans/PHASE_3_IMPLEMENTATION.md`、`docs/plans/MODULE_3B_IMPLEMENTATION.md`、`docs/DECISIONS.md`、真实代码；本文件第 2 节明确列出本次建议补充/调整，须与方案一并批准，不能默认为旧规范已经这样写。

**规划依据日期:** 2026-10-03。

## 0. 已核对的基线与新的推进方式

- 当前读取的远程分支：`codex/phase-3b-task6-validation`。
- 当前读取的 HEAD：`4adc39c1e920bc2c59e3b0fdbaff7795c31930e7`。
- 它在 `1725e6a0e0f9f03fb868efe315d842f243ebcb0a` 的 DND 修补之上，已经将 DND 测试缺少前提的静默 return 改为显式 assumption。不要重复实施。
- 当前 Room v4 已有七种 Segment、风险 App、Session 风险快照、规则快照和 FocusEvent。
- 3B 已实现 READY→Session、风险候选/确认、heartbeat、监测丢失、DND；这些不重新开发。
- 仓库最近记录的生产基线验证是 JVM 148、connected 138；新测试前提收尾另有授权/未授权专项证据。数字是历史证据，不是本计划执行结果。
- 用户最新反馈：一加 13T 已安装初步使用，暂未发现问题；采用模拟器主测，日常真机反馈补充。

### 本次建议的阶段准入调整

把 3B 记为“个人试用开发基线可用于继续开发；发布级系统/API/OEM 专项验收仍开放”。不把 NOT RUN 改成 PASS，也不宣布原协议的完整真机冻结门槛已经满足。

当前仓库仍写着“先完成真机矩阵、不得进入 3C”。用户采用本方案时，Codex 在 3C-1 的首个文档变更中同步新的准入决定，保留历史结论与未测项；不另开一个往返审批回合。

## Global Constraints

- 仅做阅读学习，不扩写作/工作模式，不做任务切换；先结束当前 Session 才能开始另一个。
- Room v4、Schema 1–4、已冻结 Migration 不变；若确实无法表达必要事实，提交一个具体数据缺口，不塞 JSON/错误枚举字段或悄悄升 v5。
- 保留唯一 Active Session/Segment、页码不回退、Note 自动保存、冷启动遗留 Session→ABNORMAL、FULL→PARTIAL 不可逆。
- 不把 UNKNOWN 当 FOCUS，不把 UNMONITORED 当专注；无 Usage 监测仍能读书、记笔记、休息、结束。
- DND 与监测/提示渠道正交；3C 不重写 DND policy、ownership、apply/release 或 READY handshake。
- 不申请 Accessibility、NotificationListener、Device Admin、VPN、QUERY_ALL_PACKAGES、相机、麦克风、精确闹钟；不添加自启/后台复活或强制电池白名单。
- 不硬锁，不拦 Home、系统返回、通知栏和紧急操作；关闭提示不伪造返回学习。
- 不采集其他 App 内容，不上传包使用轨迹；日志仅保留必要、脱敏的调试证据。
- Mirra Blue 不重做，开始/知识/我的不改导航，不加卡片墙、小字说明墙、排名、惩罚语和专注百分比。
- 本次只有四个交付包：3C-1 SOL → 3C-2 LUNA → 3C-3 SOL → 3C-4 LUNA。每包内部可有小提交，只有包边界或真实高风险阻塞才暂停。

## Review Focus

1. 从分心/恢复进入临时允许、从休息返回恢复，目前状态表并不完整：由 3C-1 的转换矩阵测试约束。
2. 已关闭提示的旧通知、旧 token、旧 Session 不得启动新的 allowance：3C-1 与 3C-3 共同测试。
3. 20 秒 foreground freshness 不等于 90 秒稳定窗口：3C-1 必须使用独立可靠的正向证据，而非延长 TTL。
4. DND 可能抑制 Mirra 自己的通知；发出通知不等于用户看见：3C-3 验证，不改变 DND 来制造成功。
5. 到期、延长、退出 App、失监、结束 Session 并发时不得重叠区间或保存假 FOCUS：3C-1 事务测试与 3C-4 闭环回归。

---

## 1. 交付给用户的体验

### 1.1 正常阅读

Session 页继续以书名、阅读时间、页码和笔记为主。新增一个平面状态行和“休息”次级操作。只有有意义的状态变化才显示提示。

### 1.2 打开已选风险 App

短暂访问仍按 3B 处理。连续证据达到原有约 10 秒确认门槛后，显示：

- 标题：`还在读《书名》`
- 主操作：`回到学习`
- 次操作：`临时使用`
- 始终可达的文字操作：`结束本次学习`、关闭提示

不写“你失败了”，不展示累计违规次数来羞辱用户。“回到学习”只是导航/选择，不直接写 RECOVERY_SUCCEEDED。

### 1.3 合理处理手机事务

“临时使用”显示四个用途，默认时长继承现有规则：回复消息 3 分钟，查资料 5 分钟，临时事务 5 分钟，随便看看 3 分钟。确认后才开始计时与 TEMPORARY_ALLOWANCE。

### 1.4 休息与回来

主动休息 5 或 10 分钟，可提前结束。休息/临时使用结束，先回到 RECOVERY；有连续可靠稳定证据才回到 FOCUS。UI 显示“正在回到学习”，不是锁住手机的 90 秒强制倒计时。

### 1.5 看不到提示的情况

外部提醒不可用时，事实仍正常记录；返回 Mirra 才展示仍然有效的提示。用户始终可以继续普通学习，不把权限变成入场门槛。

---

## 2. 继承规则与本次明确补充的决定

| 项目 | 现有来源/现状 | 本计划的明确处理 |
|---|---|---|
| 3B 工程准入 | 文档仍要求完整真机验收后才能 3C | 按用户最新意向改为个人试用准入，发布专项留待后续；不是全平台 PASS |
| 10 秒识别、FGS、DND | 旧 Phase 3 总计划含重复任务，3B 已做 | 直接复用，不重复造监测链 |
| Break 结束 | 总计划表格有直接 FOCUS，正文写 RECOVERY；代码当前缺 BREAK→RECOVERY | 采用正文的保守语义：有监测则 RECOVERY，无监测则 UNMONITORED；不自动宣称专注 |
| 分心后的临时允许 | 原 allowedTransitions 不允许 DISTRACTION→ALLOWANCE | 为 3C 新用户动作补合法转换及事务测试，这是计划内修改，不是越界 |
| Recovery 中再次风险 | 3B candidate/confirm 仅认可 countsAsFocus 的源段 | 3C 扩展为明确的可确认源段；不能用伪 FOCUS 过渡绕过旧检查 |
| 稳定成功 | 3A 方法主要检查段时长 | 3C 先验证连续正向证据，再受限写 milestone；段时长本身不够 |
| 外部提醒 | 实际 3B 未接 Overlay/干预通知 | 放在 3C-3，不假设已有 Presenter 可直接使用 |
| 旧版 Overlay | TYPE_APPLICATION_OVERLAY 从 API 26 起可用 | 本轮只接 API26+；API23–25 使用通知/应用内降级，不用旧 TYPE_PHONE 技巧 |
| 送达含义 | 旧计划把 notify 成功叫 visible | 收窄为平台已提交/界面已附着证据，不宣称用户已看到；见第 7 节 |
| 3D | 旧计划部分恢复已被 3B 提前做掉 | 后续只补结束整理与有效指标，不能照旧文档重建恢复框架 |

上述新增/澄清的决定，在用户采用本计划后一次写入 DECISIONS；不要每条再单独问一次。出现无法安全表达的数据缺口或真正矛盾才暂停。

---

## 3. 行为规则

### 3.1 渐进摩擦

范围是 `sessionId + packageName`，计数依据已持久化 RISK_APP_CONFIRMED，而不是提示渲染次数、轮询次数或解锁次数。

- 第一次确认：0 秒等待。
- 第二次：选择用途后等待 5 秒。
- 第三次及以后：选择用途后等待 15 秒，封顶。
- 短访不升级等级；新 Session 从头计算。
- 等待只限制 Mirra 的“确认临时使用”动作，不限制返回学习、结束本次学习或关闭提示。
- 首版等待从用途选定且操作界面可见时开始；界面不可见时暂停累计，跨渠道显示复用同一个运行中授权令牌。
- 关闭提示后同一个已确认 episode 不反复弹出；新的可信离开→再次进入→确认才是新 episode。
- 不设置每秒提醒或持续振动，不增加“频繁解锁自动升级”等第二套计分规则。本项是相对旧总计划的首版收缩：保留事件，不启用额外解锁摩擦。

### 3.2 Temporary Allowance

- 默认时长使用现有 context.reply/research/temporaryTask/casualAllowanceMillis。
- 若用户在确认前修改单次时长，建议本版限制 1–15 分钟；该范围是本次方案建议，不冒充既有冻结值。只影响这一次，写 plannedEndAt，不修改全局规则。
- 初始实现可用少量时长选项，不做复杂编辑器；选择动作和理由不得每次必填长文本。
- 一次只允许一个指定风险 App；结束/确认事务成功后才关闭提示。
- allowance 起点是用户授权提交时刻，不回填成候选开始时间，不擦掉已经记录的 DISTRACTION。
- 最多延长一次，固定加 2 分钟。必须在尚未到期、仍为同一个活动 allowance 段时二次确认；并发点击只能一次生效。
- 已到期就不能“补延长”。要重新允许，走新的明确用户动作，不能修改已关闭的历史。
- 短暂经过 Launcher、权限页或 Mirra 的用途面板，不立即取消 allowance；目标 App 已可信退出且用户选择返回学习/结束使用，或屏幕关闭/锁定，才提前结束授权。未知不代表退出。
- 允许 A 不等于允许 B：另一风险 App B 仍启动独立候选。B 达到原门槛后，从合法候选边界切到 DISTRACTION(B)，关闭原 allowance；之后不能暗中恢复 A 的剩余授权。短访 B 仅记 brief，不确认为分心。

### 3.3 Allowance 到期后的空档

不能让 allowance 超过授权时间，也不能到期即断言分心。

建议统一：

`ALLOWANCE 到期 → RECOVERY（等待重新投入；不等于成功）`

若目标/其他风险 App 仍在前台，从到期点或其后第一个可信风险证据开始新的 10 秒候选；达到门槛再从合法候选起点切 DISTRACTION。到期前合法使用时间不得计入新候选。

计时回调迟到时，可以按已确定的授权 deadline 切断 allowance，但不得补造 deadline 到当前时刻之间的 FOCUS 或“稳定成功”。该区间按当时真实证据处理；有监测缺口则遵守 UNMONITORED。

### 3.4 Break

- 用户主动选择 5/10 分钟；不是 Pomodoro，不自动安排。
- BREAK 内允许正常使用手机，不生成风险确认/摩擦；监测 loop 和 heartbeat 继续。
- FOCUS、DEEP_FOCUS、DISTRACTION、RECOVERY 可在用户确认后进入 BREAK；当前 allowance 要先明确结束，界面可把两步合为一次原子动作，禁止重叠。
- 结束/到期后：健康监测→RECOVERY；无监测/已失监→UNMONITORED。
- 已有风险 App 在到期后仍前台：重新候选，不计入休息期。
- 没有 Usage Access 的普通 Session 仍可手动记录 BREAK，但 coverage 始终 NONE，结束休息不自动生成 FOCUS。

### 3.5 Recovery 90 秒

满足条件：同一 Active Session、同一恢复 episode，监测查询连续可信；同时有可验证的正向信号：屏幕非交互/已锁定，或者 Mirra 的 Session 页面确实可见并持有窗口焦点。

不允许使用这些替代证据：旧 package 缓存、桌面、任意普通 App、仅收到 USER_PRESENT、仅点击“回到学习”。没有传感器就不写“手机已经放下”。

- 正向证据连续累计 90 秒才成功。
- 中性/未知打断稳定窗口时从 0 重新开始，但查询仍连续时不凭此制造 monitoring gap。
- 出现风险 App 就清稳定窗口，并继续既有 10 秒候选；确认后写 RECOVERY_INTERRUPTED 与 DISTRACTION，一次 episode 幂等一次。
- 真失监清除窗口并走既有 PARTIAL/UNMONITORED，不自动恢复监测。
- 成功事务验证当前段和 generation，关闭 RECOVERY、打开 FOCUS、写 RECOVERY_SUCCEEDED；点击和纯段时长不能绕过证据门槛。
- 本阶段不增加“手动宣布有效专注”快捷方式。

### 3.6 Stable Start / Deep Focus

继承原 3C 范围，但不新增复杂页面：

- stableStartedAt：仅 FULL、FOCUS 连续 120 秒具备上述正向证据，首次写入；不延迟 Session 启动，不覆盖旧值。
- DEEP_FOCUS：仅 FULL，连续合格 Focus 15 分钟且最近 10 分钟有连续屏幕非交互证据、没有解锁/Break/Allowance/Distraction；不足则不推断。
- DEEP_FOCUS 的条件不再成立时（例如可信的屏幕点亮/解锁），从可信边界降回 FOCUS；解锁仍不直接等于 DISTRACTION。风险确认、主动休息和失监按各自优先规则切段，不能一直挂在 DEEP_FOCUS。
- 这些是规则观测标签，不是大脑注意力检测；不显示概率，不计算新的“专注得分”。
- 已有 20 秒 foreground freshness 不改成 90/120/900 秒；使用独立 Session 可见性/屏幕信号支撑长窗口。只读到未知则宁可不出 milestone。
- 3C 不展示有效速度与剩余有效阅读时间；这些仍属 3D。

---

## 4. 时间、状态、事务和并发契约

### 4.1 时间

倒计时、摩擦、恢复、稳定期都用现有 ClockSample 的 elapsedRealtime。wall time 只用于数据库时间线与历史展示，不用修改系统时间来完成等待。

deadline 由用户确认时的同一 ClockSample 生成 wall/elapsed 配对。进程死亡后沿用 ABNORMAL 规则，不恢复旧 deadline/Overlay/授权令牌。发生 wall jump 按 3B 失监策略，不回写旧窗口。

### 4.2 唯一写入边界

复用 `BoundSessionMonitoringController` 现有串行边界；可以在其外加窄 `FocusSessionActions` 门面，但不得创建第二个独立事实锁，分别掌管 UI 和监测。

建议对外命令（具体 Kotlin 类型在 3C-1 一次定好，后续不能各自改名）：

- `startBreak(sessionId, expectedSegmentId, durationMillis)`
- `finishBreak(sessionId, expectedSegmentId)`
- `grantAllowance(sessionId, expectedSegmentId, promptToken, packageName, reason, durationMillis)`
- `extendAllowance(sessionId, expectedSegmentId, actionToken)`
- `finishAllowance(sessionId, expectedSegmentId)`
- `dismissPrompt(sessionId, promptToken)`
- `returnToStudy(sessionId, promptToken)`：不等价于 completeRecovery。

`ActionResult` 区分成功、过期动作、状态冲突、保存失败；界面错误不让用户重新创建 Session。

所有新增命令与用户正常 finish、service loss、权限撤销用同一顺序处理。不得在持有事实锁/Room transaction 时等待 Overlay、通知 Binder 或用户输入。

### 4.3 必须重新校验

事务写入前校验：Session 仍 Active、当前 Segment ID 与命令所指一致、token/generation 对应、原规则快照有效、时间合法、动作没有重复消费。

已结束 Session 的通知点击只能打开历史/提示已结束，不能新建 Session、自动启动 FGS 或写 allowance。

如果 UI 打开临时使用面板时系统已从 DISTRACTION 切到 RECOVERY，允许在同一已确认 episode、可信关联关系下提交授权；不能仅因 UI 导航而使合法按钮必定失效，也不能接受任意旧 episode。

### 4.4 切段

- 新旧段共享同一 boundary；无重叠、无空洞、无零时长段。
- 合法同刻转换采用既有零时长保护模式，或明确幂等/拒绝；不加虚构 1ms。
- 增加 BREAK→RECOVERY；DISTRACTION/RECOVERY→ALLOWANCE/BREAK；合法 allowance 的非目标风险确认；RECOVERY 再次风险确认。
- 同类型但不同风险包切换必须明确切成不同 episode，不能一直保留错误的旧 package。
- `confirmRisk()` 原来只接受 Focus 源段；本轮最小扩展并逐状态测试，不放开任意历史重写。
- 当前 RISK_APP_CONFIRMED.segmentId 指向确认所用的原源段，不一定是新 DISTRACTION 段。提示标识要保存事件身份与当前段身份，不能混为一谈。

### 4.5 数据复用

使用 v4 的 `packageName / reason / plannedEndAt / extensionCount / relatedSegmentId` 存授权与休息事实；规则取现有 context，风险列表取 Session snapshot。

允许增加查询投影/DAO 方法/类型化命令，不新增表。应用侧确定性 event ID、transaction 校验和运行中 actionToken 共同防重；不得只靠按钮禁用。

若新增 `intervention_enabled` 用户偏好，放现有 DataStore，默认 false；Session 开始结算后固定为本次运行中配置，开关只影响下一场。因死亡 Session 不恢复，不为这一运行配置新增 Room 字段；不得把它塞进 unrelated 字段。只在用户启用且能力具备时尝试外部提醒。

---

## 5. UI 范围和状态文案

### Session

默认仍是阅读页。状态只显示一个主词：`阅读中 / 休息中 / 临时使用 / 正在回到学习 / 监测已中断`。休息/临时使用显示有意义的剩余时长，其他状态不叠加倒计时。

新增“休息”操作；风险提示里的“临时使用”进入用途面板。正常更新页码、笔记、退出与结束路径不改业务顺序。

### 用途面板

四个用途、一行默认分钟、一个主确认按钮。等待时只把该按钮显示为 `再等 5 秒` 等状态；回到学习/关闭始终可用。理由不需要自由文本。

### 学习保护设置

沿用现有 Profile 学习保护区，增加一个“跨应用提醒”入口与必要的权限状态。不做权限墙。DND、Usage、提醒权限三者分别表达，不叫一整个“保护已开启”掩盖降级。

### 提示层

同一套 UI model 供 in-app 与 Overlay 使用；Compose 样式复用现有 Mirra token。Overlay 容器生命周期/输入权限由 SOL 处理，LUNA 只写内容与按钮状态。

---

## 6. 分层架构与文件责任

### 复用文件

- `domain/monitoring/BoundSessionMonitoringController.kt`：统一串行事实/用户命令入口，保留 durable loss→finish 顺序。
- `domain/monitoring/CandidateRiskAppMachine.kt`：扩展可确认源段，不重建前台 reducer。
- `domain/SessionSegmentStateMachine.kt`：受控新增合法转换。
- `data/repository/FocusRepository.kt`、`data/local/dao/FocusDao.kt`：授权、延长、Recovery/milestone 的事务与只读事件查询。
- `feature/session/SessionScreen.kt`、既有 ViewModel/导航：薄 UI 接线；Codex 先定位实际类，不凭空迁目录。
- `di/AppContainer.kt`：复用依赖容器，不引入 DI 框架。
- `platform/focus/FocusMonitoringService.kt`：仅允许新增 Presenter 生命周期接线，不复制监测 loop 或改变 FGS type。

### 建议新增文件（以仓库实际结构最小落点为准）

- `domain/monitoring/InterventionPolicy.kt`：次数→摩擦、动作资格，纯 Kotlin。
- `domain/monitoring/StableEvidenceTracker.kt`：恢复/稳定/deep-focus 连续证据窗口。
- `domain/monitoring/FocusSessionActions.kt`：UI 命令契约与门面。
- `feature/session/InterventionContent.kt`：提示与用途内容，共享渲染。
- `platform/focus/InterventionPresenter.kt`：渠道选择、回执、防重复和过期清理。
- `platform/focus/OverlayInterventionPresenter.kt`：WindowManager/生命周期安全。
- `platform/focus/NotificationInterventionPresenter.kt`：独立提醒 channel、明确 PendingIntent。

没有需要就不建文件；名字是本方案建议，不是声称这些类已经存在。SOL 在 3C-1 固定接口后，LUNA 只消费，不各自发明另一套。

---

## 7. 跨应用交付契约（3C-3，SOL）

### 7.1 渠道优先级

- Mirra 已在前台：应用内提示，不再叠 Overlay/通知。
- Mirra 在后台，用户明确开启跨应用提醒、API26+ Overlay 权限可用且有真实当前风险证据：尝试小尺寸可关闭 Overlay。
- Overlay 不可用/失败：尝试现有 POST_NOTIFICATIONS 权限允许的独立提醒通知。
- 都不具备：只保留有效待提示状态，返回 Mirra 时再展示；失效 episode 不补弹。

不是强拉 Activity，不使用全屏通知 Intent，不自动打开目标风险 App。

### 7.2 安全与生命周期

- Overlay 只占实际面板大小，不铺全屏透明遮罩，不做触摸穿透技巧，不遮系统关键 UI。
- WindowManager 操作在主线程；ComposeView 的 lifecycle/saved-state/disposal 明确管理。
- 屏幕关闭/锁定、观察变未知、风险已离开、授权成功、监测终止、Session finish 时关闭对应提示。
- 安全/权限窗口隐藏 Overlay 时不绕过；重新检查状态，无法确认就降级。
- 每个 episode 只有一个 promptToken；双渠道竞争使用同一个 owner；迟到 callback 不能重新显示已经 dismiss 的提示。

### 7.3 通知和 DND 的冲突

独立提醒 channel 与 FGS 常驻 channel 分开，但不默认绕过 DND、不修改用户的 global policy、不为了弹出提醒重写已冻结 Mirra ZenPolicy。

必须测 DND 开/关 × Overlay 可用/不可用 × 通知允许/拒绝。DND 可能使 fallback 通知不弹出或不可见；应明确记录为“已提交但可见性未确认/当前外部提醒不可用”，回 App 提示。不能声称没有 Overlay 也一定实时提醒。

### 7.4 回执不是用户已读

运行时回执至少区分 `IN_APP_PRESENTED / OVERLAY_ATTACHED / NOTIFICATION_POSTED / UNAVAILABLE`。这都是不同层次的证据，不叫“用户看到了”。渠道的 PRESENTED/ATTACHED/POSTED 都不允许触发 Recovery success 或改变 Coverage。

- `notify()` 没抛异常只能说明提交调用完成；检查授权、channel 和可获得的平台状态，但不把它当作已读证明。
- `addView()` 成功只是窗口附着，系统仍可能隐藏；不要声称像素必定可见。
- v4 不新增已读/送达率表；不能为了配合已有 INTERVENTION_SHOWN 名字，把 NOTIFICATION_POSTED 强写成真实可见。
- 仅在具有相应应用内/展示证据时写既有展示事件；通知 posted 可先保留运行态，点击后实际显示记 IN_APP。没有任何可用渠道时记 INTERVENTION_UNAVAILABLE，一次 episode 防重。

### 7.5 通知动作

使用明确目标的 immutable PendingIntent 进入现有 MainActivity/指定阅读 route。当前 stage 首版可让通知只提供“查看/返回学习”，理由与等待由统一应用内面板完成，避免在通知里维护第二套倒计时。

动作到达后再次校验 sessionId、episode/token 与当前状态。不能把过期 extras 当成已授权写入命令，不能启动死亡 Session 的 Service。

---

## 8. 四个交付包与模型切换点

模型名称沿用用户当前界面称呼。这里是任务风险分配，不是价格、可用档位或质量保证。

### 3C-1｜SOL：行为内核、事务与证据窗口

**产物:** 可测试的完整行为内核；尚无跨应用展示。

**修改:** 上述 domain/controller/Repository/DAO 的最小相关文件。允许同步新准入与明确冲突决定；不改 DND、Room schema、FGS 监测协议。

**消费者契约:** `FocusSessionActions`、`InterventionUiModel`、`FocusStatusUiModel`，统一 StateFlow；命令结果含过期/冲突/保存失败。

- [ ] 先读实际基线和本计划，仅输出必要偏差；已解决的 3B 测试不重开。
- [ ] 给新增转换、每包计数、0/5/15、授权期限/单次延长、风险 App B、Recovery 90 秒、Stable120/Deep900 写失败测试。
- [ ] 把 UI 命令与已有 loss/finish 纳入同一串行边界；写事务回滚、重复动作、deadline 竞争测试。
- [ ] 实现规则与最小数据层 API；纯规则不依赖 Android，界面倒计时不直接写数据库。
- [ ] 校验 NONE/失监路径、旧 RiskConfirm 源段关系、锁屏/页面正向证据及 stale foreground 不能冒充稳定。
- [ ] 跑全部 JVM 与受影响 Room/instrumented；记录 tests/pass/fail/skipped，不为纯 domain 修改重复整套 Compose。
- [ ] 独立 commit/push，经用户提供的实际结果确认后停止，输出接口清单与下一包。

**关键失败测试名建议:**
`secondConfirmationWaitsFiveSeconds`、`thirdAndLaterCapAtFifteen`、`grantDoesNotEraseEarlierDistraction`、`concurrentExtensionSucceedsOnce`、`otherRiskAppIsNotWhitelisted`、`breakEndsInRecoveryNotFocus`、`unknownCannotCompleteRecovery`、`sessionVisibleEvidenceOutlivesUsagePackageFreshness`、`gapWinsOverLateRecovery`、`endedSessionRejectsOldCommand`。

**STOP:** `[MODEL_SWITCH_POINT] 下一步 3C-2 / LUNA`。

### 3C-2｜LUNA：应用内用户闭环

**产物:** Session 内可点的休息、临时允许、恢复状态和理由/等待面板；外部 Overlay 尚未启用。

**范围:** `feature/session/` 内容/状态映射、Profile 的薄设置入口、必要导航、Compose 测试。沿用 3C-1 的接口，不改规则。

- [ ] 先写界面状态测试：正常阅读、休息、临时使用、恢复、无监测、保存失败。
- [ ] 实现最少操作与文案；同一数据来源，不复制计时/权限/状态机到 Composable。
- [ ] 理由/等待只影响授权确认，关闭与返回学习永远可用；0/5/15 从规则数据读取，不写死第二份。
- [ ] 保留 Note 输入、500ms 保存与导航 flush；新面板不丢草稿。
- [ ] 检查窄屏、大字号、滚动、返回键、48dp 触控区；不重绘 Start/Knowledge。
- [ ] 跑受影响 JVM/Compose、lint/build；输出少量真实截图与可安装 Debug APK 路径。
- [ ] 独立 commit/push 后停止，不实现 WindowManager、通知权限策略或系统回调。

**STOP:** `[MODEL_SWITCH_POINT] 下一步 3C-3 / SOL`。碰到核心接口不够用时只报告具体缺口，不绕过规则。

### 3C-3｜SOL：跨应用提醒与安全降级

**产物:** 真风险事件可通过 Overlay/通知/应用内渠道提醒，具备结束、失监、过期动作清理。

**范围:** platform/focus Presenter、最少 Manifest/现有 Service 接线、动作验证、渠道测试；不改 DND policy 或启动握手。

- [ ] 先核对当前 Android 官方 Overlay/通知/后台启动限制；不重复广泛竞品调研。
- [ ] 给渠道选择、状态回执、单 episode 防重、延迟 callback、权限撤销、DND fallback 写失败测试。
- [ ] 实现明确 consent/权限入口、API26+ Overlay、小面板生命周期与通知降级。
- [ ] 通知动作只进入正确页面，确认命令仍走 3C-1 的校验；旧 Session 不复活。
- [ ] 接既有 Service 生命周期的清理，不增加第二 poller/Service；NORMAL finish 和 loss 时提示撤销有明确顺序。
- [ ] 在专用 AVD 运行真实风险 App→提示→回学习/allowance→结束链路；分别记录 DND 开关与权限组合。
- [ ] 跑相关平台/事务测试、lint/build；独立 commit/push 后停止。

**STOP:** `[MODEL_SWITCH_POINT] 下一步 3C-4 / LUNA`。系统 API/ownership/安全问题仍由 SOL 处理，不扔给 LUNA 机械修补。

### 3C-4｜LUNA：联调、回归与个人试用交付

**产物:** 完整 3C Debug APK、真实截图、简洁 checkpoint、未测矩阵。

- [ ] 只修 UI 文案、布局、机械接线；核心问题保存复现交 SOL，不调整阈值让测试变绿。
- [ ] 在稳定 AVD 跑一次最终全量 JVM、Room/Migration、Compose/instrumented、lint/build。
- [ ] 显式处理权限前提：DND/Overlay 平台验证不能静默 return；skipped 不当 PASS。
- [ ] 执行第 9 节核心场景与数据保留，记录 APK checksum/commit/device alias/版本。
- [ ] 给用户安装一加 13T 日常试用；不要求马上接电脑、不刷机、不跑覆盖私人数据的自动化。
- [ ] 截图最多覆盖阅读/提醒/用途/休息/恢复/权限降级六类，不生成无关概念图。
- [ ] 更新 CURRENT_STATE 与一个 3C checkpoint，commit/push 后停止。

**STOP:** `[MODEL_SWITCH_POINT] 交 SOL 独立审查整体 diff；不自动进入 3D`。只有架构分歧、持续定位失败或新数据风险才升级到 Astra。

---

## 9. 验收矩阵：每项必须有测试或运行证据

| ID | 场景 | 必须结果 | 负责包 |
|---|---|---|---|
| C01 | 正常阅读 | 原页码/Note/结束闭环不变 | 1/2/4 |
| C02 | 风险短访 | 不升级摩擦，只保留既有 brief 事实 | 1/4 |
| C03 | 同包第1/2/3/4次确认 | 等待0/5/15/15；不同包独立 | 1 |
| C04 | 同episode重放 | 不多弹、不重复写事件 | 1/3 |
| C05 | 关闭提示但没回学习 | 不伪造 Recovery success/Allowance | 1/2/3 |
| C06 | 选择合法临时使用 | 写入后才开始授权，旧分心保留 | 1/2 |
| C07 | 重复/并发延长 | 只延长一次2分钟，不续写闭合历史 | 1 |
| C08 | Allowance A期间访问风险B | 不白名单B，确认后终止旧授权 | 1/4 |
| C09 | Allowance/Break 到期仍在风险App | 重新10秒候选，不含合法期 | 1 |
| C10 | Break 正常结束 | 先Recovery，不直接Focus | 1/2 |
| C11 | Recovery 89.9秒/90秒 | 前者不成功；后者需连续证据 | 1 |
| C12 | Recovery 中断/Unknown | 清稳定窗口；查询健康不凭空失监 | 1 |
| C13 | 仅点击返回学习 | 不自动完成Recovery | 1/2 |
| C14 | Stable/Deep不足证据 | 不补造milestone，不升Coverage | 1 |
| C15 | NONE Session 休息/结束 | 可用；无自动Focus或风险提醒 | 1/2 |
| C16 | 失监与延长/恢复同时到达 | 串行校验，无重叠/假FULL | 1/4 |
| C17 | 结束后旧通知点击 | 不创建Session，不启动FGS | 1/3 |
| C18 | Overlay许可拒绝/撤销 | 不崩溃，通知或应用内降级 | 3/4 |
| C19 | DND ON、无Overlay | 不保证通知可见，不改DND绕过 | 3/4 |
| C20 | 双渠道迟到/重复回调 | 过期提示不复活 | 3 |
| C21 | Home/Back/关闭/紧急退出 | 无硬锁，始终可操作 | 2/3/4 |
| C22 | 强停重开 | ABNORMAL、缺口、清提示、不续监 | 3/4 |
| C23 | 覆盖安装/断网 | 数据与本地闭环保留 | 4 |
| C24 | Room v4 | 1–4 Schema/hash、迁移链不变 | 1/3/4 |
| C25 | 一加13T体验 | 只记实际用户反馈，未知仍NOT RUN | 4 |

### 执行命令

包内按实际类过滤受影响测试，不要求每次小提交全量。最终交付至少执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --no-daemon
```

若任务为 UP-TO-DATE，不称为新测试执行；需要新证据时只对有关任务合理重跑。最终结果必须来自相同实现版本，不能拼接不同 APK 的通过。

模拟器基础故障最多一次有记录的受控重试；仍不稳定就停止该设备项、保留证据。禁止 wipe-data、pm clear、uninstall、削弱断言、无限拉长超时或改业务来处理未定位环境错误。

### 个人试用交付门槛

核心正确性、权限降级、数据保留、退出安全必须通过。不是等所有 OEM 全绿。AOSP 通过不能写成一加专项通过；真实敏感系统行为仍按实际反馈/证据分层记录。准备公开发布时另补 API/OEM、后台限制与政策审查。

---

## 10. 本阶段之后：3D 做什么，不在3C偷跑

3D 的用户价值是“读完时简单整理，回看时知道发生了什么”。建议拆为三个交付包，届时只校准真实代码差异，不重新讨论整个产品：

1. **SOL：结束事务与准确时间口径。** 设计页码/Note flush/Closeout/正常结束/DND释放。先解决旧计划里的 Closeout 期间无 Segment 空洞问题：不能一边关闭最后段，一边把未来 endedAt 的空档当全程FULL；采用什么时间口径必须在3D正式工程计划定清。取消结束也不能凭空新开FOCUS。保留当前3B正常结束直到3D替换测试就绪。
2. **SOL：区间事实与派生统计。** 复用时间线校验，NORMAL+FULL且无空洞/UNMONITORED才生成有效指标；Phase2总时间/速度口径不改。不能因为多了一段FOCUS就认定注意力真的集中。
3. **LUNA：简洁结束页与详情。** 默认只展示页码、本次阅读时长、笔记数；需要时展开区间事实。不重做Mine为大屏，不加打分/排行榜。最后SOL review。

3D 不重新造 DND、FGS、异常恢复、FTS、图片系统。旧总计划中的 boot receiver 不能未经讨论重新加入；当前“用户再打开才恢复，不后台续监”仍优先。

3C/3D 结束后先实际使用再排主题选择、书籍信息、备份等；AI、云同步、硬件移植、长期评分都不挤入本轮。

---

## 11. 节省模型与协作成本的固定规则

- Astra：本次总设计；后续只处理真正跨模块语义冲突或需要重新设计的问题，不审核每条文案、每份日志。
- SOL：3C-1、3C-3、最终整体审查；重大状态机/权限/持久化错误也交SOL。
- LUNA：3C-2、3C-4、已明确范围的布局/普通接线修补；“执行测试命令”不需要高成本模型全程陪跑。
- 高风险测试设计由SOL完成；机械补测试可由LUNA；不能因为叫test-only就自动当低风险。
- 同一模型的任务包内继续执行小步骤，不因创建文件/文档/普通测试每次询问。
- 每个包一份模块报告：基线、changed files、测试摘要、可复查证据、未测项、提交与下个模型。不粘贴整本规划和上百行正常日志。
- 若代码未变且失败纯属设备未连等环境错误，不先改代码；若有明确产品错误，不用“可能模拟器”掩盖。
- 只跑相关测试完成开发迭代，跨层接线结束和最终候选版再全量；维护严格门槛而不无限重复。
- 不因缺少可选 UI playbook 安装而停工；使用当前主题、已有组件和本计划，不补装一堆框架。
- Git 不做 force push、不直接合并main；每个交付包独立提交。所有云端写入须由用户发送下方授权指令明确允许。

---

## 12. 给 Codex 的一次性开工指令（用户采用方案后发送）

```text
采用附件《Mirra Module 3C 总设计与执行计划》，包括其中明确列出的新增澄清与个人试用准入调整。

本轮执行角色：SOL。
只实施交付包 3C-1：行为内核、事务与证据窗口。
不是重新写一套“计划的计划”，也不一次做完全部3C。

先核对当前分支/HEAD与4adc39c1e920bc2c59e3b0fdbaff7795c31930e7的差异；
保留用户现有未提交改动。若仅有后续记录提交，沿正确最新基线继续；
若生产代码不同，说明具体影响，不硬reset到旧SHA。

将附件保存为 docs/plans/MODULE_3C_MASTER_PLAN.md，
在同一交付包中同步CURRENT_STATE/DECISIONS中的个人试用准入，
保留所有NOT RUN，不宣称发布级真机验收已完成。

从正确基线建立 codex/phase-3c-learning-return 功能分支。
允许本轮范围内的代码、测试、计划/状态文件修改，commit和push。
不允许force push、合并main、改Room v4、动DND核心或直接实施3C-2/3C-3。

执行测试先行，完成3C-1规定的规则、事务、关键竞态与接口契约。
阶段内部按小commit推进，不因普通实现细节逐项要求我确认。
真正需要新权限、新schema、改变产品语义或无法解释的状态冲突才停。

完成后报告实际测试、改动、SHA、工作区与接口清单，并停止：
[TASK_COMPLETE]
[MODEL_SWITCH_POINT]
下一步：LUNA执行3C-2，不重新规划核心。
```

后续继续时只需发送“按该总计划执行交付包 3C-2（或3C-3/3C-4），继承最新验收基线，允许该包commit/push，完成后按规定停”。不要求用户每次重新复制所有前文。

---

## 13. 依据与可复核路径

### 仓库依据（固定读取版本）

Repository: `cc7279694-debug/Self-discipline`
Ref: `4adc39c1e920bc2c59e3b0fdbaff7795c31930e7`

- `docs/CURRENT_STATE.md`：3B完成项、NOT RUN、测试收尾状态。
- `docs/plans/PHASE_3_IMPLEMENTATION.md`：原3C/3D范围、0/5/15、allowance、Break、Recovery、Stable/Deep规则。
- `app/src/main/java/com/guanyi/mirra/domain/SessionSegmentStateMachine.kt`：现有合法转换与计时门槛。
- `app/src/main/java/com/guanyi/mirra/domain/monitoring/BoundSessionMonitoringController.kt`：现有串行loss/finish、Focus-only风险确认、Recovery未完成。
- `app/src/main/java/com/guanyi/mirra/data/repository/FocusRepository.kt`：现有事务、字段校验、事件关联。
- `app/src/main/java/com/guanyi/mirra/data/local/entity/FocusEntities.kt`：v4已有可复用字段。
- Commit `4adc39c...`：DND平台测试显式前提与对应证据；不重复实施。

### Android 官方约束（2026-10-03核对）

- Overlay类型、API26起点、SYSTEM_ALERT_WINDOW、系统可调整可见性：
  https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- Android12不可信触摸/Overlay限制：
  https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events
- 通知运行时权限，拒绝后不能把FGS Task Manager条目当通知抽屉送达：
  https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Notification channel与用户控制：
  https://developer.android.com/develop/ui/compose/notifications/channels
- NotificationChannel.canBypassDnd 与系统当前策略的关系：
  https://developer.android.google.cn/reference/android/app/NotificationChannel
- 后台启动Activity限制；通知点击等用户动作路径：
  https://developer.android.com/guide/components/activities/secure-bal

本文件提出的新增默认/范围收缩与原文冲突处理已在第2–3节标注；它们是待采用的工程产品决定，不是平台强制事实。
