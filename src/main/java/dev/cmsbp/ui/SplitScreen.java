package dev.cmsbp.ui;

import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.net.Net;
import dev.cmsbp.schem.Convert;
import dev.cmsbp.schem.Schematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * 分割设置页：先选切割方式（竖直 / 水平 / 网格），再选切割数量，确认后切片写进子目录。
 *
 * <p>语义对齐 McSTools：竖直沿 X 切 N 份、水平沿 Y 切 N 层、网格在 XZ 平面切 k×k（总数 k²）。</p>
 */
public class SplitScreen extends Screen {

    private static final int[] FLAT_PRESETS = {2, 3, 4, 5, 6, 8, 10};
    private static final int[] GRID_SIDES = {2, 3, 4, 5};

    private final Path src;
    private final Screen parent;

    private Schematic model;
    private String loadError;
    private Convert.SplitMode mode = Convert.SplitMode.GRID;
    private int flatCount = 4;
    private int gridSide = 2;
    private Convert.Plan plan;

    private boolean busy;
    private String status = "";
    private boolean statusError;

    public SplitScreen(Path src, Screen parent) {
        super(Component.literal("分割蓝图"));
        this.src = src;
        this.parent = parent;
    }

    private int x0() {
        return (this.width - CreateUi.W) / 2;
    }

    private int y0() {
        return (this.height - CreateUi.H) / 2;
    }

    private int count() {
        return this.mode == Convert.SplitMode.GRID ? this.gridSide * this.gridSide : this.flatCount;
    }

    @Override
    protected void init() {
        this.plan = null;
        this.model = null;
        this.loadError = null;
        if (this.src == null) {
            this.loadError = "没有选中的文件";
            return;
        }
        Net.run(() -> {
            try {
                Schematic s = Schematic.read(this.src);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.model = s;
                    this.flatCount = Math.max(2, Math.min(4, Math.max(2, Math.min(s.sx, s.sy))));
                    this.gridSide = Math.max(2, Math.min(2, Convert.maxGridSide(s)));
                    this.recompute();
                });
            } catch (Exception e) {
                CmsBlueprints.LOG.warn("读取蓝图失败：{}", e.toString());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.loadError = "读取失败：" + shortMsg(e);
                });
            }
        });
    }

    private void recompute() {
        if (this.model == null) {
            this.plan = null;
            return;
        }
        this.plan = Convert.plan(this.model, this.mode, this.count());
    }

    private void setMode(Convert.SplitMode m) {
        this.mode = m;
        if (m == Convert.SplitMode.GRID) {
            this.gridSide = Math.min(this.gridSide, Convert.maxGridSide(this.model));
            this.gridSide = Math.max(2, this.gridSide);
        } else {
            int dim = m == Convert.SplitMode.VERTICAL ? this.model.sx : this.model.sy;
            this.flatCount = Math.min(this.flatCount, Math.max(2, dim));
        }
        this.recompute();
    }

    private void split() {
        if (this.busy || this.model == null || this.plan == null) {
            return;
        }
        if (this.plan.parts().size() <= 1) {
            this.status = "这个数量切不出多份，换个数量";
            this.statusError = true;
            return;
        }
        this.busy = true;
        this.status = "正在分割 " + this.src.getFileName() + "…";
        this.statusError = false;
        Convert.SplitMode m = this.mode;
        int n = this.count();
        Net.run(() -> {
            try {
                Convert.Result r = Convert.split(this.src, m, n);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.busy = false;
                    this.status = r.message();
                    this.statusError = false;
                });
            } catch (Exception e) {
                CmsBlueprints.LOG.warn("分割失败：{}", e.toString());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.busy = false;
                    this.status = "分割失败：" + shortMsg(e);
                    this.statusError = true;
                });
            }
        });
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x99101010);
        int x = x0();
        int y = y0();
        CreateUi.panel(g, x, y);
        CreateUi.headerText(g, this.font, "分割蓝图", x, y + 4, CreateUi.W, CreateUi.C_TITLE);

        String name = this.src == null ? "（无）" : this.src.getFileName().toString();
        g.drawString(this.font, CreateUi.clip(this.font, name, CreateUi.W - 16), x + 8, y + 18,
                CreateUi.C_TEXT, false);
        String dims = this.model == null
                ? (this.loadError == null ? "正在读取…" : this.loadError)
                : (this.model.sx + "×" + this.model.sy + "×" + this.model.sz + "   （X×Y×Z）");
        g.drawString(this.font, CreateUi.clip(this.font, dims, CreateUi.W - 16), x + 8, y + 30,
                this.loadError == null ? CreateUi.C_DIM : CreateUi.C_ERR, false);

        boolean ready = this.model != null;
        // 切割方式
        g.drawString(this.font, "方式", x + 8, y + 46, CreateUi.C_DIM, false);
        modeButton(g, mouseX, mouseY, x + 44, y + 43, 50, "竖直", Convert.SplitMode.VERTICAL, ready);
        modeButton(g, mouseX, mouseY, x + 98, y + 43, 50, "水平", Convert.SplitMode.HORIZONTAL, ready);
        modeButton(g, mouseX, mouseY, x + 152, y + 43, 56, "网格", Convert.SplitMode.GRID, ready);

        // 切割数量
        g.drawString(this.font, "数量", x + 8, y + 64, CreateUi.C_DIM, false);
        if (this.mode == Convert.SplitMode.GRID) {
            int maxSide = ready ? Convert.maxGridSide(this.model) : 0;
            for (int i = 0; i < GRID_SIDES.length; i++) {
                int side = GRID_SIDES[i];
                int bx = x + 44 + i * 40;
                boolean ok = ready && side <= maxSide;
                boolean on = ok && side == this.gridSide;
                CreateUi.button(g, this.font, side + "×" + side, bx, y + 59, 38, 14, mouseX, mouseY, ok);
                if (on) {
                    CreateUi.frame(g, bx, y + 59, 38, 14, CreateUi.C_ACCENT);
                }
            }
        } else {
            int dim = ready
                    ? (this.mode == Convert.SplitMode.VERTICAL ? this.model.sx : this.model.sy)
                    : 0;
            for (int i = 0; i < FLAT_PRESETS.length; i++) {
                int n = FLAT_PRESETS[i];
                int bx = x + 44 + i * 24;
                boolean ok = ready && n <= dim;
                boolean on = ok && n == this.flatCount;
                CreateUi.button(g, this.font, String.valueOf(n), bx, y + 59, 22, 14, mouseX, mouseY, ok);
                if (on) {
                    CreateUi.frame(g, bx, y + 59, 22, 14, CreateUi.C_ACCENT);
                }
            }
            // 自选：任意份数（水平切割想切几层都行）
            boolean can = ready && dim >= 2;
            CreateUi.button(g, this.font, "-", x + 44, y + 76, 14, 13, mouseX, mouseY, can && this.flatCount > 2);
            String v = ready ? (this.flatCount + " 份") : "-";
            g.drawString(this.font, v, x + 62, y + 79, CreateUi.C_TEXT, false);
            CreateUi.button(g, this.font, "+", x + 96, y + 76, 14, 13, mouseX, mouseY, can && this.flatCount < dim);
            g.drawString(this.font, "自选（1 份 = 沿该轴一段）", x + 116, y + 79, CreateUi.C_DIM, false);
        }

        // 预览
        int py = y + 96;
        g.fill(x + 6, py, x + 218, py + 54, 0xFF2E2E38);
        CreateUi.frame(g, x + 6, py, 212, 54, 0xFF24242C);
        int ty = py + 5;
        if (!ready) {
            g.drawString(this.font, this.loadError == null ? "读取中…" : "读不到文件，无法分割", x + 12, ty,
                    CreateUi.C_DIM, false);
        } else {
            Convert.Part f = this.plan == null ? null : this.plan.first();
            String l1 = "将生成 " + (this.plan == null ? 0 : this.plan.parts().size()) + " 份，每份 ≤ "
                    + (f == null ? "-" : f.w() + "×" + f.h() + "×" + f.d());
            g.drawString(this.font, CreateUi.clip(this.font, l1, 202), x + 12, ty, CreateUi.C_TEXT, false);
            String dirName = this.src == null ? "" : stripExt(this.src.getFileName().toString());
            g.drawString(this.font, CreateUi.clip(this.font, "输出目录：" + dirName + "/", 202),
                    x + 12, ty + 12, CreateUi.C_ACCENT, false);
            String l3 = switch (this.mode) {
                case VERTICAL -> "沿 X 竖着切，每份保留全高全深";
                case HORIZONTAL -> "沿 Y 水平切，每份保留全宽全深";
                case GRID -> "在 XZ 平面切网格，每份保留全高";
            };
            g.drawString(this.font, CreateUi.clip(this.font, l3, 202), x + 12, ty + 24, CreateUi.C_DIM, false);
            g.drawString(this.font, "余数并入最后一份；已存在同名目录时加 -2", x + 12, ty + 36,
                    CreateUi.C_DIM, false);
        }

        if (!this.status.isEmpty()) {
            g.drawString(this.font, CreateUi.clip(this.font, this.status, CreateUi.W - 16), x + 8, y + 166,
                    this.statusError ? CreateUi.C_ERR : CreateUi.C_TEXT, false);
        }

        g.fill(x + 4, y + 194, x + 218, y + 216, 0xFF3A3A44);
        CreateUi.frame(g, x + 4, y + 194, 214, 22, 0xFF6A6A76);
        CreateUi.button(g, this.font, "分割", x + 8, y + 197, 48, 16, mouseX, mouseY,
                !this.busy && ready && this.plan != null && this.plan.parts().size() > 1);
        CreateUi.button(g, this.font, "取消", x + 170, y + 197, 42, 16, mouseX, mouseY, true);
    }

    private void modeButton(GuiGraphics g, int mouseX, int mouseY, int bx, int by, int w, String label,
                            Convert.SplitMode target, boolean enabled) {
        CreateUi.button(g, this.font, label, bx, by, w, 14, mouseX, mouseY, enabled);
        if (this.mode == target) {
            CreateUi.frame(g, bx, by, w, 14, CreateUi.C_ACCENT);
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = x0();
        int y = y0();
        boolean ready = this.model != null;
        if (in(mx, my, x + 44, y + 43, 50, 14) && ready) {
            setMode(Convert.SplitMode.VERTICAL);
            return true;
        }
        if (in(mx, my, x + 98, y + 43, 50, 14) && ready) {
            setMode(Convert.SplitMode.HORIZONTAL);
            return true;
        }
        if (in(mx, my, x + 152, y + 43, 56, 14) && ready) {
            setMode(Convert.SplitMode.GRID);
            return true;
        }
        if (ready && this.mode == Convert.SplitMode.GRID) {
            int maxSide = Convert.maxGridSide(this.model);
            for (int i = 0; i < GRID_SIDES.length; i++) {
                int side = GRID_SIDES[i];
                if (side <= maxSide && in(mx, my, x + 44 + i * 40, y + 59, 38, 14)) {
                    this.gridSide = side;
                    this.recompute();
                    return true;
                }
            }
        } else if (ready) {
            int dim = this.mode == Convert.SplitMode.VERTICAL ? this.model.sx : this.model.sy;
            for (int i = 0; i < FLAT_PRESETS.length; i++) {
                int n = FLAT_PRESETS[i];
                if (n <= dim && in(mx, my, x + 44 + i * 24, y + 59, 22, 14)) {
                    this.flatCount = n;
                    this.recompute();
                    return true;
                }
            }
            if (in(mx, my, x + 44, y + 76, 14, 13) && this.flatCount > 2) {
                this.flatCount--;
                this.recompute();
                return true;
            }
            if (in(mx, my, x + 96, y + 76, 14, 13) && this.flatCount < dim) {
                this.flatCount++;
                this.recompute();
                return true;
            }
        }
        if (in(mx, my, x + 8, y + 197, 48, 16)) {
            split();
            return true;
        }
        if (in(mx, my, x + 170, y + 197, 42, 16)) {
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            split();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }
}
