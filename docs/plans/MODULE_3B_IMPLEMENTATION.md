# Module 3B｜Android 监测能力 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task after separate authorization. Steps use checkbox (`- [ ]`) syntax for tracking.

> 供后续获授权的实施回合逐项执行和验收。本文件是规划，不是 3B 开工授权。

**Goal:** 在不阻止用户读书的前提下，用真实 Android 监测证据建立可信 Session 起点、观察风险 App、识别监测缺口，并以可归属的 DND 减少普通通知干扰。

**Architecture:** 保持单 Android App Module、手动 `AppContainer`、Navigation 3、UI → Domain/Service → Repository → Room。`SessionStartCoordinator` 编排用户启动、capability、FGS READY lease 和既有 Intent→Session 事务；FGS 只维持监测生命周期，UsageEvent 经有序 reducer 进入纯状态机；Room v4 保存事实，运行时计时使用单调时钟。

**Tech Stack:** Kotlin、Coroutines/Flow、Jetpack Compose、Room 2.8.x、DataStore、`UsageStatsManager`、`AppOpsManager`、`NotificationManager`、Android Foreground Service；`minSdk=23`、`targetSdk=37`（以当前工程为准）。不增加 Gradle Module、DI 框架或云端依赖。

**Spec:** `docs/PRODUCT_SPEC.md` Phase 3 及专注状态/手机分心/DND 章节；`docs/plans/PHASE_3_IMPLEMENTATION.md`；`docs/research/PHASE_3B_EXISTING_SOLUTIONS_REVIEW.md`；`docs/DECISIONS.md` 的 2026-09-19 监测握手与 Coverage 决策。后两者已冻结的 3B 专项语义优先于 Phase 3 总计划里“先建 Session 再启动 FGS”“2 秒初始轮询”的旧建议。

## Global constraints / Task Contract

- **Scope:** Usage Access、通知和 DND capability；用户动作启动的一个 FGS；约 1 秒 UsageEvent observation；有序 reducer；风险 App 配置与 10 秒候选/确认事实；15 秒 heartbeat 与 gap；Monitored Session Start handshake；仅 debug 可见诊断；最小权限/降级 UI。
- **Planning base:** 当前 `codex/mirra-visual-parity` HEAD 为 `55fcec57111a581289fbb93c49f51c93332f7dab`（3B Existing Solutions Review 冻结提交）；实施前仍须重新检查 Git/Schema 事实，不能把此 SHA 当永久代码状态。
- **Out of scope:** 3C Progressive Friction、Temporary Allowance 用户流程、Recovery 成功/干预、Overlay、通知干预 fallback、硬拦截、Accessibility/VPN、Deep Focus 自动推断、effective 指标、Phase 4、重做 Visual Parity。
- **Data:** Room Schema v4、`4.json`、旧 Schema 和 Migration 均保持不变。复用 `risk_apps`、`session_focus_contexts`、`session_risk_app_snapshots`、`session_segments`、`focus_events`；若实施中发现不可表达的事实，先报告具体缺口并暂停，不自行升级 v5。
- **Safety:** 唯一 Active Intent/Session/Segment；Intent→Session 事务内复核 Learning Item、Intent 时效和唯一槽位；`FULL` 只在 READY 且起点连续可信时原子创建。真实缺口立即保守记录 `UNMONITORED`，`FULL→PARTIAL` 不可逆；`NONE` 不追认 `FULL`。
- **Degradation:** 缺少 Usage Access、FGS 失败、`queryEvents` 不可用或握手超时仍可开始 `NONE + UNMONITORED`；DND 和通知可拒绝，不影响 Session 正确性。拒绝通知权限时不宣称普通通知栏已向用户展示。
- **Review focus:** 双击开始不能双建 Session；READY 到事务 commit 间 service 死亡不能伪造 FULL；锁屏且前台包未知仍可 READY；系统时钟变化不能完成 10 秒候选；Task Manager Stop 后不得自动续监；DND 中途被用户修改后不能覆盖其选择。

## Review Focus

1. 连续点击启动和 Activity recreate：只能得到一个 Session；取消中的孤儿 Service 必须停止（第 6、12 节测试）。
2. READY 后、DB commit/绑定前的监测失效：不可写假 FULL，已 commit 的 Session 必须降 PARTIAL（第 6、13 节测试）。
3. 锁屏、空事件和未知前台包：READY 与风险判断分离，不能因此阻塞学习或猜风险包（第 4、13 节测试）。
4. 系统时钟突变、迟到 UsageEvent：候选不提前确认；无法衔接时保守落 UNKNOWN/UNMONITORED（第 4–7、13 节测试）。
5. 用户停止 FGS 或中途修改 DND：不自动续监、不覆盖用户新 DND、下次启动识别缺口（第 3、7–8、13 节测试）。

## 1. 当前代码事实与复用点

| 事实源 | 3B 用法 / 需改处 |
|---|---|
| `PreparationViewModel.start()` → `SessionManager.start()` → `DefaultStudyWorkflowRepository.startSession()` | 保留唯一用户启动入口；由 Coordinator 接入，不在 UI 直启服务或直接写 DAO。现事务已经复核 Intent、Learning Item、时效与唯一槽位，并写 Session/Context/风险快照/首段/Intent CONVERTED。 |
| 3A `startSession()` 固定 `NONE + UNMONITORED` | 原非监测路径保留。新增**仅 Coordinator 持有效 READY lease 可调用**的受限 monitored-start 事务分支，在同一事务直接写 `FULL + FOCUS`；不调用旧路径后再升级。共享原有校验与插入逻辑，避免两套转换规则。 |
| `SessionFocusContextEntity.pollIntervalMillis` 现有 Kotlin 默认 `2_000` | v4 已有字段；3B 新建 Session 显式传 `1_000`，不改 Entity 默认/Schema，不追改旧记录。运行中的 cadence 从该 Session 快照读取；失败降级 Session 同样可以保留 1 秒配置但不启动 poller。 |
| `FocusRepository.transition/markMonitoringLost/updateHeartbeat` 与 3A 状态机 | 复用事务边界和 Segment 合法性；只增加监测证据所需的最小 Repository/DAO 命令。`markMonitoringLost` 需测试起点同刻、重复信号、已结束 Session 与最后可信边界。 |
| `DefaultAppContainer.startup` 先 `recoverInterruptedSession()` | 恢复顺序须在同一 bootstrap 加入 Mirra-owned DND reconcile：遗留 Session 保持 `ABNORMAL`，从持久化最后可信 heartbeat 起补 `UNMONITORED`；不后台启动 FGS。随后清理/停用仅 Mirra 自己的 DND 效果。 |
| Manifest 当前没有监测/DND/通知权限或 Service；`targetSdk=37` | 3B 才按版本加入最少声明。不能把其他 App 的 Manifest 或其常驻服务模式照搬。 |

风险 App 已有 `packageName` 主键和 `labelSnapshot`，Session 开始时已有不可变快照；3B 不再建表。3A 的 `FocusEventType.RISK_APP_BRIEF_VISIT / RISK_APP_CONFIRMED / PERMISSION_LOST` 已可存相应事实。Start 六级状态、阅读页码、Phase 2 Analytics 不变。

## 2. Capability 架构与权限 UX

在 `platform/focus/` 建窄接口，统一把 Android API 结果变为 `Available / NeedsUserAction / Unavailable(reason)`，并由 `MonitoringCapabilityManager` 提供只读 `StateFlow<MonitoringCapabilities>`。ViewModel 仅收状态和发命令，不持有 `UsageStatsManager`、`NotificationManager` 或 `AppOpsManager`。

| Capability | 检查与用户动作 | 运行中撤销 / 向上暴露 |
|---|---|---|
| `UsageAccessCapability` | Manifest `PACKAGE_USAGE_STATS`；`AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS, uid, package)` 检查允许，再以一次 `queryEvents` 成功与否确认实际可用；空结果≠拒权。可见 Activity 引导 `ACTION_USAGE_ACCESS_SETTINGS`，返回后复查。`UserManager.isUserUnlocked()` 只区分 Direct Boot 用户存储未解锁，不把普通锁屏误认为撤权。 | 约 1 秒查询中观察异常/null，Activity `onResume`/设置页返回时复查；失败立即发 `MonitoringUnavailable`，取消候选并标记 gap；不靠单次授权缓存宣称持续可用。 |
| `NotificationCapability` | API 33+ 请求 `POST_NOTIFICATIONS`，API 26+ 建稳定 FGS channel 并检查 channel 是否被用户禁用；旧版本无需运行时请求。 | `onResume` 与启动/提示前刷新。拒权时 FGS 仍可运行，但普通通知栏提示不可视为送达；3B 仅显示 FGS 持续通知，不实现干预 fallback。 |
| `DndCapability` / `DndController` | `NotificationManager.isNotificationPolicyAccessGranted`；可见 Activity 打开 `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`，用户明确选择是否启用。无授权则 Session 正常开始。 | 应用/关闭前重新检查，捕获 `SecurityException`，更新 context 的 `dndLifecycle`，UI 展示“勿扰未开启”或“需检查系统设置”；不改 coverage。 |
| `MonitoringCapabilityManager` | 组合上述状态及 service/UsageMonitor 实际 READY；`preflight()` 不直接改变业务事实。 | 状态包含 `CHECKING / READY / DEGRADED` 和原因；诊断可看细节，普通 UI 只显示必要解释。 |

Usage Access 与 DND 均不是普通危险权限弹窗：只从明确用户操作打开系统设置，不反复强推；权限拒绝后保留继续学习入口。首次引导必须说明读取“当前打开的 App/屏幕状态”用途，不声称读取微信内容或通知内容。

## 3. FocusMonitoringService：一个有限生命周期容器

- 由可见 `PreparationScreen` 上用户明确点击“我已拿起书，开始阅读”才调用 `startForegroundService`；不从 boot、receiver、WorkManager、后台定时器或 `START_STICKY` 自启。Service `exported=false`，`START_NOT_STICKY`；启动后及时 `startForeground`，失败/超时由 Coordinator 降级。Session 正常结束或异常放弃时停止 poller/receiver、停止 foreground 并 `stopSelf`；DND 由独立 Controller 收尾。
- Android 14+ 当前用例初拟 `specialUse`：Manifest 声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`foregroundServiceType="specialUse"` 与清楚描述“用户主动开始的实体书学习期间持续观察前台 App，以提示分心”的 subtype；实施前对 targetSdk 37、渠道和 Play 审核再校验。不得用 `shortService`（阅读可能超过其短时限）、`systemExempted` 或伪装 media/health。API 23–33 沿用兼容 FGS 路径。[官方 FGS types](https://developer.android.com/about/versions/14/changes/fgs-types-required)。
- API 26+ 创建低打扰、不可用于营销的持续状态 channel；通知明确显示“Mirra 正在监测本次学习”，提供返回 App/停止入口，停止动作必须走受控 Session 结束或监测降级路径，不能仅杀 Service 而保留虚假 FULL。通知权限拒绝时不把 Task Manager 内的 FGS 项当作普通通知已送达。[Android 13 行为](https://developer.android.com/about/versions/13/behavior-changes-13)。
- Service 负责 foreground ack、单一 `UsageMonitor` IO 协程、屏幕/锁屏信号接收、15 秒 heartbeat 调度、状态上报与停止；**不**直接实现 Candidate、Distraction、Recovery 业务状态机。任何重复 start 带同一 generation 幂等；不同 active generation 拒绝而不是开第二个 poller。
- Android 13+ Task Manager Stop 不给正常回调；Force Stop、reboot、进程死亡也不保证清理代码运行。下次用户打开 Mirra 后 bootstrap 从持久化 heartbeat 保守补缺口、结束遗留 Session 为 ABNORMAL、处理 Mirra-owned DND；不后台续监。[官方 Stop 行为](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)。

## 4. UsageMonitor、Observation 与有序 reducer

**采集:** active Session 约每 1 秒 IO 查询 `queryEvents(begin,end)`；API 35+ 可使用 `UsageEventsQuery` 限定 Activity resume/pause、screen/keyguard 事件，低版本用时间区间重载。`begin` 为上次成功查询 wall cursor 减一个**有界重叠窗**（初拟 3 秒），`end` 为本次 wall now（exclusive）；同一事件以 `(timestamp,type,package,class)` 去重，重叠窗长度和 OEM 延迟必须真机验证。API 返回 null/抛异常、Direct Boot 用户存储锁定与成功空结果分别建模。系统聚合 `queryUsageStats` 不能用于此刻前台判断。[官方 UsageStats API](https://developer.android.com/reference/android/app/usage/UsageStatsManager)。

**时钟:** `queryEvents` 区间和事件 timestamp 必须用 wall epoch，运行时 1 秒调度、READY lease TTL、握手 timeout、heartbeat deadline、6 秒缺口、candidate 10 秒一律用 `SystemClock.elapsedRealtime()`。每次查询保留 `(wallNow, elapsedNow)` 配对；wall clock 跳变/事件晚于合理窗口时使 observation UNKNOWN、废弃候选并降级，不把 wall 时间差直接当持续前台。跨 boot 不比较旧 elapsedRealtime。持久化 Session/Segment/Event/heartbeat 的时间仍是 wall timestamp。

**模型:** `ForegroundObservation = Package(name, observedAtElapsed, sourceEventAtWall) | ScreenOff | DeviceLocked | Unknown(reason) | MonitoringUnavailable(reason)`。另外保留查询成功与否、cursor 和 freshness，不把“此轮无事件”直接变为 PACKAGE。`ForegroundObservationReducer` 以事件时间顺序折叠，处理重复 RESUMED、PAUSED/STOPPED 迟到、同 timestamp 冲突、跨包切换与缺失 stop；无可信事件顺序或超过有界 freshness 时给 UNKNOWN，而非使用旧 `activeApps.firstOrNull()`。系统 Launcher、SystemUI、设置/权限页只作为中性过渡，不自动认作风险包；锁屏/息屏是可观测状态，**unlock 不等于 distraction**。

**连续性:** 已知 PACKAGE 只在初始可信 RESUMED 之后、屏幕仍交互、连续成功且覆盖的增量查询无反证、且 observation 未过期时维持；空结果不能从 UNKNOWN 创造 PACKAGE，也不能无限延长旧包。查询失败或事件窗无法衔接立即输出 UNKNOWN/UNAVAILABLE；若形成真实未知区间，落 `UNMONITORED` 并使 FULL 永久降 PARTIAL。约 1 秒只是采样节拍，不是 Android 事件即时性保证。

## 5. 风险 App 目录、候选与事实记录

- 复用 `risk_apps(packageName PRIMARY KEY,labelSnapshot,createdAt,updatedAt)` 与 Session 不可变快照。用户从**可见、可启动**的 App 列表显式选风险 App；Android 11+ 包可见性受限，优先针对 `ACTION_MAIN/CATEGORY_LAUNCHER` 的窄 `<queries>`，不得为图省事声明 `QUERY_ALL_PACKAGES`。只能识别可见子集时 UI 说明限制；不能宣称列出设备所有 App。[官方包可见性](https://developer.android.com/training/package-visibility/declaring)。
- `packageName` 为身份，label 仅显示快照，图标按当前包即时读取并使用平台/Coil 现有缓存，不持久化 icon。卸载/禁用后配置保留但标示不可用，重新安装同 package 时刷新 label 并让用户确认是否仍为目标；不自动根据标签或图标匹配新包。Mirra 自身、Launcher、SystemUI、权限设置及不可安全干预的关键系统 App 不能加入风险集合；默认不预选微信、抖音或其他 App。
- 纯领域 `CandidateRiskAppMachine` 输入有序 observation 与 elapsed 时间：首次可信风险包 → `Candidate(package, firstSeenElapsed)`；同包且持续可信 → 延续；换另一风险包 → 旧候选取消/记录 brief，新候选从零开始；转普通/系统过渡包 → 清除候选；`ScreenOff/DeviceLocked` → 清除候选，不将解锁计分心；UNKNOWN、查询失败、撤权或临时事件缺口 → 清除候选并通知 coverage 处理。只有持续约 10 秒、至少一次后续成功覆盖查询支持、无相反事件/缺口，才 `Confirmed`；**不按 10 次 tick**。不足 10 秒记录 `RISK_APP_BRIEF_VISIT`，确认后记录 `RISK_APP_CONFIRMED`，相同 candidate 幂等。
- 确认后在 Room 事务内由 FOCUS/DEEP_FOCUS 切到 `DISTRACTION`，边界采用可信候选起点并满足 3A 边界/乱序约束；迟到到无法安全改写已闭合段时不倒填旧时段，保守标记缺口。风险 App 可信退出时可仅开始已有 `RECOVERY` Segment（表示等待恢复，不代表成功）；**3B 不判定 90 秒成功、不展示恢复干预、不计算 effective 指标**。这避免风险 App 已离开后仍把整段伪记为 FOCUS 或 DISTRACTION；3C 才实现恢复成功与用户引导。

## 6. Monitored Session Start handshake 与事务

`SessionStartCoordinator.start(intentId,startPage)` 是 PreparationViewModel 唯一调用点；内部 Mutex 防重复点击，同一 Intent 再次调用只能返回已有匹配 Session 或明确拒绝，不能重复监测或重复插入。UI 显示短暂“正在准备分心监测”，超时仍允许继续阅读。

首版工程超时建议为**用户点击后最多 5 秒等待 READY**；用户可更早选择“不监测，继续学习”。这是可测试的等待上限，不是 Session 时长或产品成功阈值；须以真机 P95 重新验收。握手协程若因 ViewModel 销毁/导航取消，必须在 `finally` 中安全停止未绑定 Session 的 FGS，不能遗留孤儿服务；Activity recreate 后从仍 Active 的 Intent 重新发起即可，不继承已失效 lease。

```text
用户明确点击 Start（不产生 sessionStartedAt）
→ preflight Usage Access + 可查询性；DND/通知可选，不作为 READY 条件
→ 启动 FGS 并取得实际 foreground ack；loop、cursor、首次成功 query、heartbeat 建立
→ 生成含 generation、readyAtWall、readyAtElapsed、cursor、lastQueryAtElapsed 的 Ready lease
→ 在受限 Room 事务前复核 generation、capability、连续查询覆盖与 monotonic lease 期限
→ 同一 Room 事务复核 Intent 未结束/超时、Learning Item IN_PROGRESS、无 Active Session、起始页合法
→ 用 readyAtWall 原子插入 Session、FocusContext(FULL,poll=1000,lastHeartbeatAt=readyAtWall)、风险 App 快照、初始 FOCUS Segment；同事务 markConverted(Intent)
→ 绑定 sessionId/generation 到运行中的 monitor，并核对 READY→commit→首个 post-commit poll 的连续性
```

READY **不要求前台 package**；锁屏/息屏下首次成功查询可以为空，但 service/loop/cursor/heartbeat 必须可证明。READY lease 与事务之间 monitor 一直运行，暂存该区间观察证据；若监测在事务前失效，不执行 FULL 分支，而走原有普通 `startSession()` 创建 `NONE + UNMONITORED`。权限缺失、FGS 启动失败或 handshake timeout 同理降级，且停止未绑定的 Service。若 Intent 已超时或已有别的 Session，则这是业务拒绝，不得错误兜底创建新 Session。若 DB 事务失败，停止孤儿监测；若 commit 后绑定或首轮校验失败，按最后可信点（最坏为 `sessionStartedAt`）在事务中记录 `UNMONITORED` 并 `FULL→PARTIAL`，绝不删除 Session/重写起点来制造 FULL。

**原子边界:** Session、FocusContext、风险快照、首段、Intent CONVERTED 必须在一个 `withTransaction`。Service start、system permission、DND apply、notification channel 不能置于 Room 事务内。保留现有 `StudyWorkflowRepository.startSession()` 非监测分支；新增受限 `startMonitoredSession(intentId,startPage,readyLease)`，把校验/创建合并到同一内部函数以避免两套业务逻辑。`readyLease` 只能由 Coordinator 所持 monitor generation 产生，事务调用前和提交后均校验。无法实现严格跨 Service/SQLite 原子性时，用 generation、持续观察缓冲、失败补偿和保守 PARTIAL 获得可恢复的最终一致；不宣称跨系统事务。

## 7. Heartbeat、监测缺口与进程恢复

- poller 约每 1 秒在内存更新 `lastSuccessfulObservationElapsed`、cursor、observed state；**不每秒写 Room**。`FocusRepository.updateHeartbeat()` 每 15 秒且仅在查询仍可信时写入 wall timestamp；Session 起点先在创建事务写首 heartbeat。每次写单调推进、不越 Session 边界。15 秒写入间隔与 6 秒实时 observation gap 是不同用途。
- 活进程中，查询失败/null、撤权、服务非正常停止或事件序列不可解释时立即撤销 candidate 并标 `UNMONITORED`；poller 无成功观测超过 6 秒时由 watchdog 从**最后可信边界**标 gap。多次丢失幂等，只保留一条 open UNMONITORED；即便后续监测恢复也只能保持 PARTIAL，再从可信恢复时刻开启后续 FOCUS。不能把缺口“补证”为 Focus。`UNMONITORED` 不能计有效专注。
- 进程死亡/Task Manager Stop/Force Stop/reboot 后无正常回调。下次启动复用 3A `recoverInterruptedSession()`：以持久化 `lastHeartbeatAt`（可能早于内存最后查询，故保守）截断可信 Segment，后段记 UNMONITORED、Session `ABNORMAL`、不推进页码；随后仅清理 Mirra-owned DND，**不**启动 FGS 或恢复 Active Session。该恢复必须先于普通 UI 依赖 Active Session 进行。
- 若 Session 正常结束事务已提交、但 DND release 前进程死亡，下一次启动还须扫描持久化 `dndLifecycle = ACTIVE/RELEASE_PENDING/RELEASE_FAILED` 的已结束 context 并幂等重试；不能只检查 Active Session，否则会留下 Mirra 自己的 DND 规则。
- `SystemClock.elapsedRealtime` 跨进程或设备重启不持久化为可信 duration；wall clock 跳变、boot 变化、Event 时间逆序均导致保守 Unknown。若当前 `markMonitoringLost()` 无法处理“当前 Segment 起点即失监”的零长度边界，测试先固定预期，再仅作最小事务修正，绝不保存零长 Segment。

## 8. DND ownership 与正常结束

- API 35+：有 policy access 且用户启用时，使用 Mirra-owned `AutomaticZenRule`；持久化可重复取得的 own rule ID，再激活。Session context 的 `dndRuleId/dndLifecycle` 记录该次关联。结束/异常启动恢复只停用 Mirra 自己的 rule，**不**调用“把全局 DND 改为 ALL”、不修改用户睡眠/会议或别的 App rule。用户在系统设置主动停用规则时，不强行重新启用。[Android 15 行为](https://developer.android.com/about/versions/15/behavior-changes-15)。
- API 23–34：保存 Session 前 interruption filter 到现有 `priorDndInterruptionFilter`，写入意图状态后再调用旧 API；正常结束仅在当前 filter 仍等于 Mirra 当时施加值、且未观察到用户改变/ownership 不确定时才恢复快照。用户中途手动改 DND 时保留新值；进程死亡导致 ownership 无法证明时宁可提示检查系统设置，不盲写全局。系统调用失败/撤权捕获并标 `APPLY_FAILED/RELEASE_FAILED`，保持 Session 正常可结束。
- 3B 尚无 3D Closeout：**暂在既有 `finishSession()` 正常提交后**执行 Mirra DND release，并在失败时提供重试/系统设置入口；不得在保存 Session 的 Room 事务中调用系统 API。3D 获授权后再把 release 时点移至 Closeout Complete。此阶段必须保证 App 已结束 Session 时不留下 Mirra 施加的 DND。DND 状态写入与外部调用用幂等重试补偿，尤其覆盖“规则已激活、DB ACTIVE 写入前崩溃”。
- DND 不作为 Monitoring READY 必需条件，撤销 DND access 不降 coverage；撤销 Usage Access 才产生监测缺口。

## 9. Notification、设置与 Diagnostics UI

- Preparation 增加有限的 capability 引导与“本次不监测也继续”文案；握手期间只禁重复提交，不增加假进度条。缺权时从可见 Activity 打开系统 Usage/DND 设置，返回后刷新，不强迫用户授权。Session 顶部只显示“分心监测中 / 本次未监测 / 监测已中断”的轻量状态；Start 六级状态、Focus Card、底部导航和既有阅读/Note 流程不改。
- 风险 App 选择放在轻量 Focus Settings（Preparation 的次级入口，必要时从 Session 可返回设置）；只展示可见 launchable App、已选项、不可用项。没有 3C 干预页、倒计时/摩擦页、Overlay 或复杂 Dashboard。所有 UI 用现有 Mirra Theme/Token/Components；不写死品牌色。
- `MonitoringDiagnostics` 只在 debuggable 构建/入口可达，本地只读 `StateFlow`：Service STOPPED/STARTING/RUNNING/READY、Usage/DND/Notification 状态、last heartbeat/query/Event、event cursor、observed package/state、candidate package 与 elapsed、当前 Segment/Coverage、gap 起点/原因、最后一次 Service 错误。正常用户页面不暴露原始包轨迹；不上传、不额外持久化浏览历史。

## 10. Android 版本矩阵与 OEM 风险

| Android | UsageStats / 包可见性 | FGS / Notification | DND |
|---|---|---|---|
| 8–10（API 26–29；工程另覆盖 min 23–25） | `queryEvents(begin,end)`、特殊访问设置；API 23–25 同逻辑无通知 channel。 | API 26+ 必建 channel；用户可从系统停止或限制服务。 | 旧 interruption filter + ownership-safe restore。 |
| 11–12（API 30–32） | Android 11+ 包可见性过滤；Direct Boot 用户存储未解锁时 `queryEvents` 可为 null，普通息屏不等于该状态。 | Android 12+ 后台启动 FGS 受限，只从可见用户动作启动。 | 同旧 API 路径。 |
| 13（API 33） | 同上。 | `POST_NOTIFICATIONS` 拒绝后 FGS 可运行但普通通知栏不显示其 notice；Task Manager Stop 不回调。 | 同旧 API 路径。 |
| 14（API 34） | 同上。 | target 34+ 必须声明合法 FGS type/对应权限；拟用 `specialUse` 并提交用例描述。 | 当前 3B 方案仍走旧 API ownership-safe 路径。 |
| 15+（API 35–37） | API 35+ 可使用 `UsageEventsQuery` 按事件类型收窄；失败时不能偷换聚合 `queryUsageStats`。 | 延续 FGS type、后台启动与通知限制；按 target 37 实测。 | 只管理 own `AutomaticZenRule`，不恢复全局 DND。 |

OEM 验收不得把 AOSP 模拟器结论外推：Pixel 作为基准；小米、OPPO/ColorOS、vivo、Samsung 分别记录权限设置入口差异、FGS 生存/用户清理、UsageEvent 延迟、锁屏行为、DND 多规则合并与电池优化。**不**因某厂商限制而要求 Accessibility、全量包权限或电池优化白名单作为使用前提；无法证明覆盖则降 PARTIAL/NONE。真机缺席的品牌明确标 `Not Run`，不伪报通过。

## 11. 预计文件与职责（非提前创建许可）

| 文件 | 预计动作 / 单一职责 |
|---|---|
| `app/src/main/java/com/guanyi/mirra/domain/monitoring/MonitoringModels.kt`、`ForegroundObservationReducer.kt`、`CandidateRiskAppMachine.kt` | 纯 Kotlin observation、时钟化候选及有限未知语义。 |
| `app/src/main/java/com/guanyi/mirra/domain/SessionStartCoordinator.kt` | 唯一 monitored-start 编排、lease/generation、超时降级与失败补偿。 |
| `app/src/main/java/com/guanyi/mirra/platform/focus/UsageAccessCapability.kt`、`NotificationCapability.kt`、`DndController.kt`、`MonitoringCapabilityManager.kt` | Android API 封装与权限状态；不含 Room/Compose。 |
| `app/src/main/java/com/guanyi/mirra/platform/focus/FocusMonitoringService.kt`、`UsageMonitor.kt`、`FocusNotificationController.kt`、`RiskAppCatalog.kt`、`MonitoringDiagnostics.kt` | 一个 FGS、事件采集、通知、窄包目录和 debug 诊断。 |
| `app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`、`FocusRepository.kt`、`app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt`、`app/src/main/java/com/guanyi/mirra/domain/SessionManager.kt` | 受限 FULL 启动事务、heartbeat/gap、风险确认事实和 DND lifecycle 最小更新；不改变旧业务规则。 |
| `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`、`app/src/main/java/com/guanyi/mirra/feature/session/PreparationScreen.kt`、`SessionScreen.kt`、`app/src/main/java/com/guanyi/mirra/MirraApp.kt`、`navigation/` 中必要 route | 手动 wiring、UI 状态/导航及 bootstrap reconcile。 |
| `app/src/main/AndroidManifest.xml`、`app/src/main/res/values/strings.xml` 与必要通知资源 | 最小特殊权限、FGS type/subtype、channel/用户文案；不引入 Overlay 或 Accessibility 声明。 |
| `app/src/main/java/com/guanyi/mirra/feature/focus/FocusSettingsScreen.kt`、`RiskAppSettingsScreen.kt`、`MonitoringDiagnosticsScreen.kt` | 轻量设置/风险选择与仅 debuggable 可见诊断；若单文件足够可合并，不建立新设计系统。 |
| `app/src/test/java/com/guanyi/mirra/domain/monitoring/ForegroundObservationReducerTest.kt`、`CandidateRiskAppMachineTest.kt`、`SessionStartCoordinatorTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeBRepositoryTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/ModuleThreeBPlatformUiTest.kt` | JVM 纯规则、Room 事务、Service/权限、Compose 与回归。 |

**不修改** `FocusEntities.kt`、`MirraDatabase.version`、`app/schemas/.../4.json` 或已有 Migration，除非实施测试发现本计划前提错误并先重新取得数据设计授权。

## 12. 可验收的实施顺序（每步先失败测试，再最小实现）

- [ ] **Task 1｜纯领域证据链：**先测有序 reducer（重复/乱序/空查询/锁屏/包切换）和 Candidate（单调 9.9s 不确认、10s 有持续证据确认、时钟回拨无影响、Unknown 取消）；实现 `MonitoringModels`、reducer 和 candidate machine。只产 JVM 结果，不接系统 API。
- [ ] **Task 2｜Capability/FGS 骨架：**先用 Android fake 验证 granted/denied/revoked/null query、FGS ack/超时/重复 generation、Notification denied 的展示语义；再加 Manifest、系统 adapter、Service 和 debuggable diagnostics。Service 不插入 Session。
- [ ] **Task 3｜Monitored start：**先写 Room 测试证明 FULL 事务五项原子、Intent 事务内复核、单 Active Session/Segment、READY 失效走 NONE、DB 失败无孤儿 Session；再实现 Coordinator 与同一 Repository 内受限分支，最后接 Preparation loading/降级 UI。
- [ ] **Task 4｜Risk/heartbeat/gap：**先测 15 秒写放大上限、6 秒缺口、重复 loss、FULL→PARTIAL 不可恢复、风险确认/brief fact、撤权及 3A 段边界；再连接 UsageMonitor、RiskAppCatalog、FocusRepository。风险退出仅开始 RECOVERY 事实，不写成功 milestone。
- [ ] **Task 5｜DND/收尾与恢复：**先用 fake 验证 API 35 own-rule、API 23–34 user-change-safe restore、权限拒绝/撤销、crash 各阶段；再接 Controller、现有 finish 路径及 bootstrap。没有权限仍可完成 Session。
- [ ] **Task 6｜回归与设备验收：**对全量 JVM/Room/Compose/Instrumented、lintDebug、assembleDebug、APK 覆盖安装、离线/冷启动、Phase 1/2/3A 与 Visual Parity 回归；逐台真实设备记录版本、厂商、结果与未覆盖项。确认 Schema v4 文件 hash 未变、无 Migration、无 3C UI 或 effective 指标后方可提交 3B 实施报告。

每步相关测试先在未实现接口时失败，再以最小代码通过。执行命令基线：`./gradlew testDebugUnitTest`、`./gradlew connectedDebugAndroidTest`、`./gradlew lintDebug assembleDebug`（Windows 可用 `gradlew.bat`）；每步只跑受影响测试，全量命令留到第 6 步。真机脚本只辅助操作，不把手工权限/DND 结果伪装成自动化测试。

## 13. 测试矩阵与验收场景

| 层级 | 必测断言 |
|---|---|
| JVM | Reducer：重复 RESUMED、late PAUSED、同 timestamp 冲突、空查询、失败查询、锁屏、Launcher/SystemUI/Settings；Candidate：首次、同包延续、切包、Unknown、息屏、撤权、9.9s/10s、tick 丢失、wall clock 修改；Handshake：READY 不需 package、缺 FGS/query/cursor/heartbeat 任一不能 READY、lease 过期、generation 不匹配、用户取消。 |
| Room | FULL 起点 Session/Context/FOCUS/快照/Intent 一事务；NONE fallback、重复点击、Intent 超时/Active 冲突/书状态改变；DB 失败后无部分写入；risk brief/confirmed 幂等与合法切段；heartbeat 单调、6 秒 gap、起点同刻丢失、重复丢失、已结束 Session 拒绝、PARTIAL 不升 FULL；v1→v4 历史数据仍可读且 v4 Schema 未改。 |
| Instrumented / Compose | API 23/29/33/34/35/37 适用路径；Service foreground ack/停止、权限拒绝/撤销、channel/通知展示状态、Activity 返回后 capability 刷新、Preparation loading/降级、风险 App 设置、debug 入口仅 debuggable、Session 状态条；DND 模拟器只证明平台调用路径，不代替真机 ownership 结论。 |
| 真机 | 息屏读实体书与解锁不算分心；前台包未知仍可 READY；微信/抖音**仅在用户手动选为风险 App 后**验证 brief<10s 与持续≥10s；切包/系统设置中断候选；Session 中撤 Usage Access；kill Mirra、Task Manager Stop、Force Stop、reboot 后不续监且遗留 Session ABNORMAL/UNMONITORED；用户中途手动修改 DND 不被覆盖；通知拒绝时不宣称普通通知送达；电池/耗电与 UsageEvent 延迟。 |

模拟器可验证事务、界面、AOSP 权限/Service 分支；真实 UsageEvent 延迟、OEM 杀进程、厂商后台限制、DND 多规则并存、通知可见性及锁屏一致性**必须**真实设备验证。测试记录使用机器、Android/API、targetSdk、授权状态、是否锁屏和观察到的时延；未运行写 `Not Run`，不能用纯 JVM 或模拟器结论代替。

## 14. Module 3B 最终验收标准

1. 有效 READY 在 Session 起点原子创建 `FULL + FOCUS`，且不要求当时有前台 package；点击到 READY 的等待时间不计 Session。不可用时不阻止阅读，创建 `NONE + UNMONITORED` 并轻提示。
2. 所有核心计时 deadline 用单调时间；候选需要持续可观测约 10 秒，解锁/系统过渡/短暂风险访问不算分心；重复/迟到事件不会伪造持续前台。
3. FGS 只由明确用户动作启动；一个 Session 一个 monitor generation，15 秒以内仅按 heartbeat cadence 写 Room，不逐秒写。Service/Usage 失效从最后可信点记 UNMONITORED，FULL 永久降 PARTIAL。
4. DND 有权限才启用；API 35+ 只撤销 Mirra-owned rule，旧版本仅 ownership-safe restore；权限或系统失败不影响 Session 保存。Service/Task Manager/强停后不后台复活，下一次启动按 ABNORMAL 恢复。
5. 风险 App 仅显式选择，配置与快照正确；事实事件及必要 Segment 正确，**无** 3C progressive friction、temporary allowance 流程、Recovery 成功判定、Overlay 干预或 effective 指标。
6. Mirra Blue/Visual Parity、Start 六级状态、Intent/Session/Note/图片/Topic/搜索/Analytics、Phase 1/2/3A 自动化回归与断网阅读闭环通过；Schema v4/`4.json` 未变，未新增 Migration。
7. 输出 JVM、Room、Instrumented、Compose、lint、assemble、APK/冷启动及真机实测证据，报告未执行项和 OEM 限制；仅在用户另行授权的实施回合提交/Push，并在完成 3B 后停止。

## 15. 过度设计检查与待验事实

一个 FGS、一个 reducer、一个候选机、一个启动协调器、现有 Room 表足够；不引入事件总线、持久化原始 UsageEvent 流、全量 App 清单缓存、后台复活框架、第三方监控 SDK、第二套 Focus 状态机或新 Analytics 表。Developer Diagnostics 仅用于本地 debug，不是用户功能。

本规划中的 `specialUse` 是**按当前官方类别对用例的工程推断**，不是 Play 审核已获批准；1 秒轮询/3 秒重叠窗/6 秒缺口也须以真机延迟、耗电、锁屏与 OEM 数据验收。若平台不准许该 FGS 类型，或已确认设备无法证明从 READY 起连续监测，实施报告必须诚实降级/暂停相应 FULL 能力，而不能换名声明成功。

**本回合状态：仅计划。未修改业务代码、Manifest、Room、Migration、权限、设备状态；未执行 3B 测试，也未获 3B 编码授权。**
