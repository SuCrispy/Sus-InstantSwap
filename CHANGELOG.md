# SusInstantSwap Changelog

All notable changes to this project will be documented in this file.
本项目的所有重要变更将记录在此文件中。

---

## [2.1.0] — 2026-06-10

### Added / 新增

- **Toast 浮窗提示 / Toast messages** — 在 action bar 上显示交换失败原因（空槽禁止、双空槽等），内置 1.5 秒冷却防止刷屏。
  Fail reasons (empty-slot blocked, both slots empty, etc.) are shown as colored action-bar overlays with a 1.5-second cooldown.
  - 白色信息提示 / white info
  - 黄色警告提示 / yellow warning
  - 红色错误提示 / red error
- **背包按键防连击 / Backpack key-repeat guard** — 长按 E 交换时通过 `ScreenEvent.KeyPressed.Pre` 取消背包模组（SophisticatedBackpacks 等）的按键连击事件，防止背包意外开关。
  Cancels repeated key events at the Screen level so backpack mods don't toggle closed while the user is holding E for a long-press swap.
- **非原版键拦截 / Non-vanilla key guard** — 通过非原版键打开的容器界面自动阻止交换，避免对背包模组专用界面的误操作。
  Swaps are automatically blocked when the current screen was opened by a non-vanilla key binding.
- **运行时按键重绑定检测 / Runtime key-rebind detection** — 自动检测玩家在游戏内修改按键绑定，动态更新目标按键列表，无需重启。
  Automatically refreshes the tracked key set when the player rebinds keys at runtime.
- 新增配置项 / New config option: **"浮窗提示"** (`toastEnabled`, 默认开启 / default ON)。
- **9 款背包模组兼容 / 9 backpack mod compatibility** — 支持以下背包模组的快捷键拦截，在这些模组存在时自动注册其打开背包的按键为标准目标键：
  Supports key interception for the following backpack mods, auto-registering their open-backpack keys as standard target keys when installed:
  - Sophisticated Backpacks (`key.sophisticatedbackpacks.open_backpack`)
  - Traveler's Backpack (`key.travelersbackpack.open_backpack`)
  - Omnis Backpacks (`key:omnis_backpack`)
  - Backpacked (`key.backpacked.open_backpack`)
  - Inmis (`key.inmis.open_backpack`)
  - Good Backpacks (`key.goodbackpacks.open_backpack`)
  - Resource Backpacks (`key.resource_backpacks.open_backpack`)
  - Iron Backpacks (`key.ironbackpacks.open_backpack`)
  - Simply Backpacks (`key.simplybackpacks.open_backpack`)
- **22 种语言完整本地化 / 22-language i18n** — 新增繁体中文 (zh_tw)、日语 (ja_jp)、韩语 (ko_kr) 以及 17 种自动翻译的语言文件。
  Added Traditional Chinese, Japanese, Korean, and 17 auto-translated language files for full international coverage.
- **Row Swap Grooves / 整行交换凹槽** — thin green groove indicators on both sides of player-inventory rows.
  在玩家物品栏行的两侧显示绿色凹槽指示器。
  - Hover any groove + hold E → entire row (9 slots) swaps with the hotbar at once.
    悬停凹槽 + 长按 E → 整行（9 格）与快捷栏一键交换。
  - Works on **all** container screens (inventory, chest, furnace, crafting table, etc.), not just the survival inventory.
    在**所有**容器界面生效（物品栏、箱子、熔炉、工作台等），不限于生存物品栏。
  - Dynamic slot detection adapts to any menu layout automatically.
    动态槽位检测，自动适配任意菜单布局。
- **Hover sound / 悬停音效** — a crisp tick plays when moving the mouse across groove boundaries, respecting the existing Sound config toggle.
  鼠标划过凹槽边界时播放清脆提示音，遵循已有的音效配置开关。
- New config option / 新增配置项: **"整行交换"** (`rowSwapEnabled`, default ON / 默认开启)。

### Fixed / 修复

- **Creative inventory row swap / 创造模式行交换** — `SlotWrapper.index` returned incorrect values in NeoForge, breaking row swap in creative inventory. Fixed by building a `containerSlot → menuIndex` mapping during slot detection.
  NeoForge 中 `SlotWrapper.index` 返回错误值导致创造模式行交换失效。修复方式：在槽位检测阶段构建 `containerSlot → menuIndex` 映射。

- **Container swap validation — backpack ghost item fix / 容器交换验证 — 背包幽灵物品修复**
  Swapping an "in-use" item (e.g. a backpack held in mainhand while its UI is open) now correctly rejects the swap, preventing a ghost item from appearing in the mainhand slot.
  交换"使用中"的物品（如主手持背包且背包 UI 打开时）现在会正确拒绝交换，防止主手出现幽灵物品。
  - Added bidirectional `Slot.mayPickup(Player)` checks in `performSwap()`, `containerSwap()`, and `performRowSwap()` — both the hovered slot AND the hotbar slot are now validated, matching vanilla's `AbstractContainerMenu.doClick()` behavior.
    在 `performSwap()`、`containerSwap()` 和 `performRowSwap()` 中新增双向 `mayPickup(Player)` 检查——悬停槽位和快捷栏槽位都会验证，与原版 `AbstractContainerMenu.doClick()` 行为一致。
  - Added `mayPlace()` validation for row swap hotbar items (was previously missing).
    为行交换的快捷栏物品添加 `mayPlace()` 验证（之前缺失）。
  - New helper method `findMenuSlot()` for locating menu Slot objects by container slot index.
    新增辅助方法 `findMenuSlot()`，通过容器槽位索引定位菜单 Slot 对象。
  - `isContainerOpener()` guard retained as extra safety layer (detects locked matching items in non-player-inventory container slots).
    `isContainerOpener()` 守卫作为额外安全层保留（检测非玩家物品栏容器槽位中的锁定匹配物品）。

### Changed / 变更

- **统一日志门面 / Unified logging** — 新增 `SwapLog` 类统一管理所有日志输出，替换各处直接使用 `LogUtils.getLogger()`。debug 日志受配置 `debug` 开关控制。
  All logging now goes through the `SwapLog` facade; debug-level messages respect the `debug` config toggle.
- **增强调试日志 / Enhanced debug logging** — 在所有关键路径（状态机转换、交换执行、按键处理、容器检测）添加详细的 debug 级别日志，方便排查问题。
  Detailed debug logging added to all critical paths: state-machine transitions, swap execution, key handling, and container detection.
- 代码清理 / Code cleanup:
  - 移除各处冗余的 `Logger` 字段声明，统一使用 `SwapLog`。
    Removed scattered `Logger` field declarations in favor of the unified `SwapLog`.
  - 将 `isContainerOpener()` 中的 `LOGGER.info()` 替换为 `debugLog()`——之前每次交换都会记录每个槽位的日志。
    Replaced `LOGGER.info()` with `debugLog()` in `isContainerOpener()` — was logging every slot on every swap.
  - 移除未使用的 `hotbarMenuSlot()` 方法和 `slotClicked` 访问转换器条目。
    Removed unused `hotbarMenuSlot()` method and `slotClicked` access transformer entry.
- `.gitignore` 新增 `run/` 目录排除。
  Added `run/` directory to `.gitignore` (generated by `gradle runClient`).

---

## [2.0.1] — 2026-06-02

### Changed / 变更

- **Mouse reposition: zero-frame cursor placement / 鼠标重定位：零帧光标放置** — Instead of relying solely on `glfwSetCursorPos` (which updates asynchronously, causing a 1-tick visible flicker), the mod now directly sets `MouseHandler`'s internal `xpos`/`ypos` fields so the first render frame already reads the correct cursor position.
  不再仅依赖 `glfwSetCursorPos`（异步更新导致 1 帧闪烁），改为直接设置 `MouseHandler` 内部 `xpos`/`ypos` 字段，使首帧渲染即读取正确光标位置。
  - *Fabric*: Uses a `MouseHandlerAccessor` Mixin (`@Accessor("xpos")` / `@Accessor("ypos")`). Loom automatically remaps the Mojang field names to intermediary at compile time.
    *Fabric*：使用 `MouseHandlerAccessor` Mixin（`@Accessor`）。Loom 在编译时自动将 Mojang 字段名重映射为 intermediary。
  - *Forge*: Uses reflection with dual-name fallback (`"xpos"` / `"f_91507_"` for SRG runtime compatibility).
    *Forge*：使用反射，双名称回退（`"xpos"` / `"f_91507_"` 兼容 SRG 运行时）。
  - *NeoForge*: Uses reflection with `"xpos"`/`"ypos"` (runtime official mappings).
    *NeoForge*：使用反射，`"xpos"`/`"ypos"`（运行时官方映射）。
- **Mouse reposition timing fix (Fabric) / 鼠标重定位时序修复 (Fabric)** — For E-key opened screens, reposition now happens in `ScreenEvents.AFTER_INIT` (before the first render) instead of `ClientTickEvents.END_CLIENT_TICK` (after render). Eliminates the 1-tick cursor flick on all container types.
  E 键打开的界面，重定位改为在 `ScreenEvents.AFTER_INIT` 中执行（首帧渲染前），而非 `ClientTickEvents.END_CLIENT_TICK`（渲染后）。消除所有容器类型的 1 帧光标闪烁。

### Removed / 移除

- **Tooltip suppression system / 工具提示抑制系统** — With zero-frame cursor placement, the 3-tick tooltip suppression window (`suppressTooltipTicks`, `isTooltipSuppressed()`, `TooltipSuppressMixin`) is no longer needed. All related code and the `TooltipSuppressMixin.java` file have been removed.
  零帧光标放置后，3 帧工具提示抑制窗口（`suppressTooltipTicks`、`isTooltipSuppressed()`、`TooltipSuppressMixin`）不再需要。已移除所有相关代码和 `TooltipSuppressMixin.java` 文件。

### Fixed / 修复

- **Self-swap on non-inventory containers / 非物品栏容器的自身交换** — Swapping a hotbar slot with itself previously only worked correctly for `InventoryScreen`. The check `hs.index == hotbarMenuSlot(sel)` used `hs.index` (menu slot index, which varies per container layout). Now uses `hs.getContainerSlot()` (the slot's index within its container, always 0–8 for hotbar slots) for all container types.
  快捷栏与自身交换之前仅在 `InventoryScreen` 上正确工作。旧检查 `hs.index == hotbarMenuSlot(sel)` 使用 `hs.index`（菜单槽位索引，随容器布局变化）。现在对所有容器类型使用 `hs.getContainerSlot()`（容器内槽位索引，快捷栏始终为 0–8）。
- **Version metadata / 版本元数据** — `neoforge.mods.toml` in NF 1.21.1 and NF 26.1 had stale `version="2.0.0"` → corrected to `"2.0.1"`.
  NF 1.21.1 和 NF 26.1 的 `neoforge.mods.toml` 版本号过时，已更正为 `"2.0.1"`。

### Platform Knowledge / 平台知识

- **Fabric Loom does NOT remap string literals / Fabric Loom 不重映射字符串字面量** — `getDeclaredField("xpos")` fails at runtime because `"xpos"` stays as the Mojang name, but the Fabric runtime uses intermediary names. Always use `@Accessor("xpos")` or `@Shadow` for field access in Fabric — Loom remaps annotation values at compile time.
  `getDeclaredField("xpos")` 运行时失败，因为 `"xpos"` 保持 Mojang 名称，而 Fabric 运行时使用 intermediary 名称。Fabric 中务必使用 `@Accessor("xpos")` 或 `@Shadow` 访问字段——Loom 在编译时重映射注解值。

---

## [2.0.0] — 2026-05-25

### Added / 新增

- **Long press swap / 长按交换** — hold inventory key (E) ≥ threshold → swap hovered item with hotbar. Short press still opens/closes inventory.
  长按物品栏键（E）≥ 阈值 → 将悬停物品与快捷栏交换。短按仍打开/关闭物品栏。
- **GUI swap / 界面内交换** — optional keybind for swapping items directly within container screens without closing them.
  可选键位，在容器界面内直接交换物品，无需关闭界面。
- **Creative mode swap / 创造模式交换** — 4-branch logic handling creative tabs, equipment slots, SlotWrapper, and regular hotbar slots.
  4 分支逻辑处理创造标签页、装备槽位、SlotWrapper 和普通快捷栏槽位。
  - Branch order: `CONTAINER → reference-match equipment → CREATIVE_EQUIP (csi=5-8/45) → SlotWrapper → REGULAR`.
    分支顺序：`CONTAINER → 引用匹配装备 → CREATIVE_EQUIP(csi=5-8/45) → SlotWrapper → REGULAR`。
  - Equipment types: csi 5→HEAD, 6→CHEST, 7→LEGS, 8→FEET.
    装备类型：csi 5→头盔, 6→胸甲, 7→护腿, 8→靴子。
- **Dual keybind / 双键位** — separate "Swap in GUI" key alongside the inventory key.
  独立的"界面内交换"键位，与物品栏键并存。
- **Config screen / 配置界面** — built-in platform configuration GUI with sliders and toggles.
  内置平台配置 GUI，包含滑块和开关。
- **Mouse reposition / 鼠标重定位** — cursor auto-moves to bottom-right corner when opening containers.
  打开容器时光标自动移至右下角。
- **Sound feedback / 音效反馈** — swap sound and hover sound with config toggle.
  交换音效和悬停音效，支持配置开关。
- **Empty slot swap toggle / 空槽交换开关** — config option to enable/disable swapping with empty slots.
  配置项，控制是否允许与空槽位交换。
- **Supported platforms / 支持平台**: Fabric 1.20.1 / 1.21.1 / 26.1, Forge 1.20.1 / 1.21.1 / 26.1, NeoForge 1.21.1 / 26.1.

### Changed / 变更

- Removed `backpackPriority` config option.
  移除 `backpackPriority` 配置项。
- Forge-style config dual-layer sync (spec ↔ runtime) adopted.
  采用 Forge 风格配置双层同步（spec ↔ runtime）。
- Key mapping intercept uses Mixin injection on `KeyMapping.click()` / `KeyMapping.set()` to prevent long-press screen flicker.
  按键映射拦截使用 Mixin 注入 `KeyMapping.click()` / `KeyMapping.set()`，防止长按闪烁。

### Fixed / 修复

- **Creative equipment slot guard / 创造装备槽守卫** — armour type mismatch no longer silently fails.
  护甲类型不匹配不再静默失败。
- **2-tick close delay for modded containers / 模组容器 2 tick 关闭延迟** — prevents ghost swaps with Curios etc.
  防止与 Curios 等模组产生幽灵交换。
- **`Slot.mayPlace()` client-side pre-check / 客户端 mayPlace 预检查** — Curios compatibility.
  Curios 兼容性预检查。
- **EditBox text protection / 文本框保护** — typing in text fields no longer closes the screen.
  在文本框中输入不再关闭界面。

### Platform Knowledge / 平台知识

- **Forge SlotWrapper** — `getContainerSlot()` returns screen-position-based index (0–8 for hotbar), not the inventory container slot index. Must use `SlotWrapper.target` for actual container slot mapping.
  Forge `SlotWrapper` 的 `getContainerSlot()` 返回屏幕位置索引（快捷栏为 0–8），非物品栏容器槽位索引。必须使用 `SlotWrapper.target` 获取实际容器槽位映射。
- **Forge config dual-layer sync / Forge 配置双层同步** — Load: build spec → registerConfig → syncToRuntime. Save: syncToSpec → spec.save(). Never write TOML manually.
  加载：构建 spec → registerConfig → syncToRuntime。保存：syncToSpec → spec.save()。禁止手动写 TOML。
- **Forge Mod constructor / Forge 模组构造器** — Use `(FMLJavaModLoadingContext)`, not `(IEventBus, ModContainer)`. Bus: `context.getModEventBus()`, Container: `context.getContainer()`.
  使用 `(FMLJavaModLoadingContext)`，不用 `(IEventBus, ModContainer)`。Bus：`context.getModEventBus()`，Container：`context.getContainer()`。