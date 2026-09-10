# 观已Mirra｜V1 最终产品架构与 Codex 开发规范

## 0. 产品定位

这不是一个普通计时器，也不是一个 Notion / Obsidian 替代品。

V1 要解决两个真实问题：

1. **我明明想读书，但很难从刷手机、躺着等状态切换到真正开始。**
2. **开始读书以后，手机容易重新把我的注意力拉走。**

同时提供足够轻量的读书记录能力，让学习过程形成连续性。

核心闭环：

**想学习 → 真正开始 → 保持专注 → 分心后回来 → 记录阅读进度与笔记 → 结束 → 下次继续**

产品第一原则：

> “开始”不是按下计时器，而是完成真实的第一个学习动作。

V1 首要指标不是“今天专注了多少小时”，而是：

> **我想学习之后，能不能更快真正开始。**

---

# 1. V1 功能边界

## V1 必须实现

### 1.1 开始系统

支持完整基本流程：

`Intent → 启动准备 → Session → 稳定开始 → 专注 → 结束`

用户点击：

**我想开始**

时创建 Intent，而不是立刻创建 Session。

Intent 记录“我产生了学习意图”，后续才能分析：

- 有多少 Intent 最终真正开始
- 从 Intent 到实际开始用了多久

同一时间全局只能有一个 Intent。

默认 Intent 超时：

**30 分钟**

超时后记录为“未转化 Intent”。

---

### 1.2 Learning Item

V1 的 Learning Item 主要就是：

> 一本正在读的书 / 一个明确学习内容。

状态：

- 主线
- 进行中
- 暂停
- 已完成

同一时间只有一个主线 Learning Item。

非主线内容可以正常学习，但不会自动替换主线。

暂停内容默认不出现在主要开始入口。

---

### 1.3 阅读进度

书籍支持：

- 当前页
- 总页数
- 百分比
- 每次 Session 起始页
- 每次 Session 结束页

修改笔记页码时：

默认可以同步更新阅读进度。

如果只是翻回旧页记录内容：

**仅更新笔记页码，不让整体阅读进度倒退。**

长期记录：

- 每次读了多少页
- 有效阅读速度
- 整体推进速度
- 阅读速度趋势

公式：

**有效阅读速度 = 阅读页数 ÷ 有效专注时间**

**整体推进速度 = 阅读页数 ÷ Session 总时间**

速度异常只有连续多次出现时才轻提醒，单次变慢不判断。

---

### 1.4 阅读完成预测

如果数据足够：

显示：

- 剩余有效阅读时间
- 预计完成日期范围
- 预测可信度
- 预测依据

例如：

> 剩余约 6.5 小时有效阅读  
> 预计 9 月 22～27 日完成  
> 可信度：中等  
> 根据最近 14 天 6 次阅读记录估算

时间窗口动态选择：

优先最近 7 天。

样本不足时扩大到 14 天、30 天。

如果预测可信度太低：

**不显示预计完成日期，只显示剩余有效阅读时间。**

不支持“目标完成日期”。

不催用户追进度。

---

# 2. 笔记系统

## 2.1 核心理念

笔记不是复杂文档。

不是：

- Notion 页面
- Obsidian Markdown 知识库
- Block 数据库
- 项目管理系统

而是：

> **一条一条独立、完整的读书记录。**

一次 Session 中可以连续记录很多条 Note。

例如：

> 摘录 · p126  
> ……

> 我的理解 · p126  
> ……

> 问题 · p129  
> ……

每一条都是独立 Note。

不需要标题。

列表直接显示正文开头。

---

## 2.2 Note 类型

Note 有四种语义类型：

1. 摘录
2. 总结
3. 我的理解
4. 问题

系统自动判断类型。

用户可以随时修改。

界面上只做弱显示，例如淡标签或小图标。

不同类型可以有轻微视觉差异，但不能做成彩色卡片墙。

### 摘录特殊规则

不能单纯因为一句话“像原文”，就自动把它当成可靠摘录。

摘录最好同时记录页码来源。

---

## 2.3 Note 编辑体验

核心原则：

> 打开就记，记完就走。

V1：

- 自动保存
- 没有“保存”按钮
- 不做 App 内 Undo / Redo
- 不做版本历史
- 不做 AI 润色
- 不做扩写
- 不做改写
- 不做复杂写作辅助

用户通常一次就把内容写完整，因此不要围绕反复打磨文本设计编辑器。

---

## 2.4 Note 与页码

创建 Note 时可以快速记录：

> 当前第 126 页

同一个 Session 中，新 Note 默认继承最近一次页码。

下一次继续读同一本书，也默认带出上次阅读位置。

---

## 2.5 图片

Note 支持：

- 相机拍照
- 相册选择
- Caption
- Caption 参与搜索
- 自动适度压缩
- 点击全屏查看
- 双指缩放
- 图片列表

不支持：

- OCR
- PDF
- 文件附件
- 裁剪
- 旋转编辑
- 涂鸦
- 图片标注

“查看全部图片”页面显示：

- 缩略图
- Caption
- 弱化页码 / Session 来源

图片 Block 真正删除后：

如果没有其他引用，立即删除本地图片文件。

Note 整体删除时：

未被其他数据引用的图片同时清理。

---

## 2.6 Note 列表

默认：

**按创建时间倒序。**

每条显示：

- 正文预览
- 所属 Learning Item
- 创建日期
- 一行内容预览

支持：

- 默认全部 Note
- 切换按 Learning Item 分组
- 按摘录 / 总结 / 我的理解 / 问题做轻量筛选

不做：

- 收藏
- 置顶
- Tag
- 回收站
- Note 标题

Note 删除：

直接删除，但需要二次确认。

---

# 3. Topic｜V1 只做轻量版

Topic 在 V1 不是完整知识系统。

只解决：

> “这条笔记大概属于哪个知识概念？”

Note 可以关联一个或多个 Topic。

系统可以建议：

> 可能相关：锚定效应

用户确认后关联。

如果没有现有 Topic：

可以轻提示：

> 可能是一个新 Topic：心理账户  
> 创建 / 忽略

系统不能自动大量创建 Topic。

创建 Topic 时：

只需要：

- Topic 名称
- 自动关联当前 Note

不要求立即写“我的理解”。

---

## V1 不实现的 Topic 功能

以下全部延期：

- Topic 知识图谱
- 父子 Topic
- Topic Relation
- Topic 合并 / 拆分
- Topic Alias
- 知识成熟度
- 记忆稳定度
- 观点冲突
- 来源可靠性
- 证据相关性
- Topic 理解版本
- 知识观点系统

这些可以作为未来独立模块设计。

---

# 4. Session 模型

同一时间：

> **只能存在一个 Active Session。**

Session 中不能直接切换 Learning Item。

想学习其他内容：

必须先结束当前 Session。

---

## 4.1 Session 基本流程

正常流程：

`Intent Created`

↓

`Transition`

↓

`Start Script`

↓

`Started`

↓

`Stable Start`

↓

`Focus`

↓

`Finish`

---

## 4.2 Started 与 Stable Start

不能因为用户点了按钮就认为“开始成功”。

两个阶段：

### Started

系统判断用户已经完成真正的第一学习动作。

### Stable Start

开始后保持一段稳定时间，没有立刻重新被手机拉走。

这两个数据分开统计：

- 首次启动稳定率
- 最终启动稳定率
- 启动救援次数
- 从 Intent 到真正开始时间

---

# 5. 启动系统

用户最主要的启动障碍：

> 不是任务不清楚，而是难以从当前状态切换到学习状态。

因此启动脚本应该围绕：

**行为状态切换**

而不是：

- 鸡汤
- 倒计时
- “你可以的”
- 复杂任务拆解

---

## 5.1 Start Script

支持：

### Standard

普通启动脚本。

### Enhanced

高启动风险时使用的增强启动脚本。

V1 允许底层：

`StartScript → ScriptStep[]`

Step 类型可支持：

- 普通确认
- 延迟确认
- 熄屏动作
- 手机静置检测
- 提示步骤
- First Action

具体脚本内容后续可以配置。

---

## 5.2 First Action

每个 Learning Item 有一个非常具体的第一动作。

例如：

> 拿起《怪诞行为学》，翻到第 146 页。

系统可以生成默认值。

用户可以修改。

这才是“开始”的核心。

---

# 6. Start Readiness

V1 使用规则系统，不使用 AI。

可以记录：

- 精力
- 疲劳
- 情绪
- 紧张 / 烦躁
- 困倦
- 环境
- 启动前手机使用情况

环境：

- 家
- 公司
- 图书馆
- 咖啡店
- 户外
- 其他

用户状态不要求每次机械填写。

优先：

- 继承近期状态
- 快速修正
- 可跳过

系统可以根据状态判断：

> 当前状态：高启动风险

用户接受直接表达“高风险”，不需要软化措辞。

---

# 7. 专注状态

Session 需要区分：

- Focus
- Break
- Temporary Allowance
- Distraction
- Recovery

后续可以推断：

- 浅层专注
- 深度专注

深度专注采用多信号规则推断。

例如：

- 持续稳定时间
- 手机静置
- 屏幕关闭
- 没有风险 App
- 解锁频率低
- 没有休息 / 分心

UI 只显示：

> 深度专注

不要展示：

> 深度专注概率 83%

这种虚假精确数字。

---

# 8. 手机分心干预

V1 采用：

> **标准版专注干预**

不是完全锁死手机。

---

## 8.1 系统能力

V1 计划使用：

- Android Usage Access
- Do Not Disturb / Notification Policy Access
- Overlay 能力
- Notification fallback

不依赖服务器。

---

## 8.2 InterventionEngine

业务逻辑和 Android 能力必须分开。

建议抽象：

`InterventionEngine`

业务层决定：

- 是否提醒
- 摩擦等级
- 等待时间
- 是否临时允许

Android 层决定：

- 能否显示 Overlay
- 是否降级通知
- 当前设备能力

这样不同手机厂商的限制不会污染核心 Session 逻辑。

---

## 8.3 解锁

解锁手机本身：

不是立即判定分心。

第一次解锁：

显示当前任务和已专注时间。

短时间频繁解锁：

提高提醒强度。

必要时询问：

> 你现在为什么要使用手机？

原因例如：

- 回复消息
- 查资料
- 看时间
- 临时处理事情
- 就想看看

---

## 8.4 高风险 App

用户可以配置风险 App。

同一 Session 中反复打开时逐步增加摩擦。

示例默认：

第一次：

> 当前正在阅读《怪诞行为学》

第二次：

> 选择原因 + 短等待

第三次：

> 更强确认 + 更长等待

不是永久封锁。

---

## 8.5 Temporary Allowance

合法使用手机时：

可以暂时允许 App。

例如：

回复消息：

约 3 分钟。

查资料：

约 5 分钟。

临时事务：

约 5 分钟。

允许结束后：

重新恢复保护。

可以手动延长一次，但需要额外确认。

---

# 9. DND 与通知

开始 Session 后：

优先使用 Android 系统勿扰模式。

不自己重新发明完整通知规则系统。

学习期间：

**默认隐藏 / 静音微信等普通通知。**

不做“重要微信联系人白名单”。

来电规则交给 Android 系统 DND 设置。

Session 结束时：

不要立即恢复通知。

顺序：

`Session Finish`

↓

`Closeout`

↓

`Closeout Complete`

↓

`恢复 Session 前系统状态`

必须保存 Session 开始前的 DND 状态，结束后恢复原值，而不是强制改成固定状态。

---

# 10. Break 与 Recovery

用户可以主动休息。

不强制 Pomodoro。

长期专注后系统可以建议休息。

优先推荐：

- 喝水
- 走动
- 拉伸
- 闭眼
- 看远处

也允许娱乐休息，但要明确时间。

---

## 分心

总 Session 时间继续走。

同时单独记录：

> Distraction Time

长时间分心超过阈值：

可以自动结束 Session。

---

## Recovery

退出干扰 App 不代表恢复成功。

必须重新稳定一段时间才算：

> Successful Recovery

长期分析：

- 恢复成功率
- 平均恢复时间
- 每次 Session 恢复次数

---

# 11. Session 结束

结束时不要马上显示巨大数据面板。

默认摘要保持简洁：

例如：

> 本次阅读：126 → 145 页  
> 有效专注：45 分钟  
> 新增笔记：3 条

下面：

**查看详情**

详情可以展示：

- Session 总时间
- 有效专注时间
- 分心次数
- 分心时间
- Recovery
- 解锁次数
- 深度专注
- 阅读速度
- 启动耗时
- Note 数量

---

# 12. Session 自动总结

底层预留：

`SummaryEngine`

V1：

不用 AI。

规则生成简短总结。

例如：

> 本次阅读 126–145 页，共记录 3 条笔记。

未来可以替换：

- 本地 AI
- 其他 AI 引擎

但数据层不能依赖某个模型。

下一次继续同一本书时，只显示一行：

> 上次总结：本次阅读 126–145 页，共记录 3 条笔记。

不塞更多内容。

---

# 13. Session 异常结束

以下情况：

- App 被强制杀死
- 手机重启
- 系统终止进程
- Session 状态无法正常闭合

下次打开 App 时：

检测 stale Active Session。

将其标记：

> Abnormal End

异常 Session：

可以保留历史记录。

但：

**完全排除在能力趋势和核心统计之外。**

不因为系统问题污染个人行为数据。

完成 Session 后：

历史核心数据不可编辑。

---

# 14. 核心统计

不做一个虚假的综合 0–100 分。

V1 优先显示原始指标和趋势。

三个长期方向：

### Start

- Intent 转化率
- 启动成功率
- Stable Start 成功率
- Intent → Action 时间

### Maintain

- 有效专注时间
- 分心次数
- 分心时间
- 深度专注

### Recover

- Recovery 成功率
- 平均恢复时间

默认：

**最近 7 天 vs 前 7 天**

详情可以：

- 30 天
- 90 天
- 全部

---

# 15. 导航

底部固定三个一级入口：

**开始｜知识｜我的**

---

## 开始

不是传统 Dashboard。

它是：

> 状态感知的行动首页。

普通状态：

突出主线 Learning Item。

如果有 Active Intent：

优先继续 Intent。

如果有 Active Session：

直接进入当前 Session 页面。

如果启动风险高：

突出：

> 当前状态：高启动风险  
> 先切换状态，再开始

不要在“开始”首页放全局“+”。

---

## 知识

V1 简化为学习资料工作台。

主要展示：

- Learning Item
- 最近 Note
- Topic
- 搜索

只有「知识」页面有全局：

`+`

可创建：

- Learning Item
- Note
- Topic

Review 入口可以为未来预留信息架构位置，但 V1 不实现 Review 数据和 UI。

---

## 我的

主要回答：

> 最近我的学习状态怎么样？

首屏：

- 最近 7 天启动趋势
- 专注趋势
- 恢复趋势
- 当前基础状态
- 简单行为模式

下面：

- Session 历史
- 设置
- 权限
- 风险 App
- 备份
- 导入 / 导出

---

# 16. Learning Item 详情

显示：

- 名称
- 当前状态
- 当前页 / 总页数
- 阅读百分比
- 最近一次学习时间
- 上次总结
- 最近 Note
- 阅读趋势
- 阅读速度
- 预计剩余阅读时间
- 预计完成范围（可信时）
- 开始阅读

主线 Learning Item：

“继续阅读”按钮最突出。

非主线：

按钮弱一级。

所有开始入口最终仍走：

`Intent → Start → Session`

不能绕开启动系统。

---

# 17. 技术架构

## Android

- Kotlin
- Jetpack Compose
- Navigation 3（Compose-first）
- Coroutines / Flow

## Local-first

V1：

- 无账号
- 无登录
- 无服务器
- 无云数据库
- 无后端 API

用户数据全部默认在本地。

---

## 本地存储

### Room / SQLite

存结构化业务数据。

### DataStore

存：

- App 设置
- 权限状态缓存
- 用户偏好
- 当前模式

### App-owned Files

存：

- 图片
- 完整备份需要的媒体文件

不额外做应用层数据库加密。

依赖 Android App Sandbox。

---

# 18. V1 数据模型

## LearningItem

建议核心字段：

- id
- name
- status
- totalPages
- currentPage
- isMainline
- firstAction
- createdAt
- updatedAt
- completedAt

---

## Intent

- id
- learningItemId
- createdAt
- transitionedAt
- convertedAt
- endedAt
- outcome

Outcome：

- converted
- abandoned
- timeout

---

## Session

- id
- learningItemId
- intentId
- startedAt
- stableStartedAt
- endedAt
- startPage
- endPage
- endType
- generatedSummary
- ruleSnapshot
- contextSnapshotId

EndType：

- normal
- early
- auto
- start_incomplete
- abnormal

---

## SessionSegment

表示 Session 内状态区间。

字段：

- id
- sessionId
- type
- startedAt
- endedAt
- metadata

Type：

- focus
- deep_focus
- break
- distraction
- recovery
- temporary_allowance

---

## Note

V1 Note 已经是独立记录，不再使用复杂 Block 模型。

字段：

- id
- learningItemId
- sessionId nullable
- semanticType
- content
- pageNumber nullable
- createdAt
- updatedAt

SemanticType：

- quote
- summary
- understanding
- question

Note 可以脱离 Session 独立创建。

---

## ImageAsset

- id
- noteId
- localPath
- caption
- createdAt
- width
- height
- fileSize

---

## Topic

V1 极简：

- id
- name
- createdAt

使用 NoteTopicCrossRef：

- noteId
- topicId

不要在 V1 添加 Topic 复杂字段。

---

## SourceLocator

V1 只做书籍页码定位。

可以保持独立轻量对象，方便未来扩展：

- id
- noteId
- pageNumber

未来再扩展章节、论文、视频时间戳等。

---

## AppRule / FocusRule

记录：

- 风险 App
- 干预强度
- 临时允许参数
- Session 启动规则

Session 启动时必须保存 Rule Snapshot。

Session 中不能修改核心规则。

---

## UserStateSnapshot

- id
- energy
- fatigue
- mood
- agitation
- sleepiness
- environment
- capturedAt

允许字段 nullable。

用户可以跳过状态填写。

---

# 19. 搜索

V1 使用本地全文搜索。

优先使用 Room FTS。

搜索：

- Learning Item 名称
- Note 正文
- 图片 Caption
- Topic 名称
- Session 总结

搜索结果保持简单。

命中 Note：

直接打开对应 Note。

不要设计复杂“知识上下文恢复系统”。

V1 不做：

- AI 语义搜索
- 自然语言数据分析查询
- 向量数据库

未来可以通过独立 SearchEngine 扩展。

---

# 20. 数据备份与导出

Local-first App 的完整备份属于核心功能。

V1 包含四层：

### Android 系统备份

用于基础 App 数据恢复。

### 手动完整备份

这是最可靠的完整迁移方案。

必须包含：

- Room 数据
- DataStore 设置
- 图片
- FocusRule
- Learning Item
- Intent
- Session
- Note
- Topic

恢复后尽量恢复完整 App 状态。

### JSON

结构化导出业务数据。

方便：

- 未来迁移
- 用户自己处理
- 其他软件读取

### CSV

重点导出分析数据：

- Session
- 阅读进度
- 阅读速度
- Intent
- 专注指标

不要把图片塞进 CSV。

完整备份才包含图片。

---

# 21. V1 明确不做

这是 Codex 必须遵守的 Scope Guard。

不要因为“未来可能需要”提前实现以下内容：

- 登录
- 云同步
- Supabase
- Server
- Web App
- PWA
- iOS
- FSRS
- ReviewCard
- Deck
- Smart Deck
- AI
- OCR
- PDF 阅读器
- 文件附件
- 完整 Topic 知识系统
- 知识图谱
- Tag
- 收藏
- Note 置顶
- Note 标题
- Note 版本历史
- 重型 Block Editor
- BlockReference
- 问题管理系统
- TODO 管理
- 专题研究 Learning Item
- 复杂项目管理
- 游戏化金币 / 商店
- 统一 0–100 注意力评分
- 强制 Pomodoro
- 无障碍服务式强锁手机
- 复杂图片编辑器

任何未写入 V1 的能力：

**不得主动添加。**

---

# 22. 代码架构原则

优先：

**简单、稳定、可维护、可扩展。**

不是：

> 为了展示架构能力提前设计几十层抽象。

但以下边界建议预留：

- StartEngine
- ReadinessEngine
- InterventionEngine
- SessionManager
- SummaryEngine
- SearchEngine
- BackupService

这些应该是接口 / service boundary。

不要提前实现未来功能。

---

# 23. Codex 分阶段开发计划

## Phase 0｜工程基础

目标：

建立稳定 Android 工程。

完成：

- Kotlin
- Compose
- Room
- DataStore
- Navigation
- Repository 层
- 基础测试框架
- 数据库 migration 基础
- 三栏导航

验收：

App 可以稳定安装、启动、关闭、重新打开。

---

# Phase 1｜最小学习闭环

这是第一阶段真正的交付目标。

必须完整跑通：

> 创建书籍  
> ↓  
> 我想开始  
> ↓  
> Intent  
> ↓  
> 启动准备  
> ↓  
> 开始 Session  
> ↓  
> 阅读计时  
> ↓  
> 更新页码  
> ↓  
> 连续记录独立 Note  
> ↓  
> 结束 Session  
> ↓  
> 保存阅读进度  
> ↓  
> 下次继续阅读

这一阶段暂时不接：

- DND
- Usage Access
- Overlay
- 高风险 App

### Phase 1 验收标准

1. 可以创建 Learning Item。
2. 可以设主线。
3. 首页可以发起 Intent。
4. Intent 能进入启动流程。
5. Session 正常开始和结束。
6. Session 只能有一个。
7. 能记录起始页和结束页。
8. 当前阅读进度正确保存。
9. Session 中能连续快速新增多条 Note。
10. Note 自动保存。
11. Note 支持四种语义类型。
12. Note 可记录页码。
13. 重新打开 App 数据不丢。
14. 下一次打开同一本书能继续上次位置。
15. Session 结束可以生成规则式简短总结。

Phase 1 边界补充：

- `stableStartedAt` 字段保留为 nullable，但本阶段不实现 Stable Start 自动判定或相关统计。
- 本阶段不引入基于固定时长的 Active Session 自动异常结束规则。
- 本阶段结束摘要使用 Session 总时间，不虚构尚未接入分心识别后的“有效专注时间”。
- 导航使用稳定版 Navigation 3。

只有这 15 项稳定通过，才进入下一阶段。

---

# Phase 2｜笔记与阅读体验完善

加入：

- 图片
- Caption
- 图片压缩
- 全屏预览
- 图片列表
- Topic 轻量建议 / 关联
- 搜索
- Note 语义筛选
- Learning Item 阅读趋势
- 有效阅读速度
- 整体推进速度
- 剩余阅读时间
- 完成时间预测

验收重点：

> 真实读一本书时，整个流程是否比“手机备忘录 + 计时器”更顺手。

---

# Phase 3｜Android 专注干预

加入：

- Usage Access
- DND
- Risk App
- Unlock tracking
- InterventionEngine
- Overlay
- Notification fallback
- Temporary Allowance
- Progressive friction
- Break
- Distraction
- Recovery

这个阶段重点解决：

> 学习已经开始，但手机重新把人拉走。

---

# Phase 4｜个人趋势与数据安全

加入：

- Start / Maintain / Recover 趋势
- 7 天 vs 前 7 天
- Session 历史
- 阅读速度异常
- 状态相关性
- Android system backup
- 完整备份 / 恢复
- JSON export
- CSV export
- 数据恢复测试
- 异常 Session 恢复
- 稳定性与性能优化

完成 Phase 4 后：

V1 才算真正完成。

---

# 24. Future Modules｜现在不要实现

## V2 Knowledge

以后再开发：

- Topic 我的理解
- Topic Relationship
- Topic hierarchy
- Topic graph
- Source
- SourceVersion
- 证据系统
- 观点冲突
- Topic 成熟度

## V3 Review

以后开发：

- ReviewCard
- FSRS
- Review Session
- Deck
- Smart Deck
- 卡片版本
- CardQualityEngine

当前 V1 代码应该允许未来添加这些模块，但不能为了它们提前建空表。

---

# 25. Codex 工作原则

每个阶段开始前：

1. 阅读本规范。
2. 只实现当前 Phase。
3. 先写实现计划。
4. 再写代码。
5. 每完成一项运行测试。
6. 不主动扩 Scope。
7. 不因为未来扩展制造当前复杂度。
8. 数据库修改必须带 migration。
9. Android 权限能力必须有降级方案。
10. 完成阶段后输出验收报告。

如果需求和“未来扩展性”冲突：

优先保证：

> **当前真实使用体验简单可靠。**

如果一种能力现在没有真实使用需求：

> 不实现。

---

# 26. 产品最终判断标准

这个 App 的成功不是：

> 功能很多。

而是：

当我原本躺着刷手机、不想动时：

> 我打开 App → 点“我想开始” → 它帮助我切换状态 → 我真的拿起书开始读。

读书过程中：

> 手机没有轻易把我拉走。

读完以后：

> 我能快速记下完整想法，知道自己读到哪里。

下一次：

> 不需要重新回忆“上次学到哪了”，可以直接继续。

只要这个闭环稳定成立：

**V1 就成功了。**
