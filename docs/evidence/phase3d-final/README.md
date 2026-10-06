# Phase 3D final evidence index

本目录仅保存脱敏结果与少量专用测试内容截图；完整执行记录见 [final checkpoint](../../checkpoints/2026-10-04-module-3d-final.md)。不提交完整 logcat、设备序列号、私人内容或签名材料。

- Exact parent: `80ece95cf24918627e57a57bb6a6d94c253528b0`.
- Environment: explicitly selected `Mirra_API_37`, Android 17 / API37 AOSP AVD. No physical-device operations.
- Current scope/status (2026-10-06): test-only image isolation patch completed; one unfiltered full run has280 executed /280 passed /4 default opt-in assumptions and the new JPEG is byte-preserved. Same-key install-r data_assert fails only on real theme preferences: existing MainActivityThemeLifecycleTest resets NIGHT to BLUE. Original permissions/preferences/network restored; one newly controlled synthetic risk selection remains pending precise cleanup authorization. **STOP / review required; 3D-4 incomplete, no Phase3D freeze.** See [test image storage isolation](test-image-storage-isolation.md).

## Latest image isolation execution and preference stop — 2026-10-06

- Test-only patch `9cfae55898ad6a1a21b0afb8a943a81599ad9ef8`:12androidTest files,0production changes; unique guarded image sandboxes, realcamera URI/root checks, new explicit evidence key. Original missing JPEG/row/marker untouched. Directed9/9 and sixowner40/40 PASS; initialRED and FileProvider cache failures retained.
- New real-storage storage-isolation-v2 seed1/1PASS; exact sameJPEG818bytes/SHA beforefull/afterfull/immediatelypostinstall/assert. Full connected284discovered/280executed/280passed/0assertionfailures/4assumptions; rawXML4failure nodes allAssumptionViolatedException. DND3+channel5 actualPASS, previousPhase1 two timeout cases actuallyPASS inthissinglefull, no third targeted rerun.
- Same-key data_assert1executed/0passed/1assertionfailure:onlythemeNight→Blue; allbusiness/media frame entries identical. MarkerFAILED_PREFERENCES_RESTORED is retained, not retried/reseeded. Theme test patch and failure-only precise risk cleanup require separate scope approval; production remains frozen.
- Four temporarypermissions and originalknowledge/blue/dndOFF/crossOFF restored/readback; network1/1; noActiveSession/Intent/Segment/FGS/Overlay/interventionnotification/unexpectedownDND. Newly seeded synthetic risk row remains because failedassert neverentered success-onlycleanup; originalriskrow unchanged. Thisis notfullriskrestoration.
- Finalfresh lint/assemble/offline/finalAPKgate stopped; earlierJVM318 andlint/build remainhistoricalactualevidence, notnew execution. API/OEM/physical/TalkBack/releaseNOTRUN unchanged; nophysicaldeviceops.

## Earlier execution and storage stop — retained 2026-10-06 history

- [Actual remaining platform flow](final-remaining-platform-flow.md): near-finish Usage revoke and controlled stop durably PARTIAL/UNMONITORED before NORMAL closeout; final Start/FULL/notification/Allowance/91.661sec Recovery/precise closeout/three-record-entrances and ≥32sec result stability. Four new actual controlled-AVD captures linked there, not fixtures or OEM proof.
- Fresh JVM318/318/0failed/error/skipped. Fresh unfiltered connected275 discovered /271 executed /269 passed /2 real ComposeTimeoutException /0error /4opt-in assumptions; raw XML6failure/0error/0skipped, task exit1. All five gated platform assertions and six width/font cases actually passed. Unchanged targeted2/2 rerun is diagnostic only, not a full-suite PASS.
- Existing four opt-in explicit executions4/4 rechecked, not newly rerun or counted as ordinary full execution. Attempted second full run failed Gradle task selection before tests; no new full result.
- Preservation ImageAsset row/marker remain, referenced controlled JPEG is missing. TestAppContainer.close recursively clears the shared target images/image-work directories; in-memory Room does not isolate files. Exact deletion run is not proven. No restoration by reseeding, row deletion or fake image recreation; further regression stopped for separately authorized test-isolation review.
- Four temporary permissions, DND/cross-app preferences, original risk selection and network restored/read back; no active Session/Segment, monitor FGS, MirraOverlay, intervention notification or unexpectedly active own rule. Missing JPEG remains unrepaired. No physical-device operations.
- Final fresh lint/build and exact-file cover/offline gate NOT RUN after stop. Current built APK payload matches163 uncompressed frozen entries, but archive hash is9C9B33337A8FB9A3969089308C28E8302AB285939DFBD47A4103CC7D48C68CD9, not the old68C89D hash; not a completed final artifact.
- Historical >120sec Recovery anomaly remains Observed once / Not reproduced in targeted diagnostic; original91.566 and diagnostic90.827/90.858/91.006sec successes plus this91.661sec success do not meanFIXED. All original screenshots/failure records remain.

## Earlier execution records — retained history, not current gates

- Initial JVM: 318 total / 318 executed / 318 passed / 0 failure / 0 error / 0 skipped.
- Initial unfiltered connected: 268 discovered, 263 passed, 5 explicit unmet-prerequisite assumptions (DND3 / Overlay1 / notification1). Gradle exit0; raw AGP/UTP XML calls these 5 `failure` nodes, reporting 5 failures / 0 errors / 0 skipped. All five are `AssumptionViolatedException`, not executed platform assertions. Preserve both raw-report and execution meanings; NOT a 268/268 clean platform PASS. Channel3 actually executed; DND0. Temporary permission authorization still pending.
- Initial lint / build: exit0, PASS; lint0 errors / 9 existing warnings / 1 informational hint. At that earlier build, the production APK hash equaled the saved parent build; this is not the current archive hash.
- Schema1–4 seal and saved pre-validation APK: checkpoint Task1.
- Task2 controlled Stage A + actual cold start: prepare1 / assert1 actually passed. A durable PENDING at original boundary1791204072064/page42 recovered to COMPLETED/NORMAL at the same boundary before assert runner started; no FGS. Dedicated fixture data only; not hardware power loss.
- Task2 isolated real Room integration: DND fake release failure/retry and final six-second monitoring gap, 2 actually executed / 2 passed. Fake Android DND failure is not real platform delivery evidence.
- Task4 actual same-binary cover-install: old saved APK → opt-in persistent seed1/1 → install-r current APK → assertion1/1. Aggregate before/after/read checksum79019e05a56c729c137cb4eabed952f7e69652f1411e8d6cc8c538dcafa460fb; original preferences restored. Isolated migration/reopen additionally1/1, not the installation proof.
- Actual offline NONE chain: page40→temporary4→42, Note35, Break/early end, continue from confirmation, lower end page rejection, final closeout, same Summary/History/Search, local JPEG and its Note. Network restored1/1. End boundary/duration/last Segment/row counts stable after >75s, noFGS. Monitored FULL/Allowance/90s Recovery and platform-granted paths still NOT RUN pending authorization.
- Additional actual NONE cases: first finish dialog before final confirmation → Force Stop/cold start → ABNORMAL (no durable PENDING); direct final finish while BREAK → NORMAL/COMPLETED with BREAK closed at frozen boundary. These are ordinary UI, not FULL monitoring proof.
- Then-latest unfiltered JVM318/318/0failed/error/skipped. Then-latest unfiltered connected275 discovered / 266 passed / 9 explicit assumptions (opt-in4 + missing DND3/Overlay1/notification1), business assertion failures0. Raw AGP/UTP XML9failure/0error/0skipped, runner exit0. DND0/channel3 actually executed; not275/275 clean platform PASS. New ordinary preservation1 and boundary2 actually passed in that earlier full run.
- Earlier lint/build exit0, 0errors / 9existing warnings / 1hint. Historical candidate `build/deliverables/Mirra-3D4-debug.apk`, 16488770 bytes / SHA-256 `68C89D5948E34E3EF48DE374CA2043B6B5D0EE43702E7D3B63E7A1CE3EF674E8`, appId com.guanyi.mirra / 0.1.0 / code1. Exact candidate install-r, cold start and same preserved History/Search record passed at that time; same frozen production binary, not a release or completed 3D-4 claim.
- Remaining: temporary dedicated-AVD permissions await user confirmation; actual FULL/risk/channel/Allowance/90s Recovery chain, near-finish system revoke/stop, external stale action replay and this package's FULL/PARTIAL / width / large-font manual checks remain NOT RUN. Original checkpoints and failed/environment history are retained. No Phase3D freeze claim.

## Actual screenshots

These seven captures show actual UI on the dedicated AVD with newly controlled test data. They are **NONE / offline / cover-preservation evidence**, not FULL monitoring fixtures or OEM compatibility proof. Only the Topic-detail observation followed network restoration; it is documented separately without an offline claim.

| File | Scope |
| --- | --- |
| [offline-finish-confirmation](offline-finish-confirmation.png) | Real pre-final confirmation, page42/Note35; still ACTIVE |
| [offline-summary-none](offline-summary-none.png) | NORMAL / NONE result, no effective value |
| [offline-inline-timeline](offline-inline-timeline.png) | Same result page inline timeline |
| [offline-book-history](offline-book-history.png) | Preserved page42, last summary and history row |
| [offline-history-record](offline-history-record.png) | History origin, expanded same three segments |
| [offline-search-record](offline-search-record.png) | Search SESSION origin, identical facts/timeline |
| [offline-owned-image](offline-owned-image.png) | Real decoded App-owned controlled white JPEG / Caption |

## Classification

Ordinary UI execution、controlled fault + actual cold start、isolated Room/fake-clock automation分别记录。预置 FULL fixture 不表示真实监测；Notification POSTED 不表示 SHOWN；未实际等待15分钟不表示真实 Deep Focus。发布级 API/OEM/TalkBack/release 未测项保持 NOT RUN。

## Authorized supplement — historical Recovery stop

以上授权等待/full-suite assumptions为授权前历史，保留不删除。当时的专项执行与恢复见 [permission and Recovery handoff](authorized-permissions-and-recovery-review.md)：平台8/8实际断言PASS，原五项permission cases真正执行；第一场FULL/in-app Recovery91.566sec成功，第二场跨应用Overlay后的Recovery在独立无UI抓取窗口>120sec仍未完成。该历史轮次停止相关链路、不修改生产或测试、不宣布3D-4完成。

原四权限、DND/cross-app偏好、风险选择、Knowledge目的地及网络均恢复读回；无活动Session/Segment、FGS、Overlay、干预通知和意外active Mirra rule。根因待审，FULL+心跳不证明positive page evidence。最终granted full suite、重复opt-in、near-finish专项和Task5最终Gate未执行，不用平台8项替代。

| Actual capture | Evidence |
| --- | --- |
| [Closeout UI](authorized-closeout-disabled.png) | First Session end operation: page43 and disabled controls; this is not a confirmation-dialog capture |
| [FULL Summary](authorized-full-summary.png) | First Session NORMAL/FULL/effectiveFocus2min |
| [FULL timeline](authorized-full-timeline.png) | Same first Session inline record, no new route |
| [Overlay](authorized-overlay.png) | Second Session actual bounded Overlay over Chrome |
| [Recovery anomaly](recovery-after-overlay-not-completed.png) | Second Session stillRecovery after independent>120sec window; notPASS |

All captures are dedicated-AVD actual UI with controlled test data, not seeded FULL fixture, generated concept art or physical-device proof. Full raw logs and permission snapshots remain local only. Session Notification fallback remainsNOT RUN; platform notification assertions provePOSTED, notSHOWN.

Additional Break / in-app / extension / pre-success Recovery snapshots stay in the local ignored ledger; only five essential new captures are submitted.

## Recovery evidence-chain diagnostic — 2026-10-05

后续只诊断的实际记录见 [Recovery evidence chain diagnostic](recovery-evidence-chain-diagnostic.md)。基线 `9efea119667b6ae5adf1761521ea785edabb1cb0`；一个新受控 FULL Session 内一次 in-app 对照及两次已消费的 Overlay OPEN_ALLOWANCE，三次 Recovery 分别90,827 / 90,858 / 91,006ms成功，均有 proof/write/Room milestone，FULL未降级。

原异常未复现，A–F没有确认依据，结论 **RECOVERY_DIAGNOSTIC_INCONCLUSIVE**。这不覆盖历史失败，也不宣布问题修复或3D-4完成。临时日志源码全部恢复，原APK覆盖恢复，权限/偏好/风险选择/网络读回原状态，正常结束后无活动Session/Segment、FGS、Mirra Overlay、干预通知或意外active Mirra rule。完整日志与快照仅在本地；只提交脱敏诊断文档和本索引。API37成功不外推API/OEM/实体设备未测项目。
