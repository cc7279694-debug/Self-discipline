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

## Tasks 2–5

尚未完成；跨进程故障夹具、真实用户链、覆盖安装／离线、最终 Gate／交付结果在执行后追加。没有将计划项目预先标记 PASS。

## Unmeasured boundaries

API23–36 full matrix、完整 OEM matrix、完整实体设备 compatibility、TalkBack、release / Play、真实硬件断电、人工真实系统时钟修改：NOT RUN。本包 API37 结果不外推；一加13T没有本包新反馈，不记录 compatibility PASS。
