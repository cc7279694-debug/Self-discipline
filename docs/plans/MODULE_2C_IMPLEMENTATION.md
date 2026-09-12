# 观已 Mirra｜Module 2C Topic + Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在完全离线、本地优先的前提下，为 Note 增加极轻量 Topic 多对多关联，并通过 Room 2.8.x FTS4 搜索 Note 正文、图片 Caption、Learning Item 名称、Topic 名称与 Session Summary。

**Architecture:** Room 正式业务表继续作为唯一事实源；`search_fts` 是“一业务对象一行”的派生缓存，由各 Repository 在同一数据库事务内同步，并可由 `SearchIndexRebuilder` 从业务表完整重建。中文内容先做确定性的 Unicode 规范化与相邻双字 token 化，再通过安全构造且参数绑定的 FTS4 MATCH 查询检索。

**Tech Stack:** Kotlin、Jetpack Compose、Navigation 3、Coroutines / Flow、Room 2.8.5、SQLite FTS4、单 Android `app` Module、手工 `AppContainer`。

**Spec:** `docs/PRODUCT_SPEC.md`、`docs/DECISIONS.md`、`docs/plans/PHASE_2_IMPLEMENTATION.md`、`docs/checkpoints/2026-09-11-module-2b.md`

## Global Constraints

- 只实施 Module 2C；不得进入 Module 2D、Phase 3 或 Future Modules。
- 数据库只从 Schema v2 非破坏性迁移到 v3；禁止 destructive migration。
- Room 保持 2.8.x，使用 FTS4；不升级 Room 3，不使用 FTS5 trigram。
- 只新增 `topics`、`note_topic_cross_refs` 与必要的 `search_fts` 派生结构。
- Topic 只含 `id`、`name`、`createdAt`；不增加描述、别名、层级、关系、成熟度、来源或 AI 字段。
- 不实现 Topic 删除或改名；Note 与 Topic 的关联和解除均须由用户确认。
- 不实现 AI、Embedding、Vector Search、OCR、Knowledge Graph、ReviewCard、FSRS、Analytics、预测、SessionSegment、DND、Usage Access、Overlay 或云同步。
- 不新增 Gradle Module、DI Framework、WorkManager、Paging、Lucene 或网络依赖。
- Phase 1、2A、2B 的状态机、自动保存、图片文件补偿与离线闭环不得回归。

---

## 0. Task Contract

### Goal

用户可以创建和浏览轻量 Topic，把已有 Note 与一个或多个 Topic 关联，并通过统一的本地搜索快速找回学习内容、笔记、图片说明、Topic 与 Session 总结。

### Scope

- Topic 创建、列表、详情；
- Note ↔ Topic 多对多关联、解除关联；
- Note 详情内关联已有 Topic，或创建并立即关联；
- 仅基于明确文字包含/输入匹配的 `TopicSuggester`；
- Schema v2 → v3、FTS4 派生索引与完整重建；
- 中文双字、英文及混合文本搜索；
- 搜索结果按对象类型展示并导航到现有或最小详情页面；
- 启动索引一致性检查、错误恢复和全量回归。

### Out of Scope

Topic 删除/改名、别名、层级、关系、图谱、自动创建/批量关联、语义推荐、OCR、AI、搜索高级筛选、Session 历史列表、阅读分析及任何 Module 2D 能力。

### Dependencies

- Schema v2 与 `MIGRATION_1_2`；
- 现有 `LearningItemRepository`、`NoteRepository`、`ImageRepository`、`StudyWorkflowRepository`；
- `NoteEditorScreen`、`KnowledgeScreen`、Navigation 3 back stack；
- Room schema export、`MigrationTestHelper`、JVM/Instrumented/Compose 测试基础。

### Verification

实现时严格按数据模型与纯函数 → Migration → Topic 事务 → 索引同步/重建 → ViewModel/UI → 全量回归推进。每一小段先写失败测试，再做最小实现；最终验证见第 19 节。

## 1. 当前代码复用分析

规划时的事实基线：

- 工作目录：`C:\Users\CDD\Documents\ChatGPT\Mirra`；
- 分支：`codex/phase-2b-image-notes`；
- HEAD 与远程分支：`5948904 feat(images): add local image notes`；
- Room 当前 `version = 2`，表为 `learning_items`、`study_intents`、`study_sessions`、`notes`、`image_assets`；
- 工作区在规划开始时干净。

可直接复用：

- `MirraDatabase` 的单数据库事务能力与已导出的 `1.json`、`2.json`；
- `NoteEntity.learningItemId/sessionId/pageNumber`，Topic 与搜索结果无需复制这些来源字段；
- `ImageAssetEntity.caption` 与 `ImageAssetDao.listForNote()`，可把一条 Note 的全部 Caption 聚合进同一搜索文档；
- `LearningItemRepository.create()`、`NoteRepository.save/createStandalone/update/delete()`、`ImageRepository.import/updateCaption/deleteImage()`、`StudyWorkflowRepository.finishSession()` 这些真实写入边界；
- Note 删除已有的文件 trash → DB 事务 → purge/restore 流程；2C 只在该 DB 事务中同步移除 Note 搜索文档和依赖 CrossRef 的级联行；
- `KnowledgeScreen` 的知识入口与创建弹窗；
- `NoteEditorScreen` 的现有详情/编辑状态，可在已落库 Note 上追加 Topic 区域；
- `SessionSummaryScreen` 可增加只读标题和返回动作，供搜索结果复用，不建设 Session History；
- 手工 `AppContainer` 与启动 `Deferred<Unit>`，可追加轻量索引一致性检查；
- API 37 Instrumented/Compose 测试、Room Migration 测试和离线模拟器验收方式。

需要升级的边界：

- `MirraDatabase` 加入两个正式表、一个 FTS4 虚表、两个 DAO，并升到 v3；
- 四个现有 Repository 的相关写事务接入 `SearchIndexWriter`；
- `AppContainer` 装配 `TopicRepository`、`SearchRepository`、`SearchIndexRebuilder`；
- Navigation 增加 Topic、搜索和 Session 搜索详情路由；
- `CURRENT_STATE.md` 与 Module 2C checkpoint 在实施完成时更新。

不做的重构：不拆新 Gradle Module，不引入 UseCase 总线、事件总线、通用推荐框架或通用缓存框架；不重写现有 Repository 与 Note 编辑器。

## 2. Schema v3 设计

### 2.1 TopicEntity

```kotlin
@Entity(
    tableName = "topics",
    indices = [Index(value = ["name"], unique = true)],
)
data class TopicEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
)
```

Topic 名称写入前由 `TopicNameNormalizer` 处理：

1. Unicode NFKC，把全角 Latin/数字和兼容字符转换为稳定形式；
2. 去除首尾 Unicode 空白；
3. 连续的 Unicode 分隔符/空白折叠为一个半角空格；
4. 规范化后为空则拒绝；
5. 显示值保留首次创建时的大小写；数据库 `NOCASE UNIQUE` 负责 ASCII 大小写不敏感唯一性。

明确边界：SQLite `NOCASE` 只提供 ASCII 大小写折叠。NFKC 先把 `Ｋｏｔｌｉｎ` 变成 `Kotlin`，因此它与 `kotlin` 重复；中文无大小写。不会额外引入 ICU 排序或跨语言 case-fold 库。

重复创建不是崩溃型错误：Repository 捕获唯一约束竞争后再次按规范名称查询并返回 `AlreadyExists(existingTopic)`。全局创建入口提示“Topic 已存在”并可打开它；Note 内创建则提示后由用户确认/继续关联已有 Topic。不得静默创建副本。

Module 2C 不提供 Topic 删除或改名 API/UI。解除 Note 关联只删除 CrossRef，不删除 Note 或 Topic。

### 2.2 NoteTopicCrossRef

```kotlin
@Entity(
    tableName = "note_topic_cross_refs",
    primaryKeys = ["noteId", "topicId"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["topicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("topicId")],
)
data class NoteTopicCrossRef(
    val noteId: String,
    val topicId: String,
)
```

复合主键使同一关联天然唯一；`noteId` 是主键首列，可服务按 Note 查询，额外只为反向 Topic 查询建立 `topicId` 索引。删除 Note 时 SQLite 自动清理关联；当前不提供删除 Topic，但外键仍以 CASCADE 保证未来显式删除时只解除关系、不触碰 Note。

### 2.3 SearchFtsEntity

```kotlin
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["entityType", "entityId", "searchableText"],
)
@Entity(tableName = "search_fts")
data class SearchFtsEntity(
    val entityType: String,
    val entityId: String,
    val searchableText: String,
    val normalizedTokens: String,
)
```

- 不声明业务主键；FTS4 使用隐式 INTEGER `rowid`；
- 只有 `normalizedTokens` 参与 MATCH；其余列用于对象映射和片段生成；
- `entityType` 固定为 `NOTE`、`LEARNING_ITEM`、`TOPIC`、`SESSION`；
- 不创建 `IMAGE` 搜索对象。Image Caption 合并进所属 Note 文档；
- `search_fts` 不保存业务状态、页码或关联关系，也不是备份真相；
- FTS4 无复合 UNIQUE 约束，`SearchIndexWriter` 以“先按 type/id 删除，再插入”维护一对象一行，查询层再以 `(entityType, entityId)` 防御性去重。

`MirraDatabase` 从 v2 升至 v3，仅加入 `TopicEntity`、`NoteTopicCrossRef`、`SearchFtsEntity` 和对应 DAO。`1.json`、`2.json` 必须保持字节级不变，新增 `3.json`。

## 3. Migration(2, 3)

`MIGRATION_2_3` 只创建下列结构，不修改或回填现有五张业务表：

```sql
CREATE TABLE IF NOT EXISTS `topics` (
    `id` TEXT NOT NULL,
    `name` TEXT COLLATE NOCASE NOT NULL,
    `createdAt` INTEGER NOT NULL,
    PRIMARY KEY(`id`)
);

CREATE UNIQUE INDEX IF NOT EXISTS `index_topics_name`
ON `topics` (`name`);

CREATE TABLE IF NOT EXISTS `note_topic_cross_refs` (
    `noteId` TEXT NOT NULL,
    `topicId` TEXT NOT NULL,
    PRIMARY KEY(`noteId`, `topicId`),
    FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`)
        ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`)
        ON UPDATE NO ACTION ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS `index_note_topic_cross_refs_topicId`
ON `note_topic_cross_refs` (`topicId`);

CREATE VIRTUAL TABLE IF NOT EXISTS `search_fts` USING FTS4(
    `entityType` TEXT NOT NULL,
    `entityId` TEXT NOT NULL,
    `searchableText` TEXT NOT NULL,
    `normalizedTokens` TEXT NOT NULL,
    tokenize=unicode61,
    notindexed=`entityType`,
    notindexed=`entityId`,
    notindexed=`searchableText`
);
```

实施时必须以 Room 2.8.5 实际生成的 `3.json` 为准核对虚表 createSql 的引号、列声明与 `notindexed` 语法；如生成结果与上述预期存在纯语法差异，只允许让 Migration DDL 精确匹配 Room 导出的同一语义结构，不能借机改变模型。

Migration 不在 SQL 中生成搜索 token：中文规范化是 Kotlin 规则，迁移事务结束后由启动 `SearchIndexRebuilder.ensureConsistent()` 首次填充空 FTS。这样 Migration 保持确定、快速，索引始终可从业务表恢复。

两条迁移链均注册：

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)
```

必须验证：

- v2 → v3：五张旧表及全部字段值不变，新 Topic/CrossRef/FTS 为空；
- v1 → v2 → v3：四张 v1 表数据不变，v2 图片表可用，v3 新结构可用；
- 迁移后执行 rebuild，旧 Note、Caption、Learning Item 与 Session Summary 可被搜索；
- 图片相对路径与 App 私有文件不被 Migration 读取、移动或删除。

## 4. TopicDao / TopicRepository API

### TopicDao

```kotlin
@Dao
interface TopicDao {
    @Insert suspend fun insert(topic: TopicEntity)
    @Query("SELECT * FROM topics WHERE id = :topicId")
    suspend fun get(topicId: String): TopicEntity?
    @Query("SELECT * FROM topics WHERE name = :normalizedName COLLATE NOCASE LIMIT 1")
    suspend fun getByName(normalizedName: String): TopicEntity?
    @Query("SELECT * FROM topics WHERE id = :topicId")
    fun observe(topicId: String): Flow<TopicEntity?>
    @Query("""SELECT topics.*, COUNT(note_topic_cross_refs.noteId) AS noteCount
              FROM topics LEFT JOIN note_topic_cross_refs
                ON note_topic_cross_refs.topicId = topics.id
              GROUP BY topics.id
              ORDER BY topics.name COLLATE NOCASE, topics.id""")
    fun observeAllWithNoteCount(): Flow<List<TopicListItem>>
    @Query("SELECT * FROM topics ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<TopicEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: NoteTopicCrossRef): Long
    @Query("DELETE FROM note_topic_cross_refs WHERE noteId = :noteId AND topicId = :topicId")
    suspend fun deleteCrossRef(noteId: String, topicId: String): Int
    @Query("""SELECT topics.* FROM topics
              INNER JOIN note_topic_cross_refs ON note_topic_cross_refs.topicId = topics.id
              WHERE note_topic_cross_refs.noteId = :noteId
              ORDER BY topics.name COLLATE NOCASE, topics.id""")
    fun observeForNote(noteId: String): Flow<List<TopicEntity>>
    @Query("""SELECT notes.*, learning_items.name AS learningItemName
              FROM notes
              INNER JOIN note_topic_cross_refs ON note_topic_cross_refs.noteId = notes.id
              INNER JOIN learning_items ON learning_items.id = notes.learningItemId
              WHERE note_topic_cross_refs.topicId = :topicId
              ORDER BY notes.createdAt DESC, notes.id DESC""")
    fun observeNotes(topicId: String): Flow<List<NoteListItem>>
}
```

### TopicRepository

```kotlin
sealed interface CreateTopicResult {
    data class Created(val topic: TopicEntity) : CreateTopicResult
    data class AlreadyExists(val topic: TopicEntity) : CreateTopicResult
}

enum class LinkTopicResult { LINKED, ALREADY_LINKED }
enum class UnlinkTopicResult { UNLINKED, NOT_LINKED }

interface TopicRepository {
    fun observeAll(): Flow<List<TopicListItem>>
    fun observe(topicId: String): Flow<TopicEntity?>
    fun observeForNote(noteId: String): Flow<List<TopicEntity>>
    fun observeNotes(topicId: String): Flow<List<NoteListItem>>
    suspend fun create(name: String): CreateTopicResult
    suspend fun createAndLink(noteId: String, name: String): CreateTopicResult
    suspend fun link(noteId: String, topicId: String): LinkTopicResult
    suspend fun unlink(noteId: String, topicId: String): UnlinkTopicResult
    suspend fun suggestions(noteId: String): List<TopicSuggestion>
}
```

事务规则：

- `create()`：规范化 → 同事务查重 → insert Topic → 写 Topic 搜索文档；唯一约束竞争时读取已存在 Topic 并返回 `AlreadyExists`；
- `createAndLink()`：同一事务内验证 Note 存在，创建或取得既有 Topic，再插入 CrossRef；返回结果要让 UI 区分“新建并关联”和“已存在并关联”；
- `link()`：同一事务内复核 Note 与 Topic 均存在，再 `INSERT OR IGNORE`；重复关联返回 `ALREADY_LINKED`；
- `unlink()`：同一事务内复核 Note 与 Topic 存在，删除关联；重复解除返回 `NOT_LINKED`，不破坏数据；
- CrossRef 变化不重建 Note 搜索文档，因为 Topic 名称是独立 Topic 搜索源，不拼入 Note 正文/Caption；
- Repository 不暴露 `deleteTopic()` 或 `renameTopic()`。

## 5. NoteTopicCrossRef 数据流程

```text
Note 详情打开
→ observeForNote(noteId)
→ 显示已关联 Topic chips
→ 用户点击“关联 Topic”
→ 本地过滤已有 Topic + 展示保守建议
→ 用户确认 link / createAndLink
→ CrossRef 事务提交
→ Room Flow 自动刷新 chips 与 Topic 详情 Note 数量
```

解除关联：

```text
点击已关联 Topic 的移除按钮
→ 明确确认“仅解除关联，不删除笔记”
→ unlink(noteId, topicId)
→ CrossRef 删除
→ Note、Topic 和 SearchFts 业务文档均保留
```

删除 Note：现有 `NoteRepository.delete()` 的文件暂存成功后，在原数据库事务中删除 Note。SQLite 同时 CASCADE CrossRef 和 ImageAsset 行；`SearchIndexWriter.remove(NOTE, noteId)` 在相同数据库事务中删除派生搜索文档。DB 失败时文件恢复，CrossRef/FTS 随事务回滚。

不允许 UI 直接调用 DAO；所有关联存在性检查和写入都在 Repository 事务内完成。

## 6. TopicSuggester

只实现一个纯 Kotlin、确定性的小类：

```kotlin
data class TopicSuggestion(val topic: TopicEntity, val reason: String)

class TopicSuggester(
    private val normalize: (String) -> String = TopicNameNormalizer::normalize,
) {
    fun suggest(
        noteContent: String,
        existingTopics: List<TopicEntity>,
        linkedTopicIds: Set<String>,
        limit: Int = 5,
    ): List<TopicSuggestion>
}
```

规则固定为：

- 对 Note 正文与 Topic 名称使用相同 NFKC、空白折叠和 `Locale.ROOT` 小写比较值；
- 只有规范 Topic 名称至少 2 个 Unicode code point，且正文明确包含完整名称时才建议；
- 已关联 Topic 排除；
- 最长名称优先，其次 `name COLLATE NOCASE` 等价的稳定字典序，最多 5 条；
- Topic 输入框的精确名称匹配由 Repository `getByName()` 完成，可命中一个字符 Topic；正文自动建议不使用单字符名称，避免噪声；
- 建议只显示，用户点击后才调用 `link()`；不得自动创建、自动关联或从任意关键词推断新 Topic；
- 不增加接口体系、权重模型或学习反馈。

## 7. SearchFts Schema 与一对象一行

四类搜索文档：

| entityType | entityId | searchableText | normalizedTokens 来源 |
|---|---|---|---|
| `NOTE` | Note ID | Note 正文 + 每张非空 Caption（换行分隔） | 聚合文本统一规范化 |
| `LEARNING_ITEM` | Learning Item ID | 名称 | 名称规范化 |
| `TOPIC` | Topic ID | 名称 | 名称规范化 |
| `SESSION` | Session ID | 非空 `generatedSummary` | Summary 规范化 |

关键规则：

- 同一 Note 无论正文和多少个 Caption 命中，都只有一条 FTS 行和一个结果；
- Caption 按 `createdAt ASC, id ASC` 聚合，确保相同业务数据生成相同文档；
- 空 Caption 不写入；但 Note 正文非空，因此 Note 文档始终存在；
- `generatedSummary` 为空的 Session 不建文档；
- 写入采用 `DELETE WHERE entityType=:type AND entityId=:id` 后 `INSERT`，二者必须包含在调用方 Room 事务内；
- 重建前先清空 FTS，然后一次性插入全量文档；失败由事务回滚；
- 查询结果再 `distinctBy(entityType to entityId)`，防御人工损坏或旧缺陷造成的重复行。

`SearchFtsDao` 最小 API：

```kotlin
@Dao
interface SearchFtsDao {
    @Query("DELETE FROM search_fts WHERE entityType = :type AND entityId = :id")
    suspend fun delete(type: String, id: String): Int
    @Query("DELETE FROM search_fts")
    suspend fun clear()
    @Query("INSERT INTO search_fts(entityType, entityId, searchableText, normalizedTokens) VALUES (:type, :id, :text, :tokens)")
    suspend fun insert(type: String, id: String, text: String, tokens: String)
    @Query("SELECT COUNT(*) FROM search_fts")
    suspend fun count(): Int
    @Query("SELECT entityType, entityId, searchableText FROM search_fts")
    suspend fun dump(): List<SearchDocumentRow>
    @Query(SEARCH_SQL)
    suspend fun search(matchQuery: String, limit: Int): List<SearchHitRow>
}
```

如果 Room 编译器不接受以 `@Query` 声明 FTS INSERT，实施时改用 `@Insert` 且保持相同实体字段和隐式 rowid；这只是 Room DAO 表达方式，不改变 Schema 或行为。

## 8. 中文 normalization / token 方案

`SearchTextNormalizer` 与 `SafeMatchQueryBuilder` 为纯 Kotlin，可在 JVM 完整测试。

### 8.1 索引规范化

按 Unicode code point 扫描，避免拆坏补充平面的汉字：

1. 输入做 NFKC；
2. 使用 `Locale.ROOT` 小写；
3. `Character.UnicodeScript.HAN` 的连续区段单独处理；
4. 汉字段长度 ≥2 时生成所有相邻双字 token；长度 1 不建单字 token；
5. Unicode 字母/数字的连续区段保留为普通 token，Latin 和数字边界按标点/空白分开；
6. 标点、换行和符号作为分隔符；
7. Emoji 保留在 `searchableText` 供显示，但不生成 token；Emoji-only 查询视为空搜索；
8. token 之间只保留一个半角空格。

示例：

```text
心理账户                → 心理 理账 账户
Kotlin Coroutines 2026 → kotlin coroutines 2026
Kotlin心理账户2026      → kotlin 心理 理账 账户 2026
“心理，账户！”          → 心理 账户
📚心理账户              → 心理 理账 账户
心                      → （无 token）
```

“心理，账户”不会跨标点生成“理账”，因为它不是连续 CJK 区段；这是有意避免伪造原文相邻关系。

### 8.2 查询规范化与 MATCH 安全

查询复用同一 token 生成规则；多 token 采用 AND，确保混合查询的所有明确词都出现：

```kotlin
fun buildMatchQuery(raw: String): MatchQuery? {
    val tokens = SearchTextNormalizer.tokens(raw)
    if (tokens.isEmpty()) return null
    return MatchQuery(tokens.joinToString(" AND ") { token ->
        "\"${token.replace("\"", "\"\"")}\""
    })
}
```

最终 MATCH 字符串只来自规范化后的字母、数字与 Han token，并仍做双引号防御性转义；SQL 使用 Room 参数绑定：

```sql
WHERE search_fts MATCH :matchQuery
```

绝不把原始输入拼入 SQL。`AND`、`OR`、`NOT`、`NEAR` 等 FTS 保留字会变成引号内的普通词；孤立引号、括号、减号、星号和 Emoji 不会造成 MATCH 语法错误。中文单字查询提示“中文至少输入 2 个连续文字”，不建立高噪声单字索引。

英文首版使用完整 token 精确匹配，不增加 stemming、模糊搜索或前缀 `*` 语法；这让行为简单且可测试。FTS4 `unicode61` 继续负责标准 Unicode token 化。

## 9. SearchRepository / SearchEngine

### 9.1 SearchEngine

`SearchEngine` 只承担无数据库的确定性转换：

```kotlin
interface SearchEngine {
    fun buildDocument(parts: List<String?>): BuiltSearchDocument
    fun buildQuery(rawQuery: String): MatchQuery?
    fun buildSnippet(searchableText: String, query: MatchQuery, maxChars: Int = 96): String
}

data class BuiltSearchDocument(
    val searchableText: String,
    val normalizedTokens: String,
)

data class MatchQuery(
    val expression: String,
    val tokens: List<String>,
)
```

- `buildDocument()` trim 各段、丢弃空段、以换行连接原文，再生成 tokens；
- `buildSnippet()` 在原文各段中寻找第一个包含规范化 token 的段落，截取不超过 96 个字符的 code-point 安全窗口并添加省略号；找不到时显示原文开头；
- 不使用 SQLite `snippet()`/offsets 计算衍生双字 token 的位置，避免高亮位置与原文错位；首版不做富文本高亮。

### 9.2 SearchRepository

```kotlin
sealed interface SearchStateResult {
    data object EmptyQuery : SearchStateResult
    data class Success(val groups: List<SearchResultGroup>) : SearchStateResult
}

interface SearchRepository {
    suspend fun search(rawQuery: String, limit: Int = 60): SearchStateResult
    suspend fun rebuildIndex(): SearchRebuildReport
}
```

DAO 首先返回最多 60 条命中，再由 Repository 按类型批量加载正式业务对象，丢弃不存在的 orphan FTS 行，构造类型安全结果与片段。禁止对 60 条结果逐条查询；每种类型最多一次 `WHERE id IN (...)` 批量查询。

排序由 SQL 使用业务时间降序完成，不实现自定义排名：

```sql
SELECT entityType, entityId, searchableText,
       CASE entityType
         WHEN 'NOTE' THEN (SELECT updatedAt FROM notes WHERE id = entityId)
         WHEN 'LEARNING_ITEM' THEN (SELECT updatedAt FROM learning_items WHERE id = entityId)
         WHEN 'TOPIC' THEN (SELECT createdAt FROM topics WHERE id = entityId)
         WHEN 'SESSION' THEN (SELECT COALESCE(endedAt, startedAt) FROM study_sessions WHERE id = entityId)
       END AS sourceTimestamp
FROM search_fts
WHERE search_fts MATCH :matchQuery
ORDER BY sourceTimestamp DESC, entityType ASC, entityId ASC
LIMIT :limit
```

Repository 维持该顺序，再按类型生成 Note、Learning Item、Topic、Session 四组。FTS4 首版不实现 BM25、自定义 `matchinfo()` 排名或搜索历史。

第一次查询如遇 SQLite FTS 异常，Repository 允许自动调用一次 `SearchIndexRebuilder.rebuild()` 后重试一次；再次失败即返回 UI 错误，不能无限重试或清空业务数据。

## 10. SearchIndexRebuilder

```kotlin
data class SearchRebuildReport(
    val indexedDocuments: Int,
    val noteCount: Int,
    val learningItemCount: Int,
    val topicCount: Int,
    val sessionCount: Int,
)

interface SearchIndexRebuilder {
    suspend fun ensureConsistent(): SearchRebuildReport?
    suspend fun rebuild(): SearchRebuildReport
}
```

`rebuild()` 在一个 Room transaction 内：

1. 从正式表读取全部 Learning Item；
2. 读取全部 Note 及其 Caption，并按 Note 聚合；
3. 读取全部 Topic；
4. 读取 `generatedSummary` 非空的 Session；
5. 用同一 `SearchEngine` 生成确定性文档；
6. 清空 `search_fts`；
7. 插入全量文档；
8. 返回分类计数。

事务中途抛错时 FTS 修改回滚，业务表从未被写入；下次调用可重新执行。连续 rebuild 两次应生成相同 `(type,id,text,tokens)` 集合。

`ensureConsistent()` 在启动恢复 Session 和图片文件协调完成后执行：

- 计算期望文档数：Learning Item 数 + Note 数 + Topic 数 + 非空 Summary Session 数；
- 与 FTS 行数不一致时 rebuild；
- v2 → v3 首次启动时 FTS 为空，因此自动生成旧数据索引；
- 数量一致时不在每次冷启动重建全部个人数据。

应用内所有受支持写入都采用同事务索引同步，所以“数量相同但内容静默过期”不应由正常流程产生。显式修复入口和首次 FTS 查询异常的单次 rebuild 重试负责异常恢复；不增加索引版本表、WorkManager 或定时任务。

## 11. 索引同步策略

新增内部 `SearchIndexWriter`，它不是通用事件系统：

```kotlin
class SearchIndexWriter(
    private val database: MirraDatabase,
    private val engine: SearchEngine,
) {
    suspend fun reindexNote(noteId: String)
    suspend fun removeNote(noteId: String)
    suspend fun reindexLearningItem(itemId: String)
    suspend fun reindexTopic(topicId: String)
    suspend fun reindexSession(sessionId: String)
}
```

这些方法不自行开启嵌套事务；调用方必须已经位于 `database.withTransaction` 中。方法读取正式行、构造文档并替换 FTS 行。

| 业务事件 | 同一事务内动作 |
|---|---|
| Note 创建 | 写 Note 后 `reindexNote` |
| Note 编辑 | 更新 Note 后 `reindexNote` |
| Note 删除 | 删除 Note 后 `removeNote` |
| Caption 创建/编辑/清空 | 更新 ImageAsset 后 `reindexNote(noteId)` |
| 图片导入 | 插入 ImageAsset 后 `reindexNote(noteId)` |
| 图片删除 | 删除 ImageAsset 后 `reindexNote(noteId)` |
| Learning Item 创建 | 插入后 `reindexLearningItem` |
| Learning Item 改名 | 当前无改名能力，不新增 API；未来实现时必须调用同一 writer |
| Topic 创建 | 插入后 `reindexTopic` |
| Topic 改名/删除 | Module 2C 不允许，不新增 API |
| Topic 关联/解除 | 不改 FTS；Topic 不作为 Note 文档的一部分 |
| Session Summary 生成 | `finishSession()` 写 Summary 后 `reindexSession` |
| ABNORMAL/无 Summary Session | `reindexSession` 会删除旧行且不插入空文档 |

现有 Repository 改动要求：

- `NoteRepository.save()` 当前部分路径未包完整事务；2C 将“校验/写 Note/重建 Note 文档”合并进一个事务，不改变自动保存语义；
- `ImageRepository` 保留文件系统补偿顺序，只在现有 DB insert/update/delete transaction 内重建 Note 文档；DB 失败时 FTS 与 ImageAsset 一并回滚，正式文件仍按 2B 规则补偿；
- `NoteRepository.delete()` 保留 trash 补偿，只把 FTS 删除放进现有删除事务；
- `LearningItemRepository.create()` 改为 insert + index 的短事务；生命周期状态变化不影响名称文档，无需重复索引；
- `StudyWorkflowRepository.finishSession()` 在已有结束事务中索引最终 Summary；
- `TopicRepository` 在创建事务中索引 Topic。

## 12. Search 结果模型

```kotlin
sealed interface SearchResult {
    val entityId: String
    val snippet: String

    data class Note(
        override val entityId: String,
        override val snippet: String,
        val learningItemName: String,
        val pageNumber: Int?,
        val updatedAt: Long,
    ) : SearchResult

    data class LearningItem(
        override val entityId: String,
        override val snippet: String,
        val name: String,
        val status: LearningItemStatus,
        val currentPage: Int,
        val totalPages: Int,
    ) : SearchResult

    data class Topic(
        override val entityId: String,
        override val snippet: String,
        val name: String,
        val noteCount: Int,
    ) : SearchResult

    data class Session(
        override val entityId: String,
        override val snippet: String,
        val learningItemName: String,
        val startedAt: Long,
        val endedAt: Long?,
    ) : SearchResult
}

data class SearchResultGroup(
    val type: SearchResultType,
    val results: List<SearchResult>,
)
```

导航映射固定：

- Note → `NoteDetailRoute(noteId)`；
- Learning Item → `LearningItemDetailRoute(itemId)`；
- Topic → `TopicDetailRoute(topicId)`；
- Session → `SessionSearchDetailRoute(sessionId)`，复用现有 Summary 内容，以“阅读记录”标题和“返回”按钮展示。

搜索不创建 SourceLocator、跨对象上下文图、搜索结果数据库表或历史页面。

## 13. Compose UI / Navigation

### 13.1 新路由

```kotlin
@Serializable data object TopicListRoute : NavKey
@Serializable data object CreateTopicRoute : NavKey
@Serializable data class TopicDetailRoute(val topicId: String) : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data class SessionSearchDetailRoute(val sessionId: String) : NavKey
```

### 13.2 知识页面

- 在“全部笔记”“全部图片”附近增加“搜索”和“Topic”入口；
- 全局创建弹窗增加“创建 Topic”，导航到极简 `CreateTopicScreen`；
- 创建成功后返回并打开 `TopicDetailRoute`；重复名称显示“Topic 已存在”，用户可打开既有 Topic；
- 不增加新的顶层底部导航项。

### 13.3 Topic 列表与详情

`TopicListViewModel`：订阅 `observeAll()`；页面按名称稳定排序，显示名称与“n 条笔记”，空状态为“还没有 Topic”。提供创建按钮。

`TopicDetailViewModel`：同时订阅 Topic 与 `observeNotes(topicId)`；页面显示名称及按 Note 创建时间倒序的相关 Note，点击进入 `NoteDetailRoute`。Topic 不存在时显示轻量错误和返回，不提供编辑/删除。

### 13.4 Note 详情关联

- 只有已首次保存、拥有固定 noteId 的 Note 显示 Topic 区；Session 外新 Note 在正文合法并首次保存前不创建 CrossRef；
- 展示已关联 Topic chips，点击名称打开 Topic 详情；移除按钮先确认“仅解除关联”；
- “关联 Topic”打开一个轻量 ModalBottomSheet：输入框、本地已有 Topic 过滤、最多 5 条正文建议、创建新 Topic；
- 输入与已有 Topic 规范名称完全匹配时优先显示“关联已有 Topic”；
- 新名称创建成功后立即关联当前 Note；唯一约束竞争返回既有 Topic，再由当前操作完成关联；
- 所有关联写入显示短暂提交状态，失败保留 Sheet 和用户输入，可重试。

`NoteEditorViewModel` 增加 `TopicRepository` 依赖、`linkedTopics` Flow 以及 `linkTopic/createAndLink/unlink` 事件；不改变正文 500ms debounce、图片 Caption flush 或 Note 页码规则。

### 13.5 搜索页

`SearchViewModel` 使用 `MutableStateFlow<String>`、`debounce(300)`、`distinctUntilChanged()`、`flatMapLatest`/可取消协程执行本地查询。

页面状态：

- 初始空：说明可搜索笔记、图片说明、书名、Topic 和阅读总结；
- 中文单字/无有效 token：提示输入更完整的词；
- 搜索中：只显示轻量行内进度，不使用网络式全屏 Loading；
- 有结果：按 Note / Learning Item / Topic / Session 分组，组内保持 SQL 时间排序；
- 无结果：显示“没有找到相关内容”；
- 错误：显示“本地搜索索引需要修复”，提供一次“修复并重试”；
- 不增加筛选器、排序选择、搜索历史或在线状态。

## 14. 性能限制

- 输入 debounce：300ms；新输入取消旧查询；
- 查询上限：全局 `LIMIT 60`，Repository 不允许 UI 传入超过 60；
- 排序：业务更新时间/创建时间降序，再按 type/id 稳定排序；不计算相关性分数；
- 数据规模假设：单用户个人知识库约 10,000 个可搜索业务对象、每条 Note 少量 Caption；
- rebuild 一次性读取这些对象并在 IO dispatcher/Room transaction 中执行，不阻塞 Compose 主线程；
- 搜索命中后按最多四个类型批量取正式数据，避免 N+1；
- 不使用 Paging 3；60 条结果足以满足首版“快速找回”，扩大规模应基于真实性能证据另立任务；
- Instrumented 性能烟测以 5,000 个混合文档验证结果上限和无明显主线程阻塞；不把易波动的毫秒阈值设成 CI 硬失败，API 37 模拟器记录实际耗时供验收。

## 15. 错误与恢复 UX

Topic：

- 空名称：输入框原位提示“Topic 名称不能为空”；
- 重复名称：提示“Topic 已存在”，可打开或关联既有 Topic；
- Note/Topic 已被删除：保持当前页面并提示“内容已变化，请返回重试”；
- 重复关联/解除：按幂等结果轻提示，不显示技术错误；
- 冷启动关系由 Room 恢复，不需要网络或重新关联。

Search：

- 空查询不访问数据库；
- 特殊字符或保留字经安全 query builder 处理，不把 SQLite 错误直接显示给用户；
- orphan FTS 命中会被 Repository 丢弃，并请求一次 rebuild 修复；
- FTS 查询异常只自动 rebuild+重试一次；失败后提供显式修复按钮；
- rebuild 失败不修改业务表，显示“笔记仍然安全，可稍后重试搜索”；
- Migration 失败禁止 destructive fallback，保留数据库供诊断；
- 搜索结果目标在点击前已删除时，详情页显示“内容不存在”并允许返回。

## 16. 自动化测试计划

### 16.1 JVM tests

`TopicNameNormalizerTest`：

- NFKC 把全角 Latin/数字规范为半角；
- Unicode 首尾空白删除、连续空白折叠；
- 大小写显示值保留、比较值使用 `Locale.ROOT`；
- 空白输入拒绝。

`TopicSuggesterTest`：

- 正文完整包含已有名称才建议；
- 单字符、已关联和部分相似名称不建议；
- 最长优先、稳定排序、最多 5 条；
- 不创建或关联任何数据。

`SearchEngineTest`：

- `心理账户 → 心理 理账 账户`；
- 中文标点不跨区段生成 token；
- 英文大小写、数字、中英文混合；
- Emoji 不生成 token；
- 中文单字/纯标点为空查询；
- 引号、括号、星号、减号和 FTS 保留字生成合法引号表达式；
- 多词用 AND；
- Caption 与正文聚合顺序确定；
- snippet 长度和 Unicode code point 边界正确。

### 16.2 Room / Instrumented tests

`MigrationTwoToThreeTest`：用真实 `2.json` 建库，写入 Learning Item、Intent、Session、Note、ImageAsset/Caption 与图片相对路径，迁移至 v3，验证旧值逐字段保留、外键有效、新表/FTS 可写。

`MigrationOneToThreeTest`：用真实 `1.json` 建库，写入 Phase 1 数据，通过 `MIGRATION_1_2 + MIGRATION_2_3` 连续升级，确认全部原值、v2 图片表和 v3 新结构可用。

`ModuleTwoCTopicRepositoryTest`：

- 创建、trim、NFKC、ASCII 大小写重复、空名称；
- 一 Note 多 Topic、一 Topic 多 Note；
- 重复 link 幂等、unlink 幂等；
- createAndLink 原子性；
- Note 不存在时不创建孤立关联；
- 删除 Note 后 CrossRef CASCADE；
- 关闭并重开文件数据库后关系仍存在；
- 没有 Topic 删除/改名公开 API。

`ModuleTwoCSearchRepositoryTest`：

- 中文双字、英文和混合查询；
- Note 正文、Caption、Learning Item、Topic、Session Summary 各自命中；
- 正文和多 Caption 同时命中仍仅一个 Note；
- Caption 更新后旧词消失、新词命中；
- Caption 清空/图片删除后索引更新；
- Note 编辑后同步、Note 删除后不命中；
- Learning Item/Topic 创建后命中；
- Session 正常结束生成 Summary 后命中；
- 特殊字符、保留字、空查询不抛 MATCH 错误；
- `LIMIT 60`、稳定时间排序、无 N+1 行为；
- 事务故障时业务写入与 FTS 同时回滚。

`SearchIndexRebuilderTest`：

- 手动清空 FTS 后完整恢复五类来源（Caption 归入 Note）；
- 连续 rebuild 两次文档集合一致；
- rebuild 故障回滚 FTS 且业务表逐字段不变；
- orphan/重复 FTS 行经 rebuild 清理；
- v3 首启 count mismatch 自动 rebuild；
- 无文档数据库不做无意义写入。

### 16.3 Compose / end-to-end tests

- 知识页能进入搜索和 Topic；创建 Topic 后打开详情；
- Topic 列表显示计数，详情打开相关 Note；
- Note 详情关联已有 Topic、创建并关联、确认解除；
- 重复名称与空名称错误可见；
- 搜索 debounce、初始/无结果/错误/修复状态；
- 四类结果展示与导航；Session 结果复用只读 Summary；
- 多 Caption 命中 UI 只显示一个 Note；
- 原 Note 自动保存、Caption 保存、图片预览/删除、Learning Item 生命周期和 Phase 1 Session 闭环回归。

### 16.4 全量命令与设备验证

```powershell
.\gradlew testDebugUnitTest
.\gradlew connectedDebugAndroidTest
.\gradlew lintDebug
.\gradlew assembleDebug
```

API 37 模拟器还需手动验证：APK 覆盖安装保留 2B 数据；离线冷启动；旧 Caption 可搜索；创建 Topic 并跨两条 Note 查看；中文/英文/特殊字符搜索；从四类结果往返；创建 Active Session、强停、冷启动异常恢复；完整 Phase 1 学习闭环；2B 相册/相机已有图片仍可查看。

## 17. 预计新增 / 修改文件

### 新增

- `app/src/main/java/com/guanyi/mirra/data/local/entity/TopicEntities.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/entity/SearchFtsEntity.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/TopicDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/SearchFtsDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/model/TopicListItem.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/model/SearchModels.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/TopicRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/SearchRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/search/SearchIndexWriter.kt`
- `app/src/main/java/com/guanyi/mirra/data/search/SearchIndexRebuilder.kt`
- `app/src/main/java/com/guanyi/mirra/domain/TopicNameNormalizer.kt`
- `app/src/main/java/com/guanyi/mirra/domain/TopicSuggester.kt`
- `app/src/main/java/com/guanyi/mirra/domain/SearchEngine.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/TopicScreens.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/TopicPickerSheet.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/SearchScreen.kt`
- `app/src/test/java/com/guanyi/mirra/domain/TopicNameNormalizerTest.kt`
- `app/src/test/java/com/guanyi/mirra/domain/TopicSuggesterTest.kt`
- `app/src/test/java/com/guanyi/mirra/domain/SearchEngineTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/MigrationTwoToThreeTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/MigrationOneToThreeTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/ModuleTwoCTopicRepositoryTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/ModuleTwoCSearchRepositoryTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/data/SearchIndexRebuilderTest.kt`
- `app/src/androidTest/java/com/guanyi/mirra/ModuleTwoCFlowTest.kt`
- `app/schemas/com.guanyi.mirra.data.local.MirraDatabase/3.json`
- `docs/checkpoints/2026-09-11-module-2c.md`（只在实现与验收证据齐全后创建）

### 修改

- `app/src/main/java/com/guanyi/mirra/data/local/MirraDatabase.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/Migrations.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/LearningItemDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/NoteDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/SessionDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/ImageAssetDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/LearningItemRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/NoteRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/ImageRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt`
- `app/src/main/java/com/guanyi/mirra/di/AppContainer.kt`
- `app/src/main/java/com/guanyi/mirra/navigation/Routes.kt`
- `app/src/main/java/com/guanyi/mirra/MirraApp.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/KnowledgeScreen.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/NoteEditorScreen.kt`
- `app/src/main/java/com/guanyi/mirra/feature/session/SessionSummaryScreen.kt`
- `app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt`
- 受 Repository 构造参数影响的现有测试装配文件
- `docs/CURRENT_STATE.md`

### 不修改

- `app/build.gradle.kts` 依赖列表原则上不变；Room FTS4 属于现有 Room 能力；
- `1.json`、`2.json`；
- 图片目录与文件格式；
- Phase 1/2A/2B Entity 字段与索引；
- `PRODUCT_SPEC.md`、`DECISIONS.md`，除非实施中发现已冻结规范和真实平台能力冲突并先获得用户确认。

## 18. 过度设计检查

- 一对象一条 FTS 文档直接解决去重，不建立命中明细表；
- Topic 只有三个字段和一个 CrossRef，不加入删除、改名、描述、别名或图谱；
- 建议只做已有名称包含，不做 NLP、评分、学习或自动操作；
- 搜索只有四种对象、300ms debounce 和 60 条上限，不加 Paging、排名引擎、搜索历史或高级筛选；
- 只用 Room 2.8.x FTS4 + Kotlin 双字 token，不迁 Room 3，不引入 Lucene/向量库；
- 索引通过 Repository 事务同步与一个 rebuild 服务维护，不加事件总线、后台任务或索引版本表；
- Session 搜索结果复用现有 Summary UI，不提前建设历史模块；
- 现有手工 DI、单 app Module 与 Navigation 3 继续使用；
- 新增的 `SearchIndexWriter`、`SearchIndexRebuilder`、`SearchEngine` 各自只有一个明确职责，均由当前需求直接驱动。

结论：Schema v3 的三种新增结构均为已确认需求的最小必要集合；规划没有提前进入 2D 或未来知识系统。

## 19. Module 2C 最终验收标准

1. 可以创建非空 Topic；NFKC、空白和 ASCII 大小写重复规则有确定结果。
2. Topic 列表显示名称和 Note 数量，Topic 详情显示相关 Note。
3. 一条 Note 可关联多个 Topic，一个 Topic 可关联多条 Note，重复关联不重复。
4. Note 详情可关联已有 Topic、创建并立即关联、确认解除关联。
5. Topic 建议只来自正文明确包含的已有名称，不自动创建或关联。
6. Module 2C 不提供 Topic 删除/改名、图谱、关系或 AI 能力。
7. Room 从 v2 非破坏性迁移到 v3；v1 → v2 → v3 连续迁移也通过。
8. `1.json`、`2.json` 不变，新增且验证 `3.json`；不存在 destructive migration。
9. Phase 1、2A、2B 的 Learning Item、Intent、Session、Note、ImageAsset、Caption、进度及图片相对路径完整保留。
10. 搜索可命中 Note 正文、图片 Caption、Learning Item 名称、Topic 名称和非空 Session Summary。
11. 中文双字、英文、数字与中英文混合查询结果正确。
12. 特殊字符、Emoji、引号及 FTS 保留字不会造成非法 MATCH 或 SQL 拼接风险。
13. 同一 Note 的正文和多 Caption 同时命中时只显示一个 Note 结果。
14. Note/Caption/图片/Topic/Learning Item/Session Summary 的已实现写路径同步更新索引。
15. Note、图片或 Caption 删除/清空后旧内容不再命中；文件补偿流程无回归。
16. `SearchIndexRebuilder` 可在清空或损坏索引后恢复，幂等且不修改业务表。
17. 搜索结果按类型展示，并能进入 Note、Learning Item、Topic 和只读 Session Summary。
18. 搜索输入使用 300ms debounce、全局最多 60 条，并在个人数据规模下无明显主线程阻塞。
19. 全部 JVM、Room、Instrumented、Compose、lintDebug、assembleDebug 实际通过。
20. APK 覆盖安装、完全离线、强停/冷启动、Phase 1 学习闭环、2A Note 管理与 2B 图片闭环回归通过。
21. 只产生 Module 2C 相关修改，无敏感信息、调试残留或未来模块空实现。
22. `CURRENT_STATE.md` 与 Module 2C checkpoint 按真实验证结果更新，独立 Conventional Commit 并 Push 当前功能分支。
23. 完成报告列出 Schema、Migration、索引同步/重建、全部测试证据、Git SHA、偏差、风险和未完成项。
24. 完成后停止，不自动进入 Module 2D。

## 20. 测试驱动实施顺序

### Task 1: Unicode 规则与搜索纯函数

**Files:**
- Create: `app/src/main/java/com/guanyi/mirra/domain/TopicNameNormalizer.kt`
- Create: `app/src/main/java/com/guanyi/mirra/domain/TopicSuggester.kt`
- Create: `app/src/main/java/com/guanyi/mirra/domain/SearchEngine.kt`
- Test: 三个对应 JVM test 文件

**Interfaces:**
- Produces: 第 6、8、9 节的精确类型与方法。

- [ ] 写 normalization、suggestion、token/MATCH/snippet 的失败测试。
- [ ] 运行 `./gradlew testDebugUnitTest`，确认新增测试因实现缺失而失败。
- [ ] 实现最小纯 Kotlin 规则。
- [ ] 再次运行 JVM tests，确认全部通过。
- [ ] 提交 `feat(search): add deterministic text normalization`。

### Task 2: Schema v3 与真实迁移

**Files:**
- Create: Topic/Search entities、DAO、models、两个 Migration 测试。
- Modify: `MirraDatabase.kt`、`Migrations.kt`、`AppContainer.kt` migration registration。
- Generate: `app/schemas/com.guanyi.mirra.data.local.MirraDatabase/3.json`

**Interfaces:**
- Consumes: Task 1 的规范化规则仅用于迁移后 rebuild，不写入 Migration SQL。
- Produces: `TopicDao`、`SearchFtsDao`、`MIGRATION_2_3`。

- [ ] 写 v2→v3 与 v1→v3 失败测试，并逐字段断言旧数据。
- [ ] 运行目标 Instrumented tests，确认 v3/Migration 尚不存在导致失败。
- [ ] 增加最小 Entity、DAO、Database version 与 DDL。
- [ ] 生成 `3.json`，核对 DDL；确认 `1.json`、`2.json` diff 为空。
- [ ] 运行两个 Migration tests 与 schema validation，确认通过。
- [ ] 提交 `feat(database): add topic and search schema v3`。

### Task 3: Topic Repository 与关系事务

**Files:**
- Create: `TopicRepository.kt`、`ModuleTwoCTopicRepositoryTest.kt`
- Modify: `AppContainer.kt`、`TestAppContainer.kt`

**Interfaces:**
- Consumes: `TopicDao`、`TopicNameNormalizer`、`SearchIndexWriter.reindexTopic()`。
- Produces: 第 4 节 `TopicRepository` API。

- [ ] 写创建、重复、空名、多对多、幂等、CASCADE 和冷启动失败测试。
- [ ] 运行目标测试，确认 Repository 缺失导致失败。
- [ ] 实现最小事务与结果类型。
- [ ] 运行 Topic Repository 与既有 Note 删除测试。
- [ ] 提交 `feat(topics): add lightweight note associations`。

### Task 4: Search 索引写入、查询与重建

**Files:**
- Create: `SearchIndexWriter.kt`、`SearchIndexRebuilder.kt`、`SearchRepository.kt` 及三组 Instrumented tests。
- Modify: 四个现有 Repository 与所需 DAO 批量查询。

**Interfaces:**
- Consumes: `SearchEngine`、`SearchFtsDao`、正式业务 DAO。
- Produces: 第 9–12 节索引、搜索、重建和结果模型。

- [ ] 写五类来源、去重、同步、删除、安全 MATCH、排序/上限和事务回滚测试。
- [ ] 运行目标测试，确认索引服务缺失导致失败。
- [ ] 实现一对象一行 writer、批量 hydration 与 rebuild。
- [ ] 将每个已确认写事件接入现有事务。
- [ ] 运行 Search/Topic/Module2B 文件补偿与 Phase1 Repository 回归测试。
- [ ] 提交 `feat(search): add rebuildable local fts index`。

### Task 5: Topic 与 Search Compose 闭环

**Files:**
- Create: `TopicScreens.kt`、`TopicPickerSheet.kt`、`SearchScreen.kt`、`ModuleTwoCFlowTest.kt`
- Modify: `KnowledgeScreen.kt`、`NoteEditorScreen.kt`、`SessionSummaryScreen.kt`、`Routes.kt`、`MirraApp.kt`

**Interfaces:**
- Consumes: `TopicRepository`、`SearchRepository` 与第 12 节导航模型。
- Produces: 第 13、15 节 UI/UX。

- [ ] 写 Topic 创建/关联/解除与四类搜索导航的 Compose 失败测试。
- [ ] 运行目标 Compose tests，确认新入口/路由缺失导致失败。
- [ ] 实现最小 ViewModel、页面、Sheet 与 Navigation 3 entries。
- [ ] 运行 Module 2C Flow、Module 2A、Module 2B 和 Phase 1 Compose tests。
- [ ] 提交 `feat(knowledge): add topic and search flows`。

### Task 6: 启动恢复、全量验收与文档冻结

**Files:**
- Modify: `AppContainer.kt`、`docs/CURRENT_STATE.md`
- Create: `docs/checkpoints/2026-09-11-module-2c.md`

**Interfaces:**
- Consumes: `SearchIndexRebuilder.ensureConsistent()`。
- Produces: 可验证的 Module 2C 冻结 checkpoint。

- [ ] 把索引一致性检查加入启动恢复顺序，并测试 v3 首启自动 rebuild。
- [ ] 运行全部 JVM、Instrumented、Compose、lint 和 debug build。
- [ ] 用 API 37 模拟器覆盖安装并执行离线、搜索、Topic、强停/冷启动及 Phase 1/2A/2B 回归。
- [ ] 检查 schema diff、Git diff、临时文件、调试输出和敏感信息。
- [ ] 按真实证据更新 CURRENT_STATE/checkpoint；未运行项明确标记 Not Run 及原因。
- [ ] 提交 `feat(search): complete module 2c topic search` 并在用户授权范围内 Push 当前功能分支。
- [ ] 停止，不进入 Module 2D。

## 21. 规划自检结果

- Spec coverage：用户要求的 19 项分别由第 1–19 节覆盖；实施顺序在第 20 节。
- Scope coverage：未规划 Topic 删除/改名、AI/OCR、分析预测或其他禁止项。
- Migration coverage：同时覆盖 v2→v3、v1→v2→v3、旧数据逐字段保留及 v3 首启 rebuild。
- Consistency coverage：所有现有业务写事件都有明确事务同步点；Caption 统一聚合为 Note 文档。
- Type consistency：Topic/Search/重建/结果类型在前文有唯一命名，后续任务沿用同一签名。
- Placeholder scan：计划没有未定义的占位步骤；平台差异只允许通过 Room 实际 schema 验证收敛，不改变已冻结语义。

本计划完成后必须等待用户验收和单独实施授权；当前回合不创建 Entity、Migration 或业务代码。
