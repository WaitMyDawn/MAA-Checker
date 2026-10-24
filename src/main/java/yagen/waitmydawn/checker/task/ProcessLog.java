package yagen.waitmydawn.checker.task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 日志文件由父 JVM 持有，避免强制终止子进程后遗留继承的输出文件句柄。 */
public final class ProcessLog implements AutoCloseable {
    private final OutputStream output;
    private Thread reader;
    private volatile IOException failure;

    public ProcessLog(Path file) throws IOException {
        output = Files.newOutputStream(file);
    }

    public void attach(Process process) {
        if (reader != null) throw new IllegalStateException("日志采集器只能绑定一个进程");
        reader = Thread.ofVirtual().name("checker-process-output").start(() -> {
            InputStream input = process.getInputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                int size;
                try { size = input.read(buffer); }
                catch (IOException e) {
                    if (process.isAlive()) failure = e;
                    return;
                }
                if (size < 0) return;
                try { output.write(buffer, 0, size); output.flush(); }
                catch (IOException e) { failure = e; return; }
            }
        });
    }

    @Override
    public void close() throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (reader != null && reader.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ProcessScope.CleanupException("进程日志采集未能结束", null);
                try { TimeUnit.NANOSECONDS.timedJoin(reader, remaining); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (failure != null) throw new UncheckedIOException("进程日志写入失败", failure);
        } finally {
            output.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
