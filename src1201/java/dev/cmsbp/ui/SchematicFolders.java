package dev.cmsbp.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import dev.cmsbp.CreateBridge;

/**
 * schematics 目录的子目录扫描与「上传名压平」。
 *
 * <p>照抄 塞博护目镜（Create Cyber Goggles）的 {@code SchematicFolderUtil}。
 * 关键约束：Create 的服务端 {@code ServerSchematicLoader.handleNewUpload} 只建
 * {@code uploaded/<玩家名>/} 这一层，然后直接 {@code Files.newOutputStream(playerPath.resolve(schematic))}，
 * 名字里带 {@code /} 会因为父目录不存在而抛 IOException。所以上传前必须把相对路径
 * 压平成 {@code 子目录_文件名.nbt} 这种单段名字。</p>
 */
public final class SchematicFolders {

    /** 根目录用空串表示（与护目镜一致，方便直接当相对路径 resolve）。 */
    public static final String ROOT = "";
    /**
     * 上传前为了绕开「服务端只建 uploaded/&lt;玩家&gt;/ 一层」限制而复制到根目录的临时副本前缀。
     * 这些文件对本模组的列表不可见，只给 Create 的上传链路用。
     */
    public static final String MIRROR_PREFIX = "cms_up_";

    private static String selected = ROOT;

    private SchematicFolders() {
    }

    public static synchronized String selected() {
        return selected;
    }

    /** 设置当前目录；不存在或越界就退回根目录。 */
    public static synchronized void select(String folder) {
        String normalized = normalize(folder);
        Path root = CreateBridge.schematicsDir();
        Path dir = root.resolve(normalized).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            selected = ROOT;
            return;
        }
        selected = normalized;
    }

    /** 当前目录的绝对路径；异常时退回根目录。 */
    public static synchronized Path directory() {
        Path root = CreateBridge.schematicsDir();
        Path dir = root.resolve(selected).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            selected = ROOT;
            return root;
        }
        return dir;
    }

    /**
     * 列出所有「直接含 .nbt」的子目录，首元素固定是根目录（空串）。
     * 排除 {@code uploaded/}——那是 Create 往服务端传的暂存区，不是给用户选的。
     */
    public static List<String> listSelectable() {
        List<String> out = new ArrayList<>();
        out.add(ROOT);
        Path root = CreateBridge.schematicsDir();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .filter(SchematicFolders::hasDirectSchematic)
                    .map(root::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(p -> !p.equalsIgnoreCase("uploaded") && !p.startsWith("uploaded/"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(out::add);
        } catch (IOException e) {
            // 扫不到就只给根目录，不影响主流程
        }
        return out;
    }

    private static boolean hasDirectSchematic(Path folder) {
        try (Stream<Path> list = Files.list(folder)) {
            return list.anyMatch(p -> !Files.isDirectory(p)
                    && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".nbt")
                    && !isUploadMirror(p.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    /** 上传用的临时副本不该出现在用户能看到的列表里。 */
    public static boolean isUploadMirror(String fileName) {
        return fileName.startsWith(MIRROR_PREFIX) || fileName.startsWith("[sub] ");
    }

    /** 当前目录下的 .nbt 文件名（相对 schematics 根，形如 {@code sub/a.nbt}）。 */
    public static List<String> listSchematicNames() {
        List<String> out = new ArrayList<>();
        Path root = CreateBridge.schematicsDir();
        Path dir = directory();
        if (root == null) {
            return out;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".nbt"))
                    .filter(p -> !isUploadMirror(p.getFileName().toString()))
                    .map(root::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .sorted(NATURAL)
                    .forEach(out::add);
        } catch (IOException e) {
            // 目录可能刚好被删
        }
        return out;
    }

    /** 当前目录里第 index 个文件的相对路径；越界返回 null。 */
    public static String nameAt(int index) {
        List<String> names = listSchematicNames();
        return index >= 0 && index < names.size() ? names.get(index) : null;
    }

    private static String normalize(String folder) {
        if (folder == null) {
            return ROOT;
        }
        String normalized = folder.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.equals(".")) {
            return ROOT;
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /** 「1、2、10」这种按数字大小排，而不是字典序。 */
    private static final Comparator<String> NATURAL = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            boolean da = ca >= '0' && ca <= '9';
            boolean db = cb >= '0' && cb <= '9';
            if (da && db) {
                int si = i;
                int sj = j;
                while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') {
                    i++;
                }
                while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') {
                    j++;
                }
                String na = a.substring(si, i).replaceFirst("^0+(?=\\d)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=\\d)", "");
                if (na.length() != nb.length()) {
                    return na.length() - nb.length();
                }
                int cmp = na.compareTo(nb);
                if (cmp != 0) {
                    return cmp;
                }
                continue;
            }
            if (ca != cb) {
                return ca - cb;
            }
            i++;
            j++;
        }
        return (a.length() - i) - (b.length() - j);
    };
}
