package yagen.waitmydawn.checker.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.task.ProcessScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowLeaseTest {

    @Test
    void 同线程嵌套可重入而其他线程必须拒绝(@TempDir Path tmp) throws Exception {
        GameInstance instance = TransactionSessionTest.instance(tmp.resolve("game"));
        try (var outer = WorkflowLease.acquire(instance)) {
            try (var inner = WorkflowLease.acquire(instance)) {
                assertNotNull(inner);
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<IOException> blocked = executor.submit(() -> {
                    try (var ignored = WorkflowLease.acquire(instance)) {
                        return null;
                    } catch (IOException e) {
                        return e;
                    }
                });
                IOException failure = blocked.get(5, TimeUnit.SECONDS);
                assertNotNull(failure);
                assertTrue(failure.getMessage().contains("已有活动修改操作"), failure.getMessage());
            }
            try (var reopened = WorkflowLease.acquire(instance)) {
                assertNotNull(reopened);
            }
        }
    }

    @Test
    void 事务会话复用外层租约且关闭后释放(@TempDir Path tmp) throws Exception {
        GameInstance instance = TransactionSessionTest.instance(tmp.resolve("game"));
        try (var lease = WorkflowLease.acquire(instance);
             var session = TransactionSession.create(tmp.resolve("state"), instance)) {
            assertNotNull(session.header());
        }
        try (var reopened = WorkflowLease.acquire(instance)) {
            assertNotNull(reopened);
        }
    }

    @Test
    void 不同隔离实例可以同时持有各自租约(@TempDir Path tmp) throws Exception {
        GameInstance first = TransactionSessionTest.instance(tmp.resolve("one"));
        GameInstance second = TransactionSessionTest.instance(tmp.resolve("two"));
        try (var firstLease = WorkflowLease.acquire(first);
             var secondLease = WorkflowLease.acquire(second)) {
            assertNotNull(firstLease);
            assertNotNull(secondLease);
        }
    }

    @Test
    void 任务异常退出后租约仍会释放(@TempDir Path tmp) throws Exception {
        GameInstance instance = TransactionSessionTest.instance(tmp.resolve("game"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<IOException> failed = executor.submit(() -> {
                try (var lease = WorkflowLease.acquire(instance)) {
                    throw new IOException("fixture failure");
                } catch (IOException e) {
                    return e;
                }
            });
            assertEquals("fixture failure", failed.get(5, TimeUnit.SECONDS).getMessage());
        }
        try (var reopened = WorkflowLease.acquire(instance)) {
            assertNotNull(reopened);
        }
    }

    @Test
    void 另一JVM无法在等待期间取得同实例租约(@TempDir Path tmp) throws Exception {
        GameInstance instance = TransactionSessionTest.instance(tmp.resolve("game"));
        Path state = tmp.resolve("state");
        try (var lease = WorkflowLease.acquire(instance);
             var processes = new ProcessScope()) {
            Process blocked = processes.start(new ProcessBuilder(TransactionProcessFixture.command(
                    "workflow-lock", instance.gameRoot(), state, "unused"))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(blocked.waitFor(10, TimeUnit.SECONDS));
            assertEquals(23, blocked.exitValue());
        }
        try (var processes = new ProcessScope()) {
            Process available = processes.start(new ProcessBuilder(TransactionProcessFixture.command(
                    "workflow-lock", instance.gameRoot(), state, "unused"))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(available.waitFor(10, TimeUnit.SECONDS));
            assertEquals(24, available.exitValue());
        }
    }

    @Test
    void 旧修复和还原入口同样受实例租约保护(@TempDir Path tmp) throws Exception {
        GameInstance instance = TransactionSessionTest.instance(tmp.resolve("game"));
        Path tool = tmp.resolve("tool");
        CheckerEngine.Options options = CheckerEngine.Options.defaults();
        try (var lease = WorkflowLease.acquire(instance);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<IOException> fix = executor.submit(() -> {
                try {
                    CheckerEngine.fix(instance, tool, options, ignored -> { });
                    return null;
                } catch (Exception e) {
                    return e instanceof IOException io ? io : new IOException(e);
                }
            });
            Future<IOException> restore = executor.submit(() -> {
                try {
                    CheckerEngine.restore(instance, tool, null, ignored -> { });
                    return null;
                } catch (Exception e) {
                    return e instanceof IOException io ? io : new IOException(e);
                }
            });
            assertLockDenied(fix.get(5, TimeUnit.SECONDS));
            assertLockDenied(restore.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void 旧修复入口继续拒绝共享模组目录(@TempDir Path tmp) throws Exception {
        GameInstance isolated = TransactionSessionTest.instance(tmp.resolve("game"));
        Path sharedMods = Files.createDirectories(tmp.resolve("shared-mods"));
        GameInstance shared = new GameInstance(isolated.name(), isolated.dir(), isolated.versionJson(),
                isolated.mcVersion(), isolated.loader(), isolated.loaderVersion(), sharedMods, 0,
                isolated.gameRoot());

        IOException failure = assertThrows(IOException.class, () -> CheckerEngine.fix(shared,
                tmp.resolve("tool"), CheckerEngine.Options.defaults(), ignored -> { }));

        assertTrue(failure.getMessage().contains("共享 mods"), failure.getMessage());
    }

    private static void assertLockDenied(IOException failure) {
        assertNotNull(failure);
        assertTrue(failure.getMessage().contains("已有活动修改操作"), failure.getMessage());
    }
}
