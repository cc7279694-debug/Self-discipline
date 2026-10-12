# Current State

更新：2026-10-12（Asia/Shanghai）。

## 当前交付

**DEV_COMPLETE_AWAITING_NATURAL_USE**。用户已授权 Mirra Band Module 04 方案 B：学习常用控制，合同见 `docs/plans/MIRRA_BAND_MODULE_04_WRIST_CONTROLS.md`。Android 分支 `codex/mirra-band-control-bridge` 从接受基线 `3fecf50a208ddb145a67986749420b490193e903` 创建；Band 分支 `codex/module-04-wrist-controls` 从 `8b14819f15682fd22752dbdf2b05e8d465e255ee` 创建。继续现有隔离工作区，原手机工作区不变，不进入 Module 05。

## 已实现

- 手机唯一权威的休息 5/10 分钟、提前结束休息和已有有效 Intent 的实际第一步确认开始。复用 FocusSessionActions、SessionStartCoordinator 与原 Repository，不从通信层插入 Session。
- 严格腕端监测准入：提交前 preflight/FGS/READY 失败返回 REQUIRES_PHONE、保留 Intent，不创建意外 UNMONITORED；最终事务复核动作、页码、Intent、书籍、授权与 READY；提交后降级保留实际 Session。
- v2 短授权控制与 v1 只读兼容、绑定存储代际的执行器、单命令、有界去重和结果未知处理。维护、撤权和资源退役无旧 owner 写入；休息的可选 guard 直达最终首次写入，原手机调用不传 guard、规则不变。
- 默认关闭的独立 no-backup 控制许可，手机设置显式确认；权限不随备份迁移，不改 Room 或 portable preferences。

## 数据安全与基础继承

Room v4/schema 1–4、Migration、Full Backup v1、FirstActionResolver/过期/状态转换/监测/Closeout 算法、手机包名和签名身份保持不变。SQLite 为真实学习事实源，原 V1.1 本地优先产品和已有手机交互继承。Room v4 基线/当前 Git blob 均为 `97ae3a8a15aeb924350b4b7ecade389b314f7ebf`。没有新增依赖、云端或权限，没有原始用户数据库读取/清除。

## 实际验证与配套版本

最终未过滤 JVM **798/798 PASS，86 suites，0 failure/error/skip**；单次 testDebugUnitTest/lintDebug/assembleDebug/assembleDebugAndroidTest 构建 **exit0 / 4m17s**。最终 APK 在自有 API37 模拟器单次相关回归 **78/78 实际 PASS，零失败/assumption/ignored**；手机控制默认关闭/明确同意/取消保持只读已通过实际原生模拟器画面检查；完整结果以 Band 仓库 `docs/evidence/module-04/verification.json` 为准，不拼接定向回归假装全量 connected。腕端 Node **184/184**、检查 **34/4/0** 和正式 RPK 构建/独立验签已通过。

APK **1.2.0/code7**：`E:\CODEX\mirra-watch\dist\Mirra-1.2.0-band-control-debug.apk`。RPK **0.3.0/code8**：`E:\CODEX\mirra-watch\dist\com.guanyi.mirra.release.0.3.0.rpk`。公开证书沿用既有 `29:DE:D2:6B:EA:44:F1:AF:E4:FE:38:3A:30:2E:4B:50:74:23:D1:62:23:46:9D:7D:A3:FE:57:8D:9A:7F:83:B9`；二进制摘要和证据集中在 Band 交付报告，不重复冻结。Module 04 真机安装尚未执行。

## 保留 Module 03 成功事实

1.1.3-band-readonly/code6 与 Band0.2.5/code7 是用户已接受基线。正式 IDLE 双端同步有手机和腕端证据；监测学习锁屏、腕端退出重进最新状态为 **USER_REPORTED_PASS**。首次 NO_DEVICE 后同进程蓝牙恢复、自动恢复设备/服务/授权、新握手及完整快照已通过。约 5 分钟自然使用正常是用户反馈。

30 分钟被动观察有测量限制，不能改写为完整同步次数、精确 RTT 或耗电通过。历史证据保留于 Band `docs/evidence/module-03/` 和接受基线；未宣布全状态/OEM/独立最终审查通过，也不重复定义已通过项尚未完成。

## 未执行与下一步

新控制真机安装和一次自然操作证据、原生腕端渲染/触控、新控制锁屏 FGS/OEM 覆盖、其他 API/OEM、Module 04 未过滤全量 connected、9 个依赖专用阶段 fixture 的备份方法、release/生产发布、ChatGPT 独立审查 **Not Run**。

代码和门禁已完成，手机实现提交 `9662164bbffe9ebafdb72b7c4f99ac8d538876d3`；实现已 push 并核对远端 SHA；交付记录随同分支文档提交，接下来取得本轮安装同意，核对原配对手机当前公开版本/证书/空闲首页后安全覆盖，再自然使用并反馈实际结果。安装/权限/系统操作由用户确认；不卸载/清数据、不合并 main、不自动 PR、不进入 Module 05。
