首个公开版本。

## 功能

- **图纸桌三个入口**：在机械动力的图纸桌界面加三个按钮 —— CMS 蓝图站、MCS 蓝图站、蓝图处理。
- **站内浏览与下载**：直接浏览两个蓝图站的列表，预览封面图，选中即下载到 `schematics` 目录并立刻载入图纸桌，不用退出游戏。
- **格式转换**：Litematica（`.litematic`）、WorldEdit（`.schem`）、Sponge（`.schematic`）、原版结构（`.nbt`）互转，产出机械动力能读的结构文件。
- **分割**：按竖直、水平或网格方式把大蓝图切成多份，自动建子目录存放。
- **1.20.1 专属**：蓝图桌内置 3D 预览（用 Create 自己的 `SchematicRenderer` 真 GL 渲染，可拖拽旋转、滚轮缩放），文件选择器支持切换 `schematics` 下的子文件夹。

## 下载

| 游戏版本 | 加载器 | 文件 |
|---|---|---|
| 1.21.1 | NeoForge 21.1+ | `schematicsinternet-1.21.1-0.1.0.jar` |
| 1.20.1 | Forge 47.4+ | `schematicsinternet-1.20.1-0.1.0.jar` |

**必须同时安装机械动力（Create）**：1.21.1 需要 Create 6.0.10+，1.20.1 需要 Create 6.0.8。纯客户端模组，服务器端不需要安装。

## SHA256

```
30DABECD474E941EE61B8EC689F5AE26835A61D8826FA80FB8E0B3B219CC0D06  schematicsinternet-1.21.1-0.1.0.jar
BD43F55E0925B83E13A77BC988FAE065E8CDA654BE30957D1E1913D2ECEF14F4  schematicsinternet-1.20.1-0.1.0.jar
```

## 已知限制

- 只做了机械动力图纸桌的集成，没有做独立物品或方块。
- 站点登录后的私有内容不支持。
- 1.21.1 的 3D 预览依赖可选模组 Cyber Goggles；没装时用自绘俯视缩略图替代。

---

## English

First public release.

- Three extra buttons in Create's Schematic Table: CMS site, MCS site, and a blueprint tool screen.
- Browse either site in-game, preview covers, download straight into `schematics/` and load it into the table.
- Convert between Litematica / WorldEdit / Sponge / vanilla structure formats.
- Split large blueprints vertically, horizontally or in a grid, each part into its own subfolder.
- 1.20.1 build also renders a true 3D preview inside the Schematic Table (drag to rotate, scroll to zoom) and lets you pick subfolders.

Requires Create (6.0.10+ on 1.21.1, 6.0.8 on 1.20.1). Client-side only.
