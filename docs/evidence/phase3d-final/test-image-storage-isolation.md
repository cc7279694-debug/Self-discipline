# AndroidTest image storage isolation — 2026-10-06

## Authority and source

用户独立审查裁定为 **TEST HARNESS ISOLATION DEFECT**，授权修复 androidTest 文件隔离并在新 evidence run 上重验；没有证据认定生产 ImageStorageService / ImageRepository 删除了旧 JPEG。本轮不修改任何生产代码、JVM test、Manifest、Gradle、Room、Entity、Schema 或 Migration。

- Pre-patch HEAD: `4d995534306291aae84bcb71968f98fc745d17be`.
- Test-only patch: `9cfae55898ad6a1a21b0afb8a943a81599ad9ef8` — `test(storage): isolate android image fixtures`；定向 GREEN 后已独立 commit / Push。
- Branch: `codex/phase-3d-final-validation`.
- Environment: 明确绑定专用 `Mirra_API_37` AOSP AVD、Android17/API37、virtual/boot completed；另一个虚拟 transport 在线但未操作，完整 Gradle 用本地 ANDROID_SERIAL 限定目标，日志确认只运行 Mirra_API_37。不操作实体手机，不提交 transport serial。
- 内部只读审计不冒充用户独立验收；Phase3D 不自动冻结。

## Test-only isolation implementation

`TestImageStorageSandbox` 让每个测试实例拥有唯一 UUID 根目录，位于真实 App 的 `image-work/camera/mirra-test-sandboxes/<UUID>/`。测试专用 ContextWrapper 的 filesDir 指向该根，applicationContext 返回 wrapper 自身，packageName 继承 target。真实 DefaultImageStorageService 仍产生 `images/<uuid>.jpg` 相对路径，但物理文件在 sandbox 内，而不是 target 的正式 images 目录。

close 只能删除自己 UUID 根；删除前同时检查 parent/root 的 canonical/absolute 一致及严格子路径关系，拒绝 parent、sibling、外部路径和符号链接重定向。TestAppContainer 先关闭自己的 Room，再关闭 sandbox，不再删除共享 images/image-work。camera test 写入使用 androidTest-only `resolveImageStorageTestPath`，不修改生产存储接口。

### Full owner audit

| File / owner | Change or audit conclusion |
| --- | --- |
| TestAppContainer | 自有 sandbox；所有使用它的 UI / Repository 测试自动隔离 |
| ImageStorageServiceTest | setUp/tearDown 自有 sandbox；rotation、JPEG、collision、corrupt/empty、cancel、trash、orphan 原断言保留 |
| ModuleTwoANoteRepositoryTest | 真实 DefaultImageStorageService 改用 sandbox；Note 删除的物理行为仍真实 |
| ModuleTwoCSearchRepositoryTest | sandbox；不能依赖 ImageAsset 对应文件恰好不存在 |
| ModuleTwoCTopicRepositoryTest | sandbox；原 Topic / Note / image 事务断言保留 |
| StudyWorkflowRepositoryTest | sandbox；原 Workflow/Note/事务断言保留 |
| ModuleTwoBFlowTest | camera target 写入改为 container sandbox resolver；仍真实 createCameraTarget→JPEG→completeCameraImport |
| ModuleTwoBImageRepositoryTest FakeImageStorage | 仅受控 cacheDir/image-repository-test 与 fake bookkeeping，清理范围不是生产根；无需修改 |
| Screenshot / pending fixture | 写截图 cache/media 或 noBackup marker，没有真实 image root 的破坏性清理；无需修改 |
| ModuleThreeDDataPreservationTest | **继续真实 MirraApplication.container / installed mirra.db / App-owned files**，不 sandbox 化 |

### FileProvider cache issue during directed validation

首次六类定向：40 discovered / 32 passed / 8 failed。首个 sandbox camera 成功，后续 sibling camera 出现 `Failed to find configured root`。诊断确认 FileProvider 的 strategy 按 authority 静态缓存，若第一次以 wrapper 初始化，会错误缓存第一个 sandbox 的 files-path。

只修 test helper：创建 sandbox 前用 native targetContext 建立现有 camera provider 的路径 strategy。probe 仅用于构造 URI，不创建、读取、写入或删除文件；不扩大 production FileProvider path、不改 Manifest、不反射重置缓存。新增双 sandbox camera URI + 真实 ContentResolver 写入回归，确认两个文件及生产 temp 不串用。首次失败记录完整保留在仓库外 local validation log，不归因生产 camera Bug。

## TDD / directed evidence

- 隔离回归 RED：2/2 按预期失败（sibling sentinel 被删除；A/B root 相同），在修改前实际执行。
- 初始 helper GREEN：8/8；之后新增真实双 camera URI 回归。
- 最终 compile androidTest：PASS。
- 最终 helper / run-key 定向：**9/9 PASS**，0 failure/error/assumption。
- 最终六个指定 owner 类：**40/40 PASS**，0 failure/error/assumption；保留上面的第一次 8 failures，不删原断言或延长 timeout。
- 新增普通测试：sandbox5 + evidence run-key4。canonical 越界、独立 applicationContext、sentinel 字节保留、A.close 不影响 B、真实两 camera、missing/invalid/boundary key 均覆盖。
- patch 总计12个 androidTest 文件，新增4、修改8，292 insertions /25 deletions；production diff=none。

## Old damaged evidence — permanently retained

- ImageAsset: `bbd73522-cf32-404c-819f-10a6d53526d4`.
- localPath: `images/8cdecdc5-70c7-4e3e-86e5-910ac5acc5d8.jpg`；metadata fileSize818，JPEG 仍缺失。
- 旧 default marker 仍是旧 VERIFIED_PREFERENCES_RESTORED，SHA-256 `6bbd53c86d2cd35dd1b99e74af79e28925c9335cce45e9c73ffc1de54a7fd445`。
- 未补 JPEG、未删行、未改 localPath、未覆写旧 marker；旧 marker 的历史 PASS 不证明当前缺失 JPEG 仍保留。没有 full 前即时 hash 的旧 run 不被指定为精确删除时点。

## New real-storage fixture — independent evidence

仅显式 instrumentation 参数 `mirra3dEvidenceRun=storage-isolation-v2` 选择新 marker：`noBackupFilesDir/mirra3d-preservation/storage-isolation-v2/manifest.json`。key 1–64 ASCII letters/digits/underscore/hyphen，非法值拒绝，不 trim/fallback；无参数精确保留旧默认路径。

新 fixture 通过真实 Repository 创建专用 Learning Item、Note、JPEG/ImageAsset、Topic/关联及合法 Session 事实。闭合 legacy-shaped / trusted-old rows 是明确的新增受控 fixture，不冒充真实 Monitoring。seed **1/1 实际执行 PASS**，未重用或覆写旧 marker。

| Field | New fixture baseline |
| --- | --- |
| ImageAsset | `cf5826e7-57c4-4739-90f8-d911b5c7e8c0` |
| localPath | `images/8081cbe6-b0e6-492b-a15f-ef23dca5fdb4.jpg` |
| Dimensions / size | 80×120 /818 bytes |
| JPEG SHA-256 immediately before full | `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc` |
| Full fixture frameHash | `c06afac6563076f1ad9a0aa8d78bac6ed10c80bcd08264aaa677b246d7dbbdb0` |
| Original risk selection table hash | `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8` |

keyed assert 先完整比较业务/媒体/偏好以及读取前后 frame，不补造旧 effective facts。恢复原四项 preferences 成功、且业务/媒体不变后，才在已验证的新 key 中调用现有 Repository 移除本次新增的唯一受控 risk package，再验证整个原 risk selection hash。不删除旧 risk row、不重写旧 marker、不清业务 fixture。

## Final validation results

### Single unfiltered connected run

定向 GREEN 与新 seed 后，仅执行一次完整未过滤 connected，明确指定专用 AVD，保留安装包。实际 Gradle exit0 / BUILD SUCCESSFUL，19m22s；本轮没有用定向重跑拼接全量 PASS。

| Interpretation | Result |
| --- | --- |
| Raw XML | 284 tests /4 failure nodes /0 errors /0 skipped |
| Actual execution | 284 discovered /280 executed /280 passed /0 assertion failures /0 errors |
| Default opt-in prerequisites | 4 AssumptionViolatedException：pending_prepare / pending_assert / data_seed / data_assert；默认不执行，不计入280 passed |
| DND platform assertions | 3/3 实际执行 PASS |
| Intervention channel assertions | 5/5 实际执行 PASS，包括此前 Overlay/notification 权限受阻的两项 |
| Isolation / run-key ordinary regressions | 9/9 实际执行 PASS |
| Previously intermittent Compose cases | PhaseOneCorrectionTest.finishingImmediatelyFlushesDraft 与 PhaseOneLearningLoopTest.completeLearningLoopCreatesBookNotesProgressAndSummary 均在本次 full 实际 PASS；旧失败不删除 |

五个此前 permission-blocked 用例均真正进入主体并完成断言，不能仅把权限 satisfied 当业务 PASS。320/360/411dp×fontScale1/2 的六项原自动化均在 full PASS；本轮不冒充新的人工尺寸验收。

### Immediate JPEG preservation and same-key cover assertion

- full 前、full 结束后立即、覆盖安装及 assert 后，同一新 JPEG 均存在、818 bytes，SHA-256 均为 `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`。因此本次全量没有再次删除或改写这张真实 App-owned 图片。
- 明确目标的 `adb install -r` 成功；之后同一 `storage-isolation-v2` 执行 data_assert：**1 executed /0 passed /1 assertion failure /0 assumptions**。ADB process exit0 不是测试通过；runner 有 `INSTRUMENTATION_STATUS_CODE=-2` 与 `Tests run: 1, Failures: 1`。
- 比较的所有业务、JPEG、Topic/关联、FTS、legacy facts、risk snapshot 项相同；仅 `app_preferences` 不同。baseline 为 knowledge/night/true/true，实际为 knowledge/blue/true/true。原始 marker 的 baseline 和完整失败结果不修改。

| Field | Expected | Observed |
| --- | --- | --- |
| app_preferences frameHash | `7548ed3dd6c8286074724c3c54724fa01543e33a6c58cdbacdf2423addf1c11a` | `d37b683fbd3fc4ec221d395add2ce8399dcc381094f54d9b979cf6f31139c50e` |
| Complete fixture frameHash | `c06afac6563076f1ad9a0aa8d78bac6ed10c80bcd08264aaa677b246d7dbbdb0` | `5db187cd187957145e9ff25050d66f3f9c56e013fc0ed2c2e253549f30a7df29` |

只读源码与精确偏好哈希核对定位到既有 `app/src/androidTest/java/com/guanyi/mirra/ui/theme/MainActivityThemeLifecycleTest.kt`：使用真实 MirraApplication 的 DataStore，finally 固定写回 BLUE，而不是保存/恢复测试前主题。该测试在本次 full PASS，但留下了错误的全局偏好。它不是 production 主题持久化缺陷，也不是 image isolation 再失败；本轮授权仅 image-storage isolation，**没有修改该主题测试或任何生产代码**。

新 marker 状态为 `FAILED_PREFERENCES_RESTORED`，已经消费；不得把 Night 人工写回再 retry、重写 marker/expected 或重新 seed 同 key 来变绿。全部原业务/媒体校验一致也不能代替整体验证 PASS。

### Restoration and narrow next authorization required

- assert 的 finally 已恢复原真实偏好 knowledge/blue/dnd=false/cross-app=false；恢复哈希 `12311ba07fc3daf6152044c4b02fc96a61901e3c0443addb4bba3da2126bda95`。
- 四项临时权限恢复并重新读回：Usage/Overlay app-op default、实际 DND policy access=false、POST_NOTIFICATIONS granted=false 且原 flags 保留。网络 Wi-Fi/mobile data 仍1/1。不能用旧 secure setting 的 null 或历史 DND 日志判断当前权限/规则。
- Active Session/Intent/Segment 为0/0/0；无 Mirra monitoring ServiceRecord、Overlay window、ID3002 intervention notification；当前 Mirra-owned rule STATE_FALSE、系统 Zen OFF。没有操作实体设备。
- **风险选择尚未完全恢复**：断言失败使 `verified=true` 后的新 fixture risk cleanup 没有执行。原受控风险行完整保留；本 run 新增的唯一不可启动模拟包 `com.guanyi.mirra.test.preservation.pd5f7c1869c9a4e4892b4e8bb49aaf187` 仍在 risk_apps（共2条）。没有直接改 SQLite，也没有扩大本轮 patch 增加 failure-only cleanup。后续须单独授权按 marker/原整表 hash 精确校验并经现有 Repository 清理本次模拟选择；不得清旧行或篡改 FAILED marker。
- 旧损坏 ImageAsset/JPEG 状态与 legacy marker hash 均不变，保留为永久历史失败，不补造。
- Room version4、Schema1–4 原哈希和 Migration 不变；v4 hash `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。相对精确父 Freeze 的 production/JVM-test/Manifest/Gradle/Schema diff 均为空。
- 此 gate 失败后停止原3D-4最终流程：未继续新 lint/最终 assemble/最终交付 APK/offline gate；此前318/318 JVM及lint/build属于较早实际记录，不冒充 image patch 后 fresh execution。API23–36 / OEM / full physical / TalkBack / release / hardware power loss / manual wall-clock change / real15min Deep 继续 NOT RUN。

**Image storage isolation 已定向和 full 验证成功；整体 preservation gate 失败，Phase3D-4未完成、Phase3D未冻结。** 下一步需要独立审查授权一个窄 androidTest preference-restore patch、失败 fixture 的精确风险选择清理以及新独立 evidence run 重验；本轮先保存失败证据并停止，不自行修代码。
