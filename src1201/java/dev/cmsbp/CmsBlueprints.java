package dev.cmsbp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraftforge.fml.common.Mod;

@Mod(CmsBlueprints.MODID)
public final class CmsBlueprints {

    public static final String MODID = "blueprintnet";
    public static final String VERSION = "0.1.0";
    public static final Logger LOG = LoggerFactory.getLogger(MODID);

    public CmsBlueprints() {
        CmsConfig.load();
        // 用 ASCII：控制台/日志按 GBK 写时会把这行中文变成乱码，排查时反而看不清路径
        LOG.info("blueprintnet {} loaded; config={}; schematics={}",
                VERSION, CmsConfig.file(), CreateBridge.schematicsDir());
    }
}
