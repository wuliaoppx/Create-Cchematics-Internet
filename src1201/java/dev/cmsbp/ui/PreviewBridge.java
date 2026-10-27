package dev.cmsbp.ui;

import dev.cmsbp.CmsBlueprints;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 塞博护目镜（Create Cyber Goggles）的蓝图预览面板桥。
 *
 * <p>该模组是可选依赖：装了就用它的体素预览，没装就退回 {@link SimplePreview}。
 * 全部走反射，所以编译期不依赖它，也不会因为它的版本变化而链接失败。</p>
 *
 * <p>注意：{@code SchematicPreviewPanel} 是按 <b>schematics 目录里的文件名</b> 加载的，
 * 所以只有已经下载到本地的蓝图才能预览。</p>
 */
public final class PreviewBridge {

    private static final String PANEL_CLASS =
            "io.github.forgestove.create_cyber_goggles.core.schematic.SchematicPreviewPanel";

    private static boolean probed;
    private static boolean usable;
    private static Object panel;
    private static Method mSetSelected;
    private static Method mRender;
    private static Method mUpdateMouse;
    private static Method mOnScroll;
    private static Method mResetView;

    private PreviewBridge() {
    }

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> type = Class.forName(PANEL_CLASS);
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            panel = ctor.newInstance();
            mSetSelected = type.getMethod("setSelected", String.class);
            mRender = type.getMethod("render", GuiGraphics.class, int.class, int.class, int.class, int.class);
            mUpdateMouse = type.getMethod("updateMouse", double.class, double.class, boolean.class);
            mOnScroll = type.getMethod("onScroll", double.class);
            mResetView = type.getMethod("resetView");
            usable = true;
            CmsBlueprints.LOG.info("已接入塞博护目镜的蓝图预览");
        } catch (Throwable t) {
            usable = false;
            CmsBlueprints.LOG.info("未检测到可用的塞博护目镜预览（{}），改用内置俯视缩略图", t.getClass().getSimpleName());
        }
    }

    /** 护目镜预览是否可用。 */
    public static boolean available() {
        probe();
        return usable;
    }

    /**
     * 切换预览目标。
     *
     * @param fileName schematics 目录里的文件名（含 .nbt）
     */
    public static void select(String fileName) {
        probe();
        if (!usable) {
            return;
        }
        try {
            mSetSelected.invoke(panel, fileName);
        } catch (Throwable t) {
            CmsBlueprints.LOG.debug("预览切换失败：{}", t.toString());
        }
    }

    /** 在给定矩形里渲染预览。 */
    public static boolean render(GuiGraphics g, int x, int y, int w, int h) {
        probe();
        if (!usable) {
            return false;
        }
        try {
            mRender.invoke(panel, g, x, y, w, h);
            return true;
        } catch (Throwable t) {
            usable = false;
            CmsBlueprints.LOG.warn("护目镜预览渲染失败，切回内置缩略图：{}", t.toString());
            return false;
        }
    }

    public static void mouse(double x, double y, boolean dragging) {
        probe();
        if (!usable) {
            return;
        }
        try {
            mUpdateMouse.invoke(panel, x, y, dragging);
        } catch (Throwable ignored) {
            // 预览拖动失败不影响主流程
        }
    }

    public static void scroll(double delta) {
        probe();
        if (!usable) {
            return;
        }
        try {
            mOnScroll.invoke(panel, delta);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    public static void reset() {
        probe();
        if (!usable) {
            return;
        }
        try {
            mResetView.invoke(panel);
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
