# Module 3B Existing Solutions Review

调研日期：2026-09-19。研究结论与第 6 节实施语义已验收并冻结；没有实施 3B 或修改业务代码。

## 0. 证据边界与结论

直接阅读了两个仓库在以下固定提交的源码，而非仅看 README：

- [Mindful `b6eb68d`](https://github.com/akaMrNagar/Mindful/tree/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e)：Flutter + Android Kotlin；仓库 [LICENSE](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/LICENSE) 是 GPL v2。
- [Reef `9ab72ac`](https://github.com/aload0/Reef/tree/9ab72ac171cd61fca08163fe4bd1e2194294840a)：Compose + Android Kotlin；该提交根目录未见仓库级 LICENSE，GitHub `licenseInfo = null`。个别文件有自己的版权/GPL 声明，不能据此推定整库许可。
- Mirra 对照事实：[Schema v4 的 Focus Entities](../../app/src/main/java/com/guanyi/mirra/data/local/entity/FocusEntities.kt)、[FocusRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/FocusRepository.kt)、[StudyWorkflowRepository.startSession](../../app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt)；3A 目前从 `NONE + UNMONITORED` 开始，3B 尚无 Android 监测能力。

**核心结论：**Mindful 的 `queryEvents` 增量追踪与 Service/RestrictionManager 分层值得参考；Reef 的 AppOps 权限检查、API 35 `UsageEventsQuery` 与权限状态展示值得参考。但两者均没有 Mirra 所需的“监测先 ready，后原子创建 FULL Session”握手。Mindful 是 Session 先入库再启动/绑定服务；Reef 是先写运行状态与统计，再提升 FGS。两者的 DND 释放路径也都不满足 Mirra 的 ownership-safe 规则。**不得直接复制任何代码。**

表中 A=可借鉴设计/算法；B=可借鉴 Android API 用法；C=只参考结构、不复制；D=不适合 Mirra；E=当前 Android 目标上已过时或不安全。分类不是许可证授权。

## 1. Mindful：实际实现

### 前台 App / Usage Access

- [`LaunchTrackingManager.findLaunchedApp`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/tracking/LaunchTrackingManager.kt#L100-L146) 在解锁后以 `scheduleWithFixedDelay` **750ms** 轮询 `UsageStatsManager.queryEvents(lastQuery, now)`；`ACTIVITY_RESUMED` 加入 `activeApps`，`ACTIVITY_PAUSED/STOPPED` 移除，首个 active package 变化时回调。锁屏时取消任务、Overlay 与提醒；[`DeviceLockUnlockReceiver`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/receivers/DeviceLockUnlockReceiver.kt#L33-L76) 使用 `ACTION_USER_PRESENT` / `ACTION_SCREEN_OFF`。可借鉴“屏幕状态与 App 事件分离”（B），**不能**把“解锁”本身当分心。
- [`ScreenUsageHelper.fetchUsageInMs`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/usages/ScreenUsageHelper.kt#L34-L84) 查询更长历史以配对 resumed/paused，这是统计用途，不是实时前台判定。`RestrictionManager.evaluateScreenTimeLimit` 使用它计算 App/组限额；Mirra 3B 不应把聚合时长当作连续前台证据（D）。
- 该实时 tracker 没有看到事件 tuple 去重、乱序/延迟重排、空结果与查询失败的区分、明确的事件新鲜度边界或重新授权探测；`lastLaunchedApp` 可跨空事件重用，`activeApps.firstOrNull()` 也未按最新 resume 时间排序。`reInvokeLastLaunchEvent()` 会重发旧 package。[源码](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/tracking/LaunchTrackingManager.kt#L47-L168)。这些模式对 Mirra 的可信 coverage 不可直接采用（D）。
- [`PermissionsHelper.getAndAskUsageAccessPermission`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/device/PermissionsHelper.kt#L107-L130) 以“最近一天聚合使用记录非空”判断授权；“没有历史记录”与“没有权限”会混淆。Mirra 应用 AppOps 检查加真实查询结果双重诊断，而非照搬（D）。

### FGS / DND / 风险 App / 诊断

- [`MindfulTrackerService`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/tracking/MindfulTrackerService.kt#L19-L131) 组合 `LaunchTrackingManager`、`RestrictionManager`、`OverlayManager`、`ReminderManager`；`onStartCommand` 提升 FGS 并返回 `START_STICKY`，组件职责分界可参考（C），其常驻式阻断与自动重启不能照搬（D）。[`AndroidManifest.xml`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/AndroidManifest.xml) 声明 `specialUse`，但 tracker/focus service 没有 Reef 那种具体 subtype 说明，Mirra 不能直接复制 Manifest（E）。
- [`FocusSessionService.startFocusSession`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/timer/FocusSessionService.kt#L65-L105) 先启动 Focus FGS，随后异步 `startAndBind()` tracker，连接回调才更新 focused app；[`focus_mode_provider.startNewSession`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/lib/providers/focus/focus_mode_provider.dart#L210-L226) 更早已插入 Session。因此存在从 DB Session 起点到追踪真正工作之间的潜在缺口；代码未证明首个有效 foreground observation 何时到达（D）。Provider 在启动/恢复时还会尝试重启 Session Service，[参见 `_init` 与生命周期恢复](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/lib/providers/focus/focus_mode_provider.dart#L52-L78)；Mirra 不自动续监。BootReceiver 还调度后台工作，[源码](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/receivers/DeviceBootReceiver.kt#L35-L73)。
- [`NotificationHelper.toggleDnd`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/device/NotificationHelper.kt#L197-L239) 用内部 `DndWakeLock` 避免 Mindful 自己多个功能互抢，但结束时直接 `setInterruptionFilter(ALL)`，未保存/检查用户 Session 前状态，也没有显式 app-owned `AutomaticZenRule`。内部锁不等于系统 DND ownership；Mirra 不采用（E）。[`PermissionsHelper`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/device/PermissionsHelper.kt#L136-L292) 分别检查 Overlay、精确闹钟、电池优化、通知、DND，但不是 Mirra 3B 的打包权限清单。
- 风险/限制以 `AppRestriction.appPackage`、Focus `distractingApps: Set<String>` 与 `RestrictionManager.focusedApps` 按 packageName 匹配，[模型](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/models/AppRestriction.kt#L13-L61) / [管理器](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/services/tracking/RestrictionManager.kt#L16-L91)（A）；安装/卸载监听 [`DeviceAppsChangedReceiver`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/receivers/DeviceAppsChangedReceiver.kt#L14-L74) 可作刷新 UI 的参考（B）。`DeviceAppsHelper` 会读取 label/icon 并做编码，[源码](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/device/DeviceAppsHelper.kt#L17-L99)；Mirra 已有 `risk_apps.labelSnapshot`，无需持久化 icon 或全量包扫描。
- 诊断主要是 Logcat 和 [`SharedPrefsHelper.insertCrashLogToPrefs`](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/android/app/src/main/java/com/mindful/android/helpers/storage/SharedPrefsHelper.kt#L197-L209)；未发现覆盖 last event、heartbeat、coverage 的专用诊断页。日志有原始 package，Mirra 应仅在开发诊断中显示，勿默认保留可识别 App 使用轨迹。

## 2. Reef：实际实现

### 两条不同的“检测”路径

- 真正的即时阻断使用 [`BlockerService.onAccessibilityEvent`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/accessibility/BlockerService.kt#L167-L283)：窗口变化给出 package，500ms 重复检查抑制、丢弃来自非当前窗口的 stale content event；屏幕关闭停止网站追踪，锁屏时跳过事件。此 500ms 是**重复 Accessibility event 去抖**，不是风险 App 连续 10 秒证据。其 `performGlobalAction(HOME)`、浏览器节点解析/重定向和硬阻断都违反 Mirra 边界（D）。
- [`ScreenUsageHelper.calculateUsageFromEvents`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/ScreenUsageHelper.kt#L18-L119) 用 `ACTIVITY_RESUMED/PAUSED` 与前 2 小时 lookback 算历史时长；无 event 便退到 [`queryUsageStats`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/ScreenUsageHelper.kt#L120-L160)。[`queryEvents` 封装](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/ScreenUsageHelper.kt#L164-L181) 在 API 35+ 用 `UsageEventsQuery.Builder` 限定 resumed/paused，并对返回值 `!!`。这适合历史报表优化，不适合用统计 fallback 证明实时前台或判定 FULL（D/E）；API 35 按事件类型过滤的调用方式可参考（B），但 Mirra 还须包含屏幕/锁屏事件，且显式处理 null。
- [`Permissions.hasUsageStatsPermission`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/Permissions.kt#L36-L48) 通过 AppOps 的 `OPSTR_GET_USAGE_STATS` 判断授权，比“返回历史记录非空”更清楚（B）；[`checkAllPermissions`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/Permissions.kt#L77-L145) 将“权限已启用”和 Accessibility service “实际已连接”分开展示，诊断结构可借鉴（A），但 Mirra 不请求 Accessibility。

### FGS / DND / 风险 App / 诊断

- [`MainActivity.startFocusMode`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/MainActivity.kt#L642-L687) 先写 `focus_mode` 等 SharedPreferences，再 `startForegroundService(ACTION_START)`；[`FocusModeService.startTimer`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/accessibility/FocusModeService.kt#L108-L206) 先标记计时/统计开始，后调用 DND 和 `promoteToForeground()`。没有 ready ack、Usage event 基线或 coverage model；不能作为 Mirra 的 FULL 起点（D）。[`Manifest`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/AndroidManifest.xml) 声明 `specialUse` subtype，`ServiceCompat.startForeground` 用 API 34 type，可参考 API 形式（B），但 subtype 是“focus timer”且整个权限包不能照搬。
- [`BootReceiver.restoreFocusMode`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/receivers/BootReceiver.kt#L35-L61) 根据偏好在启动后尝试重启 FGS；Mirra 明确不后台复活 Session（D）。[`FocusModeService`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/accessibility/FocusModeService.kt#L47-L128) 返回 `START_STICKY`；对计时器合理，不代表 Android Task Manager Stop 能回调或保证连续监测。
- [`enableDNDIfNeeded` / `restoreDND`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/accessibility/FocusModeService.kt#L851-L871) 把 previous filter 存在 Service 内存，结束时无“当前值是否仍由自己施加”的比较就写回；进程死亡则快照丢失，用户中途手动改 DND 也可能被覆盖。未发现显式 `AutomaticZenRule`（E）。
- 风险配置以 `AppLimits` 的 packageName→分钟数和 `Whitelist` 的 packageName→Boolean SharedPreferences 保存，[源码](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/AppLimits.kt#L10-L155)。`UsageTracker.shouldSkipPackage` 用系统标志与可启动性过滤；`Whitelist.init` 默认放行大量系统/输入法/电话/Launcher App。它适合强阻断产品的安全白名单，不适合 Mirra 全量抄入（D）。未看到包卸载后清理限额配置的同等显式策略；应把“未安装”与“删除用户配置”分开处理。
- [`BlockerService.connectionState`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/accessibility/BlockerService.kt#L533-L544) 加 [`PermissionsCheckActivity`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/PermissionsCheckActivity.kt#L75-L100) 呈现权限/服务状态（A）。[`DebugActivity`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/DebugActivity.kt) 只显示异常堆栈与复制按钮，**不是**监测事件诊断页。未发现 heartbeat、last UsageEvent、candidate/coverage 的可视化。

## 3. 横向对比与采用分类

| 问题 | Mindful | Reef | Mirra 判断 |
|---|---|---|---|
| 实时前台来源 | `queryEvents` 750ms + 可选 Accessibility | 主要靠 Accessibility window event；UsageStats 用于历史时长 | Mindful 增量事件方式 B；Reef Accessibility D |
| 无/迟到/重复事件 | 未建立可信结果类型；复用 last package | 统计 fallback `queryUsageStats`；Accessibility 500ms 去重 | 两者都不能证明 FULL；自行设计 Unknown（D） |
| 屏幕状态 | unlock 启动轮询，screen off 停轮询 | screen off 停网站计时，keyguard 忽略窗口事件 | 状态区分 B；unlock 不等于 distraction |
| FGS 与 Session 次序 | Session 入库→服务连接/追踪 | 偏好/统计开始→FGS 提升 | 均有启动缺口，不能照搬（D） |
| DND | 自家功能锁，但结束设 `ALL` | 内存 prior filter，无 ownership 比较 | 两者均不满足 Mirra（E） |
| App 目录 | packageName + label/icon；包变化刷新 | packageName 限额/白名单 | packageName 思路 A；不存 icon/不默认全系统白名单 |
| 诊断 | 日志与 crash log | 权限/连接状态、崩溃页 | Reef 状态展示 A；Mirra 补缺口与时序字段 |
| 后台/开机 | WorkManager/Service 恢复 | BootReceiver 重启 FGS | 对 Mirra 都是 D |

`queryUsageStats` 的日/周聚合在时间范围上还可能被系统扩到整 interval，不是“此刻哪个 App 在前台”的证明；`queryEvents` 在 Android R+ **用户存储尚未解锁**（`UserManager.isUserUnlocked() == false`，例如 Direct Boot 阶段）时可返回 null。这不等同于用户日常按电源键锁屏；`null` 与成功返回的空 event stream 也不能合并。[Android `UsageStatsManager` API](https://developer.android.com/reference/android/app/usage/UsageStatsManager)。Android 的 [`UsageEvents.Event`](https://developer.android.com/reference/android/app/usage/UsageEvents.Event) 把 Activity resume/pause、screen interactive/non-interactive、keyguard shown/hidden 分成不同事件；“解锁”不应成为分心结论。

## 4. 对 Mirra 3B 的推荐（不是本回合实施）

### 4.1 Capability 与责任边界

1. `UsageAccessCapability`：AppOps 是否授权、用户存储是否已解锁（区别于普通锁屏）、事件查询是否成功三个状态分别返回；`GRANTED` 不意味着已经有新事件，更不意味着当前 package 已知。界面从可见 Activity 引导到系统设置，Session 中每次轮询和适当间隔复查撤权。
2. `ForegroundAppObserver` / `UsageMonitor`：在用户明确点击“开始学习”后启动的单个 FGS 内运行 IO poller。首版采用约 **1 秒**的 active-session observation loop；相邻查询窗适度重叠并在真机校准，以 `(timestamp, eventType, packageName, className)` 做有界去重、时间有序归并和新鲜度/last-success 游标。API 35 可用限定事件类型的 `UsageEventsQuery`，低版本用 `queryEvents(begin,end)`；不得用 `queryUsageStats` 替代实时观察。成功且空的增量查询、查询 null/抛错、Direct Boot 未解锁、当前 foreground 未知必须是不同结果；foreground 未知本身不阻止 Monitoring READY。普通息屏若有可信的屏幕状态事件，不能仅因没有新的 App 事件就判为监测缺口。
3. `RiskAppMonitor`：消费归一化 observation，与已有 `risk_apps` + Session 快照比对；系统过渡 package（动态 Launcher、SystemUI、Settings/权限页等）为中性，不当作风险 App。候选在首次可信观察时建立；约 10 秒后须有**连续成功查询且无相反前台事件**、至少一次后续刷新证据才确认，时长按 monotonic elapsed duration 而非轮询次数。已知前台状态在连续成功的“无新事件”窗口可保持；不是把一条旧 RESUMED 当作无限期活跃。出现查询缺口/撤权/乱序超窗立即撤销 candidate 并进入 UNMONITORED。若 10 秒内离开，只记 brief visit。Mindful 的 750ms 对 Mirra 收益不足，Reef 的 500ms 不是此处判定阈值。
4. `FocusMonitoringService`：只负责前台通知、observer/poller、screen/keyguard receiver、15 秒 heartbeat 与向 Repository 提交证据/故障；不把系统 API、规则引擎、DB SQL 堆进 Service。`Service.onCreate()` 或已调用 `startForeground()` **不等于** monitor ready。DND、Overlay、Notification 是独立可选 capability；拒绝它们不能阻断 Session 事实记录。
5. `DndCapability`：API 24+ 尽可能管理 Mirra-owned `AutomaticZenRule`，API 35+ 必须如此；Closeout 只撤销自己规则，不改别人的规则。API 23 旧 API 保存 Session 前状态，并且只在当前值仍等于 Mirra 施加值时恢复；用户改过则保留。无权限/调用异常要记录，但绝不写出“恢复成功”的假事实。[Android 15 行为](https://developer.android.com/about/versions/15/behavior-changes-15)。

3B 真正需要：Usage Access、用户动作启动的 FGS 与其合法 type/持续通知；DND 仅用户启用且获授权时使用。Notification runtime permission 影响**可见干预**而非 FGS 能否运行；Overlay 是可选通道，不是 monitored start 的必要条件。Accessibility、VPN、Device Admin、全包可见性、Exact Alarm、Battery Optimization 豁免、Notification Listener、Boot 自动复活属于对照产品的阻断/例程能力，**不得作为 3B 默认申请清单**。Android 14+ FGS 要声明合法 type；`specialUse` 须说明用途且涉及审核，不能只复制另一产品的 subtype。[FGS type](https://developer.android.com/about/versions/14/changes/fgs-types-required)、[Android 13 通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

### 4.2 Monitored Session Start Handshake（已冻结的 3B 实施约束）

以下是 Mirra **自己的**建议；两项目都没有这套保证。`FULL` 是“从 Session 起点起有连续、可信的监测覆盖”，不是“用户必定一直专注”；初始 `FOCUS` 也仍须按后续证据转段。不能以 FGS 创建成功、权限显示 granted 或旧 package 缓存单独宣称 ready。

```text
用户在可见界面明确开始
  → 检查 Usage Access、UserManager 用户存储解锁状态；缺失则保留现有非监测 Session 路径
  → 启动/提升监测 FGS，开启屏幕状态接收和事件 poller（尚无 Session）
  → FGS 实际运行、Usage Access 有效、UsageMonitor loop 和 event cursor 已建立、queryEvents 可正常访问、heartbeat 已开始 → READY
  → READY 不要求已观察到 foreground package；锁屏、息屏或当前 package UNKNOWN 仍可 READY
  → Service 返回带 generation、last successful query、event cursor、heartbeat 与当前观察状态的 Ready lease
  → 启动协调器串行化 Ready lease 与 Room 的 Intent→Session 事务
  → 以 Monitoring READY 成立的时间作为 sessionStartedAt，在受限事务内原子写 Session、FocusContext FULL、首段 FOCUS、风险 App 快照、Intent CONVERTED
  → 把 sessionId 绑定到已运行 poller；核对从 sessionStartedAt 至首个 post-commit poll 无缺口
  → 若 lease 在事务前失效：不创建假 FULL，降级原有 NONE+UNMONITORED 路径
  → 若 commit 后、绑定/首轮核对前失败：从 sessionStartedAt 或最后可信点记 UNMONITORED，FULL→PARTIAL，不能删除已发生缺口
  → 若权限缺失/被拒、FGS 失败或握手超时：仍允许学习开始，创建普通 NONE+UNMONITORED Session，并轻量告知本次不监测手机分心
```

实现上需要一个新的**受限的 monitored-start 事务入口**或对现有 `startSession` 的内部事务分支；复用唯一 Active Intent/Session/Segment 约束和现有 3A 表，**原则上不需 Schema v5**。用户点击时间不是 `sessionStartedAt`；READY 前的时间不计入 Session，也不得事后回填。事务提交前须复核 Ready lease 仍有效、监测从 READY 时间持续覆盖；不能证明时走 `NONE+UNMONITORED`。`NONE→FULL` 不是事后状态转换：只有在同一创建事务内直接初始化 FULL，且握手在该事务前真正 ready 才允许。已由旧路径产生 `NONE+UNMONITORED` 的 Session 永不改写起点；后续最多 PARTIAL。事务开始/结束与 poller 之间的狭小竞态应由单一 coordinator/lease generation、持续事件缓存和失败补偿守住；如果某设备无法验证连续覆盖，就宁可让这场 Session 不取得 FULL。这里的“首次观察耗时”两项目均无可引用保证，必须用 API 23/33/35/37 真机实测 P50/P95，再决定 ready 超时与 UI 文案。

### 4.3 gap、候选、heartbeat 与诊断

- 沿用 3A 的 `maxObservationGapMillis = 6000`、`heartbeatMillis = 15000`、`riskConfirmMillis = 10000`；约 1 秒 poll 不是 Android 事件实时 SLA。handshake timeout、heartbeat 期限、候选持续时间和 10 秒确认按 `elapsedRealtime` 等单调时钟计算；wall clock 只用于持久化历史时间和展示。每次 poll 记录 `lastQuerySuccessAt`、最大已处理 event timestamp 与已知 screen state；若连续成功查询覆盖时间窗，可维持已知前台状态；查询失败/null/权限撤销/事件窗不能衔接，则从**最后可信时刻**切 UNMONITORED，FULL 永久降 PARTIAL。跨重启不拼接单调时间，进程重启按 3A 的遗留 Session → ABNORMAL 处理。
- Candidate→confirmed 可在确认时于同一事务按 firstSeenAt 切段，但仅在这 10 秒候选区间证据完整、无后续状态冲突且 3A `transition` 边界合法时；迟到事件超出有限重排窗不追改旧 Segment，保守标 Unknown。不能因为 `queryUsageStats` 给出时长就“补证”。
- Developer Diagnostics 首版只读、开发构建可见：Usage Access/AppOps、User unlocked、FGS 实际 foreground ack、通知是否获准且通知是否可见、Overlay/DND capability、最后 query 成功时刻、最后事件类型/时间、当前 observed package 与观察年龄、candidate package/持续证据时间、active Segment/coverage、last heartbeat、monitoring gap 起点/原因、最近一次 FGS 错误。敏感 package 信息只在本机临时展示，不上传、不额外保存浏览历史。Reef 的“权限授予 vs Service 已连接”分层可直接借鉴表达方式。
- Force Stop / Task Manager Stop / reboot 不后台复活 FGS；下次打开 Mirra 检查 gap、保留已可信 Segment、其后 UNMONITORED + ABNORMAL，并清理 Mirra-owned DND。Android 13 Task Manager Stop **不提供正常回调**；Android 12+ 后台 FGS 启动有限制，故 `START_STICKY` 不是可靠恢复方案。[官方 Stop 行为](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。

### 4.4 建议复用/新增的最小类边界

| 现有 Mirra 归属 | 3B 所需补充（仅建议） | 不应承担 |
|---|---|---|
| `StudyWorkflowRepository.startSession` / `SessionManager` | 内部受限 monitored-start 事务入口；仍统一 Intent→Session、唯一槽位与风险快照 | 由 UI 分多次写 Session、Context、Segment |
| `FocusRepository` / `FocusDao` | 复用 `updateHeartbeat`、`markMonitoringLost`、Segment 事务；增加必要的原子起点契约 | 直接查询 Android 系统服务 |
| `SessionSegmentStateMachine` | 消费归一化的证据与 gap，不改变 3A 已冻结的 segment/coverage 语义 | 用计时器推断未知时间为 FOCUS |
| 尚无对应类 | `SessionStartCoordinator`、`MonitoringCapabilityManager`、`FocusMonitoringService`、`UsageMonitor`、`ForegroundObservationReducer`、`RiskAppMonitor`、`DndController`、`MonitoringDiagnostics` | 建事件总线、把 FGS 写成业务状态机或为每个系统能力建独立 FGS |
| 现有 Compose/手动 AppContainer | 一页只读 Developer Diagnostics，展示 capability、event、candidate、coverage 时间戳 | 存储/上传全量 App 使用历史 |

这只是职责划分，不是提前创建空接口、数据库表或代码的许可。

## 5. License 与复用界限

- Mindful 固定提交根目录确有 GPL v2 正文，GitHub 识别为 GPL-2.0。理解其事件窗口、职责边界和 Android API 形式，不等于复制受保护的源码。若将其表达性代码直接并入/改写后分发 Mirra，需事先评估 GPLv2 兼容性、保留版权与许可文本、提供对应源代码及下游复制/修改权利等义务；商业发行并不免除。不能假设仅复制一小段就自动安全。[Mindful LICENSE](https://github.com/akaMrNagar/Mindful/blob/b6eb68d500463c54e1127b6cc7ad540f02ae0c8e/LICENSE)、[GNU GPLv2 FAQ](https://www.gnu.org/licenses/old-licenses/gpl-2.0-faq.en.html)。
- Reef 固定提交没有根目录 LICENSE，GitHub API 也不识别仓库级许可；[`TimeColumnChart.kt`](https://github.com/aload0/Reef/blob/9ab72ac171cd61fca08163fe4bd1e2194294840a/Reef/src/main/java/dev/pranav/reef/util/TimeColumnChart.kt) 有单文件 GPL 版权说明，但不能据此推定全仓库 GPL。公开可读**不等于**获得复制/改编/分发授权；默认不复制 Reef 源码，需要时向权利人确认授权或做专业法律审查。[GitHub licensing 指南](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)。
- 本研究只记录事实与思路，没有纳入第三方源码；本段是工程风险提示，不是法律意见。

## 6. 已冻结的 3B 实施决策与待验证项

以下六项已验收并锁定，后续 3B 计划/实现须继承：

1. **Monitoring-ready 定义 Session 起点**：用户点击“开始学习”先做 capability preflight、启动监测服务并取得 READY；仅在 READY lease 仍有效、从 READY 起连续受监测时，受限 Room 事务才原子创建 `Session + FULL + 初始 FOCUS`。`sessionStartedAt` 采用 Monitoring READY 时刻，不采用点击时间，也不回填等待握手的时段。旧路径已产生的 `NONE+UNMONITORED` 不得事后追认为 FULL。
2. **READY 不要求已识别前台 package**：READY 必须证明 FGS 已运行、Usage Access 有效、UsageMonitor loop 已建立、`queryEvents` 可正常访问、event cursor 已建立、heartbeat 已开始。锁屏、息屏或当前 foreground UNKNOWN 不妨碍 READY；但 READY 也不等于把未知前台硬判为 Focus 的观测证据。
3. **监测是可选能力**：权限缺失/拒绝、FGS 失败或握手超时都不阻止学习；创建普通 `NONE+UNMONITORED` Session，UI 轻量告知本次无法监测手机分心，不伪造 FULL。
4. **运行时使用单调时间**：handshake timeout、heartbeat deadline、candidate duration、风险 App 连续约 10 秒确认使用 `elapsedRealtime` 一类 monotonic clock；wall clock 仅用于持久化历史时间和用户展示。进程重启后不得跨 boot 拼接单调时长。
5. **首版约 1 秒观察节拍**：不复制 Mindful 的 750ms；本决策取代早期 Phase 3 计划中提出的 2 秒初始节拍。Candidate 以真实 elapsed duration 和连续可观测证据确认，不以轮询次数确认；节拍只在真机测得事件延迟、耗电或 OEM 行为证据后调整。6 秒观察缺口、10 秒风险确认沿用已冻结阈值，不把 poll 周期当作系统事件 SLA。
6. **Coverage 与 Segment 正交**：`FULL` 表示整个 Session 时间轴有可信监测覆盖，不表示整段都是 FOCUS。`FULL` 可以包含 FOCUS、DEEP_FOCUS、BREAK、TEMPORARY_ALLOWANCE、DISTRACTION、RECOVERY 等已可信判定的段；`PARTIAL/NONE` 描述覆盖完整度，Segment 描述状态。UNMONITORED 或不可解释缺口不算 Focus，FULL 出现真实缺口后只能降 PARTIAL，不能追回。

不复制 Mindful 的 `mutableList<String> activeApps + firstOrNull()`：Mirra 将 UsageEvent 归并为**时间有序 Observation 流**，由 `ForegroundObservationReducer` 推导 observed package 或 UNKNOWN，并显式处理重复 RESUMED、迟到事件和 OEM 异常。推荐职责链为 `SessionStartCoordinator → MonitoringCapabilityManager → FocusMonitoringService (FGS) → UsageMonitor → ForegroundObservationReducer → RiskAppMonitor → SessionSegmentStateMachine (3A)`；FGS 只承载生命周期、heartbeat 和 capability，不承载业务状态机。旁路为 `DndController` 和只读 `MonitoringDiagnostics`。开发诊断至少展示 Service RUNNING/READY、Usage Access、最后 heartbeat/UsageEvent、observed package、candidate package 与时长、当前 Segment/Coverage、DND 状态和 monitoring gap；不上传或额外持久化 App 使用轨迹。

实施前仍须验证：实际 targetSdk/minSdk 和发布渠道下的 FGS type、`specialUse`、通知与 Play 审核要求；DND ownership；风险 App 卸载/重装身份；首次 post-commit poll 失败与 lease 失效补偿。真机应量化事件延迟、锁屏/解锁、系统设置切换、OEM 省电与耗电，不以尚未验证的厂商行为扩大 FULL 判定。

验证建议：纯 JVM 的事件归并、去重、乱序、candidate/Unknown、lease generation 和时钟边界；Room 事务测试验证 FULL 起点与失败退化；模拟器覆盖 API 版本和权限撤销；**真机必测** Usage event 延迟、息屏/解锁、Task Manager Stop、OEM 后台限制、DND 多规则合并与 notification 可见性。此文档不是 3B 开工授权。
