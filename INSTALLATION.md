# 安装与使用

单人装在客户端；联机时客户端和 Fabric 服务器都要安装相同版本。使用现有世界前先备份。

适用 Minecraft **1.21.1 / Fabric / Java 21**，不能用于 NeoForge。依赖版本：

| 依赖 ID | 版本约束 |
| --- | --- |
| `minecraft` | `1.21.1` |
| `java` | `>=21` |
| `fabricloader` | `>=0.16.10` |
| `fabric-api` | `*` |
| `megawalls` | `['1.18.37-arena-interactions-mc1.21.1', '1.18.38-tnt-floor-mc1.21.1']` |
| `tabletop` | `['0.5.6+mc1.21.1', '0.5.7+mc1.21.1', '0.5.8+mc1.21.1']` |

按 [构建说明](BUILDING.md) 生成生产 JAR，与上表依赖一起放入游戏实例的 `mods/`。
