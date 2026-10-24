package yagen.waitmydawn.checker.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 每个服务只运行一个任务；观察器必须快速返回，界面适配器应将事件转交 UI 线程。 */
public final class TaskService implements AutoCloseable {
    @FunctionalInterface
    public interface Work<T> { TaskResult<T> run(TaskContext context) throws Exception; }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Handle<?> active;
    private boolean closed;

    public synchronized <T> Handle<T> submit(String subject, Work<T> work, Consumer<TaskEvent> observer) {
        if (closed) throw new IllegalStateException("任务服务已关闭");
        if (active != null && !active.snapshot().state().terminal()) throw new IllegalStateException("已有任务正在运行或清理");
        Handle<T> handle = new Handle<>(Objects.requireNonNull(subject), Objects.requireNonNull(work), observer);
        active = handle;
        executor.execute(handle::execute);
        return handle;
    }

    /** 请求关闭而不阻塞 UI；完成信号在当前任务释放所属进程后触发。 */
    public CompletionStage<Void> shutdown() {
        Handle<?> handle;
        synchronized (this) {
            closed = true;
            handle = active;
            executor.shutdown();
        }
        if (handle != null) handle.cancel();
        return handle == null ? CompletableFuture.completedFuture(null)
                : handle.completion().thenApply(result -> null);
    }

    @Override
    public void close() {
        try { shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待任务停止时被中断", e); }
        catch (ExecutionException | TimeoutException e) { throw new IllegalStateException("任务未能完成关闭", e); }
    }

    public static final class Handle<T> {
        private final Work<T> work;
        private final Consumer<TaskEvent> observer;
        private final CompletableFuture<TaskResult<T>> done = new CompletableFuture<>();
        private volatile TaskSnapshot snapshot;
        private volatile boolean cancelRequested;
        private Thread worker;
        private long sequence;
        private int atomicDepth;

        private Handle(String subject, Work<T> work, Consumer<TaskEvent> observer) {
            this.work = work;
            this.observer = observer == null ? event -> { } : observer;
            Instant now = Instant.now();
            snapshot = new TaskSnapshot(UUID.randomUUID(), subject, TaskSnapshot.State.QUEUED,
                    TaskSnapshot.Phase.QUEUED, "等待执行", 0, now, now);
        }

        public TaskSnapshot snapshot() { return snapshot; }
        public CompletionStage<TaskResult<T>> completion() { return done.minimalCompletionStage(); }
        public boolean cancellationRequested() { return cancelRequested; }

        public synchronized boolean cancel() {
            if (snapshot.state().terminal() || cancelRequested) return false;
            cancelRequested = true;
            change(TaskSnapshot.State.CANCELLING, snapshot.phase(), atomicDepth > 0 ? "正在停止，等待当前文件提交完成" : "正在停止，等待资源清理", snapshot.round());
            if (worker != null && atomicDepth == 0) worker.interrupt();
            return true;
        }

        synchronized void update(TaskSnapshot.Phase phase, String message, int round) {
            if (snapshot.state().terminal() || cancelRequested) return;
            change(TaskSnapshot.State.RUNNING, phase, message, round);
        }

        synchronized void beginAtomic() {
            if (cancelRequested || Thread.currentThread().isInterrupted()) throw new CancellationException();
            atomicDepth++;
        }

        synchronized void endAtomic() {
            atomicDepth--;
            if (atomicDepth == 0 && cancelRequested && worker != null) worker.interrupt();
        }

        synchronized void log(String text) {
            if (!snapshot.state().terminal() && !cancelRequested) publish(TaskEvent.Kind.LOG, text);
        }

        private void change(TaskSnapshot.State state, TaskSnapshot.Phase phase, String message, int round) {
            snapshot = new TaskSnapshot(snapshot.id(), snapshot.subject(), state, phase, message, round, snapshot.createdAt(), Instant.now());
            publish(TaskEvent.Kind.STATE, message);
        }

        private void publish(TaskEvent.Kind kind, String text) {
            try { observer.accept(new TaskEvent(++sequence, kind, snapshot, text)); }
            catch (RuntimeException ignored) {
                // 展示层观察器故障不得改变领域任务结论或阻止资源清理。
            }
        }

        private void execute() {
            TaskContext context = new TaskContext(this);
            TaskResult<T> result;
            synchronized (this) {
                worker = Thread.currentThread();
                if (!cancelRequested) change(TaskSnapshot.State.RUNNING, TaskSnapshot.Phase.QUEUED, "开始执行", 0);
            }
            try {
                context.checkpoint();
                result = Objects.requireNonNull(work.run(context));
            } catch (CancellationException | InterruptedException e) {
                result = cleanupFailure(e) ? TaskResult.failure("任务资源清理失败", e)
                        : cancelRequested ? cancelled() : TaskResult.failure("工作线程被意外中断", e);
            } catch (Exception e) {
                result = TaskResult.failure("任务执行失败：" + e.getMessage(), e);
            } catch (Error e) {
                result = TaskResult.failure("任务发生不可恢复错误：" + e.getClass().getSimpleName(), e);
            }
            synchronized (this) {
                change(cancelRequested ? TaskSnapshot.State.CANCELLING : TaskSnapshot.State.RUNNING,
                        TaskSnapshot.Phase.CLEANUP, "正在释放任务资源", snapshot.round());
            }
            boolean cleanupFailed = cleanupFailure(result.cause());
            try { context.processes().close(); }
            catch (RuntimeException e) {
                cleanupFailed = true;
                result = TaskResult.failure("任务资源清理失败：" + e.getMessage(), e);
            }
            synchronized (this) {
                // 清理失败优先保留错误；取消请求只能覆盖业务执行结果。
                if (cancelRequested && !cleanupFailed) result = cancelled();
                worker = null;
                change(result.state(), TaskSnapshot.Phase.FINISHED, result.message(), snapshot.round());
            }
            done.complete(result);
        }

        private TaskResult<T> cancelled() {
            return new TaskResult<>(TaskSnapshot.State.CANCELLED, null, "任务已停止，所属进程已清理", null);
        }

        private boolean cleanupFailure(Throwable error) {
            if (error == null) return false;
            if (error instanceof ProcessScope.CleanupException) return true;
            for (Throwable suppressed : error.getSuppressed()) if (cleanupFailure(suppressed)) return true;
            return error.getCause() != error && cleanupFailure(error.getCause());
        }
    }
}
