# Decisions

## 2026-09-11 — 只有进行中的 Learning Item 可以成为主线

### Decision

`PAUSED` 或 `COMPLETED` 的 Learning Item 不能占用 `mainlineSlot`。未来实现暂停或完成状态时，状态变更与主线约束必须同时由 Repository / DAO 层执行并由数据库测试覆盖，不能只依赖 UI 隐藏或禁用。

### Context

Phase 1 已有 `LearningItemStatus`，但尚未实现暂停与完成的状态变更入口。本次 Correction Patch 不应提前扩展这些 Future UI 或 Service。

### Alternatives

现在提前实现完整状态机，或未来只在 Compose 页面阻止用户选择暂停 / 已完成内容。

### Reason

记录数据层不变量可以防止未来入口、并发调用或绕过 UI 时产生非法主线，同时避免当前 Phase 为未开放功能增加实现。

### Consequences

Phase 1 不新增暂停 / 完成 API。未来相关实现必须原子清理或拒绝非法 `mainlineSlot`，并加入 DAO / Repository 数据库测试。

## 2026-09-10 — Phase 1 用数据库唯一槽位和事务保护学习状态

### Decision

Learning Item、Intent 与 Session 分别使用 nullable 唯一槽位表达唯一主线、唯一 Active Intent 和唯一 Active Session；Intent 转 Session，以及 Session 结束、进度推进和 Summary 保存，均由 Room 事务完成。

### Context

Phase 1 的核心状态会经历并发点击、进程回收和重复调用，仅靠 UI 禁用按钮不能保证数据一致性。

### Alternatives

只在 ViewModel 中检查、只使用普通布尔字段，或引入更复杂的事件溯源与多模块状态框架。

### Reason

nullable 唯一槽位能利用 SQLite `UNIQUE` 直接保护全局单例状态，同时保持 Schema 与 Repository 简单；跨表业务变更使用事务可以避免只完成一半。

### Consequences

`mainlineSlot` / `activeSlot` 的值只使用 `1`，非占用状态使用 `NULL`。业务写入只能通过 Repository / SessionManager；UI 不访问 DAO。

## 2026-09-10 — 进程启动时保守关闭遗留 Active Session

### Decision

每个新 App 进程在展示业务 UI 前检查数据库中的 Active Session；若存在，则原子标记为 `ABNORMAL`，保留最后写入页码，但不推进 Learning Item 阅读进度。

### Context

Phase 1 没有后台服务或系统行为信号。若新进程启动时数据库仍有 Active Session，说明前一个进程未正常完成结束事务。

### Alternatives

以固定 12 小时阈值判断、自动当作正常完成，或继续恢复计时。

### Reason

进程边界是本阶段可可靠观测的信号；保守关闭可以释放唯一 Active 槽位，又不会把未经用户确认的页码写成正式阅读进度。

### Consequences

异常 Session 保留 `currentPage` 作为 `endPage` 并排除正常 Summary；不实现 Stable Start 自动判定，也不使用固定时长阈值。

## 2026-09-10 — 产品正式名称为观已Mirra

### Decision

产品对外名称统一为“观已Mirra”；Android applicationId 使用 `com.guanyi.mirra`。

### Context

项目初始化阶段需要稳定品牌名称与 Android 包标识，避免工程建立后反复改名。

### Alternatives

继续使用仓库名 Self-discipline，或使用与品牌无关的临时 applicationId。

### Reason

“观已Mirra”是用户确认的正式产品名称；`com.guanyi.mirra` 简洁且与品牌对应。

### Consequences

仓库名可以暂时保持 Self-discipline，但应用展示名、文档和代码命名使用观已Mirra / Mirra。

## 2026-09-10 — Phase 0 采用单 App Module 与 Navigation 3

### Decision

工程使用单 Android App Module、清晰分层、按功能分包、手动 AppContainer，以及稳定版 Navigation 3。

### Context

当前没有遗留 Android 代码，Phase 0 + Phase 1 只需支撑单用户本地学习闭环。

### Alternatives

多 Gradle Module Clean Architecture、Hilt，以及 ViewModel 直接访问 DAO。

### Reason

该方案保留 Repository 和状态机边界，同时避免早期多模块与依赖注入框架成本。Navigation 3 已稳定并以 Compose 为中心。

### Consequences

Phase 0 不建立多模块、不引入 Hilt；未来只有在真实复杂度出现时再拆分。

## 2026-09-10 — Phase 1 不自动判定 Stable Start

### Decision

Phase 1 保留 nullable `stableStartedAt`，但不实现基于固定时长的 Stable Start 自动判定，也不引入固定时长的 Active Session 异常结束规则。

### Context

Phase 1 尚未接入能可靠判断专注稳定性或异常结束的 Android 系统信号。

### Alternatives

以连续阅读5分钟判定 Stable Start，或以12小时阈值自动结束 Session。

### Reason

未经验证的时间阈值会把推测写成用户行为事实，污染后续统计。

### Consequences

Phase 1 只记录 Session 总时间；Stable Start 与异常 Session 的可靠判定留到具备充分信号的后续阶段。

## 2026-09-10 — 以真实学习启动而非计时为产品核心

### Decision

把 Intent、First Action、Started 和 Stable Start 分开建模；点击“我想开始”不直接创建 Session。

### Context

用户的首要困难不是缺少计时工具，而是无法从刷手机或躺着的状态切换到真实学习行为。

### Alternatives

传统计时器、番茄钟、目标清单或激励文案。

### Reason

只有完成第一个具体学习动作，才代表启动真正发生；分阶段记录才能衡量启动转化和稳定性。

### Consequences

所有开始入口必须经过 Intent 与启动流程；核心统计优先关注 Intent 转化、启动耗时和 Stable Start。

## 2026-09-10 — V1 使用 Android 原生技术栈

### Decision

采用 Kotlin、Jetpack Compose、Navigation 3 和 Coroutines / Flow，不使用 React、Capacitor 或 Web App 作为 V1 运行架构。

### Context

V1 依赖 Usage Access、勿扰模式、Overlay、通知降级和本地文件等 Android 系统能力。

### Alternatives

React + Capacitor、Next.js / PWA、跨平台框架。

### Reason

Android 原生能够以更直接、稳定的方式处理系统权限、后台状态和设备能力差异。

### Consequences

工程基础、测试、导航与数据层均围绕 Android 原生建立；V1 不维护 Web 版本。

## 2026-09-10 — Local-first 且 V1 无云依赖

### Decision

V1 无账号、无登录、无服务器、无云数据库和无后端 API。Room / SQLite 保存结构化业务数据，DataStore 保存设置与偏好，App-owned Files 保存图片与备份媒体。

### Context

产品是个人工具，核心流程不需要联网；用户数据需要离线可用、可备份、可恢复和可导出。

### Alternatives

Supabase、远程 PostgreSQL、服务端 API 或提前设计多设备同步。

### Reason

本地架构能降低依赖与延迟，保护隐私，并使核心体验不受网络或云端故障影响。

### Consequences

数据库 Migration、完整备份 / 恢复、JSON / CSV 导出和本地文件生命周期属于 V1 核心基础设施。

## 2026-09-10 — 用渐进摩擦帮助恢复，而非永久封锁手机

### Decision

V1 采用标准版专注干预：风险 App 重复打开时逐步增加摩擦，合法使用通过 Temporary Allowance 限时放行，并单独判断 Recovery 是否成功。

### Context

用户可能需要回复消息或查资料，解锁和短暂使用手机不必然等于分心。

### Alternatives

完全锁机、首次解锁即判定失败、无障碍服务式强制封锁。

### Reason

可解释、可恢复的干预更符合真实生活，也能避免系统限制导致核心 Session 逻辑失效。

### Consequences

业务判断由 InterventionEngine 负责，Android 层只负责能力执行与通知降级；解锁、分心、允许和恢复必须分开记录。

## 2026-09-10 — 严格按 Phase 控制 V1 范围

### Decision

按 Phase 0 工程基础、Phase 1 最小学习闭环、Phase 2 笔记与阅读体验、Phase 3 Android 专注干预、Phase 4 趋势与数据安全的顺序交付。

### Context

产品包含状态机、数据库、系统权限、图片和备份，若并行扩张容易失去可验证的核心闭环。

### Alternatives

一次性实现完整 V1，或为 V2 / V3 提前创建知识图谱、复习系统和未来表结构。

### Reason

阶段交付能让每个模块独立验证、回滚和验收，并避免为尚无真实需求的能力增加复杂度。

### Consequences

每个 Phase 开始前先写计划，完成后按验收标准验证并暂停；不得提前增加空表或未来模块。
