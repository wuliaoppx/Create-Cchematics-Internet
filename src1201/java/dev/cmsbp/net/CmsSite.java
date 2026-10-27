package dev.cmsbp.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** CMS（creativemechanicserver.com）适配层：把 HTML 抓来的字段映射成 {@link Site} 的统一模型。 */
public final class CmsSite implements Site {

    @Override
    public String tag() {
        return "CMS";
    }

    @Override
    public String title() {
        return "CMS 蓝图库";
    }

    @Override
    public String searchHint() {
        return "标题 / 方块 / 功能";
    }

    @Override
    public String[] sorts() {
        return new String[]{"download", "time"};
    }

    @Override
    public String sortLabel(String sort) {
        return "time".equals(sort) ? "最新" : "下载量";
    }

    @Override
    public Page search(String query, String sort, int page) throws IOException {
        SearchPage sp = Net.client().search(query, sort, "down", page);
        List<Entry> entries = new ArrayList<>(sp.posts().size());
        for (PostSummary p : sp.posts()) {
            entries.add(new Entry(String.valueOf(p.postId()), p.title(), p.author(), p.sizeText(), p.stress(),
                    p.versions(), p.desc(), p.downloads(), p.coverUrl()));
        }
        return new Page(entries, sp.page(), sp.pages(), sp.total());
    }

    @Override
    public Detail detail(Entry entry) throws IOException {
        PostDetail d = Net.client().detail(Integer.parseInt(entry.id()));
        List<FileEntry> files = new ArrayList<>(d.files().size());
        for (PostFile f : d.files()) {
            files.add(new FileEntry(String.valueOf(f.blueprintId()), f.name(), f.sizeText(), f.downloads()));
        }
        List<Material> mats = new ArrayList<>();
        if (d.materials() != null) {
            for (HtmlParser.MaterialItem m : d.materials()) {
                mats.add(new Material(m.name(), m.count()));
            }
        }
        return new Detail(entry, files, mats, d.note());
    }

    @Override
    public byte[] cover(String coverUrl) throws IOException {
        return Net.client().cover(coverUrl);
    }

    @Override
    public Download download(FileEntry file) throws IOException {
        CmsClient.Download d = Net.client().download(Integer.parseInt(file.id()));
        return new Download(d.data(), d.filename());
    }

    /** CMS 的文件名不带帖子编号，落盘时补上，方便在图纸桌里区分同名蓝图。 */
    @Override
    public String saveName(Entry entry, FileEntry file) {
        String name = file.name() == null ? "" : file.name();
        return entry.id() + "_" + (name.toLowerCase(java.util.Locale.ROOT).endsWith(".nbt")
                ? name.substring(0, name.length() - 4) : name) + ".nbt";
    }
}
