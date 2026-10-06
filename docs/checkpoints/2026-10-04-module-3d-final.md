# Phase 3D-4 — Final validation and personal-use delivery

更新日期：2026-10-06。当前状态：Recovery诊断结论已接受，剩余真实平台闭环已补齐；最终connected存在两项未定根因超时，且受控图片保留检查发现缺失JPEG/未隔离测试清理，触发数据停止条件。配置已恢复，待单独审查/修补授权；3D-4尚未完成，Phase3D未冻结。下文所有早期RED、权限前提、失败与NOT RUN作为历史继续保留，最新状态见末尾2026-10-06补充。

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
- 最终确认时受控 ClockSample 六秒缺口、真实 Controller → Manager → Room 接线，先落 PARTIAL / UNMONITORED 再结束。对实际持久事实使用冻结 Validator，得到 MONITORING_INCOMPLETE / effective=null，不为正常结束保住 FULL；不是实际撤权或真实等待六秒的系统场景。

新 androidTest 首次编译有4处夹具类型／投影字段错误，修正仅在新测试文件，随后编译通过；不是生产回归 RED，也没有修改产品来迎合测试。既有完整 baseline 的 closeout / retry / clock / stale action 覆盖与新增执行证据分别记录。真实 Usage撤权、Service stop、跨应用旧 action 等权限相关人工路径尚未执行，不冒充专项平台 PASS。

### Existing matrix coverage, not interchangeable with actual UI

补充普通真实UI场景（不依赖新增权限）：

- V-CRASH-01：新 NONE Session 在首次结束确认框内尚未最终确认时 Force Stop。冷启动前 `9b12011f-8a42-4b09-8cab-50e86563c5a1` 为 ACTIVE、endedAt/endType空；正常 MainActivity COLD 后 ABNORMAL、endedAt `1791206264989`，不存在PENDING结束决定。Active Session / Segment数量0/0，无FGS；没有恢复学习。
- V-CLOSE-04：另一个真实 NONE Session 开始5min Break，直接点击阅读结束并最终确认，没有先“提前结束休息”。`fdd57f3d-b1e7-4880-b7af-09cefbe484de` 为NORMAL/COMPLETED/NONE，42→42/0页/0Note，末段BREAK精确闭合在 `1791206348805`；没有新增Recovery/Allowance/Focus。不是完整监测的Break样本，不把NONE当可信。

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

计划要求的真实监测 / risk App / Overlay或Notification / Allowance / 90秒Recovery / near-finish撤Usage access与Service stop：尚未运行，临时专用AVD权限授权仍待用户确认。当前为 partial user-chain evidence，不能写Task3完整监测闭环PASS。未真实等待15min Deep；已自动化规则证据单列。

## Task 5 — Latest available-environment regression and partial handoff

2026-10-05最新一次完整未过滤执行，不用定向测试拼全量结果：

- JVM `testDebugUnitTest --rerun-tasks --no-daemon`：实际45 suites / 318 total / 318 passed / 0 failure / 0 error / 0 skipped，BUILD SUCCESSFUL，5m29s；XML全部本轮21:15更新。
- connected完整单次执行10m11s，runner/Gradle退出0。XML275 total / 266 passed / 原始9failure / 0error / 0skipped；逐项读取failure正文，9项全部为 `AssumptionViolatedException`，实际业务断言失败0。4项是本包opt-in pending_prepare / pending_assert / data_seed / data_assert默认不执行；另5项是原DND3 / Overlay1 / notification1缺权限。不可宣称275/275 clean PASS，也不改写原始报告。
- 4项opt-in此前明确专项分别1/1实际执行通过，保留Task2/4原证据，不把它们补加到本次full-suite的266 passed。普通隔离preservation1项与新增boundary2项均在本次full suite实际通过。
- DND平台本次0项实际执行；渠道3项实际执行（missingOverlayPermissionDoesNotAttachOrCrash、notificationDenialReturnsUnavailableNotPosted、independentChannelDoesNotBypassDndAndStartupCancelIsIdempotent），granted Overlay与notification未执行。平台授权没有改变。
- `lintDebug / assembleDebug --no-daemon` 退出0 / BUILD SUCCESSFUL，1m57s；读取实际lint XML：0errors / 9existing warnings / 1hint。生产lint/build相同输入部分UP-TO-DATE，新androidTest分析实际执行，不把UP-TO-DATE冒称源文件重写。
- Schema1–4本轮再次hash，与Task1表完全一致；version4、生产代码/Entity/Migration/Gradle相对parent仍无diff，没有5.json、没有生产fix commit。

### Candidate Debug APK — exact installed file, not completed release

最新构建复制到 `build/deliverables/Mirra-3D4-debug.apk`；本地候选个人Debug包、不入Git，不表示3D-4已全部完成或release验收。

- Build source HEAD: `c39c8f98cc71b502e1cc96efc0d5863585f0625e`；后续仅文档交接。生产树仍为冻结3D-3，没有产品修补。
- Bytes: `16488770`.
- SHA-256: `68C89D5948E34E3EF48DE374CA2043B6B5D0EE43702E7D3B63E7A1CE3EF674E8`.
- applicationId `com.guanyi.mirra`; versionName `0.1.0`; versionCode `1`; minSdk23 / targetSdk37。
- 交付文件hash与parent保存APK相同，如实写同binary，不假造新版差异。对上述确切交付文件执行真实 `adb install -r`，返回Success；之前Active Session/Segment为0/0，无签名冲突，不卸载、不清数据。
- 显式Force Stop后正常MainActivity启动：`Status=ok / LaunchState=COLD / TotalTime=2856ms`，真实Knowledge页可见。UI自动化首次snapshot的null-root重试后取得新XML；历史工具SQL引号/误用派生duration字段错误只发生在只读查询，修正后读回成功，没有业务写入或产品失败。
- 安装后实际History进入旧NONE记录、Search数字token4进入同一记录并展开：2页 / 4分钟 / 1Note，仍为UNMONITORED→BREAK→UNMONITORED。再次读回endedAt `1791205099744` / duration `252852ms` / current=endPage42 / NORMAL / NONE / COMPLETED / Segment数3及末段原boundary；没有Active Session/Segment或FGS。
- 专用preservation书仍40页，两个Note仍25/35页，ImageAsset仍1条，Topic关联2条；legacy无context/segment仍0/0，旧可信3C形态Segment仍2条。Task4全面字段/checksum及图片校验保留，以上是候选安装后的额外只读抽查，不把抽查冒充重新执行完整checksum断言。
- AVD网络读回1/1；Usage与Overlay仍default、DND/通知仍未授权。没有实体手机操作。

### Remaining requirements and stopping point

本包目前是部分验证交接，不是最终完成；没有确认的生产回归，也不因此改核心。仅等待用户确认临时专用AVD平台权限，收到许可后沿原计划继续，不另开产品设计。

- 真实Start主线→Preparation→FULL Session→risk App→Overlay / Notification→Allowance→连续90秒Recovery→阅读→最终结束的完整链：NOT RUN pending authorization。NONE普通UI链已执行，不能替代FULL。
- 真实Usage Access near-finish撤权、controlled monitor stop near-finish、旧Overlay/Notification/PendingIntent在A/B后实际回放：本包手工NOT RUN；自动化gap/guard/stale tests已执行，证据不混用。
- 手工Stage B失败/PENDING retry UI可达性未执行；既有真实Room fault injection及Compose `recreatedPendingBackCannotResumeAndRetryUsesFrozenDecision` 属于本次full suite自动化，不冒充额外手工故障场景。
- V-VISUAL-01本轮FULL/PARTIAL实际截图、320/360/411dp与fontScale2专项：NOT RUN；3D-3的既有尺寸/大字证据是历史，不写本包新PASS。真实15分钟Deep仍NOT RUN，自动化规则证据单列。
- 授权后补专项及已授权平台run，再决定最终Gate与完成报告；本次不会输出3D-4完成或自行Freeze。独立用户验收尚未开始；内部源码/覆盖审计不冒充用户验收。

### Task commits

- Task1：`38dd9efae82ac0c7926738338eff791d12c38986` — `test(focus): record phase 3d automated regression`。
- Task2：`f39277b6703e4191a3dca7043fbb711ce68164f5` — `test(focus): verify pending closeout crash windows`。
- Task4及真实NONE路径：`c39c8f98cc71b502e1cc96efc0d5863585f0625e` — `test(records): verify offline upgrade data preservation`。
- Task3真实监测链未完成，不创建误称完整闭环的commit；本次纯文档partial handoff提交另报SHA，不替代冻结实现SHA。
- 用户已授权本功能分支commit / push，不squash、不合并main、不发布。提交前diff/secret/schema核对及推送后local/remote/clean核对在实际Git执行后报告；不把预期状态写成既成事实。

## Unmeasured boundaries

API23–36 full matrix、完整 OEM matrix、完整实体设备 compatibility、TalkBack、release / Play、真实硬件断电、人工真实系统时钟修改：NOT RUN。本包 API37 结果不外推；一加13T没有本包新反馈，不记录 compatibility PASS。

## Authorized permission supplement — Recovery review required

用户在基线 `f8fa4ace60e3224354244a98114158e698ec950f` 明确授权仅专用API37 AVD四项权限。上文“待授权”与266passed/9assumptions是授权前历史，不作为本轮当前状态；完整新步骤、时间线、测试计数、异常与恢复见 [authorized supplement](../evidence/phase3d-final/authorized-permissions-and-recovery-review.md)。

- 四项原状态先可靠读回并仅存本地ledger；授权后实际重新读回。raw平台8项均实际执行，8passed / 0failure/error/skipped；原五项缺权限测试本次真正进入主体。DND rule ownership/reuse/activation/release/globalPolicy、Overlay和Notification断言通过，POSTED不冒称SHOWN。
- 第一场真实FULL Session完成in-app分心→Allowance/一次延长→91.566sec Recovery→FOCUS→正常精确Closeout / DND RELEASED。Note保留，结果可派生120191ms有效Focus。跨应用开关此场实际OFF，证据不混为外部渠道。
- 第二场跨应用ON，真实ChromeOverlay及receipt成功；Allowance提前结束后Recovery未在预期窗口完成。独立HOME/返回、无uiautomator窗口118.786秒和124.830秒再次观察仍FULL/RECOVERY，健康heartbeat、无RECOVERY_SUCCEEDED。可见Activity已RESUMED/focused/Awake，但页面owner/多reporter/证据循环尚未证明；根因待审查，不宣称已确认算法Bug或环境问题。
- 根据停止规则中止相关链，未修改生产或测试。通过正式UI结束该RECOVERY场，NORMAL/COMPLETED/FULL、末段如实RECOVERY、DND RELEASED，然后恢复原DND/cross-app OFF、原风险选择、四权限及Knowledge目的地。恢复读回成功；网络仍1/1，Active Session/Segment0/0、FGS/Overlay/intervention notification均无残留，Mirra rule在撤权前STATE_FALSE。
- 未继续Session-level Notification fallback、near-finish撤权/stop、最终granted full connected、重复四opt-in与Task5最终Gate；这些均NOT RUN，不用8项平台或第一次Recovery通过代替。旧完整JVM318及connected275/266/9assumptions不冒称新完整granted结果。Schema1–4本轮再次hash不变；Room4 / production / Migration / Gradle仍无diff。
- 新截图为专用AVD实际UI，不是fixture或概念图。关键异常截图为 `recovery-after-overlay-not-completed.png`。内部只读审查提供候选原因，不冒称用户独立验收。

**当前停止点：SOL_REVIEW_REQUIRED。3D-4未完成，未Freeze Phase3D，未进入下一Phase。** 本轮Git只提交验证文档和上述脱敏截图，实际commit / localremote / clean在提交后报告。

## Accepted Recovery review and remaining validation — 2026-10-06

用户接受 `d0da1061a7b706e25784e43fc700189564bbe980` 诊断结论 RECOVERY_DIAGNOSTIC_INCONCLUSIVE，允许沿原计划继续剩余验证，不授权production fix。上文Recovery停止点为历史，不删除。原成功91.566sec、诊断90.827/90.858/91.006sec与一次>120sec异常全部保留，异常分类仍为 Historical intermittent Recovery anomaly / Observed once / Not reproduced in targeted diagnostic，不标FIXED、ROOT CAUSE RESOLVED或AVD ISSUE。

### Remaining actual platform paths

详见 [remaining platform flow](../evidence/phase3d-final/final-remaining-platform-flow.md)，真实记录与四张脱敏AVD截图已单独提交 `50d9a0bdf339c623169cb2f1e6f3924a7bc2631c`（`test(focus): verify the complete reading record flow`）。没有新增测试来重复已有完整自动化，也没有生产修补。

- Usage Access near-finish撤销：先durable PARTIAL/UNMONITORED，再NORMAL/COMPLETED；末段精确结束，effective unavailable，DND RELEASED。
- 已有Diagnostics“停止监测测试”：先durable loss，ServiceRecord0，再正常Closeout；PARTIAL不回FULL，DND RELEASED，没有后台重启。
- 最终真实Start主线→Preparation→FULL/FOCUS→页42到44/1Note→Chrome短访及持续确认→Session Notification fallback→实际通知PendingIntent点击→Allowance5min/一次+2min→提前结束→Recovery91.661sec成功→FOCUS→最终确认→Summary/inline timeline→History/Search。没有出现新的≥110sec健康正向页面异常。
- 通知实际POSTED、fullScreenIntent=null、channel不bypassDND，无MirraOverlay；实际drawer观察和IN_APP receipt分别记录，不把POSTED写成SHOWN。合法风险退出由monitor转RECOVERY，不把该状态变更归因于notification navigation。此场DND偏好OFF用于实际drawer观察；前两条实际DND ACTIVE/RELEASED证据单列。
- 最终Session endedAt1791249203077、末段同边界，总时长321164ms、有效Focus138230ms；结果页≥32sec不变，无新增Session/Intent、活动段或FGS。COMPLETED后保存的真实episode URI/request等价回放不新增任何学习事实；不是再次发送原系统PendingIntent。
- A/PENDING间隙guard、B失败/重试、ClockSample真实Controller→Room gap继续由现有专项/自动化证明，不危险地重新造人工窗口。实际OS query-gap不可可靠触发，NOT RUN；真实15min Deep仍NOT RUN。

### Final fresh regression — not a clean PASS

2026-10-06真实执行的未过滤JVM：318 discovered /318 executed /318 passed /0 failure/error/skipped，test task实际重跑。完整未过滤connected：275 discovered /271 executed /269 passed /2 assertion failures /0error /4opt-in assumptions，10m15s、Gradle退出1。原XML275tests/6failure/0error/0skipped，其中4节点为AssumptionViolatedException、2节点为ComposeTimeoutException；未改XML。

五项原permission-gated DND/Overlay/Notification用例全部真正进入断言并PASS；320/360/411dp×fontScale1/2六项可达性也在该full中PASS。四opt-in原prepare/assert/seed/assert专项各1/1已重新读取原日志核对，普通full仍按设计不执行，不能加到269passes。

两项真实失败均为最终confirm之后Summary文本等待5000ms超时：`PhaseOneCorrectionTest.finishingImmediatelyFlushesDraft:130` 与 `PhaseOneLearningLoopTest.completeLearningLoopCreatesBookNotesProgressAndSummary:70`。仅无改动定向复现一次，2/2 PASS、41.268sec；不拼成full-suite PASS、不放宽timeout。日志没有确认Mirra crash/ANR/Room错误，也不能把共同框架警告直接认定为AVD原因。完整原始报告及每测日志已保存在仓库外本地，不上传全量raw日志。

随后尝试新full命令时，PowerShell未引用的dotted `-P` 属性导致Gradle task selection错误，测试没有开始；这是执行工具问题，不算新business FAIL或新full结果。此后因下述数据保留STOP没有继续重跑。

### Blocking test-storage preservation evidence

完整回归后实际只读发现：原preservation ImageAsset仍有1行及正确相对路径/818bytes metadata，但对应JPEG不存在，images目录为空。历史seed/assert marker仍VERIFIED_PREFERENCES_RESTORED，不是当前文件仍在的证明。学习数据库16Session/16Intent、最新FULL endedAt/page44和Active0/0未改变。

现有TestAppContainer.close使用target application filesDir递归删除images及image-work；生产DefaultImageStorageService使用同一路径。内存Room不能隔离文件，该测试cleanup已存在于冻结基线。没有本轮full之前即时文件hash，不能指定精确删除run；但现存缺失文件和共享清理边界已确认，触发原计划数据丢失STOP。不是Recovery回归、不是新生产修改，也不把缺文件归因于Summary超时。

详见 [final regression blockers](../evidence/phase3d-final/final-regression-blockers.md)。未修test/core、未补造JPEG、未删除行/marker或重新seed掩盖问题。应先获单独test-isolation修补与安全保留重验授权，再取一次完整clean connected。fresh最终lint/assemble、exact-file交付覆盖安装/离线preservation因此NOT RUN，不用历史PASS替代。

### Restoration, frozen source and artifacts

停止后实际读回：Usage/Overlay default、POST false及原flags、resumed Diagnostics DND access false；DND/cross-app OFF、原单条受控风险选择、Knowledge、网络1/1。Active Session/Segment0/0、monitor ServiceRecord0、MirraOverlay0、intervention notification0；当前Mirra-owned rule FALSE、ZenMode OFF，原consolidatedPolicy一致。历史Zen-log TRUE不是当前active。实体手机从未操作。缺失受控JPEG仍未恢复，权限恢复不等于图片恢复。

Room version4与Schema1–4 hashes再次实际核对，仍与Task1封存表一致，v4=`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。生产/app/src/main、Manifest、Migration、Entity、Gradle/依赖相对parent无diff；本轮相对诊断HEAD也只新增证据/文档，未改test。

connected实际构建的app-debug.apk为16488770bytes、SHA-256 `9C9B33337A8FB9A3969089308C28E8302AB285939DFBD47A4103CC7D48C68CD9`，com.guanyi.mirra /0.1.0 /code1，sourceHEAD50d9a0bdf339c623169cb2f1e6f3924a7bc2631c。与保存parentAPK双向163entry inventory及每项未压缩内容完全一致、公开signer相同，但whole archive hash不同，不假称byte-identical。未覆盖交付文件/重新安装exact file或宣告final APK完成。

API23–36 full matrix、OEM/完整实体机、TalkBack、release/Play、真实硬件断电、人工系统时钟、真实15min Deep继续NOT RUN。一加13T试用不升级OEM PASS；API37自动化/普通UI/受控fixture证据严格分列。

**最新停止点：测试文件隔离/受控图片保留待审查授权。Phase 3D-4未完成；没有自行Freeze Phase3D，没有进入下一阶段。** 本轮只保存真实验证证据、历史风险与安全停止状态，不把内部审计称为用户独立验收。
