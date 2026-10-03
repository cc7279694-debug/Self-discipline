# 观已 Mirra｜Module 3B Task 6B / 6C 验证记录

日期：2026-09-27  
验证分支：`codex/phase-3b-task6-validation`  
Task 6A 协议 commit：`676350fb707a67348d2dc354a8db3992c7e0ed7b`  
实现基线：`c058f2e46e04739307d37e96e5fc16f9e677ee7d`  

## 结论摘要

- JVM 回归：PASS，147/147。
- API 37 AOSP 模拟器全量 connected 回归：两次均为 136 项中 1 项 Compose 时序失败；失败用例各自单独重试均通过，不能将全量命令记为 PASS，交 Task 6C 判断。
- lintDebug：PASS。
- assembleDebug：PASS。
- APK 覆盖安装、普通启动、断网冷启动、Force Stop 后手动重开：PASS（API 37 AOSP 模拟器）。
- Task Manager Stop、reboot、真实 Monitoring 行为、DND 交互和实体 OEM：NOT RUN。
- 本回合没有修改 `app/`、Manifest、Gradle、Room、Schema 或 Migration。

## 自动化回归

| 检查 | 结果 | 证据/备注 |
|---|---|---|
| `:app:testDebugUnitTest --no-daemon` | PASS | 147 tests, 0 failures, 0 errors, 0 skipped |
| 第一次 `:app:connectedDebugAndroidTest --no-daemon` | FAIL | 136 tests；`ModuleTwoAFlowTest#createNoteDoesNotPersistBlankPlaceholder`，Compose 未在 60 秒内 idle |
| 第一次失败用例单独重试 | PASS | 目标测试通过 |
| 第二次 `:app:connectedDebugAndroidTest --no-daemon` | FAIL | 136 tests；`PhaseOneCorrectionTest#finishingImmediatelyFlushesDraft`，Compose 条件 5 秒未满足 |
| 第二次失败用例单独重试 | PASS | 目标测试通过 |
| `:app:lintDebug --no-daemon` | PASS | 无 lint failure |
| `:app:assembleDebug --no-daemon` | PASS | Debug APK 生成 |

两次全量失败均为不同的 Compose/UI 时序超时，尚未进行根因归类；Task 6B 不修复代码。

## 工具链与设备

设备使用匿名别名：`aosp-api37-avd`。未记录设备序列号。

| 项目 | 实际值 |
|---|---|
| JDK | Temurin 17.0.20.1+1 |
| Gradle Wrapper | Gradle 9.7.1 |
| ADB | 1.0.41 / 37.0.1-15733141 |
| Emulator | 37.1.11.0 |
| 设备 | Google `sdk_gphone64_x86_64` AOSP emulator |
| Android | 17 / API 37 |
| build | `sdk_gphone64_x86_64-userdebug 17 CE2A.260420.019` |
| Mirra | `0.1.0`, targetSdk 37, minSdk 23 |

## 安装与生命周期

| 场景 | 结果 | 备注 |
|---|---|---|
| APK 覆盖安装 | PASS | `adb install -r` 成功，未清除数据 |
| 普通冷启动 | PASS | `MainActivity` 获得焦点 |
| 断网冷启动 | PASS | 关闭 Wi-Fi/移动数据后启动，随后恢复设备网络设置 |
| Force Stop 后手动重开 | PASS | 强停后无进程，手动打开后 `MainActivity` 恢复 |
| Active monitored Session 的 ABNORMAL/UNMONITORED 恢复 | NOT RUN | 本回合未建立真实 monitored Session |
| Android 13+ Task Manager Stop | NOT RUN | 需要系统 UI 人工操作 |
| reboot 后恢复 | NOT RUN | 本回合未重启模拟器 |

## Monitoring 与 DND

M1–M11 真实行为协议：`NOT RUN`。本回合只有 AOSP API 37 自动化回归，没有进行 Usage Access 授权、真实 FGS monitored Session、锁屏风险 App 访问、权限中途撤销或 Service 清理实验。

D1–D11 DND 行为协议：`NOT RUN`。没有执行正式用户设置入口、系统 DND 授权、AutomaticZenRule 手动 override 或 API 分支交互验证。

API 23、29、33、34、35 模拟器与 Xiaomi/OPPO/vivo/Samsung 实体设备：`NOT RUN`。

## Schema 与数据安全

- `MirraDatabase.version = 4`。
- Migration 集合未变化：`MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4`。
- Schema hash 与 Task 6A 冻结值一致：
  - `1.json` `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1`
  - `2.json` `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D`
  - `3.json` `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205`
  - `4.json` `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`
- 未清除真实用户数据库；本回合没有数据库写入或迁移。

## Task 6C 交接项

1. 分析两次全量 connected 的不同 Compose 时序超时是否为模拟器负载/测试稳定性问题，还是存在可复现代码根因。
2. 复核单独重试通过不能替代全量回归 PASS 的验收语义。
3. 在没有实体设备前，API 23–35、OEM 后台限制、Task Manager Stop、reboot 与真实 DND/Monitoring 能力继续保持 `NOT RUN`。

Task 6B 未修改业务代码，未进入 Task 6D 或 Phase 3C。

## Task 6C 诊断结论（2026-09-28）

### 失败定位

- `ModuleTwoAFlowTest#createNoteDoesNotPersistBlankPlaceholder` 的全量失败发生在测试内容刚进入 `setContent` 时，尚未执行该用例的 Note 创建断言。
- `PhaseOneCorrectionTest#finishingImmediatelyFlushesDraft` 的全量失败发生在等待 Session 页面的“快速笔记”前置条件时，尚未执行该用例真正验证的草稿 flush 断言。
- 两个失败来自不同测试、不同等待点，均未形成稳定的业务级复现。
- 两个目标用例单独重试均通过；随后各自所在测试类整类运行也均通过：
  - `ModuleTwoAFlowTest`：PASS，6/6。
  - `PhaseOneCorrectionTest`：PASS。
- 两个测试都使用新的 in-memory Room `TestAppContainer` 与 fake/no-op monitoring，不依赖真实 Usage Access、FGS 或 DND。

### 环境复核

- 第三次全量 connected 回归在执行 2 个测试后中断，ADB 报告 `Transport endpoint is not connected`，随后 package service 不可用；该次结果属于模拟器传输/系统环境失败，不属于测试断言失败。
- 随后执行无快照冷启动。4 分钟内没有建立 ADB 设备，模拟器进程退出，因此没有产生可归因于 Mirra 的测试结果。
- 在模拟器环境重新稳定前，没有把局部通过替代为“全量 connected PASS”。

### 分类

当前证据更支持：

> API 37 AOSP 模拟器及 connected/Compose 测试执行环境存在非确定性时序与传输稳定性问题；尚无证据确认 Mirra 生产代码 Bug。

理由：失败点均早于目标业务断言、单测重试和整类回归均通过、第三次运行出现明确 ADB transport failure，且冷启动设备本身未能建立。

这不是 Module 3B 最终全绿结论。完整 `connectedDebugAndroidTest` 仍未得到一次干净 PASS，Task Manager Stop、reboot、真实 Monitoring/DND 与 OEM 真机项目仍为 `NOT RUN`。

### 后续边界

- Task 6C 未修改生产代码、测试代码、Manifest、Gradle、Room 或 Schema。
- 当前不授权 Task 6D；不基于偶发超时修改业务实现。
- 若后续授权测试基础设施稳定化，可单独评估 instrumentation process isolation、动画控制和更稳健的页面 ready 条件；这些不是本轮已确认修复方案。
- 在稳定 AVD 或可用实体设备上，应从头重新执行完整 connected 回归和对应设备协议。

## Task 6B-Retry 稳定环境复验（2026-09-28，Asia/Shanghai）

### 环境状态与重要偏差

- 仓库仍在 `codex/phase-3b-task6-validation`；HEAD 为 `676350fb707a67348d2dc354a8db3992c7e0ed7b`，实现基线为 `c058f2e46e04739307d37e96e5fc16f9e677ee7d`。
- 初始 ADB 无在线设备且没有残留 emulator 进程；执行 `adb kill-server` / `adb start-server` 后启动 `Mirra_API_37`。
- **操作偏差：启动命令误带 `-wipe-data`。** 这清除了 `Mirra_API_37` 的全部虚拟设备本地状态；用户数据仅限该 AVD，不涉及实体设备或仓库文件。此操作未获授权，明确记录为本轮执行错误。它使既有 App 数据、权限和系统设置不能用于覆盖安装保留数据验收；不得将本轮 fresh install 描述为覆盖安装 PASS，也不得把该环境操作归因于 Mirra 代码。
- wipe 后启动初期 ADB shell 的 `window` / `package` service 尚未就绪，稍后 `sys.boot_completed=1`；随后连续 5 次、每次间隔 5 秒的 `adb devices` 均报告设备在线。System UI PID 存在，焦点为 AOSP Launcher，API 37，Android 17，build fingerprint `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`。环境达到本轮 connected 回归启动门槛。
- 工具链：Temurin JDK `17.0.20.1+1`、Gradle `9.7.1`、Android Gradle Plugin `9.4.0`、ADB `1.0.41 / 37.0.1-15733141`、Emulator `37.1.11.0`。

### 自动化结果

| 检查 | 本轮结果 | 证据与边界 |
|---|---|---|
| `:app:testDebugUnitTest --no-daemon` 首次执行 | BUILD SUCCESSFUL，但 `UP-TO-DATE` | 未视为新测试运行结果。 |
| `:app:testDebugUnitTest --rerun-tasks --no-daemon` | PASS | 27 suites，147 tests，0 failures / 0 errors / 0 skipped；测试任务实际执行。 |
| `:app:connectedDebugAndroidTest --no-daemon` | PASS | 单次完整 connected 运行，API 37 AOSP AVD，136 tests，0 failures / 0 errors / 0 skipped；Gradle `BUILD SUCCESSFUL in 5m 24s`。本轮没有局部重跑。Room migration tests 包含在该 136 项结果内。 |
| `:app:lintDebug :app:assembleDebug --no-daemon` | PASS | `BUILD SUCCESSFUL in 33s`；lint task 实际执行，assemble 目标为 `UP-TO-DATE`。 |

本轮日志：`docs/checkpoints/assets/module-3b-task6/aosp-api37-avd/6b-retry-unit-rerun.log`、`6b-retry-connected-full.log`、`6b-retry-lint-assemble.log`。首次 JVM 调用的缓存结果记录在 `6b-retry-unit.log`，不得与实际 rerun 计数混淆。

### AOSP 设备项

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| AVD 稳定性门槛 | AOSP actual PASS | `sys.boot_completed=1`，5 次连续 ADB device 状态，System UI 存活且 Launcher 获焦。 |
| Mirra 安装与启动 | AOSP actual PASS（fresh install） | `adb install -r` 成功；`am start -W` 返回 `Status: ok`、`LaunchState: COLD`，启动 `com.guanyi.mirra/.MainActivity`。由于先前 AVD wipe，这不是保留旧数据的覆盖安装验收。 |
| 覆盖安装与既有数据保留 | NOT RUN | wipe 已清空 AVD 数据，无法验证旧用户数据保留。 |
| 断网冷启动 | NOT RUN | 试图执行时 connected 流程结束后的 AVD 上无法解析普通 Mirra launcher activity（`Activity class ... does not exist`），未形成可靠的 Mirra 离线启动证据。Wi-Fi 与 mobile data 开关已恢复到操作前的 enabled 状态；不将此记为 Mirra FAIL 或 PASS。 |
| Force Stop、Task Manager Stop、reboot 生命周期恢复 | NOT RUN | 没有在本轮建立真实 Active monitored Session；没有将普通强停或自动化测试代替这些场景。 |
| M1–M11 Monitoring、D1–D11 DND | NOT RUN | 未做 Usage Access、真实监测 Session、权限撤销、风险 App、DND 授权/override 等交互验证。 |
| API 23/29/33/34/35 模拟器、Android 实体设备及 OEM | NOT RUN | 当前只有 API 37 AOSP AVD；无实体/OEM 设备证据。 |

这次 connected 全量 PASS 是当前 AOSP API 37 自动化证据，不推导 OEM/真机能力通过，也不覆盖上述 NOT RUN 项。

### Schema、文案与代码边界复核

- `MirraDatabase.version = 4`；应用装配的 Migration 链仍为 `MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4`。
- Schema hash 与冻结值完全一致：
  - `1.json` `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1`
  - `2.json` `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D`
  - `3.json` `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205`
  - `4.json` `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`
- Preparation 文案仍为“正在准备本次学习…”。
- 本轮未修改生产代码、测试代码、Manifest、Gradle、Room、Schema 或 Migration；未清理 AVD 后再声称数据迁移通过。

### 结论与停止点

- 分类：**clean full automated PASS**（JVM 147/147、connected 136/136）；**AOSP actual PASS**（稳定性门槛、fresh install、普通冷启动）；**environment issue**（未经授权的 `-wipe-data` 清除 AVD 状态，导致既有数据覆盖验收失效）；其他场景保持 **NOT RUN**。
- 没有业务断言失败、没有触发 `[SOL_REVIEW_REQUIRED]`；也没有把 AVD 问题标为 Mirra FAIL。Task 6C 早先记录的 connected 偶发超时仍是历史事实，本轮这次全量结果单独记为 PASS，不覆盖之前的失败记录。
- 本轮只新增/更新验证 checkpoint 与脱敏验证日志；没有 commit 或 push。结束时工作区中 `app/` 等生产/测试代码无差异；Task 6 checkpoint 与 assets 目录仍为既有未跟踪文档内容。
- Task Manager、reboot、真实 Monitoring/DND 与 OEM 实机验收仍未完成；停止于 Task 6B-Retry，不进入 Task 6D 或 Phase 3C。

## Task 6B Final Evidence Completion（2026-09-28）

### 自动化回归（引用现有干净证据）

本次不重复运行已有的完整回归：

| 检查 | 结果 | 证据 |
|---|---|---|
| JVM Unit | PASS，147/147 | `assets/module-3b-task6/aosp-api37-avd/6b-retry-unit-rerun.log`；任务实际执行，0 failure/error/skipped。 |
| API 37 AOSP connected | PASS，136/136 | `assets/module-3b-task6/aosp-api37-avd/6b-retry-connected-full.log`；单次完整运行，0 failure/error/skipped。早先两次 Compose 时序失败与第三次 ADB transport failure 仍按历史记录保留。 |
| lintDebug | PASS | `assets/module-3b-task6/aosp-api37-avd/6b-retry-lint-assemble.log`。 |
| assembleDebug | PASS | 同一日志；任务当时为 `UP-TO-DATE`，未表述为重新编译。 |
| Room / Migration 自动化 | PASS（自动化范围） | 已包含在 136 项 connected 结果；该证据不替代本次设备数据库检查。 |

### APK 生命周期与数据保留

- 当前 AOSP API 37 AVD 的初始 package 清单中没有 `com.guanyi.mirra`。因此先通过获授权的 `adb install -r` 安装现有 Debug APK；这不是来自旧安装/旧版本的数据升级验证。
- 通过 App UI 建立专用记录：Learning Item `Task6B-20260928`、Note `Task6B-note-20260928`、一条正常结束 Session；在 Profile 将“学习时自动开启勿扰”设为开启。未使用 `pm clear`、卸载或 `-wipe-data`。
- 对已有数据的安装执行第二次 `adb install -r`，成功。随后 Force Stop 并手动冷启动，Profile 仍显示正常阅读 1 次、笔记 1 条及勿扰偏好“需要系统授权”；Room 数据与 DataStore 偏好在同版本覆盖安装后仍可见。
- 通过 `PRAGMA user_version` 从安装后真实 `mirra.db` 读取数据库版本 `4`。本次没有执行数据库迁移。
- 断网冷启动：操作前 Wi-Fi 与移动数据均为开启；关闭两者后 `Active default network: none`，Mirra `LaunchState: COLD` 成功启动，Profile 仍显示专用记录；随后 Wi-Fi 与移动数据均恢复开启，系统 default network 恢复。

### AOSP API 37 手工 Monitoring / DND

设备别名 `aosp-api37-avd`，Google `sdk_gphone64_x86_64`，Android 17 / API 37；仅模拟器，不是实体设备。

| 场景 | 结果 | 实际观察 |
|---|---|---|
| Usage Access / M1 READY | PASS（当前路径） | 系统 Usage Access 页面授权 Mirra；`GET_USAGE_STATS: allow`。启动学习后 Room 快照为 `monitoringState=FULL`，ActivityManager 显示 `FocusMonitoringService` 前台运行。未单独量 READY 延迟。 |
| 锁屏 / 解锁 / 空 query（M2–M4） | NOT RUN | 未进行受控锁屏、解锁或多 poll 连续性观察。 |
| 风险 App 短访、长访、退出恢复（M5–M7） | NOT RUN | 没有选择/打开风险 App，也没有计时验证 candidate 阈值。 |
| SystemUI / 设置中间页（M8） | NOT RUN | 权限页操作期间没有正在受测的 candidate。 |
| Usage Access 撤销、Service 手动停止、系统清理（M9–M11） | NOT RUN | 未执行中途权限撤销、App 内 monitor stop 或系统清理复现。 |
| Active FULL Session Force Stop recovery | PASS | Force Stop 前已实测 FULL 快照与前台 FGS；Force Stop 后进程与服务消失，等待 8 秒未自行复活。手动冷启动后 Room 显示原 Session `ABNORMAL`、监测状态降为 `PARTIAL`、活动槽位释放，并写入 `UNMONITORED` 缺口；学习项和既有笔记保留。 |
| DND 授权入口 / Preference | PASS（入口与读取） | API 37 系统 Modes access 中授权 Mirra；返回 Profile 显示“已就绪”。本项只证明入口与授权状态可见，不证明 Session DND lifecycle 成功。 |
| DND Session apply | DEGRADED / 待审查 | 真实 Session 快照显示 DND access at start 已授权（`1`），但 lifecycle 为 `APPLY_FAILED`；系统只观察到 Mirra-owned AutomaticZenRule，`enabled=true`、`STATE_FALSE`，勿扰未实际激活。期间 FGS/monitoring 为 FULL。未执行 retry、手工 override 或第二次 Session。该异常证据提交 Sol High 审查；不能据此宣称已确认代码根因。 |
| DND 正常 finish / release | NOT RUN | 该含 `APPLY_FAILED` 的活动 Session 随后按获授权的 Force Stop recovery 流程结束为 `ABNORMAL`，没有正常结束路径。手动打开恢复后，Room 将 DND lifecycle 收敛为 `RELEASED`；系统可见 Mirra-owned rule 已 `enabled=false`、`STATE_FALSE`。这只是异常恢复后的 ownership 状态，不替代正常 finish/release 验收。 |
| DND multi-rule override、撤权、retry、API 分支（D1–D11） | NOT RUN | 不继续尝试；当前观测点交由 Sol High 分析。 |

`logcat` 当前缓冲区未发现包含 `APPLY_FAILED`、DND/ZenRule、监测 Service 或异常类名的相关行；保留真实 Room 状态、`dumpsys notification` 与 ActivityManager 观察作为证据，不保存完整 logcat。没有发现可据以认定的稳定复现业务代码异常，因此本记录为 `[SOL_REVIEW_REQUIRED]` 的待审查异常观察，不直接宣布 FAIL。

### 实体设备、未运行项与偏差

- Android 13+ 实体设备、Pixel/AOSP 真机、Xiaomi/HyperOS、OPPO/ColorOS、vivo/OriginOS、Samsung/One UI：NOT RUN；本次仅有 API 37 AOSP AVD。
- Task Manager Stop 与 reboot：NOT RUN。
- API 23/29/33/34/35 模拟器版本矩阵：NOT RUN。
- 本轮没有重跑 JVM/connected/lint/build；引用的是既有干净日志。以前误用 `-wipe-data` 清除 AVD 的操作偏差按原文保留，没有删除或改写。
- 执行中未观察到稳定的业务异常可重复复现；DND `APPLY_FAILED` 是一次真实可观察异常，待独立审查。Force Stop 流程已按 Task 6B 授权执行，其后没有再尝试 DND retry/override 或创建第二个 Session。
- 本 Task 仅更新验证文档与脱敏证据，没有修改 App、测试、Manifest、Gradle、Room、Schema、Migration 或产品实现；不进入 Task 6D 或 Phase 3C。

## Task 6D DND Platform Compatibility Acceptance Patch（2026-10-03）

### 根因与修复

- 已确认 API 37 的 `APPLY_FAILED` 来自完整 `rule.zenPolicy == policy()` 比较：Android 可以将 Mirra 故意保留为 UNSET 的继承字段解析后返回，导致完整对象不相等。
- 修复后只比较 Mirra 明确控制的 categories 与 visual effects；不扩大到 calls、alarms、media 等继承字段，也不修改 global Notification Policy。
- API 35+ 激活/释放后立即回读 AutomaticZenRule 真实 state。只有 TRUE/FALSE 目标状态才成功；拒绝、UNKNOWN、ERROR 或不匹配都沿现有控制器落为 `APPLY_FAILED` / `RELEASE_FAILED`。
- conditionId、configurationActivity、owner、rule ID、enabled 与 interruption filter 保护未放宽；非 Mirra rule 不会被操作。

### TDD 与定向证据

- RED：在 API 37 真实授予 DND access 后，原实现的 own-rule 定向测试在 `AndroidDndSystem.kt:78` 以 `User-managed rule policy differs; open system settings` 失败，确认不是 early return。
- GREEN：受控字段比较与 state 回读实现后，`AndroidDndSystemTest` 真实设备路径 3/3 通过：own rule 可复用、受控 policy 改动会被拒绝、非 Mirra rule 不被识别为 own rule。
- JVM fake 回归验证 platform 拒绝 activate/deactivate 不会产生假 `ACTIVE` / `RELEASED`，而是分别落为 `APPLY_FAILED` / `RELEASE_FAILED`。
- manual override 无法在不写系统数据库或使用隐藏 API 的情况下稳定构造，本项保留 `NOT RUN`；不将 fake 测试伪装成手工实测。

### API 37 真实 Session

- DND preference 开启、Usage Access 与 DND access 授权后，通过真实 Start → Preparation → Session 流程创建 Session `9cebfb04-6b93-4614-b4c6-7dfbdee6ca63`。
- 进行中 Room 快照：`activeSlot=1`、`monitoring=FULL`、`dnd=ACTIVE`；系统 Mirra rule `869e7b6172614ea7a2a816a6322f457f` 为 enabled + `STATE_TRUE`。
- 通过 UI 正常结束后 Room：`outcome=NORMAL`、`monitoring=FULL`、`dnd=RELEASED`；系统 rule 为 `STATE_FALSE`，FGS 已释放。DND 过程未改写 MonitoringCoverage。
- `ownedRuleIsReusableAndNeverChangesGlobalPolicy` 在真实 Android DND access 下比较 activate/deactivate 前后 `NotificationManager.notificationPolicy`，确认 global policy 不变。

### 完整回归与环境证据

- JVM：`148/148`，0 failure / error / skipped。
- API 37 connected 最终单次 clean run：`138/138`，0 failure / error / skipped，Gradle `BUILD SUCCESSFUL in 3m 47s`。
- 在 clean run 前保留了真实 AVD 波动记录：两次全量分别在无关旧 UI 用例上随机失败，一次卡住在旧并发用例；三条目标用例单独重跑均通过。最终不以局部拼接代替 full-suite PASS。
- `lintDebug`：PASS。`assembleDebug`：PASS。
- Room 仍为 Schema v4；`4.json` SHA-256 仍为 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`，无 app/schemas diff、Migration、Entity、Table、Column 或 Index 变化。

### 未运行边界

- Android 13+ 实体设备、Pixel/AOSP 真机、Xiaomi/HyperOS、OPPO/ColorOS、vivo/OriginOS、Samsung/One UI：`NOT RUN`。
- API 23/29/33/34/35 模拟器矩阵、Task Manager Stop、reboot 与完整 Monitoring 系统场景：本 patch 未重复执行，沿用先前 checkpoint 的 PASS / NOT RUN 边界。
- 本 patch 停在 Task 6D 独立验收前，未进入 Phase 3C。

## DND 测试前提显式化与验收记录收尾（2026-10-03）

### 用户复验与边界

- 用户已对远程 `1725e6a0e0f9f03fb868efe315d842f243ebcb0a` 完成源码和已提交证据审查，Task 6D 指定 DND 生产代码修补通过复验；该复验没有重新运行 Gradle 或操作 AVD。
- Module 3B 尚未最终验收冻结。完整系统场景和至少一台 Android 13+ 实体设备验收仍未完成；其他 OEM 和未执行场景继续为 `NOT RUN`。

### 本轮修改

- 仅将 `AndroidDndSystemTest` 三个测试的 API/权限静默 return 改为 JUnit 4 `Assume.assumeTrue`，带明确前提原因；原断言全部保留。
- 不在测试代码内自动授予权限。专项验收时由独立设备操作明确授权；测试结束恢复原授权状态。
- 生产代码、Gradle、Room、Schema、Migration 无变更。

### 新执行证据

- 环境：专用 `Mirra_API_37` AOSP AVD，API 37。启动完成后初始无 Mirra package；使用 `adb install -r` 安装冻结 Debug APK 和修改后的测试 APK。没有卸载、wipe-data、pm clear 或清除已有数据；安装后数据库尚不存在，没有 Active Session。
- `:app:assembleDebugAndroidTest --no-daemon`：PASS，测试 Kotlin 实际重新编译。此结果只证明测试包构建成功。
- 直接通过 AndroidJUnitRunner 执行完整 `AndroidDndSystemTest` 类；采用 runner 实际 status event 计数，没有生成或伪造 Gradle XML。最小证据见 [DND 前提验证记录](assets/module-3b-task6/aosp-api37-avd/dnd-prerequisites-2026-10-03.md)。

| 前提 | discovered | executed（业务断言） | passed | failed | skipped | runner 证据 |
|---|---:|---:|---:|---:|---:|---|
| 未授权（初始） | 3 | 0 | 0 | 0 | 3 | 3 个 `AssumptionViolatedException`，3 个 completion code `-4` |
| 明确授权 | 3 | 3 | 3 | 0 | 0 | 3 个 completion code `0`，没有 assumption/skip |
| 恢复未授权后 | 3 | 0 | 0 | 0 | 3 | 再次出现 3 个 completion code `-4` |

注意：未授权时 runner 汇总仍打印 `OK (3 tests)`，不能将它解释为平台业务断言 PASS。必须检查逐条 completion code / skip 记录。API <29 的 assumption 分支本轮未在旧版设备执行，记录为 `NOT RUN`。

### 历史自动化与平台证据分开解释

- Task 6D 的 JVM 148/148、最终 full connected 138/138、lint/build PASS 作为原提交的执行记录保留，本轮没有重新执行全量。
- 原 connected 的零 skipped 不能独立证明 DND 平台路径被执行，因为当时测试含静默 return。此前明确授权的专项测试和真实 Session ACTIVE→RELEASED 记录是独立证据。
- 本轮明确授权专项结果 3 executed / 3 passed / 0 failed / 0 skipped 是新取得的 DND 平台证据，未授权结果不记作 DND 平台 PASS。
- 未发现生产代码异常，无需扩大验证或修改 DND core。本轮停止于测试收尾，未进入 Phase 3C/3D。
