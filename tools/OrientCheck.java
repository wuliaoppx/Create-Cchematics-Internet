import dev.cmsbp.schem.Convert;
import dev.cmsbp.schem.Schematic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 朝向校验：把样本蓝本转成结构 .nbt，再读回来逐格比对方块状态字符串。
 *
 * <p>同时按「原样 / X 镜像 / Z 镜像」三种对位方式统计不一致格数，用来暴露「整轴反了」
 * 这类坐标映射错误（方块状态的 Properties 是照抄的，所以整轴反了会表现为「楼梯朝向全乱」）。</p>
 *
 * <p>用法：<code>java OrientCheck &lt;文件…&gt;</code>；不给参数就用 build 下的样本。</p>
 */
public class OrientCheck {

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        if (args.length > 0) {
            for (String a : args) {
                files.add(Path.of(a));
            }
        } else {
            files.add(Path.of("build/lm-sample.litematic"));
            files.add(Path.of("build/lmtest/68dbb5f8-dd28-46d7-82c1-835a6a4ce6e2.litematic"));
            files.add(Path.of("build/lmtest/08baa20e-264d-41d4-8205-4611029936b0.litematic"));
            files.add(Path.of("build/we-sample.schem"));
        }

        boolean ok = true;
        int ran = 0;
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                continue;
            }
            ran++;
            ok &= check(f);
        }
        if (ran == 0) {
            System.out.println("没有可用的样本文件");
            System.exit(1);
        }
        System.out.println(ok ? "\n朝向校验全部通过" : "\n朝向校验有失败项");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean check(Path src) throws Exception {
        System.out.println("\n=== " + src + " ===");
        printRegions(src);

        Schematic a = Schematic.read(src);
        System.out.printf("源：%d×%d×%d，palette %d，非空气 %d%n", a.sx, a.sy, a.sz, a.palette.size(), a.solidCount());

        Path tmpDir = Path.of("build/orient");
        Files.createDirectories(tmpDir);
        Path copy = tmpDir.resolve(src.getFileName().toString());
        Files.copy(src, copy, StandardCopyOption.REPLACE_EXISTING);

        Convert.Result r = Convert.toNbt(copy);
        Path out = r.files().get(0);
        Schematic b = Schematic.read(out);
        System.out.printf("产物：%d×%d×%d（%d 字节），palette %d，非空气 %d%n",
                b.sx, b.sy, b.sz, Files.size(out), b.palette.size(), b.solidCount());

        if (a.sx != b.sx || a.sy != b.sy || a.sz != b.sz) {
            System.out.println("  ✗ 尺寸不一致");
            return false;
        }

        int total = a.states.length;
        int same = 0;
        int flipX = 0;
        int flipZ = 0;
        for (int y = 0; y < a.sy; y++) {
            for (int z = 0; z < a.sz; z++) {
                for (int x = 0; x < a.sx; x++) {
                    String s = name(a, a.stateAt(x, y, z));
                    if (s.equals(name(b, b.stateAt(x, y, z)))) {
                        same++;
                    }
                    if (s.equals(name(b, b.stateAt(a.sx - 1 - x, y, z)))) {
                        flipX++;
                    }
                    if (s.equals(name(b, b.stateAt(x, y, a.sz - 1 - z)))) {
                        flipZ++;
                    }
                }
            }
        }
        System.out.printf("  对位一致率：原样 %.4f / X 镜像 %.4f / Z 镜像 %.4f（总格 %d）%n",
                same / (double) total, flipX / (double) total, flipZ / (double) total, total);

        int stairsA = stairs(a);
        int stairsB = stairs(b);
        System.out.printf("  楼梯/台阶类方块：源 %d，产物 %d%n", stairsA, stairsB);

        boolean pass = same == total && stairsA == stairsB;
        System.out.println(pass ? "  ✓ 方块状态逐格一致" : "  ✗ 有 " + (total - same) + " 格不一致");
        return pass;
    }

    /** 打印 litematic 的各区域 Position/Size（Size 为负是关键线索）。 */
    private static void printRegions(Path file) throws Exception {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            return;
        }
        if (!root.contains("Regions")) {
            return;
        }
        CompoundTag regions = root.getCompound("Regions");
        int negative = 0;
        for (String name : regions.getAllKeys()) {
            CompoundTag r = regions.getCompound(name);
            CompoundTag s = r.getCompound("Size");
            CompoundTag p = r.getCompound("Position");
            int sx = s.getInt("x");
            int sy = s.getInt("y");
            int sz = s.getInt("z");
            if (sx < 0 || sy < 0 || sz < 0) {
                negative++;
            }
            System.out.printf("  区域 %-14s Position(%d,%d,%d) Size(%d,%d,%d)%s%n",
                    name, p.getInt("x"), p.getInt("y"), p.getInt("z"), sx, sy, sz,
                    (sx < 0 || sy < 0 || sz < 0) ? "  ← 含负 Size" : "");
        }
        System.out.println("  含负 Size 的区域数：" + negative);
    }

    private static int stairs(Schematic s) {
        int n = 0;
        for (int st : s.states) {
            String name = s.blockName(st);
            if (name.endsWith("_stairs") || name.endsWith("_slab")) {
                n++;
            }
        }
        return n;
    }

    /** 状态 id → 规范化字符串（Name + 排序后的 Properties）。 */
    private static String name(Schematic s, int state) {
        if (state < 0 || state >= s.palette.size()) {
            return "?";
        }
        CompoundTag t = s.palette.get(state);
        String n = t.getString("Name");
        CompoundTag props = t.getCompound("Properties");
        if (props.getAllKeys().isEmpty()) {
            return n;
        }
        List<String> keys = new ArrayList<>(props.getAllKeys());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder(n).append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(keys.get(i)).append('=').append(props.getString(keys.get(i)));
        }
        return sb.append(']').toString().toLowerCase(Locale.ROOT);
    }
}
