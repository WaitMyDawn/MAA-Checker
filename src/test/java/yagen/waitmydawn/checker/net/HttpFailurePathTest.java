package yagen.waitmydawn.checker.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpFailurePathTest {
    @AfterEach
    void resetProxy() {
        Http.setProxy(null);
    }

    @Test
    void 限流响应按RetryAfter重试后成功() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/limited", exchange -> {
            if (calls.getAndIncrement() == 0) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertEquals("{\"ok\":true}", Http.getString(url(server, "/limited"), null));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 完整响应长度不符时不会发布目标文件(@TempDir Path temp) throws Exception {
        HttpServer server = server();
        server.createContext("/short", exchange -> {
            byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path target = temp.resolve("download.jar");
        try {
            Http.DownloadOutcome outcome = Http.download(url(server, "/short"), target, null, 8);
            assertFalse(outcome.ok());
            assertEquals(Http.FailureKind.UNSTABLE, outcome.kind());
            assertTrue(outcome.detail().contains("长度不符"));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(temp.resolve("download.jar.part")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 遗留part不会阻止下一次完整下载(@TempDir Path temp) throws Exception {
        HttpServer server = server();
        byte[] body = "complete".getBytes(StandardCharsets.UTF_8);
        server.createContext("/retry", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path target = temp.resolve("download.jar");
        Path part = temp.resolve("download.jar.part");
        Files.writeString(part, "stale");
        try {
            Http.DownloadOutcome outcome = Http.download(url(server, "/retry"), target, null, body.length);
            assertTrue(outcome.ok(), outcome.detail());
            assertArrayEquals(body, Files.readAllBytes(target));
            assertFalse(Files.exists(part));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 中文空格和较长路径可完成下载(@TempDir Path temp) throws Exception {
        HttpServer server = server();
        byte[] body = "path-compatible".getBytes(StandardCharsets.UTF_8);
        server.createContext("/path", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path target = temp.resolve("中文 整合 包")
                .resolve("第一层目录-用于验证较长路径")
                .resolve("第二层目录-仍包含空格")
                .resolve("模组 下载.jar");
        try {
            Http.DownloadOutcome outcome = Http.download(url(server, "/path"), target, null, body.length);
            assertTrue(outcome.ok(), outcome.detail());
            assertArrayEquals(body, Files.readAllBytes(target));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 探测超时有独立看门狗且不等待服务端结束() {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = server(executor);
        server.createContext("/slow", exchange -> {
            requested.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            long started = System.nanoTime();
            String failure = Http.probe(url(server, "/slow"), null, 100);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertNotNull(failure);
            assertTrue(failure.contains("超时")
                            || failure.toLowerCase(Locale.ROOT).contains("timeout"),
                    failure);
            assertTrue(elapsedMs < 2_000, "probe timeout should be bounded, was " + elapsedMs + "ms");
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void 取消下载会停止传输进程并清理part(@TempDir Path temp) throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = server(executor);
        server.createContext("/stalled", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1_000_000);
                exchange.getResponseBody().write(new byte[1024]);
                exchange.getResponseBody().flush();
                requested.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        Path target = temp.resolve("cancelled.jar");
        try (TaskService tasks = new TaskService()) {
            var task = tasks.submit("fixture download", context ->
                    TaskResult.success(Http.download(url(server, "/stalled"), target, context::log, 1_000_000), "fixture"), null);
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            task.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED,
                    task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(temp.resolve("cancelled.jar.part")));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static HttpServer server() {
        return server(null);
    }

    private static HttpServer server(java.util.concurrent.Executor executor) {
        HttpServer server = assertDoesNotThrow(() ->
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
        if (executor != null) server.setExecutor(executor);
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
