# 观已Mirra｜Phase 0 + Phase 1 实施方案

## Goal

以单 Android App Module 完成稳定工程基础，并在下一模块实现“创建学习内容 → 产生 Intent → 完成启动准备 → 阅读 Session → 连续笔记 → 结束并继续”的最小闭环。

## Architecture

采用 UI → ViewModel → Domain / Service → Repository → Room / DataStore 的单向数据流。按功能分包，使用手动 AppContainer，不引入多 Gradle Module 或 Hilt。

## Module 0｜工程基础

- Kotlin、Jetpack Compose、Material 3、Navigation 3。
- Room、Preferences DataStore、Coroutines / Flow。
- Version Catalog、Gradle Wrapper、Room Schema Export。
- 单 Activity 与“开始｜知识｜我的”三栏导航骨架。
- JVM、Instrumented 与 Compose UI 测试基础。
- 生成 Debug APK，并在模拟器或真机完成安装与冷启动验证。

## Module 1｜最小学习闭环

- Entity：LearningItem、Intent、Session、Note。
- DAO：LearningItemDao、IntentDao、SessionDao、NoteDao。
- Repository：LearningItemRepository、StudyWorkflowRepository、NoteRepository。
- Domain：SessionManager、IntentExpiryPolicy、SummaryEngine、NoteTypeSuggester。
- 页面：状态感知首页、知识页、Learning Item 创建与详情、启动准备、Session、结束摘要。
- 同时最多一个主线 Learning Item、一个 Active Intent、一个 Active Session。
- Intent 转 Session、Session 结束与阅读进度更新均使用数据库事务。
- `stableStartedAt` 保留 nullable，本阶段不自动判定 Stable Start。
- 不使用固定时长自动判定异常 Session。

## Verification Gates

### Module 0

- Gradle Sync 与 Debug Build 成功。
- JVM、Instrumented、Compose 测试任务可以运行。
- Debug APK 成功生成。
- 模拟器或真机成功安装并冷启动。

### Module 1

- 对照 PRODUCT_SPEC 的15项 Phase 1 验收标准逐项验证。
- 验证数据库重开、重复创建、非法状态和事务回滚。
- 验证进度不会因旧页笔记或较小结束页倒退。

## Scope Guard

本计划不实现 Phase 2、Phase 3、Phase 4、Future Modules、DND、Usage Access、Overlay、风险 App、图片、Topic、搜索、AI、OCR、云同步、登录、后端、Tag、收藏或重型编辑器。
