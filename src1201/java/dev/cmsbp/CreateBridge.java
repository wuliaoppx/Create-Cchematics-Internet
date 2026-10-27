package dev.cmsbp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.simibubi.create.foundation.utility.CreatePaths;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 与机械动力（Create）之间的唯一桥：落盘目录、刷新图纸桌、上传载入。
 * 全部调用点都用 try/catch 包住，Create 版本不匹配时只降级、不崩溃。
 */
public final class CreateBridge {

    private static Field leftPosField;
    private static Field topPosField;

    private CreateBridge() {
    }

    /** 蓝图目录：优先用 Create 自己的（含自定义 gameDir 的情况），否则退回 gameDir/schematics。 */
    public static Path schematicsDir() {
        try {
            Path p = CreatePaths.SCHEMATICS_DIR;
            if (p != null) {
                return p;
            }
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("读 CreatePaths.SCHEMATICS_DIR 失败，退回 gameDir/schematics：{}", t.toString());
        }
        return FMLPaths.GAMEDIR.get().resolve("schematics");
    }

    /** 落盘成 <名字><扩展名>；扩展名缺省 .nbt，文件名冲突时加 -2、-3。 */
    public static Path save(String fileName, byte[] data) throws IOException {
        Path dir = schematicsDir();
        Files.createDirectories(dir);

        String base = sanitize(stripExt(fileName));
        String ext = extOf(fileName);
        Path target = dir.resolve(base + ext);
        int n = 2;
        while (Files.exists(target)) {
            target = dir.resolve(base + "-" + (n++) + ext);
        }
        Files.write(target, data);
        CmsBlueprints.LOG.info("已保存蓝图：{}（{} 字节）", target, data.length);
        return target;
    }

    /** 兼容旧调用：{@code <postId>_<名字>.nbt}。 */
    public static Path save(int postId, String displayName, byte[] data) throws IOException {
        return save(postId + "_" + stripExt(displayName) + ".nbt", data);
    }

    /** 让 Create 把 schematics 目录里的这个文件上传到图纸桌。 */
    public static boolean upload(String fileName) {
        try {
            CreateClient.SCHEMATIC_SENDER.startNewUpload(fileName);
            CmsBlueprints.LOG.info("已请求 Create 载入：{}", fileName);
            return true;
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("Create 载入失败：{}", t.toString());
            return false;
        }
    }

    /**
     * 图纸桌的下拉列表缓存了目录内容，下载完要重建界面才会出现新条目。
     * Screen.init 是 public final（首指令 clearWidgets），等价于窗口 resize，安全。
     */
    public static void refreshTable(Screen table) {
        if (!(table instanceof SchematicTableScreen)) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            table.init(mc, table.width, table.height);
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("刷新图纸桌失败：{}", t.toString());
        }
    }

    /**
     * 1.20.1 运行时成员名是 SRG（{@code leftPos} → {@code f_97735_}），1.21.1 是官方名，
     * 所以两个都试。反射拿的是字符串，重映射工具不会翻译字符串，这里必须自己兜住。
     */
    private static final String[] LEFT_FIELDS = {"leftPos", "f_97735_"};
    private static final String[] TOP_FIELDS = {"topPos", "f_97736_"};

    private static Field findPosField(String[] names) {
        for (String name : names) {
            for (Class<?> c = AbstractContainerScreen.class; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // 换下一个候选名 / 上一层父类
                }
            }
        }
        return null;
    }

    /** 取 Create 面板左上角（leftPos/topPos 是 protected，没有 getter，只能反射）。 */
    public static int[] origin(AbstractContainerScreen<?> screen) {
        try {
            if (leftPosField == null) {
                leftPosField = findPosField(LEFT_FIELDS);
                topPosField = findPosField(TOP_FIELDS);
                if (leftPosField == null || topPosField == null) {
                    CmsBlueprints.LOG.warn("取不到面板原点（leftPos/topPos），入口按钮退到窗口右上角");
                }
            }
            if (leftPosField != null && topPosField != null) {
                return new int[]{leftPosField.getInt(screen), topPosField.getInt(screen)};
            }
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("取面板原点失败：{}", t.toString());
        }
        // 兜底：贴窗口右上角，至少看得见、点得到
        return new int[]{screen.width - 214, 20};
    }

    /**
     * 在 schematics 目录里找这个蓝图已经下载过的那份。
     *
     * <p>命名规则和 {@link #save} 一致：{@code <postId>_<名字>.nbt}，冲突时带 {@code -2}、{@code -3}。</p>
     *
     * @return 找到就返回文件路径，否则 {@code null}
     */
    public static Path find(int postId, String displayName) {
        return find(postId + "_" + stripExt(displayName) + ".nbt");
    }

    /** 按文件名找 schematics 目录里已经下载过的那个（冲突时会带 -2、-3）。 */
    public static Path find(String fileName) {
        String base = sanitize(stripExt(fileName));
        String ext = extOf(fileName);
        Path dir = schematicsDir();
        Path exact = dir.resolve(base + ext);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        for (int n = 2; n < 100; n++) {
            Path p = dir.resolve(base + "-" + n + ext);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** 支持的蓝图扩展名（顺带决定「不认识的扩展名一律当 .nbt」）。 */
    private static final String[] EXTS = {".litematic", ".schematic", ".schem", ".nbt", ".bin"};

    /** 去掉已知扩展名；不认识的扩展名视为名字的一部分。 */
    static String stripExt(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        String lower = n.toLowerCase(java.util.Locale.ROOT);
        for (String ext : EXTS) {
            if (lower.endsWith(ext)) {
                return n.substring(0, n.length() - ext.length());
            }
        }
        return n;
    }

    /** 取扩展名：认识的照抄，不认识的一律 .nbt（CMS 的文件都是结构 nbt）。 */
    static String extOf(String fileName) {
        String n = fileName == null ? "" : fileName.trim();
        String lower = n.toLowerCase(java.util.Locale.ROOT);
        for (String ext : EXTS) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return ".nbt";
    }

    private static String sanitize(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_").replaceAll("^[ .]+|[ .]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "blueprint";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    /** 给 UI 用：把字节数格式化成 KB/MB。 */
    public static String human(int bytes) {
        if (bytes >= 1 << 20) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (double) (1 << 20));
        }
        if (bytes >= 1 << 10) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / (double) (1 << 10));
        }
        return bytes + " B";
    }

    /** 校验 NBT 文件头（gzip）。 */
    public static boolean looksLikeGzip(byte[] d) {
        return dev.cmsbp.net.CmsClient.looksLikeGzip(d);
    }

    public static String describe(Path p) {
        return p.toAbsolutePath().toString();
    }

    static {
        // 触发一次类初始化，便于在日志里尽早看到命名空间
        if (CmsBlueprints.LOG.isDebugEnabled()) {
            CmsBlueprints.LOG.debug("CreateBridge 初始化，schematics 目录 = {}", schematicsDir());
        }
        StandardCharsets.UTF_8.name();
    }
}
