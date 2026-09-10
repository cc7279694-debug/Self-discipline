# 观已Mirra

一款面向个人阅读与学习场景的 Android Local-first 应用，重点解决“想学习却迟迟无法开始”和“开始后容易被手机重新拉走”两个问题。

产品通过 Intent、行为启动脚本、Session、专注干预、恢复机制和轻量阅读记录，形成“产生学习意图—真正开始—维持专注—分心后回来—记录进度—下次继续”的完整闭环。

## 项目文档

- [PROJECT.md](PROJECT.md)：稳定的项目身份、目标与边界
- [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md)：V1 最终产品架构与开发规范
- [docs/CURRENT_STATE.md](docs/CURRENT_STATE.md)：当前阶段、风险与下一步
- [docs/DECISIONS.md](docs/DECISIONS.md)：已经确认的关键产品与技术决策
- [docs/plans/PHASE_0_1_IMPLEMENTATION.md](docs/plans/PHASE_0_1_IMPLEMENTATION.md)：Phase 0 + Phase 1 实施方案
- [AGENTS.md](AGENTS.md)：Codex 与工程协作规则

## 本地构建

要求 JDK 17 与 Android SDK API 37。项目通过 Gradle Wrapper 固定 Gradle 版本，不需要单独安装系统 Gradle。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

设备测试需要先连接 Android 真机或启动 API 37 模拟器。机器专属的 SDK 路径保存在被 Git 忽略的 `local.properties` 中。
