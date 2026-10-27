package dev.cmsbp.schem;

import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.CreateBridge;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 蓝图处理入口：把任意格式（.litematic / WorldEdit .schem）转成 Create 能读的结构 .nbt，
 * 以及按「切割方式 + 切割数量」把蓝图切成若干小 .nbt。
 *
 * <p>方向只有一个：<b>转进机械动力</b>。产物一律落在 Create 的 schematics 目录。</p>
 *
 * <p>切割方式语义对齐 McSTools：<br>
 * {@link SplitMode#VERTICAL} 沿 X 竖着切 N 刀（每份保留整高整深）；<br>
 * {@link SplitMode#HORIZONTAL} 沿 Y 水平切 N 层；<br>
 * {@link SplitMode#GRID} 在 XZ 平面切 k×k 网格（总份数 N = k²，如 4 / 9 / 16）。</p>
 */
public final class Convert {

    /** 切割方式。 */
    public enum SplitMode {
        /** 沿 X 竖切。 */
        VERTICAL("竖直"),
        /** 沿 Y 水平切。 */
        HORIZONTAL("水平"),
        /** XZ 平面网格。 */
        GRID("网格");

        public final String label;

        SplitMode(String label) {
            this.label = label;
        }
    }

    /** 一份切片的几何信息。 */
    public record Part(int x0, int y0, int z0, int w, int h, int d, String label) {
    }

    /** 一次分割的计划（先算后写，UI 可以拿它做预览）。 */
    public record Plan(SplitMode mode, int side, List<Part> parts) {
        public Part first() {
            return parts.isEmpty() ? null : parts.get(0);
        }
    }

    public record Result(List<Path> files, String message) {
    }

    private Convert() {
    }

    /** 任意格式 → 结构 .nbt（原地同目录）。已经是 .nbt 就不动。 */
    public static Result toNbt(Path src) throws IOException {
        String name = src.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".nbt")) {
            return new Result(List.of(src), "已经是结构 .nbt，不用转换");
        }
        Schematic s = Schematic.read(src);
        Path target = unique(src.getParent(), baseName(src), ".nbt");
        NbtIo.writeCompressed(s.toVanilla(), target.toFile());
        long size = Files.size(target);
        CmsBlueprints.LOG.info("已转换 {} → {}（{} 字节）", src.getFileName(), target.getFileName(), size);
        return new Result(List.of(target), "已生成 " + target.getFileName() + "（" + CreateBridge.human((int) size)
                + "，" + s.sx + "×" + s.sy + "×" + s.sz + "）");
    }

    /**
     * 按给定方式切成若干 .nbt，产物放进以文件名命名的新子目录。
     *
     * @param count 竖直/水平 = 切几份；网格 = 总份数，必须是完全平方数（4 / 9 / 16 …）
     */
    public static Result split(Path src, SplitMode mode, int count) throws IOException {
        Schematic s = Schematic.read(src);
        Plan plan = plan(s, mode, count);
        if (plan.parts().size() <= 1) {
            return new Result(List.of(), "只切出 1 份，不需要分割（" + s.sx + "×" + s.sy + "×" + s.sz + "）");
        }
        String base = baseName(src);
        Path dir = uniqueDir(src.getParent(), base);
        Files.createDirectories(dir);

        List<Path> written = new ArrayList<>(plan.parts().size());
        for (Part p : plan.parts()) {
            Schematic sub = s.slice(p.x0(), p.y0(), p.z0(), p.w(), p.h(), p.d());
            Path target = dir.resolve(base + "_" + p.label() + ".nbt");
            NbtIo.writeCompressed(sub.toVanilla(), target.toFile());
            written.add(target);
        }
        CmsBlueprints.LOG.info("已分割 {} → {}（{} 份，{}）",
                src.getFileName(), dir.getFileName(), written.size(), describe(plan));
        Part f = plan.first();
        return new Result(written, "已分割成 " + written.size() + " 份（" + describe(plan) + "，每份 ≤ "
                + f.w() + "×" + f.h() + "×" + f.d() + "）→ " + dir.getFileName() + "/");
    }

    /** 只算不写：UI 用来显示「将生成几份、每份多大」。 */
    public static Plan plan(Schematic s, SplitMode mode, int count) {
        SplitMode m = mode == null ? SplitMode.GRID : mode;
        List<Part> parts = new ArrayList<>();
        int side = 1;
        switch (m) {
            case VERTICAL -> {
                side = clamp(count, 2, Math.max(2, s.sx));
                fillAxis(parts, s, side, 0);
            }
            case HORIZONTAL -> {
                side = clamp(count, 2, Math.max(2, s.sy));
                fillAxis(parts, s, side, 1);
            }
            case GRID -> {
                side = gridSide(count, s);
                int[] xs = segments(s.sx, side);
                int[] zs = segments(s.sz, side);
                for (int xi = 0; xi < side; xi++) {
                    for (int zi = 0; zi < side; zi++) {
                        parts.add(new Part(xs[xi], 0, zs[zi],
                                xs[xi + 1] - xs[xi], s.sy, zs[zi + 1] - zs[zi],
                                (xi + 1) + "x" + (zi + 1)));
                    }
                }
            }
            default -> {
            }
        }
        return new Plan(m, side, parts);
    }

    /** 竖直/水平切：axis=0 沿 X，axis=1 沿 Y。最后一份吃掉余数。 */
    private static void fillAxis(List<Part> out, Schematic s, int n, int axis) {
        int dim = axis == 0 ? s.sx : s.sy;
        int[] cuts = segments(dim, n);
        for (int i = 0; i < n; i++) {
            int start = cuts[i];
            int len = cuts[i + 1] - start;
            if (len <= 0) {
                continue;
            }
            if (axis == 0) {
                out.add(new Part(start, 0, 0, len, s.sy, s.sz, "x" + (i + 1)));
            } else {
                out.add(new Part(0, start, 0, s.sx, len, s.sz, "y" + (i + 1)));
            }
        }
    }

    /** 把长度 dim 切成 n 段，返回 n+1 个边界（最后一段吃余数）。 */
    private static int[] segments(int dim, int n) {
        int[] out = new int[n + 1];
        int step = dim / n;
        int rem = dim % n;
        int acc = 0;
        for (int i = 0; i < n; i++) {
            out[i] = acc;
            acc += (i == n - 1) ? step + rem : step;
        }
        out[n] = dim;
        return out;
    }

    /** 网格边长：count 必须是完全平方数，且不超过较短的那条水平边。 */
    private static int gridSide(int count, Schematic s) {
        int k = (int) Math.round(Math.sqrt(Math.max(1, count)));
        if (k * k != count) {
            k = Math.max(2, (int) Math.floor(Math.sqrt(count)));
        }
        return clamp(k, 1, Math.max(1, Math.min(s.sx, s.sz)));
    }

    /** 网格最多能切几×几（UI 用来禁掉放不下的档位）。 */
    public static int maxGridSide(Schematic s) {
        return Math.max(1, Math.min(s.sx, s.sz));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String describe(Plan plan) {
        int n = plan.parts().size();
        if (plan.mode() == SplitMode.GRID) {
            int k = Math.max(1, (int) Math.round(Math.sqrt(n)));
            return "网格 " + k + "×" + k;
        }
        return (plan.mode() == SplitMode.VERTICAL ? "竖直切 " : "水平切 ") + n + " 份";
    }

    private static String baseName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** 同名就加 -2、-3…，绝不覆盖已有文件。 */
    private static Path unique(Path dir, String base, String ext) {
        Path target = dir.resolve(base + ext);
        int n = 2;
        while (Files.exists(target)) {
            target = dir.resolve(base + "-" + (n++) + ext);
        }
        return target;
    }

    /** 同名目录已存在且非空就加 -2、-3…（空目录直接复用）。 */
    private static Path uniqueDir(Path parent, String base) {
        Path dir = parent.resolve(base);
        int n = 2;
        while (Files.isDirectory(dir) && !isEmptyDir(dir)) {
            dir = parent.resolve(base + "-" + (n++));
        }
        return dir;
    }

    private static boolean isEmptyDir(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }
}
