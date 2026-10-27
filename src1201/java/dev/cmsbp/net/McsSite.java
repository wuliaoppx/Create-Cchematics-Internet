package dev.cmsbp.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MCS（mcschematic.top）适配层。
 *
 * <p>公开 API 全部在 {@code https://mcschematic.top/api/v1}，匿名即可读（{@code anonymouslyReadable:true}），
 * 不需要 token。列表用 {@code GET /blueprints?page&limit&sort&mod=create[&q]}：
 * {@code mod=create} 只留下用到 create 命名空间的作品，{@code sort} 取 {@code popular|latest}。
 * 分页是 page/pageSize/totalPages/totalCount，不是 cursor。</p>
 *
 * <p>下载：{@code GET /blueprints/{id}/versions/{n}/download}，返回源文件原字节
 * （{@code application/octet-stream}，没有 Content-Disposition，文件名得自己按 sourceFormat 定）。</p>
 */
public final class McsSite implements Site {

    private static final String BASE = "https://mcschematic.top/api/v1";
    private static final String UA = "schematicsinternet/0.1 (Forge mod; +https://mcschematic.top)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String tag() {
        return "MCS";
    }

    @Override
    public String title() {
        return "MCS 蓝图库";
    }

    @Override
    public String searchHint() {
        return "标题 / 关键词（留空=热门）";
    }

    @Override
    public String[] sorts() {
        return new String[]{"popular", "latest"};
    }

    /** MCS 的作品编号是 uuid，显示出来只是噪音。 */
    @Override
    public String idLabel(Entry entry) {
        return "";
    }

    @Override
    public String sortLabel(String sort) {
        return "latest".equals(sort) ? "最新" : "热门";
    }

    @Override
    public Page search(String query, String sort, int page) throws IOException {
        StringBuilder path = new StringBuilder("/blueprints?page=").append(Math.max(1, page))
                .append("&limit=20")
                .append("&sort=").append("latest".equals(sort) ? "latest" : "popular")
                .append("&mod=create");
        if (query != null && !query.isBlank()) {
            path.append("&q=").append(enc(query));
        }
        JsonObject root = obj(new String(get(path.toString(), true), StandardCharsets.UTF_8));
        List<Entry> entries = new ArrayList<>();
        JsonArray items = arr(root, "items");
        for (JsonElement e : items) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject it = e.getAsJsonObject();
            String fmt = str(obj(it, "currentVersion"), "sourceFormat");
            JsonObject author = obj(it, "author");
            JsonObject cat = obj(it, "category");
            entries.add(new Entry(
                    str(it, "id"),
                    str(it, "title"),
                    str(author, "displayName"),
                    fmt,
                    str(cat, "name"),
                    fmt,
                    str(it, "description"),
                    (int) lng(obj(it, "engagement"), "downloadCount"),
                    str(it, "previewUrl")));
        }
        int pages = Math.max(1, (int) lng(root, "totalPages"));
        int total = (int) lng(root, "totalCount");
        int cur = (int) lng(root, "page");
        return new Page(entries, cur > 0 ? cur : page, pages, total);
    }

    @Override
    public Detail detail(Entry entry) throws IOException {
        JsonObject root = obj(new String(get("/blueprints/" + enc(entry.id()), true), StandardCharsets.UTF_8));
        JsonObject version = obj(root, "currentVersion");
        int number = (int) lng(version, "number");
        String fmt = str(version, "sourceFormat");
        JsonObject analysis = obj(root, "trustedAnalysis");
        JsonObject dims = obj(analysis, "dimensions");
        String size = "";
        if (dims.has("x")) {
            size = (int) lng(dims, "x") + "×" + (int) lng(dims, "y") + "×" + (int) lng(dims, "z");
        }
        String title = str(root, "title");
        Entry full = new Entry(
                entry.id(),
                title,
                str(obj(root, "author"), "displayName"),
                size,
                fmt,
                fmt,
                str(root, "description"),
                (int) lng(obj(root, "engagement"), "downloadCount"),
                str(root, "previewUrl"));

        // 一个作品就是一个文件；id 里带上版本号，下载时不用再查一遍
        List<FileEntry> files = new ArrayList<>(1);
        files.add(new FileEntry(entry.id() + "#" + number, baseName(title) + extOf(fmt), size,
                (int) lng(obj(root, "engagement"), "downloadCount")));

        List<Material> mats = new ArrayList<>();
        JsonArray ms = arr(analysis, "materials");
        for (JsonElement e : ms) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            mats.add(new Material(shortId(str(m, "blockId")), (int) lng(m, "count")));
        }

        String note = str(root, "description");
        return new Detail(full, files, mats, note);
    }

    @Override
    public byte[] cover(String coverUrl) throws IOException {
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
            return fetch(coverUrl, false);
        }
        // previewUrl 形如 /api/v1/blueprints/<uuid>/versions/1/preview?r=…，BASE 里已含 /api/v1，
        // 所以只剥掉 /api/v1 这一段，不能只剥 /api（否则拼出 /api/v1/v1/… 直接 404）
        String path = coverUrl;
        if (path.startsWith("/api/v1/")) {
            path = path.substring("/api/v1".length());
        } else if (path.startsWith("/api/")) {
            path = path.substring("/api".length());
        }
        return get(path, false);
    }

    @Override
    public Download download(FileEntry file) throws IOException {
        String id = file.id();
        String uuid = id;
        int version = 1;
        int hash = id.indexOf('#');
        if (hash > 0) {
            uuid = id.substring(0, hash);
            try {
                version = Integer.parseInt(id.substring(hash + 1));
            } catch (NumberFormatException ignored) {
                version = 1;
            }
        }
        byte[] data = get("/blueprints/" + enc(uuid) + "/versions/" + version + "/download", false);
        return new Download(data, file.name());
    }

    // ---------------------------------------------------------------- HTTP

    private byte[] get(String path, boolean json) throws IOException {
        return fetch(BASE + path, json);
    }

    private byte[] fetch(String url, boolean json) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", UA)
                .header("Accept", json ? "application/json" : "*/*")
                .GET();
        HttpResponse<byte[]> resp = send(b.build());
        if (resp.statusCode() == 429) {
            // API_RATE_LIMITED / SEARCH_RATE_LIMITED：等 Retry-After 再来一次
            long wait = 3_000L;
            String ra = resp.headers().firstValue("Retry-After").orElse(null);
            if (ra != null) {
                try {
                    wait = Math.min(30_000L, Math.max(500L, Long.parseLong(ra.trim()) * 1000L));
                } catch (NumberFormatException ignored) {
                    // 保留默认值
                }
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            resp = send(b.build());
        }
        int code = resp.statusCode();
        if (code >= 400) {
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            throw new IOException("MCS HTTP " + code + (body.isBlank() ? "" : "：" + body.substring(0, Math.min(120, body.length()))));
        }
        return resp.body();
    }

    private static HttpResponse<byte[]> send(HttpRequest req) throws IOException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCS 请求被中断", e);
        }
    }

    // ---------------------------------------------------------------- JSON 小工具

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static long lng(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? 0L : e.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** {@code minecraft:white_stained_glass} → {@code white_stained_glass}（材料表按命名空间过滤太啰嗦）。 */
    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** 去掉标题里自带的扩展名，避免 {@code xxx.nbt.nbt}。 */
    private static String baseName(String title) {
        String t = title == null ? "" : title.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".litematic", ".schematic", ".schem", ".nbt", ".schematic"}) {
            if (lower.endsWith(ext)) {
                return t.substring(0, t.length() - ext.length());
            }
        }
        return t.isEmpty() ? "blueprint" : t;
    }

    /** 源格式 → 落盘扩展名：Create 只吃结构 .nbt，别的格式下载后要转换。 */
    public static String extOf(String sourceFormat) {
        String f = sourceFormat == null ? "" : sourceFormat.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "create", "nbt", "structure" -> ".nbt";
            case "litematica" -> ".litematic";
            case "worldedit", "sponge", "schem" -> ".schem";
            case "mcedit", "schematic" -> ".schematic";
            default -> ".bin";
        };
    }
}
