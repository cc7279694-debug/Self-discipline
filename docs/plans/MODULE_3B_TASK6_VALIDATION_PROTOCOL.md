# Module 3B Task 6｜Final Validation Protocol

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:executing-plans` to execute Task 6B mechanically. Task 6C analysis returns to the strongest available reasoning model. Do not repair business logic while collecting evidence.

**Goal:** 在不增加功能、不改写冻结语义的前提下，对 Module 3B Task 1–5 做一次可追溯的自动化、安装生命周期、AOSP 与真实设备/OEM 最终验收，并如实区分已验证、降级和未运行项。

**Architecture:** Task 6 是验证层，不新增运行时架构。Task 6B 只执行既有测试和设备协议并记录证据；Task 6C 只分析异常；只有 Task 6C 明确认定为代码 Bug、给出窄修方案并再次获得授权后，Task 6D 才允许改代码。

**Tech Stack:** Kotlin、Jetpack Compose、Room 2.8.x / SQLite Schema v4、DataStore、Coroutines / Flow、Android UsageStatsManager、Foreground Service、NotificationManager / AutomaticZenRule、ADB、Gradle Wrapper。

**Spec:** `docs/PRODUCT_SPEC.md`、`docs/plans/PHASE_3_IMPLEMENTATION.md`、`docs/plans/MODULE_3B_IMPLEMENTATION.md`、`docs/DECISIONS.md`。

## Global Constraints

- 代码事实基线固定为 `c058f2e46e04739307d37e96e5fc16f9e677ee7d`；Task 6A 只增加验证文档。
- Module 3B Task 1–5 已验收冻结；Task 6 不重新设计 Monitoring、DND ownership、Session、Room 或 UI。
- Room 必须保持 Schema v4；禁止新增 Entity、Table、Column、Index、Migration 或改写 `1.json`–`4.json`。
- `unlock != distraction`；风险 App 需要持续可信观察约 10 秒才确认。
- 正常空查询可维持 monitoring continuity，但不能无限延长旧 foreground package。
- 真实失监必须保守落为 `PARTIAL/NONE + UNMONITORED`；PARTIAL 不得恢复 FULL。
- Force Stop、Task Manager Stop、进程死亡和 reboot 后不后台复活 FGS；下一次用户打开 Mirra 才执行恢复。
- DND 与 MonitoringCoverage 正交；DND 失败不能改变 Session 或 coverage。
- API 23–28 仅做 legacy best-effort Priority filter；API 29–34 使用 Mirra-owned `AutomaticZenRule + ZenPolicy`；API 35+ 还必须尊重 user-managed/manual override。
- 本模块不验证 3C/3D 尚未实现的 Overlay、Notification fallback 干预、Progressive Friction、Temporary Allowance、Recovery 90 秒成功、Stable Start 自动判定或 effective metrics。
- 不得为 OEM 兼容引入 AccessibilityService、VPN、`QUERY_ALL_PACKAGES`、强制电池白名单、后台自启框架或云监控。
- 设备不存在或场景未真实执行时必须写 `NOT RUN`；模拟器结果不能替代 OEM 真机结论。
- 测试数据使用专用书籍、Session 和风险 App；证据不得提交真实笔记内容、完整已安装 App 清单、设备序列号或无关 UsageEvent 轨迹。

## Review Focus

1. **Coverage 假阳性：** Service/Usage 不可信后仍保留 FULL 或把缺口算成 FOCUS，必须判定为高优先级 FAIL。
2. **DND ownership：** Mirra 操作用户或其他 App 的规则、覆盖用户中途修改或 legacy 盲恢复，必须判定为高优先级 FAIL。
3. **证据来源混淆：** JVM、模拟器、AOSP 真机与 OEM 真机必须分栏；不能用 fake 或 API 37 AVD 宣称厂商通过。
4. **生命周期缺口：** Force Stop、Task Manager Stop、reboot 后后台复活监测，或重新打开后未落 ABNORMAL/UNMONITORED，必须判定为 FAIL。
5. **验证诱发扩 Scope：** 尚未实现的 3C/3D 行为不能被当作 3B 缺陷，也不能在 Task 6B 中顺手实现。

---

## 1. Task Contract

| 项目 | 定义 |
|---|---|
| Goal | 形成可以复查的 Module 3B 最终验证记录，清楚说明哪些能力在哪个环境得到证明。 |
| Scope | 自动化回归、Schema/hash、APK 生命周期、现有 Monitoring/DND 行为、AOSP 与可用真机/OEM 记录。 |
| Out of Scope | 业务代码修复、Schema、3C/3D、Overlay、干预通知、Allowance、effective metrics、发布 Play Store。 |
| Dependencies | 已冻结的 Task 1–5、现有 debug diagnostics、Gradle Wrapper、ADB 和实际可用设备。 |
| Acceptance | 自动化基线无回归；已执行设备场景有证据；未执行项明确；任何缺口不被伪报 FULL/PASS。 |
| Verification | Gradle 结果、Room migration 测试、Schema hash、ADB 生命周期证据、针对性日志/截图和逐设备记录。 |

## 2. 结果分类与证据规则

每一行只能使用以下结果之一：

| 结果 | 定义 | 是否可支持“能力已验证” |
|---|---|---|
| `PASS` | 在明确记录的设备/环境上实际执行，观察结果完全符合冻结语义，并有可复查证据。 | 是，仅限该环境与场景。 |
| `FAIL` | 已实际执行且违反冻结语义、测试失败、数据不一致，或安全边界被破坏。 | 否；交 Task 6C。 |
| `DEGRADED` | OEM/系统限制使完整能力不可证明，但 Mirra 按设计保守降为 PARTIAL/NONE、保留 Session 且未伪造事实。 | 只能证明降级安全，不能证明完整能力。 |
| `NOT RUN` | 无设备、无权限、场景风险不可接受、时间不足或尚未执行。 | 否；不得改写为 PASS。 |

补充规则：

- `PASS` 必须同时记录环境、步骤、期望、实际结果和证据位置。
- `DEGRADED` 必须写清平台限制、Mirra 的降级事实和用户仍可完成的核心流程。
- 自动化失败后 Task 6B 停止相邻的破坏性/长链路操作，保存日志并交 Task 6C；不得自行改核心代码。
- Task 6B 允许修正纯记录笔误，但不得修改 `app/`、Gradle、Manifest、Schema 或测试以“让结果变绿”。

## 3. 证据目录与记录格式

Task 6B 新建一个历史记录：

`docs/checkpoints/YYYY-MM-DD-module-3b-task6-validation.md`

必要的克制证据放入：

`docs/checkpoints/assets/module-3b-task6/<device-alias>/`

只保存：

- Gradle 汇总与测试数量；
- 设备信息摘要；
- 与 Mirra 相关的定向日志片段；
- 不包含私人内容的必要截图；
- Schema hash 与 Git 状态。

不要提交：完整 bugreport、完整 logcat、设备序列号、真实用户笔记、完整 App 列表或第三方账号信息。设备使用匿名别名，例如 `aosp-api37-avd`、`pixel-api35`、`xiaomi-hyperos-api35`。

## 4. Task 6B-0｜执行前基线

**Files:**

- Read: `AGENTS.md`
- Read: `PROJECT.md`
- Read: `docs/CURRENT_STATE.md`
- Read: `docs/DECISIONS.md`
- Read: `docs/plans/MODULE_3B_TASK6_VALIDATION_PROTOCOL.md`
- Create during execution: `docs/checkpoints/YYYY-MM-DD-module-3b-task6-validation.md`

- [ ] 记录实现基线 SHA `c058f2e46e04739307d37e96e5fc16f9e677ee7d` 与 Task 6A 协议 commit SHA；若实现基线不同，停止并交 Task 6C 判断。
- [ ] 确认分支、remote、工作区状态；保留任何不属于 Task 6 的用户改动。
- [ ] 记录 JDK、Gradle Wrapper、Android Gradle Plugin、compile/target/min SDK、ADB、Emulator 版本。
- [ ] 记录已连接设备清单，但在提交文档中使用匿名 alias，不保存 serial。
- [ ] 对每台设备记录测试前网络、Usage Access、Notification、DND、风险 App 和电池优化状态；测试结束后恢复用户原状态。
- [ ] 使用专用测试数据，不清除真实用户数据库；覆盖安装必须保留已有数据。

当前事实基线：

- `compileSdk = 37`、`targetSdk = 37`、`minSdk = 23`。
- JVM 当前已知基线为 147 项；connected API 37 当前已知基线为 136 项。Task 6B 必须重新执行并记录实际数量，不能直接抄用该数字。
- Room version 为 4，迁移链为 `MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4`。

## 5. Task 6B-1｜自动化回归

**Commands（Windows）：**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --no-daemon
```

若有多台设备同时连接，先明确目标设备或只保留本次记录对应设备，避免把不同设备结果合并成一个模糊结论。

- [ ] JVM 全量通过；记录 tests / failures / errors。当前期望下限为 147，任何减少都必须解释，不能仅写“BUILD SUCCESSFUL”。
- [ ] connected 全量通过；记录设备 alias、API、tests / failures / errors / skipped。当前 API 37 AVD 期望下限为 136。
- [ ] Room v1→v2、v2→v3、v3→v4、v1→v4 真实 Migration 测试包含在 connected 结果中并通过。
- [ ] Task 1 reducer/candidate、Task 3 handshake、Task 4 runtime facts、Task 5 DND core/UI 测试全部在结果中。
- [ ] Phase 1、2A、2B、Start、2C、Theme、2D、3A 回归全部在 connected/JVM 结果中。
- [ ] `lintDebug` 通过。
- [ ] `assembleDebug` 通过并生成 `app-debug.apk`。
- [ ] Preparation 文案仍为“正在准备本次学习…”。
- [ ] Manifest 仍只有已批准的 Usage Access、FGS specialUse、Notification 和 DND 权限；无 Overlay、Accessibility、VPN、`QUERY_ALL_PACKAGES`。

### Schema v4 不变检查

以下为 Task 6A 读取到的冻结 hash；Task 6B 必须重新计算：

| Schema | SHA-256 |
|---|---|
| `1.json` | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| `2.json` | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| `3.json` | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| `4.json` | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

- [ ] 四个 hash 完全一致。
- [ ] `MirraDatabase.version == 4`。
- [ ] Task 6 diff 不包含 Entity、DAO Schema、Migration、`app/schemas` 或数据库版本变化。

## 6. Task 6B-2｜安装与生命周期

每个场景都记录设备 alias、API、是否实体机、前置授权、步骤、预期、实际、结果和证据。

基础 ADB 操作（`<serial>` 只用于本地命令，不写入提交文档）：

```powershell
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell am force-stop com.guanyi.mirra
adb -s <serial> shell am start -W -n com.guanyi.mirra/.MainActivity
adb -s <serial> shell pidof com.guanyi.mirra
adb -s <serial> shell dumpsys activity services com.guanyi.mirra
```

Task Manager Stop 必须从 Android 13+ 系统 UI 人工执行，不能把 `am force-stop` 当作同一个场景。Reboot 可使用设备界面或 `adb -s <serial> reboot`，但必须等待系统完整启动并由用户手动打开 Mirra；不得通过脚本后台拉起监测 Service。

| 场景 | 必要前置 | 冻结预期 |
|---|---|---|
| APK 覆盖安装 | 已有 Phase 1/2/3 测试数据 | 安装成功；旧数据可读；Schema 自动保持 v4；不要求清数据。 |
| 普通冷启动 | App 已被停止 | Start/Knowledge/Mine 可用；无未请求网络依赖。 |
| 断网冷启动 | Wi-Fi、移动数据、VPN 均关闭 | 核心页面和本地数据可用；Session/Note/图片/搜索不依赖网络。 |
| Force Stop 后、重新打开前 | Active monitored Session | FGS 不自动恢复；不产生新的可信 heartbeat/Focus。 |
| Force Stop 后手动打开 | 同上 | 遗留 Session 结束为 ABNORMAL；最后可信点后为 UNMONITORED；不推进正常阅读进度。 |
| Android 13+ Task Manager Stop | Active monitored Session；人工从系统 Task Manager 停止 | 不假设回调；进程/FGS 终止；手动重开后按 ABNORMAL/UNMONITORED 恢复。 |
| Reboot 后、打开前 | Active monitored Session；设备可安全重启 | Mirra 不通过 boot/background 自动续监；无自动 Active Session 恢复。 |
| Reboot 后手动打开 | 同上 | 遗留 Session 按 ABNORMAL/UNMONITORED 恢复；仅 reconcile Mirra-owned DND。 |

补充断言：

- [ ] 恢复后的 Session 历史保留，但不进入 Phase 2 正常统计。
- [ ] 缺口不能被回填为 FOCUS，coverage 不能恢复 FULL。
- [ ] App 没有通过 receiver、WorkManager 或 sticky Service 后台重建监测。
- [ ] 测试结束后恢复设备网络和系统设置。

## 7. Task 6B-3｜Monitoring 行为协议

真实设备优先使用无敏感数据的测试风险 App。只有用户明确选择的风险 App 才能进入 candidate。

| ID | 场景 | 操作摘要 | 冻结预期 |
|---|---|---|---|
| M1 | READY 不要求 package | Usage Access 有效，从 Preparation 明确启动；观察初始 query/READY | 查询、cursor、heartbeat、FGS 就绪即可 READY；foreground 可为 UNKNOWN。 |
| M2 | 锁屏/息屏 | Session 中锁屏并放下手机 | 锁屏/息屏是中性或正向稳定事实；不产生 Distraction。 |
| M3 | 解锁 | 锁屏后解锁但不进入已选风险 App | 解锁本身不产生 Distraction。 |
| M4 | 成功空 query | 保持设备无新 Activity 事件并观察多个 poll | query continuity 保持；不制造 gap；旧 package 不被无限续期。 |
| M5 | 风险访问 `<10s` | 打开已选风险 App，少于约 10 秒后退出 | 不确认 DISTRACTION；可记录一次 brief visit；Segment 不被错误回写。 |
| M6 | 风险访问 `>=10s` | 持续可信停留约 10 秒以上 | 同一 candidate/generation/Focus guard 满足后仅确认一次 DISTRACTION。 |
| M7 | 风险退出 | 已确认后切回非风险/锁屏 | 开始 RECOVERY Segment；3B 不判定 90 秒恢复成功。 |
| M8 | 系统中间页 | Candidate 中进入 Launcher/SystemUI/权限设置 | 不把系统包认作风险 App；候选按可信证据取消或保守 UNKNOWN。 |
| M9 | Usage Access 中途撤销 | FULL Session 中撤销 Usage Access | 从最后可信点原子降 PARTIAL + UNMONITORED；Session 继续；不可恢复 FULL。 |
| M10 | Service 手动停止 | 从 Mirra 持续通知/诊断执行已提供的停止路径 | 先持久化失监，再停 Service；当前 Session 不保留虚假 FULL。 |
| M11 | Service 被系统清理 | 仅在设备能真实复现时 | 不伪造 Focus；下次启动识别 gap；未复现则 NOT RUN。 |

每台真实设备额外记录：

- 首次成功 query 到 READY 的耗时；
- UsageEvent 从实际切换到 Mirra 观察到的延迟，至少记录短访与长访样本；
- 约 1 秒 poll 是否稳定、是否出现批量迟到事件；
- 15 秒 heartbeat 是否避免逐秒 Room 写入；
- 息屏 2 分钟以上、重新解锁后的 continuity/coverage；
- 电池优化默认状态及其影响，但不得强制用户加入白名单作为 PASS 条件。

## 8. Task 6B-4｜DND 行为协议

### 版本路径

| API | 实际路径 | 产品承诺 |
|---|---|---|
| 23–28 | Legacy `INTERRUPTION_FILTER_PRIORITY`，沿用用户已有 Priority Policy | best-effort 静音；不承诺隐藏通知列表；不修改 global Policy。 |
| 29–34 | Mirra-owned、可复用 `AutomaticZenRule + ZenPolicy` | 只管理 Mirra rule；不修改用户 global Policy。 |
| 35+ | 同一 Mirra-owned rule，并尊重 user-managed/manual override | 用户 override 后不强制重新激活；不碰其他规则。 |

### 场景

| ID | 场景 | 冻结预期 |
|---|---|---|
| D1 | preference OFF | Session 正常开始；DND `NOT_APPLIED`；系统状态不变。 |
| D2 | preference ON + permission missing | Session/monitoring 不受阻；DND `APPLY_FAILED`；提供授权与当前 Session retry。 |
| D3 | permission granted | 在 monitoring bind/degradation 已确定后 best-effort apply；DND 延迟不影响 coverage。 |
| D4 | API 29+ 首次创建 | 只创建一条可识别 Mirra rule；不改 global Policy。 |
| D5 | API 29+ 后续 Session | 复用同一 rule；结束后停用 Mirra rule，不删除/修改用户规则。 |
| D6 | 正常 Session finish | Room 正常结束先提交，再 release；release 失败保留可重试状态，不丢 Session。 |
| D7 | 用户中途手动修改 DND/rule | API 35+ 尊重 override；不立即重新激活；API 23–28 ownership 不确定时不恢复。 |
| D8 | Active 时撤销 DND access | coverage 不变；release 失败记录 `RELEASE_FAILED`；重新授权后可 reconcile。 |
| D9 | apply/release 期间异常 | 不改变 FULL/PARTIAL/NONE；不留下被错误标记为成功的生命周期。 |
| D10 | crash/reconcile | API 29+ 仅重新发现/停用 Mirra-owned rule；API 23–28 跨进程无法证明 ownership 时不盲恢复。 |
| D11 | 当前 `APPLY_FAILED` 后关闭偏好再 retry | 仍可重试当前 Session；DataStore 保持 OFF，下一场不自动启用。 |

设备记录必须区分“系统 API 调用成功”“用户可见效果”和“ownership 安全”。通知是否完全隐藏受用户 Policy、其他规则合并和 OEM 影响；不能仅凭状态 enum 宣称视觉效果 PASS。

## 9. Task 6B-5｜模拟器与真机矩阵

### 模拟器记录

| 环境 | 重点 | 结果 | 证据/备注 |
|---|---|---|---|
| API 23 | legacy Service / Usage / DND 分支 | `NOT RUN` 或实测 | |
| API 29 | own-rule 起点、Usage event 分支 | `NOT RUN` 或实测 | |
| API 33 | Notification permission、Task Manager | `NOT RUN` 或实测 | |
| API 34 | specialUse FGS | `NOT RUN` 或实测 | |
| API 35 | user-managed rule 分支 | `NOT RUN` 或实测 | |
| API 37 | 全量 AOSP 回归 | `NOT RUN` 或实测 | |

Task 6B 填表时未执行的行保持 `NOT RUN`，不能用“代码分支有单元测试”替换设备结果。

### 每台实体设备记录

| 字段 | 值 |
|---|---|
| Device alias | |
| 厂商 / 型号 | |
| Android / API | |
| ROM / build | |
| Mirra implementation SHA | `c058f2e46e04739307d37e96e5fc16f9e677ee7d` |
| Usage Access | granted / denied / revoked / not run |
| Notification permission/channel | |
| DND access | |
| FGS 是否稳定 | |
| 锁屏/息屏行为 | |
| UsageEvent 实际延迟 | |
| `<10s` / `>=10s` Candidate | |
| Service 被系统清理后的结果 | |
| DND rule / restore / override | |
| 电池优化状态与影响 | |
| 总结果 | PASS / FAIL / DEGRADED / NOT RUN |
| 证据/备注 | |

目标优先级：

1. Pixel / AOSP 真机基准；
2. Xiaomi / HyperOS；
3. OPPO / ColorOS；
4. vivo / OriginOS；
5. Samsung / One UI。

不要求购买设备。没有对应设备时，整行标 `NOT RUN`。

## 10. OEM 风险判定

| 观察结果 | 分类 |
|---|---|
| OEM 延迟/停止监测，但 Mirra 及时或在下次启动保守落 PARTIAL/NONE + UNMONITORED | `DEGRADED`，记录限制和缺口。 |
| OEM 限制导致功能不可用，用户仍可无监测完成 Session | `DEGRADED`。 |
| 失监后仍保留 FULL、继续写 FOCUS、伪造风险持续或后台偷偷复活 | `FAIL`，Task 6C 必须分析。 |
| 用户 DND/其他 rule 被 Mirra 修改、关闭或覆盖 | `FAIL`，高优先级 ownership Bug。 |
| UsageEvent 延迟导致 candidate 不确认，但没有误报 | 先 `DEGRADED`；记录延迟，不擅自调阈值。 |
| 迟到/重复事件造成错误 DISTRACTION 或历史 Segment 被不安全回写 | `FAIL`。 |
| 无法在当前设备复现系统清理、Task Manager Stop 或 DND override | `NOT RUN`，不是 PASS。 |

Task 6B 不给 OEM 限制设计新机制。Task 6C 先判断它是代码 Bug、Android/OEM 限制、测试方法问题还是尚无证据。

## 11. Task 6C｜异常分析门槛

Task 6C 对每个非 PASS 项输出：

1. 复现环境和最小步骤；
2. 期望与实际；
3. 数据安全是否受影响；
4. 是否违反冻结语义；
5. 分类：代码 Bug / OEM 限制 / 测试问题 / 证据不足；
6. 最小处理建议；
7. 是否需要 Task 6D；
8. 修复后必须重跑的矩阵子集与全量回归。

没有明确代码根因时不得进入 Task 6D。OEM 限制若已正确降级，记录兼容边界即可，不为“全绿”扩大权限或后台能力。

## 12. Task 6D｜修补授权边界

Task 6D 仅在用户根据 Task 6C 报告再次授权后执行：

- 只实现已批准的最小修复；
- 不改产品语义、Room Schema、DND ownership 或 Monitoring 状态机，除非 Task 6C 明确升级给强模型重新设计并获得用户批准；
- 必须先建立失败回归，再修复，再重跑相关设备场景和完整自动化；
- 独立 commit；不得顺便进入 3C。

## 13. Module 3B 最终冻结标准

Task 6B 的“机械执行完成”允许存在 `NOT RUN`，前提是记录诚实完整；但 Module 3B 的“最终设备验收”分两层：

### 工程回归冻结

- [ ] JVM、connected、Room/Migration、Compose、lint、assemble 全部 PASS。
- [ ] Schema v4 hash 和数据库版本完全不变。
- [ ] APK 覆盖安装、普通/离线/Force Stop 冷启动 PASS。
- [ ] AOSP API 37 现有功能闭环 PASS。
- [ ] 无高优先级 FAIL；所有 DEGRADED/NOT RUN 明确记录。

### 实体设备能力冻结

- [ ] 至少一台 Android 13+ 实体设备完成 Monitoring、锁屏、风险 App、Usage Access 撤销、Service 停止、DND apply/release 与 Force Stop 恢复核心场景。
- [ ] 若没有实体设备，Task 6B 报告可以完成，但 Module 3B 的实体设备验收保持 `NOT RUN`，不能宣称发布级设备兼容已完成。
- [ ] Pixel/AOSP 之外的 OEM 可以是 `NOT RUN`；发布风险中必须逐项列出。

最终报告必须明确：

- 自动化与设备分别证明了什么；
- 哪些品牌/API 没有测试；
- 哪些行为是 PASS、DEGRADED、FAIL 或 NOT RUN；
- 是否存在 Task 6D；
- 是否建议冻结 Module 3B；
- 仍不得进入 3C，除非用户单独授权。

## 14. Task 6B Git 与停止点

- Task 6B 只允许修改 Task 6 验证 checkpoint 和经过脱敏的最小证据文件。
- 发现 FAIL 时先保存证据并停止相应高风险场景；不得修改 `app/`、Gradle、Manifest、测试或 Schema。
- 提交前检查证据没有设备 serial、真实笔记、完整包列表或无关日志。
- 是否 commit / push 以 Task 6B 当回合用户授权为准；本协议本身不扩大 Git 权限。
- 完成 Task 6B 报告后停止并交 Task 6C；不得自动执行修复或进入 3C。

## 15. BLOCKERS

Task 6A 规划审查未发现必须修改当前业务代码才能开始验证的 blocker。

当前未知项必须由 Task 6B 填写，而不是在计划中推断：

- 可用实体设备与 OEM 清单；
- API 23/29/33/34/35 AVD 是否已安装；
- 真实 UsageEvent 延迟和 OEM 后台清理行为；
- Android 13+ Task Manager Stop、reboot 与多规则 DND 的实机证据；
- `specialUse` 的 Play 审核结论不属于本地 Task 6 验收，保持发布风险项。

## 16. 过度设计检查

- 不创建验证框架、设备农场、云日志、事件总线或新的运行时诊断数据库。
- 不为一次性设备动作编写会改变系统状态的复杂自动化；Task Manager Stop、权限撤销、DND override 允许人工执行并记录。
- 不追求所有品牌全绿；优先证明保守降级不会污染用户事实。
- 不把 3C/3D 尚未实现的产品能力塞入 Task 6。
- 只保存足够复核结论的最小证据，避免隐私和仓库膨胀。

## 17. Task 6A 自检结果

- Spec coverage：自动化、生命周期、Monitoring、DND、版本矩阵、OEM、证据格式、异常分流均有对应 checklist。
- Type/语义一致性：FULL/PARTIAL/NONE、UNMONITORED、DndLifecycle 与 Task 1–5 冻结实现一致。
- 版本校准：已按真实实现修正为 API 23–28 legacy、API 29–34 own rule、API 35+ own rule + user override。
- Scope：没有为 Overlay、摩擦、Allowance、Recovery success 或 effective metrics 增加验收项。
- Evidence：Task 6A 只建立协议，未执行 Task 6B；所有设备结果仍待实测。

**本回合状态：Task 6A 协议已建立；未修改业务代码、Manifest、Room、Schema、Migration 或设备状态；未执行 Task 6B。**
