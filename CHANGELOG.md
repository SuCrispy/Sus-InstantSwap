# SusInstantSwap Changelog

## [2.0.1] — 2026-06-08

### Changed
- **Mouse reposition: zero-frame cursor placement.** Instead of relying solely on `glfwSetCursorPos` (which updates asynchronously, causing a 1-tick visible flicker), the mod now directly sets `MouseHandler`'s internal `xpos`/`ypos` fields so the first render frame already reads the correct cursor position.
  - *Fabric*: Uses a `MouseHandlerAccessor` Mixin (`@Accessor("xpos")` / `@Accessor("ypos")`). Loom automatically remaps the Mojang field names to intermediary at compile time.
  - *Forge*: Uses reflection with dual-name fallback (`"xpos"` / `"f_91507_"` for SRG runtime compatibility).
  - *NeoForge*: Uses reflection with `"xpos"`/`"ypos"` (runtime official mappings).
- **Mouse reposition timing fix (Fabric).** For E-key opened screens, reposition now happens in `ScreenEvents.AFTER_INIT` (before the first render) instead of `ClientTickEvents.END_CLIENT_TICK` (after render). Eliminates the 1-tick cursor flick on all container types.

### Removed
- **Tooltip suppression system.** With zero-frame cursor placement, the 3-tick tooltip suppression window (`suppressTooltipTicks`, `isTooltipSuppressed()`, `TooltipSuppressMixin`) is no longer needed. All related code and the `TooltipSuppressMixin.java` file have been removed.

### Fixed
- **Self-swap on non-inventory containers (chest boats, vehicles, etc.).** Swapping a hotbar slot with itself previously only worked correctly for `InventoryScreen`. The check `hs.index == hotbarMenuSlot(sel)` used `hs.index` (menu slot index, which varies per container layout). Now uses `hs.getContainerSlot()` (the slot's index within its container, always 0–8 for hotbar slots) for all container types.
- **Version metadata.** `neoforge.mods.toml` in NF 1.21.1 and NF 26.1 had stale `version="2.0.0"` → corrected to `"2.0.1"`.

### Platform Knowledge
- **Fabric Loom does NOT remap string literals.** `getDeclaredField("xpos")` fails at runtime because `"xpos"` stays as the Mojang name, but the Fabric runtime uses intermediary names. Always use `@Accessor("xpos")` or `@Shadow` for field access in Fabric — Loom remaps annotation values at compile time.

---

## [2.0.0] — Initial Multi-Platform Release

### Added
- Long-press swap: hold the inventory key (default E), release to swap current hotbar with hovered slot.
- GUI swap: bind a key in Controls to swap items without long-press in any container screen.
- Creative inventory swap with full slot type awareness.
- Dual keybind support — coexists with vanilla inventory toggle.
- Mouse reposition on container open (right-click and E-key).
- Sound feedback on successful swap.
- Empty slot swap toggle (config option).
- Supported platforms: Fabric 1.20.1 / 1.21.1 / 26.1, Forge 1.20.1 / 1.21.1 / 26.1, NeoForge 1.21.1 / 26.1.
