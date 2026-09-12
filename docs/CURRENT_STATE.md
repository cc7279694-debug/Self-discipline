# Current State

更新日期：2026-09-12

## Current Stage

Phase 2｜Module 2A、Module 2B 与 Start Experience Correction 已正式验收并冻结；Module 2C Topic + Search 已完成实现与全量验证，等待用户正式验收；Module 2D 尚未开始。

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

## In Progress

- Module 2C 已完成实现、验证与 checkpoint，等待用户正式验收冻结。

## Pending

### Phase 2｜笔记与阅读体验完善

- Module 2A：Learning Item 生命周期与 Note 完整化（已正式验收并冻结）。
- Module 2B：图片、App-owned Files 与 Schema v1 → v2 已正式验收并冻结。
- Module 2C：轻量 Topic、FTS4 搜索与 Schema v2 → v3（已实现并完成验证，等待正式验收）。
- Module 2D：阅读分析、剩余阅读时间与自然完成预测。
- Start Experience Correction：六级行动首页、首次创建主线事务与 First Action 补齐（已正式验收并冻结）。

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
- 自然完成日期的速度变异系数阈值尚未冻结；必须在 Module 2D 实施计划中提出并经工程验收，不得在实现中临时决定。
- 文件系统与 SQLite 无法形成真正的跨资源原子事务；当前通过 trash、补偿和启动清理实现最终一致。若设备在文件系统持续故障时终止进程，文件会保留供后续启动再次恢复或清理。
- Android Instrumented 测试为兼容 Room 2.8.5 Migration Schema 验证，在 androidTest 配置中固定 kotlinx-serialization 1.8.1；生产运行时依赖未因此替换。

## Git

- Current branch: codex/phase-2c-topic-search
- Base: origin/main
- Push status: Phase 0、Module 1、Phase 2 文档基线、Module 2A、Module 2B、Start Experience Correction 与 Module 2C 均已推送至各自功能分支；Module 2C 远程 SHA 以交付报告记录为准。

## Next Recommended Task

正式验收并冻结 Module 2C。之后如需推进 Module 2D，应先单独规划阅读分析、剩余阅读时间与自然完成预测；本任务未进入 Module 2D。
