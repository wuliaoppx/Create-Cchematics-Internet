package dev.cmsbp.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.cmsbp.CmsBlueprints;
import dev.cmsbp.CmsConfig;

/**
 * 站点的唯一出口。只用 JDK 自带的 java.net.http，无第三方依赖，也不调用任何 shell。
 *
 * 站点要求（实测）：POST /search/ 必须带 csrfmiddlewaretoken 表单字段 + Referer，
 * 缺任何一个都是 403；token 过期时重取一次首页即可。
 */
public final class CmsClient {

    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;

    private final String base;
    private final String ua;
    private final long intervalMs;
    private final HttpClient http;

    private volatile String csrf;
    private long lastRequestAt = 0L;

    public CmsClient() {
        this.base = CmsConfig.site();
        this.ua = CmsConfig.userAgent();
        this.intervalMs = CmsConfig.requestIntervalMs();

        HttpClient.Builder b = HttpClient.newBuilder()
                .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(CmsConfig.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector sel = proxySelector();
        if (sel != null) {
            b.proxy(sel);
        }
        this.http = b.build();
    }

    // ------------------------------------------------------------ 会话

    public synchronized void prime() throws IOException {
        HttpResponse<String> r = get(base + "/", null);
        if (r.statusCode() != 200) {
            throw new IOException("GET / 返回 HTTP " + r.statusCode());
        }
        String token = HtmlParser.csrfToken(r.body());
        if (token == null || token.isEmpty()) {
            throw new IOException("首页里找不到 csrfmiddlewaretoken（可能被 Cloudflare 挑战页替换）");
        }
        this.csrf = token;
    }

    // ------------------------------------------------------------ 搜索

    public SearchPage search(String q, String sort, String order, int page) throws IOException {
        if (csrf == null) {
            prime();
        }
        HttpResponse<String> r = postSearch(q, sort, order, page);
        if (r.statusCode() == 403) {
            CmsBlueprints.LOG.debug("POST /search/ 403，重取 CSRF 后重试");
            prime();
            r = postSearch(q, sort, order, page);
        }
        if (r.statusCode() != 200) {
            throw new IOException("POST /search/ 返回 HTTP " + r.statusCode());
        }
        int[] meta = HtmlParser.parseMeta(r.body());
        List<PostSummary> posts = HtmlParser.parseCards(r.body());
        int pages = Math.max(meta[1], (Math.max(meta[0], 0) + 19) / 20);
        return new SearchPage(posts, page, Math.max(1, pages), meta[0]);
    }

    private HttpResponse<String> postSearch(String q, String sort, String order, int page) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        // 站点后端会把缺失字段当默认值，宁可全给（照抄 cms_tool.py 的 SEARCH_FORM）
        form.put("search_type", "t");
        form.put("q", q == null ? "" : q);
        form.put("consume", "");
        form.put("product", "");
        form.put("loader_type", "any");
        form.put("mc_type", "any");
        form.put("create_type", "any");
        form.put("z_size", "");
        form.put("x_size", "");
        form.put("y_size", "");
        form.put("function", "");
        form.put("grid_col", "1");
        form.put("sort", sort == null ? "download" : sort);
        form.put("order", order == null ? "down" : order);
        form.put("page", String.valueOf(page));
        form.put("csrfmiddlewaretoken", csrf);

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/search/"))
                .timeout(Duration.ofSeconds(CmsConfig.timeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", base + "/")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
                .build();
        return send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ 详情

    public PostDetail detail(int postId) throws IOException {
        HttpResponse<String> r = get(base + "/detail/" + postId + "/", base + "/search/");
        if (r.statusCode() == 404) {
            throw new IOException("帖子 #" + postId + " 不存在（404）");
        }
        if (r.statusCode() != 200) {
            throw new IOException("GET /detail/" + postId + "/ 返回 HTTP " + r.statusCode());
        }
        String html = r.body();
        return new PostDetail(null, HtmlParser.parseFiles(html),
                HtmlParser.parseMaterials(html), HtmlParser.parseNote(html));
    }

    /**
     * 拉帖子封面图（站点上是 WebP，{@code /upload/post/<uuid>.webp}）。
     *
     * @param coverUrl 形如 {@code /upload/post/xxx.webp} 的相对路径；空串/blank 返回 {@code null}
     */
    public byte[] cover(String coverUrl) throws IOException {
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        String url = coverUrl.startsWith("http") ? coverUrl : base + coverUrl;
        return getBytes(url, base + "/");
    }

    // ------------------------------------------------------------ 下载

    public Download download(int blueprintId) throws IOException {
        HttpResponse<String> relay = get(base + "/download/" + blueprintId + "/", base + "/");
        if (relay.statusCode() == 404) {
            throw new IOException("蓝图 #" + blueprintId + " 不存在（404）");
        }
        if (relay.statusCode() != 200) {
            throw new IOException("GET /download/" + blueprintId + "/ 返回 HTTP " + relay.statusCode());
        }
        String[] link = HtmlParser.parseNbtLink(relay.body());
        if (link == null) {
            throw new IOException("中转页里没找到 .nbt 直链");
        }
        byte[] data = getBytes(base + link[0], base + "/download/" + blueprintId + "/");
        if (!looksLikeGzip(data)) {
            throw new IOException("下载内容不是 gzip 压缩的 NBT（" + data.length + " 字节），可能拿到了错误页");
        }
        return new Download(data, link[1]);
    }

    public record Download(byte[] data, String filename) {
    }

    /** 蓝图文件本身是 gzip 压缩的 NBT。 */
    public static boolean looksLikeGzip(byte[] d) {
        return d != null && d.length > 2 && (d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B;
    }

    // ------------------------------------------------------------ 底层

    private HttpResponse<String> get(String url, String referer) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(CmsConfig.timeoutSeconds()))
                .GET();
        if (referer != null) {
            b.header("Referer", referer);
        }
        return send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private byte[] getBytes(String url, String referer) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(CmsConfig.timeoutSeconds()))
                .GET();
        if (referer != null) {
            b.header("Referer", referer);
        }
        HttpResponse<InputStream> r = send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            try (InputStream in = r.body()) {
                in.readAllBytes();
            } catch (IOException ignored) {
                // 丢弃
            }
            throw new IOException("文件请求返回 HTTP " + r.statusCode());
        }
        try (InputStream in = r.body()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("文件超过 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + " MB，已中止");
                }
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        throttle();
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        } catch (IOException e) {
            throw new IOException("连不上 " + req.uri().getHost() + "：" + e.getMessage(), e);
        }
    }

    private void throttle() {
        long now = System.currentTimeMillis();
        long wait = lastRequestAt + intervalMs - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static ProxySelector proxySelector() {
        String spec = CmsConfig.proxy();
        if (spec.isEmpty()) {
            for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
                String v = System.getenv(key);
                if (v != null && !v.isBlank()) {
                    spec = v;
                    break;
                }
            }
        }
        if (spec == null || spec.isBlank()) {
            return ProxySelector.getDefault();
        }
        spec = spec.replaceFirst("(?i)^https?://", "").trim();
        int colon = spec.lastIndexOf(':');
        if (colon <= 0) {
            CmsBlueprints.LOG.warn("代理格式应为 host:port，当前为 '{}'，改为直连", spec);
            return ProxySelector.getDefault();
        }
        String host = spec.substring(0, colon);
        try {
            int port = Integer.parseInt(spec.substring(colon + 1));
            CmsBlueprints.LOG.info("使用代理 {}:{}", host, port);
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (NumberFormatException e) {
            CmsBlueprints.LOG.warn("代理端口解析失败：'{}'，改为直连", spec);
            return ProxySelector.getDefault();
        }
    }

    public String siteBase() {
        return base;
    }

    public Map<String, List<String>> noop() {
        return Collections.emptyMap();
    }
}
