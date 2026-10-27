package dev.cmsbp.net;

import java.util.List;

/** 一次搜索的结果页。posts 是当前页的 20 条。 */
public record SearchPage(List<PostSummary> posts, int page, int pages, int total) {
}
