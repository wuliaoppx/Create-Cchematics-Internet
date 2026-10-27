# 第三方声明 / Third-Party Notices

本项目的自有代码以 **MIT License** 发布（见 `LICENSE`）。但它并非在法律真空里：
一部分算法与实现来自另外三个同样是宽松协议的项目，另有一个第三方解码库被
**以二进制形式打包进发布的 jar**。下面逐条列清来源、协议与本仓库的使用方式。

---

## 1. Create（机械动力）

- 项目：https://github.com/Creators-of-Create/Create
- 协议：**MIT**
- 依据：`create-1.20.1-6.0.8.jar` 内 `META-INF/mods.toml` 的 `license = "MIT"`

**本仓库的使用方式**

- 运行时依赖，不打包、不修改其字节码。
- `src1201/java/dev/cmsbp/ui/SchematicPreviewPanel.java` 里的 `fixControllerBlockEntities()`
  按其 `SchematicHandler#fixControllerBlockEntities` 的行为实现（多方块控制器的坐标重算）。
- 蓝图桌 3D 预览调用其公开 API `SchematicRenderer`。
- 界面贴图（`schedule.png`、`AllIcons`、`AllGuiTextures`）在运行时从 Create 的资源读取，
  本仓库不复制这些素材。

```
MIT License — Copyright (c) Creators-of-Create
```

---

## 2. Create Cyber Goggles（赛博护目镜）

- 项目：https://github.com/ForgeStove/CreateCyberGoggles
- 协议：**MIT**
- 依据：`CreateCyberGoggles-1.20.1-7.2.3-Forge.jar` 内 `LICENSE` 文件

**本仓库的使用方式**（这是本项目最需要注意的一条）

1.20.1 蓝图桌的「子目录选择 + 3D 预览」是**按其实现思路重写的**，具体对应关系：

| 本仓库文件 | 来源 |
|---|---|
| `ui/SchematicPreviewPanel.java` | 其 1.21.1 `core/schematic/SchematicPreviewPanel`（`build()` / `renderPreview()` / 鼠标拖拽旋转 / 滚轮缩放 / 棋盘格背景） |
| `ui/SchematicFolders.java` | 其 1.20.1 `core/util/SchematicFolderUtil`（子目录扫描、数字感知排序、上传名压平） |
| `ui/TablePickerExt.java` | 其 `mixin/misc/SchematicTableScreenMixin` + `ClientSchematicLoaderMixin` 的等价改写 |

原文用 Mixin 注入，本仓库没有 Mixin 环境，改用 Forge 事件（`ScreenEvent.Init.Post`、
`ContainerScreenEvent.Render.Background` 等）复刻同样的挂载点，逻辑等价。

其 MIT 协议要求：上述版权声明与许可声明须包含在本软件的所有副本或实质性部分中。
**因此本文件应当随源码与发布 jar 一同分发。**

```
MIT License

Copyright (c) 2025 ForgeStove

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. TwelveMonkeys ImageIO（WebP 解码器，二进制打包）

- 项目：https://github.com/haraldk/TwelveMonkeys
- 协议：**BSD 3-Clause**
- 依据：其仓库根目录 `LICENSE.txt`

**为什么需要它**：蓝图站的封面图是 WebP 格式，而 Minecraft 自带的 `NativeImage.read`
只认 PNG/JPEG。Minecraft 运行在 Java 17/21，没有内置 WebP 解码，只能自带一个纯 Java 解码器。

**打包方式**：`lib/webp/` 下的 6 个 jar 在构建时被解开、其 `.class` 与
`META-INF/services` 合并进最终 jar（`build.ps1` 的「已合并 WebP 解码器」步骤）。
这是**二进制再分发**，触发 BSD-3 第 2 条款：须在文档或随附材料中复现版权声明、
条件与免责声明——即本文件。

| Maven 坐标 | 文件 |
|---|---|
| `com.twelvemonkeys.imageio:imageio-webp:3.10.1` | `imageio-webp-3.10.1.jar` |
| `com.twelvemonkeys.imageio:imageio-core:3.10.1` | `imageio-core-3.10.1.jar` |
| `com.twelvemonkeys.imageio:imageio-metadata:3.10.1` | `imageio-metadata-3.10.1.jar` |
| `com.twelvemonkeys.common:common-lang:3.10.1` | `common-lang-3.10.1.jar` |
| `com.twelvemonkeys.common:common-io:3.10.1` | `common-io-3.10.1.jar` |
| `com.twelvemonkeys.common:common-image:3.10.1` | `common-image-3.10.1.jar` |

来源：`https://repo.maven.apache.org/maven2/`

```
BSD 3-Clause License

Copyright (c) 2008-2020, Harald Kuhr
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## 4. 未打包的运行时依赖

以下项目仅作为编译/运行时依赖引用，其代码不进入本项目的 jar：

| 项目 | 协议 |
|---|---|
| Minecraft / Mojang | 专有（EULA），本项目不包含其任何代码或素材 |
| NeoForge 21.1（1.21.1 版） | LGPL-2.1 |
| Forge 47.4.28（1.20.1 版） | LGPL-2.1 |

---

## 5. 关于本模组抓取的两个站点

本模组会访问社区蓝图站 `creativemechanicserver.com` 与 `mcschematic.top` 的公开接口，
仅作为客户端发起 HTTP 请求、保存用户主动选择的文件，不内嵌或再分发站点的任何内容。
蓝图文件与其封面图的版权归各上传者所有。
