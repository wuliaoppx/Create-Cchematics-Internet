package dev.cmsbp.net;

import java.io.IOException;
import java.util.List;

/**
 * 一个蓝图站点。界面只认这个接口，CMS（creativemechanicserver.com）与 MCS（mcschematic.top）各自实现。
 *
 * <p>两站的字段差别很大（CMS 从 HTML 卡片上抓文本、MCS 是 JSON API），这里统一成展示用的一套字段，
 * 缺的字段给空串，界面自己做降级。</p>
 */
public interface Site {

    /** 短标签，用在面板标题与按钮上。 */
    String tag();

    /** 面板标题。 */
    String title();

    /** 搜索框提示。 */
    String searchHint();

    /** 排序键（内部值，供 {@link #sortLabel} 转中文）。 */
    String[] sorts();

    /** 排序键 → 按钮文案。 */
    String sortLabel(String sort);

    Page search(String query, String sort, int page) throws IOException;

    Detail detail(Entry entry) throws IOException;

    /** 拉封面图；相对路径也要处理。 */
    byte[] cover(String coverUrl) throws IOException;

    /** 下载蓝图原始字节。 */
    Download download(FileEntry file) throws IOException;

    /** 列表/详情里显示的编号前缀（MCS 用 uuid，不显示）。 */
    default String idLabel(Entry entry) {
        return "#" + entry.id();
    }

    /** 落盘用的文件名；默认用文件自己的名字，CMS 额外加帖子编号前缀。 */
    default String saveName(Entry entry, FileEntry file) {
        return file.name();
    }

    /** 列表里的一条帖子。 */
    record Entry(String id, String title, String author, String sizeText, String stress,
                 String versions, String desc, int downloads, String coverUrl) {
    }

    /** 详情页里的一行可下载文件。 */
    record FileEntry(String id, String name, String sizeText, int downloads) {
    }

    /** 材料统计的一行。 */
    record Material(String name, int count) {
    }

    /** 一个帖子的详情。 */
    record Detail(Entry entry, List<FileEntry> files, List<Material> materials, String note) {
    }

    /** 一页搜索结果。 */
    record Page(List<Entry> entries, int page, int pages, int total) {
    }

    /** 下载结果；{@code fileName} 为空时由调用方用 {@link FileEntry#name()} 兜底。 */
    record Download(byte[] data, String fileName) {
    }
}
