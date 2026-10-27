import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 离线验证：拉一张站点封面 WebP → twelvemonkeys 解码 → NativeImage 尺寸。
 * 不开游戏（NativeImage 不需要渲染环境，只要有类路径）。
 */
public final class CoverTest {

    public static void main(String[] args) throws Exception {
        System.out.println("ImageIO 可用的 WebP Reader：");
        ImageIO.getImageReadersByFormatName("webp")
                .forEachRemaining(r -> System.out.println("  " + r.getClass().getName()));

        byte[] data;
        if (args.length > 0 && Files.isRegularFile(Path.of(args[0]))) {
            data = Files.readAllBytes(Path.of(args[0]));
        } else {
            String url = args.length > 0 ? args[0]
                    : "https://www.creativemechanicserver.com/upload/post/f620b358-df03-4825-b667-919d6ba0c27a.webp";
            System.out.println("下载 " + url);
            data = new dev.cmsbp.net.CmsClient().cover("/upload/post/"
                    + url.substring(url.lastIndexOf('/') + 1));
        }
        System.out.println("字节：" + data.length);

        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
        if (bi == null) {
            System.out.println("!! 解码失败（WebP reader 缺失）");
            System.exit(1);
        }
        System.out.println("解码成功：" + bi.getWidth() + "x" + bi.getHeight()
                + " 类型=" + bi.getType());
        int px = bi.getRGB(0, 0);
        System.out.println("首像素 ARGB=" + Integer.toHexString(px));
        System.out.println("通过。（游戏内再转 NativeImage/DynamicTexture，离线环境无 LWJGL 故不测）");
    }
}
