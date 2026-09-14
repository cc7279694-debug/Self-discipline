# Phase 2 Freeze Checkpoint

日期：2026-09-14

分支：`codex/phase-2d-reading-analytics`

冻结远程 SHA：`734750bfe9fabc13d99091356ae0e4ffd1b8dc82`

## Frozen Scope

Phase 2 已正式验收并整体冻结，包含：

- Module 2A：Learning Item 暂停、恢复、完成，以及 Note 完整 CRUD、筛选和 Session 外创建。
- Module 2B：多图片笔记、App-owned Files、Caption、预览、文件补偿与 Room v1 → v2。
- Start Experience Correction：六级行动状态、首本书可选主线事务、First Action 与异常 Session 恢复边界。
- Module 2C：轻量 Topic、多对多关联、Room FTS4 离线搜索、可重建派生索引与 Room v2 → v3。
- Mirra Theme System：Theme Token、核心组件与已经冻结的 Mirra Blue 视觉语言。
- Module 2D：阅读历史、近期整体阅读速度、预计剩余阅读时间、自然完成日期范围与 Mine 最近 7 天摘要。

## Data Baseline

- Room Source of Truth：Schema v3。
- 业务表及 FTS 结构以导出的 `1.json`、`2.json`、`3.json` 为历史事实；Phase 2D 未改变任何 Schema 文件。
- 迁移链只有显式非破坏性的 v1 → v2 与 v2 → v3；没有 Analytics 持久化表或 destructive migration。
- 图片实体文件存于 App 私有目录，数据库只保存相对路径；trash / restore / purge 与启动清理负责最终一致性。
- `search_fts` 是可重建派生索引，业务表仍是唯一事实源。

## Frozen Product and Analytics Semantics

- Start 管行动，Knowledge 管内容，Mine 管个人记录；Start 六级状态与 `currentPage`“当前可继续阅读的位置”语义不变。
- Phase 2 只使用 Session 总阅读时长，不称为“有效专注时间”。
- Analytics 只统计正常结束、数据完整、正时长且不来自未来的 Session；ABNORMAL 可出现在历史中，但不进入统计或预测。
- `pagesRead = max(0, endPage - startPage)`，不加 1；零推进正常 Session 保留并进入时长分母。
- 近期整体阅读速度为总推进页数 / 总阅读时长；窗口严格按 7 → 14 → 30 天选择。
- 自然完成日期使用完整窗口自然日反映真实阅读频率，并先通过 Eligibility，再评估 Confidence。
- `robustCV` 是 leave-one-out 最小 CV，不是传统全样本 CV 或 median/MAD；`robustCV > 0.75` 隐藏自然完成日期。
- PAUSED / COMPLETED 保留历史，但不展示未来完成预测。

## Verification Baseline

Module 2D 最终验收建立在以下已完成验证上：

- 51 个 JVM 测试通过，覆盖 pure function、7/14/30 窗口、本地时区边界、零推进、异常排除、Confidence、日期范围与 `robustCV`。
- 101 个 API 37 Room / Migration / Instrumented / Compose / 端到端测试通过，覆盖 DAO 投影、Mine 摘要、单书历史和 Phase 1 / 2A / 2B / Start / 2C / Theme 回归。
- `lintDebug` 与 `assembleDebug` 通过。
- Debug APK 已覆盖安装；完全离线、强停与两次冷启动验证通过。
- Room Schema 保持 v3，`1.json`、`2.json`、`3.json` 未变化。

这些结果是 Phase 2 冻结时的验收基线；后续变更必须重新验证受影响范围，不得把本 checkpoint 当作未来版本的自动通过证明。

## Known Boundaries

- Mono / Night 目前只有基础 Palette / Token，尚未开放完整主题选择与全页面视觉验收。
- 中文搜索首版不支持单字、拼音、同义词、stemming 或模糊匹配。
- 阅读预测依赖用户如实更新页码；数据不足、无推进、高波动或低可信度时主动隐藏结果。
- 文件系统与 SQLite 不能构成真正的跨资源原子事务，当前通过补偿与启动恢复达到最终一致。

## Phase 3 Boundary

Phase 3 只能在独立工程规划通过验收后开始。其候选范围是 Android 专注干预与分心恢复，包括 DND、Usage Access、Risk App、Unlock tracking、InterventionEngine、通知 / Overlay fallback、Temporary Allowance、Progressive friction、Break、Distraction、Recovery、SessionSegment，以及建立在真实分段之上的有效阅读指标。

Phase 3 必须继承以下边界：

- 不改写 Phase 1 / 2 已冻结的 Intent、Session、Note、`currentPage` 和异常恢复语义。
- 不用 Session 总时长冒充有效专注时间；有效指标必须来自可验证的 SessionSegment。
- 不把 Start 改造成 Dashboard，不让干预或分析遮蔽核心阅读行动。
- 涉及系统权限、后台行为、通知或 Overlay 时，必须先规划权限拒绝、设备差异、降级路径、隐私和真机验证。
- 未经单独授权，不创建 Phase 3 Schema、Migration、系统权限入口或业务代码。

## Next Step

下一步仅编写 Phase 3 详细工程计划，等待验收后再决定是否实施。本 checkpoint 不授权自动进入 Phase 3。
