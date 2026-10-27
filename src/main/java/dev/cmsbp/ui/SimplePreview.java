package dev.cmsbp.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cmsbp.CmsBlueprints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 内置的「降级预览」：没装塞博护目镜时，自己把 .nbt 俯视投影成一张小图。
 *
 * <p>做法很土但够用：读结构里的 {@code size}/{@code palette}/{@code blocks}，每个 (x,z) 取最高的非空气方块，
 * 用 {@link Block#defaultMapColor()} 取色并按高度调亮度。这样能一眼看出大致轮廓和占地，
 * 不追求还原贴图。</p>
 */
public final class SimplePreview {

    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    private static final Map<String, int[]> SIZES = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private SimplePreview() {
    }

    /**
     * 取（并缓存）某个蓝图的俯视缩略图。
     *
     * @return 纹理 id；解析失败返回 {@code null}
     */
    public static ResourceLocation texture(Path file) {
        String key = file.toAbsolutePath().toString();
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        try {
            ResourceLocation rl = bake(file, key);
            if (rl == null) {
                FAILED.add(key);
            } else {
                CACHE.put(key, rl);
            }
            return rl;
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("生成蓝图缩略图失败（{}）：{}", file.getFileName(), t.toString());
            FAILED.add(key);
            return null;
        }
    }

    private static ResourceLocation bake(Path file, String key) throws Exception {
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            return null;
        }
        int sx = Math.max(1, Math.min(256, sizeTag.getInt(0)));
        int sy = Math.max(1, sizeTag.getInt(1));
        int sz = Math.max(1, Math.min(256, sizeTag.getInt(2)));
        SIZES.put(key, new int[]{sx, sy, sz});

        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        int[] colors = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            colors[i] = colorOf(palette.getCompound(i).getString("Name"));
        }

        int[] topColor = new int[sx * sz];
        int[] topY = new int[sx * sz];
        java.util.Arrays.fill(topColor, -1);

        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int state = b.getInt("state");
            if (pos.size() < 3 || state < 0 || state >= colors.length) {
                continue;
            }
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            if (x < 0 || x >= sx || z < 0 || z >= sz) {
                continue;
            }
            int idx = z * sx + x;
            if (y >= topY[idx]) {
                topY[idx] = y;
                topColor[idx] = colors[state];
            }
        }

        NativeImage img = new NativeImage(NativeImage.Format.RGBA, sx, sz, false);
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                int idx = z * sx + x;
                int c = topColor[idx];
                if (c < 0) {
                    img.setPixelRGBA(x, z, 0x00000000);
                    continue;
                }
                float t = sy <= 1 ? 1.0F : 0.55F + 0.45F * (topY[idx] / (float) (sy - 1));
                int r = (int) (((c >> 16) & 0xFF) * t);
                int gg = (int) (((c >> 8) & 0xFF) * t);
                int bb = (int) ((c & 0xFF) * t);
                // NativeImage 里是 ABGR 顺序
                img.setPixelRGBA(x, z, 0xFF000000 | (bb << 16) | (gg << 8) | r);
            }
        }

        DynamicTexture tex = new DynamicTexture(img);
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                CmsBlueprints.MODID, "preview/" + Integer.toHexString(key.hashCode()));
        Minecraft.getInstance().getTextureManager().register(rl, tex);
        return rl;
    }

    private static int colorOf(String blockName) {
        try {
            ResourceLocation id = ResourceLocation.parse(blockName);
            Block block = BuiltInRegistries.BLOCK.get(id);
            MapColor mc = block.defaultMapColor();
            return mc.col & 0xFFFFFF;
        } catch (Throwable t) {
            return 0x8B8B8B;
        }
    }

    /** 缩略图的原始像素尺寸（sx, sz）；未生成过返回 {@code null}。 */
    public static int[] size(Path file) {
        return SIZES.get(file.toAbsolutePath().toString());
    }
}
