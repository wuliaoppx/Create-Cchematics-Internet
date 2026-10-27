package dev.cmsbp.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cmsbp.CmsBlueprints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点封面图（WebP）的下载、解码与纹理缓存。
 *
 * <p>MC 的 {@code NativeImage.read} 只认 PNG/JPEG，站点的封面是 WebP，
 * 所以走 {@code ImageIO} + twelvemonkeys 的纯 Java WebP 解码器（lib/webp/ 下 6 个 jar）。</p>
 *
 * <p>所有方法都线程安全：下载与解码发生在网络线程，注册纹理切回渲染线程由调用方保证。</p>
 */
public final class CoverImage {

    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, int[]> SIZES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private CoverImage() {
    }

    /**
     * 取（并缓存）某个封面 URL 对应的纹理。
     *
     * @param coverUrl 形如 {@code /upload/post/xxx.webp} 的相对路径
     * @return 纹理 id；下载/解码失败返回 {@code null}
     */
    public static ResourceLocation texture(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        ResourceLocation cached = CACHE.get(coverUrl);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(coverUrl)) {
            return null;
        }
        byte[] data = CoverFetcher.fetch(coverUrl);
        if (data == null) {
            FAILED.add(coverUrl);
            return null;
        }
        try {
            NativeImage img = decode(data);
            int w = img.getWidth();
            int h = img.getHeight();
            DynamicTexture tex = new DynamicTexture(img);
            ResourceLocation rl = new ResourceLocation(
                    CmsBlueprints.MODID, "cover/" + Integer.toHexString(coverUrl.hashCode()));
            Minecraft.getInstance().getTextureManager().register(rl, tex);
            CACHE.put(coverUrl, rl);
            SIZES.put(coverUrl, new int[]{w, h});
            return rl;
        } catch (Throwable t) {
            CmsBlueprints.LOG.warn("封面图解码失败（{}）：{}", coverUrl, t.toString());
            FAILED.add(coverUrl);
            return null;
        }
    }

    public static int[] size(String coverUrl) {
        return SIZES.get(coverUrl);
    }

    /** 从缓存里取已解码纹理；没有就返回 null（不会触发下载）。 */
    public static ResourceLocation cachedTexture(String coverUrl) {
        return coverUrl == null ? null : CACHE.get(coverUrl);
    }

    /** 在网络线程上同步拉取并解码（由 Net.run 调用）；完成后 texture() 即可命中缓存。 */
    public static void prefetchBlocking(String coverUrl) {
        texture(coverUrl);
    }

    private static NativeImage decode(byte[] data) throws Exception {
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
        if (bi == null) {
            throw new IllegalStateException("ImageIO 解不出图（WebP 解码器缺失？）");
        }
        int w = bi.getWidth();
        int h = bi.getHeight();
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int[] rgb = bi.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = rgb[y * w + x];
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int gg = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (gg << 8) | r);
            }
        }
        return img;
    }

    /** 网络线程上的封面拉取（走 CmsClient 的代理与节流）。 */
    private static final class CoverFetcher {
        static byte[] fetch(String coverUrl) {
            try {
                return FETCHER.get().fetch(coverUrl);
            } catch (Exception e) {
                CmsBlueprints.LOG.warn("封面图下载失败（{}）：{}", coverUrl, e.toString());
                return null;
            }
        }
    }

    /** 封面拉取实现：由当前站点的 Site 提供，切换站点时替换。 */
    public interface Fetcher {
        byte[] fetch(String coverUrl) throws Exception;
    }

    private static final java.util.concurrent.atomic.AtomicReference<Fetcher> FETCHER =
            new java.util.concurrent.atomic.AtomicReference<>(coverUrl -> dev.cmsbp.net.Net.client().cover(coverUrl));

    /** 切换站点时调用：换掉拉取实现并清掉失败缓存（同一 URL 在别的站点可能拉得到）。 */
    public static void setFetcher(Fetcher fetcher) {
        FETCHER.set(fetcher == null ? (coverUrl -> dev.cmsbp.net.Net.client().cover(coverUrl)) : fetcher);
        FAILED.clear();
    }
}
