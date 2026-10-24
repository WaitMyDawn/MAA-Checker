package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.transaction.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileActionTest {
    @Test
    void 取消在文件提交完成后生效且台账可恢复(@TempDir Path temp) throws Exception {
        var instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path mod = RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        String hash = RepairInputs.hash(mod);
        CountDownLatch intent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TaskService service = new TaskService()) {
            var task = service.submit("fixture", context -> {
                try (var session = TransactionSession.create(temp.resolve("state"), instance, (point, action) -> {
                    if (point == TransactionSession.Point.AFTER_INTENT) {
                        intent.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("不应中断当前提交", e); }
                    }
                })) {
                    context.atomicFileAction(() -> session.removeMod("broken", "broken.jar", hash, 1, "fixture", List.of("fixture evidence")));
                    context.checkpoint();
                    return TaskResult.success(null, "不应到达");
                }
            }, null);
            try {
                assertTrue(intent.await(5, TimeUnit.SECONDS));
                assertTrue(task.cancel());
                assertEquals(TaskSnapshot.State.CANCELLING, task.snapshot().state());
                assertFalse(task.completion().toCompletableFuture().isDone());
            } finally { release.countDown(); }
            assertEquals(TaskSnapshot.State.CANCELLED, task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
        }
        assertFalse(Files.exists(mod));
        var history = TransactionSession.history(temp.resolve("state"), instance).getFirst();
        assertEquals(ActionRecord.State.APPLIED, history.actions().getFirst().state());
        try (var session = TransactionSession.resume(temp.resolve("state"), instance, history.header().sessionId())) {
            assertEquals(ActionRecord.State.REVERTED, session.restoreNext().state());
            assertNull(session.restoreNext());
        }
        assertEquals(hash, RepairInputs.hash(mod));
    }
}
