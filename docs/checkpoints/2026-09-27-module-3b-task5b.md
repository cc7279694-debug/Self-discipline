# Module 3B Task 5B｜DND Settings / Permission UI / Diagnostics

## What Was Built

- 在“我的”新增“学习保护”入口，使用既有 DataStore `dnd_enabled`，默认关闭。
- 开关只影响下一次 Session；当前 Active Session 不因设置切换而 apply、release 或改变 coverage/lifecycle。
- 权限状态映射为“关闭 / 已就绪 / 需要系统授权”，缺失授权可跳转 Android `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`，返回前台会重新检查。
- API 23–28、29–34、35+ 展示与 Task 5A 兼容矩阵一致的克制说明。
- 活动 Session 显示本次 DND 状态；`APPLY_FAILED` 可通过冻结 Controller 重试，遗留 release 可通过 `reconcileAfterRecovery()` 重试。
- 开发版 Monitoring Diagnostics 增加偏好、policy access、活动 Session、DndLifecycle、prior filter、Mirra rule 与 pending release 信息。
- Preparation 启动文案改为“正在准备本次学习…”。

## Inherited Constraints

- Room Schema 仍为 v4；无 Entity / Column / Index / Migration 变化。
- 不修改 DND ownership、MonitoringCoverage、SessionStartCoordinator、Usage Access、FGS、Overlay 或 Notification 核心实现。
- UI 通过 `DndUserActions` 薄门面访问应用动作，不直接碰 DAO 或系统 DND API。

## Verification

- JVM：145 项通过，覆盖 DND 默认值、权限状态、DataStore 持久化、Activity resume 重新检查、当前 Session 延迟生效、apply/reconcile 重试、API 文案。
- Compose / Instrumented：`DndSettingsProfileTest` 与 `ModuleThreeBPreparationHandshakeTest` 在 API 37 模拟器各通过 1 项。
- `lintDebug`：通过。
- `assembleDebug`：通过。
- APK 覆盖安装、强停后冷启动、关闭 Wi‑Fi/移动数据后的冷启动均成功，Activity 恢复到 `com.guanyi.mirra/.MainActivity`。
- 全量 `connectedDebugAndroidTest` 曾在 API 37 模拟器长回归时因系统出现 “System UI isn't responding” 而未形成结果文件；重启模拟器后已分项重跑本次变更相关设备测试并通过，不能将那次全量运行计为通过。
- `git diff` 未包含 `app/schemas`、Room Entity、Migration 或 `MirraDatabase.version` 变更；Schema 仍为 v4。

## Known Limitations

- Task 5B 不承诺在模拟器上验证真实系统 DND 授权开关和 OEM 行为；实体设备与 OEM 兼容性属于 Task 6。
- 当前 TestAppContainer 使用 Noop DND facade，仅用于 UI 交互测试；生产容器复用真实 `DndController` / `RoomDndStateStore` / `AndroidDndGateway`。

## Next Stage

用户验收 Task 5B 后，另行规划并授权 Task 6；不得自动进入 3C 或 Phase 4。
