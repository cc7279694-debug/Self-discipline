# 观已 Mirra｜Module 2B 图片笔记工程实施计划

> 状态：已获授权并按本计划实施；最终实现与验证证据见 `docs/checkpoints/2026-09-11-module-2b.md`。

## 0. Task Contract

### Goal

让已经存在且正文非空的 Note 可以离线添加、查看、说明和删除多张图片，并以可恢复、最终一致的方式管理 Room 记录与 App 私有 JPEG 文件；同时将 Room Schema 从 v1 无损迁移到 v2。

### Scope

- 系统相册多选、系统相机拍照；
- App-owned Files、方向修正、采样解码、压缩与损坏文件处理；
- `ImageAsset`、Schema v1 → v2 Migration；
- Note 内图片、Caption、单图删除、Note 删除补偿；
- 全屏缩放/平移、Note 内前后切换；
- 知识页“全部图片”；
- 启动时 import/camera temp、trash 与 orphan 恢复清理。

### Out of Scope

Topic、NoteTopicCrossRef、SearchFts、搜索、OCR、自动 Caption、AI、裁剪、旋转编辑、涂鸦、PDF、云同步、Analytics、阅读预测、SessionSegment、DND、Usage Access、Overlay、新 Gradle Module、新 DI Framework。

### Constraints

- 继续单 `app` Module、Kotlin、Compose、Navigation 3、Room 2.8.5、手工 `AppContainer`；
- SQLite 是结构化事实源，文件只存于内部 App-owned Files；
- `localPath` 只存 `images/<uuid>.jpg`，禁止绝对路径和外部 URI；
- `ON DELETE CASCADE` 仅处理数据库行，物理文件始终由 Repository 与 `ImageStorageService` 协调；
- 不允许 destructive migration；v1 用户数据必须原样保留；
- Session 外 Note 仍须正文 trim 后非空才首次创建。图片不能成为空 Note 的占位手段。

### Acceptance and Verification

完成条件见第 17 节。实现阶段按“数据与文件基础 → Note 图片闭环 → 全局图片与预览 → 故障恢复与全量回归”推进，每段先补失败测试，再做最小实现。

## 1. 当前代码事实与复用点

当前基线为分支 `codex/phase-2a-note-lifecycle`、提交 `8d3d3d1`。工作区在规划开始时干净。

可直接复用：

- `MirraDatabase`、Room `withTransaction` 和现有四张 v1 业务表；
- `NoteEntity` 的 `learningItemId`、`sessionId`、`pageNumber`，图片来源信息通过 Note 关联获得，不向 `ImageAsset` 重复写入；
- `NoteRepository`、`NoteEditorViewModel` 的非空首次创建、500ms debounce 和生命周期 flush；
- `KnowledgeScreen`、`NoteListRoute`、`NoteDetailRoute` 和 Navigation 3 back stack；
- `SessionScreen` 已保存 Note 列表。Module 2B 只让已保存的 Note 行可点击进入 `NoteDetailRoute`，不把相机逻辑塞入快速草稿，也不改变 Session 自动保存；
- `AppContainer` 的手工装配和启动恢复入口；
- `TestAppContainer`、现有 JVM/Room/Compose/Instrumented 测试框架；
- 已启用的 Room schema export 与 `room-testing`。

必须升级的边界：

- 当前 `NoteRepository.delete()` 只删数据库；2B 后由它负责多图文件暂存、事务删除和补偿恢复；
- 当前数据库 `version = 1` 且未注册 Migration；2B 注册唯一的 `MIGRATION_1_2`；
- 当前无图片解码、FileProvider、图片加载与预览能力。

不做的重构：不拆现有 Entity 文件、不重写 Note 模块、不引入 UseCase 层或媒体框架体系。新增图片代码使用独立小文件，避免继续放大 `NoteEditorScreen.kt`。

## 2. Schema v2 设计

新增且仅新增一张表：

```kotlin
@Entity(
    tableName = "image_assets",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["localPath"], unique = true),
    ],
)
data class ImageAssetEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val localPath: String,
    val caption: String?,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val createdAt: Long,
)
```

约束语义：

- `noteId` 必须指向真实 Note，Note 删除时由 SQLite 级联删除图片记录；
- `localPath` 全局唯一，格式固定为 `images/<UUID>.jpg`；
- `caption` trim 后为空时保存 `null`；
- `width`、`height` 是方向修正后最终 JPEG 的像素尺寸；
- `fileSize` 是最终文件字节数；
- 同一 Note 内按 `createdAt ASC, id ASC` 稳定排序；全局图片按 `createdAt DESC, id DESC`；
- 不新增页码、Learning Item、Session、哈希、排序号、同步状态或软删除字段。

`MirraDatabase` 只做三项改变：加入 `ImageAssetEntity`、暴露 `imageAssetDao()`、`version = 2`。

## 3. Migration(1, 2)

`MIGRATION_1_2` 只执行以下 DDL：

```sql
CREATE TABLE IF NOT EXISTS `image_assets` (
    `id` TEXT NOT NULL,
    `noteId` TEXT NOT NULL,
    `localPath` TEXT NOT NULL,
    `caption` TEXT,
    `width` INTEGER NOT NULL,
    `height` INTEGER NOT NULL,
    `fileSize` INTEGER NOT NULL,
    `createdAt` INTEGER NOT NULL,
    PRIMARY KEY(`id`),
    FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`)
        ON UPDATE NO ACTION ON DELETE CASCADE
)
CREATE INDEX IF NOT EXISTS `index_image_assets_noteId`
    ON `image_assets` (`noteId`)
CREATE UNIQUE INDEX IF NOT EXISTS `index_image_assets_localPath`
    ON `image_assets` (`localPath`)
```

实施要求：

1. 在数据库 builder 显式 `.addMigrations(MIGRATION_1_2)`；不配置 fallback；
2. 保留已有 `app/schemas/.../1.json`，生成并审查 `2.json`；
3. 使用 `MigrationTestHelper` 从真实 v1 schema 创建旧库，插入 Learning Item、Intent、Session、Note 和阅读进度，再迁移到 v2；
4. 断言旧表逐字段数据、数量、状态与关联不变，`image_assets` 初始为空；
5. 验证外键、`noteId` 索引、`localPath` 唯一约束与 Note 删除级联；
6. 用 v1 APK 创建真实数据，再由 v2 APK 覆盖安装，人工复核完整学习闭环历史。

Schema v2 只新增表，因此不需要旧数据回填，也不触碰 v1 表结构。

## 4. ImageAsset DAO 与 Repository API

### ImageAssetDao

```kotlin
@Insert
suspend fun insert(entity: ImageAssetEntity)

@Query("SELECT * FROM image_assets WHERE id = :imageId")
suspend fun get(imageId: String): ImageAssetEntity?

@Query("SELECT * FROM image_assets WHERE noteId = :noteId ORDER BY createdAt, id")
fun observeForNote(noteId: String): Flow<List<ImageAssetEntity>>

@Query("SELECT * FROM image_assets WHERE noteId = :noteId ORDER BY createdAt, id")
suspend fun listForNote(noteId: String): List<ImageAssetEntity>

@Query("SELECT localPath FROM image_assets")
suspend fun listAllPaths(): List<String>

@Query(/* image_assets JOIN notes JOIN learning_items; newest first */)
fun observeAllWithSource(): Flow<List<ImageListItem>>

@Query("UPDATE image_assets SET caption = :caption WHERE id = :imageId")
suspend fun updateCaption(imageId: String, caption: String?): Int

@Query("DELETE FROM image_assets WHERE id = :imageId")
suspend fun delete(imageId: String): Int
```

`ImageListItem` 只承载界面所需投影：图片字段、`noteId`、Note 页码、Learning Item id/name；不形成新表。

### ImageRepository

```kotlin
interface ImageRepository {
    fun observeForNote(noteId: String): Flow<List<ImageAsset>>
    fun observeAll(): Flow<List<ImageListEntry>>
    suspend fun get(imageId: String): ImageAsset?
    suspend fun importFromGallery(noteId: String, uris: List<Uri>): ImportBatchResult
    suspend fun createCameraTarget(): CameraTarget
    suspend fun completeCameraImport(noteId: String, target: CameraTarget): ImageAsset
    suspend fun cancelCameraTarget(target: CameraTarget)
    suspend fun updateCaption(imageId: String, caption: String?)
    suspend fun deleteImage(imageId: String)
    suspend fun reconcileStorage(): CleanupReport
    fun displayFile(localPath: String): File
}
```

规则：

- 所有变更先在 Repository 验证 Note/Image 是否存在；UI 不访问 DAO 或文件服务；
- 相册每次最多选择 20 张，逐张处理、逐张提交，避免把所有原图同时解码进内存；失败不回滚已经成功的图片，返回逐项结果；
- `updateCaption` trim，空串写 `null`，更新 0 行视为资源已不存在；
- `deleteImage` 使用与 Note 删除相同的 trash 补偿流程；
- UUID 在每次导入时新建，即使选择同一个 URI 两次，也形成两个互不覆盖的资产；首版不做内容去重；
- 图片文件显示统一经校验后的 `displayFile()`，禁止 UI 自行拼接路径。

## 5. ImageStorageService API 与目录

生产实现只使用 Android `ContentResolver`、`BitmapFactory`、`Matrix`、`ExifInterface` 和内部文件 API：

```kotlin
interface ImageStorageService {
    suspend fun importUri(source: Uri): StoredImage
    suspend fun createCameraTarget(): CameraTarget
    suspend fun importCameraTarget(target: CameraTarget): StoredImage
    suspend fun discardCameraTarget(target: CameraTarget)
    suspend fun moveToTrash(localPath: String): TrashedFile?
    suspend fun restoreFromTrash(file: TrashedFile)
    suspend fun purgeTrash(file: TrashedFile)
    suspend fun deleteFinal(localPath: String)
    suspend fun cleanup(referencedPaths: Set<String>, now: Long): CleanupReport
    fun resolveFinal(localPath: String): File
}
```

目录固定在 `context.filesDir`：

```text
files/
├─ images/<uuid>.jpg
└─ image-work/
   ├─ import/<uuid>.source
   ├─ camera/<uuid>.jpg
   └─ trash/<uuid>.jpg
```

约束：

- final 与 trash 使用同一个 UUID 文件名，可从 trash 确定性恢复到 `images/<uuid>.jpg`；
- 所有解析先匹配 `^images/[0-9a-fA-F-]{36}\.jpg$`，再比较 canonical parent，任何越界路径立即拒绝；
- 写文件采用临时 `.part`、flush/close 成功后再在同一内部存储卷重命名，避免半文件成为正式图片；
- 所有阻塞 I/O 在 `Dispatchers.IO`；单次只解码一张；
- 生产实现与接口分开，是为了故障注入测试文件移动/写入失败，不再抽象第二层 FileSystem。

## 6. Import / Delete / Cleanup 状态流程

### 导入

```text
Picker/Camera URI
→ 复制原始字节到 image-work/import 或 camera temp
→ 读取 bounds、格式和 EXIF
→ 计算 inSampleSize 并采样解码
→ 应用完整 EXIF 镜像/旋转
→ 缩放并写 .part JPEG
→ 校验最终尺寸和非零文件长度
→ rename 为 images/<uuid>.jpg
→ Room 事务确认 Note 仍存在并 INSERT ImageAsset
→ 删除原始 temp
```

如果 DB 插入失败或协程取消发生在 final 文件产生之后，Repository 在 `NonCancellable` 补偿段删除 final；立即删除失败则保留为 orphan，由启动清理兜底。Note 合法性必须在写 DB 的事务内部再次验证，避免处理期间 Note 被删除。

### 删除单图

```text
事务外读取 ImageAsset
→ final 移至 trash（文件已不存在视为已暂存）
→ Room 事务内重新确认 ImageAsset 并 DELETE
→ 提交成功后 purge trash
→ DB 失败则 restore trash
```

文件移动失败时不碰 DB。重复删除已经不存在的 ImageAsset 明确返回 `NotFound`，不会误删其他文件。

### 删除 Note（2A 路径升级）

```text
读取 Note 对应全部 ImageAsset
→ 逐个 final 移至 trash
→ 任一移动失败：逆序恢复已移动文件并终止
→ Room 事务内再次确认 Note，DELETE Note
→ FK CASCADE 删除全部 ImageAsset 行
→ 提交成功后逐个 purge trash
→ DB 失败：恢复全部文件
```

无图片 Note 仍走原有数据库删除。数据库提交后清 trash 失败不把用户操作判为失败，因为 Note 已经删除；记录待清理结果，下次启动完成。

### 启动恢复和清理

统一阈值为 24 小时，避免刚产生但尚未写 DB 的 final 被并发清理：

1. 从 DB 读取完整 `referencedPaths`；
2. 扫描 trash：若对应 final 仍被 DB 引用且 final 缺失，立即恢复；若 DB 不再引用且 trash 超过 24 小时，删除；
3. 删除超过 24 小时的 import/camera temp；
4. 扫描 `images/`：只处理符合受管文件名、DB 无引用且最后修改超过 24 小时的 orphan；
5. DB 引用但 final 与 trash 都不存在，或图片损坏时，不自动删 DB 记录，只报告并让 UI 显示损坏占位；
6. 任何单文件失败不阻塞其他清理，结果写入结构化 `CleanupReport` 并在下次启动重试。

清理经 `AppContainer` 的启动任务在 IO 线程运行；错误被收集但不导致 App 无法启动。年龄阈值、路径校验和“被引用绝不删”均有自动化测试。

### 崩溃矩阵

| 故障点 | 当时事实 | 下次启动处理 |
|---|---|---|
| final 已生成、DB 未插入即被杀 | orphan final | 24 小时后确认无引用再删 |
| final 已入 trash、DB 未删除即被杀 | DB 仍引用 | 立即恢复 final |
| DB 已删除、trash 未清即被杀 | DB 无引用 | 超过 24 小时清 trash |
| DB 删除失败 | DB 仍引用 | 本次立即恢复；失败则启动恢复 |
| 文件不存在、DB 仍有记录 | DB 是事实源 | 保留记录、显示缺失、允许删除记录 |
| 文件损坏、DB 仍有记录 | 元数据仍存在 | 不擅自删，显示损坏并允许删除 |

## 7. Android 相册与相机入口

### 相册

- 使用 `rememberLauncherForActivityResult(PickMultipleVisualMedia(maxItems = 20))`，请求 `ImageOnly`；
- 当前 `androidx.activity` 1.13.0 已满足 Photo Picker 集成要求；
- 支持的系统优先显示 Photo Picker；不支持时 Activity Result 合约自动回退到 `ACTION_OPEN_DOCUMENT`，覆盖本项目 minSdk 23；
- 不申请 `READ_MEDIA_IMAGES`、`READ_EXTERNAL_STORAGE` 或广泛存储权限；用户只授权所选 URI；
- 不依赖持久 URI permission。回调收到 URI 后立即复制到 App 私有临时目录，之后所有显示与离线访问都基于私有副本；这样相册原图被移动、删除或授权失效也不会破坏 Note；
- 用户取消不报错；批量导入显示 `正在处理 n/总数`，最后只汇总失败项。

### 相机

- 使用 `ActivityResultContracts.TakePicture()` 与 App 控制的临时 content URI；
- 通过 `FileProvider` 暴露且仅暴露 `files/image-work/camera/`，authority 为 `${applicationId}.fileprovider`、`exported=false`、`grantUriPermissions=true`；
- 启动拍照前创建 `CameraTarget`，其安全 token/相对路径用 `rememberSaveable` 保存，Activity 重建后仍能完成回调；
- 返回 `false` 或取消时删除 camera temp；成功时进入与相册相同的校验、方向、缩放和压缩流水线；
- 不申请 `CAMERA` 权限，因为拍照由已安装的系统相机应用完成；若无可处理的相机 Activity，按钮给出“此设备没有可用相机应用”；
- 本阶段需求只是取得一张照片，不需要实时预览、对焦控制或自定义镜头，因此不引入 CameraX。

## 8. 图片解码、方向与压缩

首版固定策略：

- 可接受输入：解码器能安全读取的 JPEG、PNG、WebP、HEIF/HEIC；GIF 只取静态首帧；其他/伪装 MIME 在实际解码失败时拒绝；
- 统一输出：baseline JPEG，`.jpg`，最长边不超过 2560px，质量 88；不放大较小图片；
- JPEG 适合书页/照片且兼容性稳定；透明 PNG 转白色背景后输出，避免转 JPEG 后出现黑底；
- 使用 `ExifInterface 1.4.2` 读取全部 8 种 orientation，包含镜像、转置和 90/180/270 度旋转；输出像素已纠正，不再依赖 EXIF；
- 先只解码 bounds，再根据源尺寸和 2560px 目标计算 `inSampleSize`，采样后才做精确缩放；拒绝非正尺寸和明显异常元数据；
- 以“采样解码 + 单图串行处理 + 捕获 OOM/IO/decoder 异常”防止超大图拖垮进程；不得先完整读取为 byte array 或原尺寸 ARGB bitmap；
- 编码后重新读取 bounds，确认宽高与文件非空；失败不创建 DB 行；
- 同一个源重复导入时生成新 UUID，不覆盖、不做感知哈希去重；
- Caption 不参与图像文件写入，只保存在 Room。

依赖保持克制：

- 新增 `androidx.exifinterface:exifinterface:1.4.2` 负责可靠方向读取；
- 新增 `io.coil-kt.coil3:coil-compose:3.6.2` 只负责本地缩略图/全屏异步显示与内存缓存，不加入任何网络 fetcher；
- 压缩、转换、文件生命周期不用 Coil，也不引入 Glide、CameraX 或独立图像编辑库。

## 9. Note 删除路径升级与并发规则

- `DefaultNoteRepository` 增加 `ImageAssetDao`、`MirraDatabase` 与 `ImageStorageService` 依赖；公开 `delete(noteId)` API 可保持不变，避免 UI 扩散；
- Note 删除确认文案在有图时显示“将同时删除 N 张图片”，数量来自图片 Flow；
- 删除期间禁用重复提交和返回，完成后再导航；失败保留页面与数据并提供重试；
- 图片导入、Caption 保存或删除进行中时，Note 删除按钮禁用；反之 Note 删除开始后不再接受新的图片操作；
- DB 事务内再次读取 Note 和图片关系，发现与已暂存集合不一致时回滚、恢复文件并提示重试，避免并发新增图片漏删；
- 2A 的文字自动保存仍须在 Note 删除前 flush；删除成功后取消 debounce，禁止已排队保存把 Note 重新插入；
- 单张图片删除与 Note 删除共用同一套 storage primitive，不创建通用“分布式事务框架”。

## 10. Compose 页面变化

### Note 编辑/详情

新增 `NoteImageSection`：

- 仅在 Note 首次成功保存、已有稳定 `noteId` 后显示“相册”“拍照”；空正文草稿提示先保存正文，不预插入 Note/ImageAsset；
- Note 详情按创建顺序显示缩略图、Caption 输入、删除按钮和单图失败占位；
- Caption 采用 500ms debounce，在 App 后台、离开页面、切换图片、删除图片、删除 Note 时 flush；用户手动修改是唯一来源；
- 图片导入显示当前进度；部分失败保留成功项并给出可理解汇总；
- 点击缩略图进入全屏；删除单图需二次确认；
- 已保存 Session Note 列表项可点击进入现有 Note 详情，拍照/选图仍发生在统一编辑页，不扩充快速笔记表单。

### 全屏图片

- 深色全屏页面，显示关闭、`当前/总数`、Caption 和“查看笔记”；
- `HorizontalPager` 在同一 Note 的 `createdAt,id` 顺序内切换，初始定位到被点击图片；
- 每页用 Compose `transformable` 支持 1x–5x 缩放与平移，平移限制在图片可见边界；切页重置当前页变换；
- 放大时暂时禁止 Pager 横滑，避免平移和切页冲突；同时提供前/后按钮以满足可发现性与无障碍操作；
- 文件缺失/损坏显示占位、Caption 与“查看笔记”，不崩溃。

### 全部图片

- `KnowledgeScreen` 增加“全部图片”入口；
- `LazyVerticalGrid` 显示缩略图、Caption（空则不造文案）、所属 Learning Item、Note 页码、创建时间；
- 默认最新创建在前；空状态为“还没有图片笔记”；
- 点击缩略图进入其 Note 的全屏预览；预览中的“查看笔记”进入 `NoteDetailRoute`；
- 不增加相册、分组、收藏、标签或独立图片知识库概念。

## 11. Navigation 变化

新增两个 Navigation 3 key：

```kotlin
@Serializable data object ImageListRoute : NavKey

@Serializable data class ImagePreviewRoute(
    val noteId: String,
    val initialImageId: String,
) : NavKey
```

`MirraApp` 增加对应 entry 和 ViewModel 装配；现有 `NoteDetailRoute` 不变。预览依靠 `noteId` 观察该 Note 当前图片，若初始图片已删除则定位到第一张；整组为空则返回上一页并显示一次提示。导航参数不包含本地路径，避免泄漏和陈旧路径。

## 12. 错误、恢复与用户反馈

- Picker 取消：静默返回；相机取消：清理本次 temp；
- 不支持/损坏/无法解码：显示“这张图片无法读取”，不生成 DB 行；
- 存储空间不足或写入失败：显示“图片未保存，请释放空间后重试”，Note 正文不受影响；
- DB 写入失败：删除刚生成的 final，提示重试；
- 批量部分失败：显示“已添加 X 张，Y 张未能处理”，不撤销成功项；
- 文件移入 trash 失败：数据库不删除，提示“图片暂时无法删除”；
- DB 删除失败：恢复文件并提示“删除未完成，数据已保留”；恢复失败时说明“将在下次启动自动恢复”；
- DB 已删除但 purge 失败：界面按成功处理，后台/下次启动清理；
- DB 行存在但文件缺失/损坏：占位图 + Caption + 删除入口，不自动篡改数据库；
- 清理失败：不阻塞启动、不弹连续 Toast，保留待重试；Debug 日志不得包含外部 URI、绝对私有路径或用户 Caption。

## 13. 自动化测试计划

### JVM

- 尺寸采样、最长边 2560、不放大、输出尺寸计算；
- 8 种 EXIF orientation 到最终宽高/矩阵映射；
- `localPath` 格式、canonical 越界拒绝；
- Caption trim/null；
- 全屏 scale 1–5、pan 边界和切页重置；
- 24 小时阈值边界（小于、等于、大于）；
- ViewModel：非空 Note 后才可加图、Caption debounce/flush、批量部分失败、重复提交保护。

### Room / Instrumented

- 使用真实 `1.json` 的 v1 → v2 Migration；
- Learning Item、Intent、Session、Note、阅读进度迁移前后完整一致；
- FK 拒绝无 Note 图片、Note 删除级联、`localPath` UNIQUE、`noteId` 查询排序；
- Caption 创建、修改为空转 null；
- 全局图片 JOIN 字段、排序及同 Note 多图不丢失；
- DB insert 触发器制造失败，验证 final 文件补偿；
- DB delete 触发器制造失败，验证单图及多图 Note 文件恢复；
- 文件先缺失时仍能安全删除 DB 记录。

### ImageStorageService Instrumented

- `ContentResolver` 测试 URI 的相册导入和 FileProvider camera target 导入；
- JPEG、PNG 透明底、WebP、设备支持时 HEIF；
- 带已知像素方向的 EXIF 1–8 测试图；
- 真实中等大图 + 极端 bounds 的采样算法，证明不会原尺寸解码；
- 损坏/截断文件、空文件、写入失败、取消；
- 输出 JPEG 可再次解码、边长≤2560、width/height/fileSize 与实际一致；
- 同源重复导入产生不同路径；
- temp、camera、trash、orphan 的小于/大于 24 小时矩阵；
- 被 DB 引用的 final 永不删，引用 trash 自动恢复，越界/未知文件不处理。

### Compose / Navigation

- Note 详情添加入口只在持久 Note 可用；加载、失败、空态；
- 图片缩略图、Caption 编辑 flush、删除确认和有图 Note 删除文案；
- 全屏打开到正确图片、前后按钮/滑动、多图计数、损坏占位、“查看笔记”；
- 全局列表展示 Caption、Learning Item、页码、时间并可进入预览/Note；
- Session 已保存 Note 可打开详情，返回后 Session 仍保持；
- 手势测试验证缩放状态与分页冲突规则，真实 pinch/pan 另做设备验收。

### 全量回归

- 现有 JVM、Room/Instrumented、Compose 全部测试；
- `lintDebug`、`assembleDebug`；
- Phase 1 完整 Intent → Session → 多 Note → 结束 → 恢复闭环；
- 2A Learning Item 生命周期、Note CRUD/筛选/删除/自动保存；
- Room schema diff 只允许新增 `image_assets` 和版本从 1 到 2。

## 14. 模拟器 / 真机验收

### API 37 模拟器

1. 从 v1 APK 创建含 Intent/Session/Note 的数据，覆盖安装 v2 APK并核对全部旧数据；
2. 系统 Picker 多选 JPEG/PNG/WebP，飞行模式下继续查看、编辑 Caption、删除；
3. 虚拟相机拍摄，验证取消、成功、方向、缩略图和全屏；
4. 一个 Note 加多图，缩放、平移、前后切换，再从全部图片进入同一 Note；
5. 在导入 final 后、DB 写入前以及移动 trash 后分别强停，冷启动验证 orphan/恢复规则；
6. 删除单图和含多图 Note，确认 DB 与 `run-as ... files` 中的文件最终一致；
7. 完整 Phase 1 和 2A 回归，APK 覆盖安装、冷启动均通过。

### 至少一台 Android 真机

- 系统 Photo Picker 多选与权限页：确认未请求广泛照片/存储权限；
- 后置相机拍竖版书页，确认方向正确、文字在放大后可读、文件大小明显低于原图；
- 相册删除/移动原图后，Mirra 私有副本仍离线可看；
- 拍照中旋转屏幕/切后台后返回；强停与重启后的 temp/trash 恢复；
- 双指 pinch、平移、放大时横向切图冲突与 Android Back 行为。

若当次执行无法取得真机，只能把真机项明确标记 `Not Run`，不能用模拟器结果冒充。

## 15. 预计新增 / 修改文件

### 新增

- `app/src/main/java/com/guanyi/mirra/data/local/entity/ImageAssetEntity.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/dao/ImageAssetDao.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/model/ImageListItem.kt`
- `app/src/main/java/com/guanyi/mirra/data/local/Migrations.kt`
- `app/src/main/java/com/guanyi/mirra/data/repository/ImageRepository.kt`
- `app/src/main/java/com/guanyi/mirra/data/storage/ImageStorageService.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/NoteImageSection.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/ImageListScreen.kt`
- `app/src/main/java/com/guanyi/mirra/feature/knowledge/ImagePreviewScreen.kt`
- `app/src/main/res/xml/file_paths.xml`
- `app/schemas/com.guanyi.mirra.data.local.MirraDatabase/2.json`
- 对应 JVM、Room、Storage、Compose 与端到端测试文件；具体按现有 test package 就近放置。

### 修改

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `MirraDatabase.kt`
- `AppContainer.kt`、`TestAppContainer.kt`
- `NoteRepository.kt`
- `NoteEditorScreen.kt`
- `KnowledgeScreen.kt`
- `SessionScreen.kt`
- `Routes.kt`
- `MirraApp.kt`
- 受构造参数影响的现有测试；
- 完成后更新 `docs/CURRENT_STATE.md`、必要时 `docs/DECISIONS.md` 和 Module 2B checkpoint。

无删除文件；不修改 `1.json`；不创建 Topic/FTS/Analytics 空文件。

## 16. 过度设计检查

- 不用 CameraX：系统相机 Intent 已满足单张拍照；
- 不做自定义图库、裁剪器、编辑器、OCR 或图片标签；
- 不做跨文件系统事务框架，只用三步补偿 + 启动清理；
- 不做内容哈希去重、手动排序、图片分页表、软删除或操作日志表；
- 不建立新 Gradle Module、DI 框架、UseCase 层或 WorkManager；
- 只加入 ExifInterface 与本地 Coil Compose 两个必要依赖，且 Coil 不带网络模块；
- 全局图片是现有表 JOIN 的只读投影，不建第二份数据；
- 24 小时统一阈值，避免多套清理策略；
- 仅在真实并发边界做事务内复核，不引入锁管理器。单进程内由 ViewModel 禁用冲突操作，跨进程中断靠 DB 事实与启动修复。

结论：方案比纯“文件写完即插表”多出的复杂度仅用于已确认的数据安全和故障恢复；没有为 2C、2D 或未来模块预埋业务结构。

## 17. Module 2B 最终验收条件

1. v1 数据库可通过 `MIGRATION_1_2` 升级到 v2，所有 Phase 1/2A 数据和阅读进度不变；
2. `2.json` 正确，`image_assets` 的 FK/CASCADE、索引、唯一约束均经测试；
3. 相册可一次选择多张图片，无广泛存储权限，副本离线长期可用；
4. 系统相机可拍照、取消安全，不引入 CameraX；
5. JPEG/PNG/WebP 与受支持 HEIF 输入可安全处理，EXIF 方向正确；损坏图不会落库；
6. 最长边≤2560、JPEG 质量 88，书页文字真机放大可读，超大图不发生 OOM；
7. Note 可显示多图、创建/编辑 Caption、删除单图；
8. 同一 Note 全屏图片可缩放、平移、前后切换，损坏文件不崩溃；
9. 知识页“全部图片”正确显示图片、Caption、Learning Item、Note 页码与时间，并可进入预览/Note；
10. 单图删除和 Note 多图删除遵守先 trash、后 DB、成功 purge、失败 restore；
11. 文件移动/写入/DB insert/DB delete 失败均有自动化补偿证据；
12. 强停/冷启动后 referenced trash 恢复，过期 temp/trash/orphan 被安全清理，合法图片不误删；
13. Session 空白草稿不创建占位 Note/ImageAsset；已保存 Session Note 可进入详情添加图片；
14. 全功能无网络可用，不新增存储/相机运行时权限；
15. JVM、Room/Instrumented、Compose、lint、build 与 Phase 1/2A 回归全部通过；
16. APK 覆盖安装和 API 37 模拟器冷启动通过；真机项有真实结果或明确 `Not Run`；
17. Git 变更仅包含 Module 2B，独立 Conventional Commit 并在获得 Push 授权时推送当前功能分支；
18. CURRENT_STATE/checkpoint 与真实实现同步，无 Topic、Search、OCR、Analytics、预测、专注干预或 Future Modules；
19. 完成报告明确列出实现、文件、Schema/Migration、测试、设备验收、Git、偏差、风险与未完成项，然后停止，不进入 Module 2C。

## 18. 推荐实施顺序（获授权后）

1. 从冻结提交创建 `codex/phase-2b-image-notes`；先写 Migration、DAO 与约束失败测试；
2. 实现 Schema v2、`MIGRATION_1_2`、导出并审查 `2.json`；
3. 先写 Storage 的方向/采样/路径/故障测试，再实现 `ImageStorageService`；
4. 先写 import/delete/Note delete 补偿测试，再实现 Repository；
5. 接入 Photo Picker、Camera/FileProvider 与 Note 图片 UI；
6. 实现全屏预览、全部图片、Navigation 和 Compose 测试；
7. 实现启动 reconcile，完成强停故障矩阵；
8. 全量自动化、v1 APK 覆盖升级、模拟器/真机、离线与 Phase 1/2A 回归；
9. 更新文档和 checkpoint，检查 diff/schema/敏感信息，独立 commit；仅在授权范围内 push；
10. 输出验收报告并停止，不进入 Module 2C。

## 19. 实施依据

- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Activity Result：PickMultipleVisualMedia](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.PickMultipleVisualMedia)
- [Android Camera Intents](https://developer.android.com/media/camera/camera-intents)
- [Activity Result：TakePicture](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture)
- [FileProvider 安全文件共享](https://developer.android.com/training/secure-file-sharing/setup-sharing)
- [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface)
- [Compose gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)
- [Room MigrationTestHelper](https://developer.android.com/reference/androidx/room/testing/MigrationTestHelper)
- [Coil changelog](https://coil-kt.github.io/coil/changelog/)
