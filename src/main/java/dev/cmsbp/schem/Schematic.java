package dev.cmsbp.schem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图中立模型（只装「结构 .nbt」能表达的方块数据，够做格式转换与分割）。
 *
 * <p>下标约定与结构 NBT 一致：{@code idx = (y * sz + z) * sx + x}，x 变化最快。</p>
 *
 * <p>{@code ox/oy/oz} 是这一份数据在原蓝图里的最小角偏移，只有方块实体/实体的坐标需要它
 * （分割出来的子块要把它们平移回去）。</p>
 */
public final class Schematic {

    /** 体积上限：24M 格，约 96 MB 的 int[]，再大就明确报错而不是把游戏卡死。 */
    public static final int MAX_VOLUME = 24_000_000;

    public final int sx;
    public final int sy;
    public final int sz;
    public final int ox;
    public final int oy;
    public final int oz;
    /** palette 里的顺序与结构 NBT 的 {@code palette} 一致，元素形如 {@code {Name, Properties}}。 */
    public final List<CompoundTag> palette;
    /** 每格的状态 id；0 通常就是空气（结构 NBT 的 palette[0]），没写进 blocks 的格子留 0。 */
    public final int[] states;
    /** 原样保留的结构 {@code block_entities}（坐标是绝对/原坐标，写出时再减 origin）。 */
    public final ListTag blockEntities;
    /** 原样保留的结构 {@code entities}。 */
    public final ListTag entities;
    public final int dataVersion;

    public Schematic(int sx, int sy, int sz, int ox, int oy, int oz, List<CompoundTag> palette,
                     int[] states, ListTag blockEntities, ListTag entities, int dataVersion) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.palette = palette;
        this.states = states;
        this.blockEntities = blockEntities;
        this.entities = entities;
        this.dataVersion = dataVersion;
    }

    public int volume() {
        return sx * sy * sz;
    }

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    public int stateAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
            return 0;
        }
        return this.states[index(x, y, z)];
    }

    /** 某状态 id 的方块名（{@code minecraft:xxx}），越界返回空串。 */
    public String blockName(int state) {
        if (state < 0 || state >= this.palette.size()) {
            return "";
        }
        return this.palette.get(state).getString("Name");
    }

    public static boolean isAir(String name) {
        return "minecraft:air".equals(name) || "minecraft:cave_air".equals(name) || "minecraft:void_air".equals(name);
    }

    /** 非空气方块数（Litematica 的 Metadata.TotalBlocks 用它）。 */
    public int solidCount() {
        int n = 0;
        for (int s : this.states) {
            if (!isAir(blockName(s))) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 读

    /** 读结构 .nbt（原版结构格式，CMS 下载到的就是这种）。 */
    public static Schematic readVanilla(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return fromVanilla(root, 0, 0, 0);
    }

    /** 读任意支持的格式（结构 .nbt / .litematic / WorldEdit .schem），按内容判型。 */
    public static Schematic read(Path file) throws IOException {
        return fromAny(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
    }

    /** 按内容判型后转成中立模型。 */
    public static Schematic fromAny(CompoundTag root) throws IOException {
        if (Litematica.looksLike(root)) {
            return Litematica.read(root);
        }
        if (WorldEdit.looksLike(root)) {
            return WorldEdit.read(root);
        }
        if (root.contains("size", Tag.TAG_LIST)) {
            return fromVanilla(root, 0, 0, 0);
        }
        if (WorldEdit.isLegacyMcedit(root)) {
            throw new IOException("这是 MCEdit 老格式（.schematic，方块用数字 id 存），暂不支持自动转换");
        }
        throw new IOException("认不出的格式（顶层键：" + String.join(", ", root.getAllKeys()) + "）");
    }

    /** 人类可读的格式名，用于界面提示。 */
    public static String formatOf(Path file) {
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (Litematica.looksLike(root)) {
                return "Litematica";
            }
            if (WorldEdit.looksLike(root)) {
                return "WorldEdit";
            }
            if (root.contains("size", Tag.TAG_LIST)) {
                return "Create/结构";
            }
            if (WorldEdit.isLegacyMcedit(root)) {
                return "MCEdit(不支持)";
            }
            return "未知";
        } catch (Exception e) {
            return "读不了";
        }
    }

    public static Schematic fromVanilla(CompoundTag root, int ox, int oy, int oz) throws IOException {
        ListTag sizeTag = root.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            throw new IOException("不是结构 NBT（缺 size[3]）");
        }
        int sx = Math.max(1, sizeTag.getInt(0));
        int sy = Math.max(1, sizeTag.getInt(1));
        int sz = Math.max(1, sizeTag.getInt(2));
        long vol = (long) sx * sy * sz;
        if (vol > MAX_VOLUME) {
            throw new IOException("蓝图太大（" + sx + "×" + sy + "×" + sz + " = " + vol + " 格），超过 " + MAX_VOLUME + " 上限");
        }

        ListTag pal = root.getList("palette", Tag.TAG_COMPOUND);
        List<CompoundTag> palette = new ArrayList<>(pal.size());
        for (int i = 0; i < pal.size(); i++) {
            palette.add(pal.getCompound(i).copy());
        }
        if (palette.isEmpty()) {
            throw new IOException("结构 NBT 里没有 palette");
        }

        int[] states = new int[(int) vol];
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            if (pos.size() < 3) {
                continue;
            }
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                continue;
            }
            int state = b.getInt("state");
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            states[(y * sz + z) * sx + x] = state;
        }

        return new Schematic(sx, sy, sz, ox, oy, oz, palette, states,
                root.getList("block_entities", Tag.TAG_COMPOUND).copy(),
                root.getList("entities", Tag.TAG_COMPOUND).copy(),
                root.contains("DataVersion", Tag.TAG_INT) ? root.getInt("DataVersion") : 0);
    }

    // ---------------------------------------------------------------- 写（原版结构）

    /** 写成原版结构 NBT 的根 compound。 */
    public CompoundTag toVanilla() {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(this.sx, this.sy, this.sz));
        ListTag pal = new ListTag();
        for (CompoundTag p : this.palette) {
            pal.add(p.copy());
        }
        root.put("palette", pal);

        ListTag blocks = new ListTag();
        // 结构 NBT 允许省略的只有「下标 0 的方块」；本模型里缺席格默认写 0，
        // 所以只有当 palette[0] 确实是空气时才能省略，否则必须把 0 也写出来。
        boolean skipZero = palette.isEmpty() || isAir(this.palette.get(0).getString("Name"));
        for (int y = 0; y < this.sy; y++) {
            for (int z = 0; z < this.sz; z++) {
                for (int x = 0; x < this.sx; x++) {
                    int state = this.states[index(x, y, z)];
                    if (state == 0 && skipZero) {
                        continue;
                    }
                    CompoundTag b = new CompoundTag();
                    b.put("pos", ints(x, y, z));
                    b.put("state", IntTag.valueOf(state));
                    blocks.add(b);
                }
            }
        }
        root.put("blocks", blocks);
        root.put("entities", translated(this.entities, true));
        root.put("block_entities", translated(this.blockEntities, false));
        if (this.dataVersion > 0) {
            root.put("DataVersion", IntTag.valueOf(this.dataVersion));
        }
        return root;
    }

    // ---------------------------------------------------------------- 分割

    /**
     * 取一块子区域，返回新模型（palette 不动，方块实体/实体坐标按偏移平移）。
     *
     * @param x0/y0/z0 子区域左上角（相对本模型，0 起）
     * @param w/h/d    子区域尺寸，必须落在本模型范围内且都 > 0
     */
    public Schematic slice(int x0, int y0, int z0, int w, int h, int d) {
        if (w <= 0 || h <= 0 || d <= 0) {
            throw new IllegalArgumentException("切片尺寸必须为正：" + w + "×" + h + "×" + d);
        }
        if (x0 < 0 || y0 < 0 || z0 < 0 || x0 + w > this.sx || y0 + h > this.sy || z0 + d > this.sz) {
            throw new IllegalArgumentException("切片越界：" + w + "×" + h + "×" + d + " @(" + x0 + "," + y0 + "," + z0 + ")");
        }
        int[] sub = new int[w * h * d];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    sub[(y * d + z) * w + x] = this.states[index(x + x0, y + y0, z + z0)];
                }
            }
        }
        return new Schematic(w, h, d, this.ox + x0, this.oy + y0, this.oz + z0,
                this.palette, sub, this.blockEntities, this.entities, this.dataVersion);
    }

    /** 按每个轴最多 {@code maxEdge} 格切块，返回若干子模型（保持 palette 不动）。 */
    public List<Schematic> split(int maxEdge) {
        int edge = Math.max(1, maxEdge);
        int nx = (this.sx + edge - 1) / edge;
        int ny = (this.sy + edge - 1) / edge;
        int nz = (this.sz + edge - 1) / edge;
        List<Schematic> out = new ArrayList<>(nx * ny * nz);
        for (int iy = 0; iy < ny; iy++) {
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    int bx = ix * edge;
                    int by = iy * edge;
                    int bz = iz * edge;
                    out.add(slice(bx, by, bz,
                            Math.min(edge, this.sx - bx),
                            Math.min(edge, this.sy - by),
                            Math.min(edge, this.sz - bz)));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    /** 把方块实体/实体的坐标减去 origin（写子块时用），并丢掉落在本块外面的，
     *  返回的仍是副本。 */
    private ListTag translated(ListTag src, boolean entity) {
        ListTag out = new ListTag();
        for (int i = 0; i < src.size(); i++) {
            CompoundTag c = src.getCompound(i).copy();
            ListTag pos = c.getList(entity ? "Pos" : "pos", entity ? Tag.TAG_DOUBLE : Tag.TAG_INT);
            if (pos.size() >= 3) {
                ListTag np = new ListTag();
                int[] shifted = new int[3];
                for (int k = 0; k < 3; k++) {
                    int shift = k == 0 ? this.ox : k == 1 ? this.oy : this.oz;
                    if (entity) {
                        double v = pos.getDouble(k) - shift;
                        np.add(net.minecraft.nbt.DoubleTag.valueOf(v));
                        shifted[k] = (int) Math.floor(v);
                    } else {
                        int v = pos.getInt(k) - shift;
                        np.add(IntTag.valueOf(v));
                        shifted[k] = v;
                    }
                }
                if (!this.contains(shifted[0], shifted[1], shifted[2])) {
                    continue; // 分割后这一份里没有这个方块实体/实体
                }
                c.put(entity ? "Pos" : "pos", np);
            }
            out.add(c);
        }
        return out;
    }

    /** 本块坐标空间内是否含这一格。 */
    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.sx && y < this.sy && z < this.sz;
    }

    static ListTag ints(int... v) {
        ListTag t = new ListTag();
        for (int x : v) {
            t.add(IntTag.valueOf(x));
        }
        return t;
    }

    static IntArrayTag intArray(int... v) {
        return new IntArrayTag(v);
    }
}
