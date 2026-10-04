# Current State

更新日期：2026-10-04

## Current Stage

Phase 2、Module 3A 与 Mirra Blue / Visual Parity 已冻结。Module 3B Task 1–5 与限定 DND 修补已通过验收；按用户批准的 3C master plan，3B 可作为个人试用开发基线继续 3C，但不等于发布级设备能力验收完成。一加 13T 日常试用反馈不替代完整兼容性验收。Module 3C-1 行为内核、采样竞态 Acceptance Patch 与 Module 3C-2 应用内体验保持正式冻结。3C-1 原始基线为 `61ed78f94125e7049c5d4ca73be1b16c5416043b`；3C-2 与 Acceptance Patch 的统一冻结实现提交为 `f275334e233298b872bb507a82dcd875ff3135d5`。Module 3C-3 已通过用户独立源码、测试与已提交证据审查，正式冻结实现为 `b9843eb5c3879899e148792a6a1f252e4b6d573d`。Module 3C-4 已完成最终联调、一个页码输入小修、完整回归与 Debug APK 个人试用交付，等待用户独立最终验收。API 23–36、OEM、实体设备、TalkBack 与发行环境未测项继续 `NOT RUN`，API 37 AVD 结果不得外推为其他平台 PASS。Closeout、有效指标与 Phase 3D 尚未开始。

## Verified Completed

- GitHub 仓库已创建，远程 main 原始状态仅包含 README。
- V1 产品定位、功能边界、技术架构、数据模型、阶段计划与验收原则已保存到 docs/PRODUCT_SPEC.md。
- 稳定项目身份与边界已提炼到 PROJECT.md。
- 关键产品与技术决策已记录到 docs/DECISIONS.md。
- 仓库协作与上下文恢复规则已记录到 AGENTS.md。
- 单 Android App Module 已建立，applicationId 为 `com.guanyi.mirra`，展示名为“观已Mirra”。
- Kotlin、Jetpack Compose、Material 3、Navigation 3、Room 2.8.x、DataStore 与 Coroutines / Flow 依赖已配置。
- 已建立手动 AppContainer、DataStore 偏好 Repository，以及“开始｜知识｜我的”三栏导航骨架。
- JVM、Instrumented 与 Compose UI 测试基础已在 API 37 模拟器通过。
- Debug APK 已生成、安装并完成冷启动；最近一次选择的顶层页面可在进程重启后恢复。
- 当前正式工作目录已迁移到纯英文路径 `C:\Users\CDD\Documents\ChatGPT\Mirra`；移除 `android.overridePathCheck` 后，完整 Module 0 验证仍通过。
- `codex/project-foundation` 分支的 Phase 0 提交已推送到 GitHub。
- 已实现创建 Learning Item、设置唯一主线、Intent、启动准备、Session 计时与页码、四类独立 Note 自动保存、Session 结束、规则式总结和下次继续。
- Room v1 已建立 `learning_items`、`study_intents`、`study_sessions`、`notes` 四张表及 Schema Export；唯一槽位、外键、索引和事务共同保护核心状态。
- 新进程启动时会把遗留 Active Session 标记为 `ABNORMAL`，不推进正式阅读进度；真实强停与冷启动恢复已在 API 37 模拟器验证。
- Active Intent 可在启动准备页显式放弃；放弃事务写入 `ABANDONED`、`endedAt` 并释放唯一槽位，“稍后再说”仍只返回且保留 Intent。
- Session 当前页与结束页在 DAO / Repository 层均只允许向前推进，旧页 Note 不会改变 Session 或 Learning Item 阅读进度，Summary 不会生成反向页码范围。
- Session 内新 Note 默认继承最新阅读页码；问号结尾与明确“总结：”前缀提供本地类型建议，摘录由用户明确选择，手动类型不会再被覆盖。
- Note 草稿保留 500ms 自动保存，并在 Activity 进入后台、离开 Session、创建下一条与结束 Session 时主动 flush。
- Intent 时间语义已修正：只有 `CONVERTED` 写入 `convertedAt`；`ABANDONED` 与 `TIMEOUT` 的 `convertedAt` 保持为空，三种结束结果均写入 `endedAt` 并释放 `activeSlot`。
- Phase 2 已冻结总体产品语义：图片文件补偿、Room 2 FTS4 中文双字 token、整体阅读速度与日历推进速度分离，以及 2A→2D 的独立验收边界。
- Phase 2 总体设计与交付计划已保存到 `docs/plans/PHASE_2_IMPLEMENTATION.md`。
- Learning Item 已支持 `IN_PROGRESS → PAUSED`、`PAUSED → IN_PROGRESS` 与 `IN_PROGRESS → COMPLETED`；暂停或完成会在同一事务解除主线，Active Intent / Session 会阻止状态变化，完成状态在本阶段不可恢复。
- 只有 `IN_PROGRESS` Learning Item 能设为主线、创建 Intent 或转换 Session；`createIntent()` 与 `startSession()` 均在各自事务内、实际插入前复核状态。
- 已提供全部 Note 列表、按四种语义类型与 Learning Item 组合筛选、Note 详情、正文/类型/页码编辑、二次确认删除，以及 Session 外独立创建 Note。
- Session 外 Note 仅在已选择有效 Learning Item 且正文去空白后非空时首次落库；首次保存后固定 ID，继续使用 500ms 自动保存与后台/离开页面 flush，Note 页码不推进阅读进度。
- Module 2A 继续使用 Room Schema v1：Entity、Table、Column、Index、数据库版本与 `1.json` 均未改变，不存在 Migration。
- 冻结的 Phase 1 APK 数据已通过 Module 2A APK 覆盖安装验证：原书籍、110 页进度、Session 总结与旧 Note 均可直接读取；离线学习闭环及 Active Session 强停冷启动恢复通过。
- Note 已支持从系统 Photo Picker 单次选择最多 20 张图片和通过系统相机拍照；每张图片独立导入，失败不回滚同批次内其他成功图片。
- 图片导入会复制到临时区，安全解码并处理 EXIF 方向，最长边限制为 2560px，以 JPEG quality 88 写入 App 私有 `images/`；数据库只保存 `images/<uuid>.jpg` 相对路径。
- 已提供 Caption 自动保存、单图删除、Note 多图删除、全屏缩放/平移、Note 内前后切换，以及知识页“全部图片”列表。
- Room 已从 Schema v1 非破坏性迁移到 v2，仅新增 `image_assets` 表、`noteId` 外键级联、`noteId` 索引与 `localPath` 唯一索引；真实 v1 Migration 测试确认 Phase 1 / 2A 数据完整保留，`1.json` 未变化。
- 图片文件删除使用 trash → 数据库事务 → purge / restore 补偿；启动时恢复仍被数据库引用的 trash，并清理超过 24 小时的 import/camera temp、无引用 orphan 与无引用 trash。
- API 37 模拟器已实际验证系统相册导入、相机取消与成功拍照导入、APK 覆盖安装、完全离线学习闭环、Active Session 强停及冷启动异常恢复。
- Start Experience Correction 已冻结产品语义：Start 只管行动、六级状态优先级、`currentPage` 不加 1、首本书主线显式确认并原子创建，以及冷启动遗留 Session 继续按 `ABNORMAL` 恢复。
- Start Experience Correction 详细工程计划已保存到 `docs/plans/START_EXPERIENCE_CORRECTION.md`；规划冻结回合未修改业务代码、Room Schema 或 Migration。
- Start 已重构为六级互斥行动状态：同进程 Active Session、Active Intent、合法主线、无主线进行中选择、仅暂停/完成内容、完全空库，并严格按安全优先级展示唯一主行动。
- 首本书创建提供默认开启但可显式关闭的“设为主线”；创建与主线写入在同一 Room 事务完成。无主线选择某本书只表示本次学习，默认不改变主线；勾选后主线写入与 Intent 创建同事务完成。
- Start 与 Preparation 统一通过 First Action resolver 展示动态 fallback；旧版自动生成文案会按最新 `currentPage` 重算，自定义内容优先，Learning Item 详情支持编辑或清空恢复默认。
- Start 的最近阅读只投影当前内容最近一次 NORMAL Session 的时长和 Note 数量，ABNORMAL Session 不参与；未建立 Analytics 层。
- Start Experience Correction 保持 Room Schema v2、`1.json`、`2.json` 与既有 Migration 不变；API 37 完整 Instrumented/Compose 回归、JVM、lint、assemble、离线覆盖安装及强停冷启动均已通过。
- Topic 已支持创建、列表、详情、Note 多对多关联、幂等关联/解除、笔记内创建并关联，以及仅基于正文明确包含关系的保守本地建议；不包含改名、删除、层级或 AI。
- Room 已从 Schema v2 非破坏性迁移到 v3：仅新增 `topics`、`note_topic_cross_refs` 与 Room FTS4 `search_fts`；`1.json`、`2.json` 未变化，v2→v3 与 v1→v2→v3 真实 Migration 测试确认旧数据完整保留。
- 本地搜索覆盖 Note 正文、图片 Caption、Learning Item 名称、Topic 名称和非空 Session Summary；中文使用 NFKC 与连续 CJK 双字 token，英文使用完整 token，MATCH 仅使用参数绑定的安全表达式。
- `search_fts` 是可重建派生索引；既有 Repository 在事务内同步相关写路径，冷启动只用行数做轻量健康检查，并保留显式 rebuild、FTS 异常后最多一次 rebuild + retry、missing/duplicate/stale 恢复测试。
- Knowledge 已增加搜索与 Topic 入口；搜索 300ms debounce、全局最多 60 条并按 Note / Learning Item / Topic / Session 分组，可进入对应详情或最小只读阅读记录。
- Module 2C 的 JVM、完整 Room/Migration/Instrumented/Compose、lintDebug、assembleDebug、离线 APK 覆盖安装与冷启动均已通过；Phase 1、2A、2B 与 Start 六级状态回归通过。
- 已建立 Mirra 自有 Color、Shape、Depth 语义 Token 与完整 Material 3 角色映射，默认 Mirra Blue 使用灰白主体、`#6598E8` 行动 Accent 与 `#386FBE` 白字主 CTA，不再泄漏 Material 默认紫色。
- 已落地 `MirraPrimaryButton`、`MirraSecondaryButton`、`MirraTextAction`、`MirraFocusCard`、`MirraProgress`、`MirraToggle`、`MirraBottomNavigation` 和最小 Surface primitive；Depth 仅用于交互与焦点。
- Start、Preparation、Session、Knowledge、Mine 与图片预览已迁移到 Mirra Blue。Start 保持六级真实状态与唯一强 CTA；Knowledge 列表保持平面和较高信息密度；Mine 未提前引入 Module 2D 数据 UI。
- 主题 ID 通过现有 DataStore 持久化，空值或非法值回退 Mirra Blue，且不覆盖已保存的顶层导航；设备测试确认已保存主题在 Activity recreate 后仍被应用。Mono / Night 只定义基础可读 Palette / Token，本阶段未开放主题选择页。
- Theme Foundation 不修改 Room：`MirraDatabase.version` 仍为 3，`1.json` / `2.json` / `3.json` 无变化，未新增 Migration。API 37 断网覆盖安装、冷启动、Start / Knowledge / Mine 视觉检查与完整 JVM / Instrumented / Compose / lint / build 已通过。
- Mirra Blue Visual Refinement 已把 EmptyLibrary 收敛为“观已 Mirra / 开始第一次学习 / 添加第一本书”三项，移除通用问题和主线解释；主 CTA 增加 Token 驱动的顶部高光、柔和阴影与 pressed 低层次，Bottom Navigation 选中态改用较弱的 `#6598E8`，Focus Card 与导航 Surface 使用统一高光 Token。
- Start Mainline 已将说明式标题替换为“今天继续”，并移除“当前主线”系统标签；Knowledge 已改为独立平面搜索入口、轻量笔记/图片/Topic 二级导航及 Learning Item 主内容，创建操作降为弱于 Start 主 CTA 的文字行动。
- 本轮保持 Start 六级状态、Navigation、业务数据与 Room Schema v3 不变；API 37 上 37 个 JVM 测试、92 个 Room/Migration/Instrumented/Compose 测试、lintDebug、assembleDebug、断网覆盖安装及强停冷启动均已通过。
- Mirra Blue 最终视觉已验收并冻结；`MirraProgress` 已移除 Material 3 默认 Track 末端 Accent stop marker，仅保留实际进度 Fill 与剩余 Track，并由像素级 Compose 回归测试保护。最终全量验证为 37 个 JVM 测试与 93 个 Room/Migration/Instrumented/Compose 测试全部通过。
- Module 2D 已新增只读 `ReadingSessionProjection` 与 `ReadingAnalyticsRepository`：单书历史、单书最近 30 天和全局最近 14 天均使用有限查询与 Session/Note 聚合，不读取图片、Topic、FTS 或 Note 正文，不产生 N+1。
- 统计只纳入正常结束且时间、页码完整的 Session；ABNORMAL、EARLY、AUTO、START_INCOMPLETE、Active、非正时长和未来记录均排除统计。零推进正常 Session 保留在历史，并计入 Session 数、阅读日、总时长及整体速度分母。
- 近期整体阅读速度使用总推进页数除以总 Session 时长，按 7→14→30 天和至少 3 Session / 30 分钟选窗；预计剩余阅读时间与自然完成日期分离，后者按完整窗口自然日计算真实日历推进速度。
- 自然完成日期先执行硬门槛，再计算可信度；`robustCV > 0.75`、低可信度、PAUSED/COMPLETED、无近期阅读或无页码推进均不显示日期。高/中可信度分别提供 10%/20% 日期范围，不展示虚假百分比。
- Learning Item 详情已加入近期节奏、剩余阅读时间、自然完成日期范围和完整已结束 Session 历史；异常历史明确标记“不参与统计”。“我的”只增加平面的最近 7 天 Session、时长、页数、Note 数和一条前 7 天时长比较，Start 未接入 Analytics。
- Module 2D 保持 Room Schema v3、`1.json` / `2.json` / `3.json` 与既有 Migration 不变；新增 `java.time` core library desugaring 仅用于 minSdk 23 的本地自然日与时区计算。
- Module 2D 最终验证覆盖 51 个 JVM 测试与 101 个 API 37 Room/Migration/Instrumented/Compose/端到端测试，并通过 `lintDebug`、`assembleDebug`、断网 APK 覆盖安装和两次强停冷启动。
- Phase 2 已在远程实现提交 `734750bfe9fabc13d99091356ae0e4ffd1b8dc82` 正式冻结；冻结检查点见 `docs/checkpoints/2026-09-14-phase-2-freeze.md`。
- Room 已从 Schema v3 非破坏性迁移到 v4，新增 `risk_apps`、`session_focus_contexts`、`session_risk_app_snapshots`、`session_segments` 与 `focus_events`；既有 v1/v2/v3 Schema 文件保持不变。
- SessionSegment 已具备 FOCUS、DEEP_FOCUS、BREAK、TEMPORARY_ALLOWANCE、DISTRACTION、RECOVERY 与 UNMONITORED 一等语义；唯一 active slot 与 Repository 事务保证切段边界连续、无零时长、无倒序且已结束 Session 不可再写。
- coverage 明确使用 FULL / PARTIAL / NONE；PARTIAL 永不回到 FULL，初始 NONE 只能在后续获得可信覆盖时成为 PARTIAL。UNMONITORED 不计 Focus，完整可信判断还会拒绝空洞、重叠、越界和未覆盖完整 Session 的时间线。
- 3A 尚无监测能力，因此新 Session 保守创建为 `NONE + UNMONITORED`；正常结束会关闭活动 Segment，进程死亡恢复会从最后可信 heartbeat 到检测时刻记录 UNMONITORED，再沿用 ABNORMAL 规则结束 Session，不伪造 Focus。
- Stable Start 120 秒与 Recovery 90 秒已作为纯领域 milestone 规则和持久化事实入口实现，不自动触发、不阻塞 Session，也未接入任何 Android 系统信号。
- Module 3A 远程提交 `35064ee1cb5fb69a9499db2e847dfb346b4331ef` 已由用户正式验收并冻结。
- Start Mainline 已按批准概念图实现顶部品牌与轻量个人入口、确定性灰阶占位封面、双栏 Focus Card、页码/百分比/Progress/First Action、Play 主 CTA 和真实正常 Session 的最近阅读条；EmptyLibrary 仍只保留品牌、标题与唯一主按钮。
- Bottom Navigation 已缩轻图标与选中区，避开系统手势区；Knowledge / Mine 仅做视觉回归检查，未修改页面内容、IA 或数据逻辑。
- Visual Parity Pass 保持 Room Schema v4、Module 3A Segment/Coverage/FocusRepository/状态机/Migration 3→4 不变；API 37 模拟器四页截图和 Mainline 并排对比已保存至 `docs/checkpoints/assets/mirra-visual-parity/`。
- Module 3B Existing Solutions Review 已基于 Mindful 与 Reef 的固定源码提交完成并验收，文档见 `docs/research/PHASE_3B_EXISTING_SOLUTIONS_REVIEW.md`；只借鉴可验证思路，不复制第三方代码。
- 3B 实施语义已锁定：Monitoring READY 定义新 Session 起点且不要求前台 package；能力不可用时允许 `NONE + UNMONITORED` 学习；运行时期限采用单调时间；首版约 1 秒观察；FULL 表示完整可信覆盖而非全程 Focus。建议采用时间有序 Observation reducer，FGS 不承载业务状态机。
- Module 3B Task 1 已新增纯 Kotlin Observation reducer 与 Risk App Candidate 状态机：查询连续性与前台包新鲜度分别跟踪；空查询不制造缺口，重复旧窗口及无关事件不刷新可信证据；失败、窗口断裂、时钟跳变输出保守的领域信号；10 秒候选使用单调时间、后续可信查询及同源 Segment guard，确认幂等。14 项新增 JVM 测试与原有测试合计 75 项通过；未接 Android API、Room 或 UI。
- Module 3B Task 2 已建立 Usage Access、通知栏可见性与 DND policy access 的独立能力检查；Usage Access 须同时通过 AppOps 和真实 query，成功空查询仍可用，通知或 DND 拒绝不阻止监测。仅开发版“我的”提供本地诊断和显式启动/停止入口。
- 私有 `specialUse` 前台 Service 只在用户显式操作后启动，使用 `START_NOT_STICKY`，以唯一 generation 防重复 poller；Foreground ACK、首次成功查询和 `MONITOR_READY` 分开报告。约 1 秒轮询、3 秒重叠查询窗口、wall-clock jump 后 cursor 重建与息屏/锁定事实均通过 Task 1 reducer；Service 不创建 Session、不写 Room、不应用 DND。
- API 37 模拟器已实际验证 Usage Access 未授权时不进入 READY、授权后在通知/DND 未授权时仍进入 READY、手动停止、运行中撤销 Usage Access 后退出 READY、权限设置往返、APK 覆盖安装及强停冷启动。Task 2 完整回归为 90 项 JVM 与 115 项设备测试通过，`lintDebug` 和 `assembleDebug` 通过；Room Schema v4 未变化。
- Module 3B Task 3 已将 Preparation 启动接入唯一 SessionStartCoordinator：PREPARING → READY_LEASE → COMMITTING → COMMITTED → BOUND；READY Lease 记录同一 ClockSample 的 wall/elapsed 时间、generation、查询连续性与单调 TTL。用户点击时间不计入 Session。
- monitored start 在同一 Room 事务中复核 Intent、Learning Item、Active Session 与页码，写入 Session、FULL Context、风险 App 快照、初始 FOCUS Segment 和 Intent CONVERTED；Session/Segment/heartbeat 的初始时间均为 READY 时刻。普通入口仍创建 `NONE + UNMONITORED`。
- 事务返回是否由本次新建，并使用预留 Session ID 核对取消后的提交归属；重入不会绑定别人的 Session。监测失败回退普通启动，业务拒绝不回退。提交后绑定前失监使用既有 `markMonitoringLost` 保守降为 `PARTIAL + UNMONITORED`。
- Service 轮询已收敛为每轮一次 UsageEvents 主查询加轻量 AppOps/解锁复核；完整 capability probe 只在 Activity onResume 或握手 preflight 执行。Session 正常结束后会释放其绑定的 FGS。
- Task 3 最终验证：109 项 JVM、123 项 API 37 设备/Compose 测试通过，`lintDebug`、`assembleDebug` 通过；APK 覆盖安装、授权/未授权启动、双击、断网冷启动、强停后 `ABNORMAL/PARTIAL` 均在模拟器验证。Room Schema 仍为 v4，未增 Migration。
- Module 3B Task 4 已把已绑定 Session 的 Usage observation 接入独立的 `BoundSessionMonitoringController`，由它协调风险候选、Room 事实、约 15 秒 heartbeat 与 6 秒查询缺口 watchdog；FGS 仍不承载业务状态机。
- Candidate 同时保存可信 UsageEvent wall 起点、单调时间、generation、token 与原 Focus Segment；可信短暂退出记录 `RISK_APP_BRIEF_VISIT`，约 10 秒确认在事务中复核 Session、风险快照和同一活动 Focus Segment 后回写 `DISTRACTION` 起点。确认事件使用真正确认时刻；可信退出转入关联的 `RECOVERY`，本任务不自动完成恢复。
- 正常空查询维持监测连续性而不无限延长旧前台包；权限撤销、Service 非正常停止、wall clock jump 与查询缺口会清候选，并将真实失监区间落为 `PARTIAL + UNMONITORED`，不会回填 Focus 或把 NONE 升级 FULL。
- “我的”开发版提供最小风险 App 选择与监测诊断。仅列可见可启动 App，不使用 `QUERY_ALL_PACKAGES`，Mirra、Launcher、SystemUI、Settings 不可选；当前 Session 使用起点风险快照，配置变化只影响下一次 Session。
- API 37 模拟器已验证真实 Chrome 风险 App 的短暂访问、持续访问确认、退出后 RECOVERY、15 秒 heartbeat、空查询不断监、运行中撤销 Usage Access，以及用户主动停止监测后立即降级。Chrome 内部 Activity 切换的旧 Activity STOPPED 不再误撤销新 Activity 的前台证据；相关纯状态机回归测试已增加。
- Task 4 完整回归为 121 项 JVM 与 127 项 API 37 Room/Instrumented/Compose 测试通过，`lintDebug`、`assembleDebug` 通过；APK 覆盖安装、离线冷启动与强停恢复在模拟器验证。Room Schema v4 与历史 Schema 文件均未变化。
- Task 4 acceptance patch 将已绑定监测的受控停止改为先同步等待失监事实落库，再停 Service；正常 Session 结束则先提交结束事务、再释放其绑定监测。两条路径由同一控制器串行化；失监写入失败会阻止正常结束保留虚假 FULL。通知停止、开发诊断停止、运行中 Usage Access 撤销、已知查询故障和 watchdog 均沿用该顺序；`onDestroy` 仅作异常兜底。
- Acceptance patch 的 122 项 JVM、131 项 API 37 Room/Instrumented/Compose 测试以及 `lintDebug`、`assembleDebug` 通过。模拟器覆盖安装并冷启动后，实测 FULL Session 从通知栏停止监测、随即结束为 NORMAL，最终数据库为 PARTIAL，FOCUS 后有连续 UNMONITORED 区间；Room Schema v4 未变化。实体 OEM 真机仍待 Task 6 验证。
- Module 3B Task 5A 已新增与 MonitoringCoverage 正交的 `DndController`、Room v4 状态存储和 Android DND adapter。用户偏好默认关闭；只有已提交 Session 才会在偏好开启且授权可用时尝试 DND，失败不阻止 Session，也不改变 FULL / PARTIAL / NONE。
- DND 兼容矩阵已冻结：API 23–28 仅使用用户现有 Priority Policy 的 legacy interruption filter，不修改全局 Notification Policy；API 29–34 使用可重复识别的 Mirra-owned `AutomaticZenRule + ZenPolicy`；API 35+ 同样只管理 Mirra rule，并尊重 user-managed policy 与用户 override。
- API 29+ 规则以稳定 condition URI 重新发现和复用，创建前先持久化 creation intent，覆盖“规则创建成功但 ID 尚未写入”的 crash gap。正常结束在 Session commit 后释放；冷启动先完成既有 ABNORMAL recovery，再对账已结束 Session 的 ACTIVE / RELEASE_PENDING / RELEASE_FAILED。旧版跨进程无法证明 ownership 时保守保留 RELEASE_FAILED，不盲写全局 DND。
- Task 5A acceptance patch 已把 DND 完全移出 Monitoring Ready Lease 的关键路径：monitored start 先完成 `verifyAfterCommit` 与 bind，或完成 `PARTIAL + UNMONITORED` 降级补偿，之后才在 IO dispatcher 上等待 best-effort DND apply。慢 DND、SecurityException 与取消后的 commit recovery 均不再改变既定 FULL/PARTIAL/NONE；unmonitored Session 仍可独立申请 DND。
- Module 3B Task 5B 已新增 Profile/Mine 的“学习保护”入口：DataStore `dnd_enabled` 默认关闭；开关只影响下一次 Session，当前 Session 不被 UI 改写。权限状态以“关闭 / 已就绪 / 需要系统授权”展示，缺少权限时提供系统设置入口并在返回前台时重新检查。
- Task 5B 复用既有 `DndController`、`RoomDndStateStore` 与 `AndroidDndGateway`；失败重试只通过薄应用门面调用 `apply` / `reconcileAfterRecovery`，不直接修改系统规则、coverage 或生命周期。API 23–28、29–34、35+ 文案遵守已冻结兼容矩阵；开发版诊断补充偏好、权限、活动 Session、生命周期、prior filter、Mirra rule 和 pending release 字段。
- Preparation 页的启动中提示已改为中性的“正在准备本次学习…”，不向用户暴露内部“分心监测”实现术语。
- Module 3B Task 5B final acceptance patch 已由用户复验通过；Task 5A + 5B 作为完整 Task 5 正式冻结。当前 Session 的 `APPLY_FAILED` retry 与下一次 Session 偏好保持分离，Profile 小屏内容可滚动。
- Task 6A 已把最终验证拆成自动化、安装生命周期、Monitoring、DND、AOSP/API 矩阵和逐 OEM 真机记录；统一使用 PASS / FAIL / DEGRADED / NOT RUN，禁止用模拟器代替 OEM 真机结论。协议见 `docs/plans/MODULE_3B_TASK6_VALIDATION_PROTOCOL.md`。
- Task 6D 已将 API 29+ Mirra-owned `AutomaticZenRule` 校验收窄到 Mirra 明确控制的 ZenPolicy 字段，不再将 calls、alarms、media 等继承/未声明字段纳入完整对象相等判断；conditionId、configurationActivity、owner、enabled 与 rule ID 的 ownership 保护保持不变。
- API 35+ 每次 `setAutomaticZenRuleState()` 后都回读真实 state；只有 activate=`STATE_TRUE` / deactivate=`STATE_FALSE` 才视为成功，系统拒绝或未接受会沿现有链路落为 `APPLY_FAILED` / `RELEASE_FAILED`，不写假 `ACTIVE` / `RELEASED`。
- API 37 AOSP 定向实测已确认 own rule 可复用、非 Mirra rule 不被操作、受控 policy 被修改时拒绝激活、激活/释放后真实 state 分别为 TRUE/FALSE，且 global Notification Policy 不变。真实 monitored Session 记录为 `FULL + DND ACTIVE`，正常结束后为 `NORMAL + FULL + DND RELEASED`，系统 Mirra rule 回到 `STATE_FALSE`。
- Task 6D 回归结果：JVM `148/148`、API 37 connected `138/138`、`lintDebug` 与 `assembleDebug` 全部通过。Room 仍为 Schema v4，`4.json` SHA-256 仍为 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`，无 Schema / Migration 变化。

## Frozen Module 3C Baseline

- Module 3C-1 已冻结。范围见 `docs/plans/MIRRA_MODULE_3C_MASTER_PLAN.md`；接口、转换矩阵、事务与连续证据契约见 `docs/checkpoints/2026-10-03-module-3c-1.md`。
- 按 Session/package 持久确认计数的 0/5/15 秒、可见等待、当前 episode 授权、1–15 分钟单次时长、一次 +2 分钟延长、5/10 分钟 Break、到期回 Recovery/NONE 回 UNMONITORED 已落地。
- 3C-2 已增加薄 SessionViewModel/actions 接线、平面状态区、休息、应用内提示、用途投影、等待/延长确认与真实页面焦点/生命周期/屏幕证据生产者；保留 Note 自动保存/flush。Back 依次关闭用途、休息选择、提示，再保存并离开。
- 3C-1 采样竞态 Acceptance Patch 已随 3C-2 正式冻结：较旧页面样本只跳过反向 gap / 混合 wall-clock 检查，仍参与动作与连续证据累计；真实 6 秒缺口、wall-clock jump 与失监不可逆防护保留。LUNA finalization 未再修改控制器或核心测试。
- 已提交 AVD 执行证据覆盖 5/10 分钟休息提前结束、Chrome 风险提示、首次零等待授权、一次延长、第二次可见等待暂停，以及连续 91.122 秒 Recovery 自动回 FOCUS；FULL 与草稿均保留。
- 3C-2 已通过用户独立源码、测试和已提交证据审查，未发现阻塞缺陷。冻结提交为 `f275334e233298b872bb507a82dcd875ff3135d5`。本次独立验收没有重新运行 Gradle；JVM 183/183、单次 connected 155/155（均 0 failure/error/skipped，DND 平台 3 项实际执行）、lintDebug / assembleDebug、覆盖安装、离线闭环、冷启动与重建恢复结论均来自已提交执行证据。历史失败及未测范围保留在 `docs/checkpoints/2026-10-03-module-3c-2.md`。

## Frozen Module 3C-3 Baseline

- Module 3C-3 已通过用户独立源码、测试与已提交证据审查，未发现阻塞缺陷并正式冻结。冻结实现为 `b9843eb5c3879899e148792a6a1f252e4b6d573d`；独立验收结论已追加到 `docs/checkpoints/2026-10-04-module-3c-3.md`。
- 跨应用提醒默认 OFF，偏好使用既有 DataStore；在 Session commit / monitoring settlement 后采集本次快照，后续修改只影响下一次。前台保留冻结的应用内 Prompt；后台消费同一有效 episode，优先小 Overlay，再降级独立通知。
- API26+ 原生 Overlay 最大 304dp / WRAP_CONTENT / NOT_FOCUSABLE，无遮罩、锁机或自动拉起；四入口只返回学习、打开现有用途面板、打开原结束入口或关闭提示。通知 `mirra_focus_intervention` / ID3002 与 FGS 分开，不 bypass DND，POSTED 不冒称可见。
- 显式 immutable Activity PendingIntent 用 session/token/action URI；submit / consume 与 Room Active Session 双重复核，旧 token / 结束 / 新进程动作拒绝。generation + 单 owner 清理 foreground、dismiss、release、loss / service destruction 与迟到回执。
- 既有 focus_events 只记录真实 IN_APP composition / OVERLAY attach 和每 episode 一次 UNAVAILABLE；不改变 Segment / Coverage / DND。Room v4、schemas 1–4 与 Migration 不变。
- 已提交执行证据为最终 JVM 222/222、完整未过滤 connected 177/177（0 failure/error/skipped，DND 3 / channels 5 实际执行）、lintDebug / assembleDebug 通过，以及 API37 真实 A–H、Overlay 导航、最终覆盖安装、离线冷启动、强停 ABNORMAL、旧动作回放和数据保留。本次独立验收与纯文档同步没有重新运行 Gradle / AVD，不将上述结果冒充本次重新执行。环境故障及修补 RED 历史继续保存在 `docs/checkpoints/2026-10-04-module-3c-3.md`。
- API 23–36、OEM、实体设备、TalkBack 与发行环境未测项继续 `NOT RUN`；API 37 专用 AOSP AVD 结果不外推为其他平台 PASS。3C-3 冻结核心保持不变，Phase 3D 尚未开始。

## Module 3C-4 Delivery — Awaiting Independent Acceptance

- 起点 `6384cc634b080ac9554ac20cd4c95b9b5e13a262`，分支 `codex/phase-3c-final-validation`。执行协议与结果见 `docs/checkpoints/2026-10-04-module-3c-final.md`；六类实际 AVD 截图见 `docs/evidence/module-3c-final/`。
- 只有一个 ViewModel 输入修补：40→42 逐字编辑中间的 4 不再立即回填40，但较小值仍不保存，旧页 Note 不推进阅读进度。新 JVM 与 Compose/真实 Room 测试约束该行为；DND、Monitoring/READY、Coverage、行为核心、Schema 与 Migration 无改动。
- 同一最终代码的完整未过滤 JVM 223/223、connected 178/178，均 0 failure/error/skipped；DND 平台3项与渠道平台5项实际执行。lint 0 errors / 9 existing warnings / 1 hint，assembleDebug 通过。
- API37 实际闭环：风险短/长访、应用内与 Overlay 四操作、通知真实点击/POSTED≠SHOWN、渠道/权限降级、Allowance 一次延长与风险 B、Break、连续91.753秒 Recovery、Usage撤权/主动停止、强停 ABNORMAL/PARTIAL/UNMONITORED与 DND释放、旧URI不复活、320dp/font2、360dp/411dp可达性。
- 最终 APK 覆盖安装与断网冷启动后，专用书籍/Note/图片/正常Session/42页进度/Caption/偏好/风险选择保留，图片与搜索可读。最终 Room v4 hash 仍 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`；schemas 1–4 不变。
- Debug APK：`build/deliverables/Mirra-3C4-debug.apk`，15874132 bytes，SHA-256 `4271EBE0BEF656098FA83F0ADAD9781574E113AB2FA4153ED7ED00F4D79087EE`；appId `com.guanyi.mirra`，0.1.0/code1。APK 不入 Git，仅个人试用，不是 release。
- C01–C24 在自动化/API37约定范围内通过；C25 是个人安装包与 checklist 交付，新版本一加13T实际反馈仍 NOT RUN，未自动操作实体机。作者自查不代替用户独立验收。

## Frozen Phase 2 Baseline

### Phase 2｜笔记与阅读体验完善

- Module 2A：Learning Item 生命周期与 Note 完整化（已正式验收并冻结）。
- Module 2B：图片、App-owned Files 与 Schema v1 → v2 已正式验收并冻结。
- Module 2C：轻量 Topic、FTS4 搜索与 Schema v2 → v3（已正式验收并冻结）。
- Module 2D：阅读分析、剩余阅读时间与自然完成预测（已正式验收并冻结）。
- Start Experience Correction：六级行动首页、首次创建主线事务与 First Action 补齐（已正式验收并冻结）。
- Theme System v1：Theme Foundation 与 Mirra Blue 已正式验收并冻结；Mono / Night 全页面迁移、可视化选择与三主题全量验收属于后续独立阶段。

## Pending

- Module 3C-4 已完成交付，等待用户独立验收；新版一加13T个人试用等待反馈。Closeout、有效指标与 Phase 3D 尚未开始，等待后续单独授权。
- Phase 3｜Module 3B Task 6 仍待最终独立验收。Task Manager Stop、reboot、完整 risk/lock/revocation 矩阵、API 23/29/33/34/35 与实体/OEM 设备继续为 `NOT RUN`，不用 API 37 AVD 结果代替。

## Known Risks / Unknowns

- UI 专项设计 Skill 的共享 Playbook 文件未安装在预期路径；Module 0 仅实现克制的 Material 3 导航骨架。
- 原中文路径副本仍因当前 Codex 桌面会话占用而保留；后续开发与验证仅以英文路径仓库为准。
- Android Studio 的系统安装流程被 Windows 安装确认阻塞，本阶段改用用户目录下的 JDK 17、Android SDK Command-line Tools、ADB 与 Emulator 完成验证。
- Android 设备与厂商对 Usage Access、DND、Overlay 的兼容性需要在 Phase 3 通过真实设备验证。
- Room 当前为 Schema v4；v1 → v2 → v3 → v4 使用显式非破坏性 Migration 并由导出的真实历史 Schema 验证，后续任何 Schema 变更仍必须提供非破坏性 Migration。
- Phase 1 不包含 SessionSegment，故只保存和展示 Session 总时长，不计算有效专注时间。
- Android 在无生命周期回调的瞬时进程终止下无法保证最后不足 500ms 的未落盘输入绝对不丢；当前已覆盖所有可观察的关键生命周期与导航节点。
- 中文双字 token 不支持中文单字、拼音、同义词、stemming 或模糊匹配；这是首版明确边界，单字查询会提示输入至少两个连续中文字符。
- FTS 行数相等只代表轻量健康检查通过，不能证明索引内容绝对正确；用户可显式重建，查询异常会自动重建并最多重试一次。
- Module 2D 的完成预测是基于近期页码与 Session 时长的解释性估算，不是目标日期；页码记录不充分、速度高波动或样本不足时会主动隐藏结果。
- 文件系统与 SQLite 无法形成真正的跨资源原子事务；当前通过 trash、补偿和启动清理实现最终一致。若设备在文件系统持续故障时终止进程，文件会保留供后续启动再次恢复或清理。
- Android Instrumented 测试为兼容 Room 2.8.5 Migration Schema 验证，在 androidTest 配置中固定 kotlinx-serialization 1.8.1；生产运行时依赖未因此替换。
- Mono / Night 尚未进行全页面、字号放大、TalkBack 与主题切换视觉验收，因此本阶段不向用户开放主题选择入口。
- 首版中途重新授权不自动恢复当前 Session 监测，保持 PARTIAL。3C-2 已接入真实页面可见性/屏幕证据及应用内干预；不使用缓存前台 package 冒充长时间稳定，FULL 不等于全程 Focus。3C-3 渠道已正式冻结；通知 POSTED 与 Overlay attach 均不能外推为用户已读，OEM 可进一步限制通道。有效专注指标仍属于后续交付包。

## Git

- Current branch: `codex/phase-3c-final-validation`
- 3C-4 delivery source：`6384cc634b080ac9554ac20cd4c95b9b5e13a262` + 本轮页码输入修补；交付提交 `feat(focus): finalize module 3c experience`，完整 SHA 与远程一致性在本轮最终报告和 Git 核对，不回写自身文档的自身 commit SHA。
- Frozen 3C-3 implementation commit: `b9843eb5c3879899e148792a6a1f252e4b6d573d`（`feat(focus): add safe cross-app intervention`），已由用户核对远程并正式验收；未合并 main。
- 3C-1 original implementation base: `61ed78f94125e7049c5d4ca73be1b16c5416043b`
- Frozen 3C-2 / Acceptance Patch implementation commit: `f275334e233298b872bb507a82dcd875ff3135d5`（`feat(focus): add in-app learning return experience`），已由用户核对远程并正式验收。
- Frozen Phase 3 planning base: `882d649cf600cd3f6f3b59d0be8a7911f3e42c70`
- Room Schema: v4

## Next Recommended Task

提交 3C-4 完整报告与 Debug APK 后停止，等待用户独立验收及个人试用反馈。继承冻结行为内核、采样竞态修补、UI 接线、渠道治理与 Room v4；API 23–36、OEM、实体设备、TalkBack 与发行环境未测项保持 `NOT RUN`。不得自动进入 Closeout、有效专注指标或 Phase 3D。
