# Mirra Phase 3D｜Implementation Index

状态：仅实施计划落库；尚未授权或开始3D-1～3D-4编码。本索引只负责导航、覆盖追踪和Gate，不另立设计或统计算法。

## 唯一设计基线与当前事实

- 仓库：`cc7279694-debug/Self-discipline`；工作目录 `C:/Users/CDD/Documents/ChatGPT/Mirra`。
- 唯一3D设计规范：[MIRRA_PHASE_3D_DESIGN.md](MIRRA_PHASE_3D_DESIGN.md)。设计commit：`874d80318c56661218fd03579ba2f1253cc35440`，远程分支 `codex/phase-3d-design`。
- 本轮从该精确SHA创建 `codex/phase-3d-plans`；只新增本索引与下列四份计划，不修改CURRENT_STATE、DECISIONS或任何app/文件，不运行未来功能测试。
- Module3C已冻结；当前仍旧单事务finish、预留Closeout字段未实施；现有controller串行边界/settlement/旧样本保护可复用，但cleanup调用位置需由3D-1迁移。
- Room4，schemas1–4不变；v4 SHA256：`EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`。
- 计划中的新类/接口/测试都是未来授权实现项，不是当前仓库已有能力或本轮测试PASS。

## 交付顺序与分支Gate

DESIGN SPEC → Plans独立验收/冻结 → 3D-1 → 独立review/freeze → 3D-2 → 独立review/freeze → 3D-3 → 独立review/freeze → 3D-4 → 最终独立review → Module3D纯文档冻结。

| 包 | 计划 | 未来分支 | 必须使用的父基线 | 完成后的停止点 |
| --- | --- | --- | --- | --- |
| 3D-1 安全结束 | [Closeout Plan](MIRRA_PHASE_3D_1_CLOSEOUT_PLAN.md) | `codex/phase-3d-closeout` | 用户确认的Plans freeze commit，不是浮动计划HEAD | `[3D_1_COMPLETE]`，独立验收，不进入3D-2 |
| 3D-2 有效指标 | [Effective Metrics Plan](MIRRA_PHASE_3D_2_EFFECTIVE_METRICS_PLAN.md) | `codex/phase-3d-effective-metrics` | 独立验收后的3D-1 freeze SHA | `[3D_2_COMPLETE]`，独立验收，不进入3D-3 |
| 3D-3 阅读记录 | [Reading Record Plan](MIRRA_PHASE_3D_3_READING_RECORD_PLAN.md) | `codex/phase-3d-reading-record` | 独立验收后的3D-2 freeze SHA | `[3D_3_COMPLETE]`，独立验收，不进入3D-4 |
| 3D-4 最终联调 | [Final Validation Plan](MIRRA_PHASE_3D_4_FINAL_VALIDATION_PLAN.md) | `codex/phase-3d-final-validation` | 独立验收后的3D-3 freeze SHA | `[3D_4_COMPLETE]`，等待最终review，不自动冻结/发布或进入Phase4 |

下一包实施前须取得用户明确授权及前一包验收文档里的**实际freeze SHA**，核对本地/远程提交和干净工作区再建分支。未来SHA尚未发生，不填造值；没有已确认freeze SHA就停止，不以“前一包代码已写完”代替Gate。不得提前创建实现分支或跨包并行编码。

每包Task按计划Red→最小实现→Green→小步commit；Gate再运行完整suite及必要AVD验证、同步checkpoint、Push核对local/remote和clean。最终验证包没有真实回归就不制造Red，也不增加功能。代码独立验收不能由本地自检或子代理review替代用户的独立验收。

## DESIGN SPEC逐章覆盖索引

所有36章都有具体落点；跨包项分别验证数据、计算或展示职责，不能据跨包映射提前实施。

| 设计章节 | 计划/Task落点 | Gate验证焦点 |
| --- | --- | --- |
| §0 来源、基线与权限 | 本索引基线/Gate；3D-4 Task5 | 精确父SHA、分支与授权、当前仅文档 |
| §1 目标 | 3D-1 Task1/3、3D-2 Task1/3、3D-3 Task2/3、3D-4 Task3 | 安全结束→可信派生→简单回看 |
| §2 结束体验与页码 | 3D-1 Task1/5、3D-4 Task2 | flush后确认、continue、唯一ClockSample、currentPage不+1、不倒退 |
| §3 v4 Closeout | 3D-1 Task1/2/6 | ACTIVE→PENDING→COMPLETED，无cancel/reopen，预留字段复用 |
| §4 两阶段协议 | 3D-1 Task1/3、3D-4 Task2 | A精确关段/零段删除；B原boundary进度/summary/FTS事务 |
| §5 页码并发 | 3D-1 Task2、3D-4 Task2 | Room内guard、两种先后顺序、旧页Note不改进度 |
| §6 Room与Android分离 | 3D-1 Task3、3D-4 Task2 | A/B连续，无平台API进tx/mutex，cleanup独立 |
| §7 A成功B失败 | 3D-1 Task1/3/5、3D-4 Task2 | 固定snapshot、逻辑已结束、锁外cleanup、只能retry |
| §8 冷启动 | 3D-1 Task4、3D-4 Task2 | PENDING先complete NORMAL；普通遗留ABNORMAL，无FGS复活 |
| §9 草稿门槛 | 3D-1 Task5、3D-4 Task3 | await in-flight save/page，失败仍ACTIVE，confirm后不新flush |
| §10 完整可信 | 3D-2 Task1 | 复用policy；FULL不单独证明、exact/closed/no gap/overlap/loss |
| §11 有效专注 | 3D-2 Task1、3D-3 Task2/3 | Focus+Deep不加权，可信0与null分开 |
| §12 有效速度 | 3D-2 Task3/4、3D-3 Task5 | 加权总页/总effective，零页分母保留，无除零/非有限数 |
| §13 有效窗口 | 3D-2 Task3 | 7/14/30，≥3场+30min有效，本地日/未来边界 |
| §14 Phase2保留 | 3D-2 Task4、3D-3 Task5 | old公式固定fixtures、仅window不足fallback、日期仍calendar pace |
| §15 派生不持久化 | 3D-2 Task2/3、3D-3 Task1/2、3D-4 Task1 | 无analytics表/字段，generatedSummary保留搜索但非结果主源 |
| §16 非N+1 | 3D-2 Task2、3D-3 Task1 | 批量IN分片预算实测，单record固定5源、无逐段label查询 |
| §17 结果页 | 3D-3 Task2/3/6 | 真实页/总时长/有效/Note/可信次数，默认collapsed |
| §18 不完整结果 | 3D-3 Task2/3/6 | PARTIAL/NONE/结构异常中文区别，局部不是整场计数 |
| §19 人类时间线 | 3D-3 Task2/3 | 标签映射、仅相邻连续阅读合并、原事实不改 |
| §20 历史label隐私 | 3D-3 Task1/2/6 | snapshot优先，缺失“风险 App”，不显示package、不读取App内容 |
| §21 次数 | 3D-2 Task1、3D-3 Task2 | 原Segment数不是按钮/通知/FocusEvent数 |
| §22 统一记录 | 3D-3 Task3/4/6、3D-4 Task3 | 三入口同projection/VM/content，不另建历史统计系统 |
| §23 书籍节奏 | 3D-3 Task5 | effective优先、成立但speed0不fallback、自然日期旧算法 |
| §24 Mine/视觉 | 3D-3 Global Constraints/Task6、3D-4 Task3 | Start/Mine边界、Mirra Blue、平面文本、无评分/卡片墙 |
| §25 并发/迟到 | 3D-1 Task2/3/5、3D-4 Task2 | final settlement、候选不补证、PENDING所有事实守卫、双击幂等 |
| §26 系统清理/DND | 3D-1 Task2/3/4、3D-3 Task3、3D-4 Task2 | 锁内runtime失效、锁外channels/monitor/DND、stale与late apply防护 |
| §27 替换旧设计 | 3D-1 Task1/3/5 | 旧finish公开绕过出口退出、无ABORTED用户流程、先flush非先系统清理 |
| §28 旧历史兼容 | 3D-2 Task4、3D-3 Task2/5、3D-4 Task4 | 不补旧effective，天然可信3C可用、Phase2旧数据保留 |
| §29 架构与事实 | 3D-1事实分析/Task1–5、3D-2 Task1–3、3D-3 Task1–5 | 复用现有controller/manager/Repositories，不造第二套系统 |
| §30 分包与停止 | 本索引Gate；各Plan最终Task | 独立分支、验收freeze，再授权下一包 |
| §31 必须场景 | 四Plan Review Focus及Gate；3D-4 Task1–4 | 事务/可信/UI矩阵分别有具体test/scenario ID |
| §32 Scope Guard | 四Plan Global Constraints；3D-4 Task5 | 无AI/cloud/backup/硬锁/新权限/主题扩展、冻结3C不改 |
| §33 Schema Guard | 3D-1 Task6、3D-2 Task4、3D-3 Task6、3D-4 Task1/5 | version4、schemas1–4/hash一致、无Migration |
| §34 授权状态 | 本索引基线/Gate与文档落库验收 | 设计落库已完成，本轮只新增计划，不冒充当前功能 |
| §35 自检 | 本索引文档验收；四Plan最终Task | 边界/guard/算法/范围/secrets/Git证据检查 |

## 包间接口交接导航

| 事实/接口 | 所属包 | 下游消费 |
| --- | --- | --- |
| CloseoutSnapshot、begin/complete、state Flow、snapshot读取 | 3D-1 Task1 | 3D-1 UI重建与retry；3D-2只读已完成事实 |
| ACTIVE学习写guard、DND late apply/post-start资格 | 3D-1 Task2 | 原3C继续读/动作；PENDING后不得再写 |
| final sample串行A/B、纯内存失效hook、锁外cleanup、pending startup | 3D-1 Task3/4 | 3D-4 crash/failure/stale矩阵 |
| SessionTimelineValidator / TimelineTrust | 3D-2 Task1 | 3D-2窗口、3D-3统一record；不复制算法 |
| EffectiveReadingSource / Service / Estimate | 3D-2 Task2/3 | 3D-3 Task5书籍详情；不用于推自然日期 |
| ReadingRecordSource / Service / Projection / Content | 3D-3 Task1–4 | Summary、History、Search；同事实同判断 |
| 最终矩阵/证据/Debug APK | 3D-4 Task1–5 | 用户独立review后才正式freeze |

本表不重复接口定义；精确签名、路径、断言、命令和commit以对应Plan Task为准。

## 本轮计划落库验收（非功能验收）

1. HEAD父基线为设计SHA，仅五个计划文档，四Plan均有Goal/Architecture/Tech Stack/Spec/Global Constraints/Review Focus。
2. 各Task有文件/API/test断言/验证命令/commit；关键失败场景映射完整，没有待定占位，没有实施代码。
3. PENDING是逻辑结束；所有学习写guard、final ClockSample settlement、A成功B失败cleanup/retry、startup顺序和snapshot重建明确。
4. 查询分片避免每Session逐条SELECT；可信0/不可用分离，三入口同projection，Phase2公式不改。
5. 生产/测试/Manifest/Gradle/Entity/Schema/Migration差异为空；1–4 hash一致。不执行或声称执行未来JVM/AVD功能测试。
6. `git diff --check`、范围/相对链接/秘密模式自检，提交 `docs(focus): add phase 3d implementation plans`，Push `codex/phase-3d-plans`，local==remote、clean。
7. 本轮报告输出 `[IMPLEMENTATION_PLAN_COMPLETE]`、`[TASK_COMPLETE]` 后停止；不得开始3D-1。

NOT RUN边界继承：API23–36 full matrix、OEM、完整实体设备、TalkBack、release。规划和API37既有结果均不能提升这些状态。
