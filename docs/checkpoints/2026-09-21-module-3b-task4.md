# Module 3B Task 4｜Runtime Monitoring Facts

## Goal

把 Task 3 已绑定的 monitored Session 接入持续 Usage observation，在不伪造行为或覆盖完整性的前提下记录风险 App 短访/确认、`DISTRACTION → RECOVERY`、周期 heartbeat 与失监缺口。

## What was built

- `BoundSessionMonitoringController` 串联 binding generation、UsageMonitor snapshot、纯 Candidate 状态机与 FocusRepository。Service 只负责 Android 生命周期、查询和 watchdog，不直接写业务事实。
- Candidate 保存真实 UsageEvent wall 起点、elapsed 起点、generation、token 与来源 Focus Segment。可信短访产生 brief Event；约 10 秒确认会在 Room 事务复核 Session、风险快照与未切换的 Focus Segment。Segment 的分心起点与确认 Event 的发生时刻分别表示实际开始与系统确认。
- 可信退出风险 App 开始关联原 DISTRACTION 的 RECOVERY；Task 4 不自动完成 Recovery，也不自动记录 Stable Start。
- 约 15 秒成功查询 heartbeat、6 秒查询连续性 watchdog、权限中途撤销、Service 主动/异常停止和时钟跳变均有保守失监路径。失监从最后可信边界写 `PARTIAL + UNMONITORED`；成功空查询不会误判 gap，也不会无限延长旧包前台状态。
- 开发版“我的”增加最小风险 App 选择与本地诊断。只列可见可启动 App；当前 Session 固定使用启动快照。
- API 37 模拟器实测发现并修复同一 App 内旧 Activity 停止事件覆盖新 Activity 前台证据的问题；补了回归测试。绑定后的“停止监测测试”现可真正停止 Service 并让当前 Session 降级。

## Data and boundaries

- Room Schema 仍为 v4；`4.json` 未变化，无 Entity、Migration 或数据库版本变更。
- 风险访问事实、heartbeat 和覆盖度属于本地数据；不上传、不持久化完整 App 浏览轨迹。
- Task 5 的 DND ownership、3C 干预、90 秒 Recovery 成功、Stable Start 自动判定及 effective 指标均未实现。中途重新授权不自动恢复当前 Session 监测，覆盖度保持 PARTIAL。

## Verification

- JVM：121 项通过，0 失败，包含候选、查询/Activity reducer、controller 与 3A/Task 3 回归。
- `:app:connectedDebugAndroidTest`：API 37 模拟器 127 项通过，0 失败，包含 Room 事务、平台与 Compose 回归。
- `:app:lintDebug`、`:app:assembleDebug`：通过。
- 模拟器覆盖安装与完全离线冷启动通过。真实 Chrome 持续打开记录 `RISK_APP_CONFIRMED`，FOCUS 在可信候选起点关闭，DISTRACTION 从同边界开始；返回 Mirra 后进入关联的 RECOVERY。另一场 Session 短访 Chrome 只记录 brief，未切成 DISTRACTION。
- 在 FULL Session 中，成功空查询下 heartbeat 持续推进而无 gap；运行中撤销 Usage Access、在开发诊断里主动停止监测，分别验证立即 `PARTIAL + UNMONITORED`。强停/冷启动仍按既有 `ABNORMAL/PARTIAL` 恢复。
- OEM 实体真机：Not Run；按冻结计划留到 Task 6。

## Next step

先由用户独立验收 Task 4；Task 5 需单独授权。不得把 Task 4 的风险事实链当作 DND/干预或有效专注指标已交付。
