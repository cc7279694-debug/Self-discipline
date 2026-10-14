# Current State

更新：2026-10-14（Asia/Shanghai）。

## 当前阶段与授权

**Mirra Band 05D / DEV_COMPLETE_AWAITING_DEVICE_NATURAL_USE**。双端可靠独立完成记录同步开发、构建、原生隔离验证及产物核验已完成。用户后续已授权先备份后自行覆盖配套包（含APK正常Room升级）并体验，实际结果尚未反馈；Codex直接操作真实设备/数据库及恢复、清数据等不在此同意范围。不进入05E、不自动PR或合并main。

本隔离树`E:\CODEX\mirra-watch\.local\android-worktree`，分支`codex/mirra-band-05d-record-sync`，基线`6589a19f0305245fe0e63bf77f105f61f27a913e`。Band分支`codex/module-05d-record-sync`，基线`1c2ac537545f4d5cb365dfa7e276cbd24cd2425f`。原手机工作区不修改。合同在Band `docs/plans/MIRRA_BAND_05D_RECORD_SYNC.md`；最终证据在Band `docs/evidence/module-05d/README.md`及artifacts.json/verification.json。本树[05C证据](evidence/module-05c/README.md)保留为历史，不覆盖当前05D范围。

## 已接受的设备事实

用户报告05C真实书籍同步、离线独立使用与保存基本正常。用户已确认正式Mirra Band **0.4.1/code10真机视觉正常**，05C.1记为**USER_REPORTED_PASS — PASS WITH NOTES**；原0.4.0白底浅字失败历史保留。

Module03真实IDLE、监测学习中手机锁屏/腕端退出重进及首次NO_DEVICE自动恢复继续继承；05A file路径损坏恢复/覆盖升级/正常重启保留、05B交互与0.1.3跨实例参考计时用户通过也保留。05B.3流畅度改善仍无明确认可，不能由本轮自动化外推。

## 05D当前实现

- 唯一应用级WearableCoordinator、OwnedWearTransport与官方SDK owner兼容v1/v2/v3，协商v4 `LOCAL_RECORD_SYNC`。记录披露许可独立、默认关闭，明确原始记录同步范围；仍存no-backup AtomicFile，不随Full Backup携带。
- OFFER必须再次完整接收原文，NEED→有界PART→COMMIT→ACK；4096字节帧、最多8192字节原文、8片/每片1024字节、固定30秒期限，不因重复/乱序片延长。严格身份、摘要、字节数及client/request/stream归属，不接受迟到回执替代新结果。
- RoomWearableRecordReceiver捕获startup、Room与StorageMaintenanceGate。完整原文校验、withTransaction提交及原文UTF-8字节核对后才返回保存结果。相同installationId/sessionId与原字节幂等；不同内容冲突、不覆盖，即使摘要相同也不跳过原文核对。SDK send与HELLO不表示持久化成功。
- OwnedWearTransport仅对严格v4记录帧使用单worker有界队列，最多10条在途/等待、32768字节，每帧重新核对SDK授权；溢出退役代际并清队列。维护、关闭、撤权、监听替换及source退役隔离旧任务，不建立第二连接所有者。
- 手机状态把当前流确认、数据库条数和历史接收分开；新源/新握手重新核对，不把旧成功状态当作手机备份回退后的完整性证明。腕端每次新握手重放保留档案及pending，缺失行可再次接收。

## 数据、备份及继承边界

Room **v5**显式Migration4→5仅新增`wearable_completed_records`及receivedAt索引，schemas1–4保留。新表以installationId/sessionId复合主键保存canonical TEXT、SHA-256、字节数及receivedAt，无原业务外键/级联，不注入study_sessions、不占唯一Active Session、不更新书籍进度、监测或统计。手机Intent/Session转换、FirstActionResolver及学习算法保持。

Full Backup **format2/schema5**包含新原始记录表和所有既有数据/媒体。旧**format1/schema4**由冻结可信v4布局严格验证，完整复制旧12个权威表及媒体到干净v5，再校验；不直接迁移不可信incoming库。闭库恢复日志兼容合法v4/v5，恢复及回滚保护保留。可读JSON/CSV仍是原手机业务12表，明确不含腕端原始记录；要保护全部新记录须使用新版完整备份。

腕端原Session主schema1、8 pending及8192字节单槽保持；新增双档案每槽65536字节/最多64完整记录，双镜像精确读回后才释放pending，无自动删除。手机旧备份回退后的补传依赖腕端仍保留原文，不能承诺丢失档案恢复。白色Mirra Blue、一次确认开始、参考计时、休息主动继续及nullable结束页码继承；参考值不计入有效专注。独立实验记录不迁移、不操作。

## 当前验证与交付

最新实际未过滤JVM **849/849 PASS**（91 suites）；最后lint **0 errors / 20 warnings / 1 hint**。Band未过滤Node **740/740 PASS**。自有API37原生 **70/70 PASS**，59.033秒，0skip/assumption，真实迁移/SQLite失败回滚/文件库重开/新旧备份和相关回归均执行；首轮69/1旧fixture计数错误与0case启动ANR历史保留，修正后完整重测，所属AVD已正常关闭。

实际APK **1.4.0/code9**（17016301字节）与正式腕端 **0.5.0/code11**（87768字节）已构建/验签，沿用`com.guanyi.mirra`与原29ded证书。SHA-256、签名、构建与Git分支由交付证据登记；RPK六页颜色/条件和签名编译包受控同步回归通过。此前05C的824项JVM及API37原生19项保留为历史，不替代本次05D证据。

## Not Run / 当前停止边界

05D原配对手机与国行9Pro正式安装、实际Room4→5升级、设备原生SHA及记录确认自然使用：**未收到实际反馈**。用户已同意先备份后自行覆盖配套包并体验，记录USER_SELF_INSTALL_AUTHORIZED，不推定已备份/安装/升级或PASS。Codex没有操作真实手机/用户数据库或其他设备；恢复、直接DB操作、签名转换、卸载/清数据/降级不在此同意范围。隔离模拟器证据不能外推OEM、无线/固件、真机耗时、电量或长期耐久。

本次用户自行覆盖APK1.4.0/code9及RPK0.5.0/code11、APK正常Room升级、开启「接收腕端完成记录」与自然体验已获同意；不重复请求同一授权，等待实际反馈，见device-acceptance.json。停05D，不强制USB或冗长人工矩阵，05E历史展示/统计合并尚未实施。
