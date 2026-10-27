import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import dev.cmsbp.net.CmsClient;
import dev.cmsbp.net.PostDetail;
import dev.cmsbp.net.PostFile;
import dev.cmsbp.net.PostSummary;
import dev.cmsbp.net.SearchPage;

/**
 * 离线冒烟测试：不开游戏，直接在 JDK 上跑一遍「抓列表 → 抓详情 → 下一个文件」。
 * 用途是验证 TLS / Cloudflare / 正则解析这些「不在 MC 里也可能出错」的环节。
 *
 * 用法（由 build.ps1 -Smoke 调起）：
 *   java -cp "build/classes;build/smoke;<97 个 jar>" SmokeTest [关键词]
 */
public final class SmokeTest {

    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? args[0] : "";
        CmsClient client = new CmsClient();

        long t0 = System.currentTimeMillis();
        client.prime();
        System.out.println("[1] GET / 成功，拿到 CSRF：" + (System.currentTimeMillis() - t0) + " ms");

        t0 = System.currentTimeMillis();
        SearchPage page = client.search(q, "download", "down", 1);
        System.out.println("[2] POST /search/ q=\"" + q + "\"：" + (System.currentTimeMillis() - t0) + " ms"
                + " → 共 " + page.total() + " 条 / " + page.pages() + " 页，本页 " + page.posts().size() + " 条");

        for (PostSummary s : page.posts().subList(0, Math.min(5, page.posts().size()))) {
            System.out.println("     #" + s.postId() + "  " + s.title()
                    + "  |下载 " + s.downloads() + "|版本 " + s.versions()
                    + "|尺寸 " + s.sizeText() + "|应力 " + s.stress());
        }

        if (page.posts().isEmpty()) {
            System.out.println("!! 列表为空，后续步骤跳过");
            return;
        }

        PostSummary first = page.posts().get(0);
        t0 = System.currentTimeMillis();
        List<PostFile> files = client.files(first.postId());
        System.out.println("[3] 详情 #" + first.postId() + "：" + (System.currentTimeMillis() - t0) + " ms"
                + " → 文件 " + files.size() + " 个");
        for (PostFile f : files) {
            System.out.println("     蓝图 #" + f.blueprintId() + "  " + f.name()
                    + "  |下载 " + f.downloads() + "|尺寸 " + f.sizeText());
        }
        if (files.isEmpty()) {
            System.out.println("!! 详情里没解析到文件，后续步骤跳过");
            return;
        }

        PostFile f0 = files.get(0);
        t0 = System.currentTimeMillis();
        CmsClient.Download dl = client.download(f0.blueprintId());
        byte[] data = dl.data();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        System.out.println("[4] 下载蓝图 #" + f0.blueprintId() + "：" + (System.currentTimeMillis() - t0) + " ms"
                + " → " + data.length + " 字节，gzip=" + CmsClient.looksLikeGzip(data));
        System.out.println("     建议文件名：" + dl.filename());
        System.out.println("     sha256：" + sha);
        System.out.println("全部通过。");
    }
}
