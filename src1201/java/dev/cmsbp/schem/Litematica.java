package dev.cmsbp.schem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 Litematica 的 {@code .litematic}，转成 {@link Schematic}（中立模型）。
 *
 * <p>字段与打包方式按 Litematica 的 {@code LitematicaSchematic}/{@code LitematicaBitArray}：
 * 每个 {@code Regions.<名字>} 一个区域，{@code BlockStates} 是 LSB 优先、跨 long 连续排列的定长位数组
 * （{@code bits = max(2, ceil(log2(paletteSize)))}），下标顺序 {@code (y * sizeZ + z) * sizeX + x}。</p>
 *
 * <p>{@code Size} 与 {@code Position} 都能是负数：Size 为负表示区域从 Position 往负方向延伸，
 * 这时下标 0 就落在 Position 那一格。多区域按各自的世界包围盒合并到一个整体包围盒里。</p>
 */
public final class Litematica {

    private Litematica() {
    }

    /** 顶层判据：有 {@code Regions} 且带 litematic 的 {@code Version}。 */
    public static boolean looksLike(CompoundTag root) {
        return root.contains("Regions", Tag.TAG_COMPOUND) && root.contains("Version", Tag.TAG_INT);
    }

    public static Schematic read(CompoundTag root) throws IOException {
        CompoundTag regions = root.getCompound("Regions");
        if (regions.getAllKeys().isEmpty()) {
            throw new IOException("litematic 里没有区域（Regions 为空）");
        }

        // ① 先扫一遍包围盒
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long volume = 0L;
        for (String name : regions.getAllKeys()) {
            int[] b = bounds(regions.getCompound(name));
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            minZ = Math.min(minZ, b[2]);
            maxX = Math.max(maxX, b[3]);
            maxY = Math.max(maxY, b[4]);
            maxZ = Math.max(maxZ, b[5]);
            volume += (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        }
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        if ((long) sx * sy * sz > Schematic.MAX_VOLUME) {
            throw new IOException("蓝图太大（" + sx + "×" + sy + "×" + sz + " = " + (long) sx * sy * sz
                    + " 格），超过 " + Schematic.MAX_VOLUME + " 上限");
        }

        // ② 各区域的 palette 合并成一份
        List<CompoundTag> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        int[] states = new int[sx * sy * sz];
        ListTag blockEntities = new ListTag();
        ListTag entities = new ListTag();

        for (String name : regions.getAllKeys()) {
            CompoundTag region = regions.getCompound(name);
            CompoundTag sizeTag = region.getCompound("Size");
            CompoundTag posTag = region.getCompound("Position");
            int rx = sizeTag.getInt("x");
            int ry = sizeTag.getInt("y");
            int rz = sizeTag.getInt("z");
            int px = posTag.getInt("x");
            int py = posTag.getInt("y");
            int pz = posTag.getInt("z");
            int w = Math.abs(rx);
            int h = Math.abs(ry);
            int d = Math.abs(rz);
            if (w <= 0 || h <= 0 || d <= 0) {
                continue;
            }
            // 下标 0 对应区域的「最小角」，坐标随下标单调递增（与 Litematica 的
            // placeBlocksToWorld 一致：posMinRel = getMinCorner(Position, Position+Size-1)，
            // 然后 pos = posMinRel + (x,y,z) - Position）。Size 为负只是说明最小角在 Position 的负方向。
            int baseX = rx >= 0 ? px : px + rx + 1;
            int baseY = ry >= 0 ? py : py + ry + 1;
            int baseZ = rz >= 0 ? pz : pz + rz + 1;
            int stepX = 1;
            int stepY = 1;
            int stepZ = 1;

            // 区域自身的 palette（下标按区域序号，需要重映射到全局）
            ListTag rp = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            int[] remap = new int[rp.size()];
            for (int i = 0; i < rp.size(); i++) {
                CompoundTag state = rp.getCompound(i);
                String key = key(state);
                Integer gi = paletteIndex.get(key);
                if (gi == null) {
                    gi = palette.size();
                    palette.add(state.copy());
                    paletteIndex.put(key, gi);
                }
                remap[i] = gi;
            }

            long[] longs = region.getLongArray("BlockStates");
            int bits = bits(rp.size());
            int count = w * h * d;
            int need = (int) (((long) bits * count + 63L) / 64L);
            if (longs.length < need) {
                throw new IOException("区域 " + name + " 的 BlockStates 长度不够（" + longs.length + " < " + need + "）");
            }
            for (int iy = 0; iy < h; iy++) {
                int gy = baseY + iy * stepY - minY;
                for (int iz = 0; iz < d; iz++) {
                    int gz = baseZ + iz * stepZ - minZ;
                    for (int ix = 0; ix < w; ix++) {
                        int gx = baseX + ix * stepX - minX;
                        int local = (iy * d + iz) * w + ix;
                        int state = getAt(longs, bits, local);
                        int mapped = state >= 0 && state < remap.length ? remap[state] : 0;
                        states[(gy * sz + gz) * sx + gx] = mapped;
                    }
                }
            }

            // 方块实体：Pos 是区域内坐标（相对区域最小角），nbt 内容内联
            ListTag tes = region.getList("TileEntities", Tag.TAG_COMPOUND);
            for (int i = 0; i < tes.size(); i++) {
                CompoundTag src = tes.getCompound(i);
                CompoundTag pos = src.getCompound("Pos");
                if (!pos.contains("x") || !pos.contains("y") || !pos.contains("z")) {
                    continue;
                }
                int lx = pos.getInt("x");
                int ly = pos.getInt("y");
                int lz = pos.getInt("z");
                CompoundTag copy = src.copy();
                copy.remove("Pos");
                copy.remove("BlockPos");
                int gx = baseX + lx * stepX - minX;
                int gy = baseY + ly * stepY - minY;
                int gz = baseZ + lz * stepZ - minZ;
                if (!inside(gx, gy, gz, sx, sy, sz)) {
                    continue;
                }
                CompoundTag e = new CompoundTag();
                e.put("pos", Schematic.ints(gx, gy, gz));
                e.put("nbt", copy);
                blockEntities.add(e);
            }

            // 实体：Pos 是 double，相对区域最小角
            ListTag ents = region.getList("Entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < ents.size(); i++) {
                CompoundTag src = ents.getCompound(i);
                ListTag pos = src.getList("Pos", Tag.TAG_DOUBLE);
                if (pos.size() < 3) {
                    continue;
                }
                double lx = pos.getDouble(0);
                double ly = pos.getDouble(1);
                double lz = pos.getDouble(2);
                CompoundTag copy = src.copy();
                copy.remove("Pos");
                copy.remove("BlockPos");
                double gx = shift(lx, baseX, stepX, minX);
                double gy = shift(ly, baseY, stepY, minY);
                double gz = shift(lz, baseZ, stepZ, minZ);
                if (!inside((int) Math.floor(gx), (int) Math.floor(gy), (int) Math.floor(gz), sx, sy, sz)) {
                    continue;
                }
                ListTag np = new ListTag();
                np.add(DoubleTag.valueOf(gx));
                np.add(DoubleTag.valueOf(gy));
                np.add(DoubleTag.valueOf(gz));
                CompoundTag e = new CompoundTag();
                e.put("pos", np);
                e.put("nbt", copy);
                entities.add(e);
            }
        }

        if (palette.isEmpty()) {
            throw new IOException("litematic 里没有方块数据");
        }
        int dataVersion = root.contains("MinecraftDataVersion", Tag.TAG_INT) ? root.getInt("MinecraftDataVersion") : 0;
        return new Schematic(sx, sy, sz, 0, 0, 0, palette, states, blockEntities, entities, dataVersion);
    }

    /** 区域的世界包围盒 {minX,minY,minZ,maxX,maxY,maxZ}。 */
    private static int[] bounds(CompoundTag region) {
        CompoundTag size = region.getCompound("Size");
        CompoundTag pos = region.getCompound("Position");
        int[] out = new int[6];
        for (int k = 0; k < 3; k++) {
            String axis = k == 0 ? "x" : k == 1 ? "y" : "z";
            int s = size.getInt(axis);
            int p = pos.getInt(axis);
            out[k] = s >= 0 ? p : p + s + 1;
            out[k + 3] = s >= 0 ? p + s - 1 : p;
        }
        return out;
    }

    /** 区域内坐标 → 全局坐标（考虑 Size 为负时的反向延伸）。 */
    private static double shift(double local, int base, int step, int globalMin) {
        return step > 0 ? local + base - globalMin : base - local - globalMin;
    }

    private static boolean inside(int x, int y, int z, int sx, int sy, int sz) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    /** 位宽：至少 2，刚好能装下 palette 下标。 */
    public static int bits(int paletteSize) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1));
        return Math.max(2, bits);
    }

    /** Litematica 的 {@code LitematicaBitArray#getAt}：LSB 优先、跨 long 连续。 */
    public static int getAt(long[] longs, int bits, int index) {
        long mask = (1L << bits) - 1L;
        int startOffset = index * bits;
        int startArr = startOffset >> 6;
        int endArr = ((index + 1) * bits - 1) >> 6;
        int startBit = startOffset & 0x3F;
        if (startArr >= longs.length) {
            return 0;
        }
        if (startArr == endArr) {
            return (int) (longs[startArr] >>> startBit & mask);
        }
        if (endArr >= longs.length) {
            return 0;
        }
        int spill = 64 - startBit;
        return (int) ((longs[startArr] >>> startBit | longs[endArr] << spill) & mask);
    }

    /** 方块状态的规范化键（Name + 排序后的 Properties），用于合并多个区域的 palette。 */
    private static String key(CompoundTag state) {
        String name = state.getString("Name");
        CompoundTag props = state.getCompound("Properties");
        if (props.getAllKeys().isEmpty()) {
            return name;
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String k : new ArrayList<>(props.getAllKeys())) {
            sorted.put(k, props.getString(k));
        }
        List<String> keys = new ArrayList<>(sorted.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder(name).append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(keys.get(i)).append('=').append(sorted.get(keys.get(i)));
        }
        return sb.append(']').toString();
    }

    static IntTag i(int v) {
        return IntTag.valueOf(v);
    }
}
