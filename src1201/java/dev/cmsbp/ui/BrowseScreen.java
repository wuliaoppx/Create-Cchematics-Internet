package dev.cmsbp.ui;

import dev.cmsbp.CreateBridge;
import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.net.CmsSite;
import dev.cmsbp.net.Net;
import dev.cmsbp.net.Site;
import dev.cmsbp.schem.Convert;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 蓝图库浏览器（Create 风格），站点的差异全部由 {@link Site} 吸收，同一个界面服务 CMS 与 MCS。
 *
 * <p>面板直接用 {@code create:textures/gui/schedule.png} 的 226×220 主体区域当底，槽位用 {@code widgets.png}，
 * 所有按钮自绘（浅灰底/深边/悬停提亮），和 Create 自己的界面观感一致。</p>
 *
 * <p>详情页三块：左上预览（三态：站点封面图 / 塞博护目镜 3D / 内置俯视投影，点角标切换；
 * 封面图态点击预览框可全屏放大）、右上元信息+注释、下方整幅宽的文件/材料双 tab 区。</p>
 */
public class BrowseScreen extends Screen {

    private static final int ROW_H = 12;
    private static final int LIST_ROWS = 11;

    /** 预览来源：站点封面图 / 塞博护目镜 3D / 内置俯视投影。 */
    private enum PreviewMode {
        COVER, GOGGLES, SELF
    }

    private final Screen parent;
    private final Site site;

    private EditBox searchBox;
    private String query = "";
    private int sortIndex;
    private int page = 1;
    private int pages = 1;
    private int total = 0;

    private List<Site.Entry> posts = new ArrayList<>();
    private int selected = -1;
    private boolean loading;
    private String status = "";
    private boolean statusError;

    private Site.Detail detail;
    private List<Site.FileEntry> files = new ArrayList<>();
    /** 文件 id → 本地已下载的 .nbt 路径（非 nbt 下载后会自动转换，这里记转换产物）。 */
    private final Map<String, Path> local = new HashMap<>();
    private int fileSelected = -1;
    private int fileOffset;
    private boolean matTab;
    private int matOffset;
    private boolean started;

    private PreviewMode previewMode = PreviewMode.COVER;
    private boolean dragPreview;
    private ResourceLocation coverTexture;
    private String coverUrl = "";
    private int coverW;
    private int coverH;

    /** 封面图全屏放大态（点击小预览框进入，任意键/点击退出）。 */
    private boolean zoomed;
    /** 放大态下的滚轮倍率（1.0 = 适配窗口）。 */
    private float zoomScale = 1.0F;

    public BrowseScreen(Screen parent) {
        this(parent, new CmsSite());
    }

    public BrowseScreen(Screen parent, Site site) {
        super(Component.literal(site.tag() + " 蓝图库"));
        this.parent = parent;
        this.site = site;
        this.sortIndex = 0;
    }

    private String sort() {
        String[] all = this.site.sorts();
        return all[Math.max(0, Math.min(this.sortIndex, all.length - 1))];
    }

    private int x0() {
        return (this.width - CreateUi.W) / 2;
    }

    private int y0() {
        return (this.height - CreateUi.H) / 2;
    }

    @Override
    protected void init() {
        int x = x0();
        int y = y0();
        this.searchBox = new EditBox(this.font, x + 8, y + 21, 118, 16, Component.literal("搜索"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setValue(this.query);
        this.searchBox.setHint(Component.literal(this.site.searchHint()));
        this.searchBox.setResponder(s -> this.query = s);
        // 封面图拉取跟随当前站点（CMS 走 HTML 站、MCS 走 API 域）
        CoverImage.setFetcher(this.site::cover);
        if (!this.started) {
            this.started = true;
            Minecraft.getInstance().execute(this::loadSearch);
        }
    }

    // ------------------------------------------------------------------ 网络

    private void loadSearch() {
        if (this.loading) {
            return;
        }
        this.loading = true;
        this.status = "正在查询…";
        this.statusError = false;
        final String q = this.query;
        final String s = sort();
        final int p = this.page;
        final Site target = this.site;
        Net.run(() -> {
            try {
                Site.Page sp = target.search(q, s, p);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.posts = sp.entries();
                    this.page = sp.page();
                    this.pages = Math.max(1, sp.pages());
                    this.total = sp.total();
                    this.selected = this.posts.isEmpty() ? -1 : 0;
                    this.loading = false;
                    this.status = this.posts.isEmpty() ? "没有找到结果"
                            : ("共 " + this.total + " 条 / " + this.pages + " 页");
                    this.statusError = false;
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.loading = false;
                    this.status = "查询失败：" + shortMsg(e);
                    this.statusError = true;
                });
            }
        });
    }

    private void openDetail(int index) {
        if (index < 0 || index >= this.posts.size() || this.loading) {
            return;
        }
        final Site.Entry sum = this.posts.get(index);
        this.loading = true;
        this.status = "正在读取详情…";
        this.statusError = false;
        this.detail = null;
        this.files = new ArrayList<>();
        this.fileSelected = -1;
        this.coverTexture = null;
        this.coverUrl = sum.coverUrl() == null ? "" : sum.coverUrl();
        final String cover = this.coverUrl;
        final Site target = this.site;
        Net.run(() -> {
            try {
                Site.Detail d = target.detail(sum);
                List<Site.FileEntry> fl = d.files();
                // 顺手把封面图拉下来解码（网络线程，失败无所谓）
                if (!cover.isBlank()) {
                    CoverImage.prefetchBlocking(cover);
                }
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.files = fl;
                    this.fileSelected = fl.isEmpty() ? -1 : 0;
                    this.detail = d;
                    this.fileOffset = 0;
                    this.matOffset = 0;
                    this.local.clear();
                    for (Site.FileEntry f : fl) {
                        Path p = CreateBridge.find(target.saveName(sum, f));
                        if (p != null) {
                            this.local.put(f.id(), p);
                        }
                    }
                    this.loading = false;
                    this.status = "共 " + fl.size() + " 个文件";
                    this.coverTexture = CoverImage.texture(cover);
                    int[] cs = this.coverTexture == null ? null : CoverImage.size(cover);
                    this.coverW = cs == null ? 0 : cs[0];
                    this.coverH = cs == null ? 0 : cs[1];
                    PreviewBridge.reset();
                    this.syncPreview();
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.loading = false;
                    this.status = "详情失败：" + shortMsg(e);
                    this.statusError = true;
                });
            }
        });
    }

    /**
     * 下载一个文件。非结构 .nbt 的（MCS 上的 .litematic / .schem）下完立刻转成 .nbt，
     * 原始文件保留，界面上用转换产物做预览。
     */
    private void download(Site.FileEntry f) {
        if (this.loading || this.detail == null || f == null) {
            return;
        }
        this.loading = true;
        this.status = "正在下载 " + f.name() + "…";
        this.statusError = false;
        final Site.Entry entry = this.detail.entry();
        final Site target = this.site;
        Net.run(() -> {
            try {
                Site.Download d = target.download(f);
                byte[] data = d.data();
                if (data == null || data.length == 0) {
                    throw new java.io.IOException("服务器返回空内容");
                }
                String name = d.fileName() == null || d.fileName().isBlank()
                        ? target.saveName(entry, f) : d.fileName();
                Path saved = CreateBridge.save(name, data);
                StringBuilder msg = new StringBuilder("已保存 " + saved.getFileName()
                        + "（" + CreateBridge.human(data.length) + "）");
                Path usable = saved;
                if (!saved.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt")) {
                    try {
                        Convert.Result r = Convert.toNbt(saved);
                        usable = r.files().get(0);
                        msg.append("，已转成 ").append(usable.getFileName());
                    } catch (Exception ce) {
                        CmsBlueprints.LOG.warn("下载后转换失败：{}", ce.toString());
                        msg.append("，但转换失败：").append(shortMsg(ce));
                    }
                }
                final Path use = usable;
                final String text = msg.toString();
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.local.put(f.id(), use);
                    this.loading = false;
                    this.status = text;
                    this.statusError = false;
                    CreateBridge.refreshTable(this.parent);
                    this.syncPreview();
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.loading = false;
                    this.status = "下载失败：" + shortMsg(e);
                    this.statusError = true;
                });
            }
        });
    }

    /** 已下载到 schematics 目录的那份文件；没下过返回 null。 */
    private Path localOfSelected() {
        if (this.fileSelected < 0 || this.fileSelected >= this.files.size()) {
            return null;
        }
        Path p = this.local.get(this.files.get(this.fileSelected).id());
        return p != null && Files.isRegularFile(p) ? p : null;
    }

    /** 打开分割设置页（切割方式 + 数量）。 */
    private void openSplit() {
        if (this.loading || this.detail == null) {
            return;
        }
        if (this.fileSelected < 0 || this.fileSelected >= this.files.size()) {
            this.status = "先在列表里选一个文件";
            this.statusError = true;
            return;
        }
        Path src = localOfSelected();
        if (src == null) {
            this.status = "还没下载这个文件，先点右边的「下载」";
            this.statusError = true;
            return;
        }
        Minecraft.getInstance().setScreen(new SplitScreen(src, this));
    }

    /** 把当前选中的文件转成结构 .nbt（方向只有「转进机械动力」）。 */
    private void convert() {
        if (this.loading || this.detail == null) {
            return;
        }
        if (this.fileSelected < 0 || this.fileSelected >= this.files.size()) {
            this.status = "先在列表里选一个文件";
            this.statusError = true;
            return;
        }
        Path src = localOfSelected();
        if (src == null) {
            this.status = "还没下载这个文件，先点右边的「下载」";
            this.statusError = true;
            return;
        }
        this.loading = true;
        this.status = "正在转换 " + src.getFileName() + "…";
        this.statusError = false;
        Net.run(() -> {
            try {
                Convert.Result r = Convert.toNbt(src);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.loading = false;
                    this.status = r.message();
                    this.statusError = false;
                    CreateBridge.refreshTable(this.parent);
                });
            } catch (Exception e) {
                CmsBlueprints.LOG.warn("转换失败：{}", e.toString());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.loading = false;
                    this.status = "转换失败：" + shortMsg(e);
                    this.statusError = true;
                });
            }
        });
    }

    private void syncPreview() {
        if (this.detail == null || this.fileSelected < 0 || this.fileSelected >= this.files.size()) {
            return;
        }
        Path p = this.local.get(this.files.get(this.fileSelected).id());
        if (p != null && this.previewMode == PreviewMode.GOGGLES) {
            PreviewBridge.select(p.getFileName().toString());
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x99101010);
        int x = x0();
        int y = y0();
        CreateUi.panel(g, x, y);
        if (this.detail == null) {
            this.renderList(g, mouseX, mouseY, x, y);
        } else {
            this.renderDetail(g, mouseX, mouseY, x, y);
        }
        this.renderStatus(g, x, y);
        if (this.zoomed) {
            this.renderZoomed(g);
        }
    }

    /** 封面图全屏放大：黑底 + 等比适配 + 滚轮缩放 + 底部提示。 */
    private void renderZoomed(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        if (this.coverTexture != null) {
            int[] cs = CoverImage.size(this.coverUrl);
            int cw = cs == null ? 1 : cs[0];
            int ch = cs == null ? 1 : cs[1];
            // 基准：适配窗口（留 40px 边距）；再乘用户滚轮倍率
            float fit = Math.max(1.0F, Math.min((this.width - 80) / (float) cw, (this.height - 80) / (float) ch));
            float sc = fit * this.zoomScale;
            int w = Math.round(cw * sc);
            int h = Math.round(ch * sc);
            g.blit(this.coverTexture, (this.width - w) / 2, (this.height - h) / 2, w, h,
                    0.0F, 0.0F, cw, ch, cw, ch);
            String info = String.format(Locale.ROOT, "%d%%", Math.round(this.zoomScale * 100));
            g.drawCenteredString(this.font, info, this.width / 2, 12, CreateUi.C_ACCENT);
        } else {
            g.drawCenteredString(this.font, "图片未加载", this.width / 2, this.height / 2, CreateUi.C_DIM);
        }
        String hint = "滚轮缩放 · 点击任意处或 Esc 返回";
        g.drawCenteredString(this.font, hint, this.width / 2, this.height - 20, CreateUi.C_DIM);
    }

    private void renderList(GuiGraphics g, int mx, int my, int x, int y) {
        CreateUi.headerText(g, this.font,
                this.site.title() + "   第 " + this.page + " / " + this.pages + " 页",
                x, y + 4, CreateUi.W, CreateUi.C_TITLE);

        this.searchBox.render(g, mx, my, 0.0F);
        CreateUi.button(g, this.font, "搜索", x + 130, y + 21, 38, 16, mx, my, !this.loading);
        CreateUi.button(g, this.font, this.site.sortLabel(sort()),
                x + 172, y + 21, 44, 16, mx, my, !this.loading);

        int rowY = y + 44;
        int listH = LIST_ROWS * ROW_H;
        g.fill(x + 4, rowY - 3, x + 218, rowY + listH - 1, 0xFF2E2E38);
        CreateUi.frame(g, x + 4, rowY - 3, 214, listH + 2, 0xFF24242C);
        for (int i = 0; i < LIST_ROWS && i < this.posts.size(); i++) {
            Site.Entry p = this.posts.get(i);
            int ry = rowY + i * ROW_H;
            boolean sel = i == this.selected;
            boolean hover = mx >= x + 6 && mx < x + 218 && my >= ry && my < ry + ROW_H - 1;
            CreateUi.rowHighlight(g, x + 6, ry, 218 - 12, ROW_H - 1, sel, hover);
            if (sel) {
                g.fill(x + 4, ry, x + 6, ry + ROW_H - 1, CreateUi.C_ACCENT);
            }

            String idLabel = this.site.idLabel(p);
            String head = idLabel.isEmpty() ? p.title() : idLabel + "  " + p.title();
            String tail = p.downloads() > 0 ? String.valueOf(p.downloads()) : "";
            int tailW = tail.isEmpty() ? 0 : this.font.width(tail) + 4;
            g.drawString(this.font, CreateUi.clip(this.font, head, 218 - 30 - tailW), x + 12, ry + 2,
                    sel ? 0xFFFFFFFF : CreateUi.C_TEXT, false);
            if (!tail.isEmpty()) {
                g.drawString(this.font, tail, x + 218 - 12 - this.font.width(tail), ry + 2,
                        sel ? CreateUi.C_ACCENT : CreateUi.C_DIM, false);
            }
        }
        if (this.posts.isEmpty() && !this.loading) {
            g.drawString(this.font, "没有结果", x + 10, rowY + 2, CreateUi.C_DIM, false);
        }

        // 底条：schedule.png 自带槽位/对勾/箭头图形，用深色铺掉再画自己的按钮
        g.fill(x + 4, y + 194, x + 218, y + 216, 0xFF3A3A44);
        CreateUi.frame(g, x + 4, y + 194, 214, 22, 0xFF6A6A76);

        CreateUi.button(g, this.font, "上一页", x + 8, y + 197, 42, 16, mx, my, this.page > 1);
        String pn = this.page + " / " + this.pages;
        g.drawString(this.font, pn, x + 58, y + 201, CreateUi.C_DIM, false);
        CreateUi.button(g, this.font, "关闭", x + 106, y + 197, 42, 16, mx, my, true);
        CreateUi.button(g, this.font, "下一页", x + 152, y + 197, 42, 16, mx, my, this.page < this.pages);
        if (this.loading) {
            g.drawString(this.font, "…加载中", x + 198, y + 201, CreateUi.C_ACCENT, false);
        }
    }

    private void renderDetail(GuiGraphics g, int mx, int my, int x, int y) {
        Site.Entry s = this.detail.entry();
        String idLabel = this.site.idLabel(s);
        String head = (idLabel.isEmpty() ? "" : idLabel + " ") + s.title();
        CreateUi.headerText(g, this.font, CreateUi.clip(this.font, head, CreateUi.W - 10),
                x, y + 4, CreateUi.W, CreateUi.C_TITLE);
        CreateUi.button(g, this.font, "返回", x + 8, y + 20, 42, 16, mx, my, true);

        // 预览框（贴边；护目镜面板自带棋盘格与边框）
        int boxX = x + 8;
        int boxY = y + 42;
        int box = 84;
        this.renderPreview(g, mx, my, boxX, boxY, box);

        int tx = boxX + box + 8;
        int tw = x + 218 - tx;
        int ty = boxY;
        ty = this.drawWrapped(g, s.title(), tx, ty, tw, 2, 0xFFFFFFFF);
        g.drawString(this.font, CreateUi.clip(this.font, "作者：" + s.author(), tw), tx, ty, CreateUi.C_DIM, false);
        ty += 10;
        g.drawString(this.font, "下载量：" + s.downloads(), tx, ty, CreateUi.C_DIM, false);
        ty += 10;
        g.drawString(this.font, CreateUi.clip(this.font, "尺寸：" + s.sizeText() + "  应力：" + s.stress(), tw),
                tx, ty, CreateUi.C_DIM, false);
        ty += 10;
        g.drawString(this.font, CreateUi.clip(this.font, "版本：" + s.versions(), tw), tx, ty, CreateUi.C_DIM, false);

        // 预览来源切换角标
        String tag = switch (this.previewMode) {
            case COVER -> "图";
            case GOGGLES -> "3D";
            case SELF -> "俯";
        };
        CreateUi.button(g, this.font, tag, boxX + box - 14, boxY - 12, 16, 11, mx, my, true);

        // 注释：meta 下方右列（2 行），下面整幅留给文件/材料区
        int noteY = y + 100;
        g.drawString(this.font, "注释", tx, noteY, CreateUi.C_ACCENT, false);
        String note = this.detail.note() == null || this.detail.note().isBlank() ? "（无）" : this.detail.note();
        int ny = noteY + 11;
        String rest = note;
        for (int i = 0; i < 2 && !rest.isEmpty(); i++) {
            String line = CreateUi.clip(this.font, rest, tw);
            g.drawString(this.font, line, tx, ny, CreateUi.C_TEXT, false);
            ny += 10;
            if (line.length() >= rest.length()) {
                break;
            }
            rest = rest.substring(line.length());
        }

        // 下方整幅宽：文件/材料双 tab 区
        int fy = y + 132;
        int fx = x + 8;
        int fw = 210;
        CreateUi.button(g, this.font, "文件", fx, fy, 34, 11, mx, my, true);
        CreateUi.button(g, this.font, "材料", fx + 38, fy, 34, 11, mx, my, true);
        if (this.matTab) {
            this.renderMaterials(g, mx, my, fx, fy, fw);
        } else {
            g.drawString(this.font, "（" + this.files.size() + " 条，滚轮滚动）", fx + 76, fy + 2, CreateUi.C_DIM, false);
            int fileRows = 4;
            int max = Math.max(0, this.files.size() - fileRows);
            this.fileOffset = Math.max(0, Math.min(this.fileOffset, max));
            for (int row = 0; row < fileRows; row++) {
                int i = this.fileOffset + row;
                if (i >= this.files.size()) {
                    break;
                }
                Site.FileEntry f = this.files.get(i);
                int ry = fy + 11 + row * ROW_H;
                boolean sel = i == this.fileSelected;
                boolean hover = mx >= fx && mx < x + 218 && my >= ry && my < ry + ROW_H - 1;
                CreateUi.rowHighlight(g, fx, ry, fw, ROW_H - 1, sel, hover);

                Path p = this.local.get(f.id());
                String mark = p != null ? "✓" : " ";
                g.drawString(this.font, mark, fx + 2, ry + 2, p != null ? CreateUi.C_OK : CreateUi.C_DIM, false);
                g.drawString(this.font, CreateUi.clip(this.font, f.name(), fw - 52), fx + 11, ry + 2,
                        p != null ? CreateUi.C_OK : CreateUi.C_TEXT, false);
                CreateUi.button(g, this.font, "下载", fx + fw - 40, ry, 40, ROW_H - 2, mx, my, !this.loading);
            }
        }

        // 底条同列表态：铺掉 schedule.png 自带图形
        g.fill(x + 4, y + 194, x + 218, y + 216, 0xFF3A3A44);
        CreateUi.frame(g, x + 4, y + 194, 214, 22, 0xFF6A6A76);
        CreateUi.button(g, this.font, "返回列表", x + 8, y + 197, 52, 16, mx, my, true);
        CreateUi.button(g, this.font, "转nbt", x + 64, y + 197, 48, 16, mx, my, !this.loading);
        CreateUi.button(g, this.font, "分割", x + 116, y + 197, 48, 16, mx, my, !this.loading);
        CreateUi.button(g, this.font, "关闭", x + 170, y + 197, 42, 16, mx, my, true);
    }

    /** 材料 tab：整幅宽列表（名字 ×数量，滚轮滚动，最多显示 4 行）。 */
    private void renderMaterials(GuiGraphics g, int mx, int my, int fx, int fy, int fw) {
        List<Site.Material> mats = this.detail.materials() == null ? List.of() : this.detail.materials();
        if (mats.isEmpty()) {
            g.drawString(this.font, "（站点未提供）", fx + 2, fy + 14, CreateUi.C_DIM, false);
            return;
        }
        g.drawString(this.font, "（" + mats.size() + " 条，仅供参考）", fx + 76, fy + 2, CreateUi.C_DIM, false);
        int rows = 4;
        int max = Math.max(0, mats.size() - rows);
        this.matOffset = Math.max(0, Math.min(this.matOffset, max));
        for (int row = 0; row < rows; row++) {
            int i = this.matOffset + row;
            if (i >= mats.size()) {
                break;
            }
            Site.Material m = mats.get(i);
            int ry = fy + 11 + row * ROW_H;
            boolean hover = mx >= fx && mx < fx + fw && my >= ry && my < ry + ROW_H - 1;
            CreateUi.rowHighlight(g, fx, ry, fw, ROW_H - 1, false, hover);
            String text = m.count() > 0 ? m.name() + " × " + m.count() : m.name();
            g.drawString(this.font, CreateUi.clip(this.font, text, fw - 8), fx + 4, ry + 2, CreateUi.C_TEXT, false);
        }
    }

    private void renderPreview(GuiGraphics g, int mx, int my, int bx, int by, int box) {
        Path p = this.fileSelected >= 0 && this.fileSelected < this.files.size()
                ? this.local.get(this.files.get(this.fileSelected).id()) : null;

        // ① 站点封面图（不依赖下载状态）
        if (this.previewMode == PreviewMode.COVER && this.coverTexture != null) {
            int[] cs = CoverImage.size(this.coverUrl);
            int cw = cs == null ? box - 2 : cs[0];
            int ch = cs == null ? box - 2 : cs[1];
            float sc = Math.min((box - 2) / (float) cw, (box - 2) / (float) ch);
            int w = Math.max(1, Math.round(cw * sc));
            int h = Math.max(1, Math.round(ch * sc));
            g.blit(this.coverTexture, bx + (box - w) / 2, by + (box - h) / 2, w, h,
                    0.0F, 0.0F, cw, ch, cw, ch);
            return;
        }

        // ② 塞博护目镜 3D
        if (this.previewMode == PreviewMode.GOGGLES && p != null && Files.isRegularFile(p)
                && PreviewBridge.render(g, bx, by, box, box)) {
            return;
        }

        // ③ 内置俯视投影
        if (p != null && Files.isRegularFile(p)) {
            ResourceLocation tex = SimplePreview.texture(p);
            int[] dim = SimplePreview.size(p);
            if (tex != null && dim != null) {
                float sc = Math.min((box - 2) / (float) dim[0], (box - 2) / (float) dim[2]);
                int w = Math.max(1, Math.round(dim[0] * sc));
                int h = Math.max(1, Math.round(dim[2] * sc));
                g.blit(tex, bx + (box - w) / 2, by + (box - h) / 2, w, h,
                        0.0F, 0.0F, dim[0], dim[2], dim[0], dim[2]);
                return;
            }
        }
        String hint = this.coverTexture != null ? "封面加载中" : (p == null ? "未下载" : "预览不可用");
        g.drawString(this.font, hint, bx + box / 2 - this.font.width(hint) / 2, by + box / 2 - 4,
                CreateUi.C_DIM, false);
    }

    private int drawWrapped(GuiGraphics g, String text, int x, int y, int width, int maxLines, int color) {
        String s = text == null ? "" : text;
        int lines = 0;
        while (!s.isEmpty() && lines < maxLines) {
            String part = CreateUi.clip(this.font, s, width);
            g.drawString(this.font, part, x, y, color, false);
            y += 10;
            lines++;
            if (part.length() >= s.length()) {
                break;
            }
            s = s.substring(Math.max(1, part.length() - 1));
        }
        return y;
    }

    private void renderStatus(GuiGraphics g, int x, int y) {
        if (this.status.isEmpty()) {
            return;
        }
        String s = CreateUi.clip(this.font, this.status, CreateUi.W - 16);
        g.drawString(this.font, s, x + 8, y + 178, this.statusError ? CreateUi.C_ERR : CreateUi.C_DIM, false);
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = x0();
        int y = y0();

        // 放大态：任意点击退出
        if (this.zoomed) {
            this.zoomed = false;
            return true;
        }

        if (this.detail == null) {
            if (this.searchBox.mouseClicked(mx, my, button)) {
                // Screen 级 focus：EditBox 自己不会置 focused（Screen.setFocused 才会），
                // 不补这行 isFocused() 恒 false，keyPressed/charTyped 的转发全部失效
                this.setFocused(this.searchBox);
                return true;
            }
            this.searchBox.setFocused(false);
            if (in(mx, my, x + 130, y + 21, 38, 16)) {
                this.page = 1;
                this.loadSearch();
                return true;
            }
            if (in(mx, my, x + 172, y + 21, 44, 16)) {
                this.sortIndex = (this.sortIndex + 1) % Math.max(1, this.site.sorts().length);
                this.page = 1;
                this.loadSearch();
                return true;
            }
            int rowY = y + 44;
            for (int i = 0; i < LIST_ROWS && i < this.posts.size(); i++) {
                if (in(mx, my, x + 6, rowY + i * ROW_H, 218 - 12, ROW_H - 1)) {
                    if (this.selected == i) {
                        this.openDetail(i);
                    } else {
                        this.selected = i;
                    }
                    return true;
                }
            }
            if (in(mx, my, x + 8, y + 197, 42, 16) && this.page > 1) {
                this.page--;
                this.loadSearch();
                return true;
            }
            if (in(mx, my, x + 152, y + 197, 42, 16) && this.page < this.pages) {
                this.page++;
                this.loadSearch();
                return true;
            }
            if (in(mx, my, x + 106, y + 197, 42, 16)) {
                this.onClose();
                return true;
            }
            return false;
        }

        // 预览来源切换 + 预览区按下（拖动换视角）
        int boxX = x + 8;
        int boxY = y + 42;
        int box = 84;
        if (in(mx, my, boxX + box - 14, boxY - 12, 16, 11)) {
            // 1.20.1 的塞博护目镜没有预览面板 → 跳过 3D 态，避免切过去是个空框
            boolean goggles = PreviewBridge.available();
            this.previewMode = switch (this.previewMode) {
                case COVER -> goggles ? PreviewMode.GOGGLES : PreviewMode.SELF;
                case GOGGLES -> PreviewMode.SELF;
                case SELF -> PreviewMode.COVER;
            };
            this.syncPreview();
            return true;
        }
        if (in(mx, my, boxX, boxY, box, box) && this.previewMode == PreviewMode.GOGGLES) {
            this.dragPreview = true;
            PreviewBridge.mouse(mx, my, true);
            return true;
        }
        // 封面图态点击预览框 → 全屏放大
        if (in(mx, my, boxX, boxY, box, box) && this.previewMode == PreviewMode.COVER
                && this.coverTexture != null) {
            this.zoomed = true;
            this.zoomScale = 1.0F;
            return true;
        }

        // tab 区：文件/材料切换 + 文件行点击（整幅宽，x+8..x+218）
        int fy = y + 132;
        int fx = x + 8;
        int fw = 210;
        if (in(mx, my, fx, fy, 34, 11)) {
            this.matTab = false;
            return true;
        }
        if (in(mx, my, fx + 38, fy, 34, 11)) {
            this.matTab = true;
            return true;
        }
        if (!this.matTab) {
            int fileRows = 4;
            for (int row = 0; row < fileRows; row++) {
                int i = this.fileOffset + row;
                if (i >= this.files.size()) {
                    break;
                }
                int ry = fy + 11 + row * ROW_H;
                if (in(mx, my, fx + fw - 40, ry, 40, ROW_H - 2)) {
                    this.fileSelected = i;
                    this.download(this.files.get(i));
                    return true;
                }
                if (in(mx, my, fx, ry, fw, ROW_H - 1)) {
                    this.fileSelected = i;
                    this.syncPreview();
                    return true;
                }
            }
        }
        if (in(mx, my, x + 64, y + 197, 48, 16)) {
            this.convert();
            return true;
        }
        if (in(mx, my, x + 116, y + 197, 48, 16)) {
            this.openSplit();
            return true;
        }
        if (in(mx, my, x + 8, y + 20, 42, 16) || in(mx, my, x + 8, y + 197, 52, 16)) {
            this.detail = null;
            this.files = new ArrayList<>();
            this.fileSelected = -1;
            this.status = "共 " + this.total + " 条 / " + this.pages + " 页";
            this.statusError = false;
            PreviewBridge.reset();
            return true;
        }
        if (in(mx, my, x + 170, y + 197, 42, 16)) {
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (this.dragPreview) {
            PreviewBridge.mouse(mx, my, true);
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (this.dragPreview) {
            this.dragPreview = false;
            PreviewBridge.mouse(mx, my, false);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.zoomed) {
            this.zoomed = false;
            return true;
        }
        if (this.searchBox != null && this.searchBox.isFocused()
                && this.searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 放大态：Esc 或任意键退出
        if (this.zoomed) {
            this.zoomed = false;
            return true;
        }
        if (this.searchBox != null && this.searchBox.isFocused()
                && this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            if (this.detail == null && this.selected >= 0) {
                this.openDetail(this.selected);
            } else if (this.detail != null && this.fileSelected >= 0) {
                this.download(this.files.get(this.fileSelected));
            }
            return true;
        }
        if (keyCode == 264) {
            this.step(1);
            return true;
        }
        if (keyCode == 265) {
            this.step(-1);
            return true;
        }
        if (keyCode == 259 && this.detail != null) {
            this.detail = null;
            this.files = new ArrayList<>();
            this.fileSelected = -1;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void step(int delta) {
        if (this.detail == null) {
            if (this.posts.isEmpty()) {
                return;
            }
            this.selected = Math.max(0, Math.min(this.posts.size() - 1, this.selected + delta));
        } else {
            if (this.files.isEmpty()) {
                return;
            }
            this.fileSelected = Math.max(0, Math.min(this.files.size() - 1, this.fileSelected + delta));
            this.syncPreview();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        int x = x0();
        int y = y0();
        // 放大态：滚轮调倍率（0.5x ~ 8x，按 1.15 一档）
        if (this.zoomed) {
            float f = scrollY > 0 ? 1.15F : 1.0F / 1.15F;
            this.zoomScale = Math.max(0.5F, Math.min(8.0F, this.zoomScale * f));
            return true;
        }
        if (this.detail != null) {
            // 预览框内滚轮 → 护目镜缩放
            int boxX = x + 8;
            int boxY = y + 42;
            if (in(mx, my, boxX, boxY, 84, 84) && this.previewMode == PreviewMode.GOGGLES) {
                PreviewBridge.scroll(scrollY);
                return true;
            }
            // tab 区滚轮 → 文件/材料滚动
            int fx = x + 8;
            int fy = y + 132;
            int fw = 210;
            if (in(mx, my, fx, fy, fw, 48)) {
                if (this.matTab) {
                    List<Site.Material> mats = this.detail.materials() == null
                            ? List.of() : this.detail.materials();
                    this.matOffset = Math.max(0, Math.min(this.matOffset - (int) Math.signum(scrollY),
                            Math.max(0, mats.size() - 4)));
                } else {
                    this.fileOffset = Math.max(0, Math.min(this.fileOffset - (int) Math.signum(scrollY),
                            Math.max(0, this.files.size() - 4)));
                }
                return true;
            }
            return true;
        }
        if (scrollY < 0 && this.page < this.pages) {
            this.page++;
            this.loadSearch();
        } else if (scrollY > 0 && this.page > 1) {
            this.page--;
            this.loadSearch();
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }
}
