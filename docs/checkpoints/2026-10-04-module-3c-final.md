# Module 3C-4｜最终联调与个人试用交付

## Contract / frozen baselines

- Goal：联调既有 3C 用户闭环、取得同一最终代码版本的完整回归和实际 AVD 证据、提供可覆盖安装的个人试用 Debug APK；不是新增能力或发布级兼容性冻结。
- 本轮用户附件：`Mirra Module 3C｜交付包 3C-4`，2026-10-04。执行分支 `codex/phase-3c-final-validation`，起点 `6384cc634b080ac9554ac20cd4c95b9b5e13a262`。
- 冻结内核：3C-1 `61ed78f94125e7049c5d4ca73be1b16c5416043b`；3C-2 / sampling patch `f275334e233298b872bb507a82dcd875ff3135d5`；3C-3 `b9843eb5c3879899e148792a6a1f252e4b6d573d`。
- Scope：既有流程联调、必要且可复现的 UI / wiring / presentation 小修、缺失验收测试、截图、APK、状态和 checkpoint。冻结核心问题先分析；需要改变核心则停止请求复验。
- Guard：Room v4 / schemas 1–4 / Migration、DND ownership、READY、Monitoring 和行为语义不变；不进入 3D、Closeout、effective metrics、主题扩展、备份、AI、云同步或硬件。
- Verification：C01–C25 对照既有自动化与本轮实际 AVD 闭环；最终未过滤 JVM、connected、lintDebug、assembleDebug；覆盖安装、离线、Force Stop；Schema hash 与 Git 检查。
- Ruling：附件是验收协议而非 Task/Expected 脚本格式，以本 checkpoint 作执行账本；不重写已冻结总计划。按总计划不自动开启并行代理，最终自查明确标为作者自查，不替代用户独立验收。
- Pre-flight：本地 HEAD 与用户冻结起点一致，工作区干净，已建立独立分支。UI / Presenter 继续消费同一行为 facts，不新增状态机。3C-3 已冻结的展示 lifetime 与 foreground freshness 分离语义优先于旧总计划的“risk exit 一律清提示”概述。

## Execution ledger

- Context 恢复完成；已有 JVM / Room / Compose / 平台测试将用于验收，不重复开发 3B 或 3C-1～3。
- 以下按实际执行顺序保存环境、修补与联调记录；修补前回归不代替最终代码的完整回归。
- Environment history：首次预检时专用 AVD 已 boot，但并行完整 Kotlin 编译期间 System UI 出现无响应对话框，主机 free memory 约 628MB；尚未运行 connected，不记 Mirra FAIL。仅关闭本轮指定 AVD，未清数据或改项目配置。编译结束后保留数据冷启动同一 AVD，boot=1、System Server PID=680、主机 free memory 约 4220MB。只消除并行重编译资源争抢；不把资源相关性当作已经证明的 ANR 根因。
- JVM fresh full run：`testDebugUnitTest --no-daemon --rerun-tasks`，4m12s，222 tests / 222 passed / 0 failed / 0 errors / 0 skipped；完整 XML 核对。没有生产或测试代码变化。
- lint/build preparation：`lintDebug --rerun :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon`，38s，退出 0；lint XML 为 0 errors / 9 existing warnings / 1 hint。assemble 复用同源且未变化的 APK（UP-TO-DATE，不称新编译）；生产 APK 大小 16,542,373 bytes。所有重新编译步骤先在 AVD 关闭时完成。
- 修补前完整 connected：5m15s，177 total / 177 passed / 0 failure / 0 error / 0 skipped；XML 核对 DND 平台 3 项与渠道平台 5 项均执行。专用 AVD 显式建立 DND / Overlay / Notification 授权前提，无其他在线设备参与；System Server PID 680 保持。该结果不是后续输入修补的最终回归。
- 实际 UI 建立专用 `Mirra-3C4-Test`（320 页、起点 40）：Usage 未授权仍能进入 `NONE + UNMONITORED`；5 分钟 Break 提前结束仍为 UNMONITORED；Note 自动保存、记下一条、NORMAL 结束及 Summary 可用。临时输入 4240 被书籍范围校验拒绝，未污染数据库；Note 42 不推进 Session 40。所有测试数据经正式 UI 创建，SQLite 仅只读检查。
- 健康监测 smoke：经正式风险 App 选择 Chrome / Clock、AVD Usage Access 授权后新 Session 初始化 FULL + FOCUS；10 分钟 Break / 提前结束 → RECOVERY；连续 90.836 秒后自动 FOCUS，`RECOVERY_SUCCEEDED` 1 条，FULL 与草稿均保留。此为修补前 smoke，最终版本仍需重验 Recovery。

## Page-input acceptance patch（UI / ViewModel only）

- 实际复现：40 → 42 的逐字编辑先产生 4；ViewModel 立即把 4 改回 40，妨碍正常编辑。这是输入文本与持久进度混用，不是 Room 允许进度倒退。
- 首次新 JVM 夹具在失败路径未释放 ViewModel 无限 ticker，测试 worker 陷入虚拟时间 drain；仅结束该明确的测试 worker，补 `finally` 清理后重跑，不将此夹具失败当作 Mirra 业务失败或 GREEN。
- RED：修补前新用例 1/1 failed，`expected:<4[]> but was:<4[0]>`（45s）。
- 最小修补：只移除 `SessionViewModel.updatePage()` 对暂时较小文本的强制回填；较小值仍立即 return，不调用 SessionManager，不改变 Note 默认页。完整合法值才沿原入口持久化；Room / Repository 单向约束与 finish 校验不变。
- 新 JVM 约束中间文本 4 保留、默认页仍 40、没有倒退保存调用，最终 42 仅调用一次正确 Session 的更新；新 Compose / 真实 Room 测试覆盖逐字编辑与旧页 Note 不推进阅读位置。
- 定向 GREEN：7/7 SessionFocusViewModel tests，0 failed / error / skipped；生产与 test APK 正常重建（1m14s）。最终全量结果待记录。

## Final APK actual AVD loops（API37 AOSP only）

- 最终输入修补版覆盖安装后，原专用 Learning Item 1、Note 2 与 2 条 NORMAL Session 保留；随后 Wi-Fi / 移动数据关闭，Force Stop / 普通冷启动成功。没有清库或卸载。
- A / G / H：新 FULL Session 在实际 UI 将当前页 40→42，自动保存测试草稿；5 分钟 Break / 提前结束 → RECOVERY。最终版本连续 91.753 秒自动回 FOCUS，成功事件 1 条，Coverage FULL。截图 `06-recovery.png`、`01-reading.png`。
- B：cross-app OFF；Chrome 约 2 秒短访仅 brief，随后实际长访确认 DISTRACTION / FULL，无 Overlay 或干预通知，回 Mirra 出现原应用内提示（`02-in-app-prompt.png`）。
- E / F：首次 Chrome 提示零等待，四用途投影为 3/5/5/3 分钟；REPLY grant 后 TEMPORARY_ALLOWANCE，原 DISTRACTION 保留。一次延长使 planned duration 180000→300000ms、extensionCount=1、入口消失（`05-allowance.png`）。Clock 短访不结束 Chrome 授权；首次 Clock 长访检查未获得确认，作为不确定观察保留，不声明失败或通过。随后明确核对 Clock top-resumed 并持续访问，确认其 DISTRACTION；给予 Clock 授权后 Chrome 长访确认，关闭 Clock allowance、转为 Chrome DISTRACTION，旧授权不恢复。同一 Chrome 第二次确认用途面板显示约 5 秒等待。
- C / DND：正式 Profile 打开 DND 和 cross-app preference，新的 Session 为 FULL / ACTIVE；Mirra-owned rule 下 Chrome 风险确认实际展示有限宽 Overlay（`03-overlay.png`）。实际关闭后仍 DISTRACTION，不伪造 Recovery；Home 正常。下一有效 episode 点击“回到学习”返回原 Session / RECOVERY，无直接成功事件；再一 episode 点击“临时使用”打开四用途面板和 15 秒等待，表内仍为 DISTRACTION，没有直接 grant。
- 观察边界：部分连续快速切换/点击脚本没有先确认新的 prompt token，旧 episode 已关闭时点击落在底层 Chrome；这些操作不计成功。后续动作必须先核对真实 window owner / 新风险确认，再操作，不能靠固定等待或无异常推断用户闭环通过。
- DND 正常结束：本轮已实际看到 NORMAL / RELEASED，Monitoring 与 DND 释放；后续渠道和权限矩阵结果如下。
- Overlay 第四入口：新有效 episode 点击“打开 Mirra 结束”只定位原结束操作，Active Session 仍为 1，没有自动 finish。另一实际 Overlay 中撤销权限后窗口 owner 消失，降级到干预通知；FULL 与 ACTIVE 未改变。
- D / H：Overlay denied / POST granted / DND OFF 的新 Session 实际出现 ID3002 干预通知和 ID3001 FGS 通知。用户展开通知栏并点击“查看”后回到原 Session；Session count 6→6、Active 1、单一 FGS、Allowance 0，通知清理，实际应用内 composition 后才写 IN_APP SHOWN。DND ON 的 fallback 仅证明 POSTED，不证明用户可见/发声；channel `mBypassDnd=false`。
- 权限 E / F：在系统渠道设置关闭干预 channel 后，无 ID3002/POSTED，UNAVAILABLE 1 条；恢复 channel 后在新 Session 拒绝 POST、Overlay denied，仍 UNAVAILABLE 1 条 / FULL，返回 App 可处理原提示。未将权限未满足记成平台 PASS。
- 中途 Usage Access 撤销：真实 FULL → PARTIAL + UNMONITORED，外部渠道与 FGS 清理；重新授权后本次仍 PARTIAL，正常结束可用。
- Controlled stop：实际开发诊断“停止监测测试”后 PARTIAL + UNMONITORED，服务列表为空；返回原 Session 显示“监测已中断”，正常结束可用。
- Force Stop：实际 FULL / DND ACTIVE / Overlay 可见时强停；窗口与 FGS 消失。离线冷启动 recovery 后原 Session ABNORMAL / PARTIAL / DND RELEASED，历史 FOCUS / DISTRACTION 保留并追加 UNMONITORED。重放旧 OPEN_ALLOWANCE URI 与匹配旧 extras 后，Session count 10→10、Note 4→4、Allowance 2→2、Active 0，服务列表为空，没有旧用途面板。

## Remaining boundaries

- API 23–36、OEM、实体设备、TalkBack、发行环境：未执行的项目继续 NOT RUN；API37 AOSP AVD 不外推为其他平台 PASS。
- 一加 13T 以个人日常 smoke 反馈记录，不冒充 OEM compatibility PASS；本轮不自动操作用户实体机。
- 历史失败保留在 3C-1 / 2 / 3 checkpoint，不因最终交付删除。
- 3D / Closeout / effective metrics 尚未实施。

## C01–C25 evidence matrix

状态按证据边界记录，自动化 PASS 不外推为未测 Android / OEM 设备 PASS。C01–C24 在约定的自动化 / API37 AVD 范围内通过；C25 完成 APK 与个人试用 checklist 交付，新版本实体机反馈仍 NOT RUN。最终完整回归和覆盖安装见下文；不存在“25 项实体设备全 PASS”的结论。

| ID | Evidence / result |
| --- | --- |
| C01 | ViewModel + Compose/Room 逐字页码与旧页 Note 回归；AVD 正式 Start→Intent→Preparation→Session、自动保存、正常总结 |
| C02 | Risk 候选 JVM 与 AVD Chrome 约 2 秒短访仅 brief，不加确认等级 |
| C03 | `BehaviorPolicyTest.secondConfirmationWaitsFiveSecondsAndThirdAndLaterCapAtFifteen`；AVD 同包 0/5/15 秒，包级计数独立 |
| C04 | Repository confirmation 幂等、Presenter repeatedToken / dismissal / newToken 测试；AVD 同 episode 不重叠或重弹 |
| C05 | actions dismiss/return 与 Compose 四操作测试；AVD Close 不写 Recovery success 或 Allowance |
| C06 | Room grant 保留 DISTRACTION；AVD REPLY 授权后才开始 TEMPORARY_ALLOWANCE |
| C07 | Room concurrentExtension 成功一次；AVD +2 分钟后 extensionCount=1 且入口消失 |
| C08 | actions/Room A 与 B 不互为白名单；AVD Clock 短访保留 A，B 确认关闭 A，后续不恢复旧授权 |
| C09 | actions Break suppress / freshCandidate、Room expiry deadline 与 lateExtension；未人工等待完整 5/10 分钟到期，不伪称实际等待 |
| C10 | Room healthyBreakExpiresAtDeadlineInRecovery；AVD 5/10 分钟提前结束先 RECOVERY |
| C11 | pure 89.9/90 秒与 Room 证据边界；实际 AVD 连续 91.753 秒成功一次，FULL 不变 |
| C12 | Unknown/失焦/证据缺口、健康空查询、旧页面样本反转与真实 6 秒缺口 JVM 回归 |
| C13 | actions dismissAndReturn 不声明成功；AVD Overlay Return 后仍 RECOVERY，不直接 FOCUS |
| C14 | Stable 120s、Deep 900s / screen-off 600s、PARTIAL/NONE/解锁规则自动化；未实际等待 15 分钟 |
| C15 | Room NONE Break → UNMONITORED；最终重新编译 APK 再次实际 NONE Session / 5 分钟 Break 提前结束 / NORMAL 40–42 页总结，未伪造 Focus |
| C16 | Room expiry/extension 竞态、monitoring gap 先落库、结束拒绝旧动作；冻结采样竞态测试全保留 |
| C17 | stale action 与 endedSession / Presenter 测试；AVD 已结束旧 URI 回放 Active=0、无 FGS、无授权新增 |
| C18 | Presenter revokedOverlay / deniedOverlay；AVD 真撤销后清窗口并通知降级 |
| C19 | channel 平台不 bypass DND 与 POSTED≠SHOWN；AVD DND ACTIVE 下 Overlay 与通知 fallback 分别实测 |
| C20 | Presenter lateOverlay / lateNotification / owner removal failure：迟到、重复不复活旧 token |
| C21 | Compose 窄屏大字体、Back/草稿保护与 Overlay 平台边界；AVD Home/Close/四入口；实际 320dp/fontScale2 与 360dp、411dp，关键操作可达 |
| C22 | AVD 强停后 ABNORMAL/PARTIAL/UNMONITORED、DND RELEASED，无 FGS/旧用途；旧动作前后计数不变 |
| C23 | 最终重新编译 APK 的离线 / 覆盖安装实际 PASS：书籍、Note、图片、正常 Session、进度、Caption、preferences / risk selection 前后对照一致 |
| C24 | Room version=4；schemas 1–4 全部冻结 hash 一致，Entity/DAO schema/Migration diff 空；Migration suite 纳入完整 connected |
| C25 | 最终 APK 交付用户个人试用；本版本一加 13T 实际安装/试用待反馈，NOT RUN；历史日常“目前正常”不升级 OEM PASS |

## Final unfiltered regression — same final code

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --rerun-tasks`：5m57s，85 tasks 实际执行，BUILD SUCCESSFUL。完整 JVM XML：223 total / 223 passed / 0 failed / 0 errors / 0 skipped。lint XML：0 errors / 9 existing warnings / 1 hint。没有因警告或环境波动放宽断言。
- `:app:connectedDebugAndroidTest --no-daemon`：7m42s，单次完整未过滤 BUILD SUCCESSFUL，最终 XML 178 total / 178 passed / 0 failures / 0 errors / 0 skipped。新增页码 Compose/Room 测试实际执行；DND 平台 3 项、渠道平台 5 项均实际执行，未靠 assumption skip 充数。Room / Migration、Phase 1/2、Start/Theme/3A/3B/3C 测试纳入全量。
- AVD 在完整重编译期间关闭，编译结束再保留数据启动；初次 UI dump 在刚 boot 时返回 null root，等系统就绪后新 dump 成功，不复用旧 XML。正式 connected 期间 ADB 保持 device、System Server PID 684 不变；没有失败测试拼接或新的系统进程死亡。
- connected runner 结束会卸载它管理的测试安装，因此完整 suite 前专用数据不作为最终覆盖安装保留证明。没有人工 uninstall / pm clear / wipe-data；runner 后重新 `install -r`，通过正式 UI 建立独立保存样本，再执行最终覆盖安装对照。
- 六类截图和长 Recovery / 跨应用联调使用同一最终生产源码；强制完整重编译重新产出的 APK 以交付 checksum 为准，不把先前不同 packaging 的二进制 hash 当作最终值。

## Final binary / persistence / accessibility

- 最终重新编译包实际建立 `Mirra-3C4-Preserve`（320 页、起点 40），Usage denied 仍正常阅读，40→42，测试 Note 自动保存；5 分钟 Break 提前结束为 NONE + UNMONITORED。NORMAL Summary 为 40–42 页 / 2 页 / 1 Note，无 +1。
- 通过系统 Photo Picker 导入专用测试截图，输出为 App-owned JPEG（1080×2400、130765 bytes），只保存 `images/<uuid>.jpg`；Caption `Mirra3C4 caption` 自动保存。离线全屏可打开；搜索 `Mirra3C4` 将 Note 正文与 Caption 合并为一个结果。
- 实际 411dp 基准、360dp 常规和 320dp/fontScale2.0 检查：Session 文字/类型可换行并滚动，Break 选择/提前结束、用途四理由/返回/关闭/结束、Allowance 延长确认、原结束阅读入口均可达。320dp/fontScale2 Overlay 四入口完整位于屏内、不占系统状态/手势区；触控区域沿用既有至少 48dp 组件/原生 Button。Activity recreate 后通过 Start 返回同一 Active Session，没有新建 Session 或重复授权。TalkBack 未执行，不称完整 accessibility PASS。
- 全量 runner 后建立保存样本，最终 `adb install -r` 成功，Wi-Fi=0 / mobile_data=0 下 Force Stop / cold start 成功。对照前后均为 Learning Item 1、Note 1、Image 1、NORMAL Session 2、risk App 1、currentPage42；正文、Note 页42、Caption、130765-byte 文件元数据不变，Room user_version4。DND preference=false / cross-app=true 在覆盖与冷启动后保留；全部图片与原 Note 图片查看入口可打开。
- 专用 AVD 最后 Active Session=0，DataStore 通过正式 UI 恢复 cross-app OFF / DND OFF，Usage/Overlay AppOps default、POST revoked、DND access disallowed、Wi-Fi/data ON、fontScale1、density420；随后保留数据关闭 AVD。未自动操作一加 13T。

## Debug APK delivery

- 稳定交付副本：`C:/Users/CDD/Documents/ChatGPT/Mirra/build/deliverables/Mirra-3C4-debug.apk`。
- 构建输出：`C:/Users/CDD/Documents/ChatGPT/Mirra/app/build/outputs/apk/debug/app-debug.apk`。
- Bytes：15874132；SHA-256：`4271EBE0BEF656098FA83F0ADAD9781574E113AB2FA4153ED7ED00F4D79087EE`（副本与输出一致）。
- applicationId `com.guanyi.mirra`；versionName `0.1.0` / versionCode `1`；debuggable / minSdk23 / targetSdk37。未生成 release signing，APK / keystore / 本地日志不提交 Git。
- Build source：冻结起点 `6384cc634b080ac9554ac20cd4c95b9b5e13a262` + 本交付的单行 ViewModel 输入修补；修补文件 Git blob `24252f3fd4d4a99c138df7d08f3bd134d679a105`。最终交付 commit 是包含本 checkpoint 的 `feat(focus): finalize module 3c experience`，完整 SHA 在 Git 与最终报告核对；不存在向自身文档回写自身 commit SHA 的循环。
- 一加 13T：APK 与上面的八步 checklist 已提供用于用户自主个人试用；本二进制的新反馈尚未取得，NOT RUN。此前“使用目前没什么问题”只记历史 daily-use smoke，不是 OEM compatibility PASS。

## Schema freeze

`MirraDatabase.version=4`；无新的 Entity / DAO schema / Column / Index / Migration，未生成 v5。最终重编译后四文件 hash 与起点一致：

| Schema | SHA-256 |
| --- | --- |
| 1.json | 4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1 |
| 2.json | C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D |
| 3.json | CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205 |
| 4.json | EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9 |

## Known limitations / handoff

- 本轮没有确认冻结核心的新缺陷；仅修逐字页码输入。作者自查不是用户独立验收，不自行宣称发布级 3B / Phase3 全部冻结。
- API23–36、OEM、实体机完整矩阵、TalkBack、release/发行环境继续 NOT RUN；9 个既有 lint warnings 与 1 hint 未在此包扩大修复。
- Overlay 可被 OEM / 系统进一步限制；Notification POSTED 不等于可见，DND 可能抑制通知。外部渠道均不可用时回 App 才提示，Session 继续。
- Force Stop 不自动恢复旧 Session/监测；Usage Access 中途恢复不把 PARTIAL 升 FULL；Stable/Deep 是保守规则证据，不是大脑注意力检测。
- 3C-1～3 仍沿用用户冻结语义，3C-4 完成联调/个人试用交付后停止，等待用户最终独立验收。Phase3D、Closeout、effective metrics 均未开始；不改写 Phase2 Session 总时长/阅读速度历史语义。
- Git 作者自查范围：只有单行 SessionViewModel 输入修补、两项回归测试、CURRENT_STATE、本 checkpoint 与最小脱敏截图。无新的架构决策，DECISIONS 不重复增加同义条目。最终检查 diff/秘密模式、Conventional Commit 与当前分支普通 Push，不 merge main / force push / rebase 冻结历史。

## Personal trial checklist — OnePlus 13T

本包提供 Debug APK，不自动操作日常手机，不要求 Root、解锁、清数据、后台白名单或运行设备自动化。签名不匹配时停止，不能以卸载解决。

1. 正常开始一次学习。
2. 写一条笔记，确认自动保存。
3. 开一次 5 分钟休息，可提前结束。
4. 把一个常用 App 加为风险 App。
5. 自主授权后试一次跨应用提醒。
6. 试一次临时使用。
7. 回 Mirra 继续学习。
8. 正常结束，确认页码、笔记和勿扰释放。

没有授权时仍可阅读；无外部渠道时回 App 才提示。用户反馈仅记个人 smoke，不能替代发布级兼容性矩阵。
