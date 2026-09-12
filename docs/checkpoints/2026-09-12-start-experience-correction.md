# Start Experience Correction Acceptance Checkpoint

## Goal

把 Start 收敛为只回答“我现在要怎么开始学习”的行动入口，按既有数据事实展示六级互斥状态；不改变 Phase 1 页码和异常恢复语义，不进入 Module 2C。

## Verified Completed

- Start 按 Active Session → Active Intent → 合法主线 → 无主线 IN_PROGRESS → 仅 PAUSED/COMPLETED → 空库的固定优先级解析。
- Active Session 只返回现有 Session；Active Intent 只继续或显式放弃，均不会重复创建工作流。
- 主线状态展示书名、原样 `currentPage`、总页数、进度、First Action 与最近一次正常阅读。
- 无主线时可选择某个 IN_PROGRESS 内容作为本次学习；选择本身不写数据库，“设为主线”默认关闭。
- 空库通过“添加第一本书”进入创建页；“设为主线”默认开启但可由用户关闭。创建与主线设置使用同一 Room 事务。
- 选择本次学习并勾选主线时，主线切换与 Intent 创建使用同一 Room 事务；`createIntent()` 仍在事务内再次验证 IN_PROGRESS。
- First Action 支持创建时填写、内容详情编辑和留空动态 fallback；旧自动文案按最新 `currentPage` 展示，不做 `+1`。
- 最近阅读投影仅查询当前 Learning Item 最近的 NORMAL Session，并联表计算 Note 数量；ABNORMAL 不参与。
- PAUSED/COMPLETED 即使存在异常 mainline 标记也不会进入正常 Start 主任务；Start 不提供内容管理列表。

## Data and Schema

- `LearningItemRepository.create()` 扩展为事务式可选主线创建；失败时主线清理与新记录插入一起回滚。
- `StudyWorkflowRepository.createIntent()` 支持可选主线，但已有 Active Intent 会优先返回且不会产生主线副作用。
- 新增 `RecentReadingSnapshot` 仅为 Room 查询投影，不是 Entity、Table 或 Analytics 模型。
- `MirraDatabase.version` 保持 2；未新增 Entity、Column、Index 或 Migration。
- Schema Export `1.json` 与 `2.json` 的 Git object hash 与基线分别完全一致。

## Verification

- `testDebugUnitTest`：23/23 通过，包含六级状态解析、`currentPage` 不加一和 First Action fallback。
- `connectedDebugAndroidTest`：69/69 通过，包含 Room、Migration、Repository、Compose、Phase 1、Module 2A 与 Module 2B 回归。
- `lintDebug`：通过。
- `assembleDebug` 与 `assembleDebugAndroidTest`：通过；Debug APK 与测试 APK 均生成。
- API 37 模拟器：Debug APK 覆盖安装成功；Wi-Fi/移动数据关闭后强停并冷启动成功，进程重新建立且 `MainActivity` 成为前台页面。
- 新增设备测试覆盖首本书主线默认开启/显式关闭、非主线本次选择、Active Intent/Session 继续入口和无进行中内容跳转 Knowledge。
- Repository 测试覆盖原子创建主线、原子 Intent+主线、已有 Active Intent 无主线副作用、First Action 更新，以及 NORMAL/ABNORMAL 最近阅读过滤。

## Scope Boundary

未修改 Session recovery、`currentPage` 语义、Room Schema、Migration、Module 2C Topic/Search、Module 2D Analytics、SessionSegment、DND、Usage Access、Overlay、AI、Gradle Module 或 DI Framework。

## Known Risks

- 本轮设备验证使用 API 37 模拟器，没有实体真机；Start 本身未引入新的厂商相关系统能力。
- Active Session 的页面时长按墙钟每 30 秒刷新，仅用于提示已经过时间，不代表有效专注时间。
- First Action 旧自动文案识别采用与 Phase 1 固定模板精确匹配；只有精确模板会动态更新，避免覆盖用户自定义文本。

## Next Step

等待用户正式验收并冻结本次 Correction；不得自动进入 Module 2C。
