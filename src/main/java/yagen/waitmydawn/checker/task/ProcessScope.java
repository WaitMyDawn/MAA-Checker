package yagen.waitmydawn.checker.task;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 只接管由本作用域创建的进程及其后代；不按名称或外部 PID 搜索进程。 */
public final class ProcessScope implements AutoCloseable {
    public static final class CleanupException extends IllegalStateException {
        CleanupException(String message, Throwable cause) { super(message, cause); }
    }
    private final List<Process> roots = new ArrayList<>();
    private final Set<ProcessHandle> observed = new LinkedHashSet<>();
    private boolean closed;
    private Thread watcher;

    public synchronized Process start(ProcessBuilder builder) throws IOException {
        if (closed) throw new IllegalStateException("进程作用域已关闭");
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        Process process = builder.start();
        roots.add(process);
        observed.add(process.toHandle());
        if (watcher == null) {
            watcher = Thread.ofVirtual().name("checker-process-watch").start(this::watch);
        }
        return process;
    }

    private void watch() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (this) {
                    if (closed) return;
                    captureDescendants();
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void captureDescendants() {
        for (ProcessHandle parent : List.copyOf(observed)) {
            if (parent.isAlive()) {
                try (var descendants = parent.descendants()) { descendants.forEach(observed::add); }
            }
        }
    }

    /** 返回已观察到的所属进程，主要用于诊断和生命周期验收。 */
    public synchronized Set<Long> ownedPids() {
        if (!closed) captureDescendants();
        return observed.stream().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized void close() {
        List<ProcessHandle> handles;
        synchronized (this) {
            if (closed) return;
            captureDescendants();
            closed = true;
            if (watcher != null) watcher.interrupt();
            handles = new ArrayList<>(observed);
        }
        // 暂存中断状态，确保取消引起的中断不会跳过有界进程回收。
        boolean interrupted = Thread.interrupted();
        try {
            java.util.Collections.reverse(handles);
            Set<ProcessHandle> rootHandles = roots.stream().map(Process::toHandle).collect(java.util.stream.Collectors.toSet());
            for (ProcessHandle handle : handles) if (!rootHandles.contains(handle) && handle.isAlive()) handle.destroyForcibly();
            // Process.destroyForcibly 同时释放 JDK 管理的进程流，不能只操作其 ProcessHandle。
            for (Process process : roots) process.destroyForcibly();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            for (Process process : roots) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            for (ProcessHandle handle : handles) {
                if (!handle.isAlive()) continue;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { handle.onExit().get(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
                catch (java.util.concurrent.TimeoutException e) { break; }
                catch (java.util.concurrent.ExecutionException e) { throw new CleanupException("无法等待所属进程退出", e); }
            }
            if (handles.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new CleanupException("所属进程未能在 5 秒内结束，不能确认任务清理完成", null);
            }
        } finally {
            for (Process process : roots) {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
