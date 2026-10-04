# 3C Clock Rollback Core Correction

日期：2026-10-04。分支：`codex/phase-3d-closeout`。修补前 HEAD / plans freeze：`758b3bc163ccc7a187f85594d1c04fbda5ecf7d4`。

## Goal / scope

仅修复已冻结语义的 implementation Bug：wall clock 回退导致真实失监事务回滚，以及随后异常恢复可能写出倒序结束时间。不是 3D 新功能；不继续 3D-1 Task 1–6、3D-2/3/4。

生产改动只有 `FocusRepository.kt`、`StudyWorkflowRepository.kt`。`SessionSegmentStateMachine.kt` 完全不变；没有新增 API、Entity、Column、Index、Migration、依赖或权限，也未修改 DND、READY / FGS、监测架构与任何 3C 阈值。

## RED evidence retained

- 原有 `backwardClockJumpLossIsDurableBeforeCloseout` 保留，并在修改生产代码前再次实际执行：API 37 / AndroidJUnitRunner，1 test / 1 failure / 0 skipped。
- 真实 Controller → RepositoryRuntimeFactsPort → FocusRepository → Room：Session / FOCUS 从 1000 开始，可信 wall 到 2000，再仅将注入 wall 改为 100，elapsed 继续前进。`SessionSegmentStateMachine.monitoringGap()` 抛出 `IllegalArgumentException: 缺口结束时间不能晚于当前时间`。
- 失败后实际读取 Room：`FULL`、`monitoringLostAt=null`、活动 FOCUS 仍从 1000 开始、共 1 个 Segment，证明失监事务回滚，而不是 fake port 推断。
- 新增恢复 / 边界回归后，仍未修改生产代码时完整执行本测试类：12 tests / 8 failures / 0 skipped。其中恢复原本把预期 2000、2500 或 1000 的结束边界写为 100；普通正向失监与恢复对照通过。
- 原始及本轮日志在本地忽略目录 `build/validation/phase-3d-1/` 保留，不提交完整设备日志或序列号。上述 RED 不因最终 GREEN 被改写。

## Minimal correction

1. `markMonitoringLost()` 捕获 `observedNow`，继续要求 `lastTrustedAt <= detectedAt` 且 `detectedAt <= max(observedNow, lastTrustedAt)`。
2. 仅该方法的 `monitoringGap()` 调用使用临时 `state.copy(now=max(observedNow, detectedAt, lastTrustedAt))`。通用 `loadState()`、普通 transition、heartbeat 不共享这个上界；不能把任意未来时间合法化。
3. 异常恢复在同一 Room transaction 读取 Session、活动 Segment 与 Context，以 `max(current wall, Session.startedAt, activeSegment.startedAt, lastHeartbeatAt, monitoringLostAt)` 作为统一恢复终点。nullable durable 项 fallback 到 Session 起点；Intent 超时判断仍使用原始 current wall。
4. 最后可信段结束与恢复终点相同时，不插入零时长 UNMONITORED。活动 UNMONITORED 恰从恢复终点开始时，沿用现有删除零时长活动段逻辑。不制造 1ms、不补 Focus、不恢复 FULL。

## GREEN / verification actually run

| Verification | Actual result |
| --- | --- |
| 指定 JVM：SessionSegmentStateMachineTest / BoundSessionMonitoringControllerTest / FocusSessionActionsTest | 35 passed / 0 failed / 0 errors / 0 skipped |
| 完整未过滤 `:app:testDebugUnitTest`，核对 36 份 JUnit XML | 225 passed / 0 failed / 0 errors / 0 skipped |
| API 37 单次定向 Room / Instrumented，显式指定 Mirra_API_37 | 63 passed / 0 failed / 0 skipped |
| `:app:lintDebug` | PASS；0 errors / 9 existing warnings / 1 hint |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | PASS |
| 指定 AVD 的 App / test APK `install -r` | 成功，无卸载、清数据或系统改时 |
| `git diff --check`、冻结文件差异与 Schema hash | PASS |

指定 JVM 命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SessionSegmentStateMachineTest' --tests '*BoundSessionMonitoringControllerTest' --tests '*FocusSessionActionsTest' :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Room 验证通过显式目标 AVD 上的 `am instrument -w -r -e class ... com.guanyi.mirra.test/androidx.test.runner.AndroidJUnitRunner` 执行，而非全量 Gradle connected：本类 12、ModuleThreeAFocusRepositoryTest 8、StudyWorkflowRepositoryTest 15、ModuleThreeBMonitoredStartRepositoryTest 15、ModuleThreeCBehaviorRepositoryTest 11、MigrationThreeToFourTest 1、MigrationOneToFourTest 1，共 63 项。Runner 实际成功状态 63、失败/跳过状态 0；不以零退出码单独判定通过。

关键结果：

- 原始真实 loss：FOCUS `1000→2000`，open UNMONITORED 从 2000 开始，`PARTIAL / monitoringLostAt=2000`。
- durable rollback recovery：保留闭合 FOCUS `1000→2000`，删除尚未有正时长的 open unknown；Session `ABNORMAL / endedAt=2000`，PARTIAL 不升级，重复恢复不改变结果。
- 普通 wall=4000、heartbeat=2500：FOCUS 到 2500，UNMONITORED `2500→4000`，ABNORMAL 于 4000；既有 `processRecoveryPreservesTrustedPartThenRecordsUnknownGap` 通过。
- heartbeat / 活动段起点 / monitoringLostAt 独立上界、Session 起点、零时长清理、重复 loss、NONE / PARTIAL 保持均通过。
- `ordinaryTransitionStillRejectsBoundaryBeyondCurrentWall`、`backwardLossUpperBoundIsNotAvailableToOrdinaryTransitions`、未知未来 / 倒序 loss 拒绝、原有 future boundary reject 均通过。

## Data freeze / boundaries

`MirraDatabase.version=4`。Schema 1–4 字节与修补前一致；Migration / Entity / Table / Column / Index 不变。v3→v4 及 v1→v4 既有真实 Migration 回归通过，没有新增 Migration。

Schema `4.json` SHA-256：

`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`

本轮没有重新跑全量 connected / Compose、真实设备系统改时、完整手工 UI 闭环、OEM / TalkBack / release：`NOT RUN`。时间回退由注入时钟与真实 Room 验证，不冒称系统改时真机验收；既有全平台未测边界不升级。一加 13T 个人试用仍不替代发布兼容性。

## Handoff

内部只读审查没有发现 Critical / Important，不能替代用户的 GitHub 独立验收。本修补与 checkpoint 同次提交，提交说明为 `fix(focus): preserve trusted boundaries across clock rollback`，只 Push 当前功能分支，不合并 main。提交后核对本地 / 远程 SHA 与 clean worktree。

完成本次窄修补后停止；等待独立复验，不自动执行 3D-1 的任何 Task 或后续交付包。
