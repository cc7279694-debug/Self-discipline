# Mirra Phase 4C-0｜Full Backup / Restore Safety Baseline Contract

## 1. 审计身份、结论与范围

- 日期：2026-10-08。
- Repository：`cc7279694-debug/Self-discipline`。
- 唯一源码基线 / Phase 4B Freeze：`cd13953cb3ac132ee64493fe688a3ac0f3cf8e74`。
- 审计分支：`codex/phase-4c-full-backup`，从上述 SHA 创建；不 merge main。
- 继承 Phase 1–3、4A、4B 冻结语义及已批准 Phase 4 Master Plan，不重做产品规划。
- 本轮只读源码、Schema、配置及已有测试定义，新增本文件；没有实现 Backup/Restore，没有运行 Gradle、测试、AVD、ADB、导出、恢复或真机操作。已有测试源码不等于本轮 PASS。

**结论：存储事实和接入点已确定，但现有代码没有完整维护屏障、严格偏好快照、可关闭/重新绑定的存储生命周期或 Restore Journal。不能把目标架构写成已实现能力。**

当前直接覆盖 live Room / DataStore / images 的方案属于 `ARCHITECTURE_SAFETY_BLOCKER`，不得进入数据切换。第 11 节列出必须先解除的安全门槛；未发现必须升级 Room v5 或改写冻结业务语义才能解决的已证实矛盾，但本轮也没有以运行证据证明后续方案安全。4C-1 仍须单独授权。

本文件中的“要求”来自用户本轮合同和已批准 Master Plan；“建议”是接入方案，尚非生产实现或新的产品冻结。

### 1.1 不变产品合同

- 正式首版 `backupFormatVersion = 1`、`roomSchemaVersion = 4`；只接受 Mirra 正式格式且通过完整校验的包，不接受裸 `.db`、任意安装数据库或 v1/v2/v3 备份。
- 全量替换，不 merge；失败保住当前数据，替换前必须保存当前安全快照。
- 不自研加密、不自动上传、不增加账号/服务器；使用 Android SAF 由用户选择文件。文件含私人笔记和图片，必须如实提示。
- Android System Backup / Device Transfer 是 4D 的有限补充，不承担完整恢复保证；本轮不修改它的配置。
- 有 Active Intent、Active Session、Closeout PENDING 或未完成 owned runtime cleanup 时禁止 Backup / Restore；不为备份自动结束学习。
- 不增 Entity / Table / Column / Index / Migration，不提前持久化派生趋势指标。

## 2. 源码证据索引

下文 Kotlin 路径统一相对 `app/src/main/java/com/guanyi/mirra/`；行号均指上述固定基线，不是将来的实现行号。

| 证据 | 文件与定位 | 核对事项 |
|---|---|---|
| E01 | `data/local/MirraDatabase.kt:28–56`；`di/AppContainer.kt:150–162` | 13 Entity、version 4、DB builder、DS/图片实例 |
| E02 | `data/local/Migrations.kt:6–121`；`app/schemas/com.guanyi.mirra.data.local.MirraDatabase/4.json` | 安装迁移链及完整字段/索引/FK |
| E03 | `data/preferences/AppPreferencesRepository.kt:30–74`；`di/AppContainer.kt:64` | 四偏好、IOException fallback、四 edit、顶层 delegate |
| E04 | `data/storage/ImageStorageService.kt:62–179,182–284`；`data/storage/ImageImportPolicy.kt:11–42` | 正式/临时目录、压缩、EXIF、sync、move、cleanup |
| E05 | `data/repository/ImageRepository.kt:55–133`；`data/repository/NoteRepository.kt:139–167` | 图片与 DB 先后顺序、失败补偿 |
| E06 | `data/search/SearchIndexWriter.kt:13–47`；`data/search/SearchIndexRebuilder.kt:23–52`；`data/repository/SearchRepository.kt:42–53,117–119` | FTS 派生、隐藏 repair writer |
| E07 | `data/repository/StudyWorkflowRepository.kt:70–130,161–361,364–392` | Intent/Session/Closeout/PENDING/ABNORMAL |
| E08 | `data/repository/FocusRepository.kt:120–307`；`data/repository/RoomDndStateStore.kt:20–35`；`data/repository/InterventionReceiptRepository.kt:19–39` | Focus、DND metadata、receipt 三条写链 |
| E09 | `MirraApplication.kt:9–15`；`di/AppContainer.kt:66–101,209–249`；`MainActivity.kt:41–60` | 实例建立、启动恢复/清理、导航偏好写 |
| E10 | `domain/SessionStartCoordinator.kt:82–177`；`platform/focus/MonitoringPlatformRuntime.kt:29–60,87–195` | READY 前启动、取消补偿、late writer、stop 未 join |
| E11 | `platform/focus/FocusMonitoringService.kt:60–168,199–213`；`platform/intervention/RuntimeInterventionChannels.kt:21,42–96` | Service scopes、轮询/watchdog、渠道队列 |
| E12 | `domain/DndController.kt:39–130`；`domain/intervention/InterventionPresenter.kt:42–130`；`domain/SessionManager.kt:74–102` | 局部锁与 best-effort cleanup，不是全局静止证明 |
| E13 | `feature/session/SessionScreen.kt:305,462–547,550,631–638`；`feature/knowledge/NoteEditorScreen.kt:124,169–176,217–289,336–389,419–426` | 草稿/页码/caption、退出时 flush |
| E14 | `feature/knowledge/NoteImageSection.kt:58–79`；`app/src/main/res/xml/file_paths.xml:3–5`；`app/src/main/AndroidManifest.xml:23,46–53` | Photo Picker、外部相机 writer、FileProvider、系统备份现状 |
| E15 | `data/local/entity/FocusEntities.kt:53–85,106–152`；`data/local/Converters.kt:17–35`；`domain/SessionTimelineValidator.kt:34–46` | 历史与 ownership 区分、软引用/枚举、可信性不是备份资格 |

## 3. 真实资源与权威数据

### 3.1 Room：12 张权威业务表 + 1 张派生 FTS 表

| 权威表 | 事实及关键关系 | Schema 4 tableName 行 |
|---|---|---:|
| `learning_items` | 学习项、页数/进度、主线及生命周期 | 8 |
| `study_intents` | 学习意图、状态/时间；FK → Learning Item | 89 |
| `study_sessions` | 开始/稳定/结束、页码、结束类型、已保存总结；FK → Item / Intent | 177 |
| `notes` | 正文、页码及 Item / nullable Session 关联 | 308 |
| `image_assets` | JPEG 相对路径、caption、尺寸/长度；FK → Note | 410 |
| `topics` | Topic 名称与创建事实 | 502 |
| `note_topic_cross_refs` | Note ↔ Topic 显式关联 | 543 |
| `risk_apps` | 当前用户风险 App 配置；不是安装包/权限保证 | 653 |
| `session_focus_contexts` | 历史规则/权限快照、coverage、heartbeat、closeout；其中设备 owner 字段需归一化 | 689 |
| `session_risk_app_snapshots` | 本场历史 package / label 快照；不依赖当前 risk 配置 | 925 |
| `session_segments` | 七类段落、边界、用途、延长、关联事实 | 969 |
| `focus_events` | 事件及真实 intervention delivery receipt | 1084 |

`search_fts`（Schema 4:603）是 FTS4 / unicode61 派生索引，必须重建，不作为额外权威业务表。`room_master_table`（1161）、FTS shadow、SQLite/Android 内部表也不计为业务表；本轮未现场枚举真实 `sqlite_master`。

以下不另建备份事实表：Analytics、Effective metrics、TimelineTrust、趋势、洞察、ReadingRecord UI projection、分页 cursor/cache。它们从上述事实重算。`generatedSummary` 虽由规则生成，但已经是 Session 持久字段，仍按原值保存；不能借“派生”名义删除既有正文或改历史。

Room version = **4**。安装 Migration 1→2（图片）、2→3（Topic/FTS）、3→4（五个 Focus 表）不变，无 destructive fallback 接线。安装 Migration 不构成旧备份格式兼容。

Schema 文件 SHA-256（本轮只读计算）：

`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`

Room identityHash 为 `2ff08bb15376ae09645ec00c373c2f82`，与上述文件 SHA-256 不同。

### 3.2 实际存储资源表

| 资源 | 当前创建/路径合同 | 备份与恢复处理 |
|---|---|---|
| Room | `Context.getDatabasePath("mirra.db")`；E01 builder | 一致快照中的 12 表事实；输出独立、已归一化、已关闭且可独立打开的 v4 DB |
| DB sidecars | 随实际 journal mode 可能有 `-wal/-shm/-journal` | 不是可独立打包的业务文件；不能漏掉 WAL 已提交数据，也不能逐个复制 live 文件冒充原子快照 |
| Preferences | `filesDir/datastore/mirra_preferences.preferences_pb`；顶层 delegate | 一次严格读取的四正式偏好；不打包 live protobuf / actor cache |
| 正式图片 | `filesDir/images/<UUID>.jpg` | 当前快照中每条 ImageAsset 引用的原 JPEG 字节、metadata 与 hash |
| 图库/压缩临时文件 | `image-work/import/*.source`、`*.jpg.part` | 排除 |
| 相机临时原图 | `image-work/camera/<UUID>.jpg` | 排除；外部相机仍可写，必须治理未完成请求和迟到回调 |
| trash / orphan | `image-work/trash/`；无引用的正式图片 | 不进入用户正式包；导出前若引用只存在于 trash，不能假装完整。内部 rollback 保留切换前正式目录，不靠 orphan cleanup 修复 |
| 恢复工作区/Journal | **当前不存在** | 建议 app-private、非 cache、同内部文件系统的受控目录，独立于 images/image-work；不进入用户包 |
| caches / 日志 / 系统状态 | Coil/cache、调试日志、FGS/Overlay/通知等 | 排除；不得用缓存补齐缺图，不上传日志/私人内容 |

路径由源码与本机 AndroidX delegate 定义核对，不是本轮从设备读取的 `/data/user/...` 实测路径。Room builder 未显式指定 JournalMode；本轮没有开库读取 `journal_mode` 或 `synchronous`，不能宣称真实 WAL/落盘配置已验证。

### 3.3 DataStore 四项正式偏好

| key | 类型/合法值 | 成功读取后 key 缺失的既有默认 |
|---|---|---|
| `last_destination` | String：`start / knowledge / profile` | Start |
| `theme_id` | String：`blue / mono / night` | BLUE |
| `dnd_enabled` | Boolean | false |
| `cross_app_intervention_enabled` | Boolean | false |

现有四 Flow 均 `IOException → emptyPreferences`；枚举还有 UI fallback（E03；`navigation/TopLevelDestination.kt:12–18`、`data/preferences/MirraThemeId.kt:3–10`）。**备份必须走无 fallback 的一次严格 Preferences 读取**：I/O / corruption / 已存在 key 类型或值非法应失败，不能当作成功默认值。成功读到 key 缺失才按已声明默认解释，并在格式合同区分 presence / effective value；不能分别取四条 Flow 组成不同版本快照。

四 setter 各自 edit，不是四偏好一次性恢复。正式导入只迁移四项白名单偏好；内部旧数据安全快照和回滚须保留读取到的**全部原 Preferences key、类型、值及缺失状态**，不能只回滚四项默认值。

`dnd_enabled` / `cross_app_intervention_enabled` 是下一场 Session 的意愿，不证明当前设备有权限或已开启保护。主题当前无生产 setter 调用，但仍是正式偏好写入口。

DataStore 顶层 delegate 缓存实例并拥有独立 scope；取消 container.applicationScope 或仅新建 container 不会清掉它。当前没有 strict snapshot、原子整组导入、scope close/rebind 合同。后续不得在该实例仍活动时替换其 protobuf 文件；应选择受控同实例原子 edit，或证明实例尚未建立/已真正终止的文件切换策略。仅cancel scope仍不会重置顶层delegate缓存，teardown方案必须同时重建受控实例所有者并证明旧actor终止。启动 bootstrap 若创建该文件的 DataStore，正常 container 必须复用同一受控实例，不能并存第二个实例；同一文件的多活动实例违反平台使用合同。[Android DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

### 3.4 图片正式合同

- `image_assets.localPath` 有唯一索引；合法路径为 `images/<严格 UUID 分段>.jpg`，并核对 canonical parent。ImageAsset.id 与 filename UUID 分别生成，不能要求两者相等。
- Photo Picker 最多 20 个 ImageOnly；TakePicture 经 FileProvider 写 camera 临时目录。来源 URI/原相机文件不作为长期依赖。
- 现有处理做 EXIF orientation/镜像修正、采样、最长边 2560、白底 flatten、JPEG quality 88；没有把原 EXIF 复制到正式 JPEG。正式包必须复制已有 JPEG 原字节，不再次压缩或旋转。
- 生成 `.part` 后 `fd.sync()`，rename 到 images，回读尺寸，再插入 ImageAsset；DB 失败还会执行文件补偿。删除图片或 Note 先移 trash，再事务删除/更新 FTS，失败恢复、成功 purge（E04/E05）。
- 启动 cleanup 可恢复被引用 trash，并按 24h 清理 temp/trash/orphan；它不证明全部 DB 引用有文件，也不检查图片 hash/metadata。补偿错误被runCatching吞掉，没有持久化失败任务，可能留下残留文件或缺图；有限cleanup不保证恢复完整性，不能把操作返回当作媒体完全一致。
- 包必须证明每个引用有且仅有对应正式 JPEG，路径唯一、长度/尺寸匹配、完整 hash 一致；校验 bounded decode 和尺寸上限。缺图/损坏/非法路径必须拒绝，不删除 row、生成占位图或从缓存补齐。

## 4. Writer Inventory 与计数口径

**52 个 Repository / store / preferences 公共持久化入口**：43 个权威 Room 写入口 + 2 个 Search 派生写入口 + 3 个 Image file-only 入口 + 4 个偏好入口。接口声明不重复计、private helper 不计、纯 SELECT 不因 withTransaction 而计写；两个 public 方法即使委托同一 helper 仍分别计入口。另有 7 个 Search helper、49 个 DAO 写方法和 9 个 Storage 写方法，属调用层覆盖清单，**不得相加当作独立业务操作总数**。

### 4.1 Repository / store 完整入口

| 类别 / 文件 | 数量 | public 方法（声明行） |
|---|---:|---|
| LearningItemRepository | 6 | create:46、updateFirstAction:76、setMainline:84、pause:94、resume:102、complete:111 |
| StudyWorkflowRepository | 9 | beginCloseout:70、completeCloseout:112、createIntent:161、markTransitioned:197、abandonIntent:201、startSession:212、startMonitoredSession:215、updateCurrentPage:301、recoverInterruptedSession:311 |
| NoteRepository | 4 | save:62、createStandalone:96、update:123、delete:139 |
| ImageRepository（Room） | 4 | importFromGallery:55、completeCameraImport:73、updateCaption:78、deleteImage:87 |
| TopicRepository | 4 | create:55、createAndLink:59、link:66、unlink:76 |
| FocusRepository | 13 | confirmRisk:120、recordBriefRiskVisit:155、exitRisk:169、replaceRiskApp:182、removeRiskApp:190、transition:194、recordEvent:206、updateHeartbeat:222、markMonitoringLost:231、markStableStarted:262、completeRecovery:271、promoteDeepFocus:286、applyBehavior:307 |
| RoomDndStateStore | 2 | prepare:20、setLifecycle:29 |
| InterventionReceiptRepository | 1 | record:19 |
| SearchRepository | 2 | search:42（自动 repair）、rebuildIndex:53 |
| ImageRepository（file-only） | 3 | createCameraTarget:71、cancelCameraTarget:76、reconcileStorage:103 |
| AppPreferencesRepository | 4 | setLastDestination:53、setThemeId:59、setDndEnabled:63、setCrossAppInterventionEnabled:66 |

### 4.2 DAO / helper / filesystem 覆盖

| DAO（`data/local/dao/`） | 数量 | 完整写方法 |
|---|---:|---|
| LearningItemDao | 8 | insert:11、clearMainline:32、assignMainline:35、markPaused:42、markInProgress:49、markCompleted:56、updateFirstAction:59、advanceProgress:62 |
| IntentDao | 5 | insert:29、markTransitioned:44、markConverted:47、markAbandoned:50、markTimedOut:53 |
| SessionDao | 3 | insert:40、advanceCurrentPage:141、finish:150 |
| NoteDao | 4 | insert:14、upsert:16、updateContent:64、delete:73 |
| ImageAssetDao | 3 | insert:13、updateCaption:43、delete:46 |
| TopicDao | 3 | insert:15、insertCrossRef:45、deleteCrossRef:48 |
| FocusDao | 19 | markCloseoutPending:33、markCloseoutCompleted:37、extendAllowance:43、prepareDnd:63、setDndLifecycle:66、insertContext:72、insertSegment:73、insertEvent:74、insertRiskSnapshots:75、upsertRiskApp:76、deleteRiskApp:79、closeActiveSegment:129、replaceAndCloseActiveSegment:136、changeActiveSegmentType:148、changeActiveSegmentToRisk:153、deleteActiveSegment:157、setCoverage:166、updateHeartbeat:177、markStableStarted:184 |
| SearchFtsDao | 4 | insert:12、insertAll:14、delete:17、clear:20 |

49 = 45 权威表写 + 4 FTS 写。生产没有检出 UI/platform 直接调用 DAO 写业务表；生产 execSQL 在安装 Migration 中。不能据此忽略 store/helper 或未来新 writer 的 admission。

7 个 Search helper：`SearchIndexWriter.reindexLearningItem/reindexNote/reindexTopic/remove/reindexSession`，`SearchIndexRebuilder.ensureConsistent/rebuild`。writer 本身依赖调用方事务；ensureConsistent 只比较数量，不证明内容正确。恢复应显式 rebuild，并验证中文 Note/Caption/Topic/Session 搜索；当前 rebuild 用 listAll/groupBy，内存随数据增长，后续需有界方案/限额验证，不宣称 constant-space。

9 个 Storage mutator：`importUri/createCameraTarget/importCameraTarget/discardCameraTarget/moveToTrash/restoreFromTrash/purgeTrash/deleteFinal/cleanup`（`ImageStorageService.kt:46–54`）；构造 mkdir 是额外初始化副作用。

### 4.3 异步、后台与系统操作来源

| 来源 | 屏障必须纳入的范围 / 风险 | 证据 |
|---|---|---|
| Note / Caption / page | 500ms debounce、排队 Deferred、onStop/onDispose 最后 flush；隐藏页面不等于停止写 | E13 |
| Gallery / Camera | copy/decode/compress/move→Room→失败补偿整条链；外部 camera lease、迟到 ActivityResult | E04/E05/E14 |
| 导航/保护设置 | lifecycleScope DS edit、DND retry apply/release、跨应用开关 | E03/E09；`feature/profile/DndUserActions.kt:58–81` |
| Session start | PREPARING/READY/COMMITTING 可已启动 FGS 但还没有 Active Session；取消后的 NonCancellable 补偿/side effect | E10 |
| FGS / factsScope | poll、watchdog、屏幕 receiver、serviceLost、deadline、页面 evidence，可能排队 late writer | E10/E11；`feature/session/SessionEvidenceReporter.kt:24–45` |
| Intervention | reconcile collector、Overlay attached / in-app receipt、PendingIntent/action；独立 channel scope | E08/E11 |
| DND | prepare、apply、release、reconcile，系统 side effect 与 Room lifecycle 两端都要纳入 | E08/E12 |
| Startup | ABNORMAL/PENDING、Intent timeout、DND reconciliation、image cleanup、FTS rebuild | E07/E09 |
| 查询触发写 | search() SQLiteException / orphan repair；不能把所有“读页面”当无副作用 | E06 |

未检出生产 WorkManager/Worker/AlarmManager 的额外持久化任务，不等于现有 Service/独立 scope/外部相机已经静止。UI 本身可继续触发最后写入。Note 作为独立事实可在 Session 结束后保存，PENDING guard 不是全库写屏障。

## 5. Maintenance Barrier：接入存在，能力尚未建立

已有局部锁：start coordinator mutex、facts/Closeout mutex、DND mutex、Presenter operation mutex、单个 Note saveMutex、Room transaction。它们各自保护局部规则，没有跨 DB/DS/文件的统一 admission/drain。

建议共享维护协调器接入以上全部入口，语义为：

`OPEN → DRAINING → EXCLUSIVE → OPEN`（失败则保持受控维护/安全恢复，不冒充 OPEN）。

1. 入口检查先拒绝已知 Active Intent/Session/PENDING/cleanup；该检查只是快速拒绝，不是最终安全证明。
2. 占有全局维护 admission，禁止新学习/编辑/配置/repair/platform operation；阻止旧 generation 的 UI、camera、receipt、Service callback 在替换后获得新 lease。
3. 排空此前已获 lease 的完整工作，包括 queued edits、同步文件 IO、NonCancellable 补偿和平台副作用；保存失败须阻止操作，不能只等 debounce 时间或取消 Job。
4. 对退出触发的 flush 提供明确等待结果。nested repository/helper 调用复用已获得 lease，不能持独占锁后等待另一条需要同锁的补偿造成死锁。
5. 排空后在屏障内重新读取 Active Intent、Active Session、**全部 Context 中 PENDING**，并确认 start handshake 无在途工作、无 active Segment、owned cleanup 和 scopes 均静止。
6. 只有此时捕获快照/准备切换。屏障保持到私有一致快照完整复制，或 Restore commit/rollback完成。SAF慢速写可在独立快照固化后释放屏障，不持锁等待用户无限选文件。

外部相机特殊要求：取消Job、撤销URI grant、unregister ActivityResult或Mirra进程退出，都不能单独证明外部相机此前打开的descriptor已关闭。未完成请求应阻止操作，或证明其临时文件与正式资源generation隔离，且迟到结果永不能发布到新generation；不能把应用内lease排空冒充外部进程停写。

TOCTOU 不能靠两次查询加未约束其他 writer 的局部 mutex解决：每个 writer 必须遵守同一 admission，且查询与进入独占之间的在途 writer 必须被 drain。正确覆盖全部入口的共享 admission mutex可以是实现手段，但不能仅锁 Backup 自己。检查 ACTIVE slot 时还需发现状态字段/slot 不一致，不能只信 UI Flow 的旧值。

**owned cleanup 必须有可验证结果**：releaseSession 只请求 stopService，不 join；onDestroy 后 factsScope 还可 launch；渠道 release 排队 reconcile；DND失败记录 RELEASE_FAILED 后 Unit返回；Presenter clear失败保留 owner（E10–E12）。`startup.await()` 和 Closeout best-effort 返回也不证明 cleanup成功。

未来 quiescence 合同应给出：poll/watchdog/receiver退出、facts/channel队列排空、presentation owner/通知/Overlay清理确认、DND owned状态确认以及全部持久化任务退出。无法确认、超时或权限不足时拒绝 Backup/Restore，保留原 owner线索，不自动结束阅读或强改系统DND。

Restore 还必须暂停/终止旧数据 generation 的**读者**：Room flows、ViewModel、FTS、图片 loader/文件 descriptor、runtime titleProvider均可持有旧实例。仅阻止写不等于可以安全 close/replace。当前没有统一 container close/rebind或 runtime detach；接入必须增加受控生命周期，不修改其冻结业务判定。

## 6. Snapshot 数据合同

### 6.1 Room 一致性与 WAL

SQLite WAL 中已提交数据可以尚未进入主文件；事务读视图和文件复制不是同一件事。禁止复制运行中的 mirra.db，禁止先拷 DB 后拷 WAL/SHM 当作一致备份。[SQLite WAL](https://www.sqlite.org/wal.html)

建议在屏障排空后，从一致源视图有界读取 12 表，在独立 app-private v4 DB保留所有正式ID、字段和关联；只在**副本**归一化设备 owner，重建FTS，完成独立数据库的 checkpoint/close及完整验证后归档。该方向避免依赖未核实的 Android SQLite原生 Backup API / VACUUM INTO 支持；不能通过普通 create/save重建，因为它们会生成新ID/时间或触发业务副作用。当前缺少全表有界 snapshot/import接口。

如后续选择物理 DB 快照，必须另外证明：无残留连接/事务、实际模式/checkpoint结果、关闭后的独立可打开性、所有相关sidecar处理及可靠落盘；方法返回成功或单次 PRAGMA 调用不够。内部 rollback 的旧 DB保护需保留切换前完整可用文件集合，不可只保留未经checkpoint的主文件。

### 6.2 跨资源快照

- 同一屏障 epoch 内严格读取一份 Preferences，并复制该Room视图引用的全部正式JPEG。复制后对私有快照逐项 hash/尺寸/关系复核，成功前源资源始终不能变化。
- 用户包建议包括 metadata、canonical Room v4数据库、白名单preferences及images manifest/bytes；确切entry名、限额和codec在后续格式合同锁定，不能靠本审计默认为已实现。
- metadata必须明确app/format/schema、创建时间、表/图片数量、文件长度和hash、ownership归一化版本；hash清单不循环包含自身hash。
- hash只证明包内完整性，**不提供来源认证**；攻击者能重写metadata/hash，所有外部包仍是非可信输入，不能因“Mirra格式”跳过schema/关系/限额校验。
- 内部当前安全快照不同于可迁移用户包：必须保留完整旧数据/偏好和原正式图片，不能先用导出归一化覆盖旧库，也不能通过业务create/delete生成rollback。

### 6.3 历史合法性与关系校验

Room schema/identity、SQLite integrity_check / foreign_key_check、唯一索引、枚举合法值、nullable/type和资源限额都必须核对；不得执行包中提供的SQL脚本，拒绝非白名单表/trigger/view及不匹配结构。incoming DB只能在独立暂存区经受限只读验证连接读取，不注册安装Migration、不运行正常container或startup回调；从已验证的白名单字段构造干净Room v4 staging，不把外来DB对象原样发布到生产。

关系除声明FK外，还包括 Intent/Session的Item一致、Note/Session的Item一致、active/mainline slot值与数量、合法页码/closeout组合、Segment/Event软引用与本场归属。`relatedSegmentId`和`FocusEvent.segmentId`没有SQL FK，须按冻结生产语义检查，不自行加新Schema约束。

必须接受并保留合法 NORMAL/ABNORMAL、PARTIAL/NONE、零新增阅读页（`endPage == startPage`）、可信0及 legacy历史；绝对页码仍遵守既有合法范围，不能以零推进为由接受非法页码0。不能把 COMPLETE_TRUSTED当备份门槛，也不能补造Focus或提高coverage。3→4未回填legacyContext/Segment。普通ABNORMAL恢复可留下 endedSession + Context.closeoutState=ACTIVE，不能单凭这个字段拒绝；PENDING则必须阻止本次操作。Closeout零长度末段可被合法删除，不补1ms段（E07/E15；`app/src/androidTest/java/com/guanyi/mirra/data/MigrationThreeToFourTest.kt:38–39`、`app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDCloseoutRepositoryTest.kt:113–119`）。

## 7. Runtime ownership 排除与归一化

| 数据类别 | 合同 |
|---|---|
| 当前设备权限/安装状态 | Usage/DND/Overlay/POST_NOTIFICATIONS重新查询；不恢复权限、不假装risk package在新设备已安装 |
| 进程内状态 | 不备份 FGS/binding generation、READY lease、cursor/epoch、candidate、elapsed deadline、evidence tracker、摩擦可见时间、tokens/PendingIntent/navigation请求、Presenter owner或cached capability |
| 系统 owner字段 | portable副本清除 `dndRuleId` / `priorDndInterruptionFilter`；不可迁移未完成ACTIVE/RELEASE_PENDING/RELEASE_FAILED或rule创建占位符。必须先证明源 owned cleanup已完成，不靠清字段掩盖真实残留 |
| dndLifecycle | 只能按明确codec投影到无可执行ownership的终态；具体允许值/终态映射须在4C-1审阅。NOT_APPLIED/RELEASED/APPLY_FAILED本身也不能代替真实owner检查；归一化不证明历史从未开启DND或原设备release成功 |
| 历史快照 | 保留四 accessAtStart、规则阈值、session risk label/package；它们是历史，不是当前系统授权 |
| 可信事实 | 保留 monitoringStatus/lostAt/lastHeartbeat、Session稳定/结束时间、UNMONITORED、所有Segment/Event/receipt、closeoutStartedAt/requestedEndPage；不能reset为FULL或删除“不漂亮”事实 |
| 用户偏好 | 四偏好按正式合同迁移，只影响未来操作，不自动启用DND/FGS/跨App提示 |

slot是业务状态约束，不是可随意清空的runtime token。release/cleanup仍需原设备真实owner线索，必须在切换前完成；恢复后不对备份中的ruleId或priorFilter调用系统API。API23–28若存在未完成/待确认legacy effect且无法证明ownership，必须保守阻止切换，不强行恢复用户全局状态；已证明RELEASED或从未施加保护且没有残留owner，不因历史priorFilter存在或冷进程本身一刀切拒绝（E12）。

## 8. App startup 与 earliest Journal gate

### 8.1 当前真实顺序

`Application → monitoringPlatform lazy求值 → DefaultAppContainer → Room builder / DataStore delegate / Storage mkdir / repositories → attach runtime与常驻scope → startup async → PENDING/ABNORMAL recovery → intervention cleanup → DND reconcile → image cleanup → FTS ensure → Activity await后显示UI`。

构造Room对象不一定当场打开SQLite连接，但它及其DAO已被捕获，startup随即可能开库并写。不能把journal放在现有startup最后或Activity await之后。新建第二container也不足：DS顶层实例仍缓存，runtime attach还要求只调用一次。

### 8.2 接入要求与建议

- 必须在 `MirraApplication.onCreate` 构造DefaultAppContainer和读取monitoringPlatform之前处理Journal，且所有其他进程/Service入口也遵守bootstrap gate；禁止带未决journal建立正常runtime。
- gate只做选择有效资源generation、提交/回滚/清理判定。必要的有限验证连接须明确关闭，不能先建立正常旧Room/Repository/Flow，也不能先按错误一侧数据执行recovery、FTS或orphan cleanup。
- 当前DataStore已建立时不能raw swap。建议审阅“冷进程bootstrap发布 + 受控singleton原子preferences edit并复用”或真正scope teardown/rebind方案；本轮不选择新的用户流程、不自动杀进程。若需要延后下一次进程启动发布，必须先持久化REQUESTED，并在真正发布前重核状态、捕获**当时最新**旧安全快照；不能回滚到用户其后新增数据之前的旧snapshot。
- Journal决定完成后，才建立一代container；继续原冻结 `PENDING正常结算 → 普通遗留ACTIVE异常恢复 → owned cleanup → 图片 → FTS` 顺序，不恢复FGS或旧owner。
- Journal损坏/发布或回滚结果未决/rollback失败时只进入维护诊断，不开放业务写入，不尝试“选看起来较新的db”或让orphan cleanup猜测。已经确认durable COMMITTED且仅CLEANUP_PENDING，不属于结果未决，可按选定新generation启动，旧临时资源清理继续幂等完成。

这是可定位的源码接入点，不是已经可调用的安全API。后续若无法证明全部owner/读者停止、DS单实例和代际一致，必须停止实施审阅。

## 9. Restore Journal：建议状态、切换前置条件与责任

Journal应独立于被替换Room/DS/images，app-private且非cache；建议noBackupFilesDir受控根，不能被系统备份当跨设备业务状态搬运。它不是Room新表。单writer互斥、operationId、sequence、old/new generation、每资源路径/尺寸/hash、动作intent/完成状态、校验状态和错误需可恢复；仅使用相对受控路径，禁止把外部entry名直接作journal目标路径。

下列状态是技术接入建议，4C-1需审阅成可测试协议。每个文件动作都必须先写durable intent；动作后验证/落盘，再写完成标记。恢复要同时检查真实资源identity，不能只信“最后标记”。

| 状态 / 切换点 | 可验证前置条件 | 持久化条件及恢复责任 |
|---|---|---|
| NONE → VALIDATED / REQUESTED | 外部包已在隔离区完整读取、限额/hash/Schema/关系/图片验证，staging已重建FTS并关闭 | 无正式数据改变；进程死亡只保留/清理本次受控staging。延期发布不允许先覆盖旧资源 |
| → PREPARED | exclusive + drain完成；最终active/owned检查通过；旧DB/全部偏好/正式图片安全快照完整且验证；空间足够 | durable旧快照与staging manifest先落盘，再落Journal PREPARED；之前失败不得改变正式数据 |
| → APPLYING_IMAGES | 无旧reader/writer，所有目标同受控filesystem，旧图片generation被pin | 先持久化交换intent，再rename/验证/同步父目录，再记完成；effect已发生但marker缺失用hash/generation判断，不删除旧目录 |
| → APPLYING_DATABASE | 图片交换可回滚，旧DB全部连接/sidecar关闭处理；staging DB已独立可打开 | 旧DB安全副本不可覆盖；rename步骤与sidecar处理逐项记intent/结果。未COMMITTED死亡默认恢复完整旧generation，不开放混合资源 |
| → APPLYING_PREFERENCES | 单实例生命周期已证明；原完整Preferences snapshot可重放 | 原子edit整组候选值并等待落盘/严格回读；日志记录semantic hash/版本。失败回滚全部原key及absence，不调用四setter拼接 |
| → VERIFYING | 三资源均标识为候选generation，暂未开放container/UI写入 | 独立验证DB/schema/integrity/FK/软关系、图片、偏好、FTS中文实查；测试连接关闭。失败进入ROLLING_BACK |
| → COMMITTED | 完整候选验证成功；所有resource与Journaldurability步骤成功 | durable COMMITTED后新generation才可开放；之后不因cleanup失败回滚已被用户继续写入的新数据 |
| → CLEANUP_PENDING → DONE | COMMITTED；新generation可用 | 仅清理本operation受控旧快照/临时路径，幂等；中断下次继续，不变更结果、不碰新业务数据 |
| → ROLLING_BACK → ROLLED_BACK | 任意precommit失败，旧快照仍完整 | 每个资源恢复均记write-ahead intent、回读验证；完整旧generation复核后才开放。任一失败保持maintenance并保留两套数据 |
| JOURNAL_INVALID / ROLLBACK_FAILED | torn、校验失败、路径非法、资源身份不明或磁盘错误 | fail closed；不删任一可恢复generation、不自动降级成功；提供可定位错误和后续恢复责任 |

交换次序可以在实现审阅中调整，但**不存在 DB + DataStore + images 的单个原子rename/Room transaction**。不能称表中三步总体原子；安全性来自不开放混合状态、保留旧generation、Journal和幂等回滚。

Journal COMMITTED写入抛错但可能已落盘时，不可立即假定未提交并开始回滚：先重新读取有效记录/sequence并核对资源；回读可见不等于file/directory sync已成功，若durability步骤失败，仍须保持maintenance并重新建立可证明的durable决定，不能仅凭可见marker开放或回滚。无法辨明则保留两代材料。rollback恢复到旧资源后若结论marker写失败也同样保留可恢复材料，不能清理。

## 10. 文件落盘、ZIP/SAF与故障矩阵

### 10.1 落盘限制

单文件AtomicFile用于完整Journal写入，但不提供线程/进程锁，也不提供跨资源事务；调用者必须互斥。文件sync与rename之后还需审阅父目录同步和错误处理，不能把普通File.renameTo成功等同硬件断电安全。[Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)

本轮另核对AOSP当前AtomicFile源码：finishWrite对sync/close失败只记录日志，内部rename失败也只记录日志；“finishWrite返回且未抛异常”不能作为durability成功条件。后续需可观测的严格sync/rename结果、文件identity验证和目录同步，不能只包一层runCatching就称Journal可靠。这是当前官方源码约束，不冒称已实测所有API实现。[AOSP AtomicFile源代码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/util/AtomicFile.java)

跨filesystem rename不作为切换原语；外部SAF URI不是内部原子publish路径。建议候选/旧快照在同一内部filesystem，文件内容sync、关闭、受控rename、父目录同步和Journal落盘逐项核对。Android提供fsync/rename等低层接口，但本轮没有验证目标filesystem/设备行为。[Android Os](https://developer.android.com/reference/android/system/Os)

Durability前置调用失败、目录sync无法建立保证、磁盘错误、旧快照不可读，都必须拒绝commit，不降级为“尽力恢复”。进程死亡可通过故障注入验证；真实断电/损坏硬件不能由AVD自动外推，继续NOT RUN。底层存储同时损坏所有副本的风险无法由软件绝对消除，不能承诺任何硬件故障都恢复。

### 10.2 输入边界和故障责任

在app-private新空目录中解包，拒绝绝对路径、`..`、反斜杠/编码别名、规范化重复路径、symlink/非普通entry、非白名单entry，逐entry核对canonical containment。标准解包API不能代替路径验证。[Android ZIP path traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)

entry数量、单文件/总压缩及解压长度、压缩比、metadata长度、行数、图片像素、耗时和SQLite读取必须有明确限额；不能只信ZIP header的size或CRC。数值在4C-1/codec协议审阅锁定并测边界，本轮不凭空冻生产阈值。

| 故障 | 检测 / 责任 | 正式数据安全结论 |
|---|---|---|
| ZIP路径穿越 / symlink | 解包前白名单+canonical containment，新空受控根 | 不触碰正式目录；拒绝包 |
| 重复entry / 别名碰撞 | 规范化entry集合去重，metadata重复key拒绝 | 不采用last-wins；拒绝 |
| ZIP bomb / 超限压缩 | 流式累计实际字节/entry/像素/行数/耗时，有界读 | 中止仅本次staging；旧数据不变 |
| hash mismatch / 长度不符 | 本地重新计算完整文件hash/bytes，不信metadata | 不进入PREPARED |
| 缺图 / JPEG损坏 | 引用集合与manifest/files精确对应，bounded decode/尺寸校验 | 拒绝；不删除Note/ImageAsset“修好”备份 |
| 非法业务引用 / 枚举 / Schema | Schema对象白名单、integrity/FK/语义检查 | 拒绝；不运行包内migration或脚本 |
| format/schema不支持 / 裸DB | format1/schema4及正式archive入口合同 | 明确unsupported；不靠安装Migration接受旧库 |
| 空间不足 | staged + 旧快照 + 交换/FTS/Journal最大临时空间与reserve检查；每次写仍处理ENOSPC | 预检查不保证随后不满；无完整旧快照不切换，切换失败保持可回滚材料 |
| SAF读取中断 / grant撤销 | 在隔离区完整读取关闭验证后才处理正式数据 | 输入失败不改变当前数据 |
| Backup创建中死亡 | 私有operation临时包未标完成，不发布成功；源只读 | 源数据不变；下次只清本operation不完整文件 |
| Restore切换中死亡 | bootstrap先读Journal和文件identity，按第9节恢复 | precommit恢复旧完整generation；不得让startup写混合态 |
| Journal更新中断 | 原子记录+sequence+checksum及前后generation核对 | 不凭文件时间猜；不确定保持maintenance |
| DB/DS/images部分替换 | 每资源write-ahead intent及旧副本，拒绝业务开放 | 全部验证或全部回滚；不允许部分成功 |
| FTS rebuild失败 | staging内显式rebuild+实查；正常库未publish | 失败不提交；若已在VERIFYING，回滚全体资源 |
| 旧数据rollback失败 | 保留旧/候选/Journals，不重建空库、不删文件 | 停止，明确ROLLBACK_FAILED / 人工诊断责任 |
| COMMITTED后cleanup中断 | 新generation已决定；cleanup幂等重试 | 保留新数据，不为清理失败回滚 |
| 外部SAF保存失败 / 截断 | 已验证私有包流式输出、关闭并能回读则校验；不能验证provider持久性时不宣称verified | 不影响源数据；提示失败/未验证。仅可清本次新建且确有授权的残片，不覆盖/删其他用户文件 |

SAF允许用户选择本地或云provider；Mirra不主动上传不代表用户所选provider一定离线。Provider行为不能被当作内部fsync/原子rename保证，必须区分“本地包验证成功”与“外部写出/回读结果”。[Android SAF](https://developer.android.com/training/data-storage/shared/documents-files)

## 11. 安全阻塞项与后续工程依赖

### 11.1 当前实施门槛

| ID | 状态 / 代码证据 | 必须解除的条件 |
|---|---|---|
| SB1 | `ARCHITECTURE_SAFETY_BLOCKER`：没有global admission/drain；E05/E06/E08/E10–E13 | 覆盖52入口、helpers、Service/外部相机/异步退出flush及完整补偿链；证明无TOCTOU和late-generation写 |
| SB2 | `ARCHITECTURE_SAFETY_BLOCKER`：DataStore fallback、四独立edit、delegate缓存；E03 | 单次strict读、整组原子edit、原全部偏好回滚、singleton生命周期；禁止live file overwrite |
| SB3 | `ARCHITECTURE_SAFETY_BLOCKER`：container/runtime持有旧实例、没有early gate/close/rebind；E01/E09–E11 | Journal先于正常构造，所有reader/writer/owner停止，完整generation选择后才normal startup |
| SB4 | `ARCHITECTURE_SAFETY_BLOCKER`：现有trash补偿不是三资源Journal；E04/E05 | durable旧快照、Journal/write-ahead、每切换点恢复/rollback、fsync错误处理与故障注入 |
| SB5 | `ARCHITECTURE_SAFETY_BLOCKER`：cleanup为best-effort返回，不证明系统owner静止；E10–E12 | 可确认的owned cleanup/quiescence结果；不丢原DND线索、不改用户系统状态、不为backup自动close Session |
| SG6 | 未实现snapshot/codec/security validation；E01/E02/E06/E15 | 12表有界读取、严格format/schema/media/relations、FTS重建、空间/ZIP/SAF限额、真实round-trip |

这些是**禁止未经补齐就发布恢复能力**的接入阻塞，不是宣称现有学习功能有新的Bug。已有target architecture可作为解除方向，但本审计不是安全性运行证明。若后续发现无法在v4及冻结语义内解决，应停止输出 `ARCHITECTURE_SAFETY_BLOCKER`，申请审阅，不偷偷升级Schema、删数据、弱化校验或绕过权限。

### 11.2 4C-1 至 4C-5 建议依赖

仅将已批准4C方向映射为工程接入依赖，不授权实施、不重新拆产品Scope：

| 交付包 | 前置依赖 / 建议接入点 | 验收门槛 |
|---|---|---|
| 4C-1 安全基础 | shared maintenance lease/generation、strict prefs、container生命周期/early bootstrap、quiescence结果；E01/E03/E09–E13 | Writer Inventory覆盖、排空/重核TOCTOU、嵌套不死锁、late flush/camera/runtime不写新代、失败保持当前数据 |
| 4C-2 正式Backup v1 | 4C-1 + bounded Room snapshot、media copy/hash、portable owner projection、codec/validator | WAL committed事实、12表+四偏好+JPEG完整round-trip、一致epoch、strict-read失败拒绝、恶意输入限额 |
| 4C-3 安全Restore | 4C-1/2 + staging/FTS、旧安全快照、Journal、跨资源publish/rollback与startup处理 | 每个intent/effect/marker点进程死亡，原DB/images字节及全部原偏好恢复；rollback失败fail closed；延期捕获最新旧数据 |
| 4C-4 SAF/薄UI接线 | 仅调用已验证服务；保留Mine平面，用户确认全量替换和隐私 | cancel/provider失败/低空间/重复请求，活动状态拒绝，不自动结束学习、不开新账号/云 |
| 4C-5 最终验证 | 前四包完成且分别获准 | 故障矩阵、离线、空/长历史/大图片库、索引/偏好/时间线回归、覆盖安装与真实设备边界记录 |

可建议新增Kotlin接入职责为maintenance coordinator、strict preference snapshot port、storage owner/bootstrap gate、snapshot codec/validator、journal/recovery coordinator；**本轮未创建任何生产文件或接口**。普通Repo只加维护接线，不重做3B/Closeout/DND算法。若平台生命周期适配会改变READY或DND ownership业务语义，必须停下审阅。

## 12. 本轮验证与停止边界

- 已执行：固定Git/分支/工作树核对；源码与Schema只读审计；Schema4文件SHA-256；官方平台文档核对；审计文档差异/文件范围检查。
- 未执行：JVM/Room/Migration/connected/Compose/lint/build；AVD/ADB/真机；数据库打开/运行时journal_mode读取；任何实际快照、导出、恢复、fsync/ENOSPC/进程死亡故障注入。没有新增PASS数字。
- API23–36、完整OEM/physical、TalkBack、release/Play、真实断电、人工系统时钟修改继续NOT RUN；一加13T日常试用不是兼容性PASS。
- 只提交本文件，不修改CURRENT_STATE、DECISIONS、PRODUCT_SPEC、旧checkpoint、生产/测试/Schema/Migration/Manifest/Gradle/resources，不进入4C-1/4D。

审计交付状态：`PHASE_4C_0_READY_FOR_INDEPENDENT_REVIEW`。
