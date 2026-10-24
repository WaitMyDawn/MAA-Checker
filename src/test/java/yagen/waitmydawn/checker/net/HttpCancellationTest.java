package yagen.waitmydawn.checker.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.task.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class HttpCancellationTest {
    @Test
    void 明确HTTP404不重试也不触发传输兜底() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/missing", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        Http.setProxy(null);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
        try {
            assertNull(Http.getStringOrNull(url, null));
            assertThrows(Http.HttpStatusException.class, () -> Http.getString(url, null));
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
    @Test
    void 取消外部传输进程后才发布终态(@TempDir Path temp) throws Exception {
        Path pidFile = temp.resolve("pid");
        try (TaskService tasks = new TaskService()) {
            var task = tasks.submit("fixture transfer", context -> {
                Http.runCaptureOrThrow(ProcessFixture.command("sleep", pidFile));
                return TaskResult.success(null, "不应到达");
            }, null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(pidFile));
            long pid = readPid(pidFile, deadline);
            task.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED, task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void Java请求取消不触发curl兜底() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/slow", exchange -> {
            requested.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        Http.setProxy(null);
        try (TaskService tasks = new TaskService()) {
            var task = tasks.submit("fixture request", context -> {
                Http.getString("http://127.0.0.1:" + server.getAddress().getPort() + "/slow", context::log);
                return TaskResult.success(null, "不应到达");
            }, null);
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            task.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED, task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /**
     * Waits until the fixture's pid signal contains a complete value.
     *
     * <p>The fixture publishes signals by renaming a fully written sibling file into place, but a
     * retry loop keeps this assertion robust if a future fixture writes less atomically or the
     * filesystem reports the target before its content is visible.
     */
    private static long readPid(Path file, long deadline) throws Exception {
        while (System.nanoTime() < deadline) {
            try {
                String value = Files.readString(file).trim();
                if (!value.isEmpty()) return Long.parseLong(value);
            } catch (IOException | NumberFormatException ignored) {
                // The publication may still be in flight; retry until the deadline.
            }
            Thread.sleep(10);
        }
        fail("pid signal was not readable before the deadline: " + file);
        return -1;
    }
}
