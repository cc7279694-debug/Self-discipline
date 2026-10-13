# Mirra Band 05C：Android 配套交付记录

日期：2026-10-13，Asia/Shanghai。范围仅手机到手环真实书籍目录，不上传腕端记录、不整合历史或统计。当前源码、自动化和产物已准备，国行目标设备验收尚未执行。

## 1. 功能

同一 WearableCoordinator / WearTransport 通道协商 v3 BOOK_CATALOG，保留 v1/v2 只读和手机常用控制行为。主线自动加入；其余仅从 IN_PROGRESS 选择，最多 11 本。目录包括稳定 ID、完整名称、页码、FirstActionResolver.resolve 得到的已有合法动作及主线标记。每次腕端学习仍须实际完成并明确确认第一步。

新增目录许可默认关闭，手机设置明确披露新增范围。手机显示发送中、等待完整持久化确认、已确认保存和真实失败；SDK 发送不代表业务成功。ACK 必须匹配当前请求、设备、stream、revision 和期限，最终发送期间的同步回调不会被消息锁丢弃。

## 2. 修改范围

新增 WearableBookCatalog、WearableCatalogReader、JVM / 原生目录测试与共享 v3 Golden Fixtures；修改 Coordinator、Protocol、LocalSettings、SettingsScreen、AppContainer 和 Application 的必要接线。构建版本递增为 1.3.0/code8。没有更改实体、DAO、Room Schema、FirstActionResolver、学习算法、SDK gateway 或备份格式。

## 3. 数据与安全

CatalogReader 使用当前容器捕获的 startup、Room 和 StorageMaintenanceGate；仅在短只读事务中 listAll，再从快照构造目录；SDK 发送发生在事务和门禁读操作结束后。

目录许可和选书保存于 noBackupFilesDir 的独立 AtomicFile，默认关闭且不进入 Full Backup。消息保持 4096 UTF-8 bytes；目录最多 12 本 / 16384 bytes，最多 32 个分片 / 每片 1024 bytes，完整 CRC 与规范 JSON 校验。固定总 30 秒覆盖读取、编码、发送和 ACK；生产使用含深睡的 elapsedRealtime。关闭同步停止更新，不自动删除腕端已有完整目录。

不迁移、清除或读取旧实验包的私有记录。05D–05E 上传、导入、统计合并未实现。

## 4. 实际验证

- 最后未过滤 JVM 全量：88 suites / 824 tests，0 failures、0 errors、0 skipped，日志 `.local/05c-android-final-jvm.log`，实际 BUILD SUCCESSFUL 3m13s。
- 目录 Coordinator 17 项、协议 9 项；双端 Golden 对照 UTF-8 / CRC / canonical。新增慢读取、读取超时、延迟 timer、时钟回退和并发 ACK 验证。
- lintDebug / assembleDebug / assembleDebugAndroidTest：实际 BUILD SUCCESSFUL 10m48s，80 tasks（21 executed / 59 up-to-date）。lint XML：0 errors / 20 warnings / 1 hint。
- apksigner verify：exit0，v1/v2；aapt 确认包名和版本。仅读取公开证书，未转换或更换签名。
- 原生instrumentation在自有API37模拟器19/19通过，8项新目录与11项既有回归；SDK使用fixture，实际Room为in-memory测试数据库。配对手机 / 国行 9 Pro 本次安装、目录往返和离线学习 **Not Run**。

失败历史保留：初次缺失 v3 能力 RED；目录 ACK / 固定期限 / manifest 边界审查 RED；NonObservableLocale lint 错误，均经对应修复。旧 ExportWorkspaceTest 的并发文件回收测试在两次并发重负载全量中出现 TimeoutException（`.local/05c-android-full-final.log` 与 `.local/05c-android-final-verified.log`），未修改原断言或过滤测试；最后局部 1024m / 单 worker 的未过滤全量通过。

## 5. APK 与未验证项

文件：`E:\CODEX\mirra-watch\dist\Mirra-1.3.0-band-catalog-debug.apk`。

| 项目 | 实际值 |
| --- | --- |
| applicationId | com.guanyi.mirra |
| versionName / versionCode | 1.3.0 / 8 |
| minSdk / targetSdk | 23 / 37 |
| bytes | 16950769 |
| SHA-256 | c95c9bf53634b465fff2039285d697bdf4f2587c72e0ece46caedd39203d2569 |
| 公开证书 SHA-256 | 29ded26bea44f1afe4fe383a302e4b507423d16223469d7da3fe578d9a7f83b9 |

新旧实际 1.2.0/code7 APK 的公开证书一致。本次未读取真实手机已装 APK；因此尚不输出物理设备 SAFE_TO_INSTALL。新 APK 和正式身份 RPK 配对后，安装前仍核对目标设备同包名、同证书和版本递增并另取当前同意。

目录和 05B.3 性能改善的目标手环体验均不能从自动化推定通过。20 个 lint warning、1 个 hint 保留，无无关依赖升级或检查抑制。

## 6. Git

Android 分支 `codex/mirra-band-05c-catalog`，基线 `30a8af3fdea225cc96db6a360d71383a85b7ea39`。Android实现提交 `c159983919093f031553b9e5e17f245635b5c15e`、Band配套实现 `502f47a21a6d0b0c8a70b28179425f907ca786ee` 已普通push，ls-remote匹配；此Git记录为后续文档提交，无源码/产物改动。未自动PR、未合并main，原手机checkout干净且未修改。

## 7. 自然使用与停止点

取得当前安装同意后，配套升级手机 APK 和正式 Mirra Band RPK；保留原实验应用。手机「我的 → 手环伴侣」允许书籍离线目录，可选择进行中书籍；腕端同步书籍，只有显示完整保存确认才是新目录可用。随后断开连接仍可选择最后完整目录，实际完成第一步并确认后独立学习。本地完成记录目前只保存在手环，未更新手机历史和统计。

本轮停在 05C，等待一次目标设备自然使用反馈，不进入 05D。

原生收尾：本项目自有MirraBand_Module03_API37 / emulator-5580实际19/19 PASS，8项新增目录/设置/UI与11项既有回归；43.711s，真实Room/SQLite in-memory fixtures及受控SDK，不是目标手环无线/固件证明。自有AVD已核验原1.2.0/code7公开证书后安全覆盖1.3.0/code8，无卸载/清数据/wipe，完成后关闭所属AVD；其他设备未操作。人工截图得到系统Pixel Launcher ANR，未取得产品画面，不能归因Mirra。物理手机/手环版本预检、安装、真实目录和离线自然使用仍Not Run。

配套正式RPK0.4.0/code9（80245bytes），SHA-256 bd0458ca70e63d815102d7becefe51a3c74644e87a9df29bc261ec98459fd0dc，和APK同com.guanyi.mirra/原29DE…证书。机器结果见本目录artifacts.json、verification.json；真机安装已获当前同意，待原配对手机连接和只读安全预检，停05C。

## 当前安装授权

用户已明确同意“先只读预检，再安装配套包”。授权已满足，不重复询问。当前ADB仅见两台无关模拟器，尚未操作物理手机或腕端；等待原配对手机USB/调试和空闲首页，以及正式腕端版本号。预检通过后按此授权安装，仍禁止卸载/清数据/降级/更换签名/实验迁移/用户数据库操作。见[设备记录](device-acceptance.json)。
