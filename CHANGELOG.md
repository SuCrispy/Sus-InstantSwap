# Changelog (fabric-26.1)

本文档记录 Sus-InstantSwap 在 `fabric-26.1` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [2.0.1] - 2025-06-10

### Added
- 零帧鼠标重定位（通过 Accessor 接口实现），消除交换时的光标闪烁
- 支持与空槽位交换物品（Empty slot swap）
- EditBox 文本输入保护，输入时不会误触发交换

### Changed
- 移除 TooltipSuppressMixin，不再压制物品提示框显示
- 重构 InstantSwapClient 代码，清理冗余逻辑

### Fixed
- 修复自身槽位交换导致物品丢失的问题（self-swap）
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，允许关闭 Ponder 等模组暂停界面
- SWAP_KEY 现在模拟原版背包键行为（keyPressed(ESC) 关闭界面）

## [2.0.0] - 2025-06-03

### Added
- MC 26.1 完整功能对等：E 键交换、GUI 交换、长按模式、创造模式交换、鼠标重定位、tooltip 抑制、装备槽保护
- 分类翻译支持
- KeyboardMixin / ScreenKeyMixin 适配 MC 26.1 API

### Changed
- 统一所有平台的元数据/语言/配置文本，与 NeoForge 1.21.1 基线对齐

### Fixed
- 修复延迟鼠标重定位问题
- 重新添加 `inMenuContext` 追踪
- 修复创造模式快捷栏客户端预测
- 添加 `emptySlotSwapEnabled` 配置开关

## [1.3.0] - 2025-05-25

### Added
- 空槽位交换功能
- EditBox 文本输入保护机制

### Changed
- 整体代码清理与优化
- SWAP_KEY 重构为模拟原版背包键行为

### Fixed
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，兼容 Ponder 等模组暂停界面

## [1.2.1] - 2025-05-20

### Fixed
- 修复按键无法关闭模组界面的问题

## [1.2.0] - 2025-05-18

### Added
- 配置界面支持

### Fixed
- 修复合成网格界面关闭问题
- 统一多语言翻译文本
- 修复 MC 版本范围
- 更新元数据

## [1.1.0] - 2025-05-10

### Added
- 从 v1.0.3 源代码升级至独立 Fabric 26.1 分支
- 长短按模式：支持长按和短按触发不同行为
- 完整的配置系统
- 模组 LOGO 图标

### Changed
- Fabric 26.1 版本独立为单独分支

> **注意**：此版本标记为 UNTESTED（未测试）。

## [1.0.3] - 2025-04-28

> 此版本为分支共同的基线。

### Added
- README 中文翻译

### Changed
- Monorepo 架构：同时支持 NeoForge 1.21、Fabric 1.21、Fabric 1.20.1
- 统一 JAR 命名格式：`Sus_InstantSwap-version-LoaderMC.jar`