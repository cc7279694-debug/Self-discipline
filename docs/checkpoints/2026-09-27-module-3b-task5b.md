# Module 3B Task 5B｜DND Settings / Permission UI / Diagnostics

## What Was Built

- 在“我的”新增“学习保护”入口，使用既有 DataStore `dnd_enabled`，默认关闭。
- 开关只影响下一次 Session；当前 Active Session 不因设置切换而 apply、release 或改变 coverage/lifecycle。
- 权限状态映射为“关闭 / 已就绪 / 需要系统授权”，缺失授权可跳转 Android `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`，返回前台会重新检查。
- API 23–28、29–34、35+ 展示与 Task 5A 兼容矩阵一致的克制说明。
- 活动 Session 显示本次 DND 状态；`APPLY_FAILED` 可通过冻结 Controller 重试，遗留 release 可通过 `reconcileAfterRecovery()` 重试。
- 开发版 Monitoring Diagnostics 增加偏好、policy access、活动 Session、DndLifecycle、prior filter、Mirra rule 与 pending release 信息。
- Preparation 启动文案改为“正在准备本次学习…”。
- Final acceptance patch：当前 Session 的 `APPLY_FAILED` 作为本次已尝试 DND 的持久证据，重试不再受下一次 Session 偏好开关影响；“我的”主内容支持纵向滚动。

## Inherited Constraints

- Room Schema 仍为 v4；无 Entity / Column / Index / Migration 变化。
- 不修改 DND ownership、MonitoringCoverage、SessionStartCoordinator、Usage Access、FGS、Overlay 或 Notification 核心实现。
- UI 通过 `DndUserActions` 薄门面访问应用动作，不直接碰 DAO 或系统 DND API。

## Verification

- JVM：147 项通过，覆盖上述场景以及“当前 Session 为 `APPLY_FAILED` 时关闭下一次偏好后仍可重试”和 `NOT_APPLIED` 不因偏好切换而立即 apply 的回归测试。
- Compose / Instrumented：API 37 全量 `connectedDebugAndroidTest` 共 136 项通过，0 失败、0 错误、0 跳过；其中包含 `DndSettingsProfileTest` 与更新后的 `ModuleThreeBPreparationHandshakeTest`。
- `lintDebug`：通过。
- `assembleDebug`：通过。
- APK 覆盖安装、强停后冷启动、关闭 Wi‑Fi/移动数据后的冷启动均成功，Activity 恢复到 `com.guanyi.mirra/.MainActivity`。
- 全量回归第一次因模拟器 System UI 长时间无响应未形成结果文件；彻底重启同一 API 37 AVD 后重新执行，全量 136 项正常完成并通过。
- `git diff` 未包含 `app/schemas`、Room Entity、Migration 或 `MirraDatabase.version` 变更；Schema 仍为 v4。

## Known Limitations

- Task 5B 不承诺在模拟器上验证真实系统 DND 授权开关和 OEM 行为；实体设备与 OEM 兼容性属于 Task 6。
- 当前 TestAppContainer 使用 Noop DND facade，仅用于 UI 交互测试；生产容器复用真实 `DndController` / `RoomDndStateStore` / `AndroidDndGateway`。

## Next Stage

用户验收 Task 5B 后，另行规划并授权 Task 6；不得自动进入 3C 或 Phase 4。
