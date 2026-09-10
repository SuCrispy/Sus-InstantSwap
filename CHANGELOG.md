# Changelog (forge-1.21.11)

本文档记录 Sus-InstantSwap 在 `forge-1.21.11` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [3.0.0] - 2026-09-10

### Added
- 完整移植至 MC 1.21.11，功能与 1.21.1 版本对等：E 键交换、GUI 交换、长按模式、创造模式交换、鼠标重定位、装备槽保护、行交换、背包模组兼容（9 款）、toast 通知、22 种语言
- 适配 MC 1.21.9+ 变更：`keyPressed(KeyEvent)`、`KeyMapping.Category`（自定义按键分类）、`InputConstants.getKey(KeyEvent)`
- 键位分类 lang 键采用 1.21.9+ 单数格式 `key.category.susinstantswap.main`
- 配置界面适配新渲染管线（背景 blur 由框架统一调用，render() 内不再手动调用）
- 跟随官方 `ResourceLocation` → `Identifier` 重命名（1.21.10 → 1.21.11 破坏性变更）
- 编译加 `options.release = 21`，确保字节码版本（class 65）兼容 Java 21 运行时
