# Current State

更新日期：2026-09-14

## Current Stage

Phase 2 已正式验收并整体冻结。Module 2A、Module 2B、Module 2C、Module 2D、Start Experience Correction 与 Mirra Blue 均已完成；当前没有实施中的业务模块。Phase 3 尚未开始，仅可在单独规划和验收后实施。

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

## In Progress

- 当前没有实施中的业务模块。Phase 2 已正式冻结；未开始 Phase 3 规划或实现。

## Frozen Phase 2 Baseline

### Phase 2｜笔记与阅读体验完善

- Module 2A：Learning Item 生命周期与 Note 完整化（已正式验收并冻结）。
- Module 2B：图片、App-owned Files 与 Schema v1 → v2 已正式验收并冻结。
- Module 2C：轻量 Topic、FTS4 搜索与 Schema v2 → v3（已正式验收并冻结）。
- Module 2D：阅读分析、剩余阅读时间与自然完成预测（已正式验收并冻结）。
- Start Experience Correction：六级行动首页、首次创建主线事务与 First Action 补齐（已正式验收并冻结）。
- Theme System v1：Theme Foundation 与 Mirra Blue 已正式验收并冻结；Mono / Night 全页面迁移、可视化选择与三主题全量验收属于后续独立阶段。

## Pending

- Phase 3｜Android 专注干预与分心恢复：仅待详细工程规划；尚未授权编码、数据库变更或系统能力接入。

## Known Risks / Unknowns

- UI 专项设计 Skill 的共享 Playbook 文件未安装在预期路径；Module 0 仅实现克制的 Material 3 导航骨架。
- 原中文路径副本仍因当前 Codex 桌面会话占用而保留；后续开发与验证仅以英文路径仓库为准。
- Android Studio 的系统安装流程被 Windows 安装确认阻塞，本阶段改用用户目录下的 JDK 17、Android SDK Command-line Tools、ADB 与 Emulator 完成验证。
- Android 设备与厂商对 Usage Access、DND、Overlay 的兼容性需要在 Phase 3 通过真实设备验证。
- Room 当前为 Schema v3；v1 → v2 → v3 使用显式非破坏性 Migration 并由导出的真实历史 Schema 验证，后续任何 Schema 变更仍必须提供非破坏性 Migration。
- Phase 1 不包含 SessionSegment，故只保存和展示 Session 总时长，不计算有效专注时间。
- Android 在无生命周期回调的瞬时进程终止下无法保证最后不足 500ms 的未落盘输入绝对不丢；当前已覆盖所有可观察的关键生命周期与导航节点。
- 中文双字 token 不支持中文单字、拼音、同义词、stemming 或模糊匹配；这是首版明确边界，单字查询会提示输入至少两个连续中文字符。
- FTS 行数相等只代表轻量健康检查通过，不能证明索引内容绝对正确；用户可显式重建，查询异常会自动重建并最多重试一次。
- Module 2D 的完成预测是基于近期页码与 Session 时长的解释性估算，不是目标日期；页码记录不充分、速度高波动或样本不足时会主动隐藏结果。
- 文件系统与 SQLite 无法形成真正的跨资源原子事务；当前通过 trash、补偿和启动清理实现最终一致。若设备在文件系统持续故障时终止进程，文件会保留供后续启动再次恢复或清理。
- Android Instrumented 测试为兼容 Room 2.8.5 Migration Schema 验证，在 androidTest 配置中固定 kotlinx-serialization 1.8.1；生产运行时依赖未因此替换。
- Mono / Night 尚未进行全页面、字号放大、TalkBack 与主题切换视觉验收，因此本阶段不向用户开放主题选择入口。

## Git

- Current branch: codex/phase-2d-reading-analytics
- Base: frozen Phase 1 / 2A / 2B / Start Experience Correction / 2C / Theme Foundation / Mirra Blue baseline
- Base remote SHA: `8ce8dd4ee82bfb1787dc762d34ccbd59742fd9a5`
- Planning commit: `b411f0bf154cab9763aaec0c04c69c4df12d356f`
- Phase 2 frozen remote SHA: `734750bfe9fabc13d99091356ae0e4ffd1b8dc82`

## Next Recommended Task

单独规划 Phase 3｜Android 专注干预与分心恢复，重点评估 DND、Usage Access、Risk App、通知 / Overlay fallback、SessionSegment、Break、Distraction 与 Recovery；规划验收前不开始编码。
