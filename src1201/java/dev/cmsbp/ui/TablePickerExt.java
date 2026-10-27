package dev.cmsbp.ui;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.table.SchematicTableMenu;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;

import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.CreateBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 给 Create 的蓝图桌加上「子目录选择」和「3D 预览」。
 *
 * <p>做法逐项对照 塞博护目镜（Create Cyber Goggles）：它有 mixin，我们只有 Forge 事件，
 * 所以把 mixin 的注入点换成等价事件——
 * <ul>
 *   <li>{@code init} RETURN → {@link ScreenEvent.Init.Post}</li>
 *   <li>{@code renderBg} TAIL → {@link ContainerScreenEvent.Render.Background}</li>
 *   <li>{@code containerTick} TAIL → 在每帧渲染里顺手纠正可见性</li>
 *   <li>{@code ClientSchematicLoader#refresh} HEAD cancel → 不拦截，改为自己维护文件列表，
 *       并改写确认按钮的回调，绕开 Create 的「把列表项当文件名直接上传」路径</li>
 * </ul>
 *
 * <p>为什么必须绕开 Create 的上传：服务端 {@code ServerSchematicLoader} 只会创建
 * {@code uploaded/<玩家名>/} 一层，然后直接往 {@code <那一层>/<名字>} 写文件；
 * 名字里带 {@code /} 就会因为父目录不存在而失败。所以上传前把相对路径压平成
 * {@code cms_up_子目录_文件名.nbt} 的根目录副本，再把这份副本交给 Create 上传。</p>
 */
@EventBusSubscriber(modid = CmsBlueprints.MODID, value = Dist.CLIENT)
public final class TablePickerExt {

    /** 预览框与已占用区域的间距。 */
    private static final int GAP = 4;
    /** 屏幕边距。 */
    private static final int MARGIN = 6;
    /** 预览框最小可用边长，再小就没有意义。 */
    private static final int MIN_SIDE = 60;
    /** 预览框期望尺寸。 */
    private static final int PREFERRED_W = 190;
    private static final int PREFERRED_H = 190;
    /** 上传副本的存活上限（毫秒），避免残留文件越攒越多。 */
    private static final long MIRROR_TTL_MS = 5 * 60 * 1000L;

    private static final SchematicPreviewPanel PANEL = new SchematicPreviewPanel();

    /** 当前目录的文件夹列表（首元素是空串 = 根目录）。 */
    private static List<String> folders = List.of();
    /** 当前目录的蓝图（相对 schematics 根的路径），与选择器索引一一对应。 */
    private static final List<String> files = new ArrayList<>();

    private static SelectionScrollInput folderArea;
    private static Label folderLabel;
    private static IconButton folderPickerButton;
    private static Rect2i previewArea;
    private static SchematicTableScreen active;

    private TablePickerExt() {
    }

    // ------------------------------------------------------------------ 挂载

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SchematicTableScreen table)) {
            return;
        }
        active = table;
        try {
            install(table, event);
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("蓝图桌扩展初始化失败：{}", t.toString());
        }
    }

    private static void install(SchematicTableScreen table, ScreenEvent.Init.Post event) {
        int[] origin = CreateBridge.origin(table);
        int x = origin[0];
        int y = origin[1] + 2;

        pruneMirrors();

        folders = SchematicFolders.listSelectable();
        List<Component> folderOptions = folderOptions();

        folderLabel = new Label(x + 51, y + 26, CommonComponents.EMPTY).withShadow();
        int selectedIndex = Math.max(0, folders.indexOf(SchematicFolders.selected()));
        folderArea = new SelectionScrollInput(x + 45, y + 21, 139, 18);
        folderArea.forOptions(folderOptions);
        folderArea.titled(Component.literal("选择文件夹"));
        folderArea.writingTo(folderLabel);
        folderArea.calling(state -> {
            if (state < 0 || state >= folders.size()) {
                return;
            }
            SchematicFolders.select(folders.get(state));
            rebuildFileList(table);
        });
        folderArea.setState(selectedIndex);
        folderArea.onChanged();
        setFolderPickerVisible(false);
        event.addListener(folderArea);
        event.addListener(folderLabel);

        IconButton folderButton = (IconButton) field(table, "folderButton");
        if (folderButton != null) {
            folderPickerButton = new IconButton(folderButton.getX() - 19, folderButton.getY(),
                    AllIcons.I_VIEW_SCHEDULE);
            folderPickerButton.withCallback(() -> setFolderPickerVisible(!folderArea.visible));
            folderPickerButton.setToolTip(Component.literal("选择文件夹"));
            event.addListener(folderPickerButton);
        }

        // 覆盖两个按钮的回调：Create 的版本直接拿列表项当文件名上传，选子目录时会失败
        IconButton refreshButton = (IconButton) field(table, "refreshButton");
        if (refreshButton != null) {
            refreshButton.withCallback(() -> refreshAll(table));
        }
        IconButton confirmButton = (IconButton) field(table, "confirmButton");
        if (confirmButton != null) {
            confirmButton.withCallback(() -> confirm(table));
        }

        rebuildFileList(table);
        CmsBlueprints.LOG.info("蓝图桌扩展已挂载：{} 个文件夹，{} 个蓝图（当前目录 \"{}\"）",
                folders.size(), files.size(), SchematicFolders.selected());
    }

    private static List<Component> folderOptions() {
        List<Component> out = new ArrayList<>(folders.size());
        for (String folder : folders) {
            out.add(folder.isEmpty() ? Component.literal("（根目录）") : Component.literal(folder));
        }
        return out;
    }

    // -------------------------------------------------------------- 列表重建

    /** 按当前文件夹重建文件选择器。 */
    private static void rebuildFileList(SchematicTableScreen table) {
        files.clear();
        files.addAll(SchematicFolders.listSchematicNames());

        ScrollInput area = (ScrollInput) field(table, "schematicsArea");
        Label label = (Label) field(table, "schematicsLabel");
        List<Component> display = new ArrayList<>(files.size());
        for (String f : files) {
            display.add(Component.literal(fileName(f)));
        }

        if (files.isEmpty()) {
            if (area != null) {
                area.visible = false;
                area.active = false;
            }
            if (label != null) {
                label.text = CommonComponents.EMPTY;
            }
            return;
        }

        if (area instanceof SelectionScrollInput selection) {
            selection.forOptions(display);
            selection.setState(0);
            selection.onChanged();
            area.visible = true;
            area.active = true;
            return;
        }

        // 打开界面时目录是空的（Create 压根没建选择器），现在有文件了 → 自己造一个补上
        if (area != null) {
            removeWidget(table, area);
        }
        if (label == null) {
            return;
        }
        MutableComponent title = titleOf(table);
        SelectionScrollInput fresh = new SelectionScrollInput(
                CreateBridge.origin(table)[0] + 45, CreateBridge.origin(table)[1] + 23, 139, 18);
        fresh.forOptions(display);
        fresh.titled(title);
        fresh.writingTo(label);
        fresh.setState(0);
        fresh.onChanged();
        setField(table, "schematicsArea", fresh);
        addWidget(table, fresh);
        label.visible = true;
        if (!isRegistered(table, label)) {
            addWidget(table, label);
        }
    }

    private static MutableComponent titleOf(SchematicTableScreen table) {
        Object title = field(table, "availableSchematicsTitle");
        return title instanceof Component c ? c.plainCopy() : Component.literal("蓝图");
    }

    /** 重新扫描文件夹 + 重列文件（对应 Create 的刷新按钮）。 */
    private static void refreshAll(SchematicTableScreen table) {
        folders = SchematicFolders.listSelectable();
        String selected = SchematicFolders.selected();
        if (!selected.isEmpty() && !folders.contains(selected)) {
            SchematicFolders.select(SchematicFolders.ROOT);
            selected = SchematicFolders.ROOT;
        }
        if (folderArea != null) {
            folderArea.forOptions(folderOptions());
            folderArea.setState(Math.max(0, folders.indexOf(selected)));
            folderArea.onChanged();
        }
        rebuildFileList(table);
        if (folderArea != null && folderArea.visible) {
            setFolderPickerVisible(true);
        }
    }

    /** 显示目录下拉时把 Create 自己的文件下拉藏起来，反之亦然。 */
    private static void setFolderPickerVisible(boolean visible) {
        if (folderArea != null) {
            folderArea.visible = visible;
            folderArea.active = visible;
        }
        if (folderLabel != null) {
            folderLabel.visible = visible;
        }
        if (folderPickerButton != null) {
            folderPickerButton.green = visible;
        }
        SchematicTableScreen table = active;
        if (table == null) {
            return;
        }
        ScrollInput area = (ScrollInput) field(table, "schematicsArea");
        if (area != null) {
            area.visible = !visible;
            area.active = !visible;
        }
        Label label = (Label) field(table, "schematicsLabel");
        if (label != null) {
            label.visible = !visible;
        }
    }

    // -------------------------------------------------------------- 上传

    /** 确认按钮：把相对路径压平成根目录副本，再交给 Create 上传。 */
    private static void confirm(SchematicTableScreen table) {
        SchematicTableMenu menu = table.getMenu();
        if (menu == null || !menu.canWrite()) {
            return;
        }
        String rel = currentFile();
        if (rel == null) {
            return;
        }
        String uploadName = mirrorForUpload(rel);
        if (uploadName == null) {
            return;
        }
        try {
            CreateClient.SCHEMATIC_SENDER.startNewUpload(uploadName);
            CmsBlueprints.LOG.info("上传蓝图：{} → {}", rel, uploadName);
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("上传蓝图失败 {}：{}", rel, t.toString());
        }
    }

    /** 把 {@code sub/a.nbt} 复制成根目录的 {@code cms_up_sub_a.nbt}，返回可直接上传的名字。 */
    private static String mirrorForUpload(String relPath) {
        Path root = CreateBridge.schematicsDir();
        if (root == null) {
            return null;
        }
        Path source = root.resolve(relPath).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            CmsBlueprints.LOG.warn("要上传的蓝图不存在：{}", source);
            return null;
        }
        String name = SchematicFolders.MIRROR_PREFIX + relPath.replace('\\', '/').replace('/', '_');
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".nbt")) {
            name = name + ".nbt";
        }
        Path target = root.resolve(name);
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return name;
        } catch (IOException e) {
            CmsBlueprints.LOG.warn("准备上传副本失败 {}：{}", name, e.toString());
            return null;
        }
    }

    /** 清掉上次留下的上传副本（上传只要几百毫秒，5 分钟前的必然已经结束）。 */
    private static void pruneMirrors() {
        Path root = CreateBridge.schematicsDir();
        if (root == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Stream<Path> list = Files.list(root)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (!SchematicFolders.isUploadMirror(name)) {
                    continue;
                }
                try {
                    if (now - Files.getLastModifiedTime(p).toMillis() < MIRROR_TTL_MS) {
                        continue;
                    }
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 正在上传就被占用着，下次再删
                }
            }
        } catch (IOException e) {
            CmsBlueprints.LOG.warn("清理上传副本失败：{}", e.toString());
        }
    }

    // -------------------------------------------------------------- 预览

    @SubscribeEvent
    public static void onRenderBackground(ContainerScreenEvent.Render.Background event) {
        if (!(event.getContainerScreen() instanceof SchematicTableScreen table)) {
            return;
        }
        active = table;
        GuiGraphics graphics = event.getGuiGraphics();

        // Create 的 containerTick 会把文件下拉重新设为可见，这里每帧纠正一次
        if (folderArea != null && folderArea.visible) {
            ScrollInput area = (ScrollInput) field(table, "schematicsArea");
            if (area != null) {
                area.visible = false;
            }
            Label label = (Label) field(table, "schematicsLabel");
            if (label != null) {
                label.visible = false;
            }
        }

        previewArea = calculateArea(table);
        if (previewArea == null) {
            return;
        }
        PANEL.setSelected(currentFile());

        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        PANEL.updateMouse(event.getMouseX(), event.getMouseY(), leftDown);
        PANEL.render(graphics, previewArea.getX(), previewArea.getY(),
                previewArea.getWidth(), previewArea.getHeight());
    }

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof SchematicTableScreen)) {
            return;
        }
        if (previewArea == null || !PANEL.isMouseOver(event.getMouseX(), event.getMouseY())) {
            return;
        }
        PANEL.onScroll(event.getScrollDelta());
        event.setCanceled(true);
    }

    /**
     * 预览框位置：优先贴在蓝图桌左侧（护目镜也是这个策略），左边不够再退到面板上方。
     */
    private static Rect2i calculateArea(SchematicTableScreen table) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int[] origin = CreateBridge.origin(table);
        int leftPos = origin[0];
        int topPos = origin[1];
        int bgW = AllGuiTextures.SCHEMATIC_TABLE.getWidth();
        int bgH = AllGuiTextures.SCHEMATIC_TABLE.getHeight();

        int occupiedLeft = leftPos;
        int occupiedTop = topPos;
        int occupiedRight = leftPos + bgW;
        int occupiedBottom = topPos + bgH + 4 + AllGuiTextures.PLAYER_INVENTORY.getHeight();
        for (Rect2i area : table.getExtraAreas()) {
            occupiedLeft = Math.min(occupiedLeft, area.getX());
            occupiedTop = Math.min(occupiedTop, area.getY());
            occupiedRight = Math.max(occupiedRight, area.getX() + area.getWidth());
            occupiedBottom = Math.max(occupiedBottom, area.getY() + area.getHeight());
        }

        int leftRoom = Math.max(0, occupiedLeft - MARGIN - GAP);
        int aboveRoom = Math.max(0, occupiedTop - MARGIN - GAP);

        if (leftRoom >= MIN_SIDE) {
            int w = Math.min(PREFERRED_W, leftRoom);
            int h = Math.min(PREFERRED_H, screenH - 2 * MARGIN);
            int px = occupiedLeft - GAP - w;
            int py = Mth.clamp(topPos, MARGIN, Math.max(MARGIN, screenH - MARGIN - h));
            return new Rect2i(px, py, w, h);
        }
        if (aboveRoom >= MIN_SIDE) {
            int w = Math.min(screenW - 2 * MARGIN, PREFERRED_W + 14);
            int h = Math.min(aboveRoom, PREFERRED_H);
            int px = Mth.clamp((occupiedLeft + occupiedRight - w) / 2, MARGIN,
                    Math.max(MARGIN, screenW - MARGIN - w));
            int py = occupiedTop - GAP - h;
            return new Rect2i(px, py, w, h);
        }
        return null;
    }

    /** 选择器当前指向的相对路径；没有选中或索引越界返回 null。 */
    public static String currentFile() {
        SchematicTableScreen table = active;
        if (table == null || files.isEmpty()) {
            return null;
        }
        ScrollInput area = (ScrollInput) field(table, "schematicsArea");
        if (area == null) {
            return null;
        }
        int index = area.getState();
        return index >= 0 && index < files.size() ? files.get(index) : null;
    }

    private static String fileName(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash >= 0 ? relPath.substring(slash + 1) : relPath;
    }

    // -------------------------------------------------------------- 反射工具

    private static Object field(Object owner, String... names) {
        for (String name : names) {
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(owner);
                } catch (NoSuchFieldException ignored) {
                    // 换下一个候选名 / 上一层父类
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void setField(Object owner, String name, Object value) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(owner, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往上找
            } catch (Throwable t) {
                return;
            }
        }
    }

    /** 1.20.1 运行时是 SRG 名，两个都试。 */
    private static void addWidget(Screen screen, GuiEventListener widget) {
        invokeScreen(screen, new String[]{"addRenderableWidget", "m_142416_"}, widget);
    }

    private static void removeWidget(Screen screen, GuiEventListener widget) {
        invokeScreen(screen, new String[]{"removeWidget", "m_169411_"}, widget);
    }

    private static void invokeScreen(Screen screen, String[] names, GuiEventListener widget) {
        for (String name : names) {
            for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, GuiEventListener.class);
                    m.setAccessible(true);
                    m.invoke(screen, widget);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // 换下一个候选名 / 上一层父类
                } catch (Throwable t) {
                    CmsBlueprints.LOG.warn("调用 Screen#{} 失败：{}", name, t.toString());
                    return;
                }
            }
        }
        CmsBlueprints.LOG.warn("找不到 Screen#{}，widget 无法增删", String.join("/", names));
    }

    private static boolean isRegistered(Screen screen, GuiEventListener widget) {
        for (GuiEventListener child : screen.children()) {
            if (child == widget) {
                return true;
            }
        }
        return false;
    }
}
