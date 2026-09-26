package yagen.waitmydawn.checker.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 极简 HTTP 客户端：JDK 自带 HttpClient，不引额外依赖。
 *
 * <p><b>网络栈踩过的坑（改这里之前先看）</b>：本机（Windows + 联通/电信）Java 走 IPv6 直连
 * {@code maven.neoforged.net}（cdn77）会 <b>TCP 连接卡死</b>——不是慢，是 0 字节直到超时；
 * 同一个 URL 用 {@code curl -4} 与 {@code curl -6} 都是 3 秒下完 7MB，Java 加
 * {@code -Djava.net.preferIPv4Stack=true} 后也是 3.5 秒。所以：
 * <ol>
 *   <li>程序启动第一件事就是强制 IPv4（见 {@code CheckerCli.main}）；</li>
 *   <li>万一还有别的网络怪癖，下载与 GET 失败后自动改用系统 {@code curl.exe} 兜底
 *       （Windows 10 1803+ 自带）。用户不该为我们的网络栈背锅。</li>
 * </ol>
 *
 * <p>速率限制纪律（Modrinth 明确会 429，Maven 也会 5xx）：
 * 429/503 按 2^n 退避重试（尊重 Retry-After），5xx 同样重试；
 * 4xx 里其它状态码直接失败——重试一个 404 只会浪费时间并掩盖"这个模组根本不存在"的事实。
 */
public final class Http {

    /**
     * HTTP 状态码错误（服务器明确回答"没有/不给你"），<b>不是</b>网络问题。
     *
     * <p>区分这两者非常重要：404 应该走"这个项目/文件不存在"的业务分支，
     * 只有网络失败才该触发"直连换代理"或询问用户。早期把 404 一并当网络失败，
     * 结果一次正常的"探测 slug 存不存在"就触发了整轮转代理 + 弹窗（实测踩过）。
     */
    public static final class HttpStatusException extends IOException {
        private final int status;

        public HttpStatusException(int status, String url) {
            super("HTTP " + status + " " + url);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /**
     * 代理（例如 {@code http://127.0.0.1:7890}）。
     * 实测教训：国内某些线路上 {@code api.modrinth.com} 通、但下载用
     * {@code cdn-alt.modrinth.com} 整段 TCP 不可达（curl 也连不上，21s 超时）——
     * 这不是我们代码能修的，只能让用户挂自己的代理，所以必须支持。
     */
    private static volatile String proxy;
    private static volatile HttpClient client;

    private static final int MAX_ATTEMPTS = 3;
    private static final String UA = "MAA-Checker/0.1 (Minecraft modpack feasibility checker)";

    private Http() {
    }

    public static synchronized void setProxy(String proxyUrl) {
        proxy = (proxyUrl == null || proxyUrl.isBlank()) ? null : proxyUrl.trim();
        client = null;
    }

    public static String proxy() {
        return proxy;
    }

    /**
     * 单次探活（不重试、不下载正文）：给"这个代理到底通不通"一个快速答案。
     *
     * <p>用 {@code sendAsync + get(超时)} 而不是同步 send：实测同步 send 在"黑洞式阻断"下
     * 连 connectTimeout / requestTimeout 都不生效（探针挂了 14 分钟没返回），必须自己看门狗兜底。
     *
     * @return null 表示可用；否则是失败原因
     */
    public static String probe(String url, String proxyUrl, int timeoutMs) {
        HttpClient c = null;
        try {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    // 必须跟随跳转：Modrinth 的下载地址会 307 跳到 cdn-alt 节点，
                    // 不跟随的话"通不通"根本测不出来（只会看到 307）
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(timeoutMs));
            if (proxyUrl != null && !proxyUrl.isBlank()) {
                java.net.URI u = java.net.URI.create(proxyUrl.contains("://")
                        ? proxyUrl : "http://" + proxyUrl);
                b.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(u.getHost(),
                        u.getPort() > 0 ? u.getPort() : 8080)));
            }
            c = b.build();
            java.net.http.HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA).timeout(Duration.ofMillis(timeoutMs)).GET().build();
            java.net.http.HttpResponse<Void> r = c.sendAsync(req,
                    HttpResponse.BodyHandlers.discarding())
                    .get(timeoutMs + 500L, java.util.concurrent.TimeUnit.MILLISECONDS);
            // 2xx 都算通过：204（generate_204）也是"隧道是通的"的合法证据
            return r.statusCode() >= 200 && r.statusCode() < 300 ? null
                    : ("HTTP " + r.statusCode());
        } catch (java.util.concurrent.TimeoutException e) {
            return "超时（超过 " + timeoutMs + "ms 无响应）";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.close();
        }
    }

    private static HttpClient client() {
        HttpClient c = client;
        if (c != null) return c;
        synchronized (Http.class) {
            if (client == null) {
                HttpClient.Builder b = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)   // HTTP/2 在这类 CDN 上只多一层变量
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(12));
                String p = proxy;
                if (p != null) {
                    try {
                        java.net.URI u = java.net.URI.create(p.contains("://") ? p : "http://" + p);
                        int port = u.getPort() > 0 ? u.getPort() : 8080;
                        b.proxy(java.net.ProxySelector.of(
                                new java.net.InetSocketAddress(u.getHost(), port)));
                    } catch (Exception e) {
                        System.err.println("⚠️ 代理地址无法解析，已按直连处理: " + p);
                        proxy = null;
                    }
                }
                client = b.build();
            }
        }
        return client;
    }

    public static String getString(String url, Consumer<String> log) throws IOException {
        return fetchString(url, log, false);
    }

    /** 与 {@link #getString} 相同，但 404 返回 null 而不是抛异常（用于"这个 slug 存不存在"这类探测） */
    public static String getStringOrNull(String url, Consumer<String> log) throws IOException {
        return fetchString(url, log, true);
    }

    private static String fetchString(String url, Consumer<String> log, boolean nullOn404)
            throws IOException {
        try {
            HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString(), log, nullOn404);
            return r.statusCode() == 404 ? null : r.body();
        } catch (IOException e) {
            if (!curlAvailable()) throw e;
            if (log != null) {
                log.accept("   ↺ Java 网络栈失败（" + e.getMessage() + "），改用系统 curl 兜底");
            }
            return curlToString(url);
        }
    }

    /** 下载失败的原因分类：决定上层是该换通道、还是该直接告诉用户"下不了" */
    public enum FailureKind {
        /** 连不上/连不通（换通道有意义） */
        CONNECT,
        /** 连上了但 5 秒内速度起不来（用户定义的"不稳定"，同样换通道） */
        UNSTABLE,
        /** 服务器明确回非 2xx（404/403 之类，换通道没意义） */
        HTTP_STATUS,
        /** 其它（磁盘、解析…） */
        OTHER
    }

    /**
     * @param bytes  已落盘字节数（失败时用于诊断："下了 0 字节"和"下了 900KB 后断"完全不同）
     * @param status HTTP 状态码（-1 表示没拿到）；3xx 表示"这个地址只是跳转，没有真的给文件"
     */
    public record DownloadOutcome(boolean ok, long bytes, FailureKind kind, String detail,
                                  int status) {
        public static DownloadOutcome success(long bytes) {
            return new DownloadOutcome(true, bytes, null, "", 200);
        }
    }

    /** 连接超时：8 秒。实测"卡住"多半卡在这里，8 秒足够暴露，又不会让用户等。 */
    private static final int CONNECT_TIMEOUT_S = 8;
    /**
     * "稳定下载"判据（用户 2026-09-22 确认的口径 A）：
     * **5 秒内平均速度必须 ≥ 13KB/s**，否则判"不稳定"并中止。
     *
     * <p>注意语义：不是"5 秒必须下完"——小文件秒完，大文件（20MB）在证明"起得来"之后
     * 会一路下完，总时长另有兜底（见 {@link #totalTimeoutSeconds}）。
     * curl 原生的 {@code --speed-limit/--speed-time} 正好是这个语义，比自己写监视线程可靠。
     */
    private static final int SPEED_LIMIT_BYTES_PER_S = 13 * 1024;
    private static final int SPEED_TIME_S = 5;

    /**
     * 总时长兜底：防止"下到 99% 挂住"。按最低 20KB/s 估算，再给 30 秒下限。
     * 例：2.3MB → 115s；20MB → 1000s（中途一直有进度输出，不会看起来像卡死）。
     */
    static int totalTimeoutSeconds(long expectedBytes) {
        if (expectedBytes <= 0) return 120;
        return (int) Math.max(30, expectedBytes / (20 * 1024));
    }

    /**
     * 下载到文件（**优先系统 curl**：它的 {@code --connect-timeout/--speed-limit/--max-time}
     * 都可靠，而 Java 的超时在"黑洞式阻断"下实测不生效——探针挂过 14 分钟）。
     *
     * @param expectedBytes Modrinth 元数据里给出的文件大小（用于算总时长兜底），未知传 0
     */
    public static DownloadOutcome download(String url, Path target, Consumer<String> log,
                                           long expectedBytes) {
        return download(url, target, log, expectedBytes, -1);
    }

    /**
     * @param maxRedirects &ge;0 时限制跳转次数（0 = 不跟随）：用于"镜像文件入口"这类
     *                     只需要判断"它到底给不给文件"的探测——镜像会把请求 302 回官方，
     *                     跟着跳就等于去撞官方 CDN（白等 8 秒），不跟则 0.1 秒就知道它不承运文件
     */
    public static DownloadOutcome download(String url, Path target, Consumer<String> log,
                                           long expectedBytes, int maxRedirects) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return new DownloadOutcome(false, 0, FailureKind.OTHER, "建目录失败: " + e.getMessage(), -1);
        }
        if (!curlAvailable()) {
            return javaDownload(url, target, log, expectedBytes);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.deleteIfExists(tmp);
            List<String> cmd = new java.util.ArrayList<>(List.of("curl.exe", "-sSL",
                    "--connect-timeout", String.valueOf(CONNECT_TIMEOUT_S),
                    // 5 秒内平均速度低于 13KB/s → 判定"不稳定"并中止（curl 自己会退出码 28）
                    "--speed-limit", String.valueOf(SPEED_LIMIT_BYTES_PER_S),
                    "--speed-time", String.valueOf(SPEED_TIME_S),
                    "--max-time", String.valueOf(totalTimeoutSeconds(expectedBytes)),
                    "-A", UA));
            if (proxy != null) {
                cmd.add("-x");
                cmd.add(proxy);
            }
            if (maxRedirects >= 0) {
                cmd.add("--max-redirs");
                cmd.add(String.valueOf(maxRedirects));
            }
            cmd.add("-o");
            cmd.add(tmp.toString());
            cmd.add("-w");
            cmd.add("%{http_code}");
            cmd.add(url);
            long t0 = System.currentTimeMillis();
            String out = runCaptureOrThrow(cmd);
            int code = parseStatus(out);
            long size = Files.exists(tmp) ? Files.size(tmp) : 0;
            if (code == 200 || code == 206) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                if (log != null) {
                    log.accept(String.format(Locale.ROOT, "      ↓ %.2f MB / %.1fs（%.0f KB/s）%s",
                            size / 1048576.0, (System.currentTimeMillis() - t0) / 1000.0,
                            size / 1024.0 / Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0),
                            proxy != null ? " [经代理]" : " [直连]"));
                }
                return DownloadOutcome.success(size);
            }
            Files.deleteIfExists(tmp);
            return new DownloadOutcome(false, size, FailureKind.HTTP_STATUS,
                    "服务器返回 HTTP " + code, code);
        } catch (IOException e) {
            long size = 0;
            try {
                size = Files.exists(tmp) ? Files.size(tmp) : 0;
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            String msg = String.valueOf(e.getMessage());
            // curl 用 --max-redirs 0 时以退出码 47 报告跳转，而不是返回 3xx 状态码；
            // 对"镜像文件入口"这类探测来说，这就是"它只是个跳转、不给文件"的确定答案
            if (msg.contains("Maximum (0) redirects") || msg.contains("(47)")) {
                return new DownloadOutcome(false, size, FailureKind.HTTP_STATUS,
                        "该地址只是 302 跳转（不承运文件）", 302);
            }
            FailureKind kind = msg.contains("too slow") || msg.contains("Less than")
                    ? FailureKind.UNSTABLE
                    : (msg.contains("Failed to connect") || msg.contains("Connection timed out")
                    || msg.contains("Could not resolve") || msg.contains("handshake")
                    || msg.contains("Failed to receive") ? FailureKind.CONNECT : FailureKind.OTHER);
            String human = switch (kind) {
                case UNSTABLE -> "5 秒内速度起不来（" + SPEED_TIME_S + "s 平均 < "
                        + (SPEED_LIMIT_BYTES_PER_S / 1024) + "KB/s），已按【不稳定】中止"
                        + (size > 0 ? "（已下 " + size / 1024 + "KB）" : "（一个字节都没下来）");
                case CONNECT -> "连不上" + (size > 0 ? "（已下 " + size / 1024 + "KB 后断）" : "");
                default -> msg;
            };
            return new DownloadOutcome(false, size, kind, human, -1);
        }
    }

    /** 没有 curl 时的 Java 兜底路径（保留看门狗语义：send 内部对连接级失败不重试） */
    private static DownloadOutcome javaDownload(String url, Path target, Consumer<String> log,
                                                long expectedBytes) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.deleteIfExists(part);
            HttpResponse<Path> r = send(url, HttpResponse.BodyHandlers.ofFile(part), log);
            if (r.statusCode() != 200 && r.statusCode() != 206) {
                Files.deleteIfExists(part);
                return new DownloadOutcome(false, 0, FailureKind.HTTP_STATUS,
                        "服务器返回 HTTP " + r.statusCode(), r.statusCode());
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return DownloadOutcome.success(Files.size(target));
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            return new DownloadOutcome(false, 0,
                    isConnectLevelFailure(e) ? FailureKind.CONNECT : FailureKind.OTHER,
                    String.valueOf(e.getMessage()), -1);
        }
    }

    /** curl 退出码非 0 时抛出，把 curl 自己的错误文本带出来（分类靠它） */
    private static String runCaptureOrThrow(List<String> cmd) throws IOException {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("curl: " + out.strip().replaceAll("\\R+", " "));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("curl 被中断", e);
        }
    }

    // ------------------------------------------------------------------ curl 兜底

    private static Boolean curlPresent;

    static synchronized boolean curlAvailable() {
        if (curlPresent == null) {
            try {
                Process p = new ProcessBuilder("curl.exe", "--version")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                curlPresent = p.waitFor() == 0;
            } catch (Exception e) {
                curlPresent = false;
            }
        }
        return curlPresent;
    }

    private static String curlToString(String url) throws IOException {
        Path tmp = Files.createTempFile("maa-checker-http", ".body");
        List<String> cmd = new java.util.ArrayList<>(List.of("curl.exe", "-sSL", "--retry", "3",
                "--connect-timeout", "30", "--max-time", "300", "-A", UA));
        if (proxy != null) {
            cmd.add("-x");
            cmd.add(proxy);
        }
        cmd.add("-o");
        cmd.add(tmp.toString());
        cmd.add("-w");
        cmd.add("%{http_code}");
        cmd.add(url);
        try {
            String status = runCapture(cmd);
            int code = parseStatus(status);
            if (code != 200) throw new HttpStatusException(code, url);
            return Files.readString(tmp, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 跑 curl 并返回 stdout（含 -w 写的状态码）；退出码非 0 视为传输层失败 */
    private static String runCapture(List<String> cmd) throws IOException {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) throw new IOException("curl 失败: " + out.strip());
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("curl 被中断", e);
        }
    }

    /** curl -w '%{http_code}' 输出里取状态码（最后一行是状态码，前面可能混着错误信息） */
    static int parseStatus(String curlOut) {
        if (curlOut == null) return -1;
        for (String line : curlOut.strip().split("\\R")) {
            String s = line.strip();
            if (s.length() == 3 && s.chars().allMatch(Character::isDigit)) {
                return Integer.parseInt(s);
            }
        }
        return -1;
    }

    private static <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler,
                                            Consumer<String> log) throws IOException {
        return send(url, handler, log, false);
    }

    /** @param accept404 true 时把 404 当正常响应返回，交调用方判断 */
    private static <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler,
                                            Consumer<String> log, boolean accept404)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofMinutes(5))
                        .GET().build();
                HttpResponse<T> r = client().send(req, handler);
                int code = r.statusCode();
                if (code == 200 || (accept404 && code == 404)) return r;
                boolean retryable = code == 429 || code >= 500;
                if (!retryable) throw new HttpStatusException(code, url);
                long waitMs = retryAfterMs(r).orElse((long) (1000 * Math.pow(2, attempt)));
                if (log != null) {
                    log.accept("   ⏳ HTTP " + code + "，第 " + attempt + " 次重试，" + waitMs + "ms 后（限流退避）");
                }
                Thread.sleep(Math.min(waitMs, 30_000L));
                last = new IOException("HTTP " + code + " " + url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("被中断: " + url, e);
            } catch (IOException e) {
                last = e;
                // 连接层面的失败是"确定性"的：连接被拒/超时/域名解析不了，重试只是浪费时间，
                // 而且会拖慢"直连不通 → 转代理"的切换（实测把一次切换拖到 40 秒以上）。
                // 真正值得重试的是限流（429/5xx）与传输中断。
                if (isConnectLevelFailure(e)) throw e;
                if (log != null) log.accept("   ⏳ 网络异常 " + e.getClass().getSimpleName()
                        + "，第 " + attempt + " 次重试");
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("被中断: " + url, ie);
                }
            }
        }
        throw last == null ? new IOException("请求失败: " + url) : last;
    }

    /** 连接级失败（重试无意义）：拒绝连接、连接超时、域名解析失败 */
    static boolean isConnectLevelFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Optional<Long> retryAfterMs(HttpResponse<?> r) {
        return r.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000L;
            } catch (NumberFormatException e) {
                return 1000L;   // 也可能是 HTTP-date，这里退化成 1s，不猜
            }
        });
    }

    /** URL 编码（用于 facets / loaders 这类需要方括号引号的查询参数） */
    public static String enc(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (safe) sb.append((char) c);
            else sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return sb.toString();
    }
}
