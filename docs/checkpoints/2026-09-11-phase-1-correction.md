# Phase 1 Correction Checkpoint

## Goal

在不进入 Phase 2、不扩展数据模型的前提下，修复 Phase 1 的 Intent 退出、阅读进度单调性、Note 默认值与类型建议，以及草稿关键节点保存问题。

## Verified Completed

- Active Intent 可显式放弃；事务写入 `ABANDONED` 与 `endedAt`、清除 `activeSlot`，重复放弃幂等，随后可为其他 Learning Item 创建新 Intent。
- “稍后再说”只返回并保留 Intent；“取消本次启动”才执行放弃。
- Session `currentPage` 与 `endPage` 通过 DAO 的 `MAX` 更新和 Repository 事务保持单调不减。
- 旧页 Note 只保存自身 `pageNumber`，不会倒退 Session 或 Learning Item 进度；Summary 不再出现反向页码范围。
- 第一条和下一条 Note 默认继承当前最新阅读页码，用户可独立改为旧页。
- 本地规则只对问号结尾建议“问题”、对明确“总结：”前缀建议“总结”，其余默认“我的理解”；“摘录”必须由用户选择，手动选择优先。
- 草稿在 500ms debounce 之外，会在后台、离页、下一条和结束 Session 时主动 flush。

## Changes

- 未新增或修改 Room Entity、表、索引与 Schema 版本；Room 仍为 v1，因此本补丁不需要 Migration。
- `StudyWorkflowRepository` 增加 `abandonIntent(intentId)`，并强化 Session 页码与结束事务。
- 启动准备页增加独立的“取消本次启动”入口。
- Session ViewModel 增加页码继承、类型建议、手动优先和关键节点保存协调；Compose 页面监听后台与离页事件。
- 新增规则式 Note 类型建议 JVM 测试、Repository 数据一致性测试和 Correction Compose 流程测试。

## Inherited Decisions

- Phase 1 不实现 Stable Start 自动判定、SessionSegment、DND、Usage Access、Overlay、图片、Topic、搜索、趋势、预测或 AI。
- 同时最多一个主线、Active Intent 和 Active Session；跨状态写入继续使用数据库唯一槽位与事务。
- `PAUSED` / `COMPLETED` 未来不得成为主线，且必须在 Repository / DAO 层约束并测试。

## Verification

- `clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest`：成功，86 个任务执行。
- JVM：9 / 9 通过，零失败、零跳过。
- API 37 设备测试：20 / 20 通过，零失败、零跳过；包含完整学习闭环与 5 个 Correction Compose 场景。
- 真实文件数据库强停恢复：从第 20 页开始、Session 推进到第 44 页后强停并冷启动；Session 为 `ABNORMAL`、`endPage = 44`、Learning Item 仍为第 20 页、Active Session 为空。
- Debug APK 已生成并在 API 37 模拟器安装、冷启动成功。

## Known Limitations

- 突然杀进程且系统完全不发送生命周期回调时，最后不足 500ms 的未落盘输入无法获得绝对保证；当前已覆盖 Android 可观察的后台、离页、下一条与结束节点。
- 仅在 API 37 x86_64 模拟器验证，未在真实设备覆盖厂商生命周期差异。

## Next Step

等待用户验收 Phase 1 Correction Patch；不要自动进入 Phase 2。
