package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 覆盖诊断、授权等待、文件修改和恢复的实例级租约。
 *
 * <p>同一线程可重入，使“多轮工作流 → 单批执行 → 文件事务”等嵌套调用共用一把外层租约；
 * 其他线程和其他 JVM 仍必须在入口处失败，不能等待或抢占。锁文件稳定保留以避免重建
 * inode 后出现两个互不相见的锁。
 */
public final class WorkflowLease implements AutoCloseable {
    private static final Map<Path, Held> HELD = new HashMap<>();
    private static final class Held {
        private final Thread owner;
        private final FileChannel channel;
        private int depth;
        private Held(Thread owner, FileChannel channel) { this.owner = owner; this.channel = channel; depth = 1; }
    }

    private final Path file;
    private boolean closed;

    private WorkflowLease(Path file) { this.file = file; }

    public static WorkflowLease acquire(GameInstance instance) throws IOException {
        Path file = InstanceStorage.root(instance, true).resolve("workflow.lock").toAbsolutePath().normalize();
        synchronized (HELD) {
            Held held = HELD.get(file);
            if (held != null) {
                if (held.owner != Thread.currentThread()) throw new IOException("此实例已有活动修改操作");
                held.depth++;
                return new WorkflowLease(file);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) TransactionFiles.regular(file);
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            try {
                if (channel.tryLock() == null) throw new IOException("此实例已有活动修改操作");
            } catch (IOException | OverlappingFileLockException e) {
                channel.close();
                throw new IOException("此实例已有活动修改操作或租约不可用", e);
            }
            HELD.put(file, new Held(Thread.currentThread(), channel));
            return new WorkflowLease(file);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (HELD) {
            if (closed) return;
            closed = true;
            Held held = HELD.get(file);
            if (held == null || held.owner != Thread.currentThread()) {
                throw new IllegalStateException("实例租约必须在获取它的线程关闭");
            }
            held.depth--;
            if (held.depth > 0) return;
            HELD.remove(file);
            held.channel.close();
        }
    }
}
