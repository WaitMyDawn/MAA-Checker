package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {
    @Test
    void 取消与快速完成竞态只产生一个一致终态() throws Exception {
        try (TaskService service = new TaskService()) {
            for (int i = 0; i < 30; i++) {
                var handle = service.submit("race", context -> TaskResult.success(1, "完成"), null);
                boolean accepted = handle.cancel();
                var result = handle.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(accepted ? TaskSnapshot.State.CANCELLED : TaskSnapshot.State.SUCCEEDED, result.state());
                assertEquals(result.state(), handle.snapshot().state());
                assertFalse(handle.cancel());
            }
        }
    }

    @Test
    void 取消后等到清理完成才发布终态且拒绝并发任务() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch mayFinish = new CountDownLatch(1);
        try (TaskService service = new TaskService()) {
            var handle = service.submit("fixture", context -> {
                running.countDown();
                try { Thread.sleep(60_000); }
                catch (InterruptedException ignored) { mayFinish.await(5, TimeUnit.SECONDS); }
                return TaskResult.success(1, "完成");
            }, null);
            assertTrue(running.await(3, TimeUnit.SECONDS));
            assertTrue(handle.cancel());
            assertFalse(handle.cancel());
            assertEquals(TaskSnapshot.State.CANCELLING, handle.snapshot().state());
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertThrows(IllegalStateException.class, () -> service.submit("other", c -> TaskResult.success(2, "ok"), null));
            mayFinish.countDown();
            assertEquals(TaskSnapshot.State.CANCELLED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
            assertEquals(TaskSnapshot.State.SUCCEEDED, service.submit("next", c -> TaskResult.success(2, "ok"), null)
                    .completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state());
        } finally { mayFinish.countDown(); }
    }

    @Test
    void 异常不会变成成功且观察器故障不影响结果() throws Exception {
        List<TaskEvent> events = new CopyOnWriteArrayList<>();
        try (TaskService service = new TaskService()) {
            var result = service.submit("fixture", context -> {
                context.phase(TaskSnapshot.Phase.PRECHECK, "准备");
                context.log("fixture log");
                throw new java.io.IOException("fixture failure");
            }, event -> { events.add(event); throw new IllegalStateException("observer failure"); })
                    .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.FAILED, result.state());
            assertInstanceOf(java.io.IOException.class, result.cause());
            assertEquals(TaskSnapshot.Phase.FINISHED, events.getLast().snapshot().phase());
            for (int i = 1; i < events.size(); i++) assertTrue(events.get(i).sequence() > events.get(i - 1).sequence());
        }
    }

    @Test
    void 关闭服务回收所属进程而不影响无关进程(@TempDir Path tmp) throws Exception {
        AtomicReference<Process> owned = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try (ProcessScope unrelated = new ProcessScope()) {
            Process other = unrelated.start(new ProcessBuilder(ProcessFixture.command("sleep", tmp.resolve("other")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            TaskService service = new TaskService();
            var handle = service.submit("fixture", context -> {
                Process child = context.processes().start(new ProcessBuilder(ProcessFixture.command("sleep", tmp.resolve("owned")))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD));
                owned.set(child);
                started.countDown();
                child.waitFor();
                return TaskResult.success(0, "退出");
            }, null);
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                service.close();
                assertFalse(owned.get().isAlive());
                assertTrue(other.isAlive());
                assertEquals(TaskSnapshot.State.CANCELLED, handle.snapshot().state());
                assertThrows(IllegalStateException.class, () -> service.submit("closed", c -> TaskResult.success(0, ""), null));
            } finally { service.close(); }
        }
    }

    @Test
    void 清理异常不能被取消结果掩盖() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (TaskService service = new TaskService()) {
            var handle = service.submit("fixture", context -> {
                started.countDown();
                try { Thread.sleep(60_000); }
                catch (InterruptedException e) {
                    e.addSuppressed(new ProcessScope.CleanupException("fixture cleanup", null));
                    throw e;
                }
                return TaskResult.success(0, "");
            }, null);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            handle.cancel();
            assertEquals(TaskSnapshot.State.FAILED, handle.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void 可取消扫描复用领域逻辑且没有文件改动(@TempDir Path tmp) throws Exception {
        Path version = Files.createDirectories(tmp.resolve("versions/example"));
        String json = "{\"id\":\"example\",\"mainClass\":\"main\",\"libraries\":[]}";
        Path file = version.resolve("example.json");
        Files.writeString(file, json);
        try (TaskService service = new TaskService()) {
            var result = service.submit("fixture", CheckerTasks.scan(tmp), null).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.SUCCEEDED, result.state());
            assertEquals(1, result.value().size());
            assertEquals(json, Files.readString(file));
        }
    }
}
