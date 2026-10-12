# Mirra Band Module 04 — 学习常用控制实施合同

## 1. 项目目标

正式执行用户已确认的 **方案 B：学习常用控制**。

在 Module 03 已经真机验证的只读伴侣上，新增以下操作：

1. 从手环选择休息 5 分钟或 10 分钟。
2. 从手环提前结束休息。
3. 在手环查看真实恢复状态，不人为确认恢复成功。
4. 手机已经创建有效学习 Intent 时，在手环查看并明确确认第一步动作，然后请求手机正式开始学习。

总体目标：减少为了执行常用学习操作而反复拿起手机的次数。

**手机始终是学习记录、监测、状态机和数据库的唯一权威。**

本轮不做从手环选书、创建 Intent、修改页码、笔记、结束 Session、分心振动、账号、云端或后台常驻。

## 2. 现有开发基线

先只读核实两个仓库当前分支、HEAD、工作区及未提交改动，不从旧 main 重新开发。

**Mirra Band**
- 仓库：`cc7279694-debug/mirra-band`
- 已接受基线分支：`codex/module-03-readonly-companion`
- 已知 HEAD：`8b14819f15682fd22752dbdf2b05e8d465e255ee`
- 新分支建议：`codex/module-04-wrist-controls`

**Mirra Android**
- 仓库：`cc7279694-debug/Self-discipline`
- 已接受基线分支：`codex/mirra-band-readonly-bridge`
- 已知 HEAD：`3fecf50a208ddb145a67986749420b490193e903`
- 新分支建议：`codex/mirra-band-control-bridge`

保留原手机独立工作区及用户未提交内容。

当前已运行配套版本为 Android `1.1.3-band-readonly` / code 6 与 Band `0.2.5` / code 7。

本模块建议目标版本为 Android 1.2.0 / code 7 与 Band 0.3.0 / code 8。以开始时核实的实际版本为准，保证单调升级。

## 3. 手机业务复用规则

### 3.1 开始休息

优先使用现有：

`FocusSessionActions.startBreak(sessionId, expectedSegmentId, durationMillis, sample)`

允许值只有当前业务定义的短休息、长休息时长，初始为 300000 / 600000 毫秒。不得接受任意客户端时长。

必须检查：
- Session 确实活动；
- closeoutState 为 ACTIVE；
- 当前 Segment 与命令预期 Segment 完全一致；
- 当前转换符合既有 SessionSegmentStateMachine；
- 当前业务 owner、监测控制器、存储代际允许操作。

FOCUS、DEEP_FOCUS 优先支持；UNMONITORED 如果现有正式接口允许且能正确执行，也可支持。其他状态不应新增未验证的快捷转换。

### 3.2 提前结束休息

复用：

`FocusSessionActions.finishBreak(sessionId, expectedSegmentId, sample)`

成功后应按现有规则进入 RECOVERY。

不能使用 `returnToStudy()` 替代 finishBreak；当前该接口主要用于处理分心提示，并不等价于结束休息。

不得人为填写 RECOVERY_SUCCEEDED、Stable Start 或有效专注时间。

### 3.3 第一动作确认并开始

仅支持手机**已经创建的活动 Intent**。

手环必须先展示实际第一步动作，用户点击明确的确认按钮后才能发送命令。不能因进入准备页面、读取快照或倒计时归零而自动开始。

手机执行前重新核实：
- Intent 身份、activeSlot、未结束及未过期；
- 书籍处于 IN_PROGRESS；
- 没有另一活动 Session；
- 用户之前看到的第一步动作与当前持久化内容相同；
- 第一动作满足 FirstActionResolver.requireChosen；
- 当前页码、书籍身份与命令预期一致。

**特别要求：在最终创建 Session 的数据库事务内再次检查预期动作和页码，避免检查后至提交前被手机端编辑的竞态。** 不得只做 UI 或事务外检查。

复用现有 SessionStartCoordinator 和原有学习事务，不直接从通信层向 Room 插入 Session。

需要给手环路径增加严格监测准入策略，但手机原有启动行为保持不变：

- 监测前提不满足、FGS 受到后台限制、READY Lease 未取得：在 Session 提交前停止，保留 Intent，返回 `REQUIRES_PHONE` 或明确失败状态。
- 不允许因腕端后台启动限制而直接退化创建 UNMONITORED Session。
- 如果 Session 已持久化提交，之后验证或绑定监测失败，沿用既有 DEGRADED 处理，如实返回“已开始，但监测降级”；不得尝试危险回滚或谎称未创建。
- 同一 Intent 的并发/重复启动不得生成第二个 Session。

禁止修改原有学习状态机、监测评价标准、结束保存算法。

## 4. 协议设计

在 `protocol/v2/` 建立严格的 v2 schema、fixture 和兼容测试；保留 `protocol/v1/` 不变。

v2 协商增加 `SESSION_CONTROL` 能力。v1 无此能力时继续只读；正式手环不得擅自尝试 v1 控制命令。

允许的命令：
- `START_BREAK`
- `FINISH_BREAK`
- `CONFIRM_PREPARATION_AND_START`

v2 SNAPSHOT 增加当前操作所需的最小事实：
- activeSegmentId（如有）
- 当前允许动作
- 手机签发的短时 controlGrant
- 与第一步动作、Intent、起始页相关的状态校验信息

命令包含协议版本、clientInstanceId、requestId、streamId、controlGrant、动作类型和对应的 Intent / Session / Segment 预期身份。

controlGrant 必须绑定设备节点、当前握手流、当前存储代际、被确认的状态与动作，设置短期有效期限。授权过期或状态变化后必须重新获取快照。

所有消息保持现有 4096 UTF-8 字节预算、严格字段白名单和 Unicode 校验，不允许客户端发送任意数据库操作或用户代码。

新增 `COMMAND_RESULT`，明确区分：
- APPLIED：手机业务操作已完成；
- ALREADY_APPLIED：同一操作可被证明确实已执行；
- STALE_STATE：业务状态已变化；
- REQUIRES_PHONE：需要在手机完成；
- DISABLED / UNAUTHORIZED；
- BUSY / FAILED；
- RESULT_UNKNOWN：结果尚不能确认。

具体编码名称可以微调，但两端协议、schema、fixtures 和测试必须严格一致。

### 防重复及不确定结果

同一握手流内按 requestId 缓存命令处理结果，并限制缓存数量。

持久化业务仍通过唯一 Active Intent、Session、Segment 和事务检查保证安全。不得仅依赖内存去重保证业务唯一性。

手环超时或断连后：
- 不自动重发状态变更命令；
- 显示“结果未确认，正在同步”；
- 重新请求完整状态；
- 通过手机已确认的状态呈现结果；
- 不能凭 send 成功回调显示“休息已开始”或“阅读已开始”。

不增加离线命令队列。

## 5. 手机控制权限

在现有「我的 → 手环伴侣」下增加独立的「允许手环控制学习」开关。

默认关闭。用户必须主动开启并阅读具体操作范围。

存储在 no-backup 的本地设置中，不进入 Room Schema 或 Full Backup v1 的 portable preferences。

关闭后立即拒绝新的控制命令并撤销当前控制授权。原有只读伴侣可继续工作。

禁止自动增加危险 Android 权限、伪造用户可见界面、后台启动无关 FGS、通过音频或定位服务保活。

## 6. 生命周期与安全

沿用 Module 03 的应用级通信所有权、SDK 授权设备归属、请求序列、代际隔离、维护暂停与重新握手。

扩展为与数据库存储代际绑定的命令执行器，不能把长期存活的 WearableCoordinator 直接绑定到可被备份恢复替换的旧 AppContainer。

保持：
- SDK 发送/等待在数据库事务及维护锁外；
- 状态读取和业务准入在权威手机端；
- 单个在途控制命令、有限排队和超时；
- 隐藏/销毁手环页面时清理页面所有权；
- 断连或过期后禁止操作；
- 签名身份不匹配、用户未授权、多个设备不明确时 fail closed；
- 维护、备份恢复、权限撤销、旧流和进程重建后，旧命令不得重新生效。

只通过小米已验证的官方通信通道，不新建 Bridge App、不使用云端、不修改现有包名或签名身份。

## 7. 腕端 UI

保持深墨背景、Mirra Blue 和现有小屏信息体系。

**专注中**
- 主要显示真实学习状态和时间；
- 页面主操作为「休息」；
- 选择 5 分钟或 10 分钟后提交手机执行；
- 执行中禁用重复点击。

**休息中**
- 主要显示剩余时间；
- 操作为「提前结束休息」；
- 完成后展示 RECOVERY，而不是直接标记专注。

**启动准备中**
- 明确展示真实书籍和第一步动作；
- 长文本可滚动阅读；
- 显式操作「这一步已完成，开始学习」；
- 请求失败保留准备状态，并展示具体、易懂的下一步。

**恢复中**
- 显示手机真实状态；
- 不提供伪造“恢复成功”的按钮。

**未知 / 断连 / 已结束 / 保存中**
- 不显示可执行的学习控制按钮。

仅当手机明确授权、v2 协商成功、快照新鲜且有可用 controlGrant 时显示可操作状态。

控制操作必须适配目标 336×480 真机触控，不依赖仅在浏览器能用的交互。保留深色 UI 产品化目标，检查顶部裁切、长文案、按钮安全边距和滚动返回。

不私自重绘用户批准的 Logo。演示包与正式包继续身份隔离，演示操作绝不修改手机数据。

## 8. 建议实现文件

按现有架构做最小必要修改，具体命名以真实代码约束为准。

Band：
- `protocol/v2/schema.json` 及双端共享 fixtures
- `src/lib/companion-protocol.js`
- `src/lib/companion-store.js`
- `src/lib/companion-presentation.js`
- `src/pages/Home/index.ux`
- `src/pages/Detail/index.ux`
- 必要的单独控制确认页面
- 对应 Node 测试

Android：
- `domain/wearable/WearableProtocol.kt`
- 新增 `domain/wearable/WearableCommandExecutor.kt`
- `domain/wearable/WearableCoordinator.kt`
- `domain/wearable/WearableSnapshot.kt`
- `data/wearable/WearableSnapshotReader.kt`
- `domain/SessionStartCoordinator.kt`
- 必要时对 `StudyWorkflowRepository` 增加原子预期动作校验
- `di/AppContainer.kt` 与 `MirraApplication.kt` 的代际绑定
- `feature/wearable/WearableSettingsScreen.kt`
- 对应 JVM、Android 集成与数据边界测试

优先复用现有 FocusSessionActions、状态机和 SDK Transport，不能重写一套并行业务服务。

## 9. 实施顺序与验证

这是**一个 Module 04**。内部任务连续自主开发，不需要逐个向用户申请验收。

第一阶段：v2 协议、v1 兼容、手机控制权限，自动化先建立失败测试。

第二阶段：手机 START_BREAK / FINISH_BREAK 命令执行、状态防竞态、ACK 和去重。

第三阶段：严格的准备动作确认及 Session Start，重点处理监测后台限制、事务内二次校验和已提交后的降级结果。

第四阶段：手环 UI、状态刷新、错误反馈、控制按钮与小屏适配。

第五阶段：完整跨端回归、构建和必要的真机操作验证。

至少覆盖以下关键情况：

- 当前合法 Session 开始休息；
- 休息提前结束后实际进入 RECOVERY；
- 多次点击、重复消息、迟到 ACK 不重复执行；
- Segment 已变化时拒绝旧命令；
- Intent 过期、书籍被暂停、第一步被编辑或页码变化时拒绝旧开始命令；
- 锁屏后台 FGS 启动被系统拒绝时，不创建意外 UNMONITORED Session；
- 监测已提交后降级时准确反馈真实 Session；
- 保存中、断连、撤权、多设备、旧存储代际均不执行写操作；
- 新手机配旧 v1 手环继续只读；
- 新手环遇旧手机只能只读，不发送无支持的控制命令；
- 备份恢复、学习算法、监测行为、Room 与个人数据保持原有安全边界。

继续做有意义的全量 JVM、相关 Android instrumented/connected 测试，以及腕端单元和页面测试。区分真实通过、条件未满足和未执行项，不拼接数据冒充全量通过。

真实手机和手环至少需要取得一组由用户自然使用完成的操作证据：休息、提前结束，以及具备系统条件时的第一步确认与开始学习。

不要求用户反复执行人工测试矩阵、拍摄所有状态或参加长时间被动验收。

如果锁屏开始学习受 Android 系统限制，明确给出受限结果，不通过放宽监测安全规则制造通过。

## 10. 数据安全、发布与交付

不修改 Room v4 Schema、Migration、完整备份格式、监测/Closeout 算法或用户签名身份。

不读取原始用户数据库用于调试，不卸载、不清数据、不重签名、不降级。

保留已有 APK/RPK 构建身份。实际安装前核对目标已安装版本、公开证书、文件哈希和版本升级关系；真实设备安装、权限与系统操作仍由用户明确授权。

最终交付：
- 配套手机 Debug APK；
- 配套正式手环 RPK；
- 双端可复核版本、SHA-256 与公开证书指纹；
- 新增控制功能、协议兼容性和自动化验证结果；
- 实际真机成功项、系统限制及未覆盖项；
- 两仓库功能分支 Commit 与 Push。

禁止自动合并 main、创建发布或进入 Module 05。

若代码与自动化已完成但真机暂未执行，报告 DEV_COMPLETE_AWAITING_NATURAL_USE，而不是宣称端到端成功。

## 11. 开发协作方式

继承用户最新决定：

**Codex 自主开发＋必要自动化验证＋自然使用反馈。**

普通代码实现、测试失败、构建、无关紧要的技术选择由 Codex 自主解决，不逐项等待用户批准。

真正需要暂停的事项仅限：
- 生产签名、用户数据或不可逆变更；
- Android 权限/安装需要用户确认；
- 现有架构无法安全满足已确认产品目标；
- 可能改变 Mirra 原有学习算法或数据含义的产品决策。

优先交付实际可用的手环操作能力，不制造重复文档、过量 checkpoint 或无意义的测试工作。

完成后停止在 Module 04 的交付边界，等待用户自然使用反馈和 ChatGPT 独立审查。