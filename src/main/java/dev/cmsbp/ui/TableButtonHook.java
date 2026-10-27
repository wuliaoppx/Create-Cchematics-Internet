package dev.cmsbp.ui;

import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.CreateBridge;
import dev.cmsbp.net.CmsSite;
import dev.cmsbp.net.McsSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 在 Create 的图纸桌（Schematic Table）面板上方挂三个入口：CMS 蓝图库 / MCS 蓝图站 / 蓝图处理。
 *
 * <p>为什么不用真正的 widget：{@code Screen.addRenderableWidget} 是 {@code protected}，
 * 外部包加不进去；{@code ScreenEvent.Init} 的 {@code addListener} 只进 listeners 列表，
 * 不会自动绘制。所以这里「自绘 + 自命中」：Render.Post 画，MouseButtonPressed.Pre 判命中。</p>
 *
 * <p>代价：按钮不进 Tab 焦点链。</p>
 */
@EventBusSubscriber(modid = CmsBlueprints.MODID, value = Dist.CLIENT)
public final class TableButtonHook {

    private static final int SIZE = 16;
    private static final int GAP = 2;
    /**
     * 面板右上角外側一行（Create 自己的「保存」按钮上方，红圈位置）。
     * Create 的外部按钮列在面板右缘 +4px 处：保存按钮 @(leftPos+214+2, topPos+2)，
     * 下方是刷新 @(…, topPos+42)。我们的三个按钮排在它正上方，即 topPos-16 一行，
     * 最右一格对齐用户指定的位置，其余向左排开（读序：CMS → MCS → 处理）。
     */
    private static final int RIGHT_X = 214 - 2;
    private static final int OFF_Y = 2 - SIZE - 2;

    /** 三个入口：图标用 Create 的 AllIcons 字段，取不到就画字母。 */
    private static final Entry[] ENTRIES = {
            new Entry("CMS", "I_SCHEMATIC", "CMS 蓝图库"),
            new Entry("M", "I_OPEN_FOLDER", "MCS 蓝图站"),
            new Entry("处", "I_REFRESH", "蓝图处理（转 nbt / 分割）"),
    };

    private record Entry(String glyph, String icon, String tip) {
    }

    private TableButtonHook() {
    }

    private static SchematicTableScreen asTable(Screen screen) {
        return screen instanceof SchematicTableScreen t ? t : null;
    }

    private static boolean hovering(double mx, double my, int bx, int by) {
        return mx >= bx && mx < bx + SIZE && my >= by && my < by + SIZE;
    }

    /** 第 i 格按钮的左上角（相对面板原点）。 */
    private static int slotX(int i) {
        return RIGHT_X - (ENTRIES.length - 1 - i) * (SIZE + GAP);
    }

    private static Screen open(int i, Screen parent) {
        return switch (i) {
            case 0 -> new BrowseScreen(parent, new CmsSite());
            case 1 -> new BrowseScreen(parent, new McsSite());
            default -> new ToolScreen(parent);
        };
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        SchematicTableScreen table = asTable(screen);
        if (table == null) {
            return;
        }
        int[] origin = CreateBridge.origin(table);
        GuiGraphics g = event.getGuiGraphics();
        CreateUi.probeIcons();

        for (int i = 0; i < ENTRIES.length; i++) {
            int bx = origin[0] + slotX(i);
            int by = origin[1] + OFF_Y;
            boolean hover = hovering(event.getMouseX(), event.getMouseY(), bx, by);

            // Create 原生贴图：槽位底 + 图标，与界面浑然一体
            AllGuiTextures.SCHEMATIC_SLOT.render(g, bx, by);
            if (hover) {
                CreateUi.frame(g, bx, by, SIZE, SIZE, 0xFFFFFFFF);
            }
            Object icon = CreateUi.icon(ENTRIES[i].icon());
            if (icon instanceof net.createmod.catnip.gui.element.ScreenElement se) {
                se.render(g, bx, by);
            } else {
                String glyph = ENTRIES[i].glyph();
                g.drawString(Minecraft.getInstance().font, glyph,
                        bx + (SIZE - Minecraft.getInstance().font.width(glyph)) / 2, by + 4,
                        hover ? 0xFF7EC8E3 : 0xFFDEDEDE, false);
            }
            if (hover) {
                String tip = ENTRIES[i].tip();
                int tw = Minecraft.getInstance().font.width(tip);
                int tx = Math.min(bx + SIZE / 2 - tw / 2, event.getScreen().width - tw - 2);
                g.drawString(Minecraft.getInstance().font, tip, Math.max(2, tx), by - 12, 0xFFFFFFFF, true);
            }
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        SchematicTableScreen table = asTable(screen);
        if (table == null || event.getButton() != 0) {
            return;
        }
        int[] origin = CreateBridge.origin(table);
        for (int i = 0; i < ENTRIES.length; i++) {
            if (hovering(event.getMouseX(), event.getMouseY(), origin[0] + slotX(i), origin[1] + OFF_Y)) {
                event.setCanceled(true);
                Minecraft.getInstance().setScreen(open(i, screen));
                return;
            }
        }
    }

    /** 客户端命令：任何界面都能打开，方便测试与快捷键绑定。 */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("cms")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new BrowseScreen(mc.screen, new CmsSite())));
                            return 1;
                        }));
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("mcs")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new BrowseScreen(mc.screen, new McsSite())));
                            return 1;
                        }));
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("bptool")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new ToolScreen(mc.screen)));
                            return 1;
                        }));
    }
}
