package dev.cmsbp.net;

import java.util.List;

/** 一个帖子的详情：摘要 + 文件列表 + 材料统计（仅供参考）+ 注释。 */
public record PostDetail(
        PostSummary summary,
        List<PostFile> files,
        List<HtmlParser.MaterialItem> materials,
        String note) {
}
