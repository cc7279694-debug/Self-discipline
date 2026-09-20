# Module 3B Task 1｜纯领域监测证据链

## Goal

在接入 Android UsageStats、FGS 或 Room 前，先把不可靠系统事件转为保守的前台观察与风险 App 候选事实。

## Verified completed

- `ForegroundObservationReducer` 以事件时间处理 RESUMED、PAUSED、STOPPED、息屏与锁定；重复与迟到事件不会凭空确认前台包，同时间矛盾事件返回 UNKNOWN。
- `lastSuccessfulQueryElapsed` 与 `lastForegroundEvidenceElapsed` 分离。成功空查询维持查询连续性，但重复旧窗口、无关 App 事件不会延长旧包证据；证据过期仅返回 UNKNOWN，不单独产生 monitoring gap。
- 查询失败、事件窗口断裂、wall/elapsed 时钟跳变会清除可信前台状态并输出 GAP / cursor reset 等领域信号。Task 1 不写 Coverage 或 Room。
- `CandidateRiskAppMachine` 以 monotonic elapsed 判断 10 秒，要求后续可信查询、同一风险包和同一 Focus Segment ID；同一 token 只产生一次确认。

## Changes and inherited boundaries

- 业务代码仅新增 `domain/monitoring/` 三个纯 Kotlin 文件及两个 JVM 测试文件；未改变 Room Schema v4、Migration、Manifest、Repository、UI 或 Android 权限。
- 分类器由调用方注入；Mirra、Launcher、SystemUI、设置页等不得被目录层标为风险 App。具体包目录属于后续 Task。
- 前台证据暂采用 20 秒有界新鲜度，wall/elapsed 明显偏差阈值沿用冻结计划的 2 秒；Android/OEM 实际事件延迟仍需后续 Task 真机校准。

## Verification

- `:app:testDebugUnitTest`：75 项通过、0 失败，其中 Task 1 新增 14 项。
- Connected / APK / 真机：Not Run；本 Task 不含 Android 平台集成。

## Next step

Task 1 单独验收后，再单独授权 Module 3B Task 2。不得从本检查点推断监测系统已可用，亦不得把现有 Session 追认为 FULL。
