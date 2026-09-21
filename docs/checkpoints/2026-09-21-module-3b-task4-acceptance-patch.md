# Module 3B Task 4｜Durable-Loss Acceptance Patch

## Problem and fix

原通知停止路径先销毁 Service，随后才在 `onDestroy` 异步写入 monitoring loss；正常 Session 结束事务可能抢先提交，使真实失监后的 Session 错留 FULL。

已绑定 Session 的通知停止、诊断停止及已知权限/查询/看门狗故障现在都先等待 `markMonitoringLost` 的 Room 事务完成，之后才停止 Service。受控写入失败时不继续停 Service，且正常结束会被失监失败栅栏阻止；可以重试失监写入。正常结束则先提交 Session，再释放绑定监测，不制造 UNMONITORED。控制器用同一互斥边界串行化失监与结束；`onDestroy` 只保留不可预期销毁的 best-effort 兜底。

## Verification

- JVM：122 项通过；包含重复失监、写入失败阻止虚假 FULL 及成功重试。
- API 37 设备测试：131 项通过；Room 测试覆盖停止先于结束、紧邻竞态、正常结束、重复销毁及权限撤销的保守落库。
- `lintDebug`、`assembleDebug` 通过；APK 覆盖安装及冷启动通过。
- 模拟器真实通知栏“停止监测”后结束 Session：最终 `NORMAL + PARTIAL`，FOCUS 与 UNMONITORED 连续覆盖至结束时刻。
- Room Schema 仍为 v4，未新增 Migration、Entity、Table、Column 或 Index。

## Boundary

本 patch 不含 Task 5、DND、Stable Start、Recovery 成功或有效专注指标。无生命周期回调的进程死亡仍由既有冷启动异常恢复处理；实体 OEM 真机验证留待 Task 6。
