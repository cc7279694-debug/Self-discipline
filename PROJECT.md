# 观已Mirra｜个人学习 App

## Project

观已Mirra 是一款面向个人阅读与学习场景的 Android Local-first 应用。它不是普通计时器，也不是 Notion 或 Obsidian 的替代品，而是一套帮助用户完成行为状态切换、维持专注并连续记录学习过程的个人工具。

## Purpose

产品优先解决两个真实问题：

1. 用户明明想读书，却很难从刷手机、躺着等状态切换到真正开始。
2. 用户开始读书以后，手机容易重新夺走注意力。

核心闭环是：

想学习 → 真正开始 → 保持专注 → 分心后回来 → 记录阅读进度与笔记 → 结束 → 下次继续

## Users

V1 服务单个 Android 用户，优先满足个人阅读与明确学习内容的长期使用，不包含团队、协作、多账号或跨设备同步。

## Product Idea

产品把“想开始”和“已经开始”分开建模。点击“我想开始”只创建 Intent；只有当用户完成具体的 First Action，例如拿起某本书并翻到上次页码，才算真正进入 Session。系统继续区分 Started、Stable Start、Focus、Distraction 和 Recovery，从而关注真实行为改变，而不是只累计计时数字。

## Core Features

- Intent 到真实启动的行为转换与启动稳定性记录
- 主线 Learning Item、阅读页码、进度、速度与可信完成预测
- 无标题、自动保存、按语义分类的轻量独立 Note
- Android 勿扰、风险 App、渐进摩擦与临时允许机制
- 分心后的 Recovery 判断与 Start / Maintain / Recover 趋势
- 本地全文搜索、图片、完整备份与 JSON / CSV 导出

## Product Highlights

- **以真实开始为核心指标**：不把点击计时器误当成开始成功，衡量 Intent 转化、首个动作与 Stable Start。
- **行为干预而非意志说教**：启动脚本围绕状态切换和具体 First Action，不依赖鸡汤、强制番茄钟或虚假综合评分。
- **可恢复的专注设计**：允许合理使用手机，通过渐进摩擦、Temporary Allowance 和 Recovery 帮助用户回来，而不是永久封锁。
- **记录服务于连续行动**：页码、笔记、Session 总结和完成预测共同降低下一次继续学习的成本。
- **Local-first 与数据归用户**：无账号、无服务器、无云端依赖；核心数据、图片、备份和导出都保存在用户设备上。

## Tech Stack

- Kotlin
- Jetpack Compose
- Navigation 3（Compose-first）
- Coroutines / Flow
- Room / SQLite：结构化业务数据的 Source of Truth
- DataStore：设置、权限状态缓存和用户偏好
- Android App-owned Files：图片与完整备份媒体

## Architecture

UI → Application / Service → Repository → Room / SQLite + DataStore + App-owned Files

建议保持 StartEngine、ReadinessEngine、InterventionEngine、SessionManager、SummaryEngine、SearchEngine 和 BackupService 的明确边界，但不得为未来功能提前制造复杂抽象。

## Constraints

- Android 原生、本地优先、离线可用、无需登录。
- 同一时间最多一个 Active Intent、一个 Active Session 和一个主线 Learning Item。
- Session 核心规则在启动时生成快照，进行中不可修改。
- Room Schema 变化必须提供 Migration，不能通过清空数据解决升级问题。
- Android 权限与厂商限制必须提供降级路径。
- 按 Phase 交付，当前阶段之外的能力不得主动加入。

## V1 Non-Goals

V1 不实现登录、云同步、Supabase、服务器、Web App、iOS、AI、OCR、PDF 阅读器、重型 Block Editor、完整知识图谱、FSRS、游戏化系统、统一注意力评分或无障碍服务式强锁手机。

## Success Criterion

当用户原本在刷手机、不想动时，App 能帮助其完成第一个真实学习动作；学习期间不被手机轻易拉走；结束后能快速留下进度与想法；下一次无需重新回忆即可继续。这个闭环稳定成立，V1 才算成功。
