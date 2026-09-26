package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 代理探测与设置（国内用户的刚需）。
 *
 * <p><b>为什么要做"三层探活"</b>——实测踩过的三种"看起来有代理其实没用"的状态：
 * <ol>
 *   <li><b>端口在听、但建不了隧道</b>：代理软件开着但端口不是 HTTP 代理（或已被占用）；</li>
 *   <li><b>隧道通、但出不去</b>：能连上代理，可代理的出口节点/订阅已经挂了——
 *       实测某次状态：经代理访问百度 <b>200 / 0.2s</b>（隧道是好的），但访问
 *       {@code api.modrinth.com} 与 {@code cdn.modrinth.com} <b>全部超时 / TLS 握手失败</b>。
 *       只看"端口在不在听"会把这种状态当成"有代理"，然后一路卡死；</li>
 *   <li><b>系统代理开关关着，但端口还在听</b>（Clash 常见：关掉"系统代理"但核心还在跑）——
 *       只读注册表 {@code ProxyEnable} 会漏判，于是去直连被墙的 CDN，傻等几百秒。</li>
 * </ol>
 * 所以探活必须"端到端"：**端口 → 隧道 → 真能访问 Modrinth** 三层都过才算"可用代理"。
 *
 * <p>取值优先级：{@code --proxy} → 软件设置 → 环境变量 → 系统代理注册表 → **本机常见代理端口扫描**。
 */
public final class ProxyConfig {

    /** 探活结论。只有 {@link #OK} 才允许被当成"有代理可用"。 */
    public enum Status {
        /** 没有可用代理（没配、也没探测到） */
        NONE,
        /** 端口在听，但建不起 HTTP 隧道 */
        PORT_ONLY,
        /** 隧道通（能连上代理进程），但出口出不去（节点/订阅挂了） */
        TUNNEL_NO_EXIT,
        /** 端到端可用：能经它访问 Modrinth */
        OK
    }

    /**
     * @param url    代理地址（可能为 null）
     * @param source 哪来的（命令行/软件设置/环境变量/注册表/端口扫描）
     * @param status 探活结论
     * @param detail 失败原因（给用户看的，必须具体：超时 / TLS 握手失败 / HTTP 407 …）
     */
    public record Setting(String url, String source, Status status, String detail) {

        public boolean enabled() {
            return url != null && !url.isBlank();
        }

        /** 是否"可以拿它去下载"——注意这比 enabled() 严格得多 */
        public boolean usable() {
            return enabled() && status == Status.OK;
        }

        public String describe() {
            if (!enabled()) return "未使用代理（" + detail + "）";
            return url + "（" + source + "，"
                    + (status == Status.OK ? "探活可用 ✓" : "探活未通过：" + detail) + "）";
        }

        /** 给用户的一句人话结论，用于 GUI 状态栏与日志 */
        public String oneLine() {
            return switch (status) {
                case OK -> "代理可用：" + url + "（" + source + "）";
                case NONE -> "未探测到可用代理（" + detail + "）";
                case PORT_ONLY -> "检测到 " + url + " 端口在监听，但它建不起代理隧道（" + detail
                        + "）——请确认代理软件是 HTTP 代理模式";
                case TUNNEL_NO_EXIT -> "检测到代理 " + url + " 能连上，但它出不了网（" + detail
                        + "）——请检查 Clash/V2Ray 的节点或订阅是否可用";
            };
        }
    }

    private static final String FILE_NAME = "config.properties";
    private static final String KEY = "proxy.url";

    /** 探"隧道是否通"的目标：极小、稳定、任何正经代理都能过 */
    private static final String TUNNEL_PROBE = "https://www.gstatic.com/generate_204";
    /** 探"真的能访问 Modrinth"的目标（用最小响应体） */
    private static final String EXIT_PROBE = "https://api.modrinth.com/v2/search?limit=1";

    /** 本机常见代理端口（Clash 7890/7891、Verge 7897、v2rayN 10809、SSR 1080…） */
    private static final int[] LOCAL_PORTS = {7890, 7891, 7897, 7899, 10809, 10808, 2080, 1080, 8080};

    private ProxyConfig() {
    }

    /**
     * 解析本次运行要用的代理。
     *
     * @param cliProxy 命令行 --proxy（显式指定，即使探活失败也如实返回 + 说明原因）
     * @param noProxy  命令行 --no-proxy（强制直连）
     */
    public static Setting resolve(String cliProxy, boolean noProxy, Path toolRoot,
                                  Consumer<String> log) {
        if (noProxy) return new Setting(null, "--no-proxy 强制直连", Status.NONE, "用户指定不用代理");

        if (cliProxy != null && !cliProxy.isBlank()) {
            Setting s = verify(normalize(cliProxy), "--proxy 参数", log);
            if (log != null) log.accept("网络：" + s.oneLine());
            return s;
        }
        String configured = loadConfigured(toolRoot);
        if (configured != null) {
            Setting s = verify(normalize(configured), "软件设置 " + FILE_NAME, log);
            if (log != null) log.accept("网络：" + s.oneLine());
            return s;
        }

        // 自动探测：环境变量 → 系统代理注册表 → 本机常见端口。
        // 只有"端到端可用"才录用；探测过程中遇到的"端口在听但出不去"会被记下来作为最终提示，
        // 因为这正是用户最容易困惑的状态（代理明明开着，为什么工具不用）。
        Setting bestEffort = null;
        for (String cand : autoCandidates()) {
            String url = normalize(cand);
            Setting s = verify(url, sourceOf(cand), log);
            if (s.status() == Status.OK) {
                if (log != null) log.accept("网络：" + s.oneLine());
                return s;
            }
            if (bestEffort == null || s.status() == Status.TUNNEL_NO_EXIT) bestEffort = s;
        }
        if (bestEffort != null) {
            if (log != null) log.accept("网络：⚠️ " + bestEffort.oneLine());
            return bestEffort;
        }
        Setting none = new Setting(null, "自动探测", Status.NONE, "没有配置代理，也没在本机常见端口找到可用代理");
        if (log != null) log.accept("网络：" + none.oneLine());
        return none;
    }

    // ------------------------------------------------------------------ 三层探活

    /** 端口 → 隧道 → 出口，三层逐级探活 */
    static Setting verify(String proxyUrl, String source, Consumer<String> log) {
        String hostPort = proxyUrl.replaceFirst("^[a-zA-Z]+://", "");
        int colon = hostPort.lastIndexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        int port = colon > 0 ? Integer.parseInt(hostPort.substring(colon + 1)) : 8080;
        if (!portOpen(host, port)) {
            return new Setting(proxyUrl, source, Status.PORT_ONLY, "端口 " + port + " 不在监听");
        }
        String tunnelErr = HttpProbe.probe(TUNNEL_PROBE, proxyUrl, 6000);
        if (tunnelErr != null) {
            return new Setting(proxyUrl, source, Status.PORT_ONLY,
                    "隧道建不起来（" + tunnelErr + "）");
        }
        String exitErr = HttpProbe.probe(EXIT_PROBE, proxyUrl, 8000);
        if (exitErr != null) {
            return new Setting(proxyUrl, source, Status.TUNNEL_NO_EXIT,
                    "能连上代理但访问 Modrinth 失败（" + exitErr + "）");
        }
        return new Setting(proxyUrl, source, Status.OK, "");
    }

    static boolean portOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 保持旧签名（弹窗里的"再检测一次"用）：返回 null 表示可用，否则是失败原因 */
    public static String probe(String proxyUrl) {
        Setting s = verify(proxyUrl, "手动检测", null);
        return s.status() == Status.OK ? null : s.detail();
    }

    private static List<String> autoCandidates() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
                "ALL_PROXY", "all_proxy"}) {
            String v = System.getenv(key);
            // 统一 normalize 之后再入集合：否则 "127.0.0.1:7890"（注册表）与
            // "http://127.0.0.1:7890"（端口扫描）会被当成两个候选，同一个代理白探两次（各 6 秒）
            if (v != null && !v.isBlank()) out.add(normalize(v));
        }
        String reg = detectWindowsRegistryProxy();
        if (reg != null) out.add(normalize(reg));
        for (int p : LOCAL_PORTS) {
            out.add("http://127.0.0.1:" + p);
        }
        return new ArrayList<>(out);
    }

    private static String sourceOf(String candidate) {
        if (candidate.startsWith("http://127.0.0.1") || candidate.startsWith("http://localhost")) {
            return "本机代理端口扫描";
        }
        String env = System.getenv("HTTPS_PROXY") != null ? System.getenv("HTTPS_PROXY") : "";
        return candidate.equals(env) ? "环境变量" : "系统代理";
    }

    // ------------------------------------------------------------------ 读写配置 / 系统代理

    public static String loadConfigured(Path toolRoot) {
        return AppConfig.get(toolRoot, KEY);
    }

    public static void save(Path toolRoot, String url) throws IOException {
        var all = AppConfig.readAll(toolRoot);
        all.put(KEY, normalize(url));
        AppConfig.writeAll(toolRoot, all);
    }

    public static void clear(Path toolRoot) throws IOException {
        var all = AppConfig.readAll(toolRoot);
        all.remove(KEY);
        AppConfig.writeAll(toolRoot, all);
    }

    static String detectSystem() {
        for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
                "ALL_PROXY", "all_proxy"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return detectWindowsRegistryProxy();
    }

    /** Clash Verge / v2rayN 勾"系统代理"后写的就是这两个键；用 reg.exe 读，免得引第三方依赖 */
    static String detectWindowsRegistryProxy() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
        try {
            String enable = regQuery(key, "ProxyEnable");
            if (enable == null || !enable.trim().endsWith("1")) return null;
            String server = regQuery(key, "ProxyServer");
            return server == null ? null : parseWindowsProxy(server);
        } catch (Exception e) {
            return null;
        }
    }

    private static String regQuery(String key, String value) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("reg", "query", key, "/v", value)
                .redirectErrorStream(true).start();
        byte[] bytes = p.getInputStream().readAllBytes();
        p.waitFor();
        String out = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (p.exitValue() != 0) return null;
        for (String line : out.split("\\R")) {
            if (line.contains(value)) {
                String[] parts = line.trim().split("\\s{2,}");
                if (parts.length >= 3) return parts[parts.length - 1].trim();
            }
        }
        return null;
    }

    static String parseWindowsProxy(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (!s.contains("=")) return s;
        String https = null;
        String http = null;
        for (String part : s.split(";")) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            String scheme = part.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String hostPort = part.substring(idx + 1).trim();
            if (scheme.startsWith("https")) https = hostPort;
            else if (scheme.startsWith("http")) http = hostPort;
            else if (scheme.startsWith("socks") && https == null && http == null) http = hostPort;
        }
        return https != null ? https : http;
    }

    static String normalize(String url) {
        String s = url == null ? "" : url.trim();
        if (s.isEmpty()) return s;
        return s.contains("://") ? s : "http://" + s;
    }

    /** 小适配层：探活走 {@link yagen.waitmydawn.checker.net.Http#probe}，避免这里反向依赖 net 包细节 */
    static final class HttpProbe {
        private HttpProbe() {
        }

        static String probe(String url, String proxyUrl, int timeoutMs) {
            return yagen.waitmydawn.checker.net.Http.probe(url, proxyUrl, timeoutMs);
        }
    }
}
