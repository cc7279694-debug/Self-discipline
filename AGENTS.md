# Repository Collaboration Guide

## Mirra Band 05C 当前授权范围

2026-10-13 用户授权从 `codex/mirra-band-control-bridge` 的真实 HEAD `30a8af3fdea225cc96db6a360d71383a85b7ea39` 在 `codex/mirra-band-05c-catalog` 连续实现真实书籍离线目录。统一合同在 Band 仓库 `docs/plans/MIRRA_BAND_05C_OFFLINE_CATALOG.md`。主线自动加入，其他只可明确选择 IN_PROGRESS；通过 FirstActionResolver.resolve 构造动作，空或无效动作交腕端预设兜底，仍需每场明确完成确认。

复用现有 WearableCoordinator、WearTransport 和 captured StorageMaintenanceGate/Room source；目录只读，新增披露许可与所选 ID 仅存独立 no-backup AtomicFile。Room v4、学习/监测/Intent/统计、Full Backup v1、原手机工作区和签名材料不变。v3 目录能力兼容既有 v1/v2；只有腕端完整保存读回后的精确 ACK 可以标记目录同步成功。普通开发、自动化、构建及当前功能分支 commit/push 已授权；设备安装、权限或正式包替换另取当次同意。无自动 PR/main 合并，05D–05E 记录上传及历史整合不在范围内。

下方 Module 04 状态和范围是历史基线，不能覆盖此项当前授权；保留其实现和已验证证据。

## Mirra Band Module 04 授权范围

用户已授权在 codex/mirra-band-control-bridge（基线 3fecf50a208ddb145a67986749420b490193e903）执行 docs/plans/MIRRA_BAND_MODULE_04_WRIST_CONTROLS.md：只开放 5/10 分钟休息、提前结束休息与手机已有 Intent 的明确第一步确认。复用当前手机业务；可增加外部控制准入 guard 和最终事务期望事实校验，但不改状态转换规则、监测阈值、Room v4/Migration、Full Backup v1、签名身份或直接调试用户数据库。

普通实现、测试、构建、功能分支 commit/push 已授权；实际手机/手环安装及系统权限另行确认。当前模块交付后等待自然使用与 ChatGPT 独立审查，不合并 main、不进入后续模块。

## Authority

本仓库的事实源优先级为：用户当前明确要求 → docs/PRODUCT_SPEC.md（产品需求与架构）→ 本文件 → PROJECT.md → 真实代码与数据库 Schema → docs/CURRENT_STATE.md → docs/DECISIONS.md → README.md → Chat。

代码与文档冲突时先核对真实实现，再修正文档；不得凭对话记忆继续开发。

## Context Loading

开始中大型任务前，按顺序阅读：

1. AGENTS.md
2. PROJECT.md
3. docs/CURRENT_STATE.md
4. docs/DECISIONS.md
5. docs/PRODUCT_SPEC.md 中与当前 Phase 相关的部分
6. 相关代码、测试、数据库 Schema 与 Git 状态

## Product Guardrails

- 产品是 Android 原生个人学习 App，不是普通计时器、笔记平台或项目管理工具。
- 产品正式名称为“观已Mirra”。
- V1 采用 Kotlin、Jetpack Compose、Navigation 3、Room / SQLite、DataStore 和 App-owned Files。
- Local-first、Offline-ready、No-login-first；V1 不增加服务器、Supabase、Vercel、Web App 或云同步。
- “开始”必须经过 Intent → Start Script → First Action → Session，不得把点击按钮直接记为开始成功。
- 同一时间最多一个 Active Intent、一个 Active Session 和一个主线 Learning Item。
- 笔记保持独立、轻量、自动保存；不得扩展为重型 Block Editor。
- Phase 1 保留 nullable `stableStartedAt` 字段，但不实现 Stable Start 自动判定或统计。
- V1 Scope Guard 以 docs/PRODUCT_SPEC.md 的“V1 明确不做”为准，任何未列入当前 Phase 的能力不得主动实现。

## Data Safety

- Room / SQLite 是结构化业务数据的 Source of Truth。
- 数据库结构修改必须包含可验证的 Migration，不得要求用户清空 App 数据。
- 图片存 App-owned Files，数据库只保存路径和元数据；删除时仅清理无其他引用的文件。
- Session 异常结束要保留历史，但排除在能力趋势和核心统计之外。
- Backup / Restore 是 V1 基础设施；恢复前必须校验格式、版本、文件和数据完整性。

## Delivery Workflow

- 先明确 Goal、Scope、Out of Scope、Constraints、Acceptance Criteria 和 Verification，再实现中大型模块。
- 严格按 docs/PRODUCT_SPEC.md 的 Phase 0 → 4 顺序推进；每个 Phase 或独立模块完成后暂停验收。
- 优先最小实现与小范围修改，不做无关重构、依赖升级或未来式空表。
- 测试与风险匹配；数据库、Migration、备份恢复、统计与 Session 状态机优先做可重复验证。
- Evidence before assertion：只报告实际执行并通过的验证，未运行项必须明确标记 Not Run 及原因。

## Git

- 中大型功能和独立模块使用 codex/ 前缀的功能分支，不直接在 main 开发。
- 使用 Conventional Commits；提交只包含当前模块相关修改。
- 提交前检查状态、差异、测试、临时文件和敏感信息。
- 未经用户明确授权，不得 Push、Merge、Force Push、删除远程分支、发布或修改生产数据。
