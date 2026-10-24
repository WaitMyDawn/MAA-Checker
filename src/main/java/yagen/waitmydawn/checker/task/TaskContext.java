package yagen.waitmydawn.checker.task;

import java.util.concurrent.CancellationException;

/** 仅工作线程持有上下文；取消请求由任务句柄发出。 */
public final class TaskContext {
    private final TaskService.Handle<?> handle;
    private final ProcessScope processes = new ProcessScope();

    TaskContext(TaskService.Handle<?> handle) { this.handle = handle; }

    public void checkpoint() {
        if (handle.cancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("任务已请求停止");
        }
    }

    public void phase(TaskSnapshot.Phase phase, String message) {
        checkpoint();
        handle.update(phase, message, handle.snapshot().round());
    }

    public void round(int round) {
        checkpoint();
        if (round < 0) throw new IllegalArgumentException("轮次不能为负数");
        handle.update(handle.snapshot().phase(), handle.snapshot().message(), round);
    }

    public void log(String message) {
        checkpoint();
        handle.log(message);
    }

    public ProcessScope processes() { return processes; }

    @FunctionalInterface
    public interface FileAction { void run() throws java.io.IOException; }

    /** 短文件事务内延后线程中断；调用方不得在此执行下载、启动或等待用户输入。 */
    public void atomicFileAction(FileAction action) throws java.io.IOException {
        handle.beginAtomic();
        try { action.run(); }
        finally { handle.endAtomic(); }
    }
}
