# Theme System v1｜Theme Foundation + Mirra Blue Checkpoint

日期：2026-09-13

## Goal

在不改变现有 IA、导航、Start 六级状态、业务行为或数据模型的前提下，建立 Mirra 自有主题基础与可复用组件，并把当前主要页面完整迁移到 Mirra Blue。

## Verified Completed

- 建立 Mirra Color、Shape、Depth 语义 Token，并完整映射 Material 3 ColorScheme，避免默认 Material Accent 泄漏。
- Mirra Blue 以灰白为主体；`#6598E8` 用于 Progress、Selected State、Toggle 和少量 Accent，白字主要 CTA 使用 `#386FBE`。
- 建立 Primary / Secondary Button、Text Action、Focus Card、Progress、Toggle、Bottom Navigation 与最小 Surface primitive。
- Start、Preparation、Session、Knowledge、Mine 和图片预览使用同一套 Mirra Blue 主题；Start 六级状态、CTA 回调、导航和数据逻辑未改变。
- Knowledge 普通内容保持平面；Mine 未新增分析界面；Soft Neumorphism 只保留在重点交互和焦点组件。
- 主题偏好接入既有 DataStore，未知值回退 Mirra Blue，写入主题不覆盖顶层导航偏好。
- Mono 与 Night 仅提供可读 Palette / Token 和架构支持，未开放主题选择，也未宣称完成全页面验收。
- Room Schema 保持 v3；没有 Entity、Table、Column、Index 或 Migration 变化。

## Changes

- 新增主题 ID、Palette、Color / Shape / Depth Token 与 CompositionLocal。
- 新增 `ui/components` 下的首批实际复用组件。
- 根 Activity 从 DataStore Flow 读取主题，主题变化可驱动同一 Composition 更新。
- 顶层 Bottom Navigation 改为 Mirra 组件；主要页面移除直接品牌色决策并复用语义 Token / 组件。
- 系统栏改为透明，并按当前主题明暗设置图标可读性。

## Inherited Decisions

- 一个 Screen、一套布局、多套 Token；主题不得复制 Screen 或引起 layout shift。
- Depth 只服务于交互与焦点，不作为普通内容装饰。
- Theme 使用 DataStore，不进入 Room；数据库仍是 Schema v3。
- 概念图中的书封面、头像、背景气泡和 slogan 不属于已确认功能。
- 不改变 `currentPage`、Active Intent / Session、First Action、搜索、Topic、图片文件补偿等冻结语义。

## Known Limitations

- Mono / Night 还没有完成全页面视觉、字号放大、TalkBack、切换与系统栏验收，因此本阶段没有可视化主题入口。
- 本轮人工视觉检查使用 API 37 模拟器的空库 Start / Knowledge / Mine；含丰富真实内容的多设备视觉矩阵仍属于后续视觉验收。
- TalkBack 与 2.0 font scale 人工检查未运行；已通过基础对比度测试和 48dp / 56dp 组件尺寸约束，但不能替代专项无障碍验收。

## Verification

- `testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug`：通过，Gradle `BUILD SUCCESSFUL`。
- API 37 模拟器：最新 Debug APK `install -r` 成功；关闭 Wi-Fi 与移动数据后冷启动成功，`LaunchState: COLD`。
- API 37 人工视觉检查：Start、Knowledge、Mine 均使用 Mirra Blue；无默认紫色、满屏 Card 或过重阴影；Start 主 CTA 层级最高，Knowledge / Mine 保持可读性。
- 静态检查：Screen 中未发现直接品牌 Hex；UI 颜色集中于 Palette / Theme / Mirra components。图片 JPEG 合成使用的白色不属于 UI Theme。
- Schema 检查：`MirraDatabase.version = 3`，`1.json` / `2.json` / `3.json` 无 diff，Migration 列表未变化。

## Next Step

等待本阶段正式验收。后续只能在单独授权后选择 Mono / Night 全面迁移与主题选择，或进入已规划但未实施的 Module 2D；不得自动继续。
