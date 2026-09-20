# Module 3B Task 2｜Android 监测平台骨架

## Goal

将 Task 1 的纯 Kotlin 监测证据链接到最小 Android capability、UsageEvents adapter 与前台 Service；只证明平台事实和生命周期，不创建或修改学习 Session。

## Verified completed

- Usage Access 同时检查 AppOps 与真实 `queryEvents()`；成功空结果可用，null、异常或撤权不进入 READY。通知可见性、DND policy access 独立报告，不作为监测 READY 硬门槛。
- 非导出的 `specialUse` FGS 仅由开发版诊断页的显式用户操作启动，`START_NOT_STICKY`，无 boot/background 自动启动。generation 保护重复启动和单一 poller。
- 状态分为 STOPPED、STARTING、FOREGROUND_ACK、MONITOR_INITIALIZING、MONITOR_READY 与 FAILED。wall-clock jump 会撤销 READY 并按 reducer 指示重建 cursor；成功空查询不伪造前台 package。
- 开发版诊断显示 capability、Service、generation、查询连续性、前台证据新鲜度、cursor、observation 和错误；不上传或持久化 App 轨迹。
- API 37 模拟器实测：未授权不可 READY；授权后通知/DND 未授权仍可 READY；手动停止、运行中撤销 Usage Access、系统权限设置往返、APK 覆盖安装及强停冷启动均正常。

## Changes and inherited boundaries

- Manifest 新增 Usage Access、FGS/specialUse、通知和 DND 检查所需权限与私有 Service；未声明 Accessibility、Overlay、VPN 或 `QUERY_ALL_PACKAGES`。
- Room Schema 保持 v4；Entity、Migration、Session、Segment、Coverage 和 Phase 2 统计均未变。Task 2 不创建 monitored Session、不会把既有 Session 追认为 FULL，也不应用 DND。
- `specialUse` subtype 的发行/Play 审核仍需后续发布前验证；Pixel 与小米等真机的 UsageEvent 延迟、后台限制和 20 秒证据新鲜度留到 Task 6 校准。

## Verification

- `:app:testDebugUnitTest`：90 项通过、0 失败。
- `:app:connectedDebugAndroidTest`：API 37 模拟器 115 项通过、0 失败。
- `:app:lintDebug`、`:app:assembleDebug`：通过。
- `adb install -r`、强停后冷启动及设备诊断交互：通过。
- 实体 Android 真机：Not Run；Task 6 的 OEM 验证尚未授权。

## Next step

Task 2 独立验收后，再单独授权 Module 3B Task 3。不得从这个平台 READY 推断已经发生完整可信 Session 覆盖。
