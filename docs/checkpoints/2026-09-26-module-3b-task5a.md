# Module 3B Task 5A｜DND Ownership Core

## Goal

在不改变 Session 或 MonitoringCoverage 正确性的前提下，建立只撤销 Mirra 自己影响、尊重用户系统选择并能从 crash 中对账的 DND 核心。Task 5A 不提供设置 UI，也不进入 5B/Task 6。

## Verified completed

- `DndController` 使用 `DB intention → Android operation → DB confirmation → compensation/reconcile`；系统调用不在 Room transaction 内。
- API 23–28 只使用 legacy `INTERRUPTION_FILTER_PRIORITY`，从不调用 `setNotificationPolicy()`；同进程监听到任何后续 DND 变化即失去恢复资格，跨进程 ownership 不确定时不恢复。
- API 29–34 使用一条稳定 condition URI 标识的 Mirra-owned `AutomaticZenRule + ZenPolicy`；API 35+ 走同一路径，并在激活前尊重 user-managed/用户修改或停用。
- API 29+ 创建规则前先持久化 creation intent，能处理系统已创建规则但 Room 尚未保存 ID 的 crash gap；重复 apply/release 与冷启动 recovery 幂等。
- DND 只在已提交 Session 上按显式偏好尝试；偏好默认关闭。apply 失败不阻止监测或无监测 Session。正常 Session commit 后释放；bootstrap 先执行既有 ABNORMAL recovery，再做 DND reconcile。
- 普通消息/Conversation 被规则拦截，被拦截通知的视觉效果隐藏；来电字段使用 `UNSET` 交给 Android 解析为用户当前 DND 来电策略。API 37 测试确认规则读回的来电策略等于系统策略，且 Mirra 操作前后 global `NotificationManager.Policy` 不变。

## Data and architecture

- Room 继续为 Schema v4；没有 Entity、Table、Column、Index 或 Migration 变化。
- 复用 `session_focus_contexts.priorDndInterruptionFilter`、`dndRuleId`、`dndLifecycle` 和 `dndAccessAtStart`。
- v4 Schema SHA-256 仍为 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。
- DataStore 增加布尔偏好 `dnd_enabled`，默认 `false`；Task 5B 才提供用户入口。

## Inherited decisions

- DND 与 FULL / PARTIAL / NONE 正交；任何 DND 失败不得修改 coverage。
- 不修改用户 global Notification Policy，不触碰用户或其他 App 的 Zen Rule。
- 用户 manual override 后不在同一 Session 强制重新激活。
- 3B 暂在 normal finish commit 后 release；只有 Phase 3D 获授权后才能移到 Closeout Complete。

## Verification

- JVM：132 项通过；覆盖 API 29+ 现代规则创建/复用、orphan rediscovery、DB failure 补偿、激活前失败不误停用户规则、撤权重试、用户停用不重激活、legacy safe restore/跨进程不恢复及 coverage 不变。
- API 37 Device / Room / Migration / Compose：135 项通过；包含真实 Mirra-owned rule 创建、复用、TRUE/FALSE 状态、ZenPolicy 与 global Policy 不变、Room DND 状态、normal finish hook 顺序。
- `lintDebug`：通过；没有新增 lint baseline 或错误压制。
- `assembleDebug`：通过；APK 覆盖安装成功，强停后冷启动成功。
- `git diff --check`：通过（仅仓库既有 Windows 行尾提示）。

## Known limitations

- API 23–28 与 API 29–34 的平台路径由 fake/JVM 和编译边界验证；本回合没有对应系统版本模拟器，真实系统调用标记 `Not Run`。
- Pixel/小米/OPPO/vivo/Samsung 实体设备的多规则合并、设置页差异和后台限制 `Not Run`，按冻结计划留给 Task 6。
- Task 5A 没有设置/授权/重试 UI；用户无法从正式 UI 打开默认关闭的 DND 偏好，属于 Task 5B 范围。

## Next step

停在模型切换点。用户复验 Task 5A 核心后，切换 Luna Think，只执行 Task 5B 的低风险设置入口、权限引导、状态文案、Diagnostics、Compose/UI 测试与接线；不得重写本 checkpoint 的 ownership 语义。
