package dev.cmsbp.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Create 风格绘制工具。
 *
 * <p>直接用 Create 自己的 GUI 贴图（{@code assets/create/textures/gui/*.png}）画面板、标题条和
 * 槽位，颜色也照抄 Create 的深色面板配色，避免和原版界面拼在一起像两个游戏。</p>
 *
 * <p>这里刻意不依赖 catnip 的 {@code ScreenElement}/{@code AllGuiTextures}：
 * 直接 blit 贴图更稳，少一层反射/版本耦合。</p>
 */
public final class CreateUi {

    public static final ResourceLocation SCHEDULE =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/schedule.png");
    public static final ResourceLocation WIDGETS =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/widgets.png");

    /**
     * 面板尺寸。schedule.png 是 256×256 图集，主体面板实际只占 (13,0)-(238,219)：
     * 标题条 y0–14、深色主体 y15–189、底条 y190–219；x13 左右各留白 13/17px，
     * y220 之后全是透明或零散小部件。blit 整张 256×226 会导致「小一圈」+ 右端露杂件。
     */
    public static final int TEX_X = 13;
    public static final int TEX_Y = 0;
    public static final int W = 226;
    public static final int H = 220;
    /** 顶部浅色标题条高度。 */
    public static final int HEADER = 15;
    /** 底部浅色按钮条高度。 */
    public static final int FOOTER = 21;

    public static final int C_TITLE = 0xFF3A3A3A;
    public static final int C_TEXT = 0xFFDEDEDE;
    public static final int C_DIM = 0xFF9A9A9A;
    public static final int C_ACCENT = 0xFF7EC8E3;
    public static final int C_OK = 0xFF7FD08A;
    public static final int C_ERR = 0xFFE07A7A;

    public static final int C_BTN = 0xFFC6C6C6;
    public static final int C_BTN_HOVER = 0xFFE2E2E2;
    public static final int C_BTN_EDGE = 0xFF8B8B8B;
    public static final int C_BTN_TEXT = 0xFF3F3F3F;

    /** AllIcons 反射是否可用（失败则入口按钮退回画字母）。 */
    public static boolean iconsReady;
    private static boolean iconsProbed;
    /** 反射拿到的 AllIcons 字段缓存：字段名 → 图标实例（失败记为 null）。 */
    private static final java.util.Map<String, Object> ICONS = new java.util.HashMap<>();

    /**
     * 反射探测 Create 的 AllIcons（编译期不直接引用，避免 ponder/catnip 版本耦合）。
     * 探测不到就记 null 并不再重试，入口按钮退回画字母。
     */
    private static void probeIconsOnce() {
        if (iconsProbed) {
            return;
        }
        iconsProbed = true;
        try {
            Class<?> cls = Class.forName("com.simibubi.create.foundation.gui.AllIcons");
            Object schematic = cls.getField("I_SCHEMATIC").get(null);
            ICONS.put("I_SCHEMATIC", schematic);
            iconsReady = schematic != null;
        } catch (Throwable t) {
            iconsReady = false;
        }
    }

    /** 供 TableButtonHook 调用的公开入口。 */
    public static void probeIcons() {
        probeIconsOnce();
    }

    /**
     * 取一个 AllIcons 图标（{@code I_SCHEMATIC} / {@code I_OPEN_FOLDER} / {@code I_REFRESH} …）。
     *
     * @return 图标实例；反射不可用或字段不存在时返回 {@code null}
     */
    public static Object icon(String field) {
        probeIconsOnce();
        if (!iconsReady) {
            return null;
        }
        if (ICONS.containsKey(field)) {
            return ICONS.get(field);
        }
        Object got = null;
        try {
            got = Class.forName("com.simibubi.create.foundation.gui.AllIcons").getField(field).get(null);
        } catch (Throwable t) {
            got = null;
        }
        ICONS.put(field, got);
        return got;
    }

    private CreateUi() {
    }

    /** 画整块面板（只 blit schedule.png 里真正属于面板的 226×220 区域）。 */
    public static void panel(GuiGraphics g, int x, int y) {
        g.blit(SCHEDULE, x, y, W, H, TEX_X, TEX_Y, W, H, 256, 256);
    }

    /** 顶部标题条上的深色居中文字。 */
    public static void headerText(GuiGraphics g, Font font, String text, int x, int y, int w, int color) {
        String s = font.plainSubstrByWidth(text, Math.max(8, w - 4));
        g.drawString(font, s, x + (w - font.width(s)) / 2, y, color, false);
    }

    /**
     * 自绘按钮（Create 的浅灰底色 + 深色描边）。
     *
     * @return 鼠标是否悬停其上
     */
    public static boolean button(GuiGraphics g, Font font, String label,
                                 int x, int y, int w, int h,
                                 int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? C_BTN_HOVER : C_BTN);
        g.fill(x, y, x + w, y + 1, C_BTN_EDGE);
        g.fill(x, y + h - 1, x + w, y + h, C_BTN_EDGE);
        g.fill(x, y, x + 1, y + h, C_BTN_EDGE);
        g.fill(x + w - 1, y, x + w, y + h, C_BTN_EDGE);
        String s = font.plainSubstrByWidth(label, Math.max(8, w - 4));
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2,
                enabled ? C_BTN_TEXT : 0xFF9A9A9A, false);
        return hover;
    }

    /** Create 的物品槽底（widgets.png 里 54,0 处的 16×16）。 */
    public static void slot(GuiGraphics g, int x, int y) {
        g.blit(WIDGETS, x, y, 16, 16, 54.0F, 0.0F, 16, 16, 256, 256);
    }

    /** 1px 边框。 */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 悬停/选中的整行高亮。 */
    public static void rowHighlight(GuiGraphics g, int x, int y, int w, int h, boolean selected, boolean hover) {
        if (selected) {
            g.fill(x, y, x + w, y + h, 0x50FFFFFF);
        } else if (hover) {
            g.fill(x, y, x + w, y + h, 0x28FFFFFF);
        }
    }

    public static String clip(Font font, String s, int width) {
        if (s == null) {
            return "";
        }
        return font.width(s) <= width ? s : font.plainSubstrByWidth(s, width - font.width("…")) + "…";
    }
}
