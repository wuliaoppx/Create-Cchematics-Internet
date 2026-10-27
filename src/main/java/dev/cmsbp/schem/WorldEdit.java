package dev.cmsbp.schem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 WorldEdit / Sponge 的 {@code .schem}，转成 {@link Schematic}（中立模型）。
 *
 * <p>支持 Sponge Schematic v1/v2/v3：{@code Width/Height/Length} + {@code Palette}(名称→下标) +
 * {@code BlockData}(每格一个 varint) + {@code BlockEntities}/{@code Entities}。
 * 下标顺序 {@code (y * Length + z) * Width + x}。</p>
 *
 * <p>老 MCEdit 的 {@code .schematic}（{@code Blocks}/{@code Data} 用数字 id 存方块）不在这里处理：
 * 数字 id 到现代 blockstate 的映射表太大，硬转出来的东西经常是错的，宁可明确报错。</p>
 */
public final class WorldEdit {

    private WorldEdit() {
    }

    public static boolean looksLike(CompoundTag root) {
        CompoundTag blocks = blocks(root);
        return data(blocks) != null && blocks.contains("Palette", Tag.TAG_COMPOUND);
    }

    /**
     * 找到真正装方块数据的那层 compound。
     *
     * <p>Sponge v3 把整份数据包在 {@code Schematic} 里，方块部分又在 {@code Schematic.Blocks}；
     * v2 直接摊在根上；v1 用 {@code TileEntities} 而非 {@code BlockEntities}。</p>
     */
    private static CompoundTag blocks(CompoundTag root) {
        CompoundTag body = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        return body.contains("Blocks", Tag.TAG_COMPOUND) ? body.getCompound("Blocks") : body;
    }

    /** v3 叫 {@code Data}，v1/v2 叫 {@code BlockData}。 */
    private static byte[] data(CompoundTag blocks) {
        if (blocks.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            return blocks.getByteArray("BlockData");
        }
        return blocks.contains("Data", Tag.TAG_BYTE_ARRAY) ? blocks.getByteArray("Data") : null;
    }

    /** 老 MCEdit 格式：{@code Blocks} 字节数组 + 数字 id。 */
    public static boolean isLegacyMcedit(CompoundTag root) {
        return root.contains("Blocks", Tag.TAG_BYTE_ARRAY) && !root.contains("Palette", Tag.TAG_COMPOUND);
    }

    public static Schematic read(CompoundTag root) throws IOException {
        CompoundTag body = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        CompoundTag blk = blocks(root);
        int w = num(body, "Width");
        int h = num(body, "Height");
        int d = num(body, "Length");
        if (w <= 0 || h <= 0 || d <= 0) {
            throw new IOException("schem 尺寸不合法（" + w + "×" + h + "×" + d + "）");
        }
        long vol = (long) w * h * d;
        if (vol > Schematic.MAX_VOLUME) {
            throw new IOException("蓝图太大（" + w + "×" + h + "×" + d + " = " + vol + " 格），超过 "
                    + Schematic.MAX_VOLUME + " 上限");
        }

        // Palette：方块状态字符串 → 下标（下标不一定连续，按最大值铺数组）
        CompoundTag pal = blk.getCompound("Palette");
        if (pal.getAllKeys().isEmpty()) {
            throw new IOException("schem 里没有 Palette");
        }
        List<CompoundTag> palette = new ArrayList<>();
        int maxId = 0;
        Map<String, Integer> names = new HashMap<>();
        for (String name : pal.getAllKeys()) {
            CompoundTag state = parseState(name);
            int id = ((NumericTag) pal.get(name)).getAsInt();
            names.put(name, id);
            maxId = Math.max(maxId, id);
        }
        CompoundTag[] byId = new CompoundTag[maxId + 1];
        for (Map.Entry<String, Integer> e : names.entrySet()) {
            byId[e.getValue()] = parseState(e.getKey());
        }

        int[] states = new int[(int) vol];
        byte[] data = data(blk);
        int pos = 0;
        for (int i = 0; i < states.length; i++) {
            int id = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IOException("BlockData 长度不够（解到第 " + i + " 格）");
                }
                int b = data[pos++] & 0xFF;
                id |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 35) {
                    throw new IOException("BlockData 里的 varint 太长（数据损坏？）");
                }
            }
            states[i] = id >= 0 && id < byId.length && byId[id] != null ? id : 0;
        }

        // 把稀疏的 id 空间压成连续的 palette，顺便统一结构
        int[] remap = new int[byId.length];
        for (int id = 0; id < byId.length; id++) {
            if (byId[id] == null) {
                remap[id] = 0;
                continue;
            }
            remap[id] = palette.size();
            palette.add(byId[id]);
        }
        if (palette.isEmpty()) {
            palette.add(parseState("minecraft:air"));
        }
        for (int i = 0; i < states.length; i++) {
            states[i] = states[i] < remap.length ? remap[states[i]] : 0;
        }

        // 方块实体：{Id, Pos:[x,y,z], Data:{...}} → {pos:[x,y,z], nbt:{id, ...Data}}
        ListTag blockEntities = new ListTag();
        ListTag bes = blk.contains("BlockEntities", Tag.TAG_LIST)
                ? blk.getList("BlockEntities", Tag.TAG_COMPOUND)
                : body.getList("TileEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < bes.size(); i++) {
            CompoundTag src = bes.getCompound(i);
            ListTag p = src.getList("Pos", Tag.TAG_INT);
            if (p.size() < 3) {
                continue;
            }
            int x = p.getInt(0);
            int y = p.getInt(1);
            int z = p.getInt(2);
            if (!inside(x, y, z, w, h, d)) {
                continue;
            }
            CompoundTag nbt = src.contains("Data", Tag.TAG_COMPOUND) ? src.getCompound("Data").copy() : new CompoundTag();
            String id = src.getString("Id");
            if (!id.isEmpty() && !nbt.contains("id")) {
                nbt.putString("id", id);
            }
            CompoundTag e = new CompoundTag();
            e.put("pos", Schematic.ints(x, y, z));
            e.put("nbt", nbt);
            blockEntities.add(e);
        }

        // 实体：{Id, Pos:[d,d,d], Data:{...}}
        ListTag entities = new ListTag();
        ListTag ents = body.getList("Entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < ents.size(); i++) {
            CompoundTag src = ents.getCompound(i);
            ListTag p = src.getList("Pos", Tag.TAG_DOUBLE);
            if (p.size() < 3) {
                continue;
            }
            double x = p.getDouble(0);
            double y = p.getDouble(1);
            double z = p.getDouble(2);
            if (!inside((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), w, h, d)) {
                continue;
            }
            CompoundTag nbt = src.contains("Data", Tag.TAG_COMPOUND) ? src.getCompound("Data").copy() : new CompoundTag();
            String id = src.getString("Id");
            if (!id.isEmpty() && !nbt.contains("id")) {
                nbt.putString("id", id);
            }
            ListTag np = new ListTag();
            np.add(DoubleTag.valueOf(x));
            np.add(DoubleTag.valueOf(y));
            np.add(DoubleTag.valueOf(z));
            CompoundTag e = new CompoundTag();
            e.put("pos", np);
            e.put("nbt", nbt);
            entities.add(e);
        }

        int dataVersion = body.contains("DataVersion", Tag.TAG_INT) ? body.getInt("DataVersion")
                : (body.contains("DataVersion", Tag.TAG_SHORT) ? body.getShort("DataVersion") : 0);
        return new Schematic(w, h, d, 0, 0, 0, palette, states, blockEntities, entities, dataVersion);
    }

    /** {@code minecraft:oak_stairs[facing=east,half=bottom]} → {@code {Name:…, Properties:{…}}}。 */
    static CompoundTag parseState(String text) {
        CompoundTag out = new CompoundTag();
        String name = text;
        int bracket = text.indexOf('[');
        if (bracket < 0) {
            out.putString("Name", name);
            return out;
        }
        name = text.substring(0, bracket);
        out.putString("Name", name);
        int end = text.lastIndexOf(']');
        String inner = text.substring(bracket + 1, end > bracket ? end : text.length());
        if (inner.isBlank()) {
            return out;
        }
        CompoundTag props = new CompoundTag();
        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        out.put("Properties", props);
        return out;
    }

    /** Width/Height/Length 在 v2/v3 里是 Short，老版本有 Int，统一按数值读。 */
    private static int num(CompoundTag c, String key) {
        Tag t = c.get(key);
        return t instanceof NumericTag n ? n.getAsInt() : 0;
    }

    private static boolean inside(int x, int y, int z, int w, int h, int d) {
        return x >= 0 && y >= 0 && z >= 0 && x < w && y < h && z < d;
    }

    static IntTag i(int v) {
        return IntTag.valueOf(v);
    }
}
