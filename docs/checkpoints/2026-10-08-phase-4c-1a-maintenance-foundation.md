# Phase 4C-1A — Maintenance Protocol + Strict Preferences Foundation

## Identity and goal

- 日期：2026-10-08；Repository `cc7279694-debug/Self-discipline`。
- Branch `codex/phase-4c-full-backup`；授权 base `7499ba50cc775d801711eecae38801099f6c66f9`，继承 4B Freeze `cd13953cb3ac132ee64493fe688a3ac0f3cf8e74`。
- 先独立提交 `a14c073c2febc239dfce55e8c4a556550653e0a9`（`docs(backup): accept phase 4c0 audit`）：4C-0 accepted safety audit / PASS WITH NOTES；不是 Backup/Restore 验收。新增 FileProvider 可能早于 Application.onCreate 的门禁审阅提示。
- Production + corresponding JVM tests HEAD `8e50545417f8854caee366b70426b9cf56058ae0`（`feat(backup): add maintenance foundation`）。原实现交付时的验证文档提交为 `bf5e423536b93623eaec1e8cf03261879c3154a1`，包含当时的本检查点/当前状态/安全合同；本次 Freeze 另作纯文档提交，两者均不替代 production HEAD。
- 目标：仅建立可测试的内存许可/排空协议与严格偏好快照模型。不生成备份、不替换数据、不实施 Restore/Journal。
- 原实现交付状态：**foundation implemented / awaiting independent review**。当时内部只读复核未发现 Critical/Important 实现缺陷，不等同 ChatGPT 独立验收，未宣布正式冻结。当前正式状态：**accepted / frozen — PASS WITH NOTES**；独立验收及范围见末尾追加记录，不进入 4C-1B。

## Actual maintenance API and contract

源码根为 `app/src/main/java/com/guanyi/mirra/`。

| API / model | 实际语义 |
|---|---|
| `MaintenanceCoordinator.state` | 只读 StateFlow，phase、generation、activePermits、blockedReason；不持久化 |
| `withOperation(generation = state.value.generation, block)` | OPEN 下同一 admission mutex 原子校验状态/token+登记，再锁外工作；返回 body 结果或传播异常 |
| `withNestedOperation(permit, block)` | 显式复用已登记 operation；同一许可、独立计数，不重新 OPEN 准入，不因 DRAINING 死锁；跨 coordinator / 已封口 / 已结束许可失败 |
| `OperationPermit.release()` | 幂等封口，不允许后续 nested 登记；不提前释放仍运行主体/子登记的计数。正常 wrapper 自行在 finally 封口/完成 |
| `withExclusive(timeoutMillis, block)` | 原子 OPEN→DRAINING，拒新许可/第二 owner；锁外 bounded await，登记归零后才 EXCLUSIVE；timeout/cancel 不调用 body、不返回成功 |
| `ExclusivePermit.markUncertain(reason)` | 只允许当前 EXCLUSIVE owner 调用；必须在未来不确定资源效果之前调用，BLOCKED 不自动回 OPEN |
| `MaintenanceGeneration` | opaque identity token、number 仅诊断；不能凭同号跨 owner/代复用；成功取得 EXCLUSIVE 并 unchanged 退出时换代 |

`coroutineScope` 的实际完成（包含 structured children）之后，NonCancellable finally 才完成登记；必要补偿必须放在该 scope 内。显式登记的 detached 子任务即使比根主体晚结束仍计数；未登记的异步任务没有写入许可，捕获 token 不等于 admission。没有 CoroutineContext 权限继承机制。

DRAINING 时旧许可可完成正常工作和补偿，但不能以它开启新的操作 cohort。旧根主体结束后许可封口，迟到 callback 不能复用；未来 writer 接线必须显式保留/校验旧 generation，不能在 callback 中省略 token、重新读取当前 generation 来绕过隔离。

排空 timeout/cancel 在尚未进入 EXCLUSIVE 时可安全回 OPEN：没有资源切换，原登记工作仍被计数且 generation 不变。EXCLUSIVE 的正常/异常/取消退出只有 **unchanged resource** 合同才回 OPEN；若已标不确定则保持 BLOCKED。当前没有实际切换 API，没有 durable Journal，不能把内存状态用于宣称跨进程安全。所有 wait/body 在 admission mutex 外，无固定 sleep 判断静止。

## Strict preferences snapshot

新增独立 `StrictAppPreferencesReader`，由既有 `DefaultAppPreferencesRepository` 实现；原 `AppPreferencesRepository` 接口、UI Flow/fallback/setter 均不变，避免破坏原 fakes 和设置体验。

`readStrictSnapshot()` 使用同一个 constructor DataStore，单次 `data.first()` 同时派生两个视图：

- `PortableAppPreferencesSnapshot`：仅 last_destination、theme_id、dnd_enabled、cross_app_intervention_enabled；每项 `PreferenceSnapshotValue(value, present)`。成功读取但 key 不存在：Start / BLUE / false / false，present=false。已存在非法 enum/type 不采用默认；IO/CorruptionException/CancellationException 原错误传播。
- `OriginalAppPreferencesSnapshot.values`：全部原名称与类型，不删 unknown key，不补造缺失 key；八类 `StoredPreferenceValue` 为 Boolean、Float、Double、Int、Long、String、StringSet、ByteArray。Map/Set 新副本+unmodifiable view，ByteArray 构造和 getter 都 copy，不能通过源或返回视图改快照。

正常 Preferences.Key 按 name 判等，因此合法 Preferences.asMap 不会保存重复名称；没有为此添加反射 fixture。未知 value 类型/非字符串 Set 会 fail closed，但未另外构造绕过 SDK 的非法未知类型 fixture；四个正式 key 的可合法构造错误类型已覆盖。上述模型尚无导入、整组 edit、回滚、protobuf 操作或第二 DataStore；fake 成功不是文件耐久性证明。

## TDD and fresh verification

使用既有 JDK17/SDK/Gradle 和现有依赖；没有安装/升级依赖。所有 Gradle 调用 `--no-daemon`。本次实际执行：

| Stage | Command / scope | Actual result |
|---|---|---|
| Maintenance RED | `:app:testDebugUnitTest --tests '*MaintenanceCoordinatorTest'`，编译通过的 TODO API 壳 | 22 discovered / 22 expected failures（NotImplementedError）；在实现前执行，Gradle exit1 |
| Maintenance first GREEN | 同定向 | 22/22 PASS，exit0 |
| Strict RED | `:app:testDebugUnitTest --tests '*StrictAppPreferencesSnapshotTest'` | 17 discovered / 16 expected failures（13 reader TODO + 3 defensive-copy assertions）；旧 setter 兼容测试 PASS，exit1 |
| Combined first GREEN | 两类 `--tests` | 24+17 = 41/41 PASS（已补二次 drain/嵌套取消覆盖），exit0 |
| Initial full gate | 未过滤 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | 472/472 JVM，0 failure/error/skipped；lint/build PASS，exit0；不删除该中间证据 |
| Review coverage additions | EXCLUSIVE publication 取消、strict 明确 cancellation、等待首 emission 取消；仅加测试，生产实现不变 | 定向 25+19 = 44/44 PASS，0 failure/error/skipped，exit0 |
| Final full gate | 未过滤 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | **475/475 JVM**，0 failure/error/skipped；lintDebug PASS（0 errors / 9 existing warnings / 1 hint）；assembleDebug PASS（本次 UP-TO-DATE，未改生产实现），exit0 |

XML 实际汇总来自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`，lint 来自 `app/build/reports/lint-results-debug.xml`。最终测试数由一次完整未过滤运行取得，不用局部测试拼全量。RED 是未实现/防御复制行为的预期失败，不是生产旧 Bug 或环境失败；之后三条 coverage test 验证已正确的实现，没有编造另一次 RED。

Maintenance 25项覆盖 OPEN、新准入拒绝、两种 admission/drain 先后、嵌套、异常、取消及补偿等待、虚拟时间 timeout、多 writer、重复/提前封口、代际/owner隔离、双 exclusive（含 DRAINING 竞争）、安全/不确定结束、structured/detached children、迟到 callback、EXCLUSIVE 发布时取消、非法 timeout。EXCLUSIVE 发布取消注入点在 withTimeout 排空块内，取消先于主体进入；不单独归因为 coroutineScope guard，也不外推所有 OS 调度交错。

Strict 19项覆盖四偏好、全部/部分缺失、非法 destination/theme、四 key 错误类型、IO、corruption、非IO错误、两种取消、同一次 first emission、presence、unknown全部支持类型、源/视图不可变、旧 UI fallback/setter 兼容。

内部只读 review（不是 ChatGPT 独立验收）发现的取消覆盖备注已由三条补充测试覆盖；OS 多线程压力、Android 实际 DataStore 文件 durability 未执行，仍明确为能力证明边界。

## Unwired scope and safety blockers

**本轮既有 Writer 接线数量 = 0。不是“52入口已保护”，不是 App 全局屏障已生效。**

未接入：52公共写入口、49 DAO 写方法；Note debounce/autosave/flush；Image import/trash/move/delete及补偿；Camera外部 lease；Search自动 FTS repair；FGS factsScope/watchdog；DND/intervention owner；startup recovery；container close/rebind。未检查/锁定真实 Active Intent/Active Session/PENDING/cleanup；仅协议模型存在不能让后续 Backup 跳过这些门槛。

| ID | Part added here | Still unresolved |
|---|---|---|
| SB1 | 内存 admission/drain/permit/generation 的确定性协议测试 | 所有真实 Writer/异步补偿链、资源 reader/后台写入、实际排空/TOCTOU/迟到效果覆盖 |
| SB2 | strict read、presence、不可变完整 old snapshot/四项 portable | 整组原子偏好导入、全部原 key/absence 回滚、single-instance lifecycle、落盘 |
| SB3 | 仅记录 FileProvider pre-onCreate 审阅提示 | early bootstrap、old Room/DS/owner close/rebind、Provider/URI 隔离 |
| SB4 | 无 | DB/DS/images 一致快照、durable Journal、跨资源 publish/rollback、fsync/进程死亡故障 |
| SB5 | 无 | owned cleanup/quiescence 的确定结果；外部 camera/system 效果证明 |

五类 `ARCHITECTURE_SAFETY_BLOCKER` 均未解除。SG6 codec/archive/relations/FTS/staging/space/SAF security validation 也未实现。本轮没有 BackupService、RestoreService 或生产 Journal。

## Files, data and inherited boundaries

新增：

- `app/src/main/java/com/guanyi/mirra/domain/maintenance/MaintenanceCoordinator.kt`
- `app/src/main/java/com/guanyi/mirra/data/preferences/StrictAppPreferencesSnapshot.kt`
- 对应两个 `app/src/test/...` JVM 文件
- 本 checkpoint

修改：`AppPreferencesRepository.kt` 仅增加 strict reader 接口/转发；`docs/CURRENT_STATE.md`、`docs/plans/MIRRA_PHASE_4C_BACKUP_SAFETY_CONTRACT.md`。没有修改 DECISIONS、PRODUCT_SPEC 或 Master Plan。

实际 Room `version = 4`；`app/schemas` 1–4、`data/local` Entity/DAO/Migrations/Database 与授权 base Git diff 为空。v4 Schema **file SHA-256**：`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`（不是 Room identityHash）。无 Entity/Column/Index/Migration、Manifest/permission、Gradle/dependency/resources 变化。

Phase 1–3 / 4A / 4B、Start/Intent/Session/Closeout、Monitoring/READY/FGS/DND、Recovery/Stable/Deep、ReadingRecord/Phase2 Analytics 均不变。没有读取/替换/导出/恢复用户数据库、偏好 protobuf 或图片，没有操作设备。

## NOT RUN and next boundary

connected / AVD / ADB / 实体机 / 覆盖安装 / 冷启动运行态 smoke：**NOT RUN**，本授权只要求 JVM/lint/build；历史 connected 不作为本轮执行。

API23–36 full matrix、full OEM/physical compatibility、TalkBack、release/Play、真实断电、人工系统时钟修改继续 NOT RUN；不外推历史 API37结果，一加13T日常反馈不是发布兼容性PASS。真实 Backup/Restore、Journal/crash rollback、Android 文件 durability、OS多线程压力/长时间 soak 均 NOT RUN。

原实现交付时下一步为等待独立审阅和单独授权；独立审阅现已完成，4C-1B 仍需单独明确授权。不自动进入 4C-1B，不生成/恢复任何用户数据。

原实现交付标记：`PHASE_4C_1A_FOUNDATION_AWAITING_REVIEW`。

## Independent Acceptance and Formal Freeze

2026-10-08：用户确认 ChatGPT 已完成 Phase 4C-1A 独立代码与证据审阅，正式结论为 `REVIEW_COMPLETE — PASS WITH NOTES`。Phase 4C-1A — **accepted / frozen — PASS WITH NOTES**。

- Accepted Implementation + Tests：`8e50545417f8854caee366b70426b9cf56058ae0`。
- Accepted Validation Documentation：`bf5e423536b93623eaec1e8cf03261879c3154a1`。validation SHA 不代替 implementation SHA；本次 Freeze 为独立纯文档提交。
- 冻结对象仅为 MaintenanceCoordinator 基础协议：OPEN / DRAINING / EXCLUSIVE / BLOCKED、Operation Permit、显式 Nested Operation、同一同步边界准入与排空、结构化子任务完成后释放登记、重复 release 不提前结束登记、Cancellation / Exception、Generation identity 隔离、不确定保持 BLOCKED。
- Strict Preferences Foundation：同一 DataStore 实例、单次严格读取、四项 Portable Preferences 的 Value + Presence、完整 Original Preferences、未知 Key 与类型保留、防御性复制、读取失败直接传播、原 UI fallback 不变。

### PASS WITH NOTES — retained, not resolved

1. 52 个现有公共 Writer 接线数量仍为 **0**；不能称 App-wide Maintenance Barrier 已实现。
2. 异步回调必须携带原 Generation 或有效 Permit，不能通过默认读取最新 Generation 绕过代际隔离。
3. 当前 EXCLUSIVE 仅适用于尚未执行资源切换的基础协议；实际切换前必须建立持久化 Journal 与安全切换协议。
4. DataStore 尚未实现整组原子导入、完整原始偏好回滚及实例生命周期证明。
5. SB1–SB5 均未完全解除；上文未接线清单与阻塞表继续保留。
6. JVM / Fake 测试不证明 Android 文件持久性、系统服务静止或真实断电安全。
7. connected、AVD、真机均为 NOT RUN；其余未执行项目和历史失败继续保留，不外推为 PASS。

只引用已接受的实现/验证执行证据：新增 targeted JVM **44/44 PASS**，完整 JVM **475/475 PASS**，0 failure/error/skipped；lintDebug **0 errors / 9 existing warnings / 1 hint**；assembleDebug **PASS**。本次纯文档冻结没有重新运行测试、Gradle / AVD / 设备，也没有新增 PASS 数字。

Room 仍为 **v4**；Schema 1–4 与 Migration 1→2→3→4 不变。v4 Schema **文件 SHA-256** 为 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`，不得与 Room identityHash 混淆。

Phase 4C-1A 正式冻结；**4C-1B 未开始，未实现完整备份或恢复**。本次不修改生产代码、Tests、Schema、Migration、Manifest、Gradle、依赖、资源或其他冻结业务。

`PHASE_4C_1A_ACCEPTED_FROZEN`
