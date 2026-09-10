# Changelog (neoforge-26.2)

本文档记录 Sus-InstantSwap 在 `neoforge-26.2` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [3.0.0] - 2026-09-10

### Added
- 完整移植至 MC 26.2，功能与 1.21.1 / 26.1 版本对等：E 键交换、GUI 交换、长按模式、创造模式交换、鼠标重定位、装备槽保护、行交换、背包模组兼容（9 款）、toast 通知、22 种语言

### Changed
- 适配 Gui 重组：`mc.screen` → `mc.gui.screen`（字段私有化）
- SwapToast 重写，移除已删除的 `ChatFormatting` 依赖（改用 `TextColor`）
- 资源包格式 pack_format 26.1=57 → 26.2=88
- Fabric：Loom 1.17.19、ModMenu 20.0.1、Gradle 9.5.1
- 补上 \`ContainerSetSlotGuardMixin\`（26.1 移植时缺失）
- 配置界面适配 1.21.9+ 渲染管线（背景 blur 由框架统一调用，render() 内不再手动调用）
