# Su's Instant Swap / 探囊取物

Hold Left Alt to open your inventory, release over a slot to instantly swap it with your hotbar item. Works in Survival, Creative, and Adventure modes.

长按左 Alt 键打开物品栏，松开时将悬停槽位的物品与手持物品瞬间交换。支持生存、创造、冒险全部游戏模式。

## Supported Platforms / 支持平台

| Loader   | Minecraft | Status |
|----------|-----------|--------|
| NeoForge | 1.21.1    | Stable |
| Fabric   | 1.21.1    | Stable |
| Fabric   | 1.20.1    | Stable |

## Installation / 安装

Download the JAR for your platform from [Releases](https://github.com/SuCrispy/Sus-InstantSwap/releases) and place it in your `mods/` folder.

从 [Releases](https://github.com/SuCrispy/Sus-InstantSwap/releases) 下载对应平台的 JAR 文件，放入 `mods/` 文件夹。

| Loader   | MC      | File                                        |
|----------|---------|---------------------------------------------|
| NeoForge | 1.21.1  | `Sus_InstantSwap-1.0.3-NF1.21.jar`   |
| Fabric   | 1.21.1  | `Sus_InstantSwap-1.0.3-Fabric1.21.jar` |
| Fabric   | 1.20.1  | `Sus_InstantSwap-1.0.3-Fabric1.20.jar` |

## Building / 构建

Each platform is an independent Gradle project:

每个平台是独立的 Gradle 子项目：

```bash
# NeoForge 1.21 (JDK 21)
cd neoforge-1.21
./gradlew build

# Fabric 1.21 (JDK 21)
cd fabric-1.21
./gradlew build

# Fabric 1.20.1 (JDK 17)
cd fabric-1.20.1
./gradlew build
```

## License / 许可证

LGPL-3.0 — see [LICENSE](LICENSE).
