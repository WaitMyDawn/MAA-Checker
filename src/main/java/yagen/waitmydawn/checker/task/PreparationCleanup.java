package yagen.waitmydawn.checker.task;

import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.transaction.WorkflowLease;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 仅清理独立准备区；恢复对象、事务备份和仍需引用的运行库不属于缓存。 */
public final class PreparationCleanup {
    public record File(String relative, long size, long modified) { }
    public record Preview(String root, List<File> files, List<String> directories) {
        public Preview { files = List.copyOf(files); directories = List.copyOf(directories); }
        public long bytes() { return files.stream().mapToLong(File::size).sum(); }
    }
    private PreparationCleanup() { }
    public static TaskService.Work<Preview> preview(GameInstance instance) {
        return context -> TaskResult.success(inspect(instance, context::checkpoint), "已统计准备缓存，恢复快照不在清单内");
    }
    public static TaskService.Work<Integer> clear(GameInstance instance, Preview preview) {
        return context -> {
            try (var lease = WorkflowLease.acquire(instance)) {
                Preview current = inspect(instance, context::checkpoint);
                if (!current.equals(preview)) throw new IOException("准备缓存已变化，请重新预览");
                Path root = Path.of(current.root());
                int removed = 0;
                for (File file : current.files()) {
                    context.checkpoint();
                    Path target = root.resolve(file.relative()).normalize();
                    if (!target.startsWith(root) || !target.toRealPath().equals(target)) throw new IOException("准备缓存路径发生变化");
                    BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile() || attrs.size() != file.size() || attrs.lastModifiedTime().toMillis() != file.modified()) throw new IOException("准备缓存文件发生变化");
                    Files.delete(target); removed++;
                }
                for (String relative : current.directories().reversed()) {
                    context.checkpoint();
                    Path target = root.resolve(relative).normalize();
                    if (!target.startsWith(root) || !target.toRealPath().equals(target)) throw new IOException("准备缓存目录发生变化");
                    Files.delete(target);
                }
                return TaskResult.success(removed, "已清理 " + removed + " 个准备文件；轮次快照、事务与运行库保留");
            }
        };
    }
    public static Preview inspect(GameInstance instance) throws IOException {
        return inspect(instance, () -> { });
    }

    private static Preview inspect(GameInstance instance, Runnable checkpoint) throws IOException {
        Path root = InstanceStorage.directory(InstanceStorage.root(instance, false), "preparation", false);
        List<File> files = new ArrayList<>(); List<String> directories = new ArrayList<>();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void check(Path path) throws IOException {
                checkpoint.run();
                if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("准备区包含链接，拒绝清理");
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith(".env") || name.equals("secrets") || name.equals("credentials") || name.endsWith(".key")
                        || name.endsWith(".pem") || name.endsWith(".p12") || name.endsWith(".pfx")) throw new IOException("准备区存在受保护内容，拒绝清理");
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                check(dir);
                if (!dir.equals(root)) {
                    if (dir.getParent().equals(root) && !dir.getFileName().toString().matches("(prepare|installer|candidate)-[A-Za-z0-9-]+")) throw new IOException("准备区包含未知目录，拒绝清理");
                    directories.add(root.relativize(dir).toString());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                check(file);
                if (!attrs.isRegularFile() || file.getParent().equals(root)) throw new IOException("准备区包含未知文件，拒绝清理");
                files.add(new File(root.relativize(file).toString(), attrs.size(), attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(File::relative));
        // 父目录先于子目录排序，删除阶段按逆序处理，且不递归删除新出现的内容。
        directories.sort(Comparator.comparingInt((String path) -> Path.of(path).getNameCount()).thenComparing(String::compareTo));
        return new Preview(root.toString(), files, directories);
    }
}
