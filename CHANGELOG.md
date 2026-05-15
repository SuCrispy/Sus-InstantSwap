# Changelog

## v1.0.3 (2026-05-15)

### Added
- Fabric 1.21.1 support
- Fabric 1.20.1 support

### Fixed
- Creative mode: use `CreativeModeInventoryScreen` instead of `InventoryScreen`

## v1.0.2 (2026-05-14)

### Fixed
- `neoforge.mods.toml` format: moved `license` to top level
- Added null checks for `mc.player` and `mc.gameMode`
- Added SLF4J logging throughout
- Proper `@Mod(dist = Dist.CLIENT)` annotation

## v1.0.0 (2026-05-13)

### Added
- Initial release: NeoForge 1.21.1
- Hold Left Alt to open inventory, release to swap
- Survival and Creative mode support
