package dev.cmsbp.ui;

import dev.cmsbp.CreateBridge;
import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.net.Net;
import dev.cmsbp.schem.Convert;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 蓝图处理：把 schematics 目录（含子目录）里的 .litematic / .schem 转成 Create 能读的结构 .nbt，或按尺寸切块。
 *
 * <p>只做「转进机械动力」这一个方向；分割对任意支持的格式都可用，产物同样是 .nbt。</p>
 *
 * <p>分割会把产物写进以源文件命名的子目录，所以列表支持进入子目录：点目录行即进入，首行「../」或
 * Backspace 返回上级。</p>
 */
public class ToolScreen extends Screen {

    private static final int ROW_H = 12;
    private static final int ROWS = 9;
    private static final String[] SUPPORTED = {".nbt", ".litematic", ".schem", ".schematic"};

    /** 列表里的一项：目录、可转换文件，或返回上级的伪条目。 */
    private record Item(Path path, boolean dir, boolean up) {
    }

    private final Screen parent;
    private final List<Item> items = new ArrayList<>();
    private Path dir;
    private int selected = -1;
    private int offset;
    private boolean busy;
    private String status = "";
    private boolean statusError;

    public ToolScreen(Screen parent) {
        super(Component.literal("蓝图处理"));
        this.parent = parent;
    }

    private int x0() {
        return (this.width - CreateUi.W) / 2;
    }

    private int y0() {
        return (this.height - CreateUi.H) / 2;
    }

    @Override
    protected void init() {
        this.reload();
    }

    private static boolean supported(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void reload() {
        Path root = CreateBridge.schematicsDir();
        if (this.dir == null || !this.dir.startsWith(root)) {
            this.dir = root;
        }
        this.items.clear();
        if (!this.dir.equals(root)) {
            Path up = this.dir.getParent();
            if (up == null || !up.startsWith(root)) {
                up = root;
            }
            this.items.add(new Item(up, true, true));
        }
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(this.dir)) {
            try (Stream<Path> s = Files.list(this.dir)) {
                s.forEach(p -> {
                    String n = p.getFileName().toString();
                    if (n.startsWith(".")) {
                        return;
                    }
                    if (Files.isDirectory(p)) {
                        dirs.add(p);
                    } else if (supported(p)) {
                        files.add(p);
                    }
                });
            } catch (Exception e) {
                this.setStatus("读目录失败：" + shortMsg(e), true);
            }
        }
        dirs.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        files.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        for (Path p : dirs) {
            this.items.add(new Item(p, true, false));
        }
        for (Path p : files) {
            this.items.add(new Item(p, false, false));
        }
        this.offset = Math.min(this.offset, Math.max(0, this.items.size() - ROWS));
        int firstReal = (!this.items.isEmpty() && this.items.get(0).up()) ? 1 : 0;
        this.selected = this.items.size() > firstReal ? firstReal : (this.items.isEmpty() ? -1 : 0);
        this.offset = Math.max(0, Math.min(this.offset, this.selected - ROWS + 1));
        if (this.items.isEmpty()) {
            this.offset = 0;
        }
    }

    private void setStatus(String text, boolean error) {
        this.status = text;
        this.statusError = error;
    }

    /** 当前目录相对 schematics 根的位置，形如 "刷金机/" 或 ""（根目录）。 */
    private String relDir() {
        Path root = CreateBridge.schematicsDir();
        try {
            String rel = root.relativize(this.dir).toString().replace('\\', '/');
            return rel.isEmpty() ? "" : rel + "/";
        } catch (Exception e) {
            return "";
        }
    }

    /** 扩展名 → 格式标签（不读文件内容，纯看后缀，避免渲染线程上做大 IO）。 */
    private static String kind(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".nbt")) {
            return "Create";
        }
        if (n.endsWith(".litematic")) {
            return "Litematica";
        }
        if (n.endsWith(".schem")) {
            return "WorldEdit";
        }
        if (n.endsWith(".schematic")) {
            return "MCEdit";
        }
        return "?";
    }

    private static String size(Path p) {
        try {
            return CreateBridge.human((int) Files.size(p));
        } catch (Exception e) {
            return "?";
        }
    }

    private Item selectedItem() {
        if (this.selected < 0 || this.selected >= this.items.size()) {
            return null;
        }
        return this.items.get(this.selected);
    }

    private Item selectedFile() {
        Item it = this.selectedItem();
        return (it == null || it.dir()) ? null : it;
    }

    private void convert() {
        if (this.busy) {
            return;
        }
        Item it = this.selectedFile();
        if (it == null) {
            setStatus("先在列表里选一个文件（目录要先点进去）", true);
            return;
        }
        Path src = it.path();
        this.busy = true;
        setStatus("正在转换 " + src.getFileName() + "…", false);
        Net.run(() -> {
            try {
                Convert.Result r = Convert.toNbt(src);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.busy = false;
                    this.setStatus(r.message(), false);
                    this.reload();
                });
            } catch (Exception e) {
                CmsBlueprints.LOG.warn("转换失败：{}", e.toString());
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().screen != this) {
                        return;
                    }
                    this.busy = false;
                    this.setStatus("转换失败：" + shortMsg(e), true);
                });
            }
        });
    }

    /** 打开分割设置页（选切割方式与数量）。 */
    private void openSplit() {
        Item it = this.selectedFile();
        if (it == null) {
            setStatus("先在列表里选一个文件（目录要先点进去）", true);
            return;
        }
        Minecraft.getInstance().setScreen(new SplitScreen(it.path(), this));
    }

    private void enter(Path target) {
        this.dir = target;
        this.selected = -1;
        this.offset = 0;
        setStatus("", false);
        this.reload();
    }

    private void goUp() {
        Path root = CreateBridge.schematicsDir();
        Path up = this.dir.getParent();
        if (up == null || !up.startsWith(root)) {
            up = root;
        }
        if (!up.equals(this.dir)) {
            enter(up);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x99101010);
        int x = x0();
        int y = y0();
        CreateUi.panel(g, x, y);
        CreateUi.headerText(g, this.font, "蓝图处理   转进机械动力", x, y + 4, CreateUi.W, CreateUi.C_TITLE);

        // 列表
        int rowY = y + 22;
        int listH = ROWS * ROW_H;
        g.fill(x + 4, rowY - 3, x + 218, rowY + listH - 1, 0xFF2E2E38);
        CreateUi.frame(g, x + 4, rowY - 3, 214, listH + 2, 0xFF24242C);
        for (int i = 0; i < ROWS; i++) {
            int idx = this.offset + i;
            if (idx >= this.items.size()) {
                break;
            }
            Item it = this.items.get(idx);
            int ry = rowY + i * ROW_H;
            boolean sel = idx == this.selected;
            boolean hover = mouseX >= x + 6 && mouseX < x + 218 && mouseY >= ry && mouseY < ry + ROW_H - 1;
            CreateUi.rowHighlight(g, x + 6, ry, 218 - 12, ROW_H - 1, sel, hover);
            if (sel) {
                g.fill(x + 4, ry, x + 6, ry + ROW_H - 1, CreateUi.C_ACCENT);
            }
            String name;
            String tail;
            int tailColor;
            if (it.up()) {
                name = "../";
                tail = "上级";
                tailColor = CreateUi.C_DIM;
            } else if (it.dir()) {
                name = it.path().getFileName() + "/";
                tail = "目录";
                tailColor = CreateUi.C_ACCENT;
            } else {
                name = it.path().getFileName().toString();
                tail = kind(it.path()) + "  " + size(it.path());
                tailColor = sel ? CreateUi.C_ACCENT : CreateUi.C_DIM;
            }
            int tailW = this.font.width(tail);
            g.drawString(this.font, CreateUi.clip(this.font, name, 200 - tailW),
                    x + 12, ry + 2, sel ? 0xFFFFFFFF : CreateUi.C_TEXT, false);
            g.drawString(this.font, tail, x + 214 - tailW, ry + 2,
                    sel ? CreateUi.C_ACCENT : tailColor, false);
        }
        if (this.items.isEmpty()) {
            g.drawString(this.font, "（空目录）", x + 10, rowY + 2, CreateUi.C_DIM, false);
        }

        // 状态行：优先显示错误/进度，否则显示当前路径与条目数
        String line = this.status;
        if (line.isEmpty()) {
            String rel = relDir();
            line = (rel.isEmpty() ? "schematics/" : "schematics/" + rel) + "  ·  " + this.items.size() + " 项";
        }
        g.drawString(this.font, CreateUi.clip(this.font, line, CreateUi.W - 16), x + 8, y + 178,
                this.statusError ? CreateUi.C_ERR : CreateUi.C_DIM, false);

        // 底条
        g.fill(x + 4, y + 194, x + 218, y + 216, 0xFF3A3A44);
        CreateUi.frame(g, x + 4, y + 194, 214, 22, 0xFF6A6A76);
        boolean hasFile = selectedFile() != null;
        CreateUi.button(g, this.font, "转nbt", x + 8, y + 197, 48, 16, mouseX, mouseY, !this.busy && hasFile);
        CreateUi.button(g, this.font, "分割", x + 60, y + 197, 48, 16, mouseX, mouseY, !this.busy && hasFile);
        CreateUi.button(g, this.font, "上级", x + 112, y + 197, 42, 16, mouseX, mouseY,
                !this.dir.equals(CreateBridge.schematicsDir()));
        CreateUi.button(g, this.font, "关闭", x + 170, y + 197, 42, 16, mouseX, mouseY, true);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = x0();
        int y = y0();
        int rowY = y + 22;
        for (int i = 0; i < ROWS; i++) {
            int idx = this.offset + i;
            if (idx >= this.items.size()) {
                break;
            }
            if (in(mx, my, x + 6, rowY + i * ROW_H, 218 - 12, ROW_H - 1)) {
                this.selected = idx;
                Item it = this.items.get(idx);
                if (it.up()) {
                    enter(it.path());
                } else if (it.dir()) {
                    enter(it.path());
                }
                return true;
            }
        }
        if (in(mx, my, x + 8, y + 197, 48, 16)) {
            this.convert();
            return true;
        }
        if (in(mx, my, x + 60, y + 197, 48, 16)) {
            this.openSplit();
            return true;
        }
        if (in(mx, my, x + 112, y + 197, 42, 16)) {
            if (!this.dir.equals(CreateBridge.schematicsDir())) {
                this.goUp();
            }
            return true;
        }
        if (in(mx, my, x + 170, y + 197, 42, 16)) {
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (this.items.size() > ROWS) {
            this.offset = Math.max(0, Math.min(this.offset - (int) Math.signum(scrollY),
                    this.items.size() - ROWS));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 264 || keyCode == 265) {
            if (!this.items.isEmpty()) {
                this.selected = Math.max(0, Math.min(this.items.size() - 1,
                        this.selected + (keyCode == 264 ? 1 : -1)));
                if (this.selected < this.offset) {
                    this.offset = this.selected;
                } else if (this.selected >= this.offset + ROWS) {
                    this.offset = this.selected - ROWS + 1;
                }
            }
            return true;
        }
        if (keyCode == 259) {
            // Backspace：返回上级
            if (!this.dir.equals(CreateBridge.schematicsDir())) {
                this.goUp();
                return true;
            }
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            Item it = this.selectedItem();
            if (it != null && it.dir()) {
                enter(it.path());
            } else {
                this.convert();
            }
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

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }
}
