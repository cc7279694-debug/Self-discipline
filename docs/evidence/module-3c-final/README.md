# 3C-4 actual API37 AOSP screenshots

本目录只包含专用 AVD 与 `Mirra-3C4-Test` 的真实界面；不是概念图、OEM 真机或发布级证据。截图来自同一最终生产代码，后续只增加测试与文档。设备序列号、私人笔记、完整 logcat 和数据库不入库。

| File | Actual state |
| --- | --- |
| 01-reading.png | FULL Session，页码 42，专用 Note 已自动保存，结束入口可达 |
| 02-in-app-prompt.png | cross-app OFF，返回原 Session 后应用内四操作提示；RECOVERY 不等于成功 |
| 03-overlay.png | DND ON，Chrome 上的有限宽 Overlay，四入口，不自动拉 App |
| 04-notification.png | DND OFF / Overlay denied 的真实独立通知；与 FGS channel 分离 |
| 05-allowance.png | REPLY allowance，一次延长后；测试草稿保留，临时使用不是失败 |
| 06-recovery.png | Break 提前结束后的恢复观察；无强制 90 秒倒计时 |

Notification POSTED 与 Overlay attach 不证明用户已读。通知栏截图只有本 App 和 AVD 公共系统提示；没有私人通知。

完整执行与未测边界见 `../../checkpoints/2026-10-04-module-3c-final.md`。
