# Current State

更新日期：2026-10-09

## Current Stage — Phase 4E

**Phase 4E — complete / awaiting independent review, not accepted/frozen.** Branch `codex/phase-4e-v1-final-validation`, parent `4066b898620b375303a4c1fb27e4bea64caae808`. Implementation + tests: `bc2efd27f8b2e8a53f56102b4c031c2acdfaaa1d`. The separate validation commit/remote equality is reported in the final handoff. No Phase 4 replanning, main merge, release or V2 work; no physical device operation or existing-AVD reset.

Phase 4A/4B remain accepted/frozen. The user reports independent acceptance **REVIEW_COMPLETE — PASS WITH NOTES** for both 4C and 4D; this does not independently accept 4E. Room v4, Schema 1–4, Migration and formal Backup Format v1 remain unchanged.

The [single V1 checkpoint](checkpoints/2026-10-09-phase-4e-v1-final-validation.md) and [sanitized evidence index](evidence/phase4e/README.md) retain RED history, two interrupted full attempts, corrections, actual data protection and final limits.

- Delivered actual installed learning/Note/image/Topic/search/record journeys, offline official SAF full-backup/restore and JSON/CSV save/cancel; original raw facts, IDs, JPEG bytes, four portable preference values/presence and FTS verified. Real Recovery 91,260 ms and Stable Start 123,005 ms, FULL/PARTIAL/NONE and abnormal recovery were observed without changing frozen rules.
- Reproduced and fixed two narrow V1 regressions: undisclosed/abandoned backup candidate resource retention, and legitimate pre-3D NORMAL/ACTIVE/null-boundary history incorrectly rejected by backup validation. Frozen runtime ownership, Journal selection, schema, learning, closeout, monitoring, DND, metrics and export rules remain unchanged.
- Final-source full JVM **607/607 PASS**, zero failure/error/skip. Fresh complete unfiltered connected **435 discovered / 426 actual PASS / 9 opt-in assumptions / 0 actual failure / error / unfinished**, 16m02s. All eight platform DND/intervention methods actually passed; the nine opt-in methods have separate safe specialized execution, not extra PASS added to the full total. Lint **0 errors / 18 warnings / 1 hint** includes the new nonfunctional lifecycle `EmptySuperCall` warning; both APK builds passed.
- Old retained 2 GiB API37 AVD owns installed SAF/learning/process/performance evidence. New dedicated API37 AVD owns the complete gate: generated runtime 4,096 MB RAM / 4 cores / SwiftShader / Vulkan disabled (3,072 MB launch request was adjusted by emulator). Fresh success does not establish the native renderer root cause or erase either incomplete attempt.
- Both original baselines were restored/verified through the production service. Permissions/network/display restored; no active learning/PENDING/FGS/overlay/intervention/current active owned rule or unresolved Journal; both AVD disks retained and environments closed. Final cover install preserved 14 measured current private-file hashes. Normal COLD 7,374 ms and offline COLD 3,186 ms / three main pages / Data Management/Back passed; smoke's preference changes were separately protected and restored.
- Performance measured 10,001-record cohorts/keyset history and 1,000 long notes/16 JPEGs formal round-trip. ALL medians remain O(N) scalar samples; memory is sampled/endpoint process observation, not peak or arbitrary-scale/OEM guarantee. Actual system transports, release/Play, API23–36, physical/OEM matrix, TalkBack, real power loss/manual clock remain NOT RUN. Manual Deep Focus waiting and separate actual controlled-service-stop/query-gap-near-closeout were not executed this phase. Semantic layouts and actual 320dp/font2 Data Management are not a full six-viewport pixel matrix.
- Final local Debug APK: `build/deliveries/v1/Mirra-v1-debug-20261009.apk`, **17,473,097 bytes**, SHA-256 `185A1635DC81C1B1538116F891ED1F929E20188B39786E272CA4907BE9502F4F`. Not committed/released. Room v4, schemas 1–4/migrations and Backup Format v1 unchanged; compiled automatic-backup restrictions verified, transport execution NOT RUN.

**MIRRA_V1_FINAL_VALIDATION_COMPLETE_AWAITING_REVIEW.** Stop at independent ChatGPT review, not V2 or self-declared acceptance/freeze.

## Inherited Phase 4D delivery and independent review

**Phase 4D — delivered / independent review PASS WITH NOTES (user reported).** Branch `codex/phase-4d-export-android-backup`, parent `62ce659029fb2269902a07145c8cdc87379016f5`, final validation `4066b898620b375303a4c1fb27e4bea64caae808`. The following execution record is retained as 4D history, not re-executed 4E evidence. Mine → 数据管理 exposes JSON and CSV ZIP export with private-data confirmation and SAF. 4E is separately authorized above; main merge and release remain prohibited.

Implementation + tests HEAD: `5a5cbde268791d828f70a4126a872aaead455aca` (`feat(data): add readable exports and safe android backup policy`). Validation documentation is a separate subsequent commit; its SHA and verified local/remote equality are reported in the delivery report, not substituted for implementation HEAD.

- Readable exports reuse the frozen 4C consistent/validated snapshot, project the 12 authoritative tables through explicit field whitelists, exclude JPEG bytes/private paths/runtime ownership, and stream rows. JSON preserves original text/null/zero; CSV documents its spreadsheet-safe text prefix and null convention. Neither is a restore input.
- Automatic raw-data extraction is disabled with `allowBackup=false`, literal legacy `fullBackupContent=false`, and whole-domain cloud/D2D/cross-platform exclusions. No BackupAgent, permission, dependency or cloud service is added. Actual transports/OEM behavior are not claimed from rule parsing.
- Current actual 4D evidence: post-review full JVM **607/607**, zero failure/error/skipped; single unfiltered connected **418 discovered / 404 actual PASS / 14 unmet assumptions / 0 actual assertion failures or errors**. All 21 new Android cases actually passed, including 3 compiled-policy checks. XML encodes the 14 assumptions in raw failure text nodes, not business failures or 418/418 PASS. Lint **0 errors / 17 warnings / 1 hint**, debug/test APK builds PASS. Dedicated API37 offline official JSON/CSV SAF save/cancel and prior-process export cleanup passed; 12 tables / 105 exported fields / 13 links compared, source facts/JPEG/preferences unchanged. Official 4C backup/restore and exact original baseline recovery passed with five actual opt-in fixture invocations. Post-suite cold start / three main pages / empty baseline / no active learning or journal/service passed; see the [4D checkpoint](checkpoints/2026-10-09-phase-4d-export-android-backup.md) and [evidence index](evidence/phase4d/README.md).
- Delivered Debug APK: `build/deliveries/phase4d/Mirra-phase4d-debug-20261009.apk`, 17,473,097 bytes, SHA-256 `82E27709B45D9AC4FD4E76FE8F9046A9A110BF2893B90C6E1251DAD0F50A375B`; not committed or released. Cover installation preserved 19 current files, not historical retention. Network restored, physical devices untouched.
- Room **v4**, schemas 1–4/migrations unchanged; v4 schema file hash remains `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`. API23–36/OEM/physical/TalkBack/release/real power loss/manual clock and system transport remain NOT RUN.

## Inherited Phase 4C delivery and independent review

The user reports ChatGPT independent review of Phase 4C as **REVIEW_COMPLETE — PASS WITH NOTES**, authorizing 4D from final validation `62ce659029fb2269902a07145c8cdc87379016f5`. The following delivery/execution record is retained as its historical evidence, not re-executed 4D evidence.

**Phase 4C — delivered / independent review PASS WITH NOTES (user reported).** Implementation and tests: `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f`, from freeze `3579cb74f327b6e243771285d901f639452bcd07`; final validation `62ce659029fb2269902a07145c8cdc87379016f5`. No 4C safety flow is reopened by Phase 4D.

**Inherited Module:** Phase 4C Full Backup & Restore. Final corrected-APK bootstrap, installed offline round-trip, full regression, cover-install, smoke and quiescence checks were complete. The [single delivery checkpoint](checkpoints/2026-10-08-phase-4c-full-backup.md) and [evidence index](evidence/phase4c/README.md) retain the implementation, RED history and evidence boundaries. Validation SHA is not substituted for implementation SHA.

### Current implemented capabilities

- Mine → **数据管理** → create complete backup → Android SAF save → select/validate backup → separate full-replacement confirmation → safe restore → reopen the selected application generation. Privacy/error/cancel UI is present; no account, automatic upload or encryption feature is added.
- Captured resource-generation admission covers the audited 52 public writers, pending editor freeze/flush, Camera leases, search repair, startup and runtime work. Retired writer/reader owners cannot reopen a new generation; activity compositions/ViewModels, Room and the single DataStore owner are closed before switching.
- Format v1 contains the 12 authoritative Room tables, referenced JPEG bytes and the four portable preferences with value/presence. It rejects unsupported packages/relations/schema/hash/ZIP limits, rebuilds derived FTS and excludes runtime ownership. No live SQLite main-file copy is presented as a consistent snapshot.
- Staging, immutable complete OLD/NEW safety resources, checksummed durable Restore Journal, rollback and Application-before-storage recovery are implemented. Uncertain selection remains fail closed; pointer absence is not treated as proof of rollback. FileProvider checks the early gate too.
- Active Intent / Session / PENDING / unresolved owned cleanup refuses backup or restore without automatically ending learning. Room stays **v4**, schemas 1–4 and Migration 1→2→3→4 unchanged. V4 schema **file SHA-256** remains `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`, not Room identityHash.

### Current actual evidence and final gate

- Actual dedicated API37 installed SAF round-trip completed: export/cancel, synthetic mutation, import/cancel, package validation, confirmed full replacement, all 12-table columns/IDs, JPEG bytes, portable preference value/presence and search checked. Original installed baseline restoration passed and only fixture-owned cache was removed.
- Sandbox 11 external prepare → force-stop → cold-start → assert cycles: **22/22 actual PASS**. Final corrected APK production Application bootstrap at precommit images-published → OLD and COMMITTED → NEW: **4/4 actual invocations PASS again**, followed by verified original-resource restoration. Final offline installed SAF round-trip and original-baseline cleanup: **5/5 actual invocations PASS**. These are actual process tests, not physical power-loss proof.
- Final post-bootstrap/cache-correction full JVM **553/553 PASS**, zero failure/error/skipped. Earlier 542/542 is retained as intermediate history, not the final production count. Targeted snapshot Android 14/14, archive JVM 13/13, text-budget JVM 4/4, direct Android 24/24 and final real restored-image pixel test 1/1 retain their specific execution scope.
- Final unfiltered connected: **397 discovered / 383 actual executed and PASS / 14 unmet assumptions / 0 actual business assertion failures or errors**, 19m28s / Gradle exit 0. XML has 14 assumption failure nodes and zero native skipped nodes; assumptions are not PASS. Nine opt-in methods (four historical, five 4C) and five permission prerequisites are separate. The five 4C opt-in methods were actually executed in the installed/sandbox journeys above; the four historical opt-in and five permission cases remain NOT RUN in this full run. Intermediate 396/382/14 and interrupted attempts remain historical, not final evidence.
- Final post-correction lint/build passed: 0 errors / 15 warnings / 1 hint and both debug/test APKs built; the six added warnings are recorded, not relabeled historical. The prior execution-session/environment reset interrupted a connected attempt without complete XML; it is not PASS or an identified business failure.
- Final cover-install PASS: 16 measured current DB/files hashes unchanged before launch. Normal/offline cold start and Start–Knowledge–Mine were observed; original network settings restored and read back as 0/1/1. Post-connected cold start and actual Data Management preparation → CreateDocument → cancellation returned normally, proving the formal active-state/runtime guard admitted the quiet state. Readback: no pending Journal, monitoring service, Mirra Overlay, active notification or owned-rule record; no ANR since the final AVD boot.
- Final APK: `build/deliverables/Mirra-Phase4C-debug.apk`, 17,258,080 bytes, SHA-256 `877E5E521C363E8EEF719F4C75B158FA336E3CABEF2A584CFC8ED1180548AA35`. Implementation/test commit `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f`; this validation-document commit and final push/local-remote/clean result are separately identified in the delivery report.
- API23–36, full OEM / physical compatibility, OnePlus 13T, TalkBack, release / Play, real hardware power loss and manual system-clock changes remain **NOT RUN**. No user physical device is operated; API37 cannot upgrade these boundaries.

## Inherited Phase 4C-0 / 1A foundation — historical snapshot only

The following 4C-0 / 1A facts and limitations describe their original audit/freeze, not current full-feature implementation or current authorization. Their lack of writer integration/backup/recovery at that time is retained as history; the full-feature delta and remaining final gate are above.

Phase 4C-0 — **accepted safety audit / PASS WITH NOTES**。ChatGPT 独立源码与安全合同审阅已完成；审计文档 HEAD 为 `7499ba50cc775d801711eecae38801099f6c66f9`，继承 Phase 4B Freeze `cd13953cb3ac132ee64493fe688a3ac0f3cf8e74`。这只是安全审计接受，不是 Backup / Restore 功能接受。**该审计基线当时** SB1–SB5 五类 `ARCHITECTURE_SAFETY_BLOCKER` 尚未解除；新增 FileProvider 早于 Application.onCreate 初始化的启动门禁审阅提示。详见 [安全合同](plans/MIRRA_PHASE_4C_BACKUP_SAFETY_CONTRACT.md) 与本轮完整功能 checkpoint 的解除证据，不把历史未接入结论冒充当前源码状态。

当前分支 `codex/phase-4c-full-backup`。Phase 4C-1A — **accepted / frozen — PASS WITH NOTES**。用户已确认 ChatGPT 独立代码与证据审阅完成，结论为 `REVIEW_COMPLETE — PASS WITH NOTES`。Accepted Implementation + Tests `8e50545417f8854caee366b70426b9cf56058ae0`；Accepted Validation Documentation `bf5e423536b93623eaec1e8cf03261879c3154a1`，两者职责分开；4C-0 acceptance docs `a14c073c2febc239dfce55e8c4a556550653e0a9`。

1A 当时的冻结对象仅为 MaintenanceCoordinator 基础协议（OPEN/DRAINING/EXCLUSIVE/BLOCKED、Operation Permit、显式 Nested Operation、同一同步边界准入/排空、结构化子任务完成后释放登记、重复 release 不提前结束登记、取消/异常、generation identity、不确定保持 BLOCKED）和 Strict Preferences Foundation（同一 DataStore、单次严格读取、四项 portable 的 value+presence、完整 original/未知 key 和类型、防御复制、读取失败传播、原 UI fallback 不变）。**1A 当时**没有生产 UI EXCLUSIVE 入口，没有任何全 App Writer 接线、备份生成、资源替换或 Restore/Journal；那次不是完整 Backup/Restore 功能接受，不代表本轮仍未实现。

已接受的实现/验证轮次执行证据：新增定向 JVM 44/44（Maintenance 25 + Strict Preferences 19）；最终完整未过滤 JVM 475/475、0 failure/error/skipped。lintDebug PASS：0 errors / 9 existing warnings / 1 hint；assembleDebug PASS。此前 TDD RED（22 coordination failures、17 preferences / 16 failures）及中间 472/472 full GREEN 保留，最终数字不拼接定向运行。本次 Acceptance Freeze 仅同步文档，不重新运行测试、Gradle、AVD 或设备，不产生新的 PASS 数字。完整证据和未接线清单见 [4C-1A checkpoint](checkpoints/2026-10-08-phase-4c-1a-maintenance-foundation.md)。

### Phase 4C-1A PASS WITH NOTES — retained historical limits at 1A

以下均为 **1A 当时**的边界。当前实现与新增验证见顶部，不删除历史记录，也不拿历史 1A PASS 代替本轮验证。

1. 1A 时 52 个现有公共 Writer 接线数量为 **0**，不能称当时 App-wide Maintenance Barrier 已实现。
2. 异步回调必须携带原 generation 或有效 permit；不能通过默认读取最新 generation 绕过代际隔离。
3. 当前 EXCLUSIVE 仅适用于尚未执行资源切换的基础协议；实际切换前必须建立持久化 Journal 与安全切换协议。
4. DataStore 尚无整组原子导入、完整原始偏好回滚及实例生命周期证明。
5. SB1–SB5 均未完全解除，包含完整写链、Provider 启动门禁、多资源持久化及 owned cleanup 证明。
6. JVM / fake 测试不证明 Android 文件持久性、系统服务静止或真实断电安全，也不是完整全局屏障证明。
7. connected / AVD / ADB / 真机 / 覆盖安装 / 运行态 smoke 均 NOT RUN；不引用历史设备结果冒充本轮执行。

Room v4 / Schema 1–4 / Migration 1→2→3→4 和 Phase 1–3、4A、4B 冻结语义继续保持。v4 Schema **文件 SHA-256** 为 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`，不是 Room identityHash。1A 冻结时后续接线与完整功能尚未授权/实现；**该等待已由本轮完整 Phase 4C 开发授权替代**，不再要求单独授权 4C-1B。

以下 4B / 4A 内容保留为历史冻结基线，过去“4C 未开始”描述指对应历史轮次，不覆盖上面的当前授权状态。

## Inherited Phase 4B Freeze

Phase 4B — accepted / frozen — PASS WITH NOTES。用户已确认 ChatGPT 独立代码、失败历史、fresh AVD 与最终执行证据审查完成，结论为 `REVIEW_COMPLETE — PASS WITH NOTES`；2026-10-07 的执行分类 `VALIDATION_RECOVERED` 保留，不替代独立验收结论。Task 1–3 的只读、同书有效阅读节奏变化提示保持不变。分支 `codex/phase-4b-descriptive-insights`，父冻结 `a57a7807b7acd0e1d7b8e49f29ef84d639a471b1`。执行合同见 [4B plan](plans/MIRRA_PHASE_4B_DESCRIPTIVE_INSIGHTS_PLAN.md)，包括失败历史与冻结备注的证据见 [4B checkpoint](checkpoints/2026-10-07-phase-4b-descriptive-insights.md)。Room v4，4A/Phase 2/3D 冻结语义保持；Phase 4C 尚未开始，等待单独明确授权。下文 4A 的证据与 PASS WITH NOTES 继续保留为历史冻结基线。

- Phase 4B Accepted production HEAD：`5cbff38853c069b02772b6062d5a7e96a1f9fb13`。
- Phase 4B Accepted test-only stabilization HEAD：`f000ca03d5eb03646d76f199fe2cb899979dfe68`。
- Phase 4B Accepted validation HEAD：`2f1b271e6b958e0cc9b21153e168c91a970ac500`。Test-only、validation 和本次文档 Freeze 均不代替 production SHA。
- 本次 Acceptance Freeze 只同步文档，没有重新执行 Gradle / JVM / connected / AVD / ADB / 真机，没有新的测试 PASS 数字。

Phase 4A — accepted / frozen — PASS WITH NOTES。用户已确认 ChatGPT 独立代码与证据审查完成，正式结论为 `REVIEW_COMPLETE — PASS WITH NOTES`。Planning Freeze 为 `cc5180762d9f735754ce3a5b0759089aefe6dcc9`，4A 功能分支为 `codex/phase-4a-trends-foundation`。4B 已完成独立验收并冻结；4C–4E 未开始。4B 当前合同已冻结 3 次候选、最多 10 次基线、30/90 自然日、30/60min、0.70/1.30 与三场同向规则，不反写历史 Master Plan 当时的 PROPOSED 状态。

- Accepted production HEAD：`e799997002d7c8d4dbb833c7452d194e8bad5d9f`。
- Accepted validation HEAD：`614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693`。Task 5 是验证提交，不代替最后一个生产代码 SHA。
- 该历史 4A Acceptance Freeze 只同步文档，不修改实现、不重新运行 JVM / connected / Gradle / AVD / 真机，不产生新的测试 PASS 数字；本轮 4B 的实际执行证据单独记录。

## Inherited Phase 4B Execution Evidence

Phase 4B｜Descriptive Insights 已独立验收并正式冻结，状态为 accepted / frozen — PASS WITH NOTES。唯一新增能力为同一本书的有效阅读节奏持续变化描述，放在既有 Knowledge → Learning Item Detail；不新增页面、不改变 Start/Mine、不推断原因、主观能力或阅读质量。冻结的样本、加权速度及 0.70/1.30 三场同向规则见 4B plan 与 checkpoint；不新增通知、Overlay、badge、push、AI、评分、排名、streak、持久化或 dismissal 状态。主观 User State / 情绪 / 疲劳 / 精力 / 环境采集与相关性仍为 `DEFERRED BY PRODUCT DECISION / Post-V1 candidate`，不是 DONE；没有 UserStateSnapshot 或 Schema v5。本轮依据用户已完成的独立审查做纯文档冻结，不修改 production / tests / config。

已接受的 2026-10-07 执行证据：专用 `Mirra_API_37_Phase4B_Final`（Android 17 / API37 / Google APIs x86_64，实际 build `CE2A.260420.019`），fresh userdata，不导入旧 DB/marker/snapshot。首次 4 轮健康观察实际跨度 72.102s；最终 full 前后 boot1、AM/PM/WM/Power、SystemUI、MainActivity resolve 正常，system_server 未重启，last ANR 为空。全量 JVM 431/431 PASS；lintDebug 0errors/9existingwarnings/1hint，assembleDebug / assembleDebugAndroidTest PASS。三个指定方法各 3/3 独立实际 PASS；4B focused JVM 57/57、Room 8/8、Compose 10/10。唯一实际完整 connected：351 discovered / 342 actual PASS / 9 unmet assumptions（4 opt-in + 5 permission prerequisites，均 NOT RUN）/ 0 actual failure / 0 error / 0 unfinished，351 个唯一 start/finish，Gradle exit0，host 1046.447s。原始 XML 把 assumptions 写成 9 failure nodes、0 errors/0 skipped；runner 为 0 failed / 0 ignored，另记 9 assumptions，不是 351/351 PASS。独立 review 与本次文档 Freeze 均未重跑这些检查。

历史首轮 complete FAIL、72-entry incomplete retry、后续 Forensics Fresh Full stale-title assertion、旧 AVD/QEMU 消失、test-only stabilization 和 collector 误分类均保留在 checkpoint。2026-10-07 的 host 参数拆分导致 Gradle task-selection 失败也保留：0 Android tests started；修正参数引用后才开始上述唯一实际 full，不是失败后 rerun until green。新 full 不抹除历史失败，也不证明旧失败均由环境造成。旧 AVD 未修复、wipe、删除或继续跑 Gate；该验证轮次没有新的源码修改、权限授权、实体设备操作或旧安装数据保留 hash 证明。

## Inherited Phase 4A Freeze

Phase 4A｜Trends Foundation 已正式冻结：Mine 保持原最近 7 天摘要，增加“查看趋势”和“阅读记录”轻入口；Start 页面、顶层导航、Mirra Blue 和品牌不变。Start 按 Intent.createdAt 统计 CONVERTED/(CONVERTED+ABANDONED+TIMEOUT)，开放 Intent 排除，后续 Session 结果不反向修改已开始事实；Intent→Session latency 与 Stable Start 独立，`stableStartedAt == null` 只表示未确认，不提供 Stable success score/rate。Maintain 按 Session.endedAt 并复用冻结 Validator，仅 COMPLETE_TRUSTED 聚合 effective focus、实际 Deep Focus、分心次数及时长，可信 0 与 unavailable 分开。Recover 同样按 Session.endedAt，Primary 仅 DISTRACTION 来源，区分 SUCCESS / INTERRUPTED / UNKNOWN，UNKNOWN 不进入失败分母；Break/Allowance 返回不混入，局部已证实的恢复不被后续失监抹掉。支持 7/30/90 本地自然日与 ALL，ALL 无前期比较；没有 Dashboard、chart、Score、ranking、streak、AI judgement、Insights、主观状态、Backup 或 Export。

全局阅读记录使用 pageSize 50、`(endedAt DESC, id DESC)` keyset 单 JOIN（51 条 lookahead），复用既有 ReadingRecord 详情，不建立第二套 Session Detail；保留 NORMAL / ABNORMAL / PARTIAL / NONE / legacy ended Session。趋势每批最多 800 个 Session IDs，批量 Context/Segment/Event，无 per-session N+1；不是每批 Segment/Event 最多 800 行。10,001 Intent + 10,001 ended Session 的最终读取为 1,770/1,737/1,746ms、每轮 65 business SELECT；DAO 非主线程断言通过。当前证据不要求新增索引；精确中位数保留 O(N) 标量样本，不能宣称 constant-space 或全历史查询在任意规模均快速。

已接受的实现/验证轮次执行证据：全量 JVM 374/374 PASS；完整未过滤 connected 333 discovered / 324 actual PASS / 9 unmet assumptions / 0 actual business assertion failures，新增 4A 39 项全部实际 PASS。9 项是 4 opt-in fixture（该验证轮次 NOT RUN）与 5 未获权限的平台用例（该验证轮次 NOT RUN），不是 333/333 PASS。原始 XML 将这 9 项记为 failures=9、errors=0、skipped=0；runner 为 0 failed / 0 ignored，计数口径分开记录。lint 0 errors / 9 existing warnings / 1 hint；assembleDebug 与 assembleDebugAndroidTest PASS。本次文档冻结没有重新执行这些检查。

专用 API37 AVD 实际覆盖安装、首次启动前 18 个当前文件 hash 不变、普通/断网冷启动、Start/Knowledge/Mine/Trends/空 History 以及两条系统 Back 返回 Mine 已确认。离线时 active default network=none；网络恢复原 0/1/1（airplane/Wi-Fi/mobile data），未授权新权限、未操作实体机。非空 History→既有详情→History→Mine 由真实内存 Room + Compose 验证；额外 ADB 手工建记录未完成，已取消，不能冒称人工非空闭环 PASS。

Room v4 / Schema 1–4 / Migration 1→2→3→4 未变化；没有 Entity / Column / Index migration，没有 Schema v5。v4 Schema 文件 SHA-256 为 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`，不是 Room identityHash。Phase 2 / 3A–3D / Closeout Revision / DND / Monitoring / READY / FGS / Recovery / Stable / Deep / Effective / Validator 冻结语义未修改。完整证据、APK、截图分类、失败历史与未测边界见 [4A checkpoint](checkpoints/2026-10-07-phase-4a-trends-foundation.md) 和 [evidence index](evidence/phase4a/README.md)。evidence index 保留 Task 5 当时的执行快照；正式状态以本次 Acceptance Freeze 为准。

### PASS WITH NOTES — retained, not resolved

- ALL 精确 median 保留 O(N) scalar samples；raw Session/Context/Segment/Event 按最多 800 个 Session IDs 分批处理，当前 10k evidence 可接受，不宣称 constant-space。
- History 固定 `snapshotNow` 不是跨多页长期 SQLite snapshot。迟到的旧 endedAt durable fact 可能需要重新进入页面才完整可见，不宣称 immutable multi-page snapshot。
- EXPLAIN 仍有 cohort scan / temporary sort；10,001+10,001 实测未证明需新增 index，更大规模需重新 measure，不默认修改 Room v4。
- 累计 AVD gfxinfo 有明显 jank observation，不是隔离 Trends benchmark；不归因于数据库或 4A，不宣称 zero-jank。
- 额外人工 nonempty installed journey 仍 NOT RUN；真实 in-memory Room + Compose 的 nonempty History→existing ReadingRecord detail 证据保留。
- 320/360/411dp + fontScale 1/2 有 semantic Compose 验证，但没有 clean 320/font2 pixel screenshot，不冒称有。
- API23–36 / full OEM / physical matrix / TalkBack / release-Play / real power loss / manual clock change 继续 NOT RUN，API37 AVD 不外推。
- 历史 Launcher/SystemUI ANR、Recovery >120s observation 和数据保留限制原样保留，不改写为已解决。

## Inherited Frozen Baseline

Phase 3D-1 Closeout Revision 已通过用户独立 review，结论为 `PASS WITH NOTES`，状态为 `accepted / frozen`；Accepted implementation HEAD 为 `085543ffd0b5d03859565577a8ee277036fc50f9`。本次仅冻结这份限定修订，不表述为整个 Phase 3D 本轮重新冻结。分支为 `codex/phase-3d-closeout-v2`，实际继承基线为 `c39f135e09d48e27573dbb331c0bf57a9e075499`（上一轮品牌本地提交）。只修订最终确认后的 Note flush 与 A / owned cleanup / B 顺序。既有 3D-2 / 3D-3 / 3D-4 实现全部继承，不重新开发；本次纯文档冻结不修改代码、不重跑 Gradle / AVD、不进入后续 Phase，不合并 main、不创建 release、不改新品牌资源或 Mirra Blue。

历史 Phase 3D 已正式完成并冻结，继续作为本轮继承基线。Phase 3D-4 已通过用户独立验收，Accepted validation HEAD 为 `bf1082983859c1f24d096e3bb49d4bcb92afc34e`；最终 test-only durable flush correction 为 `768127951c57e01aa09ed16de4c2e5e9ee929b4c`，精确生产父 / Phase 3D-3 Freeze 为 `80ece95cf24918627e57a57bb6a6d94c253528b0`。3D-4 没有 production fix。历史 Phase 3D Formal Freeze 为 `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18`，不将本轮限定修订冒称新的整个 Phase 3D 冻结。

冻结能力包括继承的 monitored Session foundation、SessionSegment timeline、monitoring coverage、risk App confirmation、Break / Allowance / Recovery / Stable Start / Deep Focus rules、DND protection 与 safe cross-app intervention，以及 Closeout A/B、crash / PENDING recovery、effective focus metrics / reading speed、统一 ReadingRecord（Summary / History / Search）、真实本地存储保留与离线 Debug 交付。这是产品行为与个人试用开发基线冻结，不是发布级 Android 全设备或 OEM compatibility complete；Module 3B 的未测设备矩阵不因本次冻结升级为 PASS。

历史 Phase 3D 用户独立 review 已核对最终 branch diff、test-only correction、fresh AVD final gate、v5 preservation chain 与最终 APK evidence，未发现新的阻断缺陷。该独立 review 与 Phase 3D 纯文档冻结均没有重新运行 Gradle / AVD；本文历史 Phase 3D 的自动化、平台、覆盖安装和离线结果来自已提交的 Codex 执行证据，详见 [fresh AVD final gate](evidence/phase3d-final/fresh-avd-final-gate.md) 与 [Independent Acceptance](checkpoints/2026-10-04-module-3d-final.md#independent-acceptance)。上一轮 Brand Refresh 实际执行的验证另列于下节，不代替本轮 Closeout 验证。

3D-3 Accepted implementation HEAD `54a9e2bdda28da80028aa5313ef243da593e976b` 与 3D-2 Freeze `4e15076265ad393c85bd5f0e08916f6e886e4be1`的既有实现保持冻结；3D-1 Freeze `ba480d9b61f0b71879b07113ec32fc840a97ddab`保留为历史记录，仅旧 flush / cleanup 顺序由本轮限定修订代替。历史 Recovery >120sec 异常仍为“Observed once / Not reproduced in targeted diagnostic / Root cause unresolved / No production fix”，不宣称 FIXED、ROOT CAUSE RESOLVED 或 AVD ISSUE；后续真实成功 91.566 / 90.827 / 90.858 / 91.006 / 91.661sec 不覆盖历史风险，本轮未重跑 Recovery 诊断。

历史 Phase 3D-1 已独立验收并正式冻结，Accepted implementation HEAD 为 `c07fbabf050d6d5ac3aeae0677d5e5abf14f4465`，包括 Clock Rollback Correction、Tasks 1–6 与 Acceptance Patch；本轮仅在新授权范围内修订顺序。Phase 2、Module 3A、Mirra Blue / Visual Parity 与整个 Module 3C 保持原冻结语义。Module 3B 是个人试用开发基线，不等于发布级设备能力验收完成。Room v4 / schemas 1–4 不变。API 23–36 full matrix、OEM / 实体设备完整矩阵、TalkBack、release / Play、真实硬件断电与真实系统时钟人工修改继续 `NOT RUN`，API 37 AVD 结果不外推；一加 13T 反馈仍仅为个人试用。

## Phase 3D-1 Closeout Revision — Accepted / Frozen

- 用户独立验收结论：`PASS WITH NOTES`；冻结实现为 `085543ffd0b5d03859565577a8ee277036fc50f9`。历史 Phase 3D 整体冻结记录继续保留；本次只同步该限定修订的正式接受状态。
- 首击只打开可编辑、可取消的结束确认，不强制保存、重试页码或采样结束时间；普通 500ms autosave 与 Confirming 时的生命周期 flush 必须继续。
- 最终确认先防止重复操作，再等待真实保存结果并 flush 最新 Note；失败保持 ACTIVE、草稿和临时结束页，结束页不得提前落库。成功 flush 并复核最新持久进度 / totalPages 后才唯一采时。
- 冻结顺序：首次点击结束 → 只打开可编辑确认层 → 最终确认 → 保存最新 Note → 保存成功后复核页码 → 唯一采样结束时间 → Stage A：ACTIVE → PENDING，并关闭最后 Segment → 本 Session 内存行为立即失效 → 锁外 best-effort 清理 owned intervention / monitoring / DND → Stage B：NORMAL + COMPLETED → 后续结果页。沿用既有串行边界，锁内先 settle，再执行 A 与本场内存失效；不将 Android 清理放入事实锁。
- PENDING 在业务上已结束，不可恢复阅读；`closeoutStartedAt` 是唯一结束边界，complete / retry 不得重新采时。PENDING 冷启动继续正常结算，不得改成 ABNORMAL；未进入 PENDING 的遗留 ACTIVE 仍按既有 ABNORMAL recovery。PENDING 后禁止新 Break / Allowance / Recovery / Segment / 学习事实；cleanup 失败不得撤销 Session 已结束事实。
- 已提交的实现轮次执行证据：完整 JVM 327/327；一次未过滤 connected 294 discovered，285 实际 PASS / 9 unmet assumptions / 0 实际业务断言失败，Gradle exit 0；不是 294/294 PASS。lint 0 errors / 9 existing warnings / 1 hint，assembleDebug PASS。9 项为 4 opt-in fixture + 5 permission platform cases，不能计作业务 PASS；HTML 原始 failures=9、skipped=0，均为 assumption body，与 runner 的 0 failed / 0 ignored 表现分开记录。本次文档冻结没有重新运行 Gradle / AVD。范围和完整失败历史见 [revision contract](plans/MIRRA_PHASE_3D_1_CLOSEOUT_REVISION.md) 和 [本轮 checkpoint](checkpoints/2026-10-07-phase-3d-1-closeout-revision.md)。
- API37 AVD 覆盖安装成功，安装前后当前 17 个数据库/本地文件 hash 完全一致；只能证明当前文件，不能证明首次框架移除安装前的旧数据。冷启动命令返回 COLD / ok、Start 可见，但 Launcher / SystemUI ANR 弹窗阻碍额外人工烟测，因此该人工可操作性检查为 DEGRADED，不写完全 PASS。未发现该日志中的 Mirra fatal / Mirra ANR；未据此修生产代码，也不宣称所有历史 UI 失败根因已确认。
- 执行风险：本轮首次 connected 漏传保留安装参数，测试框架收尾移除了专用 AVD 的目标安装，旧 AVD 数据/marker 不得宣称保留；后续运行明确传保留参数。不自动用旧备份回填，不操作实体设备；原历史证据仍保留。

## Mirra Brand Refresh v1

- 将旧绿色 Logo 替换为“静心之窗 / Inner Window”：暖米白底、炭黑拱门、简化门板、内侧纵深与门槛光路。SVG 源文件与运行时 VectorDrawable 对齐，不使用参考图截图或联网图片。
- Launcher 分离为 API23–25 矢量回退、API26+ adaptive background/foreground、API33+ 单色层；Splash 为极简静态系统启动画面。原通知 smallIcon 保留资源名并更新为透明白色轮廓，两处通知生产逻辑不改。Start / Mine 没有旧 Logo 图片，因此不新增图形。
- Mirra Blue / 业务 Kotlin / JVM tests / Room v4 / schemas 1–4 / Migration / 权限 / 依赖保持不变。实际执行 assembleDebug / assembleDebugAndroidTest / lintDebug，0 lint errors、9 existing warnings、1 hint；最终品牌专项 instrumented 单次 7/7、0 failed/error/skipped。四种 mask、32/64/128/256/512/1024px、单色与受控 day/night tint 检查通过。
- 专用 API37 AVD 覆盖安装前后、首次启动前，DB/WAL/preferences/JPEG/preservation marker 五类 SHA 完全一致；实际 Splash / Launcher light-dark / Start / Knowledge / Mine 检查通过，原系统 night=no 已恢复。没有操作实体设备或扩大权限；未重跑整套 Phase3 业务矩阵。测试导出失败与资源缓存失败保留在 [品牌 checkpoint](checkpoints/2026-10-06-mirra-brand-refresh-v1.md)。
- Debug APK：`build/deliverables/Mirra-BrandRefresh-v1-debug.apk`，15,993,492 bytes，SHA-256 `36E1B051AA705E8FD25C64143FC8186E360A4CF900C511CBE1026CACA2347F53`。APK 不入 Git。上一轮品牌任务只授权本地 commit，未 Push；交付后等待用户视觉验收，不自行正式冻结新品牌。本轮当前功能分支的 Push 授权另见 Git 节，不改写上一轮交付事实。

## Latest Fresh AVD Final Gate

- Legacy测试只去掉Summary“1条笔记”5000ms渲染deadline：确认前立即读取exact durable Note，最终确认后检查NORMAL/endedAt/nonactive、ReadingRecord.noteCount1及同一Note仍在。不加timeout/sleep/retry、不改生产。提交前四类19/19，保留其他Summary覆盖。
- 新AVD实际Android17/API37/qemu1，原API37 Google APIs/x86_64镜像，无旧snapshot/DB/marker导入。4轮健康检查、整个窗口67.650sec；full前后及最终lastanr均noANR sinceboot，无DeadSystem/transportabort/系统服务消失。旧AVD和所有失败历史保留，不推断旧timeout的生产根因。
- 唯一full8m21s/exit0；rawXML286tests/4failure/0error/0skipped，4failure均明确opt-in Assumption。实际282/282，0真实失败/未完成，原flush/完整Phase1闭环及所有非opt-in测试PASS。四opt-in不算PASS；不做第二full或targeted补绿。
- v5真实installed storage seed1/1 + candidateinstall-r + assert1/1；JPEG818bytes同path/SHA，pre/post full/install四偏好、riskhash与SEEDED marker字节完全相同。成功marker正常为VERIFIED_PREFERENCES_RESTORED，最终start/blue/false/false、risk0/原整表hash、active0/0/0。旧v4通过独立restore1/1仅恢复原偏好/risk，保持SEEDED/UNCONSUMED；不assert/reseed/补缺图。
- 全量后fresh JVM318/318、lint0errors/9existingwarnings/1hint、assembleDebug PASS。最终Debug APK16488770bytes，SHA `9C9B33337A8FB9A3969089308C28E8302AB285939DFBD47A4103CC7D48C68CD9`，0.1.0/code1，buildHEAD `768127951c57e01aa09ed16de4c2e5e9ee929b4c`。exact交付文件覆盖、普通/断网cold start及五类读取PASS；受控trusted记录截图不冒称真实FULL监测，原fixture/实际平台截图分类保留。
- 四权限精确恢复default/false/default/false及原POSTflags；网络1/1，平台残留0、ZenOFF。Room4/Schema1–4全hash不变，生产/JVM-test/Manifest/Gradle/Migration相对父Freeze0diff。临时helper/AVDguard适配已从源码及最终已安装testAPK移除，不提交。
- 本包已独立验收，Phase 3D-4 / 整个 Phase 3D 正式冻结。API23–36/OEM/fullphysical/TalkBack/releasePlay/真实断电/人工系统时钟/实际15minDeep/真实OSquery-gap仍NOT RUN；API37 AVD 结果不得外推。已有90秒Recovery/near-finish/PENDING实际证据独立引用，独立 review 与本次纯文档冻结不冒称重新执行。

## Earlier Same-userdata Revalidation and Mandatory Stop — retained history

以下为此前失败轮次的原记录；后续经单独授权完成精确v4恢复与fresh AVD gate，不能用新成功改写旧失败。

- 唯一未过滤 connected 实际35m32s/exit1，XML286个唯一且完整终态，raw286tests/5failure/0error/0skipped；语义281PASS+1真实ComposeTimeout+4明确opt-in assumptions，无空中断节点。`PhaseOneCorrectionTest.finishingImmediatelyFlushesDraft`在line130等“1 条笔记”再超时；另一历史LearningLoop实际PASS。满足 `FULL_SUITE_INTERMITTENT_CONFIRMED / ARCHITECTURE_REVIEW_REQUIRED`，没有targeted或第二full，没有用旧PASS拼齐。
- 服务检查没有发现新的DeadSystem/transportabort，但post-full实际系统弹窗和lastanr确认04:11:10UTC SystemUI input-dispatch ANR，早于04:12:58 full start；不能用服务healthy证明全程UI稳定，亦不能推断它就是超时根因。未点击系统Wait/Close、未二次reboot。
- 独立 `storage-isolation-v4` seed实际1/1/0assumption；JPEG818bytes同SHA，knowledge/night/true/true、风险整表hash及SEEDED marker字节始终相同。外部状态STOPPED_FULL_FAILURE/UNCONSUMED，不修改marker，不consume/reseed。v2FAILED、v3INTERRUPTED/UNCONSUMED及legacy缺图/行/marker均保留。
- Usage/Overlay default、DND false、POST false及原flags、网络1/1已恢复读回；Current ZenConfig ownrule STATE_FALSE、ZenOFF，ActiveSession/Intent/Segment0/0/0、FGS/Overlay/ID3002通知0。UI收尾被系统弹窗阻断，原theme/DND/cross偏好及v4模拟风险行尚未恢复；排除v4行的只读hash精确等于原一行hash。正式UI无BLUE setter和不可启动风险项删除入口；不原始改SQLite/DataStore或新建testhelper，等待单独授权。
- Fresh JVM/lint/assemble、v4 data_assert、最终 exact APK/覆盖/离线/人工视觉gate本轮NOT RUN；生产、app/src/test、androidTest、Manifest、Gradle、Schema/Migration相对执行基线0diff。Room4/Schema1–4全hash不变。只提交脱敏文档与受控AVD环境截图，不复开冻结核心或下一Phase。

## Earlier Phase 3D-4 Verification Evidence and Stop — retained history

- 没有生产修补：相对精确父 Freeze，`app/src/main`、app/src/test、Schema1–4、Migration、Manifest、Gradle和依赖不变。当前新增/修改只在androidTest及验证文档；image isolation patch独立12文件（新增4、修改8），新fixture继续真实installed storage，不隔离为fake。普通full四opt-in fixture默认不执行。
- 最新未过滤connected尝试退出1、5m34s；raw XML21tests/5failure/0error/0skipped，仅16已完成PASS、4明确opt-in assumptions、1ModuleTwoAFlowTest.createNoteDoesNotPersistBlankPlaceholder空failure（120.410s、无finished/断言栈）。系统多进程FATAL、DeadSystemException及activity service短暂消失支持环境/runtime中断，不定位根因或确诊blank-note业务断言错误。历史两个Phase1 timeout、DND3/channel5和主题full用例未到达；不重跑定向或第二次full。主题定向5/5、v2 cleanup1/1与v3 seed1/1分开记录，不拼完整PASS。
- 此前已接受的完整connected284 discovered /280 executed /280 passed /4opt-in assumptions、DND3/channel5实际PASS与六尺寸字号PASS保留为历史；此前两项Phase1 Summary超时在该full均PASS。旧275/271/269/2timeout/4assumptions及定向2/2也保留。318/318 JVM与lint/build仍为较早证据，最新interrupted full不以旧结果补齐；fresh JVM/lint/assemble/exact APK/offline/manual visual final gates本轮NOT RUN。
- 受控A后PENDING + 实际Force Stop/冷启动已证明原边界NORMAL/COMPLETED、无FGS复活；真实NONE页面覆盖第一次确认前强停→ABNORMAL、Break中直接结束、40→临时4→42/旧页Note35、低结束页拒绝、继续阅读/最终确认、Summary原地展开/History/Search同事实、结果停留超过75秒不增长。
- 原Task4缺失JPEG/ImageAsset/旧marker原样保留。v2原seed1/1/full/覆盖后JPEG818bytes同SHA及data_assert仅主题失败全部保留；marker永远FAILED_PREFERENCES_RESTORED且未改字节。按授权只用Repository移除v2模拟riskrow，整表hash精确还原。v3新seed1/1、pre/post partial-full JPEG/knowledge/night/true/true/risk/SEEDED marker完全一致，但不是完整full或覆盖保留PASS。按收尾授权恢复原偏好/移除v3模拟riskrow，marker保持SEEDED原字节、不consume/reseed；一次性helpers从源码及已安装test APK移除。
- 授权后raw平台8/8，0failure/error/skipped，原DND3/Overlay1/notification1实际进入断言。第一场真实FULL→Break→brief/confirmed risk→in-app→Allowance/一次延长→91.566sec Recovery→FOCUS→NORMAL/COMPLETED，DND RELEASED；真实FULL结果/时间线截图已保存。COMPLETED后的真实token URI/request replay不创建新学习事实，不冒充原PendingIntent.send或PENDING间隙回放。
- 历史Overlay之后>120sec Recovery异常保留，后续诊断90.827/90.858/91.006sec均成功且结论已接受，不授权生产修补。此前已提交的平台验证（`50d9a0bdf339c623169cb2f1e6f3924a7bc2631c`）中，真实FULL链再次91.661sec成功，无新的≥110sec异常；未宣称根因解决，本轮主题隔离未重新执行此闭环。
- 此前已提交的平台验证（`50d9a0bdf339c623169cb2f1e6f3924a7bc2631c`）中，真实Usage撤权和Diagnostics监测停止均先durable PARTIAL/UNMONITORED再NORMAL/COMPLETED，effective unavailable。实际Start→Preparation→FULL→页42到44/1Note→brief/confirmed risk→真实Notification点击→Allowance/一次延长→Recovery→FOCUS→正常Closeout→Summary原地展开/History/Search完成；结果≥32sec边界不增长，COMPLETED旧request不复活事实。本轮未重新执行这些平台场景。PENDING旧动作/Stage B故障仍单列自动化，真实OS query gap与15min Deep NOT RUN。
- 最新停止后四权限精确恢复读回（Usage/Overlay default、DND false、POST false及原flags）；原knowledge/blue/dnd=false/cross-app=false已恢复，网络1/1。v2/v3新增模拟riskrow均只经Repository精确移除，原一行与整表hash `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8`保留。Active Session/Intent/Segment0/0/0、FGS/Overlay/ID3002通知0，Mirra-owned rule STATE_FALSE、Zen OFF。未改失败/SEEDED marker，未候选覆盖/data_assert/新full，未修生产或操作实体机。
- 真实平台闭环里程碑已提交 `50d9a0bdf339c623169cb2f1e6f3924a7bc2631c`；恢复诊断基线 `d0da1061a7b706e25784e43fc700189564bbe980`。全部证据见最终checkpoint/evidence index；本包尚不具备“3D-4完成等待独立验收”资格，Phase3D未冻结。API23–36 / OEM / physical / TalkBack / release继续NOT RUN，实体机未操作。

## Frozen Phase 3D-3 Reading Records

- Source只读组合Session / Context / Segments / 历史App快照 / 非空Note count；固定5次读取，无逐段N+1。统一Projection复用冻结Validator，不修补旧历史或更改信任标准。
- Summary原地展开；书籍History与Search SESSION通过既有详情Route同源回看并返回原入口，不恢复Session。仅严格相邻的FOCUS/DEEP_FOCUS在展示层合为“阅读”，未知与异常记录不伪造有效时间，App名仅用历史快照或“风险 App”。
- 刚结束结果仅RELEASE_PENDING/RELEASE_FAILED弱提示并复用release retry/设置刷新；旧历史不持续DND提示，不apply或修改偏好。书籍详情按合格effective.window有效优先，只有窗口不足回退原Phase 2口径；读取错误明确显示，自然完成日期继续独立。
- Task 1–5独立提交分别为 `e0230c6` / `38c3983` / `a45ae22` / `31d1ff4` / `c230d44`；Task 6 / Accepted implementation HEAD 为 `54a9e2bdda28da80028aa5313ef243da593e976b`。完整SHA、RED/GREEN、Task 6证据与独立验收结论见 `docs/checkpoints/2026-10-04-module-3d-3.md`，不squash。
- 已提交的实现轮次执行证据：fresh完整JVM318/318、单次未过滤API37 connected268/268，均0 failure/error/skipped，DND平台3项与渠道5项实际执行。lint0 errors / 9 existing warnings / 1 hint，assembleDebug PASS；实际宽度320/360/411dp、fontScale1/2与≥48dp可达性已验证。用户独立review已复核最终implementation diff、Source / Projection / UI / Navigation / Effective Pace接线，未发现新的阻断缺陷。本次独立review及纯文档Freeze没有重新运行Gradle / AVD，不将已提交证据冒称本次执行。
- 专用AVD覆盖安装保留原测试数据；断网真实1→3页 / 1Note / NORMAL/NONE/COMPLETED通过结果→History→Search，同事实且无有效时间。闭合事实在Force Stop/冷启动后不变。7张受控fixture截图与3张真实离线截图分开标记，不把FULL fixture当监测或实体机证明。临时平台授权与网络已恢复。
- Debug产物 `build/deliverables/Mirra-3D3-debug.apk`，16488770 bytes，SHA-256 `68C89D5948E34E3EF48DE374CA2043B6B5D0EE43702E7D3B63E7A1CE3EF674E8`，不入Git。Room v4 / Schema1–4与所有冻结核心不变。用户独立验收已完成；内部只读review不冒充独立验收，DECISIONS不重复既有批准设计。

## Frozen Phase 3D-2 Effective Metrics

- 3D-1 Freeze / 精确父基线为 `ba480d9b61f0b71879b07113ec32fc840a97ddab`，实施分支 `codex/phase-3d-effective-metrics`。Task 1 `b8c0e42192b15cc2c811f9c7489870a8fae5239d`、Task 2 `0302f588e71759fcc0b26c59102cba1d2ec94380`、Task 3 `e2732c834b781fe0bb56b32e95b37574d5693bdb`、Task 4 / Accepted implementation HEAD `9b137ea51e824f59ab3480ed770c9dcf24da2d87`，不 squash。完整记录及独立验收结论见 `docs/checkpoints/2026-10-04-module-3d-2.md`。
- Validator 复用冻结的 SegmentTimelinePolicy，区分 SESSION_INELIGIBLE / MONITORING_INCOMPLETE / STRUCTURE_INVALID / COMPLETE_TRUSTED；可信零 Focus=0，不可用=null，FULL 不能覆盖 gap / lostAt / UNMONITORED。仅 FOCUS + DEEP_FOCUS 1:1。
- Source 先查指定书籍和时间范围的已结束 Session，800 IDs 分批读 Context / Segment，等待全部初值后组合；无 N+1、无 analytics cache。真实 Room SELECT 预算：1/40/800场=3条，801=5条，1601=7条，空源=1条，不丢事实。
- 7→14→30本地自然日，首个≥3场且有效时长≥30min；零页正有效时间保留分母。速度=总页/总有效时间；全部零页有有限速度0但无未来时间。PAUSED / COMPLETED 保留历史速度，不生成未来时间。新 effective 剩余时间用精确整数比值向上取整分钟，溢出与样本不足分开，不泄漏 NaN / Infinity。
- Phase 2 总时长、overall speed、普通剩余时间、calendar pace、自然日期与 confidence 均以固定全字段 fixture 验证不变；旧无段 Session 保留旧统计，天然可信的旧3C Session不按版本排除。不改写历史或补造 Focus，无 UI / Closeout / DND / Monitoring / READY / FGS / Schema 修改。
- 已验收实现轮次执行证据：fresh 完整未过滤 JVM 297/297、API37 单次完整 connected 245/245，均0 failure/error/skipped；含新 Source7、旧查询4、Migration5、DND平台3、渠道平台5。lintDebug 0 errors / 9 existing warnings / 1 hint，assembleDebug PASS。内部只读审查发现的55→56分钟取整问题已新测试 RED→GREEN；失败、夹具与平台前提读取历史保留。用户独立 review 已复核最终实现 diff、Validator / Repository / Effective service / compatibility tests，未发现新的阻断缺陷，正式批准冻结。独立 review 与本次纯文档 Freeze 均没有重新运行 Gradle / AVD，不将已提交执行证据冒称本次测试。

## Frozen Phase 3D-1 Closeout

以下保留历史验收与冻结事实。旧“首击 await Note/page 再确认”和“锁内 A / 失效 / B 后再 cleanup”仅在本轮限定授权内被上方修订顺序代替，原失败历史、测试证据和其余冻结规则不删除、不重写为本轮结果。

- 规划基线为 `758b3bc163ccc7a187f85594d1c04fbda5ecf7d4`，实施分支 `codex/phase-3d-closeout`。Tasks 1–6 与 Acceptance Patch 已完成独立验收，Accepted implementation HEAD 为 `c07fbabf050d6d5ac3aeae0677d5e5abf14f4465`；完整规则、文件清单、失败历史、执行证据与正式冻结结论见 `docs/checkpoints/2026-10-04-module-3d-1.md`。
- A 事务固定最终确认的时间/页码、关闭活动 Segment、保留 occupied slot 并写 PENDING；B 使用原快照原子完成 NORMAL、书籍进度、既有 Summary、FTS 与 COMPLETED。低结束页明确拒绝。PENDING 永久不可恢复，retry 不重新采时。零时长末段删除，不伪造 1ms。
- ACTIVE 资格在相关事务内复核；PENDING 拒绝晚到页码、监测、行为、里程碑、receipt 与 DND apply，release 元数据仍可写。结束经既有 Controller mutex settle → A → 纯内存失效 → B，再锁外独立有限清理渠道/监测/DND；旧 callback 不借用新 Session。取消回查 durable 状态并传播。
- 冷启动先完成原 PENDING，B 失败保留快照、只读重试、无 FGS 重启，不改写为 ABNORMAL。普通遗留 ACTIVE 仍异常恢复。UI 先 await Note/page 实际保存再确认；最终确认仅采一次 ClockSample，重建显示冻结时间，外部请求按 ID 一次消费。
- Acceptance Patch 已冻结：Stage A 同一事务复核 event / heartbeat / Stable Start 等 durable learning fact boundary，旧确认不得早于已落库学习事实；不将 DND/cleanup 的 updatedAt 当学习边界，更新健康查询本身不制造失监。Backward proof 不得越过 durable loss 后的新事实。页码保存失败可不改数字直接重试，失败仍阻止确认；Saving 显示“正在保存本次阅读…”，不在 A durable 前声称阅读已结束。
- 前置 rollback 的原始 RED 与 225 JVM / 63 定向 Room 历史证据保留在 `docs/checkpoints/2026-10-04-clock-rollback-correction.md`，不冒充本轮全量结果。3D-1 唯一 backward 例外需同一 binding 的真实 sample 对和匹配 durable loss，在 A 中重核；generic PARTIAL 不作证据。DND ownership / READY / FGS Usage 架构、Coverage、StateMachine、3C 阈值、Phase 2 analytics 与 Schema 未变化。
- 已验收最终执行证据：Acceptance Patch 完整未过滤 JVM 270/270、API37 单次完整 connected 238/238，均 0 failure/error/skipped；DND 3 项、渠道 5 项实际执行。lintDebug 0 errors / 9 existing warnings / 1 hint，assembleDebug PASS。原始 264/228 gate、所有 RED、首次 connected 外部请求重复消费失败与平台前提缺失历史保留于 checkpoint。独立 review 及本次纯文档冻结没有重新运行 Gradle / AVD，不将已提交执行证据冒称本轮测试。
- 原 3D-1 gate 的 AVD 正常确认/继续阅读/低结束页拒绝、旧页 Note、覆盖安装保留数据、断网冷启动与普通 ACTIVE 强停→ABNORMAL 已实际执行。PENDING 使用真实 Room 文件关闭/重开及失败重试测试，不冒充断电。该 gate 交付 APK `build/deliverables/Mirra-3D1-debug.apk`，16179372 bytes，SHA-256 `24D3FC56C833A432923F91DC5104B33BC02C1F8AE7EA1EA37835042690EB338B`，是 Acceptance Patch 前的个人 Debug 试用包，不冒称最终冻结 HEAD 的新交付包，不入 Git。
- 专用测试框架此前默认清理安装导致不能宣称旧测试数据保留；最终 connected 使用命令行 leaveApksInstalledAfterRun=true，之后创建测试书/Note并单独覆盖安装比较确认保留。不曾手工 uninstall/pm clear/wipe。专用 AVD 的临时平台授权和网络设置已恢复，无实体机操作。

## Verified Completed

- 继承 Phase 4C 已交付用户功能：Mine 平面数据管理入口、正式 SAF 创建/保存/验证、完整替换确认、staging / Journal / 回滚 / early bootstrap / fail-closed UI；实际合成数据 SAF 往返与原 baseline 还原已有执行证据。用户报告独立审阅 PASS WITH NOTES；4D 不重开其安全流程，也不把本轮内部复核写成新的独立验收。
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
- API 23–36、OEM、实体设备、TalkBack 与发行环境未测项继续 `NOT RUN`；API 37 专用 AOSP AVD 结果不外推为其他平台 PASS。3C-3 冻结核心保持不变；3D-1 已正式冻结，后续阶段仍需单独授权。

## Frozen Module 3C-4 Baseline / Module 3C Closure

- 起点 `6384cc634b080ac9554ac20cd4c95b9b5e13a262`，分支 `codex/phase-3c-final-validation`。执行协议与结果见 `docs/checkpoints/2026-10-04-module-3c-final.md`；六类实际 AVD 截图见 `docs/evidence/module-3c-final/`。
- 只有一个 ViewModel 输入修补：40→42 逐字编辑中间的 4 不再立即回填40，但较小值仍不保存，旧页 Note 不推进阅读进度。新 JVM 与 Compose/真实 Room 测试约束该行为；DND、Monitoring/READY、Coverage、行为核心、Schema 与 Migration 无改动。
- 同一最终代码的完整未过滤 JVM 223/223、connected 178/178，均 0 failure/error/skipped；DND 平台3项与渠道平台5项实际执行。lint 0 errors / 9 existing warnings / 1 hint，assembleDebug 通过。
- API37 实际闭环：风险短/长访、应用内与 Overlay 四操作、通知真实点击/POSTED≠SHOWN、渠道/权限降级、Allowance 一次延长与风险 B、Break、连续91.753秒 Recovery、Usage撤权/主动停止、强停 ABNORMAL/PARTIAL/UNMONITORED与 DND释放、旧URI不复活、320dp/font2、360dp/411dp可达性。
- 最终 APK 覆盖安装与断网冷启动后，专用书籍/Note/图片/正常Session/42页进度/Caption/偏好/风险选择保留，图片与搜索可读。最终 Room v4 hash 仍 `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`；schemas 1–4 不变。
- Debug APK：`build/deliverables/Mirra-3C4-debug.apk`，15874132 bytes，SHA-256 `4271EBE0BEF656098FA83F0ADAD9781574E113AB2FA4153ED7ED00F4D79087EE`；appId `com.guanyi.mirra`，0.1.0/code1。APK 不入 Git，仅个人试用，不是 release。
- C01–C24 在自动化/API37约定范围内通过；C25 是个人安装包与 checklist 交付，新版本一加13T实际反馈仍 NOT RUN，未自动操作实体机。个人试用反馈未取得不阻止本次开发基线冻结，也不升级为 OEM compatibility PASS。
- 用户已独立核对远程分支、实现提交 `da461423dbbe17f0a875373d7a4c38f2a1830788`、实际 diff、测试与已提交执行证据，未发现新的阻塞缺陷。Module 3C-4 与整个 Module 3C 正式冻结；3C-1、Sampling Acceptance Patch、3C-2、3C-3 原冻结状态不变。
- 本次独立验收及纯文档冻结同步没有重新运行 Gradle / AVD；上述 JVM 223/223、connected 178/178、lint/build 与 AVD 结果来自已提交执行证据。所有历史 RED、测试夹具故障与环境失败记录继续保留在原 checkpoint；API 23–36 / OEM / 实体设备完整矩阵 / TalkBack / release 未测项保持 `NOT RUN`。

## Frozen Phase 2 Baseline

### Phase 2｜笔记与阅读体验完善

- Module 2A：Learning Item 生命周期与 Note 完整化（已正式验收并冻结）。
- Module 2B：图片、App-owned Files 与 Schema v1 → v2 已正式验收并冻结。
- Module 2C：轻量 Topic、FTS4 搜索与 Schema v2 → v3（已正式验收并冻结）。
- Module 2D：阅读分析、剩余阅读时间与自然完成预测（已正式验收并冻结）。
- Start Experience Correction：六级行动首页、首次创建主线事务与 First Action 补齐（已正式验收并冻结）。
- Theme System v1：Theme Foundation 与 Mirra Blue 已正式验收并冻结；Mono / Night 全页面迁移、可视化选择与三主题全量验收属于后续独立阶段。

## Pending

- Phase 4D 整包功能与验证已交付，等待 ChatGPT 独立审阅；不自行 accepted/frozen。4C 已由用户报告独立审阅 PASS WITH NOTES，其安全流程保持不变。4E 尚未授权，不自动进入。
- Phase 4A / 4B、4C-0 audit / 1A foundation、Phase 3D / Closeout Revision 保持既有独立接受状态与 PASS WITH NOTES；Brand Refresh v1 不冒称独立冻结。旧 v4 精确恢复偏好/risk、STOPPED_FULL_FAILURE / UNCONSUMED / SEEDED、v2 FAILED_PREFERENCES_RESTORED、v3 INTERRUPTED / UNCONSUMED 和缺图历史继续保留，不等同于旧安装数据现在仍完整，也不被本轮安全恢复测试删除或重新分类。
- Phase 3｜Module 3B Task 6 仍待最终独立验收。Task Manager Stop、reboot、完整 risk/lock/revocation 矩阵、API 23/29/33/34/35 与实体/OEM 设备继续为 `NOT RUN`，不用 API 37 AVD 结果代替。

## Known Risks / Unknowns

- Phase 4C：最终 JVM 553/553、connected 397 discovered / 383 actual PASS / 14 assumptions、安装 smoke 和真实恢复已验证；542 JVM / 396 connected 是中间源码证据，不替代最终验证。Journal 跨 DB/偏好/images 没有硬件级跨资源原子事务，安全来自关闭所有 owner、完整安全副本、durable decision、验证与 fail closed。SAF readback 不保证 transport 永久保存；测试受控低空间不是实测物理磁盘耗尽；真实断电/OEM/全 API 矩阵仍 NOT RUN。更早 private cleanup orphan 的边界和后续 cache/bootstrap 修补验证见本轮 checkpoint，不宣称未测故障均已解决。
- Phase 4B PASS WITH NOTES：历史 complete FAIL、incomplete retry、stale-title failure 和旧 AVD 退出均保留；fresh full clean Gate 不确认这些旧事件的单一根因，也不证明旧根因全部已解决。既有 test-only async readiness fix 保留原业务断言，本次 Freeze 不改源码/断言/超时。90日源仍加载 O(N) 匹配事实、不是只读13条或 constant-space；10001样本的1737/1086/1705ms和27查询只是早期专用 AVD 合成证据，不能外推任意规模/OEM/production SLA。新人工安装数据hash链、新4B pixel截图、4 opt-in fixture、5权限平台用例和真机兼容性在最终验证轮次均未执行；fresh AVD/覆盖安装成功不能证明旧安装用户数据保留。API23–36/full OEM/physical matrix/TalkBack/release-Play 继续 NOT RUN；一加日常反馈不是兼容性 PASS。
- Phase 4A：Intent/Session cohort 页仍需扫描/临时排序；大于当前 10k+ 测量规模的性能未证明。Heap delta 为 GC 敏感的采样差，不是峰值内存。AVD 首次进入相关累计 gfxinfo 为 18 frames / 17 janky（并非隔离趋势页的测量），冷启动 5,249/6,274ms；不能将 DAO 不在主线程执行外推为 UI 零卡顿，未确认必要索引或生产根因。初次 ANR 遮挡、空白/错帧截图、null UI bridge 与未完成的额外 ADB 建记录均保留；可确认截图与语义测试分别记录。
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
- 首版中途重新授权不自动恢复当前 Session 监测，保持 PARTIAL。3C-2 已接入真实页面可见性/屏幕证据及应用内干预；不使用缓存前台 package 冒充长时间稳定，FULL 不等于全程 Focus。3C-3 渠道已正式冻结；通知 POSTED 与 Overlay attach 均不能外推为用户已读，OEM 可进一步限制通道。3D-2已增加纯派生有效指标，3D-3结果/历史/pace UI已独立验收并正式冻结。批量独立Flow非跨表事务快照，依赖冻结的结束后学习事实不可变；未来若改变此前提须重新审查一致性。

## Git

- Current branch: `codex/phase-4d-export-android-backup`；4C 最终验证父 HEAD `62ce659029fb2269902a07145c8cdc87379016f5`。本轮是 JSON/CSV 与系统备份策略实现/验证交付，**不是 Acceptance Freeze**；implementation / validation SHA 和最终 remote/local equality/clean tree 在交付报告中分别列示。不 merge main、不 force push、不 release。
- Historical 4C delivery branch: `codex/phase-4c-full-backup`；父 `3579cb74f327b6e243771285d901f639452bcd07` → implementation/tests `1c1648c7ce98aae4d1e45ca343fde26b8dc0798f` → validation `62ce659029fb2269902a07145c8cdc87379016f5`。
- Historical 4C foundation chain: 4B Freeze `cd13953cb3ac132ee64493fe688a3ac0f3cf8e74` → 4C-0 audit `7499ba50cc775d801711eecae38801099f6c66f9` → audit acceptance docs `a14c073c2febc239dfce55e8c4a556550653e0a9` → 4C-1A Accepted Implementation + Tests `8e50545417f8854caee366b70426b9cf56058ae0` → Accepted Validation Documentation `bf5e423536b93623eaec1e8cf03261879c3154a1` → inherited 1A freeze `3579cb74f327b6e243771285d901f639452bcd07`。这些 SHA 保留各自历史职责，不代替本轮 Full Backup/Restore。
- Historical Phase 4B branch: `codex/phase-4b-descriptive-insights`；父 Phase 4A Freeze 为 `a57a7807b7acd0e1d7b8e49f29ef84d639a471b1`。Task 1 / 2 / 3 提交为 `f46b5a7679e52c084a55e72739f27af3343639cf` / `fd7b86d93a2d22fa90de646536540ba1f39c87fe` / `5cbff38853c069b02772b6062d5a7e96a1f9fb13`；Accepted production HEAD 为 Task 3。Accepted test-only stabilization SHA 为 `f000ca03d5eb03646d76f199fe2cb899979dfe68`，Accepted validation SHA 为 `2f1b271e6b958e0cc9b21153e168c91a970ac500`，两者已独立 Push，不代替 production SHA。Phase4B accepted/frozen — PASS WITH NOTES；当时仅 `docs(insights): freeze phase 4b` 文档提交，精确 Freeze SHA/local-remote 核对以交付报告与 Git 为准。当时不合并 main、不发布、不进入 4C。
- Historical Phase 4A branch: `codex/phase-4a-trends-foundation`；父 Planning Freeze 为 `cc5180762d9f735754ce3a5b0759089aefe6dcc9`。Task 1–4 分别为 `e533c04e3562087b7ab58270d83cf4bddea6a5d8` / `76a53540c0365dba4106d81a7c3fd2f41c4a55b8` / `8e692354506e0caaa306fed0229f709f775a2eb8` / `e799997002d7c8d4dbb833c7452d194e8bad5d9f`。Accepted production HEAD 为 Task 4 `e799997002d7c8d4dbb833c7452d194e8bad5d9f`；Accepted validation HEAD 为 Task 5 `614c61b89fb3cb0d7aa3bac491e5cb80f4aa1693`，两者职责分开。4A Freeze 为 `a57a7807b7acd0e1d7b8e49f29ef84d639a471b1`；当时没有进入 4B，其后本轮独立授权不改写当时事实。
- Historical Closeout Revision branch: `codex/phase-3d-closeout-v2`；实际起点为品牌本地提交 `c39f135e09d48e27573dbb331c0bf57a9e075499`，继承历史 Phase 3D Formal Freeze `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18` 及其后续合法实现。Accepted implementation HEAD 为 `085543ffd0b5d03859565577a8ee277036fc50f9`，保持正式冻结；不由本次 4A 重新冻结。
- Phase 3D-4 Accepted validation HEAD: `bf1082983859c1f24d096e3bb49d4bcb92afc34e`。其纯文档 Formal Freeze 为 `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18`，不替代该验收 HEAD；上一轮品牌提交不改变历史 Phase 3D 冻结结论，本轮不重新开发 3D-4。
- Historical Phase 3D fresh-gate build/test HEAD: `768127951c57e01aa09ed16de4c2e5e9ee929b4c`（`test(session): verify draft flush at durable closeout boundary`），仅PhaseOneCorrectionTest.kt；GREEN19/19后独立Push。后续仅验证文档/受控截图提交，无production fix、未合并main或发布；该历史轮次的local/remote SHA见对应交付报告。
- Earlier same-userdata source HEAD: `b3b4c89d6ced6011babce93cd73bf49e2967115d`；原286完整节点/281PASS/1timeout/4assumptions与旧ANR保留为失败历史，不混写为当前fresh PASS。旧v4已获授权精确恢复prefs/risk但未consume。
- AndroidTest image storage isolation patch: `9cfae55898ad6a1a21b0afb8a943a81599ad9ef8`（`test(storage): isolate android image fixtures`），定向GREEN后已独立Push；不含生产代码或验证文档。历史该轮失败gate及恢复边界保留在独立证据提交记录，不改写为当时3D-4 COMPLETE。
- AndroidTest theme exact-original restore patch: `5331661f24e0d1f01a89deeeaaeafbb3d6a3a891`（`test(theme): restore persisted preference after lifecycle test`），单文件定向5/5后独立Push；临时helpers未提交。历史该轮full中断/状态恢复证据单独保留，不改写为当时3D-4 COMPLETE。
- Frozen Phase 3D-3 accepted implementation HEAD: `54a9e2bdda28da80028aa5313ef243da593e976b`，已独立验收并正式冻结。精确父Freeze / Phase 3D-2 Freeze: `4e15076265ad393c85bd5f0e08916f6e886e4be1`。Task 1–6独立提交、实际Gate与证据见3D-3 checkpoint；正式文档Freeze为 `80ece95cf24918627e57a57bb6a6d94c253528b0`，不替代实现SHA。不合并main、不发布。
- Phase 3D-1 Freeze / Phase 3D-2 exact parent: `ba480d9b61f0b71879b07113ec32fc840a97ddab`。
- Frozen Phase 3D-2 accepted implementation HEAD: `9b137ea51e824f59ab3480ed770c9dcf24da2d87`，已独立验收并正式冻结；独立 Task 1–4 提交、验证与冻结结论见3D-2 checkpoint，不合并 main。纯文档 Freeze commit 另行报告，不替代该实现 SHA。
- Frozen Phase 3D-1 accepted implementation HEAD: `c07fbabf050d6d5ac3aeae0677d5e5abf14f4465`（`fix(focus): reject closeout before durable learning facts`），已独立验收并正式冻结。冻结文档提交另行报告，不替代该实现 SHA。
- Phase 3D-1 planning freeze base: `758b3bc163ccc7a187f85594d1c04fbda5ecf7d4`；accepted prerequisite `2d43e8875ed8dc4b93c0411661262909ca5dc56c`。Task 5 实现 `087f6dce67767d4c7e6941d75f11aec0f9455b74`；Tasks 1–4 与额外 ownership fence 的完整 SHA 见 3D-1 checkpoint。未合并 main。
- Frozen 3C-4 implementation commit: `da461423dbbe17f0a875373d7a4c38f2a1830788`（`feat(focus): finalize module 3c experience`），已由用户核对远程并正式验收；Module 3C 整体正式冻结，未合并 main。
- Frozen 3C-3 implementation commit: `b9843eb5c3879899e148792a6a1f252e4b6d573d`（`feat(focus): add safe cross-app intervention`），已由用户核对远程并正式验收；未合并 main。
- 3C-1 original implementation base: `61ed78f94125e7049c5d4ca73be1b16c5416043b`
- Frozen 3C-2 / Acceptance Patch implementation commit: `f275334e233298b872bb507a82dcd875ff3135d5`（`feat(focus): add in-app learning return experience`），已由用户核对远程并正式验收。
- Frozen Phase 3 planning base: `882d649cf600cd3f6f3b59d0be8a7911f3e42c70`
- Room Schema: v4

## Next Recommended Task

停在 **`PHASE_4D_COMPLETE_AWAITING_REVIEW`**，下一步仅为 ChatGPT 独立审阅本轮实现与实际证据；不自行 accepted/frozen，不进入 4E。冻结业务、Room v4 / schemas 1–4 与历史失败保留；API23–36 / OEM / physical / TalkBack / release-Play / 真实断电 / 手动时钟修改和系统 transport 继续 NOT RUN，一加反馈仍只属个人试用。
