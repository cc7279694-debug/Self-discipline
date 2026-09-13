# Mirra Blue Visual Refinement Checkpoint

日期：2026-09-13

## Goal

在冻结 Theme Foundation 后，只收敛 Mirra Blue 的文案层级和关键交互质感，不改变 IA、Start 六级状态、导航、业务逻辑或数据库。

## Verified Completed

- EmptyLibrary 只保留“观已 Mirra”“开始第一次学习”和“添加第一本书”，移除通用问题标题、主线解释与其他填充内容。
- EmptyLibrary 保留大面积留白；没有新增 slogan、头像、封面、气泡、卡片或装饰图形。
- `MirraPrimaryButton` 使用 `accentStrong`，并加入柔和顶部高光、raised 阴影、pressed 低 elevation 与轻量内描边。
- `MirraBottomNavigation` 保持 Floating Pill，选中项从 `accentStrong` 改为较弱的 `accent`，避免与主 CTA 争夺视觉层级。
- 品牌标识改为“观已 Mirra”，字号与字重略微增强，仍使用克制的次级文字色。
- `MirraFocusCard` 与 Bottom Navigation 的 Surface 高光由新增 `surfaceHighlight` Token 提供，Mono / Night 也有对应可读值。
- 非 EmptyLibrary 的 Start 状态继续显示原有通用行动问题；Mainline 的 Focus Card、Progress、First Action 与 CTA 行为不变。

## Scope Preserved

- 没有修改 ViewModel、Repository、Navigation、Room、DataStore、Entity、Schema 或 Migration。
- Start 六级状态、首本书创建事务、Intent / Session、Topic、Search、Image 与 currentPage 语义均未改变。
- 没有实施 Mono / Night 全页面迁移、主题选择或 Module 2D。

## Verification

- 新增 EmptyLibrary UI 断言：新主标题存在，旧通用问题与主线解释不存在；测试先在旧 UI 上失败，修改后通过。
- `testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug`：通过，Gradle `BUILD SUCCESSFUL`。
- 37 项 JVM 测试、92 项 Instrumented / Compose 测试：0 failure、0 error、0 skipped。
- API 37：最终 Debug APK 覆盖安装成功，关闭 Wi-Fi 与移动数据后冷启动成功，`LaunchState: COLD`。
- API 37 实机截图：EmptyLibrary、Mainline、Knowledge、Mine 均已检查；主 CTA 与选中导航层级清晰，普通内容仍保持平面。
- `MirraDatabase.version = 3`，历史 Schema JSON 与 Migration 无变化。

## Known Limitations

- 本轮只等待 Mirra Blue 视觉验收；Theme Foundation 已冻结。
- 截图中的 Learning Item 名称是自动化输入 fixture，不属于产品文案或业务改动。
- Mono / Night、2.0 font scale 与 TalkBack 人工专项仍属于后续独立验收。

## Next Step

等待用户验收 Mirra Blue 视觉语言。未获单独授权前，不进入 Mono / Night 或 Module 2D。
