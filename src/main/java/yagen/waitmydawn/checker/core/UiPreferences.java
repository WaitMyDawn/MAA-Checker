package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 全局桌面外观偏好；与实例恢复资料分开保存。 */
public record UiPreferences(boolean dark, boolean motion) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = "ui-settings-v1.json";

    public static UiPreferences defaults() {
        return new UiPreferences(false, true);
    }

    public static UiPreferences load(Path toolRoot) throws IOException {
        Path file = path(toolRoot, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return defaults();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("外观设置不是普通文件");
        }
        return JSON.readValue(Files.readAllBytes(file), UiPreferences.class);
    }

    public static void save(Path toolRoot, UiPreferences preferences) throws IOException {
        Path file = path(toolRoot, true);
        Path temp = file.resolveSibling(FILE + ".tmp-" + UUID.randomUUID());
        try {
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(preferences);
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
