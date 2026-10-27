package dev.cmsbp.net;

import java.util.List;

/** 搜索列表里的一条帖子（字段都是从卡片 HTML 上抓的展示用文本，不做业务判断）。 */
public record PostSummary(
        int postId,
        String title,
        String author,
        String datetime,
        String sizeText,
        String stress,
        String desc,
        int downloads,
        String versions,
        String tags,
        String io,
        String coverUrl) {
}
