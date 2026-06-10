# Changelog (forge-1.20.1)

本文档记录 Sus-InstantSwap 在 `forge-1.20.1` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [2.0.1] - 2025-06-10

### Added
- 零帧鼠标重定位，消除交换时的光标闪烁
- 支持所有容器中的空槽位交换（Empty slot swap）
- EditBox 文本输入保护

### Changed
- 移除 TooltipSuppressMixin，不再压制物品提示框显示
- 清理 InstantSwapClient 和 gradle.properties 冗余代码

### Fixed
- 修复自身槽位交换导致物品丢失的问题（self-swap），覆盖所有容器类型
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，允许关闭 Ponder 等模组暂停界面
- SWAP_KEY 现在模拟原版背包键行为（keyPressed(ESC) 关闭界面）

## [2.0.0] - 2025-06-03

### Added
- 移植到 Forge 1.20.1，采用无 Mixin 按键追踪 + SRG 反射方案
- 与 NeoForge 1.21.1 基线同步

### Changed
- 统一所有平台的元数据/语言/配置文本，与 NeoForge 1.21.1 基线对齐

### Fixed
- 版本范围修正
- 语言文件统一

## [1.3.0] - 2025-05-25

### Added
- 空槽位交换功能
- EditBox 文本输入保护机制

### Changed
- 整体代码清理与优化
- SWAP_KEY 重构为模拟原版背包键行为

### Fixed
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，兼容 Ponder 等模组暂停界面
- 简化 `simulateVanillaInventoryKey` 实现
- 改用 `keyPressed(ESC)` 方式关闭界面
- 使用 `removed()` 方式实现 `simulateVanillaInventoryKey`

## [1.2.1] - 2025-05-20

### Fixed
- 修复按键无法关闭模组界面的问题
- 声明模组为纯客户端（`side=CLIENT` in `[[mods]]`）

## [1.2.0] - 2025-05-18

### Added
- 添加 `mod_version` 字段

### Fixed
- 修复合成网格界面关闭问题
- 统一多语言翻译文本
- 修复配置重置问题
- 更新元数据

## [1.1.1] - 2025-05-15

### Changed
- 从 Fabric 1.20.1 重新移植，修复诸多问题

## [1.1.0] - 2025-05-10

### Added
- 首次移植到 Forge 1.20.1
- 长短按模式：支持长按和短按触发不同行为
- 完整的配置系统
- 模组 LOGO 图标

> **注意**：此版本标记为 UNTESTED（未测试）。