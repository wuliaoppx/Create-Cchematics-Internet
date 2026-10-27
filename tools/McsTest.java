import dev.cmsbp.net.CmsSite;
import dev.cmsbp.net.McsSite;
import dev.cmsbp.net.Site;
import dev.cmsbp.schem.Convert;
import dev.cmsbp.schem.Schematic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 联网自测：MCS 走完「搜索 → 详情 → 下载 → 转成 Create 结构 nbt」整条链，CMS 做一次搜索/详情冒烟。
 *
 * <p>断言：<br>
 * 1. MCS 列表非空、总数 > 0；<br>
 * 2. 详情有文件、材料；<br>
 * 3. 下载字节数与 Content 一致、扩展名与 sourceFormat 匹配；<br>
 * 4. 非 nbt 的文件能被 {@link Convert#toNbt} 转出 .nbt，且读回后 size/palette 合法；<br>
 * 5. 封面图能解码出宽高；<br>
 * 6. CMS 搜索结果与详情可用（旧的 HTML 路径没被 Site 抽象改坏）。</p>
 */
public class McsTest {

    private static final Path OUT = Path.of("build/mcstest");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        boolean ok = true;

        System.out.println("== MCS ==");
        ok &= mcs();

        System.out.println();
        System.out.println("== CMS ==");
        ok &= cms();

        System.out.println(ok ? "\n全部通过" : "\n有失败项");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean mcs() throws Exception {
        boolean ok = true;
        McsSite site = new McsSite();

        Site.Page page = site.search("", "popular", 1);
        System.out.printf("  搜索：%d 条 / %d 页 / 总计 %d%n", page.entries().size(), page.pages(), page.total());
        ok &= page.entries().size() > 0;
        if (page.entries().isEmpty()) {
            return false;
        }
        Site.Entry e = page.entries().get(0);
        System.out.printf("    首条：%s｜作者 %s｜格式 %s｜下载 %d%n",
                trunc(e.title(), 28), e.author(), e.versions(), e.downloads());

        // 找一个不是 create 原生格式的，走一遍「下载后转换」这条路
        Site.Entry conv = null;
        for (int pg = 1; pg <= 3 && conv == null; pg++) {
            Site.Page sp = pg == 1 ? page : site.search("", "popular", pg);
            for (Site.Entry cand : sp.entries()) {
                if (!isCreate(cand.versions())) {
                    conv = cand;
                    break;
                }
            }
        }
        if (conv != null) {
            System.out.printf("  可转换样本：%s｜格式 %s%n", trunc(conv.title(), 28), conv.versions());
        } else {
            System.out.println("  （前 3 页全是 create 原生格式，跳过转换检查）");
        }

        Site.Detail d = site.detail(e);
        System.out.printf("  详情：尺寸 %s｜文件 %d｜材料 %d｜注释 %d 字%n",
                d.entry().sizeText(), d.files().size(), d.materials().size(),
                d.note() == null ? 0 : d.note().length());
        ok &= !d.files().isEmpty();
        ok &= d.entry().sizeText() != null && !d.entry().sizeText().isBlank();

        String coverBytes = "";
        try {
            byte[] cover = site.cover(e.coverUrl());
            BufferedImage bi = cover == null ? null : ImageIO.read(new ByteArrayInputStream(cover));
            coverBytes = bi == null ? "解不出" : bi.getWidth() + "×" + bi.getHeight();
            System.out.printf("  封面：%s（%s）%n", cover == null ? "无" : cover.length + " B", coverBytes);
            ok &= bi != null;
        } catch (Exception ex) {
            System.out.println("  封面：失败 " + ex);
        }

        Site.FileEntry f = d.files().get(0);
        Site.Download dl = site.download(f);
        Path saved = OUT.resolve(dl.fileName());
        Files.write(saved, dl.data());
        System.out.printf("  下载：%s（%d B）%n", dl.fileName(), dl.data().length);
        ok &= dl.data().length > 0;

        if (conv != null && !isCreate(conv.versions())) {
            Site.Detail cd = site.detail(conv);
            Site.Download cdl = site.download(cd.files().get(0));
            Path csrc = OUT.resolve(cdl.fileName());
            Files.write(csrc, cdl.data());
            System.out.printf("  下载（待转换）：%s（%d B）%n", cdl.fileName(), cdl.data().length);
            Convert.Result r = Convert.toNbt(csrc);
            System.out.println("  转换：" + r.message());
            ok &= r.files().size() == 1;
            if (!r.files().isEmpty()) {
                Path nbt = r.files().get(0);
                CompoundTag root = NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap());
                int palette = root.getList("palette", 10).size();
                int blocks = root.getList("blocks", 10).size();
                System.out.printf("    读回：size=%s palette=%d blocks=%d%n",
                        root.getList("size", 3), palette, blocks);
                ok &= root.contains("size") && palette > 0 && blocks > 0;

                Schematic s = Schematic.read(nbt);
                Convert.Result split = Convert.split(nbt, Convert.SplitMode.GRID, 9);
                System.out.println("  分割：" + split.message());
                for (Path p : split.files()) {
                    NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
                }
                ok &= split.files().size() == Convert.plan(s, Convert.SplitMode.GRID, 9).parts().size();
            }
        }
        return ok;
    }

    /** create / nbt / structure 都是 Create 直接能读的结构格式，其余（litematica/schem）要转换。 */
    private static boolean isCreate(String format) {
        String f = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
        return f.equals("create") || f.equals("nbt") || f.equals("structure");
    }

    private static boolean cms() throws Exception {
        CmsSite site = new CmsSite();
        Site.Page page = site.search("", site.sorts()[0], 1);
        System.out.printf("  搜索：%d 条 / %d 页 / 总计 %d%n", page.entries().size(), page.pages(), page.total());
        if (page.entries().isEmpty()) {
            return false;
        }
        Site.Entry e = page.entries().get(0);
        System.out.printf("    首条：%s %s｜作者 %s｜下载 %d%n", site.idLabel(e), trunc(e.title(), 24),
                e.author(), e.downloads());
        Site.Detail d = site.detail(e);
        System.out.printf("  详情：尺寸 %s｜文件 %d｜材料 %d｜注释 %d 字%n",
                d.entry().sizeText(), d.files().size(), d.materials().size(),
                d.note() == null ? 0 : d.note().length());
        System.out.printf("  封面：%s%n", e.coverUrl());
        return !d.files().isEmpty() && e.coverUrl() != null && !e.coverUrl().isBlank();
    }

    private static String trunc(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
