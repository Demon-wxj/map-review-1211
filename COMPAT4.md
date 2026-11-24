# Map Review 1.0.5 compat4

`1.0.5+mc1.21.1.compat4` 对应 Mega Walls
`1.18.45-bed-items-mc1.21.1` 和 Tabletop `0.5.14+mc1.21.1`。
运行环境仍为 Minecraft 1.21.1、Fabric 和 Java 21。

使用原始发行的 `map-review-1.0.5+mc1.21.1.jar` 作为输入，以 Python 3.9 或更新版本执行。

```text
python tools/build_compat4.py map-review-1.0.5+mc1.21.1.jar map-review-1.0.5+mc1.21.1.compat4.jar
```

| JAR | SHA-256 |
| --- | --- |
| 1.0.5 原始发行包 | `1ad282c54388569d1547502daad68c4ab47cfcae42902dbe1d4fce9c68d99f86` |
| 已核对的 compat4 发行包 | `560c33c4ae72cce42b96ffdd9a90ce9546f41831f180cabdc5506105767c1fa8` |

脚本校验输入哈希，生成新 JAR，并检查只有 `fabric.mod.json` 发生变化。
原 JAR 的 Java 字节码、资源和 MIT 许可文本均保留。
仓库现有 Java 源码与 GPL-3.0-only 许可属于源码快照 `1.0.3+mc1.21.1`。
