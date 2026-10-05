# Phase 3D-4 — Final validation and personal-use delivery

日期：2026-10-05。状态：执行中，尚未最终独立验收；不表示 Phase 3D 已冻结。

## Contract and exact parent

- Parent / 3D-3 formal freeze: `80ece95cf24918627e57a57bb6a6d94c253528b0`.
- 3D-3 accepted implementation: `54a9e2bdda28da80028aa5313ef243da593e976b`.
- 3D-2 freeze: `4e15076265ad393c85bd5f0e08916f6e886e4be1`.
- 3D-1 freeze: `ba480d9b61f0b71879b07113ec32fc840a97ddab`.
- Branch: `codex/phase-3d-final-validation`，从精确 parent、干净工作区创建。远程 tracking ref 原因是窄 fetch 配置而缺席；已显式 fetch 并核对实际远程 SHA，没有改写远程分支。
- 本包默认只测试、受控 androidTest fixture、脱敏证据及 Debug APK；不重开冻结产品语义，不升级依赖。任何真实回归须单独 RED / root cause / minimal fix / GREEN commit；本记录不制造人为 RED。
- 禁止清库、卸载、wipe、改系统时间或操作实体手机。发布级未测环境继续 NOT RUN。

## Task 1 — Baseline APK, automation and schema

### APK saved before any 3D-4 test change or connected target install

在 parent 生产树完成 `assembleDebug`，退出 0 / BUILD SUCCESSFUL；然后保存真实产物：

- Path: `build/deliverables/Mirra-3D3-before-validation-debug.apk`（本地，不入 Git）。
- Source: `80ece95cf24918627e57a57bb6a6d94c253528b0`，其生产树即已验收 3D-3。
- Bytes: `16488770`.
- SHA-256: `68C89D5948E34E3EF48DE374CA2043B6B5D0EE43702E7D3B63E7A1CE3EF674E8`.
- applicationId: `com.guanyi.mirra`; versionName `0.1.0`; versionCode `1`; targetSdk `37`.
- Debug signing certificate SHA-256: `29ded26bea44f1afe4fe383a302e4b507423d16223469d7da3fe578d9a7f83b9`（仅公开证书指纹，不含签名材料）。

### Fresh run and actual prerequisites

- 专用环境：`Mirra_API_37` AOSP AVD，Android 17 / API37，qemu=1，boot_completed=1；只有已明确指定的 AVD transport。序列号仅用于本地命令，不写入证据。
- 初始没有 Active Session / Mirra FGS。已安装包 `0.1.0 / code1`；Wi-Fi / mobile data 初值 `1 / 1`。
- Usage Access default、Overlay default、DND access false、notification runtime permission false。没有自动授予权限；需显式授权的路径单独记录。缺前提不是平台 PASS。
- 初始 JVM full unfiltered fresh run：XML `318 total / 318 executed / 318 passed / 0 failure / 0 error / 0 skipped`；45 suites，实际执行不是沿用旧 checkpoint 数字。
- 初始 connected full unfiltered（冻结 test APK）：268 total，263 实际业务断言通过，5 项 `AssumptionViolatedException`（DND access 3、Overlay 1、notification 1 前提缺失）。Gradle / runner exit=0，但 AGP/UTP XML 原始计数是 `268 tests / 5 failures / 0 errors / 0 skipped`；这5个 failure 节点全是明确前提 assumption，不能改写原始 XML 或报告“268/268 clean PASS”。按执行性质记录为263 passed / 5 unmet-prerequisite NOT RUN，断言失败为0。
- 5项准确名称：`AndroidDndSystemTest.accessibleNonMirraRuleIsIgnored`、`controlledPolicyChangeIsRejected`、`ownedRuleIsReusableAndNeverChangesGlobalPolicy`；`AndroidInterventionChannelsTest.grantedOverlayAttachUpdateAndRepeatedRemoveAreSafe`、`grantedNotificationIsPostedButNeverFullScreen`。渠道其余3项实际执行；DND平台本次0项实际执行。不修改测试前提或依赖来掩盖缺授权。
- 初始 `lintDebug / assembleDebug` 命令退出0 / BUILD SUCCESSFUL；lint 0 errors / 9 existing warnings / 1 informational hint，原生产报告按相同输入复用，androidTest分析执行。构建产物仍为保存的同SHA APK。平台授权仍待用户明确确认；后续 granted run 与此历史分开保存。

### Schema seal

当前 `MirraDatabase.version = 4`；只有以下四份 schema。与 parent 比较生产 Entity / Migration / Schema 无 diff，不重新生成或修齐文件：

| Schema | SHA-256 |
| --- | --- |
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

## Task 2 — Controlled crash and boundary integration

新增 `ModuleThreeDCloseoutCrashFixtureTest`。只有明确的 `pending_prepare` / `pending_assert` 参数、专用 AVD 名称、API37 / ranchu 与真实 AVD 属性均匹配时才执行；默认明确 Assume，不在普通 full suite 留下 PENDING。使用真实持久 `mirra.db` 与 Repository，不新增生产故障入口，不清除既有数据。

本轮实际执行：

1. `pending_prepare` 定向 runner：1 executed / 1 passed / 0 failure / 0 skipped。创建专用书和 Note，持久页码42、Note页35；只执行 Stage A，不执行 Stage B。
2. prepare runner 结束后目标进程已经退出。只读检查仍为 `PENDING`，确认边界 `1791204072064`，请求页42，Session endedAt / endType 为空，coverage NONE。没有把 instrumentation 的退出写成硬件断电。
3. 显式 Force Stop，再正常启动 MainActivity：真实 `LaunchState=COLD / Status=ok`。在启动 assert instrumentation **之前**，只读检查已为 `COMPLETED / NORMAL`，endedAt仍为 `1791204072064`，请求页仍42，coverage NONE；没有监测 ServiceRecord。
4. `pending_assert` 再检查完整数据库事实：1 executed / 1 passed / 0 failure / 0 skipped。没有 Active Session / 活动 Segment，末段精确结束于原边界，Summary / FTS 已完成。

这是“受控 Stage A 故障窗口 + 实际冷启动”，不是硬件断电或真实 SQLite 磁盘失败。准备／断言通过 durable marker 跨进程交接，不依赖 JUnit 顺序，也不在 assert 中重新 seed。

新增 `ModuleThreeDFinalBoundaryTest`，实际定向执行2项 / 2 passed / 0 failure / 0 skipped：

- PENDING 与 COMPLETED 下 DND release 受控失败、重试：真实 Room / Workflow / DND Store / Controller，只有系统能力 fake；冻结 Session / Segment / coverage / 页码不变。Stage B 即使晚到仍使用原结束边界。
- 最终确认时真实六秒查询缺口：真实 Controller → Manager → Room，先落 PARTIAL / UNMONITORED 再结束。对实际持久事实使用冻结 Validator，得到 MONITORING_INCOMPLETE / effective=null，不为正常结束保住 FULL。

新 androidTest 首次编译有4处夹具类型／投影字段错误，修正仅在新测试文件，随后编译通过；不是生产回归 RED，也没有修改产品来迎合测试。既有完整 baseline 的 closeout / retry / clock / stale action 覆盖与新增执行证据分别记录。真实 Usage撤权、Service stop、跨应用旧 action 等权限相关人工路径尚未执行，不冒充专项平台 PASS。

### Existing matrix coverage, not interchangeable with actual UI

以下既有测试属于 Task1 的完整自动化；新增两项单独执行，不重复计数。内部只读覆盖审计不是用户独立验收。

| Requirement | Automated evidence | Boundary |
| --- | --- | --- |
| 七种 Segment / NONE 精确关闭 | `ModuleThreeDCloseoutRepositoryTest.beginCloseoutFreezesExactSampleAndReleasesOnlySegmentSlot`、`sameBoundaryCloseoutDeletesZeroDurationSegmentWithoutInventedMillis` | 受控类型矩阵，不表示真实等待 Deep 或真实形成每种行为 |
| B fault / retry / duplicate | `completeFailureRetainsPendingAndRetryUsesOriginalBoundary`、`duplicateBeginAndCompleteNeverRewriteBoundary`、`failedPendingRecoveryNeverBecomesAbnormalAndStillExpiresOldIntent` | 隔离 Room trigger 注入，不是磁盘故障 |
| backward clock / ordinary early boundary | `backwardClockJumpLossIsDurableBeforeCloseout`、`backwardClockJumpStillAllowsCloseoutAtLastDurableBoundary`、`finalSettlementBackwardClockUsesOnlyDurableLossBoundary`、`ordinaryBoundaryBeforeActiveSegmentStillFailsWithoutClockJumpEvidence`、`permissionLossCannotBorrowBackwardProof` | fake ClockSample + 真实 Room，不修改真实系统时钟 |
| A/B stale learning facts / receipts | `ModuleThreeDPendingGuardsTest.pendingRejectsEveryFocusWriteEntryWithOriginalReturnSemantics`、`pendingInvalidatesLateReceipts`；`InterventionNavigationTest.sessionEndingAfterSubmitRejectsConsumption` / `processRestartHasNoValidPrompt` | durable guard / 导航消费分层，不等同真实旧外部 PendingIntent 回放 |
| monitoring loss near finish | `realSixSecondGapSettlesBeforeCloseout`，新增 `lossAtFinalConfirmationNeverProducesTrustedFocus`，Controller 的 service-stop / durable-loss retry 测试 | 自动化真实 Room gap；真实系统撤权／停止服务尚未运行 |
| page / Note | `SessionFocusUiTest.editingCurrentPageAllowsPartialInputButNeverRollsBackRoomProgress`、`SessionCloseoutViewModelTest.partialPageInputDuringFinishKeepsDurablePageAndOldNote`、`SessionCloseoutUiTest.lowerEndPageIsExplicitlyRejectedThenNormalSummaryKeepsMonotonicProgress` | Compose / VM 自动化；本轮另走普通真实 UI |

## Task 4 — Actual cover installation, initial preservation proof

新增 androidTest-only `ModuleThreeDDataPreservationTest`：两项显式 `data_seed` / `data_assert` gate 默认 Assume；普通 full suite 只运行独立 UUID 数据库的 migration / reopen 用例。没有生产测试开关、清库或额外 Schema。

本轮执行顺序：确认闲置专用 AVD → 实际 `install -r` 保存的 parent APK（成功）→ raw instrumentation seed1/1 → 读取 durable marker → 实际 `install -r` 当前 Debug APK（成功）→ raw assertion1/1。没有 Gradle 在 seed/assert 之间隐式安装 target，没有 uninstall / pm clear / wipe。两份 APK 同为 `68C89D...F674E8`，因此明确是**冻结前后同 binary 覆盖保留**，不是新版本号或数据库迁移。

专用 UUID 图包含 Learning Item、两条 Note、生产导入的私有 JPEG 与 Caption、Topic / 两条关联、Risk App、正常 Closeout Session、旧无 Context / Segment Session、旧可信 3C-style Session、Segment、历史 risk snapshot、FTS 文档及四项逻辑 preference。只新增受控闭合旧形状记录，不改写既有历史，不冒充 READY / FULL 真实监测。包含字段值、ID、关系及文件内容校验，不只比较行数；FTS rowid 不作为业务真相。

- before / after-install / after-record-search-analytics-read aggregate：均为 `79019e05a56c729c137cb4eabed952f7e69652f1411e8d6cc8c538dcafa460fb`。
- JPEG内容 SHA-256：`df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`，实际解码及尺寸 / 相对路径 / metadata 均检查。
- 旧无段 Session 没有长出 Context / Segment / effective facts；可信旧3C形状允许派生 effective，业务与索引 / 图片在读取前后未变。
- 测试先校验 seed preference（knowledge / night / DND on / cross-app on）保留，再恢复原 knowledge / blue / DND off / cross-app off；没有借恢复让错误 preference 通过。恢复 hash `12311ba07fc3daf6152044c4b02fc96a61901e3c0443addb4bba3da2126bda95`；恢复后除 preference 外的业务 hash `dba3c67843ff9bcf74256921ea29b9890d2e85df18cef1e4ad9a696e72b1d0ae`。最终 durable marker 为 VERIFIED_PREFERENCES_RESTORED。
- 普通隔离 v3→v4 migration / reopen 定向实际执行1项 / 1 passed，保留 Phase2 overall 样本，五类 focus 表无补造。仅删除该新 UUID 隔离测试库，不触碰持久 mirra.db。与上述真实 APK cover install 证据分列。
- 内部源码审计修正测试 cleanup 的异常遮蔽：保留原断言异常，清理失败附加 suppressed，保证关闭连接。没有放宽断言。修正后重新编译并执行上述用例均通过。

### Actual offline use and restoration

记录 AVD 原网络 Wi-Fi / mobile data 为1/1，仅对该 AVD 关闭至0/0。实际 Force Stop / MainActivity COLD 启动后，通过 Knowledge → 新建专用书 `3D4 User Flow` 的既有启动准备 → Session 完成普通阅读、页码、Note、Break、结束、Summary / History / Search / 本地图片 / Caption / 图片所属 Note 回看。完成后仅恢复本轮改变的网络至1/1，实际读回已确认；平台权限和实体手机未变。

Note 页35、图片所属独立 Note 页25均显示保留；Topic 的两条 Note 关联经 Repository 校验及覆盖后实际 Topic list / detail 打开确认。Topic detail 打开时网络已恢复，不把该步骤冒充独立离线 Topic 场景。JPEG是受控白色80×120测试图片，照片入口通过夹具生产 import API，不冒充真实相机拍摄。

## Task 3 — Actual NONE flow; monitored chain still outstanding

本轮实际普通 UI 而非注入：

- 书页40 → 临时输入4时只读数据库仍40 → 输入42后数据库42。Note正文 `3D4 offline controlled note`，页码改35，500ms自动保存；休息与确认框均保留草稿和Note。
- 5min Break 实际开始 / 提前结束；本场 NONE，结束休息回 UNMONITORED，不伪造 RECOVERY / FOCUS。没有真实等待5分钟。
- 第一下结束出现确认框时，仍 ACTIVE、Session未结束。点击“继续阅读”后仍ACTIVE。再次输入结束页41，明确显示“不能低于已经记录的阅读位置”，数据库仍42/ACTIVE；改42最终确认后NORMAL/COMPLETED。
- 实际结果 `40→42 / 2页 / 4分钟 / 1条Note / 未开启手机监测`。Session `84a12737-569d-4cef-88df-8a7aa99d5e2e`，结束边界 `1791205099744`，精确duration `252852ms`；3段为 UNMONITORED → BREAK → UNMONITORED，末段同边界。NONE 不显示 effective focus / counts，未把不可用写成可信零。
- 结果页留置超过75秒后读回，上述Session所有比较字段、末段、Segment数量3、Note数量1均不变。Intent / Session总数保持8/8、Active数量0；没有 FGS ServiceRecord，未重新开始学习。
- Summary “查看本次记录”原地展开仍有结果标题和“完成”；完成回真实Start。书籍History行进入Reading Record，Back回书籍，再回Knowledge；搜索数字token `4` 得到该Session Summary，进入同一记录，Back回原搜索。三入口同为2页/4分钟/1Note及相同三段标签和时间；没有新Intent/Session。Route / stack断言另外由既有 `ReadingRecordNavigationTest` 自动化证明，不把普通UI截图冒充内部stack检查。
- 截图全部是上述真实NONE离线路径和受控新增书/Note；不是FULL fixture，也不是本次真实监测 / 90秒Recovery证明。AVD本地timezone=GMT，截图时间按其真实本地时间；主机timezone不替换AVD事实。

已授权真实监测 / risk App / Overlay或Notification / Allowance / 90秒Recovery / near-finish撤Usage access与Service stop：尚未运行，临时专用AVD权限授权仍待用户确认。当前为 partial user-chain evidence，不能写Task3完整监测闭环PASS。未真实等待15min Deep；已自动化规则证据单列。

## Task 5 — Latest regression and handoff

尚未完成最终完整Gate／交付；不会把缺授权的未执行路径标PASS。Phase3D未冻结。

## Unmeasured boundaries

API23–36 full matrix、完整 OEM matrix、完整实体设备 compatibility、TalkBack、release / Play、真实硬件断电、人工真实系统时钟修改：NOT RUN。本包 API37 结果不外推；一加13T没有本包新反馈，不记录 compatibility PASS。
