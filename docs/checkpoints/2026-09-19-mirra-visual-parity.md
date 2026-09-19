# Mirra Visual Parity Pass｜2026-09-19

## Goal

在已验收的 Module 3A 基线 `35064ee1cb5fb69a9499db2e847dfb346b4331ef` 上，使真实 Android Start 页面在构图、信息层级与质感上接近用户批准的概念图，而不是仅沿用配色原则。

## Verified completed

- Start Mainline 采用品牌区、行动标题、双栏 Focus Card、书名确定性灰阶占位封面、页码与百分比、连续进度条、First Action、Play 主 CTA、最近正常阅读信息条。
- 最近阅读条只使用既有正常 Session 的日期、总时长和 `max(0, endPage - startPage)`；没有真实数据时不显示。百分比按最接近整数展示，阅读页码仍直接使用 `currentPage`，不加 1。
- EmptyLibrary 仍只有品牌、“开始第一次学习”和“添加第一本书”；Knowledge / Mine 内容未重做。
- Bottom Navigation 保持原三栏与点击行为，降低高度和图标重量，并为系统手势区留出安全间距。
- 四张 API 37 模拟器实机渲染截图和 Mainline 并排图保存在 `assets/mirra-visual-parity/`。并排图左侧为用户提供的批准概念图，右侧为带测试夹具数据的真实 Compose 页面；数据夹具不是用户生产数据。

## Inherited decisions and boundaries

- Mirra Blue 仍为默认主题；`#386FBE` 是主 CTA 基色，`#6598E8` 是进度和选中色。没有新增真实书封面字段、网络素材或图片文件。
- Start 六级状态、Intent/Session 流程、Phase 2 Analytics 和 Module 3A SessionSegment/Coverage/FocusRepository/状态机均未改变。
- Room Schema 仍为 v4；没有新增 Entity、Migration 或 Schema JSON 变化。
- 未来 Module 3B 必须在 Session 起点建立真实监测握手，只有无缺口的起点覆盖才可初始化 FULL；已发生的 UNMONITORED 不得追认。

## Verification

- JVM：`testDebugUnitTest`，61 项通过。
- API 37 connected Instrumented / Compose / Room / Migration：112 项通过；首次全量运行中一条 Phase 1 测试仍断言旧 UI 整句，改为断言新版拆分页码文案后，第二次全量通过。
- `lintDebug`、`assembleDebug`：通过。
- Debug APK 在 API 37 模拟器两次 `install -r` 成功；Wi-Fi 与移动数据关闭后强停并冷启动，`MainActivity` 正常恢复，UI 层次中可见空库 Start。
- `MirraDatabase.version = 4`，导出 Schema 与 Migration 均无差异。

## Known limitations

- 视觉对比只覆盖一台 API 37、1080×2400 模拟器；尚未做实体设备、平板、超大字体和 TalkBack 专项验收。
- 占位封面不是实际书封面；最近阅读条目前是只读事实展示，不提供新导航。
- 本轮不实施 3B Android 系统监测能力，也不生成 effective focus 指标。

## Next step

请用户验收视觉截图；若通过，再单独规划和授权 Module 3B。
