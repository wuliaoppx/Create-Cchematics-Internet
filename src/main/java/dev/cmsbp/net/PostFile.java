package dev.cmsbp.net;

/** 详情页里的一行蓝图文件（对应一个 /download/<blueprintId>/ 链接）。 */
public record PostFile(int blueprintId, String name, int downloads, String sizeText) {
}
