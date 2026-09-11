# Phase 2 Implementation Plan

> **For agentic workers:** 每个 Module 开始前必须另写可逐项执行的工程计划，并使用适用的计划执行 Skill 按任务验证；本总体计划不构成业务代码实施授权。

**Goal:** 在不进入专注干预的前提下，让观已Mirra 的阅读记录能够长期积累、查找、轻量关联并形成可信的阅读进度理解。

**Architecture:** 延续单 Android App Module 与 `Compose UI → ViewModel → Domain / Service → Repository → Room / App-owned Files`。业务表是事实源，SearchFts 是可重建派生数据；图片数据库记录和物理文件通过补偿流程保持一致。

**Tech Stack:** Kotlin、Jetpack Compose、Navigation 3、Room 2.8.x、SQLite FTS4、Coroutines / Flow、Android App-owned Files。

**Spec:** `docs/PRODUCT_SPEC.md`

## Global Constraints

- Phase 1 已冻结，不重新打磨既有学习闭环。
- Local-first、Offline-ready、无账号、无后端、无云数据库。
- 不实现 SessionSegment、DND、Usage Access、Overlay、AI、OCR、知识图谱、Review 或 Future Modules。
- UI 不直接访问 DAO；跨表状态变化由 Repository 事务保护。
- 不使用破坏性 Migration；每次 Schema 变化都导出 Room Schema 并提供真实迁移测试。
- Phase 2 只使用 Session 总时长，不展示有效阅读速度或剩余有效阅读时间。
- 每个 Module 独立计划、测试、Commit、Push、验收；未获授权不得进入下一 Module。

---

## 1. 已冻结的产品与数据语义

### 1.1 Learning Item 状态

允许的 Phase 2 状态变化：

```text
IN_PROGRESS → PAUSED
PAUSED → IN_PROGRESS
IN_PROGRESS → COMPLETED
```

`COMPLETED` 在 Phase 2 不恢复。只有 `IN_PROGRESS` 可以成为主线或创建 Intent。暂停或完成主线时必须在同一事务内清除 `mainlineSlot`，不自动选择替代主线。存在该内容的 Active Intent 或 Active Session 时拒绝暂停和完成，引导用户先取消 Intent 或结束 Session。

### 1.2 Note

Note 仍是无标题的独立记录。支持全局时间流、详情、正文/类型/页码编辑、二次确认删除、Session 外创建、四类语义筛选和按 Learning Item 查看。Session 外页码不得推进 Learning Item 进度。暂停或完成的 Learning Item 仍允许整理既有 Note；创建新的学习 Intent 则禁止。

### 1.3 图片

`ImageAsset` 归属于一个 Note；Note 详情显示当前 Note 图片，全屏预览在当前 Note 内切换。“全部图片”是知识页面下的全局图片列表，页码、Learning Item 和 Session 来源通过 Note 关联取得。

数据库外键级联只删除记录。图片导入、删除与 Note 删除必须经过 `ImageStorageService` 的暂存、确认、补偿和启动清理流程。数据库只保存 `images/<uuid>.<ext>` 形式的相对路径。

### 1.4 Topic

Topic 只包含 `id`、`name`、`createdAt`。一条 Note 可关联多个 Topic。建议只依据正文明确出现的已有 Topic 名称；任何关联或新建都由用户确认，不自动批量创建。

### 1.5 搜索

Room 保持 2.8.x。使用 FTS4 派生索引；中文连续文本生成双字 token，英文保持普通 token。查询按普通文本转义，不能让用户输入成为 MATCH 表达式。SearchFts 可以从正式业务表重建，同一业务对象最终只返回一条结果。

### 1.6 阅读分析

合格 Session 必须已结束、时间和页码完整、时长大于零且 `endType != ABNORMAL`。

```text
sessionProgress = max(0, endPage - startPage)
recentOverallReadingSpeed = 合格 Session 总推进页数 / 合格 Session 总时长
remainingPages = max(0, totalPages - currentPage)
remainingReadingTime = remainingPages / recentOverallReadingSpeed
calendarReadingPace = 选定窗口内总推进页数 / 窗口自然日数
naturalCompletionDate = 当前日期 + remainingPages / calendarReadingPace
```

窗口优先 7 天；不足 3 个合格 Session 或累计 30 分钟时扩展到 14 天，再扩展到 30 天。30 天仍不足则不显示预测。自然完成日期还要求至少 5 个 Session、3 个阅读日、跨度至少 7 天；最近 14 天无阅读或速度波动过大时隐藏。

波动使用近期 Session 阅读速度的变异系数判断。具体阈值属于 Module 2D 计划的验收决策，必须先提出、说明样例并通过单元测试设计评审，不能在实现中临时选择。

---

## 2. 数据库演进

### Schema v1（当前）

- `learning_items`
- `study_intents`
- `study_sessions`
- `notes`

### Schema v2（Module 2B）

新增：

```text
image_assets
- id TEXT PRIMARY KEY
- noteId TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE
- localPath TEXT NOT NULL UNIQUE
- caption TEXT NULL
- width INTEGER NOT NULL
- height INTEGER NOT NULL
- fileSize INTEGER NOT NULL
- createdAt INTEGER NOT NULL
- INDEX(noteId)
```

必须提供 `Migration(1, 2)`、导出的 `2.json` 和使用现有 `1.json` 的 Migration Test。

### Schema v3（Module 2C）

新增：

```text
topics
- id TEXT PRIMARY KEY
- name TEXT NOT NULL UNIQUE
- createdAt INTEGER NOT NULL

note_topic_cross_refs
- noteId TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE
- topicId TEXT NOT NULL REFERENCES topics(id) ON DELETE CASCADE
- PRIMARY KEY(noteId, topicId)
- INDEX(topicId)

search_fts
- Room FTS4 派生索引
- entityType
- entityId
- searchableText
```

必须提供 `Migration(2, 3)`、导出的 `3.json`、v2→v3 测试，以及从 v1 连续升级到 v3 的测试。SearchFts 数据必须可在迁移或首次打开后从业务表重建。

---

## 3. Module 2A｜Learning Item 与 Note 完整化

### 目标

让已有结构化记录可以在 Session 外长期管理，不改变数据库版本。

### 预计修改范围

- `data/local/dao/LearningItemDao.kt`：受状态约束的主线与生命周期更新。
- `data/local/dao/NoteDao.kt`：详情、分页列表、筛选、更新和删除查询。
- `data/repository/LearningItemRepository.kt`：暂停、恢复、完成事务。
- `data/repository/NoteRepository.kt`：独立创建、编辑和删除。
- `feature/knowledge/`：Note 时间流、详情、编辑、筛选与 Learning Item 状态入口。
- `navigation/Routes.kt`：Note 列表和详情路由。
- `androidTest/` 与 `test/`：状态不变量、Note 数据与 Compose 流程。

### 核心验收

- PAUSED / COMPLETED 永远不能占用主线槽位或创建 Intent。
- 暂停与完成会原子清除主线；恢复不会自动成为主线。
- Active Intent / Session 阻止非法状态变化。
- 全局 Note 默认按创建时间倒序，可按类型或 Learning Item 查看。
- Session 外 Note 页码不改变阅读进度。
- 编辑保留 `createdAt` 并更新 `updatedAt`；删除需要二次确认。
- 现有 Phase 1 学习闭环回归通过。

### 停止条件

完成 2A 自动化和模拟器验收、独立提交并推送后停止，等待用户验收；不得创建 ImageAsset、Topic 或 SearchFts。

---

## 4. Module 2B｜图片与 App-owned Files

### 目标

让 Note 可以可靠保存、查看和删除本地图片，同时完成 Schema v1→v2。

### 预计新增边界

- `ImageAssetEntity`、`ImageAssetDao`、`ImageRepository`。
- `ImageStorageService`：临时导入、方向修正、适度压缩、正式落盘、回收与恢复。
- 系统 Photo Picker / 多选回退与相机拍照入口；不申请与功能无关的广泛存储权限。
- 当前 Note 图片区、Caption 编辑、全屏缩放、全局图片列表。

### 文件生命周期

导入：系统 URI → App 临时文件 → 解码与校验 → 方向修正和压缩 → 正式相对路径 → 数据库写入；任一步失败都删除本次生成文件且不留下 ImageAsset。

删除：读取路径 → 移入临时回收区 → 数据库事务删除 → 成功后彻底删除；事务失败恢复原路径。App 启动时只清理超过安全时间的 temp 文件和确认无数据库引用的正式 orphan 文件。

### 核心验收

- 相机和相册图片在原 URI 失效、离线和冷启动后仍可查看。
- 图片方向、尺寸、文件大小与 Caption 正确保存。
- Caption 修改和删除可靠；全屏支持缩放、平移和同 Note 切换。
- 删除单图或 Note 后无明显 orphan；模拟数据库失败时不会产生断裂引用。
- v1 用户升级后 Learning Item、Intent、Session、Note 与进度全部保留。

### 停止条件

完成 v1→v2 Migration、文件故障测试和真实设备/模拟器图片闭环后独立提交并推送，等待用户验收；不实现 Topic 或搜索。

---

## 5. Module 2C｜轻量 Topic 与本地搜索

### 目标

让用户以极低管理成本关联概念，并离线找回书籍、Note、Caption、Topic 和 Session Summary。

### 预计新增边界

- `TopicEntity`、`NoteTopicCrossRef`、对应 DAO / Repository。
- `TopicSuggester`：只做明确名称包含匹配。
- `SearchFtsEntity`、`SearchDao`、`SearchRepository`、`SearchEngine`。
- 中文/英文规范化器与完整索引重建器。
- Topic 列表/详情/关联 UI、全局搜索页及各结果路由。

### 中文索引规则

- 连续中文段按相邻两个字符生成 token；Phase 2 不额外建立高噪声的完整单字索引。
- `心理账户` 的索引至少包含 `心理 理账 账户`。
- 拉丁文字和数字先做稳定大小写规范化，再交由普通 FTS tokenization。
- 混合文本分别处理后合并；查询使用相同规范化器。
- Note 搜索文档聚合正文和所有图片 Caption，因此多处命中只产生一个 Note 结果。

具体 FTS4 Entity 结构、同步写入方式和 MATCH 查询必须在 2C 工程计划中结合 Room 2.8.5 生成 Schema 验证；不允许以升级 Room 3 代替验证。

### 核心验收

- Topic 名称去除首尾空白并拒绝空名称与精确重复名称。
- Note 多 Topic 关联不会重复；删除 Note 自动清理交叉记录。
- 建议不会自动创建或关联 Topic。
- 中文局部词、英文、混合文本、Caption 和 Summary 均能命中。
- 特殊符号输入不触发 MATCH 错误或查询语法。
- 索引删除后可从业务表重建，重建前后结果一致。
- 搜索结果能导航到 Note、Learning Item、Topic 或 Session Summary。

### 停止条件

完成 v2→v3、v1→v3 连续迁移、FTS 重建和离线搜索验收后独立提交并推送，等待用户验收；不实现 AI、OCR 或 Topic 关系图。

---

## 6. Module 2D｜阅读数据与完成预测

### 目标

用已有 Session 数据提供可解释、不过度承诺的阅读节奏反馈，不引入有效专注时间。

### 预计新增边界

- Session / Note 聚合查询与只读统计模型。
- `ReadingAnalyticsService`：窗口选择、合格 Session、总时长、页数、整体速度与趋势。
- `CompletionPredictionService`：剩余阅读时间、日历推进速度、自然完成范围和可信度。
- Learning Item 阅读详情、历史列表与“我的”最近 7 天基础摘要。

### Module 2D 计划必须先冻结的规则

- Session 速度变异系数的计算样本、零推进 Session 处理方式和“波动过大”阈值。
- 高 / 中 / 低可信度的确定性分界。
- 高 / 中可信度完成日期范围的扩张规则。
- 跨时区与自然日边界统一使用设备当前时区的方式。

这些规则必须配套固定输入/输出样例，经用户验收后才允许实现。

### 核心验收

- 整体速度使用“总页数 ÷ 总时长”，不平均单次速度。
- ABNORMAL Session 完全排除；数据缺失或不足时不展示推测结果。
- 剩余阅读时间与自然完成日期使用不同公式。
- 日历推进速度把未阅读日计入窗口自然日数。
- 最近 14 天没有阅读或波动超阈值时隐藏完成日期。
- 可信度只显示高 / 中 / 低，不显示百分比。
- 阅读趋势只陈述数据，不在 Phase 2 产生效率下降提醒或行为评价。

### 停止条件

完成纯 JVM 算法测试、Room 聚合测试、Compose 显示边界与模拟器验收后独立提交并推送，正式关闭 Phase 2；不得自动进入 Phase 3。

---

## 7. 每个 Module 的验证基线

每个模块只报告实际执行结果：

```text
JVM unit tests
Room instrumented tests
Compose UI tests
Android instrumented tests
Lint
Debug build
APK install and cold start
Relevant offline/manual flow
Git status and diff inspection
Sensitive-data scan
```

涉及数据库版本的模块必须额外验证旧 Schema 真实升级；涉及图片的模块必须验证文件补偿；涉及预测的模块必须验证时区、边界值和数据不足隐藏逻辑。

## 8. Phase 2 最终闭环

```text
打开一本书
→ 经 Intent 启动阅读
→ 连续记录 Note 与图片 Caption
→ 关联轻量 Topic
→ 结束 Session
→ 通过中文或英文搜索找回记录
→ 从 Topic 查看跨书笔记
→ 查看阅读历史、整体速度、剩余阅读时间
→ 数据足够时查看自然完成日期范围
→ 下次继续阅读
```

整个闭环必须完全离线；Phase 1 唯一 Active Intent / Session、进度不倒退和异常恢复不变量必须持续成立。

## 9. 当前授权边界

本文件只冻结 Phase 2 总体产品语义、架构边界、数据库演进和模块验收。下一步仅可编写 Module 2A 的详细工程实施计划；未经用户再次授权，不得修改业务代码、数据库版本或开始 Module 2A。
