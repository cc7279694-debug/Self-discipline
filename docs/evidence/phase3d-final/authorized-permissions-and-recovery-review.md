# API37 authorized platform execution and Recovery review handoff

日期：2026-10-05。执行基线 `f8fa4ace60e3224354244a98114158e698ec950f`，分支 `codex/phase-3d-final-validation`。仅专用 `Mirra_API_37` AOSP / Android17 / API37，qemu=1、boot_completed=1；没有实体设备操作。

## Permission prerequisites and restoration — PASS

在任何授权前保存真实原状态到本地 ignored validation ledger；本文件不包含序列号、完整 dumpsys 或私人数据。

| Capability | Original readback | Temporary readback | Restored readback |
| --- | --- | --- | --- |
| Usage Access | GET_USAGE_STATS default（初始无 operation） | allow；真实 Diagnostics AVAILABLE | default |
| DND policy access | 应用实际 NotificationManager API / Diagnostics false | 同一 API / Diagnostics true | 同一 API / Diagnostics false |
| Overlay | SYSTEM_ALERT_WINDOW default | allow；平台测试真实 attach | default |
| POST_NOTIFICATIONS | granted=false | granted=true | granted=false；原 USER_SENSITIVE_WHEN_GRANTED / USER_SENSITIVE_WHEN_DENIED flags 保留 |

没有把旧 secure setting 的 null 当 DND 授权证明，没有修改全局 Notification Policy、系统时间或其他 App 权限。DND 测试的临时非阅读 URI rule 由 Mirra 自己创建和删除，不操作既有用户/其他 App 的 rule。

结束专用 Session 后先确认 DND RELEASED、当前 Mirra rule STATE_FALSE / Zen OFF，再恢复偏好和四项权限。真实读回：DND preference OFF、跨应用提醒 OFF，风险选择恢复为原受控 preservation package；Wi-Fi / mobile data 为原1/1，顶层目的地恢复 Knowledge。Active Session / Segment 0/0，Mirra monitoring ServiceRecord 0、Overlay window 0、intervention notification ID3002 0。没有清库、卸载、wipe或修改实体手机。

## Actual granted platform assertions — 8/8 PASS

raw AndroidJUnitRunner：8 discovered / 8 executed / 8 passed / 0 failed / 0 error / 0 skipped，`OK (8 tests)`，instrumentation result -1。不是单看权限授权成功，也不是从旧 full suite 拼平台PASS。

| Class | Actual methods |
| --- | --- |
| AndroidDndSystemTest | accessibleNonMirraRuleIsIgnored; controlledPolicyChangeIsRejected; ownedRuleIsReusableAndNeverChangesGlobalPolicy |
| AndroidInterventionChannelsTest | independentChannelDoesNotBypassDndAndStartupCancelIsIdempotent; missingOverlayPermissionDoesNotAttachOrCrash; notificationDenialReturnsUnavailableNotPosted; grantedOverlayAttachUpdateAndRepeatedRemoveAreSafe; grantedNotificationIsPostedButNeverFullScreen |

此前缺权限的DND3、Overlay1、Notification1此次均进入真实测试主体并通过断言。覆盖 rule reuse/ownership、真实启用与停用回读、全局 Policy 未改变、Overlay attach/update/remove、实际 active notification / immutable Activity PendingIntent / 无 full-screen Intent及清理。通知证明是 POSTED，不声称 SHOWN。

## First actual FULL Session — PASS for the executed in-app chain

受控测试书 `3D4 User Flow`，Session `35a1e174-daf3-44f9-8110-31853eb233a8`。

- 真实 Knowledge→书籍→Preparation→用户确认→Session：持久 FULL + FOCUS，DND ACTIVE。页码42→43、正文自动保存；没有通过 SQL 写 Segment 或制造 FULL。
- 首次配置时跨应用开关未实际开启（后来用新 UI snapshot 发现并纠正），所以本场仅记录 in-app 证据，不冒充外部渠道链。
- 实际开始5min Break并提前结束→RECOVERY；Chrome约3秒访问仅记录 RISK_APP_BRIEF_VISIT，后续超过10秒访问确认 DISTRACTION，返回应用出现真实提示。
- 回复消息3min Allowance、一次延长2min、入口消失、提前结束→RECOVERY；历史分心保留，Note仍自动保存。
- Recovery start `1791209476373`，真实 RECOVERY_SUCCEEDED / FOCUS boundary `1791209567939`，相差 **91,566ms**。全程FULL、无monitoringLostAt，不使用 fakeclock或点击按钮宣布成功。
- 最终确认后 NORMAL / COMPLETED / FULL / DND RELEASED；endedAt `1791209631263`，start `1791209274206`，duration `357057ms`，endPage43。
- Summary真实显示1页、5min、1Note、有效专注2min、Break1 / Allowance1 / Distraction1。8段末段精确关闭于原结束边界；FOCUS合计120,191ms。Summary“查看本次记录”在原页面展开。
- 在 COMPLETED 后，使用本场真实已捕获 event token 重建匹配 URI/extra 的 OPEN_ALLOWANCE Activity request，经实际入口回放；没有活动Session或新段，boundary/duration/page/8段保持不变。明确是 **URI/request replay**，不是原 PendingIntent.send，也不是 A/PENDING 间隙回放。

该场证明首次90秒Recovery可完成；不能因此忽略下一场外部入口后的异常。

## Second actual FULL Session — Recovery acceptance blocked

纠正跨应用开关并确认“悬浮提醒已就绪”后新建 Session `bb858a51-6bfc-4940-a665-a53a3806ce90`。真实风险访问产生两个 confirmation episode，Chrome前台实际Overlay截图及 INTERVENTION_SHOWN / OVERLAY receipt，返回应用有 IN_APP receipt。

步骤：

1. 真实FULL/DND ACTIVE Session→Chrome超过10秒→Overlay显示。
2. 返回Mirra，关闭提示；第二次Chrome超过10秒→第二个Overlay。
3. 返回Mirra→用途面板选“查资料 · 5分钟”，真实可见摩擦等待完成后 grant→提前结束。
4. `TEMPORARY_ALLOWANCE` `1791209901811–1791209904936`；随后 `RECOVERY` 从 `1791209904936` 开始。
5. 保持页面可见，FULL、monitoringLostAt=null、心跳持续更新，但约140秒后仍RECOVERY。
6. 为排除反复 UI snapshot 的影响，HOME后重新打开Mirra，记录 wall `1791210093703`，期间**不运行 uiautomator / 不操作UI**。在 `1791210212489`（118,786ms后）仍RECOVERY / FULL，lastHeartbeatAt `1791210209662`；在 `1791210218533`（124,830ms后）结论仍相同，无 RECOVERY_SUCCEEDED。
7. 随后实际系统状态读回：MainActivity为topResumedActivity / current focused window，PowerManager对应Awake。截图仍“正在回到学习”。这只证明可见Activity状态，**不证明导航 LocalLifecycleOwner、所有reporter实例或Controller收到的每一条positive evidence**。

**判定：可重复的验收异常，需要 SOL / 架构审查；根因尚未确认。** FULL与健康查询/心跳不等于Recovery正向证据。没有自行改3C冻结核心、阈值、Lifecycle、Monitoring或Schema，也没有伪造Focus。

只读分析候选：后台/重复页面reporter的false证据可能重置同一个窗口；导航页面owner未RESUMED；证据循环异常终止或SAVE_FAILED未显式呈现。应先用生命周期/实例/证据流验证，不能直接将候选当已证实代码Bug，也不能直接归咎AVD。工具曾留下多个MainActivity window记录，但尚不能据此断言同Session存在两个活跃reporter。

按用户停止规则中止相关链路。为安全收尾，**通过正式UI正常结束当前RECOVERY Session**，不强停丢失DND：endedAt `1791210276568`、NORMAL / COMPLETED / FULL / endPage43 / DND RELEASED，Active Session/Segment释放。保存真实RECOVERY末段，不回填FOCUS；随后恢复全部临时配置。

## Not completed in this supplement

因上述异常暂停，不把失败或未执行场景写PASS：

- 完整跨应用→Allowance→90秒Recovery→FOCUS链：未通过，需审查。
- Session-level Notification fallback及可见性、near-finish实际撤Usage/controlled monitor stop/query loss：本轮 NOT RUN；平台Notification assertion不替代Session路径。
- 真实 A/PENDING 旧外部动作回放、同进程PENDING失败retry UI：手工NOT RUN；既有Room/Compose/guard自动化证据保留。
- 授权环境最终未过滤 connected、本轮重跑四项opt-in、最终Task5交付Gate：未执行。授权前full JVM318/318、connected275 discovered / 266passed / 9assumptions及既有opt-in专项仍作为历史独立记录，不冒称新granted full PASS。
- 本轮320/360/411dp/fontScale2专项未完成；真实Deep未等待15min。
- API23–36、OEM、完整实体机、TalkBack、release/Play、真实硬件断电、人工系统时钟修改继续NOT RUN。

**Phase3D-4没有完成，Phase3D没有Freeze。** 恢复已完成；后续先审查该具体Recovery现象，再决定是否授权最小修补/新验证。全部原RED、环境历史和权限缺失报告继续保留。
