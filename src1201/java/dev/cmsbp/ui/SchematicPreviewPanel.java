package dev.cmsbp.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.schematics.client.SchematicRenderer;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.createmod.catnip.gui.UIRenderHelper;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.CreateBridge;

/**
 * 蓝图桌右侧的 3D 预览面板。
 *
 * <p>实现照抄 塞博护目镜（Create Cyber Goggles）1.21.1 的
 * {@code io.github.forgestove.create_cyber_goggles.core.schematic.SchematicPreviewPanel}，
 * 去掉它的配置项与 sable 子关卡兼容层，改为固定默认视角 + 本地文件直读。</p>
 *
 * <p>渲染用的是 Create 自带的 {@link SchematicRenderer}（真 GL，带光影与方块实体），
 * 而不是自己光栅化——后者既看不出形状，也不支持透明方块。</p>
 */
public final class SchematicPreviewPanel {

    private static final int CHECKER_SIZE = 10;
    private static final int CHECKER_LIGHT = -8482627;
    private static final int CHECKER_DARK = -9931603;

    /** 默认视角：斜 35° 俯视，与护目镜一致。 */
    private static final float DEFAULT_YAW = 35.0F;
    private static final float DEFAULT_PITCH = 24.0F;
    private static final float DEFAULT_ZOOM = 1.0F;
    /** 选中后等这么多毫秒再真正解析文件，避免快速翻列表时反复建渲染器。 */
    private static final int LOAD_DELAY_MS = 120;
    /** 超过这个方块数就不渲染（渲染器会把每个方块都 tessellate 一遍）。 */
    private static final long MAX_VOLUME = 500_000L;

    private String currentFile;
    private String pendingFile;
    private long pendingSince;
    private State state = State.NONE;
    private SchematicRenderer renderer;
    private SchematicLevel schematicLevel;
    private BoundingBox frame = new BoundingBox(BlockPos.ZERO);

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float zoom = DEFAULT_ZOOM;
    private boolean dragging;
    private boolean wasDown;
    private double lastMouseX;
    private double lastMouseY;

    private int lastX;
    private int lastY;
    private int lastW;
    private int lastH;

    /** 当前状态，UI 与日志用。 */
    public String status() {
        return state.label;
    }

    /** 切换预览目标；{@code relPath} 是相对 schematics 目录的路径。 */
    public void setSelected(String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            clear();
            return;
        }
        if (relPath.equals(currentFile) || relPath.equals(pendingFile)) {
            return;
        }
        pendingFile = relPath;
        pendingSince = Util.getMillis();
        state = State.LOADING;
        currentFile = null;
        renderer = null;
        schematicLevel = null;
        resetView();
    }

    public void clear() {
        currentFile = null;
        pendingFile = null;
        renderer = null;
        schematicLevel = null;
        state = State.NONE;
    }

    public void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = DEFAULT_ZOOM;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= lastX && mouseX < lastX + lastW && mouseY >= lastY && mouseY < lastY + lastH;
    }

    /** 由外层每帧传入鼠标状态；面板自己判断是否在拖拽旋转。 */
    public void updateMouse(double mouseX, double mouseY, boolean leftDown) {
        if (leftDown && !wasDown && isMouseOver(mouseX, mouseY)) {
            dragging = true;
        }
        if (!leftDown) {
            dragging = false;
        }
        if (dragging) {
            yaw += (float) (mouseX - lastMouseX);
            pitch = Mth.clamp(pitch + (float) (mouseY - lastMouseY), -90.0F, 90.0F);
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        wasDown = leftDown;
    }

    public void onScroll(double delta) {
        zoom = Mth.clamp(zoom * (delta > 0.0 ? 1.1F : 0.9090909F), 0.25F, 5.0F);
    }

    // ---------------------------------------------------------------- 渲染

    public void render(GuiGraphics graphics, int x, int y, int w, int h) {
        // 棋盘格必须整格对齐，否则边缘会被切出半格，所以先把 w/h 吸附到 10 的倍数
        int cols = Math.max(1, (w - 2) / CHECKER_SIZE);
        int rows = Math.max(1, (h - 2) / CHECKER_SIZE);
        w = cols * CHECKER_SIZE + 2;
        h = rows * CHECKER_SIZE + 2;
        lastX = x;
        lastY = y;
        lastW = w;
        lastH = h;

        tickLoad();

        int innerX = x + 1;
        int innerY = y + 1;
        int innerW = w - 2;
        int innerH = h - 2;

        drawCheckerboard(graphics, innerX, innerY, innerW, innerH);
        graphics.fill(x, y, x + w, y + 1, -1);
        graphics.fill(x, y + h - 1, x + w, y + h, -1);
        graphics.fill(x, y, x + 1, y + h, -1);
        graphics.fill(x + w - 1, y, x + w, y + h, -1);

        if (state == State.OK && renderer != null && schematicLevel != null) {
            renderPreview(graphics, innerX, innerY, innerW, innerH);
        } else {
            Minecraft mc = Minecraft.getInstance();
            Component text = Component.literal(state.label);
            graphics.drawString(mc.font, text,
                    innerX + (innerW - mc.font.width(text)) / 2,
                    innerY + innerH / 2 - 4, 0xFFFFFFFF);
        }
    }

    private void drawCheckerboard(GuiGraphics graphics, int x, int y, int w, int h) {
        int cols = (w + CHECKER_SIZE - 1) / CHECKER_SIZE;
        int rows = (h + CHECKER_SIZE - 1) / CHECKER_SIZE;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int cx = x + col * CHECKER_SIZE;
                int cy = y + row * CHECKER_SIZE;
                int cw = Math.min(CHECKER_SIZE, x + w - cx);
                int ch = Math.min(CHECKER_SIZE, y + h - cy);
                graphics.fill(cx, cy, cx + cw, cy + ch,
                        ((row + col) & 1) == 0 ? CHECKER_LIGHT : CHECKER_DARK);
            }
        }
    }

    private void renderPreview(GuiGraphics graphics, int x, int y, int w, int h) {
        float centerX = x + w / 2.0F;
        float centerY = y + h / 2.0F;
        int maxDim = Math.max(1, Math.max(frame.getXSpan(), Math.max(frame.getYSpan(), frame.getZSpan())));
        float fit = Math.min(w, h) * 0.6F;
        float scale = fit / maxDim * zoom;

        graphics.enableScissor(x, y, x + w, y + h);
        // 先把已经攒下的 UI 批次刷出去，否则会被 3D 的 z 值盖住
        graphics.flush();

        PoseStack ms = graphics.pose();
        ms.pushPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        Lighting.setupFor3DItems();

        ms.translate(centerX, centerY, 350.0F);
        ms.scale(scale, scale, scale);
        // GUI 坐标系 Y 向下，3D 是 Y 向上，翻一下
        UIRenderHelper.flipForGuiRender(ms);
        ms.mulPose(Axis.XP.rotationDegrees(pitch));
        ms.mulPose(Axis.YP.rotationDegrees(yaw));
        // 把结构包围盒的中心挪到原点
        ms.translate(-(frame.minX() + frame.maxX() + 1) / 2.0F,
                -(frame.minY() + frame.maxY() + 1) / 2.0F,
                -(frame.minZ() + frame.maxZ() + 1) / 2.0F);

        DefaultSuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        renderer.render(ms, buffer);
        buffer.draw();

        ms.popPose();
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.disableScissor();
    }

    // ---------------------------------------------------------------- 载入

    private void tickLoad() {
        if (pendingFile != null && Util.getMillis() - pendingSince >= LOAD_DELAY_MS) {
            build(pendingFile);
        }
    }

    private void build(String relPath) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        currentFile = relPath;
        pendingFile = null;
        if (level == null || mc.player == null) {
            // 没进世界时 Create 的渲染器直接 return，建了也没用
            state = State.NO_WORLD;
            return;
        }
        try {
            StructureTemplate template = loadTemplate(level, relPath);
            Vec3i size = template.getSize();
            if (size.equals(Vec3i.ZERO)) {
                state = State.EMPTY;
                return;
            }
            long volume = (long) size.getX() * size.getY() * size.getZ();
            if (volume > MAX_VOLUME) {
                state = State.TOO_LARGE;
                return;
            }
            SchematicLevel fake = new SchematicLevel(level);
            template.placeInWorld(fake, BlockPos.ZERO, BlockPos.ZERO,
                    new StructurePlaceSettings(), RandomSource.create(), 2);
            for (BlockEntity be : fake.getBlockEntities()) {
                // 结构里记录的是原世界的 level，这里换成虚拟 level，
                // 否则多方块控制器、渲染数据都会去真世界找坐标
                be.setLevel(fake);
            }
            fixControllerBlockEntities(fake);
            frame = fake.getBounds();
            renderer = new SchematicRenderer(fake);
            schematicLevel = fake;
            state = State.OK;
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("蓝图预览载入失败 {}：{}", relPath, t.toString());
            renderer = null;
            schematicLevel = null;
            state = State.FAILED;
        }
    }

    /** 直接读 schematics 目录下的相对路径（Create 的 loadSchematic 只认物品上的 Owner/File 标签）。 */
    private static StructureTemplate loadTemplate(Level level, String relPath) throws IOException {
        Path dir = CreateBridge.schematicsDir();
        Path path = dir.resolve(relPath).normalize();
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            throw new IOException("蓝图不存在：" + path);
        }
        CompoundTag nbt = NbtIo.readCompressed(Files.newInputStream(path));
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), nbt);
        return template;
    }

    /**
     * 多方块（齿轮箱、传动轴、储罐等）在结构里存的是「控制器相对上次已知位置的偏移」，
     * 贴到虚拟世界后原坐标失效，必须按新坐标重算控制器位置。逐字照抄 Create 自己的做法。
     */
    private static void fixControllerBlockEntities(SchematicLevel level) {
        for (BlockEntity be : level.getBlockEntities()) {
            if (!(be instanceof IMultiBlockEntityContainer multiBlock)) {
                continue;
            }
            BlockPos lastKnownPos = multiBlock.getLastKnownPos();
            BlockPos currentPos = be.getBlockPos();
            if (lastKnownPos == null || multiBlock.isController() || lastKnownPos.equals(currentPos)) {
                continue;
            }
            BlockPos adjusted = multiBlock.getController().offset(currentPos.subtract(lastKnownPos));
            if (multiBlock instanceof SmartBlockEntity smart) {
                smart.markVirtual();
            }
            multiBlock.setController(adjusted);
        }
    }

    private enum State {
        NONE("未选蓝图"),
        LOADING("载入中…"),
        OK(""),
        EMPTY("空蓝图"),
        TOO_LARGE("蓝图过大"),
        NO_WORLD("进世界后可预览"),
        FAILED("预览失败");

        private final String label;

        State(String label) {
            this.label = label;
        }
    }
}
