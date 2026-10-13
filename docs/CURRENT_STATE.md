# Current State

更新：2026-10-13（Asia/Shanghai）。

## 当前交付

**05C_IMPLEMENTED_AWAITING_NATIVE_AND_DEVICE_VALIDATION**。用户批准真实书籍离线目录和正式腕端整合，选择不迁移独立实验记录。从 `codex/mirra-band-control-bridge` 实际 HEAD `30a8af3fdea225cc96db6a360d71383a85b7ea39` 在 `codex/mirra-band-05c-catalog` 开发；原手机工作区保持不变。统一合同见 Band 仓库 `docs/plans/MIRRA_BAND_05C_OFFLINE_CATALOG.md`；本端实际交付见 [05C Android 记录](evidence/module-05c/README.md)。

## 当前实现

- 复用唯一应用级 WearableCoordinator/SDK 通道，增加兼容 v1/v2 的 v3 BOOK_CATALOG 协商、完整有界分片与当前 owner/context 的持久化 ACK 确认。
- WearableCatalogReader 捕获 Room/startup/StorageMaintenanceGate 代际，仅只读主线和明确选择的 IN_PROGRESS。FirstActionResolver.resolve 沿用原实现，合法已有动作与实际完成确认分开。
- 新目录披露许可默认关闭；选择和许可存独立 no-backup AtomicFile，不进入 Room 或 Full Backup。手机设置有明确披露、选书与同步状态。
- SDK 发送、握手与目录持久化确认分别记录；目录读写失败、断连、撤权、维护、旧 stream/请求或超时不得显示已同步。
- 实际 APK 1.3.0/code8 已构建和公开验签，位于 `E:\CODEX\mirra-watch\dist\Mirra-1.3.0-band-catalog-debug.apk`。不能据此宣称已经在手机上覆盖安装。

## 数据与继承边界

Room v4/schema 1–4、Migration、Full Backup v1、LearningItemEntity、FirstActionResolver、Intent/Session/监测/Closeout/统计业务、原 SDK gateway 和正式身份不变。新源使用同一门禁，Room 短只读事务完成后才发送 SDK。

05B.3 已交付的白色腕端 UI、单次确认开始、双槽/CAS/读回、参考计时、空结束页码与 pending 由 Band 继承；真机流畅度尚未明确反馈，保持待验收。旧独立实验应用与其记录保留，正式包不读取或迁移另一个包的私有文件。05D–05E 记录上传/历史整合/统计合并未实施。

## 验证进度

2026-10-13 最后一次未过滤 `testDebugUnitTest` 实际 `BUILD SUCCESSFUL in 3m 13s`；读取本轮 XML：88 suites、824 tests、0 failures/errors/skipped。其中目录 Coordinator 17 项、协议 9 项。最终 `lintDebug assembleDebug assembleDebugAndroidTest` 实际 `BUILD SUCCESSFUL in 10m 48s`；lint XML 为 0 errors、20 warnings、1 hint，不将其写为零告警。

协议缺失能力 RED、早期 ACK 测试失败、独立审查新增的固定总期限/manifest 负例 RED 均已修复。ACK 最终发送与状态发布同锁，固定 30 秒含 source/encoding/send/ACK；实际 App 使用 `SystemClock.elapsedRealtime`，延迟计时任务或回退也不能使过期 ACK 变成功。原本 lint 的 NonObservableLocale 错误通过可观察 LocalLocale 修复，未抑制检查。

保留两次重负载并发验证中的旧 `ExportWorkspaceTest.concurrentWorkspaceInstancesCanReclaimRepeatedlyWithoutDeletingCurrentOperations` 超时失败历史：`.local/05c-android-full-final.log`、`.local/05c-android-final-verified.log`。没有改动该测试断言、过滤测试或隐藏失败；最后串行低并发、局部 1024m/单 worker 的未过滤全量通过。自动化成功不能外推国行手环响应速度。

APK 16,950,769 bytes；SHA-256 `c95c9bf53634b465fff2039285d697bdf4f2587c72e0ece46caedd39203d2569`。aapt 确认 `com.guanyi.mirra`、1.3.0/code8、min23/target37；apksigner verify exit0、v1/v2，通过的公开证书 SHA-256 `29ded26bea44f1afe4fe383a302e4b507423d16223469d7da3fe578d9a7f83b9` 与实际旧 1.2.0/code7 APK 一致。元数据 `.local/05c-android-apk-metadata.json`。原生Room只读、维护退役、本地设置及Compose披露/选书在自有API37模拟器实际回归19/19通过；不能替代国行手环或配对手机真机证据。

## 已保留的既有证据

Module 04 的 1.2.0/code7 和正式腕端 0.3.0/code8 产物、代码及自动化保留。Module 03 正式 IDLE 双端同步、监测学习锁屏/腕端重进与自动恢复既有成功事实保留，不重复定义为未完成。

## 当前未完成与下一步

完成自有模拟器验证和配套正式 RPK 检查后，由任务主执行者提交并普通推送两端当前功能分支，再另取当前真机安装授权。物理设备当前安装版本/证书、本次正式目录同步和离线自然学习尚未验证；不卸载、清数据、降级、转换签名或操作用户原数据库。无 PR/main 合并，不自动进入 05D。

原生收尾：本项目自有MirraBand_Module03_API37 / emulator-5580实际19/19 PASS，8项新增目录/设置/UI与11项既有回归；43.711s，真实Room/SQLite in-memory fixtures及受控SDK，不是目标手环无线/固件证明。自有AVD已核验原1.2.0/code7公开证书后安全覆盖1.3.0/code8，无卸载/清数据/wipe，完成后关闭所属AVD；其他设备未操作。人工截图得到系统Pixel Launcher ANR，未取得产品画面，不能归因Mirra。物理手机/手环版本预检、安装、真实目录和离线自然使用仍Not Run。

配套正式RPK0.4.0/code9（80245bytes），SHA-256 bd0458ca70e63d815102d7becefe51a3c74644e87a9df29bc261ec98459fd0dc，和APK同com.guanyi.mirra/原29DE…证书。机器结果见本目录artifacts.json、verification.json；真机安装尚待当前同意，停05C。

## Git交付

Android实现c159983919093f031553b9e5e17f245635b5c15e、Band实现502f47a21a6d0b0c8a70b28179425f907ca786ee已普通push且远端SHA一致；Git记录后置文档提交不改源码或产物，停05C。
