package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProcessScopeTest {
    @Test
    void 已观察到的子进程在父进程先退出后仍可回收(@TempDir Path tmp) throws Exception {
        Path signal = tmp.resolve("child.pid");
        ProcessHandle child = null;
        try (ProcessScope scope = new ProcessScope()) {
            Process parent = scope.start(new ProcessBuilder(ProcessFixture.command("tree-release", signal))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while ((!Files.exists(signal) || Files.size(signal) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(signal));
            long pid = Long.parseLong(Files.readString(signal));
            child = ProcessHandle.of(pid).orElseThrow();
            assertTrue(scope.ownedPids().contains(pid));
            Files.writeString(tmp.resolve("release"), "exit");
            assertTrue(parent.waitFor(3, TimeUnit.SECONDS));
            assertTrue(child.isAlive());
        } finally { if (child != null && child.isAlive()) child.destroyForcibly(); }
        assertNotNull(child);
        assertFalse(child.isAlive());
    }

    @Test
    void 父子进程都在作用域关闭时回收(@TempDir Path tmp) throws Exception {
        Path childSignal = tmp.resolve("child.pid");
        Process parent;
        ProcessHandle child = null;
        try (ProcessScope scope = new ProcessScope()) {
            parent = scope.start(new ProcessBuilder(ProcessFixture.command("tree", childSignal))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while ((!Files.exists(childSignal) || Files.size(childSignal) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(childSignal), "子进程启动超时");
            long childPid = Long.parseLong(Files.readString(childSignal));
            child = ProcessHandle.of(childPid).orElseThrow();
            assertTrue(scope.ownedPids().contains(childPid));
            assertTrue(child.isAlive());
        } finally {
            // 若断言中途失败，只结束由测试信号文件确认的子进程。
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
        assertFalse(parent.isAlive());
        assertNotNull(child);
        assertFalse(child.isAlive());
    }

    @Test
    void 作用域关闭后拒绝启动且重复关闭无副作用(@TempDir Path tmp) {
        ProcessScope scope = new ProcessScope();
        scope.close();
        scope.close();
        assertThrows(IllegalStateException.class, () -> scope.start(new ProcessBuilder(ProcessFixture.command("sleep", tmp.resolve("signal")))));
    }
}
