# Module 3B Task 3｜Monitored Session Start Handshake

## Goal

让用户从启动准备进入 Session 时，只有监测在 Session 起点已真实 READY 且连续，才创建 `FULL + FOCUS`；监测不可用时仍能以 `NONE + UNMONITORED` 阅读。不实施 Task 4 持续监测状态机。

## Verified completed

- Service 每轮只由 `UsageMonitor.poll()` 查询 UsageEvents；轻量 AppOps/解锁复核不再额外查询，通知与 DND 不再每秒刷新。
- `MonitoringReadyLease` 绑定 generation、同一 ClockSample 的 READY wall/elapsed 时间、成功查询代次、cursor、连续性 epoch 和单调 TTL；READY 不需要已知前台 package。
- `SessionStartCoordinator` 串联 `IDLE → PREPARING → READY_LEASE → COMMITTING → COMMITTED → BOUND`。预留 Session ID、事务返回 `created` 与 Mutex 防止双击/并发或取消恢复时将别的 Session 错绑到当前 generation。
- Room 单事务完成 Intent 与 Learning Item 复核、单 Active Session/Segment 约束、Session、FULL Context、风险 App 快照、初始 FOCUS Segment 和 Intent CONVERTED。初始 Session/Segment/heartbeat 均用 READY 时刻。原普通 `startSession()` 保留 `NONE + UNMONITORED`。
- capability/FGS/READY/Lease 失败会停止未绑定 Service 并走普通入口；Intent 超时、状态不合法、页码非法或 Active Session 冲突仍按业务拒绝。提交前取消清理孤儿监测；提交结果未知时按预留 ID 查库；提交后绑定前连续性失败保留 Session 并降 `PARTIAL + UNMONITORED`。
- Preparation 展示握手等待并禁重复提交；保守回退轻提示。正常结束 Session 后释放该 Session 的监测绑定。
- API 37 模拟器实测未授权 `NONE + UNMONITORED`、授权 `FULL + FOCUS`、双击仍只有一个 Active Session/Segment、同一 READY 起点、强停冷启动 `ABNORMAL/PARTIAL` 与离线启动。

## Data and boundaries

- Room Schema 保持 v4；未改变 Entity、`4.json` 或 Migration。
- FGS 与 Room 不存在跨资源 ACID；这里依靠 generation、短期 Lease、单次 Room 事务、提交后再验证和失败补偿。
- Task 4 的风险 App 实时切段、周期性 Room heartbeat、6 秒 runtime gap watchdog、已绑定 Session 中途的即时 `FULL → PARTIAL` 均未实现；DND apply、Overlay、Recovery 与有效专注统计也未实现。

## Verification

- `:app:testDebugUnitTest`：109 项通过，0 失败。
- `:app:connectedDebugAndroidTest`：API 37 模拟器 123 项通过，0 失败；含 Room 事务、取消竞态及 Compose 握手状态。
- 本轮早先一次全量运行中，旧 `StartExperienceCorrectionTest.choosingThisStudyDoesNotImplicitlyChangeMainline` 曾单次失败；该用例独立复跑通过，之后最终完整运行 123 项全部通过。未修改该旧业务逻辑。
- `:app:lintDebug`、`:app:assembleDebug`：通过。
- `adb install -r`、权限两支、双击、断网冷启动和强停恢复：通过。
- OEM 实体真机：Not Run；按计划留待 Task 6。

## Next step

先由用户独立验收 Task 3，再单独授权 Task 4。不得因本次能创建 FULL Session 就提前使用 effective focus 指标。
