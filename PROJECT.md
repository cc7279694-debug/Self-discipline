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

V1 服务单个 Android 用户，优先满足个人阅读与明确学习内容的长期使用，不包含团队、协作、多账号或云端多设备同步。已授权的Mirra Band扩展通过本机官方通信通道同步目录及独立完成记录，不依赖服务器。

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

## Optional Mirra Band Companion

Mirra Band使用一份应用级WearableCoordinator、WearTransport与官方Xiaomi SDK监听所有者。v1只读快照、v2经手机原业务准入的有限学习控制、v3真实书籍目录及兼容v4的独立记录同步共用此所有者。手机拥有手机Intent/Session；腕端独立学习拥有自己的Session，两者不同时写入同一学习事实。

伴侣、手机控制、目录及记录同步有分别披露的本地许可，默认关闭；许可与选书设置使用`noBackupFilesDir`下AtomicFile，不随Full Backup携带。开启伴侣后才延迟创建SDK。读写源捕获数据库、startup与StorageMaintenanceGate代际，维护期间取消/排空旧请求，退役源不能重新取得新数据库。SDK等待及发送不发生在Room事务内，服务、授权、握手、快照新鲜度与数据持久确认分别表达。

05D新增独立不可变`wearable_completed_records`表，复合键为稳定installationId/sessionId，保存完整原始canonical、SHA-256、字节数及接收时间。同ID同原字节幂等，不同内容拒绝冲突；事务提交并校验原文后才允许ACK。Room Migration 4→5只增加此表，不注入study_sessions、不占Active Session、不更新书籍页码或统计。

Full Backup format2/schema5包含新表；旧format1/schema4按冻结可信布局完整验证，复制全部旧权威数据到干净v5数据库后再次校验，保留闭库恢复日志与回滚保护。可读JSON/CSV仍只导出原手机业务表并明确排除腕端原文，不能替代完整备份。

腕端保留有界完整档案，每次新握手重新核对，可补回手机旧备份回退后缺失的记录；手机不会把历史接收状态当作当前流保存证明。参考时间不进入已验证有效专注。手机算法、监测、签名与原工作区保持，05E历史/统计整合未实施。新正式安装和真实用户数据库升级/恢复须另行明确授权。统一[05D合同](https://github.com/cc7279694-debug/mirra-band/blob/codex/module-05d-record-sync/docs/plans/MIRRA_BAND_05D_RECORD_SYNC.md)与[证据](https://github.com/cc7279694-debug/mirra-band/tree/codex/module-05d-record-sync/docs/evidence/module-05d)由Band维护。

## V1 Non-Goals

Mirra Band目录与原始记录接收是已授权的本地扩展；完整记录留在独立表，05D不导入原手机Session历史、不合并统计、不迁移实验包记录，不改变手机第一步、监测或有效专注算法。

V1 不实现登录、云同步、Supabase、服务器、Web App、iOS、AI、OCR、PDF 阅读器、重型 Block Editor、完整知识图谱、FSRS、游戏化系统、统一注意力评分或无障碍服务式强锁手机。

## Success Criterion

当用户原本在刷手机、不想动时，App 能帮助其完成第一个真实学习动作；学习期间不被手机轻易拉走；结束后能快速留下进度与想法；下一次无需重新回忆即可继续。这个闭环稳定成立，V1 才算成功。
