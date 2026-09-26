package yagen.waitmydawn.checker.net;

import yagen.waitmydawn.checker.core.ProxyConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 网络与下载通道管理。策略是 2026-09-22 与用户逐条确认下来的：
 *
 * <ol>
 *   <li><b>有可用代理</b>（三层探活通过）→ 下载走<b>官方 Modrinth 本站（经代理）</b>；
 *       API 优先走镜像（快 10 倍），镜像失败自动回退官方；</li>
 *   <li><b>没代理</b> → API 走镜像（不受墙、0.065s），下载<b>真的去试一次官方地址</b>
 *       （镜像拿到的就是官方地址；直连偶尔能成，实测成功过）；</li>
 *   <li><b>单文件 5 秒稳定判据</b>：连上后 5 秒内平均速度 &lt; 13KB/s 就判"不稳定"，
 *       立刻换通道或给结论，绝不傻等（旧实现最坏要等 300s×2）；</li>
 *   <li><b>通道互为兜底</b>：代理试一次 → 直连试一次 → 都失败才弹窗；同一轮内**不重复**
 *       试已经判死的通道（否则 10 个模组会白等 20 次）；</li>
 *   <li>弹窗三个选项：<b>已开好代理继续 / 本次只做摘除（不做加法）/ 停止检验并还原</b>；</li>
 *   <li><b>缓存复用</b>：已下好的文件不重复下载。</li>
 * </ol>
 *
 * <p>为什么不再"逐个文件从直连开始试"：直连官方 CDN 在国内是<a>整体</a>不可用（不是某个文件的问题），
 * 判死一次就该换通道——旧实现每个文件都先撞一次直连，才出现"一直不动"的体验。
 */
public final class NetworkManager {

    /** 下载通道：代理（官方本站）/ 直连（官方本站）/ 镜像文件入口 */
    public enum Route {
        PROXY("官方本站（经代理）"), DIRECT("官方本站（直连）"), MIRROR_FILE("镜像文件入口");

        private final String zh;

        Route(String zh) {
            this.zh = zh;
        }

        public String zh() {
            return zh;
        }
    }

    /** 用户选择"只做摘除并继续"：调用方据此停止补装并走摘除 */
    public static final class RemoveOnlyRequested extends IOException {
        public RemoveOnlyRequested(String msg) {
            super(msg);
        }
    }

    /** 用户选择"停止检验并还原" */
    public static final class StopRequested extends IOException {
        public StopRequested(String msg) {
            super(msg);
        }
    }

    /** 弹窗/命令行要问用户的问题 */
    public record Ask(String title, String message, String currentProxy, boolean offerProxyInput) {
    }

    public enum Choice {
        /** 已填/已开好代理，继续 */
        CONTINUE,
        /** 停止检验并还原到检验前 */
        STOP_AND_RESTORE,
        /** 只做摘除并继续（不再补装） */
        REMOVE_ONLY
    }

    public record Answer(Choice choice, String proxyInput) {
        public static Answer of(Choice c) {
            return new Answer(c, null);
        }
    }

    /** 交互抽象：CLI 用控制台，GUI 用弹窗（同一套语义） */
    public interface Asker {
        Answer ask(Ask ask);

        /** 探活一次代理（弹窗里的"再检测一次"） */
        default String probe(String proxyUrl) {
            return ProxyConfig.probe(proxyUrl);
        }
    }

    private final Consumer<String> log;
    private final Asker asker;
    private final boolean allowDirectCdn;
    private final boolean allowMirrorFile;
    private final String proxy;
    private final Set<Route> dead = new LinkedHashSet<>();
    private Route lastGood;
    private boolean removeOnly;
    private boolean apiViaMirror = true;
    /** 最近一次失败原因：所有通道都被判死后，弹窗要能说清"上次到底为什么失败" */
    private String lastFailure;

    public NetworkManager(ProxyConfig.Setting proxySetting, Asker asker, boolean allowDirectCdn,
                          boolean allowMirrorFile, Consumer<String> log) {
        this.log = log == null ? m -> {
        } : log;
        this.asker = asker;
        this.allowDirectCdn = allowDirectCdn;
        this.allowMirrorFile = allowMirrorFile;
        this.proxy = proxySetting != null && proxySetting.usable() ? proxySetting.url() : null;
        if (proxy != null) {
            this.lastGood = Route.PROXY;
            Http.setProxy(proxy);
        }
        log.accept("网络策略：" + strategyLine(proxySetting));
    }

    private String strategyLine(ProxyConfig.Setting s) {
        StringBuilder sb = new StringBuilder();
        sb.append("API 优先镜像（mod.mcimirror.top，失败回退官方）");
        if (proxy != null) {
            sb.append("；下载走代理 ").append(proxy);
        } else {
            sb.append("；下载无代理 → 直连官方 CDN 试 5 秒（不稳定即判失败）");
            if (s != null && s.enabled()) sb.append("（已探测到 ").append(s.url())
                    .append(" 但不可用：").append(s.detail()).append("）");
        }
        sb.append("；单文件判据：连接 8s / 5 秒内 <13KB/s 判不稳定");
        if (allowDirectCdn) sb.append("；已允许直连 CDN");
        if (allowMirrorFile) sb.append("；已允许镜像文件入口");
        return sb.toString();
    }

    public String proxy() {
        return proxy;
    }

    public boolean removeOnly() {
        return removeOnly;
    }

    /** 预置"只做摘除"（命令行 --net remove-only，不再询问） */
    public void forceRemoveOnly() {
        this.removeOnly = true;
    }

    /** 给报告/日志用：本轮用过哪些通道 */
    public String routeSummary() {
        return "当前通道=" + (lastGood == null ? "未成功过" : lastGood.zh())
                + (dead.isEmpty() ? "" : "，已判死=" + dead);
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下载一个文件：按通道顺序尝试，直到某个通道"稳定拿到完整文件"。
     *
     * @param officialUrl 官方 CDN 地址（镜像 API 给的也是这个）
     * @param mirrorUrl   镜像文件入口（可为 null；实测当前 302 回官方，留着以便镜像以后支持）
     * @param expectedBytes 元数据里的文件大小，用于总时长兜底
     */
    public Http.DownloadOutcome download(String officialUrl, String mirrorUrl, long expectedBytes,
                                         Path target) throws IOException {
        if (removeOnly) throw new RemoveOnlyRequested("用户选择只做摘除并继续，不再下载");
        // 缓存复用：已经下好的文件不再重复试网络
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 0
                    && (expectedBytes <= 0 || Files.size(target) == expectedBytes)) {
                log.accept("   ♻️ 复用已下载文件 " + target.getFileName());
                return Http.DownloadOutcome.success(Files.size(target));
            }
        } catch (IOException ignored) {
        }

        List<Route> order = routeOrder();
        Http.DownloadOutcome lastOutcome = null;
        for (Route r : order) {
            if (dead.contains(r)) continue;
            Http.setProxy(r == Route.PROXY ? proxy : null);
            String url = r == Route.MIRROR_FILE ? mirrorUrl : officialUrl;
            if (url == null) continue;
            log.accept("   ⇣ 尝试下载[" + r.zh() + "] " + target.getFileName());
            // 镜像文件入口不跟随跳转：它 302 回官方就等于"不承运文件"，0.1 秒判死，
            // 比跟着跳到官方 CDN 白等 8 秒强得多
            Http.DownloadOutcome o = r == Route.MIRROR_FILE
                    ? Http.download(url, target, log, expectedBytes, 0)
                    : Http.download(url, target, log, expectedBytes);
            if (o.ok()) {
                lastGood = r;
                return o;
            }
            lastOutcome = o;
            lastFailure = r.zh() + "：" + o.detail();
            if (r == Route.MIRROR_FILE && o.status() >= 300 && o.status() < 400) {
                dead.add(r);
                log.accept("   ✗ 镜像不承运文件（HTTP " + o.status() + " 跳转到官方），"
                        + "本轮不再试它");
                continue;
            }
            switch (o.kind()) {
                case HTTP_STATUS -> {
                    // 服务器明确回答（404 等）：换通道没意义，直接把这轮结论交给上层
                    log.accept("   ✗ " + r.zh() + "：" + o.detail() + "（换通道无意义）");
                    return o;
                }
                case CONNECT, UNSTABLE -> {
                    dead.add(r);
                    log.accept("   ✗ " + r.zh() + "：" + o.detail() + " → 本轮不再走这条通道");
                }
                default -> log.accept("   ✗ " + r.zh() + "：" + o.detail());
            }
        }
        // 所有通道都失败 → 问用户（或按无人值守策略处理）
        askUser(lastOutcome);
        return lastOutcome == null
                ? new Http.DownloadOutcome(false, 0, Http.FailureKind.OTHER,
                        lastFailure == null ? "没有可用通道" : "本轮通道都已判死，最后失败：" + lastFailure,
                        -1)
                : lastOutcome;
    }

    /** 通道顺序：优先最近成功过的；有代理就先代理，无代理则直连（可选镜像入口在前，因为它最快能判死） */
    private List<Route> routeOrder() {
        List<Route> out = new ArrayList<>();
        if (allowMirrorFile) out.add(Route.MIRROR_FILE);
        if (proxy != null) out.add(Route.PROXY);
        if (allowDirectCdn || proxy == null) out.add(Route.DIRECT);
        if (lastGood != null) {
            out.remove(lastGood);
            out.add(0, lastGood);
        }
        return out;
    }

    private void askUser(Http.DownloadOutcome last) throws IOException {
        String cause = last != null ? last.detail()
                : (lastFailure == null ? "未知原因" : lastFailure);
        if (asker == null) {
            throw new IOException("下载失败（" + cause + "），且当前是无人值守模式、没有可用代理。"
                    + "请用 --set-proxy 设好代理后重跑，或加 --net remove-only 只做摘除。");
        }
        String title = proxy != null ? "代理连上了但下载不通" : "没有代理，模组下不下来";
        StringBuilder msg = new StringBuilder();
        msg.append("下载失败了：").append(cause).append("\n\n");
        msg.append("说明：Modrinth 的下载地址（cdn.modrinth.com）在国内直连基本不可用，")
                .append("镜像站只加速“找地址”不承运文件。\n");
        if (proxy != null) {
            msg.append("已配置代理 ").append(proxy).append("，但它这次没能把文件取回来——")
                    .append("请检查 Clash/V2Ray 的节点是否可用，然后点【已开好代理，继续】。\n");
        } else {
            msg.append("请打开你的代理（Clash / V2Ray 的“系统代理”开关即可），")
                    .append("本工具会自动探测到；也可以直接把代理地址填在下面。\n");
        }
        msg.append("\n也可以选择：本次检验【只做摘除】——接下来遇到“A 需要 B”就直接摘掉 A，不再尝试补装；")
                .append("或者【停止检验并还原】。");

        while (true) {
            Answer a = asker.ask(new Ask(title, msg.toString(), proxy, proxy == null));
            if (a == null) a = Answer.of(Choice.STOP_AND_RESTORE);
            switch (a.choice()) {
                case STOP_AND_RESTORE -> throw new StopRequested("用户选择停止检验并还原");
                case REMOVE_ONLY -> {
                    removeOnly = true;
                    log.accept("   ↪ 用户选择【本次只做摘除】：不再补装，缺前置就摘掉依赖方");
                    throw new RemoveOnlyRequested("用户选择只做摘除");
                }
                case CONTINUE -> {
                    String candidate = a.proxyInput() != null && !a.proxyInput().isBlank()
                            ? a.proxyInput().trim() : proxy;
                    if (candidate == null) {
                        log.accept("   ⚠️ 没有填代理，继续按原通道重试");
                        dead.clear();
                        return;
                    }
                    String err = asker.probe(candidate);
                    if (err == null) {
                        Http.setProxy(candidate);
                        dead.clear();
                        lastGood = Route.PROXY;
                        log.accept("   ✓ 代理可用（" + candidate + "），继续下载");
                        return;
                    }
                    log.accept("   ✗ 代理仍不可用：" + err);
                    msg = new StringBuilder("刚才检测 ").append(candidate).append(" 仍然失败：")
                            .append(err).append("\n\n请确认代理软件已启动、系统代理已打开（或节点可用），再试一次。");
                    title = "代理仍然不可用";
                }
            }
        }
    }

    // ------------------------------------------------------------------ API

    /**
     * 取 JSON：**优先镜像 API**（实测 0.065s vs 官方 0.69s，且不受墙），失败自动回退官方。
     *
     * <p>镜像是否可用会被记住（{@link #apiViaMirror}），避免每个请求都先失败一次。
     */
    public String getJson(String url) throws IOException {
        if (removeOnly) throw new RemoveOnlyRequested("用户选择只做摘除并继续，不再联网");
        String[] pair = splitApi(url);
        String official = pair[0];
        String mirror = pair[1];
        IOException last = null;
        if (apiViaMirror && mirror != null) {
            try {
                return validated(Http.getString(mirror, log), mirror);
            } catch (IOException e) {
                apiViaMirror = false;
                last = e;
                log.accept("   ↺ 镜像 API 失败（" + e.getMessage() + "），本轮回退官方 API");
            }
        }
        try {
            return validated(Http.getString(official, log), official);
        } catch (IOException e) {
            if (last != null) e.addSuppressed(last);
            throw e;
        }
    }

    /** 把官方 API 地址拆成 [官方, 镜像]；不是 Modrinth API 地址时镜像为 null */
    static String[] splitApi(String url) {
        String officialBase = "https://api.modrinth.com";
        if (url.startsWith(officialBase)) {
            return new String[]{url, ModrinthClient.MIRROR_API_BASE + url.substring(officialBase.length())};
        }
        return new String[]{url, null};
    }

    /** JSON 校验：国内线路上被返回 HTML/劫持页是常见现象，拿到非 JSON 要当失败重试 */
    private static String validated(String body, String url) throws IOException {
        String t = body == null ? "" : body.stripLeading();
        if (t.isEmpty() || !(t.startsWith("{") || t.startsWith("["))) {
            throw new IOException("返回的不是 JSON（可能是劫持页）: "
                    + t.substring(0, Math.min(60, t.length())));
        }
        return body;
    }
}
