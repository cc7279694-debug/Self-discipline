# Mirra Blue 最终冻结 Checkpoint

## Goal

完成 Theme Foundation 与 Mirra Blue 的首个完整视觉基线，让后续页面直接继承稳定的颜色、层级、组件和交互质感。

## Verified completed

- 灰白主体、蓝色行动色、`#386FBE` 主 CTA 与 `#6598E8` Selected / Progress 语义已冻结。
- Start EmptyLibrary、Mainline、Knowledge、Floating Bottom Navigation、Focus Card 与主按钮视觉已经验收。
- Start Mainline 使用“今天继续”，不暴露“当前主线”系统术语。
- Knowledge 使用独立搜索入口、轻量笔记/图片/Topic 二级导航，并保持 Learning Item 为主体。
- 确定型 Progress 只显示实际 Fill 与剩余 Track，不绘制 Track 末端 stop marker。

## Changes

- `MirraProgress` 显式关闭 Material 3 默认 `drawStopIndicator`。
- 新增像素级 Compose 测试，验证未填充 Track 尾部不存在 Accent 像素。
- 更新产品规范与当前状态，记录 Mirra Blue 已正式冻结。

## Inherited decisions

- Depth 只服务于交互与焦点，不用于普通列表和大段文本装饰。
- Theme 不改变 IA、布局尺寸、Touch Target、Navigation、Start 六级状态或业务行为。
- Module 2D 新页面直接复用 Mirra semantic token 与现有组件，不重新定义视觉系统。

## Known limitations

- Mono / Night 仅保留基础 Palette / Token，尚未完成全页面视觉迁移或开放选择入口。
- 本次不包含 Module 2D、Analytics、数据库或 Migration 变化。

## Verification

- `testDebugUnitTest`：37/37 通过。
- `connectedDebugAndroidTest`：93/93 通过，包含新增 Progress 像素回归测试。
- `lintDebug`：通过。
- `assembleDebug`：通过，Debug APK 成功生成。
- API 37 模拟器断网覆盖安装并实机截图确认 Track 末端蓝点已移除。
- Room Schema 保持 v3，历史 Schema 文件无变化。

## Next step

等待用户单独授权后，按 `docs/plans/MODULE_2D_IMPLEMENTATION.md` 实施 Module 2D，并继承本 Checkpoint 的 Mirra Blue 视觉规则。
