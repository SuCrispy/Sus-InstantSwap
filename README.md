# 探囊取物 / Su's Instant Swap

**一键交换手中物品，不再需要打开物品栏拖放，省时省力。**  
**One key, instant item swap. No more opening inventory and dragging items with the mouse.**

---

## 使用方式 / How it works

1. 按住 `左 Alt` 键（键位可自定义）  
   Hold the `Left Alt` key (fully customizable)

2. 鼠标悬停在弹出物品栏的目标物品上  
   Hover your mouse over the desired item in the popup inventory panel

3. 松开 `左 Alt` 键  
   Release the `Left Alt` key

选中物品会与当前手持物互换，就是这么简单～  
The selected item will swap with the item you're currently holding. That's it!

---

## 支持的模组版本 / Supported Versions

- **Minecraft**: 1.21.1+
- **Loader**: NeoForge（`neoforge.mods.toml` 中 `versionRange="[21.1,)"` 覆盖 NeoForge 21.x 及 26.x）
- **游戏模式**: 生存 / 冒险 / 创造（自动识别模式，分别使用服务端权威交换或客户端创造模式物品添加）

---

## 安装 / Installation

1. 安装 [NeoForge](https://neoforged.net/)（版本 ≥ 21.1）
2. 将下载的 `.jar` 文件放入 `mods/` 文件夹
3. 启动游戏

> ⚠️ **纯客户端模组** – 服务端不需要安装，可在任何服务器上使用  
> Pure client-side mod – no server installation needed, works on any server

---

## 重要说明 / Important

- **反作弊风险**：使用前请务必向服主确认，否则需自行承担风险  
  **Anti-cheat warning**: Always check with your server admin. Use at your own risk.

- **键位自定义**：在 `Options... → Controls... → Su's Instant Swap` 中修改触发按键

---

## 未来计划 / Future plans

- 提供 API 接口，方便开发附属模组  
  Provide an API for add-ons

- 可能适配部分背包模组  
  Possibly add compatibility with some backpack mods

---

## 技术细节 / Technical Details

| 项目 | 说明 |
|------|------|
| 协议 / License | LGPL 3.0 |
| 运行环境 / Side | 纯客户端（Client-only）|
| 网络通信 | 生存/冒险模式使用 `ServerboundSwapPacket` 服务端权威交换；创造模式使用客户端 `handleCreativeModeItemAdd` |
| Access Transformer | 需要 `CreativeModeInventoryScreen.CONTAINER`、`SlotWrapper.target` |

---

## 开源 / Open Source

完全由 AI 在 1.21.1 版本上开发，理论上兼容 1.21 之后的所有版本。  
Developed entirely by AI on version 1.21.1, theoretically compatible with all versions after 1.21.

- **GitHub**: https://github.com/SuCrispy/Sus-InstantSwap
- **Modrinth**: [待发布 / Coming soon]
- **问题反馈 / Issues**: https://github.com/SuCrispy/Sus-InstantSwap/issues

---

## 许可证 / License

本模组以 **LGPL 3.0** 协议发布。  
This mod is released under the **LGPL 3.0** license.

详见 [LICENSE](LICENSE) 文件。  
See the [LICENSE](LICENSE) file for details.
