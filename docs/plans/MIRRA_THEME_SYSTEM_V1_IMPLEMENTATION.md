# 观已 Mirra｜Theme System v1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本计划通过用户验收前不得实施。

**Goal:** 在不改变页面结构、信息优先级、Start 六级状态或业务行为的前提下，为 Mirra 建立可持久化、可测试的 Compose 主题系统，并依次交付 Mirra Blue、Mono、Night 与可视化主题选择。

**Architecture:** 延续单 Android App Module 和 Material 3，在 `MaterialTheme` 外增加 Mirra Color、Shape、Depth 语义 Token。先用完整 Material `ColorScheme` 映射承接现有控件，再逐步迁移到少量 Mirra 组件；一个组件、一套布局、多套 Token，不复制 Screen。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Navigation 3、DataStore Preferences、Coroutines / Flow、现有手工 `AppContainer`；不增加第三方 UI、阴影、主题或 DI 依赖。

**Spec:** 本计划第 1 节，以及 `docs/PRODUCT_SPEC.md`、`docs/DECISIONS.md`、`docs/checkpoints/2026-09-12-start-experience-correction.md`。

## Global Constraints

- 主题只改变颜色、Surface、Depth、Shape 的细微表现和组件视觉状态；不得改变 IA、页面结构、Navigation、Start 六级状态、按钮位置、信息优先级或功能逻辑。
- 默认主题为 Mirra Blue；首轮可选主题只有 `BLUE`、`MONO`、`NIGHT`。
- Paper 只保留产品方向，不创建不可选择的枚举、空 Palette、隐藏入口或未来组件。
- 蓝色只用于主 CTA、进度、当前导航、当前选择、Toggle On 和少量关键图标；普通正文不得使用 Accent。
- Depth 只用于交互和焦点，不用于装饰；Note、Search Result、Topic、普通 Learning Item List、Settings 保持平面。
- 不增加书封面、头像、账户、装饰标语、背景气泡、渐变插画或其他数据字段；概念图不是功能规格。
- 不修改 Room Entity、Schema v3、Migration、Repository 业务语义或 App-owned image 生命周期。
- 不拆 Gradle Module，不引入新 DI Framework，不升级依赖，不扩大产品 Scope。

---

## 0. Task Contract

### Scope

- Theme Token、Material 3 映射、统一 Shape/Depth；
- Mirra Blue、Mono、Night；
- Primary/Secondary/Text Action、Focus Card、Progress、Toggle、Bottom Navigation、Surface、Chip；
- DataStore 持久化；
- `我的 → 外观` 可视化主题选择；
- 现有页面分批迁移；
- Preview、自动化测试和 API 37 人工验收。

### Out of Scope

- Paper 实现；Theme 与系统 Light/Dark 的组合矩阵；Dynamic Color；
- 自定义字体、Logo 重做、动画系统；
- 页面重构、新业务、新数据字段、封面、头像、登录、云同步；
- Module 2D 实现。

## 1. 参考图与产品视觉基线

图片只作为视觉参考，不是像素级设计稿：

- 图 1、2：只借鉴柔和凸起、胶囊控件和相向高光/阴影；不照搬满屏拟态、低对比度和高卡片密度。
- 图 3：验证 Mono 的黑白层级和单一强 CTA；书封面、头像、口号、小注释不是当前需求。
- 图 4：作为 Mirra Blue 主方向——浅冷灰、白灰 Focus Card、蓝色行动色、蓝色进度和选中导航；删除装饰气泡与冗余文字，并降低阴影强度。

> Mirra 使用安静、克制且具有轻微触感的视觉系统。主题改变的是氛围，而不是产品结构；颜色与层次用于引导行动，而不与内容争夺注意力。

固定页面规则：

- 一个页面只有一个最强 CTA；
- 先用留白、字号、字重和对齐建立层级；
- 普通集合优先 `ListRow + Divider`，不为每项创建 Raised Card；
- 不为“设计感”增加 slogan 或解释性小字；
- 选中、错误、成功不能只依赖颜色。

## 2. 当前实现事实

- `ui/theme/Theme.kt` 只有绿色 `#315C4C` 的浅色 `ColorScheme`，没有自定义 Color/Shape/Depth Token。
- `lightColorScheme()` 只填写部分角色，未指定角色可能泄漏库默认颜色。
- `MainActivity` 固定调用无参数 `MirraTheme`；没有主题偏好。
- `AppPreferencesRepository` 已用 DataStore 保存顶层导航，可以增加主题 key；Room 不参与。
- `MirraApp` 使用默认 `NavigationBar/NavigationBarItem`。
- Screen 大量使用 Material Button 与 `MaterialTheme.colorScheme`，适合先映射、后逐屏迁移。
- 图片转 JPEG 的 `Color.White` 是位图合成背景，不属于 UI Theme；图片全屏遮罩应改为媒体语义 Token。
- Profile 目前无设置导航或 ViewModel，适合增加轻量“外观”入口。

保留 Material 3 Typography、Navigation 3 back stack、现有 DataStore/AppContainer。首版不换字体、不改业务 ViewModel。

## 3. Theme ID 与持久化

```kotlin
enum class MirraThemeId(val storageValue: String) {
    BLUE("blue"), MONO("mono"), NIGHT("night");

    companion object {
        fun fromStorageValue(value: String?): MirraThemeId =
            entries.firstOrNull { it.storageValue == value } ?: BLUE
    }
}
```

`AppPreferencesRepository` 增加：

```kotlin
val themeId: Flow<MirraThemeId>
suspend fun setThemeId(themeId: MirraThemeId)
```

- 老用户缺 key、未知值或损坏字符串均回退 `BLUE`；不删除其他 preference。
- 点击主题立即写 DataStore 并更新 Composition，无保存按钮。
- 写入失败保留原主题并显示轻量错误，不谎称成功。
- V1 不读取系统 dark mode，不自动切换 Night。

## 4. Color Token 与 Material 映射

```kotlin
@Immutable
data class MirraColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfacePressed: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val divider: Color,
    val positive: Color,
    val onPositive: Color,
    val warning: Color,
    val onWarning: Color,
    val danger: Color,
    val onDanger: Color,
    val scrim: Color,
    val mediaBackdrop: Color,
    val onMediaBackdrop: Color,
)
```

`onAccent`/状态前景确保可读；`scrim/mediaBackdrop` 避免 Dialog 和图片页写死黑白。

```kotlin
object MirraTheme {
    val colors: MirraColors
        @Composable @ReadOnlyComposable get() = LocalMirraColors.current
    val shapes: MirraShapes
        @Composable @ReadOnlyComposable get() = LocalMirraShapes.current
    val depth: MirraDepth
        @Composable @ReadOnlyComposable get() = LocalMirraDepth.current
}
```

根 `MirraTheme(themeId)` 同时提供自定义 Token 和 MaterialTheme。Material role 必须全部显式映射自同一 Palette：primary→accentStrong、primaryContainer→accentSoft、background/surface→同名 Token、onSurfaceVariant→textSecondary、outline→textTertiary、outlineVariant→divider、error→danger、scrim→scrim；surfaceContainer 与 inverse roles 也必须明确，不保留默认紫色。

## 5. 三套 Palette

用户色值作为视觉 seed；生产值因对比度作三项修正：Blue action 蓝加深，Light 次级文字加深，Night 三级文字提亮。

### Mirra Blue（默认）

| Token | Value |
|---|---:|
| background / surface / raised | `#F2F4F7` / `#F7F8FA` / `#FAFBFC` |
| surfacePressed | `#E9EDF2` |
| textPrimary / secondary / tertiary | `#181A1D` / `#626873` / `#686E78` |
| accent / strong / soft | `#6598E8` / `#386FBE` / `#DCE9FA` |
| onAccent / divider | `#FFFFFF` / `#E1E5EA` |
| positive / onPositive | `#2E6F4E` / `#FFFFFF` |
| warning / onWarning | `#7A5500` / `#FFFFFF` |
| danger / onDanger | `#B3261E` / `#FFFFFF` |

`#6598E8` 配白字约 2.91:1，不能承载普通按钮文字；主 CTA 用 `#386FBE`（配白字约 5.02:1），柔和蓝用于进度/选择。

### Mono

| Token | Value |
|---|---:|
| background / surface / raised | `#F3F3F1` / `#F8F8F6` / `#FAFAF8` |
| surfacePressed | `#E7E7E4` |
| textPrimary / secondary / tertiary | `#171717` / `#626262` / `#6B6B6B` |
| accent / strong / soft | `#292929` / `#151515` / `#E4E4E2` |
| onAccent / divider | `#FFFFFF` / `#DEDEDB` |

状态仍有语义 Token，但同时使用图标/文案；导航、进度、选择保持灰阶。

### Night

| Token | Value |
|---|---:|
| background / surface / raised | `#121416` / `#191C20` / `#1E2226` |
| surfacePressed | `#242A30` |
| textPrimary / secondary / tertiary | `#F2F3F4` / `#A6ABB2` / `#80868F` |
| accent / strong / soft | `#729DE2` / `#84ACEC` / `#26364E` |
| onAccent / divider | `#121416` / `#292D32` |
| positive / warning / danger | `#7FC9A0` / `#E8B75D` / `#FFB4AB` |

Night 不反转 Light 阴影，主要依赖 Surface 明度差；状态色和 Accent 使用深色前景。

## 6. Shape 与 Depth

```kotlin
@Immutable
data class MirraShapes(
    val small: Shape = RoundedCornerShape(14.dp),
    val medium: Shape = RoundedCornerShape(20.dp),
    val large: Shape = RoundedCornerShape(28.dp),
    val extraLarge: Shape = RoundedCornerShape(32.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
)
```

三主题共享 Shape。Screen 不再随意创建圆角；图片固有裁切和系统 Dialog 可作为明确例外。

```kotlin
enum class MirraDepthLevel { FLAT, SUBTLE, RAISED, FLOATING, PRESSED }
data class MirraShadowSpec(val radius: Dp, val spread: Dp, val offset: DpOffset, val color: Color)
data class MirraDepth(/* each level's shadow specs + highlight */)
```

内部 `Modifier.mirraDepth(level, shape)` 使用 Compose `dropShadow()`/`innerShadow()`：Blue/Mono Raised 为左上淡高光+右下低 alpha 冷灰影；Pressed 去外影并加轻 inner shadow；Night 主要靠 Surface 差和一个极弱黑影。按压过渡 100–150ms，只变深度/填色。

拟态仅用于 Primary Action、Focus Card、Progress、Toggle、Bottom Navigation 和少量当前选择。可点击性同时来自填色、标签与 semantics，不能只靠阴影。

## 7. 统一组件

```kotlin
@Composable fun MirraPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit)
@Composable fun MirraSecondaryButton(...)
@Composable fun MirraTextAction(...)
@Composable fun MirraFocusCard(...)
@Composable fun MirraSurface(level: MirraDepthLevel = FLAT, ...)
@Composable fun MirraListRow(...)
@Composable fun MirraProgress(progress: () -> Float, ...)
@Composable fun MirraToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, ...)
@Composable fun MirraChip(selected: Boolean, onClick: () -> Unit, ...)
@Composable fun MirraBottomNavigation(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit)
```

- Primary：Pill、accentStrong、onAccent、Raised→Pressed，最小高度 56dp。
- Secondary：surface、textPrimary、Subtle；Text Action 无 Surface。
- 普通内容使用 ListRow/Divider；Focus Card 只承载当前主任务。
- Progress clamp `0f..1f`；Toggle/Chip 选中不只靠颜色。
- Bottom Navigation 为 Floating Pill，当前项为 Accent 胶囊+图标+文字。
- 所有交互至少 48dp，保留 Material role、focus、disabled、TalkBack semantics。

## 8. UI 迁移策略

顺序：Theme Root/Material mapping → Blue shell → Start → Session/Preparation → Knowledge/Profile → 内容与编辑页面 → Mono → Night → Picker。

Start 只做视觉迁移：核心状态可使用唯一 Focus Card，主 CTA 用 Primary，取消启动用 Text Action，ChooseInProgress 保持平面列表。禁止添加封面占位、头像、背景气泡和 slogan；`currentPage`、First Action、最近阅读、六级优先级和全部回调不变。

Note、Search、Topic、普通 Learning Item List 保持平面，以留白和 Divider 分组；不将列表改成满屏拟态卡。图片查看器改用 media Token，JPEG 白底合成保持非 UI 例外。

切换主题不得重置 back stack、输入草稿、Session timer 或 ViewModel，也不得重复创建 Intent/Session。

## 9. Theme Picker 与 Navigation

只增加一个：

```kotlin
@Serializable
data object AppearanceRoute : NavKey
```

路径为 `我的 → 外观`，Appearance 页面中的主分区是“主题”；不为字面层级再建空设置页。每个 Preview 展示固定的小 Surface、Progress、Button 色块、主题名称及当前标记。整行点击后立即预览并持久化，不设保存按钮，不展示 Paper。

`AppearanceViewModel` 只观察/写 preference 和错误状态，不持有 Color；Preview 通过 `themeId → palette` 渲染。

## 10. System Bars

- 继续 `enableEdgeToEdge()`，系统栏透明，让 background 延伸。
- Blue/Mono 用深色 system icons，Night 用浅色 icons。
- 根 Composition 用 `SideEffect` 更新 icon appearance。
- `themes.xml` 移除固定米色栏色，使用中性启动背景。
- 不借此重构现有 Insets/Scaffold。

## 11. Accessibility 与 Preview

- 普通文字对背景 ≥4.5:1；大字、关键图标、进度/控件边界 ≥3:1。
- 测试每套 palette 的三层文字、onAccent、状态前景；低对比不能被当作“高级感”。
- 状态含义必须有图标、文字、位置或控件状态辅助。
- fontScale 1.0/1.3/2.0 不截断关键 CTA；TalkBack 能读主题名、选中态和导航用途。
- Night 下 Dialog、TextField、图片遮罩、状态栏和导航栏可辨认。

新增 `MirraThemePreview(themeId, content)`，为核心组件和同一 Start Mainline fixture 生成 Blue/Mono/Night Preview 矩阵。Preview 不复制生产状态机。

## 12. 测试策略

### JVM

- theme ID null/未知→Blue，三值往返；
- Palette Token 完整，Material mapping 无默认紫色；
- 普通文字、Accent、状态色对比度满足门槛；
- progress clamp；Paper 不在 enum。

### DataStore / Instrumented

- 老 preference 默认 Blue且 lastDestination 保留；
- Blue→Mono→Night 后重建 Repository 仍恢复 Night；
- 未知值回退；写主题不覆盖导航；写入失败不伪造成功。

### Compose

- 按钮语义、disabled、点击和 ≥48dp；Toggle/Chip checked/selected 语义。
- Bottom Nav 仍只有开始/知识/我的。
- Picker 三项、即时选中、Paper 不可见。
- Start 六状态在三主题下 CTA/回调不变；2.0 fontScale 可用。
- Night 的 Dialog、输入、图片遮罩和错误文字正确。

### 全量回归

- Phase 1 Intent→Preparation→Session→Summary；2A CRUD；2B 图片补偿；Start 六状态/currentPage；2C Topic/FTS。
- 切换主题不丢 Note 草稿、不重置 Session、不重复创建核心状态。
- 完全离线、覆盖安装、强停、冷启动。

```powershell
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
./gradlew assembleDebug
```

API 37 另做三主题视觉矩阵、font scale、TalkBack、系统栏和冷启动人工验收。未执行项明确写 `Not Run`。

## 13. 预计文件

### 新增

- `data/preferences/MirraThemeId.kt`
- `ui/theme/MirraColors.kt`、`MirraPalettes.kt`、`MirraShapes.kt`、`MirraDepth.kt`
- `ui/components/MirraButtons.kt`、`MirraSurfaces.kt`、`MirraProgress.kt`、`MirraToggle.kt`、`MirraChip.kt`、`MirraBottomNavigation.kt`
- `feature/profile/AppearanceScreen.kt`、`AppearanceViewModel.kt`
- 对应 JVM/Instrumented/Compose 测试与 Preview fixtures。

### 修改

- `ui/theme/Theme.kt`
- `data/preferences/AppPreferencesRepository.kt`
- `MainActivity.kt`、`MirraApp.kt`、`navigation/Routes.kt`
- `di/AppContainer.kt`、`androidTest/.../TestAppContainer.kt`
- `res/values/themes.xml`
- Start、Session、Knowledge、Learning Item、Note、Image、Topic、Search、Profile Screen：仅迁移组件/Token。
- 用户验收规范后再更新 PRODUCT_SPEC、DECISIONS、CURRENT_STATE 和 checkpoint。

### 不修改

- Room Entity、DAO、Database、Migration、schema JSON。
- 业务 Repository（AppPreferences 主题偏好除外）与全部数据语义。

## 14. 测试驱动实施阶段

### Task 1：Theme Contract 与 Palette

- [ ] 写 theme ID、fallback、Palette 完整性和对比度失败测试。
- [ ] 运行目标 JVM 测试，确认因实现不存在而失败。
- [ ] 最小实现 ID、Token、三 Palette 与完整 Material mapping。
- [ ] 运行目标测试和 `testDebugUnitTest`。
- [ ] 提交 `feat(theme): add semantic palettes`。

### Task 2：Shape、Depth 与 Theme Root

- [ ] 写三主题 CompositionLocal/Material mapping Compose 测试。
- [ ] 实现 Shapes、Depth、`mirraDepth`、Theme provider、system bar effect。
- [ ] 运行目标测试、lint、assemble。
- [ ] 提交 `feat(theme): add shape and depth system`。

### Task 3：主题持久化

- [ ] 写默认、往返、未知值、保留导航 preference 的失败测试。
- [ ] 实现 `THEME_ID`、repository API 和根 Composition 收集。
- [ ] 运行 DataStore/启动回归测试。
- [ ] 提交 `feat(theme): persist appearance selection`。

### Task 4：核心组件

- [ ] 为语义、点击、disabled、selected、48dp、clamp 写失败测试。
- [ ] 实现组件，参数只读取 Token；添加三主题 Preview。
- [ ] 运行组件 Compose tests、lint、assemble。
- [ ] 提交 `feat(ui): add mirra themed components`。

### Task 5：Blue Shell 与 Start

- [ ] 先固化三个导航目标和 Start 六状态 CTA/回调测试。
- [ ] 迁移 Bottom Nav、Focus Card、主按钮、Progress，不增加内容或功能。
- [ ] 运行 Start、Navigation、Phase 1 回归。
- [ ] 提交 `feat(ui): apply mirra blue to start experience`。

### Task 6：其余页面

- [ ] 每次先补当前功能域按钮/导航/CRUD 回归断言。
- [ ] 逐域迁移 Session、Knowledge、Learning Item、Note、Image、Topic、Search、Profile。
- [ ] 每域运行对应 Compose/Instrumented tests。
- [ ] 提交 `refactor(ui): migrate screens to mirra components`。

### Task 7：Mono、Night 与 Picker

- [ ] 写三选项、选中语义、即时更新、失败反馈、Paper 不可见测试。
- [ ] 新增 AppearanceRoute、Profile 入口、Preview Card 与 ViewModel。
- [ ] 运行 Picker、Navigation、冷启动和 Night system bar tests。
- [ ] 提交 `feat(profile): add theme picker`。

### Task 8：全量验收与文档

- [ ] 运行第 12 节全部验证并记录真实结果。
- [ ] API 37 完成主题、font scale、TalkBack、离线、覆盖安装、强停/冷启动。
- [ ] 回归 Phase 1/2A/2B/Start/2C；若 2D 已实施，再回归 2D。
- [ ] 确认 Schema v3、Database version、Migration 不变。
- [ ] 扫描 Screen Hex、默认紫色、临时文件、调试输出、敏感信息和无关修改。
- [ ] 按证据更新规范、DECISIONS、CURRENT_STATE 和 checkpoint。
- [ ] 独立提交；只有明确授权才 Push，然后停止验收。

## 15. 最终验收标准

1. Blue 为默认，Mono/Night 可即时切换并跨重启保持；Paper 不出现。
2. 一个 Screen 服务全部主题，无主题专属副本。
3. 用户可见品牌色来自 Token，Material 角色无默认紫色泄漏。
4. Shapes 为 14/20/28/32dp/Pill；无无理由局部圆角。
5. Depth 只用于操作与焦点；普通内容列表不拟态卡片化。
6. Night 使用 Surface 层次与弱阴影，不机械反转 Light。
7. Primary/Secondary/Text Action 层级清楚，每页一个最强 CTA。
8. Start 六状态、currentPage、First Action、Intent/Session 行为不变。
9. Bottom Nav 仍只有三项目标，选中态不只靠颜色。
10. 普通文字 ≥4.5:1、关键非文字 ≥3:1、交互 ≥48dp。
11. 2.0 fontScale、TalkBack、三主题系统栏可用。
12. 切换主题不丢草稿、不重置计时/back stack、不重复创建状态。
13. Picker 使用可视化预览，无下拉或保存步骤。
14. Room Schema v3、Migration 与用户业务数据完全不变。
15. Phase 1/2A/2B/Start/2C、离线、强停、冷启动全量回归通过。
16. JVM、DataStore、Compose、Instrumented、lintDebug、assembleDebug 通过。
17. 无封面、头像、slogan、背景装饰、新依赖或产品功能扩张。

## 16. 过度设计检查

- 扩展 MaterialTheme，不替换整个 Material 体系。
- 只有三个可用 Palette，不做 Theme×Mode 矩阵。
- Depth 是 Token+Modifier，不做渲染引擎或动画框架。
- 组件只覆盖当前重复场景；主题偏好复用 DataStore。
- 只建一个 AppearanceRoute，不创建空设置层级。
- 不引入截图测试框架；Token 用 JVM，交互用 Compose，视觉用 API 37 矩阵。

## 17. 分支与实施顺序

当前 `codex/phase-2c-topic-search` 已有未提交的 Module 2D 规划。本回合不整理、不提交、不混合这些改动。

实施前：

1. 分别验收并冻结 Module 2D 与 Theme System 规划；
2. 让规划文档形成边界清晰的提交；
3. 从确认基线创建 `codex/mirra-theme-system-v1`；
4. 建议先实施 Theme Foundation + Blue，再实施 Module 2D UI，避免分析页面二次迁移；
5. Theme 模块完成后停止，不自动进入 Module 2D。

本计划完成后等待用户验收与单独实施授权；当前回合不得修改业务代码或开始主题迁移。

## 18. 规划自检

- 产品边界：参考图中的封面、头像、装饰背景与 slogan 均明确排除；Theme 不改变 IA 或业务状态。
- 架构边界：Color、Shape、Depth、Component、Persistence 和 Picker 各有单一职责；Material 与 Mirra 颜色来自同一 Palette。
- 类型一致性：`MirraThemeId`、`MirraColors`、`MirraShapes`、`MirraDepthLevel`、`AppearanceRoute` 和 Repository API 在全文名称一致。
- 迁移完整性：先全局 Material 映射，再按 Shell/Start/功能域迁移，旧页面不会处于无颜色来源状态。
- 可访问性：用户建议色值已作为 seed 保留，承担文字的组合按 4.5:1 门槛调整并纳入测试。
- 数据安全：只增加 DataStore preference；Room Schema、Migration 和业务数据完全不变。
- 占位检查：没有待定接口、未定义主题或未来模块空实现。
- 过度设计：不增加依赖、截图框架、动态主题矩阵、渲染引擎或复制 Screen。
