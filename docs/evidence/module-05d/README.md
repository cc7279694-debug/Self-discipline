# 05D Android 配套交付

2026-10-14，隔离分支 codex/mirra-band-05d-record-sync，基线6589a19f0305245fe0e63bf77f105f61f27a913e。原手机工作区保持1338b9a且干净。完整双端七项报告由Band仓库docs/evidence/module-05d/README.md维护；本目录artifacts.json、verification.json、changes.json为同次交付索引。

手机新增独立不可变腕端原始记录表、Room4→5明确Migration、事务后持久ACK，以及FullBackup2/schema5和旧1/schema4严格完整恢复。复用原Coordinator/Interconnect所有者，兼容v1/v2/v3、新增v4。记录同步披露默认关闭。不注入study_sessions、不更新书籍页码/统计，不修改原Intent/Session/监测算法。

全量JVM849/849 PASS，91 suites/0fail/error/skip；实际APK和testAPK构建通过，最后新增原生用例后Lint再次通过，0error/20warning/1hint。真实Room迁移、SQLite失败回滚/重开、旧备份恢复等自有API37原生fixture结果以verification.json实际计数为准。它们不证明目标物理手机或国行9Pro。

配套APK1.4.0/code9与正式RPK0.5.0/code11实际同com.guanyi.mirra和原29ded证书，精确大小/摘要/签名见artifacts.json。当前正式设备安装、真实用户Room升级/恢复没有执行，须当前另行明确同意；不清数据、不卸载、不降级、不转换签名。Room5升级后不得直接覆盖旧APK试图降级。FullBackup2保存独立原文，JSON/CSV仍明确只含原手机业务表。

用户已确认05C.1 0.4.1视觉正常，USER_REPORTED_PASS — PASS WITH NOTES保留；05B.3真机流畅度仍无明确反馈。参考墙钟时长不进入有效专注。档案有限保留、手机旧备份回退补传依赖腕端原文仍存在，不自动删除。停05D自然使用验收，05E未实施，无PR/main合并。
