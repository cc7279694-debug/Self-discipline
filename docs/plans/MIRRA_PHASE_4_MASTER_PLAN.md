# Mirra Phase 4｜个人趋势与数据安全

## 1. 文档状态与当前真实基线

- 状态：**Phase 4 Planning / FROZEN / awaiting implementation authorization**。Delta Audit、五阶段规划及 ChatGPT 审阅修订已完成，用户正式授权本轮 Planning Freeze；冻结对象仅为本 Master Plan 与其中已批准的产品决定，不代表 Phase 4 功能完成或 4A 实施授权。P3 的速度异常阈值继续 PROPOSED，待 4B 单独审阅。
- 审计日期：2026-10-07。仓库：`cc7279694-debug/Self-discipline`。
- 实际工作目录：`C:\Users\CDD\Documents\ChatGPT\Mirra`；旧中文路径不是本轮事实源。
- 当前分支：`codex/phase-3d-closeout-v2`。
- Planning Freeze 父基线：`b2adc9a3506329f92c73acff2ad7d07f8a4a64b7`；开始冻结前只读核对的本地/远程分支 SHA 相同。冻结提交及 Push 后 local/remote SHA 以 Git 实读和本轮报告为准。
- Closeout Revision accepted implementation：`085543ffd0b5d03859565577a8ee277036fc50f9`，`accepted / frozen`。
- 历史整个 Phase 3D Formal Freeze：`096af8e5e943b8efbca8aa47b10ab2b7d2f53e18`。继承已完成的 3D-2 / 3D-3 / 3D-4，不重新开发。
- Room `version = 4`；13 个注册 Entity，包括派生 FTS Entity；业务 Schema 1–4、Migration 1→2→3→4 原样继承。
- Kotlin / Compose / Navigation 3 / Room / DataStore / App-owned Files；Local-first、Offline-ready、No-login-first。
- 新品牌与 Mirra Blue 保持现状。本轮不修改任何生产代码、测试、Schema、Manifest、Gradle、权限或设备状态。
- 冻结范围：只提交本 Master Plan 与 CURRENT_STATE planning 状态，用户已授权独立文档 Commit / Push 到当前分支；不修改 PRODUCT_SPEC / DECISIONS / 旧 checkpoint，不运行 Gradle / AVD，不实施 4A，不创建 Schema v5，不 merge / force push / release。

原 Delta Audit 已完整阅读 `AGENTS.md`、`PROJECT.md`、`README.md`、`docs/PRODUCT_SPEC.md`、`docs/CURRENT_STATE.md`、`docs/DECISIONS.md`，并核对第 3 节的代码、测试、相关历史计划和最终冻结记录。本次修订完整复核本计划、正式审阅决定及对应 Intent→Session 实现，并只读检查 Git/Schema；没有重做全仓审计或独立 ChatGPT 验收。两轮均**没有重新运行 Gradle、ADB、AVD、真机或性能测试**。

本轮实读 Schema SHA-256：

| Schema | SHA-256 |
| --- | --- |
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

## 2. Capability Matrix｜以实际实现而非字段存在判定

`DONE` 指限定能力已经实现并有历史测试证据，不等于全平台发布验收。`PARTIAL` 指确有可复用实现但缺 Phase 4 增量。`SUPERSEDED` 只标被后续实现覆盖的旧工作，不废弃被冻结的现存功能。未审定事项独立列 `NEEDS PRODUCT DECISION`；用户已批准移出 V1 的目标标 `DEFERRED BY PRODUCT DECISION / Post-V1 candidate`，不标 DONE。

| 能力 | 状态 | 真实已有能力 / 尚缺增量 | 证据 | Schema 影响 / 冻结边界 |
| --- | --- | --- | --- | --- |
| 总 Session 时长、overall speed、普通剩余时间、自然完成预测 | DONE | Phase 2 已完成资格、加权速度、7→14→30 窗口、预测门槛与区间 | E1 | v4 可读；不改 Phase 2 公式 |
| Mine 最近 7 天与前 7 天基础摘要 | DONE | 已有 Session/时长/页数/独立 Note 数；UI 展示一句时长差 | E1、E2 | 不重做，不把 Note 数改成 Session Note 数之和 |
| Start / Maintain / Recover 长期趋势 | PARTIAL | 原始 Intent、milestone、Segment/event 事实已有；缺全局 source、分母契约和趋势 UI | E3、E4、E5 | 基础事实可保持 v4；缺失事实不猜 |
| 30 / 90 天与全部历史趋势 | NOT IMPLEMENTED | 现有有限窗口不是长期趋势页面；需要日期边界与有界聚合 | E1、E2 | 只读查询优先；索引另行测量 |
| 单场 ReadingRecord、Summary、单书 History、Search SESSION 回看 | DONE | 统一 Source / Projection / ViewModel / Content / 详情路由 | E6 | 不建立第二套 History 或可信判定 |
| 全局 Session 历史列表及行为聚合 | PARTIAL | 全局 endedBetween 已供 14 天摘要；缺全局分页、可信行为聚合 | E1、E6 | 复用详情；列表不逐条启动 5-query source |
| 有效专注、effective speed、effective remaining time | DONE | COMPLETE_TRUSTED、可信 0 / unavailable null、加权有效速度已完成 | E4、E7 | 不修改 Validator / effective formulas |
| 阅读速度异常提醒 | NOT IMPLEMENTED | 只有“连续多次才轻提醒”的需求；没有异常 service / UI | E1、E7 | 同书、同口径派生；阈值待审 |
| 异常提醒阈值、样本范围及文案 | NEEDS PRODUCT DECISION | robustCV 是完成日期可靠性，不是异常提醒规则 | E1、E7 | 不把 0.75 当作异常阈值 |
| 用户状态采集与主观状态相关性 | DEFERRED BY PRODUCT DECISION / Post-V1 candidate | 尚未实现，用户正式决定 V1 不采集情绪/疲劳/环境等主观状态、不分析其相关性；规则/权限快照不是情绪状态 | E3、E8、P2 | 本轮不建 UserStateSnapshot，不增加启动摩擦，不从 DND/Coverage/Break/权限推测主观状态 |
| 主观状态相关性的 V1 范围 | DEFERRED BY PRODUCT DECISION / Post-V1 candidate | 原规格目标经用户正式范围调整移出 V1，不是技术完成 | E8、P2 | Phase 4 默认保持 v4；Post-V1 若重启该需求必须独立审查，不回填旧数据 |
| stale ACTIVE / ABNORMAL / PENDING Closeout 恢复 | DONE | 现存不同恢复分支与原结束边界、事实 guard 已实现 | E9 | 继承 v4，不重写恢复核心 |
| Phase 4 再实现一次异常恢复、ReadingRecord 或速度公式 | SUPERSEDED | 已由 Phase 1 / 2 / 3A–3D / Revision 覆盖；剩余是验证/聚合增量 | E1、E6、E7、E9 | 旧工作移入回归清单，不作为新实现 |
| Phase 2 时期“有效指标尚不可用”的阶段限制 | SUPERSEDED | 后续 3D 已实现有效指标；不是整体淘汰 Phase 2 统计 | E7 | overall / natural prediction 继续独立保留 |
| App-owned 图片、trash 补偿与启动清理 | DONE | UUID 相对路径、JPEG、引用恢复、24h 清理已实现 | E10 | 不是用户 Full Backup/Restore |
| DataStore 设置基础 | DONE | destination / theme / dnd / cross-app 四项及 fallback | E11 | 不增加持久化框架 |
| DataStore 可备份的一致快照/导入/回滚 | PARTIAL | Repository 已有读写，但缺严格读取、原子导入与维护屏障 | E11 | 文件/接口增量，不必改 Room |
| Android system backup | PARTIAL | Manifest 只有 allowBackup=true；没有规则或已验证恢复契约 | E12 | 后续仅按批准的传输政策变更 Manifest/XML/必要接线 |
| Android cloud / device-transfer 的具体传输范围 | NEEDS PRODUCT DECISION | 产品责任已批准：系统能力仅为有限补充，Full Backup / Restore 是唯一完整恢复承诺；cloud/D2D 具体内容由 4D 单独审定 | E12、S1、P4 | 不保证完整恢复或所有 OEM/transport 可靠；不能仅凭 allowBackup 宣称范围正确 |
| Mirra Full Backup / Restore | NOT IMPLEMENTED | 没有 production ZIP/metadata/staging/rollback/SAF/UI | E10–E13 | 首版仅正式 format v1 / Room v4；全量替换须明确用户确认 |
| Full Backup / Restore 的产品合同 | NOT IMPLEMENTED | 决定已批准但尚未实施：全量替换、终态维护屏障、安全快照、正式 format v1 / Room v4、严格校验、明示非加密私人文件 | E10–E13、P5–P8、P11 | 不 merge、不导入裸 DB 或任意旧安装库、不丢图、不恢复设备运行 ownership |
| JSON export / CSV export | NOT IMPLEMENTED | navigation serialization 与测试 marker JSON 都不是业务导出 | E13 | 读现存事实，不新增导出表 |
| Full Backup 恢复、回滚与跨资源一致性测试 | NOT IMPLEMENTED | 升级保留、Migration、图片补偿不等于完整恢复测试 | E10、E13 | 增量数据安全 gate |
| Migration 1→2→3→4 基础与覆盖安装保留证据 | DONE | 实际历史 Schema 非破坏迁移和专项 preservation 已有 | E8、E13 | 不扩写成所有旧安装数据绝对保留 |
| 全平台可靠性、长历史性能、系统备份实测 | PARTIAL | 有大量 API37/JVM 证据；无本轮 benchmark，设备/发布矩阵未完成 | E14 | 未测仍 NOT RUN；不能为了全绿修改核心 |

## 3. 实现证据索引与继承点

路径均为仓库相对路径；下表链接指向真实文件。列出的测试是已检查的测试定义，**不是本轮重新执行结果**。

| ID | Repository / Service / 数据 / UI | 测试与历史来源 |
| --- | --- | --- |
| E1 | [ReadingAnalyticsRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/ReadingAnalyticsRepository.kt) 44–90：单书 effective source、14d source；[ReadingAnalyticsService](../../app/src/main/java/com/guanyi/mirra/domain/ReadingAnalyticsService.kt) 9–84：资格/窗口/两个 7d；[CompletionPredictionService](../../app/src/main/java/com/guanyi/mirra/domain/CompletionPredictionService.kt)：日期门槛与 leave-one-out 最小 CV；[SessionDao](../../app/src/main/java/com/guanyi/mirra/data/local/dao/SessionDao.kt) 60–132：[ReadingSessionProjection](../../app/src/main/java/com/guanyi/mirra/data/local/model/ReadingSessionProjection.kt)；[NoteDao](../../app/src/main/java/com/guanyi/mirra/data/local/dao/NoteDao.kt) 52–57：createdAt 非空 Note count | [ReadingAnalyticsServiceTest](../../app/src/test/java/com/guanyi/mirra/domain/ReadingAnalyticsServiceTest.kt)、[ReadingAnalyticsRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ReadingAnalyticsRepositoryTest.kt)、[CompletionPredictionServiceTest](../../app/src/test/java/com/guanyi/mirra/domain/CompletionPredictionServiceTest.kt)；[Module 2D plan](MODULE_2D_IMPLEMENTATION.md) |
| E2 | [ProfileViewModel](../../app/src/main/java/com/guanyi/mirra/feature/profile/ProfileViewModel.kt) 41–66 / 92–105：日历边界与摘要；[ProfileScreen](../../app/src/main/java/com/guanyi/mirra/feature/profile/ProfileScreen.kt) 228–247：平面摘要；[LearningItemScreens](../../app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemScreens.kt)：节奏与单书历史 | [ModuleTwoDFlowTest](../../app/src/androidTest/java/com/guanyi/mirra/ModuleTwoDFlowTest.kt)、[LearningItemEffectivePaceTest](../../app/src/test/java/com/guanyi/mirra/feature/knowledge/LearningItemEffectivePaceTest.kt) |
| E3 | [Entities](../../app/src/main/java/com/guanyi/mirra/data/local/entity/Entities.kt) 50–96：Intent 时刻/结局、Session stableStartedAt；[IntentDao](../../app/src/main/java/com/guanyi/mirra/data/local/dao/IntentDao.kt) 26–36；[StudyWorkflowRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt) monitored start；[SessionStartCoordinator](../../app/src/main/java/com/guanyi/mirra/domain/SessionStartCoordinator.kt) | [SessionStartCoordinatorTest](../../app/src/test/java/com/guanyi/mirra/domain/SessionStartCoordinatorTest.kt)、[Phase 3 plan](PHASE_3_IMPLEMENTATION.md)、[Module 3B plan](MODULE_3B_IMPLEMENTATION.md) |
| E4 | [SessionTimelineValidator](../../app/src/main/java/com/guanyi/mirra/domain/SessionTimelineValidator.kt) 34–65、[SegmentTimelinePolicy](../../app/src/main/java/com/guanyi/mirra/domain/SegmentTimelinePolicy.kt)、[FocusEntities](../../app/src/main/java/com/guanyi/mirra/data/local/entity/FocusEntities.kt)、[FocusDao](../../app/src/main/java/com/guanyi/mirra/data/local/dao/FocusDao.kt) | [SessionTimelineValidatorTest](../../app/src/test/java/com/guanyi/mirra/domain/SessionTimelineValidatorTest.kt)、[ModuleThreeAFocusRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeAFocusRepositoryTest.kt) |
| E5 | [StableEvidenceTracker](../../app/src/main/java/com/guanyi/mirra/domain/monitoring/StableEvidenceTracker.kt) 24–37；[BoundSessionMonitoringController](../../app/src/main/java/com/guanyi/mirra/domain/monitoring/BoundSessionMonitoringController.kt)；[FocusRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/FocusRepository.kt) 146–148 / 262–301：不同 Recovery event 关联语义 | [BehaviorPolicyTest](../../app/src/test/java/com/guanyi/mirra/domain/monitoring/BehaviorPolicyTest.kt)、[FocusSessionActionsTest](../../app/src/test/java/com/guanyi/mirra/domain/monitoring/FocusSessionActionsTest.kt)、[ModuleThreeCBehaviorRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeCBehaviorRepositoryTest.kt)；[3C Master Plan](MIRRA_MODULE_3C_MASTER_PLAN.md) |
| E6 | [ReadingRecordRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/ReadingRecordRepository.kt) 14–22：5 个查询；[ReadingRecordService](../../app/src/main/java/com/guanyi/mirra/domain/ReadingRecordService.kt)、[ReadingRecordSource](../../app/src/main/java/com/guanyi/mirra/data/local/model/ReadingRecordSource.kt)、[ReadingRecordViewModel](../../app/src/main/java/com/guanyi/mirra/feature/session/ReadingRecordViewModel.kt)、[ReadingRecordContent](../../app/src/main/java/com/guanyi/mirra/feature/session/ReadingRecordContent.kt) | [ReadingRecordRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ReadingRecordRepositoryTest.kt)、[ReadingRecordProjectionTest](../../app/src/test/java/com/guanyi/mirra/domain/ReadingRecordProjectionTest.kt)、[ReadingRecordNavigationTest](../../app/src/androidTest/java/com/guanyi/mirra/ReadingRecordNavigationTest.kt)；[3D-3 plan](MIRRA_PHASE_3D_3_READING_RECORD_PLAN.md) |
| E7 | [EffectiveReadingService](../../app/src/main/java/com/guanyi/mirra/domain/EffectiveReadingService.kt) 12–80、[EffectiveReadingSource](../../app/src/main/java/com/guanyi/mirra/data/local/model/EffectiveReadingSource.kt)；[LearningItemScreens](../../app/src/main/java/com/guanyi/mirra/feature/knowledge/LearningItemScreens.kt)：有效窗口优先、自然日期独立 | [EffectiveReadingServiceTest](../../app/src/test/java/com/guanyi/mirra/domain/EffectiveReadingServiceTest.kt)、[EffectiveAnalyticsCompatibilityTest](../../app/src/test/java/com/guanyi/mirra/domain/EffectiveAnalyticsCompatibilityTest.kt)、[EffectiveReadingRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/EffectiveReadingRepositoryTest.kt) 52–84：800-ID 批次预算；[3D-2 plan](MIRRA_PHASE_3D_2_EFFECTIVE_METRICS_PLAN.md) |
| E8 | [MirraDatabase](../../app/src/main/java/com/guanyi/mirra/data/local/MirraDatabase.kt) 28–45：13 Entity / v4；[Migrations](../../app/src/main/java/com/guanyi/mirra/data/local/Migrations.kt)：既有显式链；[schemas](../../app/schemas/com.guanyi.mirra.data.local.MirraDatabase/) | [MigrationOneToFourTest](../../app/src/androidTest/java/com/guanyi/mirra/data/MigrationOneToFourTest.kt)、[MigrationThreeToFourTest](../../app/src/androidTest/java/com/guanyi/mirra/data/MigrationThreeToFourTest.kt)；[PRODUCT_SPEC](../PRODUCT_SPEC.md) 的可选 UserStateSnapshot 是旧需求而非现存表，本轮按 P2 正式移出 V1 |
| E9 | [StudyWorkflowRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/StudyWorkflowRepository.kt) 112–130 / 311–393：原快照 B、ACTIVE/PENDING 分流；[ActiveLearningFactGuard](../../app/src/main/java/com/guanyi/mirra/data/repository/ActiveLearningFactGuard.kt)；[AppContainer](../../app/src/main/java/com/guanyi/mirra/di/AppContainer.kt) 237–243：启动顺序；[SessionManager](../../app/src/main/java/com/guanyi/mirra/domain/SessionManager.kt) | [SessionManagerCloseoutTest](../../app/src/test/java/com/guanyi/mirra/domain/SessionManagerCloseoutTest.kt)、[ModuleThreeDCloseoutRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDCloseoutRepositoryTest.kt)；[Closeout Revision](MIRRA_PHASE_3D_1_CLOSEOUT_REVISION.md)、[accepted revision checkpoint](../checkpoints/2026-10-07-phase-3d-1-closeout-revision.md) |
| E10 | [ImageAssetEntity](../../app/src/main/java/com/guanyi/mirra/data/local/entity/ImageAssetEntity.kt)、[ImageRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/ImageRepository.kt) 87–139、[ImageImportPolicy](../../app/src/main/java/com/guanyi/mirra/data/storage/ImageImportPolicy.kt)、[ImageStorageService](../../app/src/main/java/com/guanyi/mirra/data/storage/ImageStorageService.kt)、[NoteRepository](../../app/src/main/java/com/guanyi/mirra/data/repository/NoteRepository.kt)：多图删除补偿 | [ImageStorageServiceTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ImageStorageServiceTest.kt)、[ModuleTwoBImageRepositoryTest](../../app/src/androidTest/java/com/guanyi/mirra/data/ModuleTwoBImageRepositoryTest.kt) |
| E11 | [AppPreferencesRepository](../../app/src/main/java/com/guanyi/mirra/data/preferences/AppPreferencesRepository.kt) 15–74：4 keys、IOException fallback；[AppContainer](../../app/src/main/java/com/guanyi/mirra/di/AppContainer.kt)：mirra_preferences | [AppPreferencesThemeTest](../../app/src/androidTest/java/com/guanyi/mirra/data/preferences/AppPreferencesThemeTest.kt) |
| E12 | [AndroidManifest](../../app/src/main/AndroidManifest.xml) 23：allowBackup=true，没有 fullBackupContent/dataExtractionRules/BackupAgent；[file_paths.xml](../../app/src/main/res/xml/file_paths.xml) 是相机临时文件分享，不是备份规则 | 尚无 system backup transport / restore 验收定义与执行证据；S1 |
| E13 | main/src 全文件及 Backup/Restore/ZIP/CSV/CreateDocument 搜索未发现生产导出链；[SearchIndexRebuilder](../../app/src/main/java/com/guanyi/mirra/data/search/SearchIndexRebuilder.kt) 19–52：轻量 count health 与显式 rebuild 分离 | [ModuleThreeDDataPreservationTest](../../app/src/androidTest/java/com/guanyi/mirra/ModuleThreeDDataPreservationTest.kt)、[PreservationEvidenceRun](../../app/src/androidTest/java/com/guanyi/mirra/PreservationEvidenceRun.kt)：opt-in 升级保留/marker，不是 Full Backup；旧 Phase 3 “格式升级”文字不能证明已有格式 |
| E14 | [CURRENT_STATE](../CURRENT_STATE.md)、[Phase 3D final checkpoint](../checkpoints/2026-10-04-module-3d-final.md)、[fresh AVD gate](../evidence/phase3d-final/fresh-avd-final-gate.md)、[Closeout Revision checkpoint](../checkpoints/2026-10-07-phase-3d-1-closeout-revision.md) | 仅引用历史已提交证据，保留 RED / assumption / ANR / 数据保留限制 / NOT RUN |

## 4. 已覆盖的旧需求与真正剩余 Delta

不再创建“Phase 4 History 基础”“Phase 4 abnormal recovery engine”“另一套 overall/effective speed”。它们已完成。旧 3D 的 PENDING cancel/reopen 已由不可逆 Closeout 取代；旧首击 flush 和旧 cleanup/B 顺序已由 accepted Revision 取代，不能按旧计划再改回来。

旧规格的 Intent→First Action 用时：现存 `convertedAt/startedAt` 是 READY/提交 Session 时刻，不是独立的 First Action 点击 timestamp。因此只能展示 **Intent→Session 用时**，包含准备/握手，不冒称按键反应时间。首次稳定/最终稳定、启动救援等没有完整独立事实时，不用字段相似度强行计算。

本轮用户正式审阅决定调整了原规格中的主观状态相关性范围及系统完整恢复责任：以第 5 节 P2/P4 为准。仅将该决定落入获授权的 Master Plan，不修改 PRODUCT_SPEC / DECISIONS；后续执行不得因未同步的旧表述再次把主观状态采集或系统完整恢复加入 V1。

真正新增的是：跨书的有界事实聚合、历史分页入口、可解释趋势、经审定的轻洞察，以及用户可携带的完整备份/恢复/导出和可靠性验证。Start 仍只管行动，不接入 Dashboard；现有 ReadingRecord 继续作为详情事实源。

## 5. Product Decisions｜正式审阅决定与仍待审事项

以下 `APPROVED` / `DEFERRED` 来自用户本轮正式审阅决定，不表示功能已经实施。其余建议仍为 **PROPOSED / NEEDS PRODUCT DECISION**，必须在所属阶段实施计划审阅时单独冻结；未决项不自动按模型喜好实现。

| ID | 决定与状态 | 正式决定 / 保留提案 | 边界及后续审阅 |
| --- | --- | --- | --- |
| P1 | APPROVED — Start Conversion / 开始耗时 / Stable 边界 | `Start Conversion = CONVERTED / (CONVERTED + ABANDONED + TIMEOUT)`；开放 Intent 不入分母。CONVERTED 且已创建合法 Session 就是 Start 成功，后续 NORMAL/ABNORMAL/PENDING/其他结局不反写成功事实。开始耗时为 `Intent.createdAt → Session.startedAt` | Stable Start 独立，不重写 Start Conversion；损坏关联单列，不把后续异常误作“没开始”。Stable/Recover 的具体展示仍在 4A 详细计划审阅 |
| P2 | DEFERRED BY PRODUCT DECISION / Post-V1 candidate | V1 不新增主观 User State、情绪、疲劳采集、精力打分或环境状态表单，也不实现主观状态相关性：无持久事实、增加启动摩擦、仅为此升级 Schema 不值得 | 不是 DONE；不得从 DND/Coverage/Break/权限推测状态。4B 仅基于现有事实提供描述性 Insights，Phase 4 默认 v4 |
| P3 | PROPOSED — 速度异常仍属于 4B | 同书、同口径、连续 3 次≤独立历史基线70%；基线≥5场/60min，单次不提醒 | 这些数值均未冻结；最终阈值和资格在 4B 实施计划审阅时单独批准；详细边界见第 7 节 |
| P4 | APPROVED — 系统备份产品责任；具体传输范围待审 | Mirra Full Backup / Restore 是唯一承诺完整数据恢复的正式机制；Android System Backup / Device Transfer 仅为系统级补充，4D 定义有限、安全、可验证的策略 | cloud/D2D 具体范围由 4D 审定。不承诺完整恢复、替代 Full Backup 或所有 OEM/transport 可靠；不直接搬运未归一化 DB/图片/设备句柄 |
| P5 | APPROVED — Restore V1 全量替换 | 不做 merge；预览影响、明确确认、恢复前自动生成当前数据安全快照 | 不擅自合并 ID/关系/主线；本轮不恢复或删除数据 |
| P6 | APPROVED — Full Backup 终态前提 | 存在 Active Intent / Active Session / PENDING / 未完成 owned cleanup 时，禁止生成或导入 Full Backup | 不静默跳过事实或由备份按钮偷偷结束学习；屏障内排空后再次核验，无法证明静止就失败 |
| P7 | APPROVED — 首个正式 Full Backup 合同 | `backupFormatVersion = 1`，首个受支持正式 backup schema 为 Room v4；只接受正式 Mirra BackupService 生成并通过完整 metadata/hash/schema 校验的包；缺图/损坏严格拒绝替换 | 不接受裸 `.db`、任意旧安装库或 v1/v2/v3 SQLite。既有 Migration 链不等于 backup compatibility；未来 Room 升级时才另审已有合法格式迁移 |
| P8 | APPROVED — V1 不自研 Backup 加密 | 明示文件包含私人笔记/图片、文件本身非加密；由用户选择保存位置 | 不自行发明密码学，Mirra 不自动上传；以后加密另审需求与可靠库 |
| P9 | PROPOSED — JSON / CSV 字段、范围与风险 App 标识 | JSON 可读业务关系与原始事实；CSV 以 Session/Intent/进度表为主，明示含历史风险配置的范围 | 不是可恢复备份，不混入 token、系统句柄；字段白名单在 4D 先冻结 |
| P10 | APPROVED — Mine 平面信息架构 | 保留平面 7d 摘要，只增加趋势、全局阅读记录、数据管理轻入口 | 不变成 Dashboard、不满屏卡片、不重做三个顶层导航、Start 不变 |
| P11 | APPROVED — 设备运行 ownership 不可迁移 | DND / Overlay / FGS / PendingIntent/token / ruleId / 设备能力 ownership 等不作为跨设备恢复状态；portable 包移除 ruleId/prior filter 等可执行句柄，不把去活化写成已释放 | 联动 P6 阻止非终态 ownership 输入；不得清空句柄后交旧 reconcile 或在新设备复活资源 |

P11 的正式首版约束与 P6 联动：只创建/接收无活动 Intent/Session、无 PENDING、无未完成 owned cleanup 的手动格式；保留历史已终态 lifecycle 与权限-at-start，portable 副本清空设备句柄。RELEASE_PENDING/RELEASE_FAILED 等未完成 ownership 不得携带后调用目标设备 reconcile，也不无声转换为 RELEASED。若未来需要支持，必须单独审定来源隔离与惰性历史状态，不能改冻结 DND ownership。系统自动恢复不天然满足 P6，须按 P4 的有限安全范围另审。

## 6. Trends Architecture｜可测事实与资格契约

### 6.1 薄只读扩展

建议新增 `TrendsRepository`（或扩展现有 ReadingAnalyticsRepository 的全局只读 source）、`TrendsService` 纯函数与 `TrendsProjection`。命名是建议，不要求另建重复分层。复用 `AnalyticsTimeProvider/AnalyticsTimeContext`、`ReadingAnalyticsService`、`SessionTimelineValidator`、`EffectiveReadingService` 和既有 ReadingRecord 详情。

推荐 source 按指标分开：Start 读取 createdAt cohort 内的 resolved Intents，并按 converted Intent ID 批量读取关联 Session 的必要字段，**不按 Session endedAt/endType/closeoutState 过滤**；Reading/Maintain/Recover 读取相应范围的 ended Sessions、批量 Context/Segment/所需 Focus events、独立 Note counts。Start 不可只依赖 ended Sessions，否则会丢掉 Active/PENDING 的合法转换。采用一次性只读事务/明确 generation 做趋势快照；不把多个正在变化的 Flow 拼接成“原子快照”。已结束学习事实不可变是现存批量 Flow 的前提，但 Note count、DND 元数据可变化，导出/备份有更严格要求。

### 6.2 指标与缺失值｜Start 已批准，其余展示在 4A 细化审阅

| 类别 | 可计算事实 / 拟定展示 | 分母及未知边界 |
| --- | --- | --- |
| Start | `Start Conversion = CONVERTED / (CONVERTED + ABANDONED + TIMEOUT)`；开始耗时为 `Intent.createdAt → Session.startedAt` | 按 Intent.createdAt cohort；开放 Intent 不进入分母。CONVERTED 且已建立合法关联 Session 即成功，后续 NORMAL/ABNORMAL/Active/PENDING/其他结束状态不改变该事实、不单列为待转换或排除。损坏/缺失关联单列数据缺失，不伪造成功或失败；开始耗时异常单独 unavailable，不据此重写已合法转换事实 |
| Stable | 完整可信 NORMAL 中已记录 stableStartedAt 的数、占比与 startedAt→stableStartedAt 耗时 | 短于本场阈值、缺 Context、NONE/PARTIAL、损坏/未来记录分别列不足/不可观测。null 只称“未确认”，不称失败；比例只能称记录确认比例，不证明所有 null 都有完整正向证据机会 |
| Maintain | COMPLETE_TRUSTED 场数/覆盖；有效专注总时长、实际 DEEP_FOCUS 时长、DISTRACTION 分段次数/时长 | 使用同一 Validator。可信 0=0，不可用=null；未知样本数可见。DEEP 只统计实际晋升后的持久区间，不回填先前15分钟；Break/Allowance不算失败 |
| Recover | 明确成功/中断次数；成功的 RECOVERY 段历时；未完成/失监/主动换段数量 | primary 分组仅来自 DISTRACTION 的 Recovery；Break/Allowance 后 Recovery 另组。S/(S+I)只称已明确结局比例，不称所有尝试成功率；截断/无结局/丢证据不混成I |
| Reading | 沿用两个7d的正常 Session数/总时长/推进页；独立非空 Note创建数 | 必须经 ReadingAnalyticsService.qualify：NORMAL、非空 endedAt/endPage、endedAt>startedAt、endedAt≤同一snapshot.now。缺Context/旧无段不因此排除overall；ABNORMAL保留历史但排除核心趋势；0页正常样本保留，不+1、不平均逐场speed |

Stable/Recovery 正向连续窗口是 runtime 证据，没有完整历史窗口表。FULL 是 query coverage，不等于持续正向证据，也不等于全程 Focus。不能从 RECOVERY ≥90秒推导成功、从解锁推导分心或从缺少事件推导失败。

Stable Start 是独立观测指标，不加入 Start Conversion 的成功条件，也不以 stableStartedAt 为 null、Session 后来异常或监测资格不足重写 Start 成功。4A 不产生任何能力评分：Start / Maintain / Recover 只能呈现真实事实、样本、耗时、次数、比例与缺失状态；禁止任何 Score（包括 Start / Maintain / Recover Score）、0–100、自律指数、排名及 AI 能力判断。

仓库没有持久化 UNKNOWN Segment；运行时 UNKNOWN 会重置证据，但不能在历史中精确还原。Phase 4 不将 runtime UNKNOWN 另算 Focus，也不擅自从冻结的有效时间公式中反扣无法还原的窗口；只能按原持久 Segment 与 Validator 给出结果，解释其测量范围，不声称测得真实注意力。

Recovery 成功事件的 segmentId 指向**新 FOCUS**；中断事件指向**原 RECOVERY**。聚合时必须同时校验同 Session、相关 Segment、相邻边界、类型与 occurredAt，按 ID 幂等匹配，一段最多一个结局；坏关联记 unknown。成功历时是 RECOVERY startedAt→endedAt，包含重新积累时间，不冒称恰好90秒有效窗口。分心次数沿用 DISTRACTION 分段数，不偷换成 raw event 数。

### 6.3 时间窗与聚合

- 基础摘要继续当前约定：本地 today-6..today（today只截至now）对比 today-13..today-7；不是14×24小时。两个7d不重叠，今日未完明确标记，不静默改成仅完整日。
- Session 汇总按 endedAt 所在本地自然日；Intent 趋势按 createdAt cohort，标题不得混成同一事件数。分段时长首版跟随所属 Session cohort，不声称午夜前后逐日专注；如要按天切片须另定义分配规则。
- 30/90天及全部为趋势详情范围，不改速度预测7→14→30。时间上下界、now、ZoneId来自同一snapshot；跨午夜、DST 23/25小时、时区切换按自然日重新投影，不修改数据库UTC事实。
- 比较先显示原始绝对差/样本量。前期为0不算增长百分比；分母0时 unavailable，不输出 NaN/Infinity；比例差用百分点，Start 依 P1，其余比例按所属指标资格合同，不叫自律能力增长。
- ABNORMAL 不进入 Reading/Maintain/Recover 核心统计，Active/PENDING 不进入已结束 Session 统计；**这些排除条件不适用于 Start Conversion**，它们对应的合法 CONVERTED Intent 仍为开始成功。损坏/未来记录按各指标资格排除并计数，不篡改历史；无法计算合法开始耗时不抹去已建立的转换事实。有效聚合、整体阅读聚合分别标明资格，不因样本变换误解释提高。

### 6.4 DAO / 性能 / 导航

- 新全局历史使用 `(endedAt DESC, id DESC)` keyset，建议pageSize50；仅列表投影。按需进入既有 SessionSearchDetailRoute/ReadingRecord，不新增第二套详情或恢复 Session。
- 当前单书 History 无分页；已有10k测试证明完整顺序，不证明性能/常量内存。全局页面禁止 listAll() 常驻全部实体或逐条调用 ReadingRecordRepository。
- 现有 effective source 的 Context/Segment IN 分批≤800；空源1查询，非空 `1+2*ceil(N/800)`，不是恒定3。趋势若额外每批查event，明确预算增加为 `1+3*ceil(N/800)`，另加固定 Intent/Note等查询；Start 关联 Session 的字段查询也按 converted IDs 分批≤800并单列查询预算，禁止每 Intent 单查。用 QueryCallback验证，不仅肉眼无N+1。
- 有限日期范围仍可能有巨量数据；详情列表分页，长范围聚合分批归约，不保存全部时间线。全集统计不能用逐页 UI 拼接造成遗漏/双计；使用一致读快照与游标。
- current schema 的 Session 无 endedAt 游标索引，Note无createdAt索引。先 EXPLAIN QUERY PLAN + 分布/数据量基准，必要时独立申请索引 Migration；不因规划提前升级。
- Mine 保持平面、少量事实与轻入口，详情按需展示。使用现有 MirraTheme / Token / Components，普通内容无新增软拟态统计卡片；不重做 Start/Knowledge/导航。

## 7. Insights｜条件式阶段而非假精确度

速度异常是新描述性提醒，不覆盖 Phase 2 prediction、3D effective windows 或 robustCV。

P3 **PROPOSED、尚未冻结**：同一本书、同一速度口径，历史基线至少5场/60分钟；基线不包含最近候选连续段。连续3场≤基线70%才提供轻提醒，单场不判断。这些候选数值不能作为 Master Plan 已批准阈值；最终门槛及单场最短时长、基线跨度、0页处理、换书/暂停/大跨度重置规则在 **4B 实施计划独立审阅时** 冻结。不沿用既有3场/30min作为异常规则。

优先 effective 同口径样本；如果决定支持 overall，必须作为独立提示来源，绝不能把整体基线和有效候选比较，或因监测失效自动切换导致假异常。不跨书合并页/小时做能力排名。零页正时长保留原统计分母，但全零/基线0不输出比例提醒；要称“近期推进较少”仍需产品审定。

P2 已正式决定 **DEFERRED BY PRODUCT DECISION / Post-V1 candidate**：V1 没有主观状态事实，不新增情绪/疲劳/环境表单、持久化或相关性分析，也不从权限、DND、Coverage、Break 猜测。4B 仅保留基于现有真实事实的轻量描述性 Insights；若将来重启主观状态研究，须另立 Post-V1 模块审定产品价值、采集与数据模型，本计划不为它预设 V1 表、迁移或分组门槛。

不实现 score/ranking/0–100、自律指数、AI状态/能力判定、因果结论、虚假置信百分比或自适应阈值。P2 是已批准的 V1 范围调整，不是 DONE；P3 数值仍待 4B 审阅，两者不能混写成 Insights 已实施。

## 8. 数据模型影响与 Migration 风险

| 新能力 | 最小数据变化 | 是否需要 v5 |
| --- | --- | --- |
| 原始趋势、全局历史、窗口比较 | 新 projection/只读DAO/纯函数，复用 v4 事实 | 不必；实际性能必要索引是另审变更 |
| 同书速度异常 | 纯派生规则，首版不持久通知次数/统计缓存 | 不必；若要求跨进程dismiss才另审 |
| Full Backup/Restore | ZIP metadata、staging、journal、业务数据快照接口 | 不必；backupFormatVersion ≠ Room schemaVersion |
| JSON/CSV | DTO/stream writer 与字段白名单 | 不必 |
| 主观用户状态采集/相关性 | DEFERRED BY PRODUCT DECISION / Post-V1 candidate，V1 不新增数据结构 | 不在本 Phase 4 升级；未来若重新授权，独立审定数据模型与 Migration |

结论：经 P2 正式范围调整，**Phase 4 默认继续 Room v4，不创建 Schema v5**。趋势、基于现有事实的 Insights、首版 Full Backup/Restore 和 Export 不要求新增业务表。若以后性能测量证明必须增加索引或另获新的数据模型授权，须单独申请 Schema/Migration 审阅，不属于本轮或默认 4A 实施范围；保留真实 v1–v4 安装迁移链、旧 Schema 与旧事实。安装 Migration 不赋予首版 Backup 导入旧库能力；将来 Room 升级才另审已有正式合法备份包兼容。禁止空表、destructive fallback 或为备份增加派生指标列。

原UserStateSnapshot/FocusRule文字不等于当前Entity：规则阈值已经在SessionFocusContext，风险配置在risk_apps，历史名称在session_risk_app_snapshots；不要创建重复FocusRule表。

## 9. 三种数据能力严格分离

| 能力 | 用户目的 | 目标范围 | 不能替代什么 |
| --- | --- | --- | --- |
| Android System Backup / Device Transfer | 系统级补充；传输和实际可用性由系统控制 | 4D 审定的明确、有限、安全且可验证内容 | 不承诺完整恢复全部 Mirra 数据、不替代 Full Backup、不保证所有 OEM/transport 可靠、不直接搬未归一化 DB/图片/运行句柄 |
| Mirra Full Backup / Restore | Mirra 唯一承诺完整数据恢复的正式机制；用户主动获得可恢复文件 | 正式 format v1 / Room v4 包：校验 ZIP + 数据库 + 偏好 + 全部引用媒体，移除设备运行 ownership | 不等于覆盖安装/测试marker；不自动同步；只有完整验证通过的正式包才可恢复 |
| JSON / CSV Export | 查看、分析、迁移/带走可读数据 | 公开字段和关系，不含图片二进制 | 不作为Restore输入，不承担完整恢复职责 |

全部核心路径离线可执行，无账号、服务器、Supabase、云数据库或VPN。SAF允许用户选择本地或其他provider；选择云provider是用户文件去向，不是Mirra增加云依赖，离线gate必须选本地目录。[Android SAF官方说明](https://developer.android.com/guide/topics/providers/document-provider)（S2）。

## 10. Full Backup Architecture｜一致快照而非复制几个文件

### 10.1 推荐格式与接口

建议独立小边界：`BackupService`协调维护与文件输出，`BackupSnapshotRepository`取得正式事实，`BackupArchiveCodec`流式ZIP，`BackupValidator`纯规则+staging验证，`RestoreCoordinator`发布/回滚。复用已存在DAO/Storage/Search，不另建并行业务Repository，不引入云SDK。

首个正式格式已批准：`backupFormatVersion = 1`，受支持的 backup `schemaVersion = 4`。只接受 Mirra 正式 BackupService 生成且完整通过 metadata/hash/schema/引用媒体校验的包，不接受裸 `.db`、任意旧安装数据库或 v1/v2/v3 SQLite。Mirra 此前没有正式 Full Backup 格式，现有 Migration 1→2→3→4 只是安装升级能力，不是旧备份兼容性。未来 Room 升级时，再由未来版本明确支持已有合法正式备份格式的迁移。

包结构建议如下（详细字段合同在 4C 计划审阅）：

```text
mirra-backup-<date>.zip
├── metadata.json
├── database.sqlite
├── preferences.json
└── images/<uuid>.jpg
```

metadata：appId、appVersion/code、backupFormatVersion、schemaVersion、schemaIdentity、createdAt UTC、文件字节/hash清单、每表行数、快照策略版本。metadata自身不自引用hash；其余entry与清单一一匹配。SHA-256是损坏检测，不是来源认证/加密。

数据库覆盖12张正式业务表和关系，包括LearningItem/Intent/Session/Note/ImageAsset/Topic/cross-ref/risk_apps/context/risk snapshots/segments/events。staged Room保持合法v4结构和FTS shadow结构，但不将旧search_fts内容作为可信输入，恢复显式重建。历史数据不得仅因ABNORMAL/无Focus被过滤掉。

偏好读取4项已知key，值白名单；不导出DataStore内部文件和任意未知key。导出失败不写“成功”，不修改来源数据。默认不包含temp/camera/trash/orphan/cache、日志/截图/测试marker、内部安全备份、journal/staging，防止递归备份。

### 10.2 快照一致性

1. 按已批准 P6 检查无 Active Intent / Active Session / PENDING / 未完成 owned cleanup；任一存在就禁止生成或导入 Full Backup，指引用户通过现有流程完成。不由备份按钮偷偷结束、结算或丢掉 Intent，也不跳过活动事实。
2. UI flush 可观察Note/Caption草稿；失败停止。取得单一维护屏障，阻止新Session/Intent、autosave、图片导入/删除/reconcile、DataStore写入与runtime callback，等待在途写完成。屏障内排空后再次核验无Active Intent/Session、无PENDING与待处理owned资源，防止屏障前已启动操作随后提交；不满足P6就退出，不偷偷结算。staging validator也执行同一终态政策。只检查Active Session或Room读事务都不够。
3. 同一Room读事务按固定表序/有界游标捕获正式行；在屏障内严格读取DataStore并pin/copy所有引用图片。当前偏好Flow的IOException→默认值不能用于backup，严格读取失败就报错。
4. 建议把事实写入**独立staged Room v4**，清空派生FTS后checkpoint并关闭staged连接，验证单文件能独立打开。WAL里的已提交行必须进入snapshot。
5. 来源快照完成后释放屏障；压缩与SAF输出在IO/流式执行。不顺序复制运行中的mirra.db/-wal/-shm，不假设所有minSdk平台支持VACUUM INTO。
6. 最终包二次核验后才报告已生成；SAF取消/ENOSPC/半写入只作为失败或不完整文件提示，不承诺第三方provider rename原子。任何删除仅针对本次创建的确定临时输出。

图片在Room事务前move/import的现状、DataStore独立存储与startup orphan清理都必须纳入屏障。屏障是数据维护接线，不改变冻结业务规则；若需要改DND/Monitoring核心语义或Schema，先停止审查，不随备份实现扩大范围。

## 11. Restore Architecture｜验证、补偿与设备边界

### 11.1 输入不可信，替换前验证

- ZIP不可信：限制entry数量、单个/总解压字节、压缩比、路径长度、JSON深度/行数；拒绝absolute path、`..`、反斜杠绕过、重复路径、symlink、未知entry。逐entry canonical containment，不能依赖文件扩展名。
- hash/size实际读取核对；空间预检覆盖incoming+old safety snapshot+staging+余量，不足在发布前失败。
- appId/format/schema版本严格检查；首版仅正式 `backupFormatVersion = 1` / Room v4 完整包。拒绝裸 `.db`、任意旧安装 DB、Room v1/v2/v3 包和未来不支持版本，无 downgrade；不在首版 staging 调用安装 Migration 链“兼容”这些输入。未来升级另行支持已有合法正式格式时，才审定 staging migration gate。
- SQLite integrity/foreign_key检查；真实schema/identity/columns/indexes与批准版本比对，拒绝未知trigger/view/业务表（Room/FTS系统结构单独白名单）。检查枚举、唯一槽、页码、时间、ID关系、引用完整性。
- 每个ImageAsset都有合法UUID路径、真实JPEG、hash/字节/尺寸一致；缺图或损坏按P7严格失败。包内未引用文件拒绝/明确报告，不自动替代为猜测图片。
- 不要求所有历史Timeline都COMPLETE_TRUSTED：旧无段、ABNORMAL、NONE/PARTIAL是合法历史。不得“验证”时补造Focus/修复旧事实；只拒绝结构损坏或宣称不受支持的包。

### 11.2 发布与rollback

`VALIDATING → READY → CURRENT_SAFETY_SNAPSHOT → PREPARED → PUBLISHING → VERIFIED → COMMITTED`，失败走`ROLLING_BACK`。这是文件journal，不是Room新表。每个切换点均需失败注入和下一启动恢复测试。

Restore 入口先按 P6 证明当前终态；发现未完成 owned cleanup 即拒绝导入，交现有资源处理流程完成后由用户重试，不由 Restore 自动清理后继续。清理不确定不能丢失当前原句柄；终态检查通过、完成当前安全快照并确认后才允许替换。incoming 来源系统句柄不执行，不 apply DND、启动 FGS、激活 Overlay 或恢复旧 token；权限从当前设备重新检查。

portable 副本去除 dndRuleId/priorDndInterruptionFilter 等设备可执行句柄，保留历史规则和 capability-at-start；DND / Overlay / FGS / token / ruleId 等运行 ownership 均不作为跨设备恢复状态。依 P6/P11 阻止非终态 ownership 导入，不能简单清空后调用旧 DND reconcile，也不写“已在旧设备释放成功”。风险 App 配置/历史 label 保留，当前包是否可用另行检测；不扫描猜历史名称。

推荐在**正常AppContainer打开前**解决持久restore journal，明确旧Room连接、订阅/Repository代际、DataStore写入生命周期。当前DI没有热切换基础，不能在UI内覆盖已打开的mirra.db。可采用受控重建/下一次用户冷启动发布；具体机制须在4C详细计划审定，不能通过偷偷杀进程或多份DataStore实例规避。

Restore的维护屏障必须覆盖当前安全快照→发布→验证→COMMITTED或ROLLING_BACK完成的整个区间，不能套用导出“快照后释放屏障”的规则。journal记录当前存储代际/快照身份；若延期到下一次冷启动，必须在实际发布前重新核验终态、重取最新当前安全快照并更新journal，不能用过时回滚副本覆盖等待期间新写的Note/图片/偏好。

DB/图片/偏好无法跨资源真正原子commit：保留旧资源，journal标记已发布步骤，发布偏好用一次受控DataStore edit（不是4个setter或直接换正在运行的preferences文件）。只有新库/图片/偏好/FTS验后成功才commit；任何一步失败能恢复旧三者。journal置noBackup区并在startup的Session/image/FTS reconcile之前处理，防止清理器删除rollback资源。

回滚证据分别定义：未改写、完整保留的旧数据库/图片文件检查字节hash；DataStore通过受控edit恢复原全部key/value集合，检查语义一致，不仅4项已知key。一次edit本身不保证protobuf文件字节完全一致；如要求其原文件hash相同，4C须另行审定停止DataStore并保留/恢复原文件的机制，不可仅凭值相等宣称字节一致。

恢复后显式`SearchIndexRebuilder.rebuild()`；不只调用行数相等的ensureConsistent。新事实可读、搜索正确、媒体可开后，再清理仅属于本次恢复的staging/旧安全副本，保留期限需明确。

### 11.3 ACTIVE / PENDING 与系统恢复

首版手动 format 按 P6 阻止含 Active Intent / Active Session / PENDING / 未完成 owned cleanup 的生成与导入，不遗漏数据而是明确拒绝。4D 不直接搬运未归一化整库/图片/设备句柄，不默认支持活动整库恢复。若未来另行授权活动快照或安全归一化的系统恢复内容，必须先审定以下边界，而不是首版已支持的能力：

- PENDING使用原closeoutStartedAt/requestedEndPage正常B，不能变ABNORMAL。
- 未PENDING的ACTIVE不得自动继续FGS，不得把几天后的restore时间加为阅读；恢复边界与缺口来源必须明确，不能直接把旧backup丢给现有当前时刻recovery。
- 旧系统DND句柄不能在新设备reconcile，恢复后的pref仅影响下一次用户显式开始。
- 只增加恢复入口适配，不重写Closeout/ABNORMAL引擎；若冻结核心或Schema不足以安全表达，必须另行授权。

## 12. Export 与 Android Backup 建议

### 12.1 JSON / CSV

复用一致只读snapshot/维护设施；不是Backup入参。JSON公开格式version、UTC时间与关系ID，保留null/0、endType、trust来源、非空Note、Topic关系和Image metadata；实际图片只在Full Backup。用户文本、标题、风险label为隐私数据，不自动上传。

CSV建议分Session、Intent、Learning Item进度表，原始总时长与可用有效时长分列（unavailable空单元而不是0），明确毫秒/分钟/页单位与当地显示日期。proper quoting处理逗号/引号/换行/Unicode，用户可控文本按明确的spreadsheet-safe策略防`= + - @`等公式注入，不把正常数值列当文本；JSON保留原文不为CSV安全改数据库。

允许ABNORMAL历史导出但标注统计资格；不平均逐Session速度，不伪造state snapshot。流式写出、可取消、provider失败保留来源数据；CSV/JSON不是完整恢复、不支持从其恢复图片或未知字段。

### 12.2 Android System Backup

事实是allowBackup=true且没有显式规则，不能只补一句文案就标DONE。Android Auto Backup有25MB云备份配额，可能无法覆盖全部图片；API31+ target使用data-extraction-rules，同时旧系统仍需fullBackupContent。cloud与device-transfer分别配置；部分OEM不因allowBackup=false就禁用D2D，不能承诺该开关是全平台绝对阻断。[Android Auto Backup官方说明](https://developer.android.com/identity/data/autobackup)（S1）。

P4 已冻结产品责任：**Mirra Full Backup / Restore 是唯一承诺完整数据恢复的正式机制**；Android System Backup / Device Transfer 只是有限补充，不承诺完整恢复、替代 Full Backup 或所有 OEM/transport 可靠。4D 仍需独立选择 cloud/D2D 的具体安全内容：低风险配置、经过审定的归一化快照，或排除业务内容。不得直接传输未经归一化 DB/图片/设备句柄。本轮仅在 Master Plan 记录这一正式决定；PRODUCT_SPEC / DECISIONS 的后续同步须另获授权，不把旧宽泛表述视为系统完整恢复承诺。

XML不能只排除数据库某几个DND列；单份DataStore也不能通过目录规则只导主题不导保护开关。要安全传输经过归一化的snapshot，必须使用审定的独立快照/必要BackupAgent与restore入口；否则排除不安全整文件。不得因怕设计复杂就默认整库云备份。BackupAgent受限运行环境不可依赖正常AppContainer/监测Service，需独立测试。

规则排除cache、temp/camera/trash、journal/staging、内部安全快照、测试marker和设备运行句柄；最终system restore复用第11节的预启动安全边界。实际transport不支持/无账号/没有目标系统时记录NOT RUN，不购买设备凑矩阵，不以手动ZIP成功代替系统backup PASS。

## 13. 推荐五个阶段｜独立授权、独立验收

保留已获认可的五阶段结构。**第一步仍为 4A；P2 已正式移出 V1，4B 的 P3 最终阈值待该包审阅，不应阻塞数据安全。**如洞察决定尚未完成，可另获授权先执行 4C→4D，最后 4B→4E；这不是自动跳包授权。

### 4A｜Trends Foundation

- Goal：在已有摘要和ReadingRecord之上增加可信、有限的全局事实趋势。
- Scope：继承已批准 P1/P10，细化 Stable/Maintain/Recover 展示与资格；Trends source/纯规则/缺失值 projection；7d 比较、30/90/全部有界查询；全局 History 分页复用详情；Mine 平面轻入口。
- Non-goals：主观状态采集/相关性、异常提示、备份、改预测/Validator、Start Dashboard、新路由体系、统计表/空表；任何 Score（含 Start/Maintain/Recover Score）/ 0–100 / 自律指数 / 排名 / AI 能力判断。
- 拟新增/修改：`domain/TrendsModels.kt`、`TrendsService.kt`、`data/repository/TrendsRepository.kt`或现有repo扩展；IntentDao/SessionDao/FocusDao只读查询；`feature/profile`薄UI/navigation接线；对应JVM/Room/Compose测试。均为未来建议，不是现存接口。
- Gate：C/(C+A+T)、开放 Intent 不入分母、CONVERTED 关联 Active/PENDING/ABNORMAL/其他状态仍为 Start 成功、坏关联显式缺失、开始耗时与 Stable 独立；Reading/Maintain/Recover 另验已结束 NORMAL 资格；stable null≠失败；成功/中断 event 不同关联与 censored；FULL 坏时间线/null/可信0；Deep 不回填；两个7d/DST/午夜/时区/now；0分母；800/801/1601 查询预算及 Start 批量关联预算；keyset 同 timestamp 无漏重；10k/更大受控数据的 EXPLAIN/耗时/内存；Mirra 平面 UI/320–411dp/font2/48dp、无综合评分。
- 交付：事实/分母矩阵、只读接口预算、截图与测试证据、checkpoint；需要索引迁移先停，不自动v5。

### 4B｜Descriptive Insights

- Goal：有限样本的描述性变化，不对用户做能力评分。
- Scope：仅基于现有真实事实的描述性 Insights，保留同书连续速度异常；P3 的最终阈值与资格在本包实施计划审阅时单独冻结。
- Non-goals：主观状态采集/相关性（P2 已移出 V1）、AI/因果、跨书速度排名、自适应监控、改现存 Session 事实、回填历史状态、自动扩大 Schema。
- 拟新增/修改：`domain/ReadingInsightService.kt` 与 projection、书籍/趋势详情轻文案；不增加 UserStateSnapshot、状态表单或关联 Entity，默认保持 Room v4。
- Gate：独立 baseline、单次不提醒、混口径/换书隔离、0页/基线0、获批阈值边界、样本不足 null、future/ABNORMAL 排除、同一提醒去重政策；不猜主观状态、不跨书作能力评价。不修改 Phase2/effective compatibility fixtures。
- 交付：单独批准的速度异常阈值合同、描述性样本说明与证据；P2 继续 DEFERRED，不标为已实现。

### 4C｜Full Backup & Restore

- Goal：可恢复全部正式事实与引用图片，失败保住当前数据。
- Scope：继承已批准 P5–P8/P11；维护屏障、严格偏好 snapshot、正式 format v1 / Room v4 codec、staging 验证、全量替换、journal/rollback、FTS 重建、平面数据管理轻入口。
- Non-goals：账号/云同步、ID合并、导入任意SQLite、恢复设备权限或监测运行时、改DND/Closeout语义、加备份表。
- 拟新增/修改：`domain/backup`协调/模型/validator、`data/backup`snapshot+archive+journal；既有Repository的维护接线；AppContainer之前bootstrap/rebind边界；App-owned storage与DataStore严格接口；Profile数据管理UI。启动适配必须保留冻结恢复顺序。
- Gate：正式 format v1 / Room v4 的12表+图片+偏好 round-trip；WAL 已提交行；来源写入/图片 move 并发；严格 DataStore 读取失败；屏障内排空后重核 Active Intent / Session / PENDING / 未完成 owned cleanup 全部不存在；裸 DB/旧 v1-v3 安装库/不支持格式及未来版本明确拒绝（不以安装 Migration 兼容）；恶意ZIP/SQLite/缺图/hash/磁盘不足；每 journal 切换点故障/进程死亡/旧 DB 图片字节保留与全部原偏好值回滚；延期发布不丢期间新增数据；无 FGS/DND/Overlay/token 旧 ownership 复活；中文 Note/Caption/Topic/FTS 实查；取消/重复恢复幂等；无图片库/大图片库/离线 SAF。失败输入不得触碰当前正式数据。
- 交付：format contract、恢复失败矩阵、当前数据安全备份策略、Schema/hash证明。无法保证跨资源rollback或owned句柄隔离就停止审查，不靠删库安装修复。

### 4D｜Export & Android Backup

- Goal：用户可携带可读数据；系统备份作为有限补充，其范围安全、可解释、可验证，不替代 Full Backup。
- Scope：继承 P4 的正式产品责任，审定具体 cloud/D2D 范围及 P9 字段；JSON/CSV 流式 export、SAF；显式新旧 backup rules 与确有必要的安全 restore adapter，继承 4C 格式/维护设施。
- Non-goals：Export作为Restore、后台上传、Drive SDK、跨设备自动同步、新权限/账号、无限范围原始文件分享。
- 拟新增/修改：`domain/export`DTO/contract、`data/export`writers、数据管理入口；Manifest备份属性、`res/xml`新旧规则、确有必要的BackupAgent/restore接线。4D允许范围必须在其实施授权中明确，本轮不改Manifest。
- Gate：CSV公式/escaping/Unicode/大文件/取消/provider失败；JSON关系与null/0；文件不含系统句柄/token/日志；系统 cloud/D2D 具体有限内容、quota、未归一化 DB/图片/设备运行 ownership 的排除或已审定安全快照、规则 API 差异、实际 transport 恢复。不默认接入活动整库恢复；未运行 transport 为 NOT RUN，不冒称完整恢复或所有 OEM PASS。
- 交付：公开export格式、系统备份有限承诺、实际transport/平台证据表。

### 4E｜V1 Reliability & Final Validation

- Goal：整合功能/数据安全最终gate，区分开发基线与发布兼容性。
- Scope：复验现有ABNORMAL/PENDING恢复、升级保留、Backup/Restore/Export、趋势查询性能和故障路径；实际用户链路、权限降级、离线、长历史/媒体；已确认Bug才窄修。
- Non-goals：重建recovery引擎、随意升级依赖、Accessibility/VPN/后台自启、买全OEM设备、隐去历史失败、Phase5/V2/V3。
- 拟修改：验证协议/checkpoint/脱敏evidence；发现Bug先定位影响/授权最小修补，不能借总回归重开冻结核心。
- Gate：第14节全部适用门槛；真实设备、环境不稳定与业务FAIL分别记录；不通过扩大timeout/删除断言/拼局部PASS完成full-suite。
- 交付：V1 acceptance packet、数据恢复证据、已知限制、实际APK及hash（须另获交付授权）、最终文档；等待用户独立验收，不能自封全平台release-ready。

## 14. V1 Final Gate｜完成标准而非本轮测试结果

1. 范围闭环：4A–4E逐包验收，所有需要产品决定的目标已实施或**经用户明确调整范围**；History/速度/异常恢复不重复实现。
2. 全量JVM、稳定专用AVD完整connected（Room/Migration/Compose）、lintDebug、assembleDebug；输出discovered/executed/pass/fail/error/assumption/opt-in。权限平台用例实际断言与4个opt-in专项分开，不将BUILD SUCCESSFUL当业务全绿。
3. Phase 4 默认 Room v4，旧 Schema 1–4 文件不改、v4 hash 同基线；首版只验证正式 format v1 / Room v4 Backup，不接受裸 DB / v1-v3 安装库。未来若另获 Schema 升级授权，再定义真实非破坏安装 Migration 与已有正式合法 Backup 的兼容 gate，两者不可混同。
4. Backup可离线导出→严格验证→正常restore；失败/恶意/空间不足/取消/crash rollback后当前DB/图片/偏好完整；WAL、FTS内容重建、引用一致实测，不以文件数量相等替代hash与关系。
5. 覆盖安装保留真实DB、DataStore、图片、风险配置和Note/Topic/Search；使用保留安装参数。测试sandbox与真installed-data fixture分离，不wipe/clear/uninstall；记录hash验证边界，不能证明已被框架移除的旧数据。
6. 单一会话链路：Intent→READY或NONE→阅读/Note/图片→Break/Allowance/Distraction/Recovery→finalNote保存→唯一边界A→内存失效→锁外owned cleanup→B→ReadingRecord/History/Search→趋势；PENDING retry原边界、旧动作不复活、cleanup失败不撤销结束事实。
7. 强停/Task Manager Stop/reboot与权限撤销在可执行目标上验收；无后台FGS复活；UNKNOWN/UNMONITORED保守，FULL→PARTIAL不可恢复。DND只管理owned rule、不改用户global policy。
8. 有界查询、无N+1、长历史滚动/聚合与媒体库基准；定义设备/数据规模/测量方案后再设预算，不凭“本地快”声称性能PASS。优化只基于证据；FTS现有全量listAll重建是潜在大数据瓶颈，不无关重构。
9. UI继承Mirra Blue/新品牌，Start仍行动页；320/360/411dp、fontScale1/2、关键48dp、Back/系统picker/大文件取消；TalkBack须实际执行或保留未完成发布gate。
10. 环境分层：功能与个人试用基线可以独立冻结，但API23–36/OEM/实体完整矩阵/TalkBack/release-Play/真实断电/人工系统调时未执行仍NOT RUN。发布级门槛未验证则不能称商业发布全设备PASS。一加13T日常反馈只写daily-use smoke，不升级compatibility PASS。
11. 每包Git仅授权分支、独立commit/checkpoint、无敏感数据/完整logcat/serial/私人Note/备份ZIP入Git；Push/发布/merge需该阶段授权；local/remote SHA与工作区实读。

## 15. 已知风险与证据限制

- 最新accepted Revision证据为JVM327/327；connected294 discovered、285实际PASS、9 unmet assumptions、0实际业务断言失败；不得写294/294。lint/build PASS来自历史执行。本轮无新增运行数字。
- 人工AVD smoke因Launcher/SystemUI ANR为DEGRADED；首轮connected框架移除目标包导致旧数据保留不可证明；后续17文件hash只证明那次cover-install当前数据。历史环境与RED不删除。
- Recovery >120秒异常仍Observed once / targeted diagnostic未复现 / root cause unresolved / no production fix；后续90秒成功不等于根因解决。
- FULL与milestone不同、runtime正向证据不全持久化，成功率不能凭null/Segment长度推断。未来规则版本差异要用本场Context阈值/样本范围解释，不反写旧阈值。
- 全局跨书页速不可简单比较；样本构成/监测资格变化可能产生伪趋势。Insight不借完成日期confidence制造假精确。
- 系统默认备份可能包含未经归一化的业务/设备状态；这是4D待治理项，不是本轮已复现数据事故，也不自动授权改Manifest。
- Room+媒体+DataStore无跨资源ACID；restore journal、启动前处理、写入屏障和rollback必须真实证明。现有image trash处理只是局部补偿，不能直接当restore原子性。
- 缺索引、全量History/FTS重建潜在扩展风险尚无本轮benchmark；先measure再决定Migration或优化。
- 备份/导出含私人数据，hash非认证/加密；明示保存责任，不提交实际个人文件。系统transport与SAF provider差异必须独立记录。

## 16. 推荐第一个实施阶段与停止边界

推荐**4A Trends Foundation**，Master Plan 已完成 Planning Freeze，P1/P10 已由用户批准；下一步仍须单独审定/授权 4A 的只读 source、纯聚合、指标资格合同与平面 UI。理由：事实基础已具备，不依赖主观状态采集或 Schema 升级，能立即复用 7d 摘要与 ReadingRecord，并把未知/样本/查询预算先统一。

本轮已解决 P1/P2/P5–P8/P10/P11 的产品决定，P4 的产品责任也已明确；没有这些事项造成的遗留 4A 阻塞。P3 最终阈值只影响 4B，P4 具体传输范围与 P9 字段只影响 4D，不阻塞 4A。4A 详细指标展示/查询/测试合同仍需单独计划与授权，不能把“无遗留阻塞”当成现在可以实施。

4A 完成与独立验收后再单独授权下一包；Backup/Restore 是 V1 核心，不能被未决洞察长期阻塞。P3 未定时可另获授权先审 4C 数据安全详细合同。**本次 Planning Freeze 只授权两份规划文档的 Commit/Push，不授权 4A、任何 Migration、设备操作、安装或发布，也不继承为未来实施提交授权。**

本轮允许文件只有本 Master Plan 与 CURRENT_STATE planning 状态。没有更新 PRODUCT_SPEC/DECISIONS/旧 checkpoint，没有把建议阈值记成冻结算法。按当前明确授权创建独立 `docs(phase4): freeze master plan` 文档提交并 Push 当前分支；完成后停止，4A 尚未开始。

## 17. 官方平台参考与本轮规划检查

- S1：[Android Auto Backup](https://developer.android.com/identity/data/autobackup)，原 Delta Audit 读取；支持quota、默认包含范围、新旧rules、cloud/D2D分离、restricted BackupAgent及OEM边界。官方事实不等于Mirra已经实现。
- S2：[Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)，原 Delta Audit 读取；用户选择document provider、本地和云provider分离；SAF不是Mirra内建云同步。
- 已完成本轮只读核对：本地/远程基线、Room v4注册及Schema hash、冻结实现/旧计划差异、全部矩阵行证据与阶段依赖。
- 文档写完后执行diff检查、限定文件检查、链接/敏感内容检查；不重新Gradle，不制造本轮业务PASS。
- 本轮修订已按用户正式审阅决定同步 Product Decisions、Capability Matrix、指标资格、Backup 兼容/责任与五阶段说明；检查范围仅限规划文档，未重跑 Gradle / AVD。
- 正式状态：`PHASE_4_PLAN_FROZEN`，仅 Planning Freeze；历史证据边界与未测项原样保留，本轮不产生新测试 PASS 数字。完成文档提交/Push 与 local/remote SHA 核对后停止，等待 4A 单独实施授权，不进入 4A。
