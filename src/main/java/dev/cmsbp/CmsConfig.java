package dev.cmsbp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 极简配置：config/schematicsinternet.properties（旧名 blueprintnet.properties / cms-blueprints.properties 会自动迁移）。
 * 用 Properties 而不是 TOML，是为了不引第三方库、也不踩 NeoForge 配置 API 的版本差异。
 */
public final class CmsConfig {

    private static final Properties PROPS = new Properties();
    private static Path file;

    private CmsConfig() {
    }

    public static void load() {
        file = FMLPaths.CONFIGDIR.get().resolve("schematicsinternet.properties");
        if (!Files.exists(file)) {
            for (String oldName : new String[] { "blueprintnet.properties", "cms-blueprints.properties" }) {
                Path legacy = FMLPaths.CONFIGDIR.get().resolve(oldName);
                if (!Files.exists(legacy)) {
                    continue;
                }
                try {
                    Files.copy(legacy, file);
                    CmsBlueprints.LOG.info("已把旧配置 {} 迁移成 {}", oldName, file.getFileName());
                    break;
                } catch (IOException e) {
                    CmsBlueprints.LOG.warn("迁移旧配置 {} 失败：{}", oldName, e.toString());
                }
            }
        }
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                PROPS.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                CmsBlueprints.LOG.warn("读取配置失败，使用默认值：{}", e.toString());
            }
        } else {
            PROPS.setProperty("site", "https://www.creativemechanicserver.com");
            PROPS.setProperty("userAgent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36");
            PROPS.setProperty("proxy", "");           // host:port，留空 = 环境变量 / 系统代理
            PROPS.setProperty("timeoutSeconds", "30");
            PROPS.setProperty("requestIntervalMs", "300");
            save();
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                PROPS.store(out, "schematicsinternet");
            }
        } catch (IOException e) {
            CmsBlueprints.LOG.warn("写入配置失败：{}", e.toString());
        }
    }

    public static String site() {
        return PROPS.getProperty("site", "https://www.creativemechanicserver.com").replaceAll("/+$", "");
    }

    public static String userAgent() {
        return PROPS.getProperty("userAgent", "Mozilla/5.0");
    }

    public static String proxy() {
        return PROPS.getProperty("proxy", "").trim();
    }

    public static int timeoutSeconds() {
        return intProp("timeoutSeconds", 30);
    }

    public static long requestIntervalMs() {
        return intProp("requestIntervalMs", 300);
    }

    public static Path file() {
        return file;
    }

    private static int intProp(String key, int def) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
