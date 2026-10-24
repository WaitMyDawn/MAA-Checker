package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.task.TaskService;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.UUID;

/**
 * 全实例共用的修复策略。
 *
 * <p>策略存放在应用数据根，而不是某个实例目录。默认所有问题分类自动执行、上限 10 轮；
 * 实例内的旧 {@code workflows/policy.json} 保留但不再作为当前策略来源。
 */
public final class GlobalRepairPolicy {
    public record Snapshot(RepairPolicy policy, boolean saved) {
    }

    private record Stored(int schema, RepairPolicy policy) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = "repair-policy-v1.json";

    private GlobalRepairPolicy() {
    }

    public static RepairPolicy defaults() {
        EnumMap<RepairPolicy.Category, RepairPolicy.Mode> modes =
                new EnumMap<>(RepairPolicy.Category.class);
        for (RepairPolicy.Category category : RepairPolicy.Category.values()) {
            modes.put(category, RepairPolicy.Mode.AUTO);
        }
        return new RepairPolicy(modes, 10);
    }

    public static Snapshot load(Path toolRoot) throws IOException {
        Path file = path(toolRoot, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(defaults(), false);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("全局修复策略不是普通文件");
        }
        Stored stored = JSON.readValue(Files.readAllBytes(file), Stored.class);
        if (stored.schema() != 1 || stored.policy() == null) {
            throw new IOException("全局修复策略格式无效");
        }
        return new Snapshot(stored.policy(), true);
    }

    public static TaskService.Work<RepairPolicy> save(Path toolRoot, RepairPolicy policy) {
        return context -> {
            context.phase(TaskSnapshot.Phase.PLANNING, "正在保存全局修复策略");
            Path file = path(toolRoot, true);
            Path temp = file.resolveSibling(FILE + ".tmp-" + UUID.randomUUID());
            try {
                byte[] bytes = JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(new Stored(1, policy));
                context.atomicFileAction(() -> {
                    try {
                        Files.write(temp, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE);
                        try {
                            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException e) {
                            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                });
                return TaskResult.success(policy, "全局修复策略已保存，对所有实例生效");
            } catch (IOException e) {
                throw new IOException("保存全局修复策略失败：" + file, e);
            }
        };
    }

    private static Path path(Path toolRoot, boolean create) throws IOException {
        Path state = toolRoot.toAbsolutePath().normalize().resolve("state");
        if (create) Files.createDirectories(state);
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)
                || !state.toRealPath().equals(state))) {
            throw new IOException("全局状态目录包含链接或异常文件");
        }
        return state.resolve(FILE);
    }
}
