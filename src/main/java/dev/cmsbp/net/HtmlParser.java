package dev.cmsbp.net;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站点 HTML 解析。站点是 Django 服务端渲染 + Cloudflare，没有 JSON 接口，
 * 所以只能按卡片/行的结构抓文本。正则全部照搬自 cms/cms_tool.py（那里实测过）。
 */
public final class HtmlParser {

    private static final Pattern CARD = Pattern.compile(
            "<a href=\"/detail/(\\d+)/\" class=\"list_result\">([\\s\\S]*?)</a>");
    private static final Pattern COVER = Pattern.compile(
            "src=\"(/upload/post/[^\"]+\\.webp)\"");
    private static final Pattern CARD_TITLE = Pattern.compile("<h2 class=\"b title oh\">(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern VERSION_BLOCK = Pattern.compile(
            "<div class=\"f version\">([\\s\\S]*?)</div><div class=\"download\">", Pattern.DOTALL);
    private static final Pattern VERSION_ITEM = Pattern.compile("<div class=\"t\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern IO_BLOCK = Pattern.compile(
            "<div class=\"io\">([\\s\\S]*?)</div><div class=\"function\">", Pattern.DOTALL);
    private static final Pattern ARROW_IMG = Pattern.compile("<img[^>]*arrow filter_r[^>]*>");
    private static final Pattern ITEM = Pattern.compile("alt=\"([^\"]+)\">[\\s\\S]{0,240}?<span>\\s*([\\w.]+)\\s*</span>");
    private static final Pattern TAG = Pattern.compile("class=\"(\\w+_bg) tag\">([^<]+)<");
    private static final Pattern TIME = Pattern.compile("<time class=\"op5 time\" datetime=\"([^\"]+)\"");
    private static final Pattern AUTHOR = Pattern.compile("class=\"op5 b nw oh author\">作者：([^<]*)");
    private static final Pattern SIZE = Pattern.compile("<div class=\"size\">尺寸：([^<]*)");
    private static final Pattern DESC = Pattern.compile("<div class=\"desc oh ow\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern STRESS = Pattern.compile("<div class=\"stress\">应力：([^<]*)");
    private static final Pattern DOWNLOADS = Pattern.compile("<div class=\"download\">下载量：([^<]*)");
    private static final Pattern META = Pattern.compile("(\\d+)\\s*个结果[，,]\\s*(\\d+)\\s*页");
    private static final Pattern ONLY_TOTAL = Pattern.compile("(\\d+)\\s*个结果");
    private static final Pattern FILE_ROW = Pattern.compile(
            "<div>([^<]+)</div>\\s*<div>下载量\\s*(\\d+)</div>\\s*<div>尺寸([\\s\\S]{0,120}?)</div>\\s*"
                    + "<a href=\"(/download/(\\d+)/)\"");
    private static final Pattern NBT_LINK = Pattern.compile(
            "href=\"(/upload/blueprint/[^\"]+\\.nbt)\"(?:\\s+download=\"([^\"]*)\")?");

    private HtmlParser() {
    }

    // ------------------------------------------------------------ 搜索页

    public static List<PostSummary> parseCards(String html) {
        List<PostSummary> out = new ArrayList<>();
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            String pid = m.group(1);
            String card = m.group(2);

            String title = stripTags(first(card, CARD_TITLE, 1));

            List<String> versions = new ArrayList<>();
            String vblock = first(card, VERSION_BLOCK, 1);
            if (vblock != null) {
                Matcher vm = VERSION_ITEM.matcher(vblock);
                while (vm.find()) {
                    String t = stripTags(vm.group(1)).trim();
                    if (!t.isEmpty()) {
                        versions.add(unescape(t));
                    }
                }
            }

            String io = "";
            String ioBlock = first(card, IO_BLOCK, 1);
            if (ioBlock != null) {
                String norm = ARROW_IMG.matcher(ioBlock).replaceAll("<ARROW>");
                int cut = norm.indexOf("<ARROW>");
                String left = cut >= 0 ? norm.substring(0, cut) : norm;
                String right = cut >= 0 ? norm.substring(cut + 7) : "";
                String l = joinItems(left);
                String r = joinItems(right);
                io = l.isEmpty() && r.isEmpty() ? "" : l + " -> " + r;
            }

            List<String> tags = new ArrayList<>();
            Matcher tm = TAG.matcher(card);
            while (tm.find()) {
                String t = unescape(tm.group(2)).trim();
                if (!t.isEmpty()) {
                    tags.add(t);
                }
            }

            String cover = first(card, COVER, 1);
            out.add(new PostSummary(
                    parseInt(pid, 0),
                    unescape(nz(title)),
                    unescape(nz(first(card, AUTHOR, 1)).trim()),
                    nz(first(card, TIME, 1)).trim(),
                    nz(first(card, SIZE, 1)).trim(),
                    nz(first(card, STRESS, 1)).trim(),
                    collapse(unescape(nz(first(card, DESC, 1)))),
                    num(first(card, DOWNLOADS, 1)),
                    String.join(" ", versions),
                    String.join("·", tags),
                    io,
                    cover == null ? "" : cover));
        }
        return out;
    }

    /** 返回 {total, pages}，解析不到时 pages 用 1。 */
    public static int[] parseMeta(String html) {
        Matcher m = META.matcher(html);
        if (m.find()) {
            return new int[]{parseInt(m.group(1), 0), Math.max(1, parseInt(m.group(2), 1))};
        }
        Matcher t = ONLY_TOTAL.matcher(html);
        if (t.find()) {
            int total = parseInt(t.group(1), 0);
            return new int[]{total, Math.max(1, (total + 19) / 20)};
        }
        return new int[]{0, 1};
    }

    // ------------------------------------------------------------ 详情页

    /** 详情页材料条目：站点给的中文名 + 数量 + 图标路径。 */
    public record MaterialItem(String name, int count, String iconUrl) {
    }

    private static final Pattern TIP_BOX = Pattern.compile(
            "<div class=\"tip_box\">[\\s\\S]{0,600}?</div></div></div>", Pattern.DOTALL);
    private static final Pattern TIP_IMG = Pattern.compile(
            "src=\"(/upload/Tag/[^\"]+\\.png)\"\\s+alt=\"([^\"]+)\"");
    private static final Pattern TIP_COUNT = Pattern.compile("<span>\\s*(\\d+)\\s*</span>");
    private static final Pattern NOTE_BLOCK = Pattern.compile(
            "<h4 class=\"gap_t\">注释</h4><hr><div class=\"gap_x\">([\\s\\S]*?)</div>", Pattern.DOTALL);

    /** 材料统计（站点标注“仅供参考”）：图标 + 名字 + 数量；解析失败返回空表。 */
    public static List<MaterialItem> parseMaterials(String html) {
        List<MaterialItem> out = new ArrayList<>();
        Matcher m = TIP_BOX.matcher(html);
        while (m.find()) {
            String box = m.group();
            Matcher img = TIP_IMG.matcher(box);
            if (!img.find()) {
                continue;
            }
            String name = unescape(img.group(2)).trim();
            String icon = img.group(1);
            Matcher cnt = TIP_COUNT.matcher(box);
            int count = cnt.find() ? parseInt(cnt.group(1), 0) : 0;
            out.add(new MaterialItem(name, count, icon));
        }
        return out;
    }

    /** 作者注释（材料统计上方，可为空）。 */
    public static String parseNote(String html) {
        Matcher m = NOTE_BLOCK.matcher(html);
        if (!m.find()) {
            return "";
        }
        String body = stripTags(m.group(1));
        return body == null ? "" : body.trim();
    }

    public static List<PostFile> parseFiles(String html) {
        List<PostFile> out = new ArrayList<>();
        Matcher m = FILE_ROW.matcher(html);
        while (m.find()) {
            String name = collapse(unescape(m.group(1)));
            String size = collapse(unescape(m.group(3))).replaceFirst("^[：:]\\s*", "");
            out.add(new PostFile(parseInt(m.group(5), 0), name, parseInt(m.group(2), 0), size));
        }
        return out;
    }

    /** 下载中转页里的文件直链：[{path, filename}]，filename 可能为 null。 */
    public static String[] parseNbtLink(String html) {
        Matcher m = NBT_LINK.matcher(html);
        if (!m.find()) {
            return null;
        }
        String path = m.group(1);
        String fname = m.group(2);
        String decoded = null;
        if (fname != null && !fname.isEmpty()) {
            try {
                decoded = java.net.URLDecoder.decode(fname, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                decoded = fname;
            }
        }
        return new String[]{path, decoded};
    }

    public static String csrfToken(String html) {
        return first(html, Pattern.compile("name=\"csrfmiddlewaretoken\" value=\"([^\"]+)\""), 1);
    }

    // ------------------------------------------------------------ 工具

    private static String joinItems(String block) {
        List<String> parts = new ArrayList<>();
        Matcher m = ITEM.matcher(block);
        while (m.find()) {
            String name = m.group(1);
            if ("to".equals(name)) {
                continue;   // 流向箭头的 alt
            }
            parts.add(unescape(name) + "x" + m.group(2));
        }
        return String.join(", ", parts);
    }

    private static String first(String text, Pattern p, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "");
    }

    public static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    public static String unescape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) {
                sb.append(c);
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = switch (ent) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> null;
            };
            if (rep == null && ent.startsWith("#")) {
                try {
                    int cp = ent.startsWith("#x") || ent.startsWith("#X")
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    rep = new String(Character.toChars(cp));
                } catch (Exception ignored) {
                    rep = null;
                }
            }
            if (rep == null) {
                sb.append(c);
            } else {
                sb.append(rep);
                i = semi;
            }
        }
        return sb.toString();
    }

    /** '61144' / '7.2k' / '1.2万' -> int，解析不了返回 0 */
    public static int num(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = Pattern.compile("^([\\d.]+)\\s*([kKmM万]?)$").matcher(text.trim().replace(",", ""));
        if (!m.find()) {
            return 0;
        }
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
        double mul = switch (m.group(2)) {
            case "k", "K" -> 1e3;
            case "m", "M" -> 1e6;
            case "万" -> 1e4;
            default -> 1.0;
        };
        return (int) Math.round(v * mul);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
