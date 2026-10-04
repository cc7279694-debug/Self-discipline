# Mirra Phase 3D-4｜最终联调与个人试用交付 Implementation Plan

> 本文件仅规定未来验收动作，不表示已经执行。默认不新增功能；确有回归时才 Red → 最小修复 → Green → 独立commit。不得以验收名义重开已冻结核心。

**Goal:** 在专用API37 AVD取得完整自动化、Closeout故障窗口、真实用户闭环和数据保留证据，交付可校验Debug APK，等待最终独立review。

**Architecture:** 复用3D-1结束协议、3D-2纯派生服务、3D-3统一记录内容和既有Android渠道；测试夹具仅存在于androidTest，证据区分普通UI实测、受控故障和自动化断言。不增加生产debug开关、系统后台任务或另一份统计事实。

**Tech Stack:** 已有Gradle Wrapper/JBR、JUnit/Room/Compose instrumentation、Android SDK/ADB/API37 AVD、PowerShell、Git、MirraTheme/Components；不新增依赖。

**Spec:** [MIRRA_PHASE_3D_DESIGN.md](MIRRA_PHASE_3D_DESIGN.md) §0–35；原始设计 commit `874d80318c56661218fd03579ba2f1253cc35440`；本计划以该规范本次用户批准的 clock / Summary Plans Review 修订为准，实际执行父基线必须为独立验收通过的3D-3 freeze SHA。

## Global Constraints

- 从真实3D-3 freeze SHA创建 `codex/phase-3d-final-validation`；前三包未冻结不得进入本包。本回合不创建该实现分支。
- Room4、Entity/Table/Column/Index/Migration、schemas1–4不变；Phase2公式、3C行为阈值、Coverage、READY、DND ownership和delivery语义不变。
- 默认只验证、完善证据、交付APK；稳定可复现回归必须保留失败、增加对应最小测试、修复本阶段接线并重跑。不解决无关技术债，不升级依赖。
- 涉及Schema缺口、冻结核心语义改变或范围扩展，保存复现后停止请求用户批准；不得为了全绿重写核心、删除测试、强延长timeout或补造Focus。
- 使用明确指定的专用AVD；实体手机另需用户授权，禁止全量instrumentation直接运行在日常手机。序列号只存本地命令/进程环境，不提交。
- 仅覆盖安装；禁止wipe-data、pm clear、uninstall、改系统时间跳倒计时、手改业务行制造用户闭环。签名不匹配停止，保留数据。
- 无后台复活FGS、无权限自动授予；DND测试前提显式记录。缺授权应skipped/NOT RUN而非虚假PASS；不得关闭用户/其他App规则。
- 每项证据记录环境、commit、实际步骤、PASS/FAIL/DEGRADED/NOT RUN、脱敏结论和证据位置；不上传完整logcat/通知/私人笔记/设备序列号。
- API37成功仅证明该AVD；API23–36、OEM、完整实体机、TalkBack和release未实际执行继续NOT RUN。一加13T反馈最多作为个人daily-use smoke，不外推兼容性PASS。
- 所有路径相对 `C:/Users/CDD/Documents/ChatGPT/Mirra`；以下命令/测试数量是未来执行，不沿用旧223/178作为新运行计数。

## Review Focus

| 危险场景 | 本包具体验证 |
| --- | --- |
| full suite未干净通过，却拼局部结果或DND静默跳过声称全绿 | Task1 `V-AUTO-01`；核XML的executed/passed/failed/skipped及平台前提 |
| A成功后死亡仍记ABNORMAL或使用冷启动时间 | Task2 `V-CRASH-02`、`pendingFixtureRecoversAsNormalAtOriginalBoundary` |
| 结束时风险/撤权/Service stop竞态被忽略，伪造FULL | Task2 `V-CLOSE-11/12`、`lossAtFinalConfirmationNeverProducesTrustedFocus` |
| backward wall jump将用户困在无法结束，或任意过早boundary被自动夹高 | Task2 `V-CLOCK-01/02`；正常边界仍精确、例外仅明确且已durable loss |
| PENDING后旧Overlay/通知仍创建Allowance或重开Session | Task2 `V-STALE-01/02`、`pendingStaleActionsCannotCreateLearningFacts` |
| B失败/PENDING重试漏系统清理，DND失败又改变结束时间 | Task2 `V-CRASH-03`、`V-DND-01`、`pendingRetryAndDndFailureDoNotChangeBoundary` |
| Summary/历史/搜索结果不同，时间仍增长、返回复活或Summary多push详情页 | Task3 `V-FLOW-01`、`completedRecordIsStableAcrossThreeEntrancesAndElapsedTime`、`summaryViewRecordExpandsWithoutNavigation` |
| 覆盖安装丢图片/关联/旧历史，或回填effective | Task4 `V-DATA-01`、`coverInstallPreservesExistingFactsWithoutEffectiveBackfill` |
| APK checksum/commit与实际交付不是同一版本，未测设备写PASS | Task5 `V-DELIVERY-01`、`V-EVIDENCE-01` |

## Task 1：完整自动化与Schema封存

**Files**

- Read: `app/build.gradle.kts`、`app/src/main/java/com/guanyi/mirra/data/local/MirraDatabase.kt`、`app/schemas/com.guanyi.mirra.data.local.MirraDatabase/1.json`～`4.json`。
- Read/run: `app/src/test/`、`app/src/androidTest/`全部既有及3D新增测试；特别是3D-1 Closeout/guard、3D-2 validator/source/compatibility、3D-3 record/navigation/pace测试。
- Update/Create: `docs/checkpoints/2026-10-04-module-3d-final.md`；Create `docs/evidence/phase3d-final/README.md`（脱敏环境和证据索引，不收集完整日志）。

**Interfaces:** 不增改生产接口。若自动化暴露回归，只为具体失败用例添加测试和范围内最小修正；commit单独描述该修正，不混入新的产品功能。

- [ ] 在任何本包修改/connected运行前，核对当前代码仍为3D-3 freeze生产树，保存其Debug APK到`build/deliverables/Mirra-3D3-before-validation-debug.apk`并记录来源SHA/hash/签名。若没有既有APK，先在该冻结生产树assembleDebug生成并保存；不要在已修复3D4代码后才制造“旧版”文件。不创建额外实现分支或覆盖未提交工作。
- [ ] `V-AUTO-01`：先确认目标AVD boot completed、ADB transport健康、无真实阅读Session，再完整未过滤JVM和connected各一次。XML逐suite汇总total/failure/error/skipped，报告executed=total-skipped，不只记录BUILD SUCCESSFUL。
- [ ] 平台DND测试显式assumption：未授权不是DND PASS。需要取得已授权专项执行证据时先请求/确认用户授权，再执行并分别记录；不能将缺前提静默return计为通过。
- [ ] lint记录实际errors/warnings与报告位置；assemble记录实际退出状态。只在稳定环境可复现同一断言才判业务FAIL；transport/SystemUI异常属于环境，不盲改代码。
- [ ] `V-SCHEMA-01`：Room.version=4，只有1–4 schema，四个文件逐一与3D-3 freeze比对；4.json hash必须一致，Migration源及Entity差异为空。不得重新生成/编辑schema修齐hash。
- [ ] 若FAIL：先保存Red/复现，确认本包范围修正后最小实现→相关Green→重新完整run。与Schema/冻结语义有关则STOP；没有回归不制造“测试先失败”记录。
- [ ] **Commit:** 证据里程碑 `test(focus): record phase 3d automated regression`；如有实际修复另提交 `fix(focus): repair verified closeout regression`，不在无bug时创建修复commit。

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
Get-ChildItem app/schemas/com.guanyi.mirra.data.local.MirraDatabase -Filter '*.json' | Get-FileHash -Algorithm SHA256
git diff --check
```

Expected: 一次完整single clean run；失败为0、跳过如实记且不替代专项实际执行；4.json `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。XML路径在实际app/build测试报告中确认，Gradle输出与XML一致。

## Task 2：API37 Closeout状态与Crash矩阵

**Files**

- Test/Create: `app/src/androidTest/java/com/guanyi/mirra/ModuleThreeDCloseoutCrashFixtureTest.kt`（仅专用AVD的受控夹具，绝不进入main）。
- Test/Extend only if uncovered: `app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDCloseoutRepositoryTest.kt`、`app/src/androidTest/java/com/guanyi/mirra/data/ModuleThreeDPendingGuardsTest.kt`；`app/src/test/java/com/guanyi/mirra/domain/SessionManagerCloseoutTest.kt`。
- Update: `docs/checkpoints/2026-10-04-module-3d-final.md`、`docs/evidence/phase3d-final/README.md`；必要的脱敏截图置同目录，不记录私人内容。

**Interfaces:** 无生产故障接口。仅androidTest夹具方法 `preparePendingFixture()` 与 `assertRecoveredFixture()` 使用冻结Repository API；fixture仅新增专用测试书/Session，标记仅存在于测试命名/本地证据，不加业务字段。用Repository合法动作建Segment，不直接SQL改Segment/closeout字段。两方法是显式opt-in场景，不依赖JUnit顺序：分别要求instrumentation参数`mirra3dScenario=pending_prepare`或`pending_assert`，不满足则Assume明确skipped，不静默return。全量suite不运行跨进程夹具链、不留下PENDING；这两项的专项实际执行结果独立列示，默认skip不得算场景PASS。

- [ ] Red → Green：`pendingFixtureRecoversAsNormalAtOriginalBoundary` 先用新夹具证明A后的durable状态，之后真实停止App/冷启动，原boundary/page NORMAL且COMPLETED。不能用普通“点结束后马上强停”碰运气代替确定A/B窗口。
- [ ] 受控A/B间隙：测试夹具调用beginCloseout提交A，故意不调用B，打印仅测试Session的匿名标识/冻结时间；夹具结束后保持PENDING（不cleanup测试数据）。随后显式目标ADB force-stop，再正常launch应用触发真正bootstrap。检查仅该专用记录的context/Session/最后段/summary/FTS；标记为“受控故障 + 实际冷启动”，不是普通UI按钮闭环。
- [ ] B失败/重试用测试隔离DB临时trigger/fake写入barrier证明；真实AVD PENDING重试入口用A夹具+同进程启动验证。测试隔离故障不升级成“真实系统曾磁盘失败”。DND failure/retry亦明确fake失败映射与真实系统执行边界，不破坏用户全局策略制造失败。
- [ ] 若新测试缺行为而Red，禁止在本包重新设计；仅补真实遗漏接线/最小回归。已有3D-1测试已覆盖则复跑并引述实际结果，不复制新算法。

| 场景ID | 实际操作/观察 | 必须结果与证据性质 |
| --- | --- | --- |
| V-CLOSE-01 | 真实ACTIVE正常阅读最终确认 | 精确boundary、NORMAL、COMPLETED、无active段/Session；普通UI实测 |
| V-CLOSE-02/03 | 分别FOCUS/DEEP_FOCUS结束 | 不增清理时长；FOCUS可实测，Deep未真实等待阈值只能自动化或受控夹具，标实际层级 |
| V-CLOSE-04/05 | 真实Break/Allowance中确认结束 | 末段关在sample、无自动Recovery/续期；真实UI操作 |
| V-CLOSE-06/07 | durable Distraction/Recovery中结束 | 不补未确认candidate、不虚构恢复成功；真实风险App/返回操作 |
| V-CLOSE-08/09 | UNMONITORED及NONE结束 | 正常普通结果保留，有效null、非FULL；记录产生状态的真实前提 |
| V-CRASH-01 | 首次按钮后的确认框，尚未最终确认时Force Stop，冷启动 | 普通异常ABNORMAL+原恢复语义；无已保存结束决定 |
| V-CRASH-02 | 受控阶段A提交后、B之前停止App再启动 | 原time/page NORMAL，不重采时间、不后台恢复FGS；受控故障实测 |
| V-CRASH-03 | B失败后PENDING重复重试 | 固定boundary，幂等一次推进/summary；DB失败为隔离故障测试+真实retry可达性 |
| V-DND-01 | release失败→结果/保存弱提示→retry | Session事实不变，只处理Mirra-owned规则；fake与真正执行分列 |
| V-CLOSE-11 | near-closeout撤Usage access/到6秒gap | 先settlement/loss，再结束；FULL不被保住；系统操作需明确授权 |
| V-CLOSE-12 | near-closeout controlled monitor stop | 当前coverage降级不可逆，正常结束仍保存；无后台重新启动 |
| V-CLOCK-01 | 隔离真Room + fake ClockSample：start1000，可信边界/active UNMONITORED.start2000，final wall100且elapsed单调，明确backward证据，PARTIAL/lostAt2000；另测真实Repository loss前提 | A / Session boundary2000，NORMAL/COMPLETED；零时长未知段删除，无1ms、无retry-time、无Focus回填、coverage仍PARTIAL、effective unavailable；自动化受控时钟证据，不改AVD系统时间、不冒充普通手工实测 |
| V-CLOCK-02 | 普通过早boundary，无明确backward证据；另含仅撤权/gap形成PARTIAL，或证据与durable事实不匹配 | 明确拒绝且无结束事实写入；不能把任意早时间通用clamp，不能以不完整事实修饰FULL |
| V-STALE-01/02 | 捕获本测试episode通知/Overlay旧action，A后和B后分别执行 | 不创建Allowance/Break/Recovery、Session不复活；A间隙用受控fixture，真实已结束旧通知另做实测 |
| V-PAGE-01/02 | UI 40→临时4→42；Note页35 | 临时4不持久化、最终42；Note35不改进度；真实UI与既有自动化均留证 |

补充具体回归名称：`lossAtFinalConfirmationNeverProducesTrustedFocus`、`pendingStaleActionsCannotCreateLearningFacts`、`pendingRetryAndDndFailureDoNotChangeBoundary`、`backwardClockJumpStillAllowsCloseoutAtLastDurableBoundary`、`ordinaryBoundaryBeforeActiveSegmentStillFailsWithoutClockJumpEvidence`。若已有等价测试则记录其准确全名与结果，不强制重复新增。clock例外必须复跑3D-1 Task1真Room与Task3串行接线，并由3D-2 Validator验证PARTIAL无有效资格；原3C controller fake测试单独通过不能替代durable loss/真实事务证明。若真实loss前提暴露冻结核心冲突，按Scope Guard停止报告，不扩大clamp或修改Schema。

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon
# 仅覆盖test APK；target Debug APK已由Task1安装，不在窗口中让Gradle隐式重装。
& $mirraAdb -s $mirraAvdSerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $mirraAdb -s $mirraAvdSerial shell am instrument -w -r -e class 'com.guanyi.mirra.ModuleThreeDCloseoutCrashFixtureTest#preparePendingFixture' -e mirra3dScenario pending_prepare com.guanyi.mirra.test/androidx.test.runner.AndroidJUnitRunner
# 以下$mirraAvdSerial为本地已识别专用AVD；不把其值提交。
& $mirraAdb -s $mirraAvdSerial shell am force-stop com.guanyi.mirra
& $mirraAdb -s $mirraAvdSerial shell am start -n com.guanyi.mirra/.MainActivity
& $mirraAdb -s $mirraAvdSerial shell am instrument -w -r -e class 'com.guanyi.mirra.ModuleThreeDCloseoutCrashFixtureTest#assertRecoveredFixture' -e mirra3dScenario pending_assert com.guanyi.mirra.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:testDebugUnitTest --tests '*SessionManagerCloseoutTest' --no-daemon
git diff --check
```

运行前由现有local.properties SDK确定`$mirraAdb`，本地设置并核对`$env:ANDROID_SERIAL`；fixture须在任何读写前验证opt-in与AVD，禁止在实体机/非指定环境执行。读取实际test APK/runner输出确认package/执行计数，不能只依赖adb退出码。检查夹具运行器结束是否本身停止了target进程；记录确切生命周期，不能将重装/额外bootstrap混为指定的A/B死亡窗口。不添加main故障入口；无法可靠控制的场景记NOT RUN并说明。

**Commit:** `test(focus): verify pending closeout crash windows`。故障夹具完成后完整正常suite不得遗留PENDING占槽：按complete正常完成专用记录，保留合法历史，不清库。

## Task 3：完整学习→结束→回看用户闭环

**Files:** Test/Create `app/src/androidTest/java/com/guanyi/mirra/ModuleThreeDFinalFlowTest.kt`（若既有分段测试没有覆盖统一导航链）；Read/reuse `MirraApp.kt`、`SessionScreen.kt`、`ReadingRecordContent.kt`、`LearningItemScreens.kt`、`SearchScreen.kt`；Update final checkpoint/evidence index。无新UI功能。

**Interfaces:** 只调用已冻结Navigation3 routes、SessionManager/FocusSessionActions与ReadingRecordRepository；不添加绕过confirmation/READY的测试用生产接口。

- [ ] Red/Green：`completedRecordIsStableAcrossThreeEntrancesAndElapsedTime`：一个正常记录，从结果/历史/搜索显示完全同值；推进测试clock或真实停留后endedAt、末段、duration不变，不产生新Intent/Session/FGS。`finalFlowPreservesDraftAndEndsOnlyAtFinalConfirm` 保证草稿已保存再弹框、continue仍学习、final唯一boundary。
- [ ] 复跑3D-3 `summaryViewRecordExpandsWithoutNavigation`：Summary默认简洁，查看后仍为SessionSummaryRoute，back stack不新增SessionSearchDetailRoute、展开真实timeline；History/Search仍进入既有detail并返回各自来源。同projection比较值，不比较三种入口的route结构。
- [ ] `V-FLOW-01` 真正执行：Start→Preparation→Session→阅读/页码/Note→Break→安全风险App→Overlay或Notification→Allowance→提前结束→Recovery连续90秒→阅读→第一下结束/草稿flush→确认/最终结束→Summary→展开timeline→完成回Start→书籍history→record→Search session result→同一record。
- [ ] Summary→展开timeline为当前页面inline切换，不是导航；该步骤前后route / back stack不变，不增加第二个详情页。后续history/search回看仍为统一SessionSearchDetailRoute，不能以三入口同源为由改变此UX。
- [ ] Overlay与Notification分开说明权限/DND前提；POSTED不能写SHOWN。正常DND运行时不为了可见通知关闭/绕过规则；in-app fallback保留。如果外部渠道不可用，记录DEGRADED，不中止阅读或伪造恢复。
- [ ] `V-FLOW-02` 查看结果停留至少30秒，返回原旧intent/action也不能续读；普通数据duration按旧口径，effective只来自完整可信段；Deep/Stable未实际等待的状态只列已有自动化证明。
- [ ] `V-VISUAL-01` 真实截图：结束确认、FULL结果、PARTIAL/NONE结果、展开时间线、书籍节奏；320/360/411dp和fontScale2关键按钮可滚动/48dp，使用现有Mirra Blue。不生成概念图、不重做Start/Mine。
- [ ] **Commit:** `test(focus): verify the complete reading record flow`。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.guanyi.mirra.ModuleThreeDFinalFlowTest,com.guanyi.mirra.ReadingRecordNavigationTest,com.guanyi.mirra.SessionCloseoutUiTest" --no-daemon
git diff --check
```

若无需新增FinalFlowTest，命令只用实际现有类，不声称不存在的测试已通过。真风险App时间/Recovery等待与Compose fakeclock用例分列，不互相替代。

## Task 4：覆盖安装、离线与历史数据兼容

**Files:** Test/Create `app/src/androidTest/java/com/guanyi/mirra/ModuleThreeDDataPreservationTest.kt`；Read `app/build/outputs/apk/debug/app-debug.apk`；Update final checkpoint/evidence index。业务和Schema文件只读。

**Interfaces:** 仅androidTest方法 `seedPreservationFixture()`、`assertPreservationFixture()`，通过真实Repositories创建专用Item/Note/Session/Segment/RiskApp/Topic/CrossRef/Image。旧无context/segment历史在测试隔离旧schema/合法旧数据fixture中建立，不修改私人/生产数据来“模拟”。跨安装seed/assert分别要求`mirra3dScenario=data_seed`/`data_assert`，默认Assume skipped；普通`coverInstallPreservesExistingFactsWithoutEffectiveBackfill`自动化用独立fixture且自行setup/teardown，不依赖跨运行顺序。专项真实install结果与全量计数分别报告。

- [ ] Red → Green：`coverInstallPreservesExistingFactsWithoutEffectiveBackfill`：覆盖前后专用fixture的ID/引用/页码/旧Session时间/Segment/Topic关联/图片relative path/文件checksum相同，旧历史effective=null；不只比较行数。`offlineRecordAndSearchRemainUsable`：断网新增/结束/搜索/历史/本地图片正常。
- [ ] `V-DATA-01` 先保存3D-3 freeze构建的target APK为本地`build/deliverables/Mirra-3D3-before-validation-debug.apk`并记录来源commit、签名、hash；在任何3D4 connected隐式安装前保留该文件。若仅有已安装旧包，可只读取回base.apk并核已安装版本/来源；无法确认来源则不能声称3D3→3D4升级PASS。
- [ ] 同一专用AVD覆盖安装该已确认3D3基线（不得降级/清数据，versionCode若有冲突则停止），再只安装兼容androidTest APK，通过raw instrument seed，不让Gradle自动覆盖target。记录专用fixture摘要后唯一一次覆盖3D4 APK，再raw instrument assert。若两包生产binary相同，如实写“冻结前后同binary覆盖保留”，不声称发生Schema迁移或真实版本号升级。
- [ ] 覆盖后实际打开Notes/Images/Topics/Search/History，图片文件仍可解码，既有Phase2预测/旧无段记录保留；已可信3C历史可派生effective，不追加/改写历史段。
- [ ] `V-OFFLINE-01` 专用AVD关闭网络后冷启动，完整普通阅读→结束→结果→搜索→图像回看，退出/重进事实不变。执行前记录网络状态，执行后恢复仅本轮改变的AVD配置；不改用户实体机网络/后台权限。
- [ ] 若安装签名不匹配或任何数据缺失：STOP并保存证据，不卸载。现有Schema Migration1→2→3→4测试随全量执行，不生成新的Migration。
- [ ] **Commit:** `test(records): verify offline upgrade data preservation`。

```powershell
& $mirraAdb -s $mirraAvdSerial install -r build/deliverables/Mirra-3D3-before-validation-debug.apk
& $mirraAdb -s $mirraAvdSerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $mirraAdb -s $mirraAvdSerial shell am instrument -w -r -e class 'com.guanyi.mirra.ModuleThreeDDataPreservationTest#seedPreservationFixture' -e mirra3dScenario data_seed com.guanyi.mirra.test/androidx.test.runner.AndroidJUnitRunner
& $mirraAdb -s $mirraAvdSerial install -r app/build/outputs/apk/debug/app-debug.apk
& $mirraAdb -s $mirraAvdSerial shell am instrument -w -r -e class 'com.guanyi.mirra.ModuleThreeDDataPreservationTest#assertPreservationFixture' -e mirra3dScenario data_assert com.guanyi.mirra.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

instrumentation受控fixture与普通手工离线行为分别留证。测试runner不可使用clear-package-data/Android Test Orchestrator自动清库；如果实际runner有此配置先停止，不能删数据获得验证。

## Task 5：最终全量、Debug交付与冻结门槛

**Files**

- Binary/Create: `build/deliverables/Mirra-3D4-debug.apk`（本地交付，不提交大二进制/签名材料）。
- Update: `docs/checkpoints/2026-10-04-module-3d-final.md`、`docs/CURRENT_STATE.md`、`docs/evidence/phase3d-final/README.md`及少量脱敏实测截图。
- `docs/DECISIONS.md`仅确有新的最终长期决定才更新，不重复既有Closeout/coverage公式。

**Interfaces:** 无新增业务接口；最终报告列出四包已验收commit、本包commit、生成APK对应build基线。commit包含证据而不嵌入自己的未知SHA，Push后报告精确local/remote SHA。

- [ ] `V-DELIVERY-01` 完成所有修复/测试后再次取得一次完整未过滤JVM、connected、lint、assemble；不拼单例通过。文档-only整理不强制重复相同build。复制最终APK到指定路径，记录bytes、SHA256、applicationId=`com.guanyi.mirra`、实际versionName/versionCode和source commit。
- [ ] 重新覆盖安装该交付文件，正常/断网cold start和已有专用record检查；APK文件hash与报告一致，不拿旧3C包冒充。
- [ ] `V-EVIDENCE-01` 检查所有矩阵，PASS须有对应实际运行证据。未运行项必须NOT RUN及原因；failed环境历史不删。API23–36 full/OEM/full实体机/TalkBack/release未运行保留，不外推API37；一加13T只有用户后续实际反馈才记daily-use smoke，不能等同compatibility PASS。
- [ ] final checkpoint记录Closeout crash故障注入边界、DNDcleanup/重试、3C兼容、Phase2不变、schema四hash、tests实际计数/skip、APKchecksum、未完成范围；CURRENT_STATE写“3D-4待独立验收”，不自行写全Phase3已冻结。
- [ ] 检查 `git diff --check`、status、文件范围、secret pattern、临时文件、Schema/冻结core diff；独立commit `test(focus): finalize phase 3d validation evidence` 并Push当前功能分支。local==remote、clean后停；不merge main、不发布release、不开始Phase4。
- [ ] 输出 `[3D_4_COMPLETE]`；只有用户最终独立review通过后，另获授权的纯文档freeze才把Module3D正式冻结。本包执行结束不自动继承冻结权限。

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
New-Item -ItemType Directory -Force build/deliverables
Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination build/deliverables/Mirra-3D4-debug.apk
Get-Item build/deliverables/Mirra-3D4-debug.apk | Select-Object Length
Get-FileHash -Algorithm SHA256 build/deliverables/Mirra-3D4-debug.apk
& $mirraAdb -s $mirraAvdSerial install -r build/deliverables/Mirra-3D4-debug.apk
Get-ChildItem app/schemas/com.guanyi.mirra.data.local.MirraDatabase -Filter '*.json' | Get-FileHash -Algorithm SHA256
git diff --check
```

Future completion report：功能与事务、测试总数/failed/skipped、实际AVD场景、未执行设备、数据保留、Room4/hash、APKpath/bytes/SHA/version、branch/commit/localremote/clean和任何风险。没有证据不宣称“全设备/发布级已验收”。
