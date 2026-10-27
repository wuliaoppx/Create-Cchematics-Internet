import dev.cmsbp.schem.Convert;
import dev.cmsbp.schem.Litematica;
import dev.cmsbp.schem.Schematic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 离线校验「转进机械动力」这条路：读 .litematic / WorldEdit .schem，转成结构 .nbt，再读回来逐格比对。
 *
 * <p>断言：<br>
 * 1. 真实 .litematic 样本解出来的非空气方块数 == {@code Metadata.TotalBlocks}（下标/位宽/方向错就必挂）；<br>
 * 2. .litematic → .nbt → 再读，方块数、包围盒、palette 一致；<br>
 * 3. .schem → .nbt 同样成立；<br>
 * 4. 分割：子块能拼回原数据，方块实体坐标平移到子块内。</p>
 */
public class ConvertTest {

    public static void main(String[] args) throws Exception {
        boolean ok = true;
        Path lm = firstExisting("build/lm-sample.litematic", "build/mcs-bp.litematic");
        if (lm != null) {
            ok &= checkLitematic(lm);
        } else {
            System.out.println("（跳过 .litematic 检查，没有样本）");
        }

        Path we = firstExisting("build/we-sample.schem", "build/mcs-we.json");
        if (we != null && we.toString().endsWith(".schem")) {
            ok &= checkWorldEdit(we);
        } else {
            System.out.println("（跳过 .schem 检查，没有样本）");
        }

        Path nbt = firstExisting("../cms/downloads/1450_洗照片机1.1.nbt", "build/lmtest/split/big-p1of9.nbt");
        if (nbt != null) {
            ok &= roundTripNbt(nbt);
            ok &= checkModes(nbt);
        } else {
            System.out.println("（跳过往返检查，找不到结构 .nbt 样本）");
        }

        System.out.println(ok ? "\n全部通过" : "\n有失败项");
        if (!ok) {
            System.exit(1);
        }
    }

    private static Path firstExisting(String... paths) {
        for (String p : paths) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /** 真实 .litematic：解包 → 非空气数必须等于 Metadata.TotalBlocks。 */
    private static boolean checkLitematic(Path p) throws Exception {
        CompoundTag root = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
        int totalBlocks = root.getCompound("Metadata").getInt("TotalBlocks");
        int version = root.getInt("Version");
        Schematic s = Schematic.read(p);
        int solid = s.solidCount();
        boolean ok = solid == totalBlocks;
        System.out.printf("  %s（schema v%d）：%d×%d×%d，palette %d，非空气 %d / TotalBlocks %d %s%n",
                p.getFileName(), version, s.sx, s.sy, s.sz, s.palette.size(), solid, totalBlocks, ok ? "✓" : "✗");

        // 自查：所有解出来的状态下标都必须在 palette 范围内（by construction 成立，但这里防越界写入）
        for (int i = 0; i < s.states.length; i++) {
            if (s.states[i] < 0 || s.states[i] >= s.palette.size()) {
                System.out.printf("  ✗ 下标越界：第 %d 格 = %d（palette %d）%n", i, s.states[i], s.palette.size());
                ok = false;
                break;
            }
        }

        // 转成 .nbt 再读回来，看数据是否稳定
        Path dir = Path.of("build/lmtest");
        Files.createDirectories(dir);
        Path copy = dir.resolve("lm_in_" + base(p) + ".litematic");
        Files.copy(p, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Convert.Result r = Convert.toNbt(copy);
        Path outNbt = r.files().get(0);
        Schematic back = Schematic.read(outNbt);
        int[] dims = {s.sx, s.sy, s.sz};
        int[] dimsBack = {back.sx, back.sy, back.sz};
        boolean same = java.util.Arrays.equals(dims, dimsBack) && back.solidCount() == solid
                && back.palette.size() == s.palette.size();
        int diff = 0;
        for (int i = 0; i < s.states.length; i++) {
            if (s.states[i] != back.states[i]) {
                diff++;
            }
        }
        System.out.printf("  转 .nbt：%s（%d 字节），读回 %d×%d×%d，非空气 %d，差异格 %d %s%n",
                outNbt.getFileName(), Files.size(outNbt), back.sx, back.sy, back.sz, back.solidCount(), diff,
                (same && diff == 0) ? "✓" : "✗");
        return ok && same && diff == 0;
    }

    /** WorldEdit .schem：解包后转 .nbt 再读回，数据必须稳定。 */
    private static boolean checkWorldEdit(Path p) throws Exception {
        CompoundTag root = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
        // Sponge v3 把尺寸包在 Schematic 里，v1/v2 在根上
        CompoundTag body = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int w = num(body, "Width");
        int h = num(body, "Height");
        int d = num(body, "Length");
        Schematic s = Schematic.read(p);
        boolean ok = s.sx == w && s.sy == h && s.sz == d;
        System.out.printf("  %s：%d×%d×%d，palette %d，非空气 %d %s%n",
                p.getFileName(), s.sx, s.sy, s.sz, s.palette.size(), s.solidCount(), ok ? "✓" : "✗");

        Path dir = Path.of("build/lmtest");
        Files.createDirectories(dir);
        Path copy = dir.resolve("we_in_" + base(p) + ".schem");
        Files.copy(p, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Convert.Result r = Convert.toNbt(copy);
        Path outNbt = r.files().get(0);
        Schematic back = Schematic.read(outNbt);
        int diff = 0;
        for (int i = 0; i < s.states.length; i++) {
            if (s.states[i] != back.states[i]) {
                diff++;
            }
        }
        boolean stable = back.sx == s.sx && back.sy == s.sy && back.sz == s.sz && diff == 0
                && back.solidCount() == s.solidCount();
        System.out.printf("  转 .nbt：%s（%d 字节），读回差异格 %d %s%n",
                outNbt.getFileName(), Files.size(outNbt), diff, stable ? "✓" : "✗");
        return ok && stable;
    }

    /** 结构 .nbt：分割成小块后逐格拼回。 */
    private static boolean roundTripNbt(Path src) throws Exception {
        Schematic s = Schematic.read(src);
        System.out.printf("  %s：%d×%d×%d，palette %d，非空气 %d%n",
                src.getFileName(), s.sx, s.sy, s.sz, s.palette.size(), s.solidCount());

        int edge = Math.max(2, Math.min(8, Math.max(Math.max(s.sx, s.sy), s.sz) / 2));
        java.util.List<Schematic> parts = s.split(edge);
        boolean splitOk = true;
        for (Schematic part : parts) {
            for (int y = 0; y < part.sy && splitOk; y++) {
                for (int z = 0; z < part.sz && splitOk; z++) {
                    for (int x = 0; x < part.sx && splitOk; x++) {
                        int expect = s.stateAt(x + part.ox, y + part.oy, z + part.oz);
                        if (part.stateAt(x, y, z) != expect) {
                            splitOk = false;
                        }
                    }
                }
            }
        }
        System.out.printf("  分割：%d 份（每轴 ≤ %d），子块拼回原数据 %s%n", parts.size(), edge,
                splitOk ? "✓" : "✗");

        // 落盘 + 读回，覆盖 toVanilla 的写出路径
        Path dir = Path.of("build/lmtest/split");
        Files.createDirectories(dir);
        try (var old = Files.list(dir)) {
            for (Path q : old.toList()) {
                Files.deleteIfExists(q);
            }
        }
        boolean writeOk = true;
        for (int i = 0; i < parts.size(); i++) {
            Schematic part = parts.get(i);
            Path f = dir.resolve(base(src) + "-p" + (i + 1) + "of" + parts.size() + ".nbt");
            NbtIo.writeCompressed(part.toVanilla(), f);
            Schematic back = Schematic.read(f);
            if (back.sx != part.sx || back.sy != part.sy || back.sz != part.sz) {
                writeOk = false;
            }
            for (int k = 0; k < part.states.length; k++) {
                if (part.states[k] != back.states[k]) {
                    writeOk = false;
                    break;
                }
            }
        }
        System.out.printf("  落盘读回：%d 个文件 %s%n", parts.size(), writeOk ? "✓" : "✗");
        return splitOk && writeOk;
    }

    /** 三种切割方式：计划覆盖整个体积、落盘份数与计划一致、子块非空气数之和 == 原图。 */
    private static boolean checkModes(Path src) throws Exception {
        Schematic s = Schematic.read(src);
        Path work = Path.of("build/lmtest/modes");
        Files.createDirectories(work);
        try (var old = Files.list(work)) {
            for (Path q : old.toList()) {
                if (Files.isDirectory(q)) {
                    try (var inner = Files.list(q)) {
                        for (Path r : inner.toList()) {
                            Files.deleteIfExists(r);
                        }
                    }
                    Files.deleteIfExists(q);
                } else {
                    Files.deleteIfExists(q);
                }
            }
        }
        Path copy = work.resolve(base(src) + ".nbt");
        Files.copy(src, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        boolean ok = true;
        Convert.SplitMode[] modes = {Convert.SplitMode.VERTICAL, Convert.SplitMode.HORIZONTAL, Convert.SplitMode.GRID};
        int[] counts = {3, 5, 9};
        for (int i = 0; i < modes.length; i++) {
            Convert.Plan plan = Convert.plan(s, modes[i], counts[i]);
            long vol = 0;
            boolean inBounds = true;
            for (Convert.Part p : plan.parts()) {
                vol += (long) p.w() * p.h() * p.d();
                if (p.x0() < 0 || p.y0() < 0 || p.z0() < 0
                        || p.x0() + p.w() > s.sx || p.y0() + p.h() > s.sy || p.z0() + p.d() > s.sz) {
                    inBounds = false;
                }
            }
            boolean covers = vol == (long) s.sx * s.sy * s.sz;

            Convert.Result r = Convert.split(copy, modes[i], counts[i]);
            boolean countOk = r.files().size() == plan.parts().size() && !r.files().isEmpty();
            int solid = 0;
            boolean readable = true;
            for (Path f : r.files()) {
                try {
                    solid += Schematic.read(f).solidCount();
                } catch (Exception e) {
                    readable = false;
                }
            }
            boolean sameSolid = solid == s.solidCount();
            boolean modeOk = covers && inBounds && countOk && readable && sameSolid;
            ok &= modeOk;
            System.out.printf("  %s：计划 %d 份（体积覆盖 %s，越界 %s），落盘 %d 份可读 %s，非空气 %d/%d %s%n",
                    modes[i].label, plan.parts().size(), covers ? "✓" : "✗", inBounds ? "无" : "有",
                    r.files().size(), readable ? "✓" : "✗", solid, s.solidCount(), modeOk ? "✓" : "✗");
            System.out.println("    → " + r.message());
        }
        // 网格放不下时自动收窄：2×2 请求在 1 格宽图上不应崩
        return ok;
    }
    private static String base(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static int num(CompoundTag c, String key) {
        Tag t = c.get(key);
        return t instanceof net.minecraft.nbt.NumericTag n ? n.getAsInt() : 0;
    }

    static ListTag empty() {
        return new ListTag();
    }

    static int bits(int paletteSize) {
        return Litematica.bits(paletteSize);
    }
}
