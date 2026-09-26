package yagen.waitmydawn.checker.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.ProxyConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道策略单测（不依赖外网：用必然连不上的本地地址触发失败路径）。
 *
 * <p>策略来自用户 2026-09-22 确认的四条：
 * 有可用代理 → 走官方本站（经代理）；无代理 → 试直连（5 秒判据）失败即弹窗；
 * 弹窗三选项（继续 / 只做摘除 / 停止还原）；已判死的通道本轮不重复试。
 */
class NetworkManagerTest {

    /** 必然连不上：端口 1 不会有东西在听，且被判死得快 */
    private static final String DEAD = "http://127.0.0.1:1/never.jar";

    @AfterEach
    void reset() {
        Http.setProxy(null);
    }

    private static ProxyConfig.Setting proxy(String url, ProxyConfig.Status st) {
        return new ProxyConfig.Setting(url, "测试", st, st == ProxyConfig.Status.OK ? "" : "测试用");
    }

    @Test
    void 有可用代理时只走代理_不再空等直连(@TempDir Path tmp) {
        List<String> log = new ArrayList<>();
        NetworkManager nm = new NetworkManager(proxy("http://127.0.0.1:1", ProxyConfig.Status.OK),
                ask -> NetworkManager.Answer.of(NetworkManager.Choice.REMOVE_ONLY), false, false,
                log::add);
        assertThrows(NetworkManager.RemoveOnlyRequested.class,
                () -> nm.download(DEAD, null, 0, tmp.resolve("a.jar")));
        assertTrue(log.stream().anyMatch(l -> l.contains("尝试下载[官方本站（经代理）]")),
                "有代理时应该走代理通道：" + log);
        assertFalse(log.stream().anyMatch(l -> l.contains("尝试下载[官方本站（直连）]")),
                "有可用代理时不该再去撞直连：" + log);
    }

    @Test
    void 无代理时会试直连_失败后询问用户(@TempDir Path tmp) {
        List<String> log = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        NetworkManager nm = new NetworkManager(null, ask -> {
            asked.add(ask.title());
            return NetworkManager.Answer.of(NetworkManager.Choice.REMOVE_ONLY);
        }, false, false, log::add);
        assertThrows(NetworkManager.RemoveOnlyRequested.class,
                () -> nm.download(DEAD, null, 0, tmp.resolve("b.jar")));
        assertTrue(log.stream().anyMatch(l -> l.contains("尝试下载[官方本站（直连）]")), log.toString());
        assertFalse(asked.isEmpty(), "直连失败后必须问用户");
        assertTrue(nm.removeOnly());
    }

    @Test
    void 无代理时选择停止检验会抛出停止请求(@TempDir Path tmp) {
        NetworkManager nm = new NetworkManager(null,
                ask -> NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE), false,
                false, m -> {
        });
        assertThrows(NetworkManager.StopRequested.class,
                () -> nm.download(DEAD, null, 0, tmp.resolve("c.jar")));
    }

    @Test
    void 无人值守模式绝不挂死_直接报错说明该怎么做(@TempDir Path tmp) {
        NetworkManager nm = new NetworkManager(null, null, false, false, m -> {
        });
        IOException e = assertThrows(IOException.class,
                () -> nm.download(DEAD, null, 0, tmp.resolve("d.jar")));
        assertTrue(e.getMessage().contains("--set-proxy") || e.getMessage().contains("remove-only"),
                "错误信息要给出可操作的建议：" + e.getMessage());
    }

    @Test
    void 已判死的通道后续不再重复试(@TempDir Path tmp, @TempDir Path tmp2) {
        List<String> log = new ArrayList<>();
        NetworkManager nm = new NetworkManager(null,
                ask -> NetworkManager.Answer.of(NetworkManager.Choice.REMOVE_ONLY), false, false,
                log::add);
        assertThrows(Exception.class, () -> nm.download(DEAD, null, 0, tmp.resolve("e1.jar")));
        int triesAfterFirst = countTries(log);
        assertThrows(Exception.class, () -> nm.download(DEAD, null, 0, tmp2.resolve("e2.jar")));
        assertEquals(triesAfterFirst, countTries(log),
                "第二个文件不该再重复尝试已判死的通道（否则 10 个模组要白等 20 次）：" + log);
    }

    @Test
    void 缓存命中时不再联网(@TempDir Path tmp) throws Exception {
        Path cached = tmp.resolve("cached.jar");
        Files.write(cached, new byte[1234]);
        List<String> log = new ArrayList<>();
        NetworkManager nm = new NetworkManager(null, null, false, false, log::add);
        Http.DownloadOutcome o = nm.download(DEAD, null, 1234, cached);
        assertTrue(o.ok(), "已下好的文件应当直接复用");
        assertTrue(log.stream().anyMatch(l -> l.contains("复用已下载文件")), log.toString());
    }

    @Test
    void 镜像API地址能被正确拆出来() {
        String[] pair = NetworkManager.splitApi("https://api.modrinth.com/v2/project/kubejs");
        assertEquals("https://api.modrinth.com/v2/project/kubejs", pair[0]);
        assertEquals("https://mod.mcimirror.top/modrinth/v2/project/kubejs", pair[1]);
        // 非 Modrinth 地址不套镜像
        assertNull(NetworkManager.splitApi("https://maven.neoforged.net/x")[1]);
    }

    @Test
    void 镜像文件入口地址按官方CDN同路径形状拼() {
        ModrinthClient.Candidate c = new ModrinthClient.Candidate("kubejs", "KubeJS", "umyGl7zF",
                "THIGFPwf", "2101.7.2", "kubejs-x.jar",
                "https://cdn.modrinth.com/data/umyGl7zF/versions/THIGFPwf/kubejs-x.jar", 100, "mod");
        assertEquals("https://mod.mcimirror.top/data/umyGl7zF/versions/THIGFPwf/kubejs-x.jar",
                c.mirrorUrl());
        assertNotNull(c.url());
    }

    private static int countTries(List<String> log) {
        return (int) log.stream().filter(l -> l.contains("尝试下载[")).count();
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }
}
