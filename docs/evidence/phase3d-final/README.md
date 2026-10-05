# Phase 3D final evidence index

本目录仅保存脱敏结果与少量专用测试内容截图；完整执行记录见 [final checkpoint](../../checkpoints/2026-10-04-module-3d-final.md)。不提交完整 logcat、设备序列号、私人内容或签名材料。

- Exact parent: `80ece95cf24918627e57a57bb6a6d94c253528b0`.
- Environment: explicitly selected `Mirra_API_37`, Android 17 / API37 AOSP AVD. No physical-device operations.
- Scope: frozen production semantics, tests / controlled fixtures / validation only. Final independent acceptance pending.
- Initial JVM: 318 total / 318 executed / 318 passed / 0 failure / 0 error / 0 skipped.
- Initial unfiltered connected: 268 discovered, 263 passed, 5 explicit unmet-prerequisite assumptions (DND3 / Overlay1 / notification1). Gradle exit0; raw AGP/UTP XML calls these 5 `failure` nodes, reporting 5 failures / 0 errors / 0 skipped. All five are `AssumptionViolatedException`, not executed platform assertions. Preserve both raw-report and execution meanings; NOT a 268/268 clean platform PASS. Channel3 actually executed; DND0. Temporary permission authorization still pending.
- Initial lint / build: exit0, PASS; lint0 errors / 9 existing warnings / 1 informational hint. Current production APK hash equals the saved parent build.
- Schema1–4 seal and saved pre-validation APK: checkpoint Task1.
- Task2 controlled Stage A + actual cold start: prepare1 / assert1 actually passed. A durable PENDING at original boundary1791204072064/page42 recovered to COMPLETED/NORMAL at the same boundary before assert runner started; no FGS. Dedicated fixture data only; not hardware power loss.
- Task2 isolated real Room integration: DND fake release failure/retry and final six-second monitoring gap, 2 actually executed / 2 passed. Fake Android DND failure is not real platform delivery evidence.
- Actual full UI / offline / cover-install preservation / final APK: not yet completed. Permissions remain unchanged pending explicit authorization.

## Classification

Ordinary UI execution、controlled fault + actual cold start、isolated Room/fake-clock automation分别记录。预置 FULL fixture 不表示真实监测；Notification POSTED 不表示 SHOWN；未实际等待15分钟不表示真实 Deep Focus。发布级 API/OEM/TalkBack/release 未测项保持 NOT RUN。
