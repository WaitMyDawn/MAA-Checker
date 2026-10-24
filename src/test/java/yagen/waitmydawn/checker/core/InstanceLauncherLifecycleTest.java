package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.task.ProcessFixture;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InstanceLauncherLifecycleTest {
    private LaunchSpec spec(Path tmp, String mode) {
        return new LaunchSpec(ProcessFixture.command(mode, tmp.resolve("latest.log")), "fixture",
                tmp, tmp.resolve("latest.log"), tmp, tmp.resolve("stdout.log"), tmp.resolve("run"), 21, List.of());
    }

    private void capturePid(String message, AtomicLong pid) {
        if (message.startsWith("进程已启动 pid=")) {
            String number = message.substring("进程已启动 pid=".length()).split("（", 2)[0];
            pid.set(Long.parseLong(number));
        }
    }

    private boolean alive(long pid) { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }

    @Test
    void 成功标记与进程退出同时出现不能判通过(@TempDir Path tmp) throws Exception {
        LaunchResult result = InstanceLauncher.run(spec(tmp, "marker-exit"), 10, 0, line -> { });
        assertEquals(LaunchVerdict.EXITED, result.verdict());
    }

    @Test
    void 观察通过后清理进程再返回(@TempDir Path tmp) throws Exception {
        AtomicLong pid = new AtomicLong();
        LaunchResult result = InstanceLauncher.run(spec(tmp, "marker-live"), 10, 0, line -> capturePid(line, pid));
        assertEquals(LaunchVerdict.PASS, result.verdict());
        assertTrue(result.killed());
        assertFalse(alive(pid.get()));
        assertTrue(java.nio.file.Files.readString(result.stdoutLog()).contains("fixture stdout captured"));
    }

    @Test
    void 日志回调异常不能遗留已启动进程(@TempDir Path tmp) {
        AtomicLong pid = new AtomicLong();
        assertThrows(IllegalStateException.class, () -> InstanceLauncher.run(spec(tmp, "sleep"), 10, 0, line -> {
            capturePid(line, pid);
            if (pid.get() != 0) throw new IllegalStateException("fixture observer failure");
        }));
        assertNotEquals(0, pid.get());
        assertFalse(alive(pid.get()));
    }

    @Test
    void 取消监控后回收进程且终态不为通过(@TempDir Path tmp) throws Exception {
        AtomicLong pid = new AtomicLong();
        CountDownLatch started = new CountDownLatch(1);
        try (TaskService service = new TaskService()) {
            var handle = service.submit("fixture", context -> {
                LaunchResult result = InstanceLauncher.run(spec(tmp, "sleep"), 30, 0, line -> {
                    capturePid(line, pid);
                    if (pid.get() != 0) started.countDown();
                }, context);
                return TaskResult.success(result, "完成");
            }, null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            handle.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED, handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            assertFalse(alive(pid.get()));
        }
    }

    @Test
    void 超时回收进程并保留超时结论(@TempDir Path tmp) throws Exception {
        AtomicLong pid = new AtomicLong();
        LaunchResult result = InstanceLauncher.run(spec(tmp, "sleep"), 1, 0, line -> capturePid(line, pid));
        assertEquals(LaunchVerdict.TIMEOUT, result.verdict());
        assertFalse(alive(pid.get()));
    }

    @Test
    void 非零退出保留失败结论(@TempDir Path tmp) throws Exception {
        LaunchResult result = InstanceLauncher.run(spec(tmp, "exit"), 10, 0, line -> { });
        assertEquals(LaunchVerdict.FAIL, result.verdict());
        assertEquals(7, result.exitCode());
    }
}
