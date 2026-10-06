# Legacy finish contract correction and fresh AVD final gate

执行日期：2026-10-06。状态：**Phase 3D-4 validation complete, awaiting independent acceptance.** 本记录不是 Phase 3D Freeze，也不授权下一阶段。

## Baseline and permitted changes

- Branch: `codex/phase-3d-final-validation`。
- 本轮授权入口 HEAD：`5987692e65b3f3bbd9e52124ab78d9beae136b2b`。
- 精确生产父 Freeze：`80ece95cf24918627e57a57bb6a6d94c253528b0`。
- 独立 test-only commit：`768127951c57e01aa09ed16de4c2e5e9ee929b4c`，`test(session): verify draft flush at durable closeout boundary`；仅 `PhaseOneCorrectionTest.kt`。
- 生产代码、JVM 测试、Room / Entity / Schema / Migration、Manifest、Gradle / 依赖相对父 Freeze 均无变化。没有 production fix commit。
- 一次性 restore helper 与新 AVD fixture guard adapter 均已从源码移除；最终全量前和最终交付后均覆盖恢复 canonical test APK。它们不进入 Git，不增加生产入口。

## Legacy oracle correction

`PhaseOneCorrectionTest.finishingImmediatelyFlushesDraft` 现在直接验证冻结的 durable contract：

1. 保留当前 `sessionId`，输入“最后一笔”，请求结束，等待真正的最终确认框。
2. 确认框出现后立即读取 Note Repository 的第一份快照，不等待 Note 数量最终满足：exactly 1、正文“最后一笔”、同一 Session、page10。此断言位于最终确认之前。
3. 只点击一次最终确认，等待真实 Session `NORMAL / endedAt != null / activeSlot == null`。
4. 直接读取 ReadingRecord `noteCount == 1`，并确认保存的同一个完整 Note 仍然存在。

移除的是 Summary 渲染“1 条笔记”的 5000ms presentation deadline，不是增加等待时间；durable closeout 等待仍是 5000ms。未增加 sleep / retry、重复确认或 production 改动。历史重复 full-suite timeout 是已授权 RED 证据，没有人为制造新 RED。

提交前四类实际 GREEN：PhaseOneCorrection **5/5**、SessionCloseoutUi **6/6**、ReadingRecordNavigation **4/4**、ReadingRecordRepository **4/4**，合计 **19/19 / 0 assumptions**。最初组合 Gradle class 参数的 XML 实际只选中第一类5项；后三类分别显式执行，未将初次命令写成19项。它们均在最终唯一 full 之前，不是失败后 targeted 补绿。

PhaseOneLearningLoop、ReadingRecordNavigation / Ui / Repository、SessionCloseoutUi 原有 Summary 覆盖不变。

## Old v4 exact restoration, without consuming its failure

旧 `Mirra_API_37` 仅运行 marker-scoped `validation_restore / storage-isolation-v4`，实际 **1 executed / 1 passed / 0 assumptions**，0.412sec。前置 Session / Intent / Segment 均0；恢复只经既有 Repository：

- marker exact original 四偏好恢复为 `knowledge / blue / false / false`，读回完全相等。
- 排除该 fixture risk row 后整表 hash 已等于 original；只移除这一条模拟选择，最终原一行 hash `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8`。
- SEEDED marker SHA 始终 `633681c91ffb61f1efff601bca7c64f55450d4f25d1afb3d1d7efc62a2184c85`；baseline frame `5f64ecb5c28150c313911516623a30dfdd8e63bf1fddf52d5158afb87bc86e35`。
- JPEG `images/e817709f-1c48-4b63-aa02-055d35a62c41.jpg` 始终818bytes，SHA `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`。

没有 v4 `data_assert`、reseed、修改 marker、删除业务 fixture 或补旧缺失 JPEG。canonical test APK 恢复后旧 AVD 正常关闭、userdata 和全部历史保留；它不再承担最终 clean gate。

## New environment and health gate

新建 **Mirra_API_37_Final**，实际 Android **17 / API37 / qemu1**；使用同一已安装 `android-37 / google_apis / x86_64` 系统镜像，build `CE2A.260420.019 / 15611780`。这是 AOSP/Google APIs 模拟器，不是 Pixel 或 OEM 实体机。fresh userdata，没有 clone / snapshot restore / 旧 DB 或 marker 导入。

第一次启动在创建默认10GiB userdata前因主机可用空间不足失败：需要12GiB、实际11.65GiB；Android 尚未 boot，测试没有开始。仅将**新 AVD 首次建盘前**分区设为4GiB，之后正常首次启动；没有清理旧数据、wipe、pm clear 或 uninstall。此 setup error 保留在本地，不计 Mirra FAIL。

完整稳定窗口 **67.6497279sec**，内含4轮检查、约16.5 / 17.5 / 16.4sec间隔；四个采样点首尾跨度50.484sec，不将其说成四点跨度≥60sec。每轮真实确认 boot1、activity/package/window/power service found、shell成功、MainActivity可解析。预检、full前后及最终收尾 lastanr 均为 `no ANR has occurred since boot`。

为新 v5 fixture 专项临时适配原 guard：仅显式 `storage-isolation-v5` + data_seed/data_assert/validation_restore 可选择真实 `Mirra_API_37_Final`；其余仍是原 AVD，API37/ranchu/getprop校验不变。没有伪造属性或放宽实体设备访问。seed 后恢复原 guard 和 canonical test APK，**唯一 full 在原始 guard 下执行**；assert 专项后再次恢复并重新构建/覆盖 canonical test APK。Git 不包含此适配。

## Single unfiltered connected run

唯一最终 full：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" --no-daemon
```

UTC 2026-10-06 **07:15:19.725441 → 07:23:41.520650**，501.795sec；Gradle `BUILD SUCCESSFUL in 8m 21s` / exit0。没有 class filter、第二次 full 或 full 后 targeted 补绿。

| XML / execution meaning | Actual result |
| --- | --- |
| discovered | 286 unique cases |
| executed / passed | **282 / 282** |
| business assertion failures / errors / incomplete | **0 / 0 / 0** |
| expected opt-in assumptions | **4** |
| other skipped / unmet permission assumptions | **0 / 0** |
| raw AGP XML | 286 tests / **4 failure nodes** / 0 errors / 0 skipped |
| raw XML SHA-256 | `0744B9F008645020E4613485DD450EE7EE0E3EFDDEEE0A04A6CD5BBCB3A80A03` |

四 failure 节点全部为 `AssumptionViolatedException`：pending_prepare、pending_assert、data_seed、data_assert。它们默认不实际执行，不计 PASS；保留原始 XML 计数，不把 raw failure 改写为0。

实际进入主体并 PASS：

- corrected `finishingImmediatelyFlushesDraft`；PhaseOneLearningLoop完整学习闭环。
- DND platform **3/3**；intervention channels **5/5**。granted overlay/notification是真实平台路径，拒绝渠道用 capability seam；后者不是本轮真实撤权操作。
- MainActivity theme lifecycle **3/3**，包括初始 Night/Blue 的 exact restoration。
- ReadingRecord **320/360/411dp × fontScale1/2 六项**及 Session窄屏大字 escape 项。是实际 Compose 自动化，不冒充本轮人工改变系统字号。
- 原有 Room/Migration、Closeout、timeline/effective、Note/Image/Topic/Search 与行为回归均在这一次未过滤 run 内。

full 前后 lastanr 未变，crash buffer没有 DeadSystem/system_server/SystemUI crash，无 transport abort或系统服务消失。green XML与环境观察分别核对，没有仅由绿色报告推断环境健康。完整 XML、logcat与设备标识只保存在本地，不提交。

## v5 real-storage preservation chain

全新独立 key **storage-isolation-v5**，不重用 v2/v3/v4。

| Gate | Actual |
| --- | --- |
| data_seed | **1 executed / 1 passed / 0 assumptions**, 0.775sec |
| immediately pre-full → immediately post-full, before installation | JPEG / four preferences / full risk hash / SEEDED marker bytes exact unchanged |
| candidate `install -r` → post-cover snapshot | Success; same four groups exact unchanged |
| data_assert | **1 executed / 1 passed / 0 failure / 0 assumptions**, 0.967sec |
| existing fixture success cleanup | exact original preferences/risk restored; normal consumed marker state |

真实 seed/frame：

- baseline frame hash `fed286a9688774c0ba7c691c84a45ed382b34321c326c403e489a376ea2dd49d`。
- seed/pre-full/post-full/post-cover prefs `knowledge / night / true / true`。
- pre-seed original empty risk hash `74a6760bc31e8746ee0a56de778654a51e980f9256623e709fb950a006c96d40`；seeded one-row hash `1d55e8eb7959e03e1907cb1661b9cfb520150496c5a5f1c136d399915cd0d94d`。
- SEEDED marker SHA `047d82b8d80332fbbbb0962627bf9aa13529ba4b3fd4763ecac5a8c5bf30c1ea`，跨 full / candidate install 未变。
- JPEG relative path `images/7c0bde67-d08a-43f2-97e8-30d3949b472d.jpg`；真实与 metadata 均818bytes；SHA `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`，全链与最终离线收尾未变。

data_assert真正检查 Learning Item、两个 Note、ImageAsset/JPEG、Topic/CrossRef、Session facts/Segments、历史 risk snapshot、legacy无段记录、trusted旧记录、Search、四偏好及没有effective回填。它不是只比行数，也不是用普通 suite assumption替代。

成功清理后与最终读回：prefs `start / blue / false / false`，risk0/原空表hash，Active Session/Intent/Segment0/0/0。marker正常进入 **VERIFIED_PREFERENCES_RESTORED**，SHA `891c7150e54754851162569090c5f81aa36771c7454fed3a48036153746416cd`；该一次状态/hash变化是既有成功消费，不是修改失败marker再重试。

## Fresh JVM, lint, build and frozen schema

clean connected + v5 assertion成功后，重新执行：

- JVM：`testDebugUnitTest --no-daemon --rerun-tasks`，26 tasks实际执行，**318/318 / 0 failure/error/skipped**，45份XML，2m47s。
- lint：`lintDebug --no-daemon --rerun-tasks`，28 tasks实际执行，**0 errors / 9 existing warnings / 1 hint**，4m17s。
- `assembleDebug :app:assembleDebugAndroidTest --no-daemon`：**PASS**，1m5s；前者最终 target，后者恢复没有临时 adapter 的 canonical test APK。没有为了文档重复 connected。
- `git diff --check` PASS，Room `version = 4`，仅1–4 schema，Entity/Migration/Manifest/Gradle/production diff为空。

| Schema | SHA-256, unchanged |
| --- | --- |
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

## Exact delivered APK and actual offline reads

- Artifact：`build/deliverables/Mirra-3D4-debug.apk`，**16488770 bytes**。
- SHA-256：`9C9B33337A8FB9A3969089308C28E8302AB285939DFBD47A4103CC7D48C68CD9`，以本轮最终文件读回为准；不要求等于旧 archive hash。
- source/build HEAD：`768127951c57e01aa09ed16de4c2e5e9ee929b4c`；生产仍是父 Freeze，后续 evidence-only commit不改变此构建来源。
- appId `com.guanyi.mirra` / versionName **0.1.0** / versionCode **1**。
- 对**该交付文件** install-r Success，canonical test APK亦install-r恢复；普通 force-stop/cold-start `Status: ok`，实际安装版本核对一致。
- 新 AVD Wi-Fi/mobiledata真实读回 **0/0**后再force-stop/cold-start成功。断网真实 UI 打开 History、Search SESSION、Note正文、全屏本地JPEG、Topic关联两条Note。没有新建学习事实或修改正文/页码。

| New actual UI capture | Evidence classification |
| --- | --- |
| [History expanded](fresh-offline-history.png) | 读取v5 NORMAL/NONE保存记录：30→40页、1Note、监测中断；不是新真实阅读闭环 |
| [Search trusted record](fresh-offline-search-fixture.png) | 真实Search入口读v5受控trusted旧记录，20→30页/阅读+休息；**fixture，不是本轮真实FULL监测证明** |
| [Note](fresh-offline-note.png) | 真实断网读取受控standalone正文及Topic/图片，没有编辑 |
| [Local JPEG](fresh-offline-local-jpeg.png) | 818byte受控白色JPEG实际解码/全屏/Caption，白色不是缺图占位或失败 |
| [Topic](fresh-offline-topic.png) | 真实断网Topic详情及两个关联Note（p35/p25） |

这些截图都是实际 AVD UI，数据来自明确的 preservation fixture；不是概念图、普通真实学习或实体机证据。原7张fixture automation、旧真实离线和真实FULL平台截图继续各自保留分类。

## Exact restoration and final cleanup

权限修改前真实原状态均可读取；第一次在 target尚未安装时的过早读取不具备POST前提，已停止该读取并在安装后、任何grant之前重新捕获完整原值。

| Capability / state | Original | Temporary | Final actual readback |
| --- | --- | --- | --- |
| Usage Access appop | default | allow | default |
| DND policy approval | false | true | false |
| Overlay appop | default | allow | default |
| POST_NOTIFICATIONS | false | true | false，原flags完全一致 |
| Wi-Fi / mobiledata | 1/1 | offline读取0/0 | 1/1 |
| Preferences | start/blue/false/false | seed knowledge/night/true/true | start/blue/false/false |
| Risk selection | 0 / original empty hash | one synthetic fixture row | 0 / exact original hash |

POST flags始终 `USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED`；appop历史访问时间不是权限mode变化。最终 Zen OFF、own rules0/意外active0、FGS0、Overlay0、intervention notification0、Active Session/Intent/Segment0/0/0。最后无ANR sinceboot。没有修改global Notification Policy或其他App/实体手机权限；没有wipe/pm clear/uninstall/系统时间修改。

## Inherited actual evidence, retained failures and NOT RUN

本轮授权不要求重跑90秒Recovery、Deep15min或全部人工平台闭环；以下只引用**已提交的此前实际证据**，不写成本轮再次执行：

- [Earlier crash/preservation checkpoint](../../checkpoints/2026-10-04-module-3d-final.md)：`f39277b6703e4191a3dca7043fbb711ce68164f5`，pending_prepare1/1 + 实际Force Stop/cold start + pending_assert1/1；原time/page NORMAL/COMPLETED，无FGS复活。不是硬件断电。
- [Remaining actual platform flow](final-remaining-platform-flow.md)：`50d9a0bdf339c623169cb2f1e6f3924a7bc2631c`，真实FULL→risk/notification→Allowance/一次延长→Recovery **91.661sec**→Focus→Closeout→Summary原地展开/History/Search；结果停留≥32sec边界不增长。near-finish真实Usage撤权/controlled monitor stop先durable PARTIAL/UNMONITORED，再NORMAL结束。
- [Recovery diagnostic](recovery-evidence-chain-diagnostic.md)：历史>120sec异常仍 **Observed once / Not reproduced in targeted diagnostic / INCONCLUSIVE**。后续90.827/90.858/91.006和91.661sec成功不宣布FIXED或AVD ROOT CAUSE。
- [Image/preference failed history](test-image-storage-isolation.md)、[theme interrupted history](theme-isolation-and-final-preservation.md)、[old AVD timeout/ANR](stable-environment-revalidation.md)及原checkpoint保留。旧缺JPEG/ImageAsset/marker未修；v2 FAILED、v3 interrupted/unconsumed、v4 failed/unconsumed和旧Compose/DeadSystem/SystemUI ANR记录未删改。

继续 **NOT RUN**：API23–36 full matrix、OEM matrix、完整实体机compatibility、TalkBack、release/Play、真实硬件断电、真实系统时钟人工修改、实际15分钟Deep Focus、真实OS query-gap操作。本轮没有操作一加13T；其旧日常使用反馈不升级为OEM PASS。

最终状态只有 **validation complete, awaiting independent acceptance**。内部只读交叉核对不冒充用户独立验收；Phase3D未Freeze，不进入下一Phase。
