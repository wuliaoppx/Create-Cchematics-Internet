# Create: Blueprint Network

Browse community blueprint sites straight from Create's **Schematic Table**, download the result
into `schematics/`, and convert or slice it before you place it.

**English** ｜ [中文](#中文说明)

> Two builds are shipped. Pick the one that matches your game version:
> `blueprintnet-1.21.1-0.1.0.jar` or `blueprintnet-1.20.1-0.1.0.jar`.

---

## What it does

Three buttons appear one row above the Schematic Table panel:

```
┌──────────────[CMS][M][处]┐   ← one row above the panel's top-right corner
│  CMS  ── creativemechanicserver.com  (community blueprint site, Chinese)
│  M    ── mcschematic.top             (community blueprint site, JSON API)
│  处    ── blueprint tools: convert / split files already in schematics/
```

- **Browse and search** — page through both sites, sort by downloads or date, search by keyword.
- **Inspect before downloading** — cover image, size, stress impact, required mods, material list
  and the author's notes are all shown in-game.
- **One click to `schematics/`** — the file lands where Create expects it. Litematica (`.litematic`)
  and WorldEdit (`.schem`) files are converted to Create's structure `.nbt` automatically on
  download; the original is kept.
- **Split large builds** — vertical (X), horizontal (Y) or grid (XZ) cuts with an adjustable piece
  count. Output goes into its own subfolder.
- **1.20.1 build only** — subfolder selection inside the Schematic Table, plus a real 3D preview
  (shading, transparency, block entities) rendered with Create's own renderer. Drag to rotate,
  scroll to zoom.

Everything is done by hand: the mod never crawls, never polls, and never downloads anything you
did not click.

## Requirements

| | 1.21.1 build | 1.20.1 build |
|---|---|---|
| Minecraft | 1.21.1 | 1.20.1 |
| Loader | NeoForge 21.1.265 | Forge 47.4.28 |
| Create | 6.0.10 | 6.0.8 |
| Java | 21 | 17 |

**Create is required.** Blueprint Network is a client-side mod — the server does not need it, and
the 1.20.1 build is marked to ignore version mismatches so it will not block you from joining
servers that don't have it.

## Installation

1. Install Minecraft, the loader and Create for your version.
2. Drop the matching jar into `.minecraft/mods/`.
3. Launch. No new items or blocks are added — everything lives in the Schematic Table screen.

## How to use

Open a **Schematic Table**. The three buttons sit just above the panel's top-right corner.
You can also run `/cms`, `/mcs` or `/bptool` from chat to open the same screens.

| Where | What you can do |
|---|---|
| List | click to select, click again or `Enter` to open; scroll wheel flips pages; search field filters by keyword |
| Detail | preview (cover image / built-in top-down), metadata, notes, **Files** and **Materials** tabs |
| Files tab | **Download** (converts automatically if needed), **To NBT**, **Split** |
| Split page | choose vertical / horizontal / grid and a piece count, then confirm |
| Blueprint tools | convert or split any file already in `schematics/`, including subfolders |

Converted and split files land in `schematics/`; split output goes into `<name>/` (or `<name>-2/`
if that already exists). Conversion is one-way — other formats into Create's `.nbt`. Turning a
Create blueprint back into Litematica or WorldEdit is not supported.

## Configuration

`config/blueprintnet.properties`:

```properties
site=https://www.creativemechanicserver.com
userAgent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/156.0.0.0 Safari/537.36
proxy=
timeoutSeconds=30
requestIntervalMs=300
```

Set `proxy=host:port` if you need one. If the sites are unreachable from your network, this is the
first thing to check.

## Known limitations

- Tied to the Create version above — it hooks Create's schematic loader and GUI textures, so a
  major Create update needs a new build.
- The three buttons only show up on the Schematic Table.
- In the 1.21.1 build, the `3D` preview mode requires the **Create Cyber Goggles** mod. Without it
  the cover-image and top-down previews still work.
- MCEdit `.schematic` files can be listed and split, but not converted (they use numeric block IDs).
- No Mixin. The buttons are drawn and hit-tested directly, so they are mouse-only and cannot be
  reached with Tab.

## Credits

- **Create** (MIT) — the schematic renderer, GUI textures and loader this mod builds on.
- **Create Cyber Goggles** by ForgeStove (MIT) — the 1.20.1 subfolder picker and 3D preview are a
  reimplementation of its approach, rewritten against Forge events instead of Mixin.
- **TwelveMonkeys ImageIO** (BSD-3-Clause) — pure-Java WebP decoder, bundled into the jar because
  Minecraft cannot read the cover images otherwise.

Full license texts are in the repository (`THIRD_PARTY_NOTICES.md`). This mod is MIT licensed.

---

## 中文说明

在 Create 的**图纸桌**里直接浏览社区蓝图站，选中后下载到 `schematics/`，还能转换格式、按尺寸切块。

> 有两个版本，按你的游戏版本选：`blueprintnet-1.21.1-0.1.0.jar` 或 `blueprintnet-1.20.1-0.1.0.jar`。

### 功能

图纸桌面板右上角上方会多出三个按钮：

- `CMS` —— creativemechanicserver.com（中文社区蓝图站）
- `M` —— mcschematic.top（JSON 接口的蓝图站）
- `处` —— 蓝图处理：对 `schematics/` 里已有的文件做转换 / 分割

能做的事：

- **翻页、排序、搜关键词**，两个站点都支持；
- **下载前先看清**：封面图、尺寸、应力影响、需要的模组、材料用量、作者注释；
- **一键进 `schematics/`**：`.litematic` / `.schem` 下载后自动转成 Create 结构 `.nbt`，原文件保留；
- **大蓝图切块**：竖直（X）/ 水平（Y）/ 网格（XZ），份数可调，产物自动进同名子目录；
- **1.20.1 版额外**：图纸桌内可选子文件夹，以及真 3D 预览（带光影与透明方块），拖拽旋转、滚轮缩放。

模组不会抓站、不会后台轮询，只处理你亲手点的那一个文件。

### 版本要求

| | 1.21.1 版 | 1.20.1 版 |
|---|---|---|
| Minecraft | 1.21.1 | 1.20.1 |
| 加载器 | NeoForge 21.1.265 | Forge 47.4.28 |
| Create | 6.0.10 | 6.0.8 |
| Java | 21 | 17 |

**必须装 Create。** 本模组是纯客户端模组，服务器不需要装；1.20.1 版已设置为忽略版本差异，
不会因为服务器没装而拒绝你进入。

### 安装

把对应 jar 丢进 `.minecraft/mods/` 即可。没有新增物品或方块，全部功能都在图纸桌界面里。

### 使用

打开图纸桌，三个按钮就在面板右上角上方。也可以在聊天栏输入 `/cms`、`/mcs`、`/bptool` 直接打开。

| 界面 | 能做什么 |
|---|---|
| 列表 | 点击选中，再点一次或 `Enter` 进详情；滚轮翻页；搜索框按关键词查 |
| 详情 | 预览（封面图 / 内置俯视）、元信息、注释、**文件** 与 **材料** 双 tab |
| 文件 tab | **下载**（需要时自动转换）、**转nbt**、**分割** |
| 分割页 | 选竖直 / 水平 / 网格与份数，确认后落盘 |
| 蓝图处理 | 对 `schematics/` 里已有的文件转换或分割，支持进子目录 |

转换只有一个方向：**别的格式 → Create 结构 `.nbt`**，不支持把 `.nbt` 导回 Litematica / WorldEdit。

### 已知限制

- 依赖上表中的 Create 版本（用到它的图纸载入器与界面贴图），Create 大版本更新后需要重新编译；
- 三个入口按钮只在图纸桌界面出现；
- 1.21.1 版的 `3D` 预览需要装 **Create Cyber Goggles**，没装时该档自动跳过，封面图与俯视预览不受影响；
- MCEdit 的 `.schematic` 可以列出、可以分割，但不能转换；
- 没有用 Mixin：按钮是自己绘制并自己判定点击的，因此只能用鼠标点，Tab 键选不到。

### 协议

MIT。Create（MIT）、Create Cyber Goggles（MIT）、TwelveMonkeys ImageIO（BSD-3-Clause）的声明见仓库
`THIRD_PARTY_NOTICES.md`。
