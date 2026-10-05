# Recovery evidence chain diagnostic — inconclusive

日期：2026-10-05。诊断基线 `9efea119667b6ae5adf1761521ea785edabb1cb0`，分支 `codex/phase-3d-final-validation`。仅专用 `Mirra_API_37` / API37 AOSP AVD；实际核对 API37、qemu=1、boot_completed=1。没有实体设备操作。

## Verdict and scope

**RECOVERY_DIAGNOSTIC_INCONCLUSIVE**。本轮在一个新建的受控 Session 内完成一次 in-app 对照和两次已实际消费的外部 `OPEN_ALLOWANCE`；三次 Allowance 提前结束后的 Recovery 均成功。原先第二场外部返回后长时间不恢复的现场未复现，A–F 均没有足够的确认依据。

这不是修复、根因排除或 3D-4 完成证明。保留 [先前异常与权限执行记录](authorized-permissions-and-recovery-review.md)，不删除原失败、RED、环境波动或 NOT RUN。等待独立 review 决定下一步；没有修改正式业务行为，也没有冻结 Phase 3D。

## Instrumentation and execution

按照本轮授权临时加入 `MirraRecoveryDiag`：Activity 生命周期、外部导航前后、Reporter attach/dispose/lifecycle/window、约10秒一次的状态采样、Controller evidence/proof/write、Tracker start/reset/progress。另在原 Session ViewModel 的刷新/门控/送达边界记录同类日志，以定位 evidence delivery；异常仍沿原路径重抛或返回 SAVE_FAILED。

临时涉及六个文件：`MainActivity.kt`、`MirraApp.kt`、`SessionEvidenceReporter.kt`、`SessionScreen.kt`（ViewModel）、`BoundSessionMonitoringController.kt`、`StableEvidenceTracker.kt`。没有改变正向判定、阈值、返回值、转换或原业务调用顺序；没有改为 repeatOnLifecycle、屏蔽 false、优化 same-route 导航或修改 Tracker 判定逻辑。

诊断期间两次 `assembleDebug` 编译通过，最终一次产物覆盖安装到专用 AVD。没有运行全量 JVM / connected / lint，也没有将编译通过当成回归验收。本轮只复现目标链，不重新执行整包 3D-4。

新 Session 通过真实 Knowledge → Preparation → 用户确认进入 FULL + FOCUS / DND ACTIVE；风险确认来自实际测试 App 访问。所有 Allowance 和结束操作通过正式 UI；Room 仅只读，不手工写 Segment 或制造 FULL。

## Three observations in the same Session

| Observation | Entry evidence | Recovery start → persisted FOCUS boundary (wall ms) | Segment duration | Positive proof duration |
| --- | --- | --- | --- | --- |
| 1 — in-app control | Overlay tap 后只有 NEW_INTENT，没有 NAV 消费；随后通过 in-app 临时使用完成。**不算 exact OPEN_ALLOWANCE** | `1791213505640` → `1791213596467` | 90,827ms | 90,059ms |
| 2 — first verified external entry | 实际 Overlay 临时使用 → NEW_INTENT → NAV OPEN_ALLOWANCE；同 SessionRoute / same=true / size=1 → clear/add / size=1 | `1791213837338` → `1791213928196` | 90,858ms | 90,300ms |
| 3 — second verified external entry | 同一 Session 再次 risk confirmation → 实际 Overlay → NEW_INTENT → NAV OPEN_ALLOWANCE；同样 same=true / size=1 → clear/add / size=1 | `1791214456970` → `1791214547976` | 91,006ms | 90,491ms |

第一次有别于目标外部导航路径，故补充第三个 episode，确保同一 Session 内有两次确认消费到的外部入口。选择“查资料”后，三个 Allowance 依次完成0/5/15秒对应的真实摩擦等待，grant 后提前结束；没有通过 fake clock、系统时间修改或按钮宣布 Recovery success。

每个 Allowance → RECOVERY 后独立静置至少105秒；不运行 UIAutomator、不截图、不切 HOME、不打开 Profile、不操作页面。静置期间仅允许诊断 tag 读取及只读事实；结束后统一读取 Room 和系统状态。第三次两段实际等待为50秒和55秒，读回边界距静置开始141,362ms。第二次静置读回距起点143,095ms；第一次168,323ms。读取/调度开销不能记为 proof 时长，表中使用真实写入边界和 Tracker proof。

三次均生成 RECOVERY proof → WRITE_ATTEMPT → WRITE_RETURN success=true，Room 均有对应 RECOVERY_SUCCEEDED / FOCUS 新段。FULL 保持，monitoringLostAt=null，心跳持续。没有记录到 milestone exception。

## Activity, navigation and evidence ownership

为避免暴露不必要标识，以下将实际 identityHashCode 映射为 A1/R1/VM1/C1/E1；原始映射仅在本地日志中。

- 观察日志中一个 MainActivity 实例 A1、一个 Reporter R1、一个 ViewModel VM1、一个 Controller C1、一个 Tracker E1；Reporter ATTACH=1、LOOP_START=1，静置结束前 DISPOSE=0。Activity create=1，三个外部返回均为同 A1 的 NEW_INTENT，未记录第二个 Activity create。
- 静置后 `dumpsys activity` 核对为一个唯一 Mirra MainActivity ActivityRecord、top/resumed，`dumpsys window` 核对 Mirra 为 current focused window。相同 ActivityRecord 在多个汇总行出现不算多个 Activity。
- 两次确切 OPEN_ALLOWANCE 均触发现有 clear/add，但 R1/VM1 未重新 attach。因此只能证明代码路径执行，**不能把 clear/add 写成已观察到新的 Reporter mount 或 ownership 异常**。
- R1 的 owner 为 ComposeLifecycleOwner；返回后为 RESUMED、attached=true、window focus=true、interactive=true、pagePositive=true。
- 风险 App 前台时 owner CREATED/窗口失焦，Allowance Dialog 打开时 owner RESUMED 但窗口失焦，false 及窗口重置是符合现有判定的状态变化。第二次 Dialog 打开时曾出现较早捕获的 true 随后送达，再由同一 R1 的 false 重置；不是两个 Reporter 互相干扰。
- Allowance 提前结束后的三个安静窗口，Controller 持续接收 healthy=true / positive=true；Tracker positiveSince 建立后完成30/60/85/90秒进度及 proof，没有观测到窗口反复清零。

少量脱敏节选（不是完整日志；按实际来源摘录）：

```text
15:23:00.727 NAV before action=OPEN_ALLOWANCE top=SessionRoute same=true size=1
15:23:00.727 NAV after size=1
15:25:28.212 CONTROLLER PROOF RECOVERY continuous=90300
15:25:28.212 CONTROLLER WRITE_ATTEMPT RECOVERY
15:25:28.216 CONTROLLER WRITE_RETURN RECOVERY success=true

15:32:36.041 NAV before action=OPEN_ALLOWANCE top=SessionRoute same=true size=1
15:32:36.043 NAV after size=1
15:35:48.002 CONTROLLER PROOF RECOVERY continuous=90491
15:35:48.002 CONTROLLER WRITE_ATTEMPT RECOVERY
15:35:48.023 CONTROLLER WRITE_RETURN RECOVERY success=true
```

## A–F adjudication and limitations

| Category | This run | Evidence boundary |
| --- | --- | --- |
| A — stale Reporter | Not confirmed | 一个 R1，无多 Reporter 持续 true/false 对冲 |
| B — lifecycle ownership | Not confirmed | 正常返回后 owner RESUMED、窗口聚焦；用途面板失焦不等于 owner 卡住 |
| C — delivery break | Not confirmed | Reporter → VM → Controller → proof/write 链实际贯通 |
| D — tracker reset bug | Not confirmed | 三个完整窗口均生成 proof；窗口外非正向/Segment 变化导致的 reset 不冒充缺陷 |
| E — Recovery write failure | Not confirmed | 三次返回 success=true，Room 有对应 milestone 和 FOCUS；无异常 class/message 可报告 |
| F — evidence loop gap | Not confirmed | 安静窗口无 CLEAR_CLOCK；节流日志中记录的相邻 sample delta 约1秒，不把两条约10秒间隔的 SAMPLE 日志当输入 gap |

本轮只记录低频状态和关键转换，没有逐条 callback 关联序号或独立 mount-generation，不能由节流日志声称测得整个流的精确最大 delta。原始数据是同一 tag 流的累积快照，不能累加文件前缀来统计实例/事件。日志开销可能改变竞态时序。两次 exact 外部导航均保留同一 Reporter，尚未覆盖“导航实际新 mount 后异常”的现场。

这些限制和成功结果均不支持宣布旧问题消失、确认架构 Bug 或修改 Recovery 阈值。历史 FULL/heartbeat 健康也仍不能单独证明页面正向证据。

## Cleanup and original-state readback

正常 UI 二次确认结束当前专用 Session：`endedAt=1791214642992`，NORMAL / COMPLETED / FULL / DND RELEASED。Active Session=0、Active Segment=0；最后 Segment 已关闭。当前 Mirra-owned rule STATE_FALSE / Zen OFF，未改 global Notification Policy 或其他 App/rule。

| Item | Original state | Final readback |
| --- | --- | --- |
| Usage Access | GET_USAGE_STATS default | default |
| DND policy access | 应用真实 API / Diagnostics false | false；DND NEEDS_USER_ACTION |
| Overlay | SYSTEM_ALERT_WINDOW default | default |
| POST_NOTIFICATIONS | false，原 USER_SENSITIVE flags | false，原 flags 保留 |
| DND / cross-app preference | OFF / OFF | OFF / OFF |
| Risk selection | 原受控 preservation fixture 一项 | 原集合恢复，临时 Chrome 移除；不删除 fixture |
| Network | Wi-Fi / mobile 1 / 1 | 1 / 1，无修改 |
| Destination | Knowledge | Knowledge |
| Monitoring / Overlay / intervention notification | 无活动 Session | Service STOPPED / 无 Mirra Overlay / 无 ID3002 active notification；系统 ShellDropTarget 保持不变 |

六个临时源码已逐个 `git restore --source=9efea119667b6ae5adf1761521ea785edabb1cb0`，`git diff --check` 通过；生产和测试源码回到诊断基线，没有日志代码提交。

诊断 APK 也用诊断前保存的原 APK 覆盖恢复并冷启动；不卸载、不清库。保存文件及实际已安装 base.apk SHA-256 一致：`68C89D5948E34E3EF48DE374CA2043B6B5D0EE43702E7D3B63E7A1CE3EF674E8`。冷启动 Diagnostics 读回 STOPPED、无 bound/active Session、DND access=false、preference OFF。测试事实按正常历史保留。

Room PRAGMA user_version=4，Schema1–4 均与基线一致，无 Entity / Migration 变化。v4 SHA-256：`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。

完整 tag 日志、原状态快照、临时补丁及少量静置前截图仅在本地证据目录，不提交 Git。最终提交仅本文件及目录索引更新。

## Remaining boundary

不继续修 production、不重跑/补齐整包3D-4、不宣布 Phase3D freeze。原 granted full suite、near-closeout 系统路径、opt-in重复专项及最终交付 Gate 保持原执行状态，不能用本轮三次成功替代。

API23–36 full matrix、OEM / 完整实体机矩阵、TalkBack、release / Play、真实硬件断电、人工修改真实系统时钟仍 NOT RUN。一加13T未操作，个人试用不升级兼容性PASS。

下一步：独立审阅本次诊断证据，决定是否授权更精准的复现/最小修补或恢复其余验证；目前没有证据支持选择 A–F 的某项修复。
