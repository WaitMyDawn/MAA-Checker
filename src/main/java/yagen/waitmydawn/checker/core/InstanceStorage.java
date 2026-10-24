package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** 实例受管文件集中在单一目录；不得将实例运行库链接到其他实例。 */
public final class InstanceStorage {
    private InstanceStorage() { }
    public static Path root(GameInstance instance, boolean create) throws IOException {
        return directory(instance.dir().toRealPath(), ".maa-checker", create);
    }
    public static Path directory(Path parent, String child, boolean create) throws IOException {
        Path path = parent.resolve(child);
        if (create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !PathSafety.isDirectory(path)) {
            throw new IOException("受管目录包含链接或异常文件：" + path);
        }
        return path;
    }
    public static Path libraries(GameInstance instance, String runtime, boolean create) throws IOException {
        UUID.fromString(runtime);
        Path path = directory(root(instance, create), "runtime", create);
        path = directory(path, runtime, create);
        return directory(path, "libraries", create);
    }
    public static void requireIsolatedMods(GameInstance instance) throws IOException {
        if (!PathSafety.samePath(instance.modsDir(), instance.dir().resolve("mods"))) {
            throw new IOException("该实例使用共享 mods 目录。请先在启动器开启实例隔离，再进行自动修复，以免影响其他实例。");
        }
    }
}
