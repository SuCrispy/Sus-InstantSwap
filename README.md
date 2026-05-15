# Su's Instant Swap

Hold Left Alt to open your inventory, release over a slot to instantly swap it with your hotbar item. Works in Survival, Creative, and Adventure modes.

## Supported Platforms

| Loader   | Minecraft | Status |
|----------|-----------|--------|
| NeoForge | 1.21.1    | Stable |
| Fabric   | 1.21.1    | Stable |
| Fabric   | 1.20.1    | Stable |

## Installation

Download the JAR for your platform from [Releases](https://github.com/SuCrispy/Sus-InstantSwap/releases) and place it in your `mods/` folder.

| Loader   | MC      | File                                        |
|----------|---------|---------------------------------------------|
| NeoForge | 1.21.1  | `Sus_InstantSwap-NeoForge-1.21-1.0.3.jar`   |
| Fabric   | 1.21.1  | `Sus_InstantSwap-Fabric-1.21-1.0.3.jar`     |
| Fabric   | 1.20.1  | `Sus_InstantSwap-Fabric-1.20-1.0.3.jar`     |

## Building

Each platform is an independent Gradle project:

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

## License

LGPL-3.0 — see [LICENSE](LICENSE).
