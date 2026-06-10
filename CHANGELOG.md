# Changelog (neoforge-26.1)

本文档记录 Sus-InstantSwap 在 `neoforge-26.1` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [2.0.1] - 2025-06-10

### Added
- 零帧鼠标重定位，消除交换时的光标闪烁
- 支持空槽位交换物品（Empty slot swap）
- EditBox 文本输入保护

### Changed
- 移除 TooltipSuppressMixin，不再压制物品提示框显示
- 清理 InstantSwapClient 及 `neoforge.mods.toml` 冗余内容

### Fixed
- 修复自身槽位交换导致物品丢失的问题（self-swap）
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，允许关闭 Ponder 等模组暂停界面
- SWAP_KEY 现在模拟原版背包键行为（keyPressed(ESC) 关闭界面）

## [2.0.0] - 2025-06-03

### Added
- MC 26.1 完整功能对等：E 键交换、GUI 交换、长按模式、创造模式交换、鼠标重定位、tooltip 抑制、装备槽保护
- 创造模式交换装备槽保护修复
- `ScreenEvent.KeyPressed.Pre` 事件拦截
- 装备槽 block 机制
- 分类翻译支持

### Changed
- 统一所有平台的元数据/语言/配置文本，与 NeoForge 1.21.1 基线对齐
- 使用 `isInventoryScreen` 实现 `closePendingTicks`（1 vs 2 tick）+ gatekeeper 方式

### Fixed
- 修复创造模式交换装备槽守卫问题

## [1.3.0] - 2025-05-25

### Added
- 空槽位交换功能
- EditBox 文本输入保护机制

### Changed
- SWAP_KEY 重构为模拟原版背包键行为

### Fixed
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，兼容 Ponder 等模组暂停界面

## [1.2.1] - 2025-05-20

### Fixed
- 修复按键无法关闭模组界面的问题

### Changed
- 更新 NeoForge 26.1 元数据

## [1.2.0] - 2025-05-18

### Added
- GUI 交换功能：在界面中通过快捷键交换物品
- 创造模式交换客户端预测
- 支持原版容器（熔炉、箱子、工作台等）

### Changed
- `guiSwapEnabled` 默认改为 `false`
- GUI 交换限制为纯存储容器 + 玩家物品栏
- 移除容器交换支持，仅保留玩家物品栏交换
- 统一字段声明顺序

### Fixed
- 修复合成网格界面关闭问题
- 统一多语言翻译文本
- 修复 GUI 交换对原版容器的支持
- 修复 `mods.toml` 描述乱码（编码问题）

## [1.1.1] - 2025-05-15

### Changed
- 版本号升级
- 统一命名：NF1.21 → NF1.21.1

### Fixed
- 移除错误路径下的重复 `icon.png`

## [1.1.0] - 2025-05-10

### Added
- 独立 NeoForge 1.21 分支
- 长短按模式：支持长按和短按触发不同行为
- 完整的配置系统
- 模组 LOGO 图标

### Changed
- 配置文件精简
- 调试日志置底

## [1.0.3] - 2025-04-28

> 此版本为分支共同的基线。

### Added
- README 中文翻译

### Changed
- Monorepo 架构：同时支持 NeoForge 1.21、Fabric 1.21、Fabric 1.20.1
- 统一 JAR 命名格式：`Sus_InstantSwap-version-LoaderMC.jar`