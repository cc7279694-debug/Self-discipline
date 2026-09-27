# Module 3B Task 6A｜Final Validation Protocol

## Goal

在 Task 1–5 已正式验收冻结后，为 Module 3B 建立可逐项执行、可区分证据来源、不会借验收扩 Scope 的最终验证协议。

## Verified Completed

- 用户已独立复验 `c058f2e46e04739307d37e96e5fc16f9e677ee7d`，Task 5B 与完整 Task 5 正式验收冻结。
- Task 6A 协议已保存到 `docs/plans/MODULE_3B_TASK6_VALIDATION_PROTOCOL.md`。
- 协议覆盖自动化、Room/Migration、Schema hash、APK 生命周期、Monitoring、DND、AOSP/API 矩阵、真机/OEM 记录和异常分流。
- 结果统一为 PASS / FAIL / DEGRADED / NOT RUN；模拟器不得冒充 OEM 真机证据。
- 早期 3B 文档中的旧 DND 矩阵已校准为 API 23–28 legacy、API 29–34 Mirra-owned rule、API 35+ own rule + user override。

## Changes

- 仅修改项目文档与状态记忆。
- 未修改 `app/`、Gradle、Manifest、Room、Schema、Migration、权限或设备状态。
- 新建独立分支 `codex/phase-3b-task6-validation`，基于 Task 5B 冻结提交。

## Inherited Decisions

- Coverage 不可信时保守降级；FULL→PARTIAL 不可逆，UNMONITORED 不计 Focus。
- Force Stop、Task Manager Stop、reboot 后不后台复活监测。
- DND 与 MonitoringCoverage 正交；只管理 Mirra 自己拥有的规则或同进程可证明 ownership 的 legacy filter。
- Task 6 不进入 3C/3D，不验证 Overlay、Progressive Friction、Temporary Allowance、Recovery success 或 effective metrics。
- 不为 OEM 兼容新增 Accessibility、VPN、全量包权限、强制电池白名单或云监控。

## Known Limitations

- Task 6B 尚未运行；本 checkpoint 不提供新的 JVM、connected、APK 或设备结果。
- 当前可用实体设备、OEM 和 API 23/29/33/34/35 AVD 清单未知，须在 Task 6B preflight 中记录。
- 没有至少一台 Android 13+ 实体设备时，Task 6B 记录可以完成，但 Module 3B 实体设备能力验收仍为 NOT RUN。
- `specialUse` 的 Play 审核结论不是本地 Task 6 可以证明的事实，继续作为发布风险。

## Verification

- 核对实现基线、当前分支、工作区和最近提交。
- 核对 `compileSdk/targetSdk = 37`、`minSdk = 23`、Room version 4 与 Migration 1→2→3→4。
- 读取并记录冻结 Schema hash；Task 6A 未重新运行自动化测试。
- 对照 `PRODUCT_SPEC.md`、Phase 3 plan、Module 3B plan、真实 DND/Monitoring 实现和现有测试清单完成协议自检。

## Next Step

用户验收 Task 6A 后，Task 6B 只按协议执行自动化与已有设备的机械验收。异常只记录并转交 Task 6C，不自行修改核心代码。
