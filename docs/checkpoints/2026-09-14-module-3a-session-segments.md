# Module 3A Checkpoint｜Session Segments

日期：2026-09-14

## What Was Built

- Room Schema v4：新增 `risk_apps`、`session_focus_contexts`、`session_risk_app_snapshots`、`session_segments`、`focus_events`。
- `Migration(3,4)` 与 v1→v2→v3→v4 连续迁移验证；旧 Learning Item、Intent、Session、Note、Image、Topic 与 Search 数据不改写。
- SessionSegment 七种事实类型与 FULL / PARTIAL / NONE coverage。
- 事务化 Session 初始化、Segment 同边界切换、正常结束与 ABNORMAL 恢复。
- 纯 Kotlin `SessionSegmentStateMachine` 与 `SegmentTimelinePolicy`。
- Stable Start 120 秒和 Recovery 90 秒 milestone 的纯规则与持久化入口。

## Key Semantics

- Module 3A 没有系统监测证据，因此新 Session 从 `NONE + UNMONITORED` 开始。
- `UNMONITORED` 永远不计为 Focus；PARTIAL 永不升级为 FULL。
- Segment 必须正时长、连续、无重叠、不越过 Session 边界；一个时刻最多一个 Active Segment。
- 进程死亡后不恢复 Active Session：最后可信 heartbeat 之后补记 UNMONITORED，并按既有规则保存为 ABNORMAL。
- 旧 v3 Session 不补造 Segment，不制造历史 effective focus 数据。

## Verification Baseline

- JVM：59 项通过，覆盖状态转换、非法转换、coverage 单向约束、Stable Start、Recovery、时间线空洞/重叠/越界/零时长。
- Room / Instrumented / Compose：API 37 模拟器 111 项通过，覆盖 v3→v4、v1→v4、唯一 Active Segment、Session 初始化、正常结束、重复/非法写入、异常恢复与旧模块回归。
- `lintDebug`、`assembleDebug` 通过；Debug APK 覆盖安装成功，并在断网、强停后冷启动成功。
- Schema：新增 `4.json`；`1.json`、`2.json`、`3.json` 不变；禁止 destructive migration。

## Explicitly Not Built

- DND、Usage Access、Foreground Service、Overlay、Notification。
- 风险 App 实时读取、权限申请或任何 Android 系统行为。
- Closeout UI、effective 指标、Module 3B/3C/3D。

## Next Stage Must Inherit

Module 3B 只能以真实 capability 证据驱动 coverage 与 Segment；不得把权限缺失、Service 中止或不可解释事件区间推断为 Focus/Distraction，也不得修改 Phase 2 历史统计语义。
