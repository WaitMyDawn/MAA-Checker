package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 路径类型与链接检查。
 *
 * <p>Windows 的 8.3 短路径（例如 {@code RUNNER~1}）与 {@code toRealPath()} 展开后的长路径
 * 指向同一对象。不能用“真实路径字符串是否等于输入路径”判断链接，否则 CI 临时目录会被误报。
 */
public final class PathSafety {
    private PathSafety() {
    }

    public static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !containsLink(path);
    }

    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !containsLink(path);
    }

    public static boolean containsLink(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            try {
                BasicFileAttributes attrs = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // Windows junction 在 JDK 中常表现为 isOther，而不是 isSymbolicLink。
                if (attrs.isSymbolicLink() || attrs.isOther()) return true;
            } catch (java.nio.file.NoSuchFileException missing) {
                return false;
            } catch (IOException | RuntimeException error) {
                return true;
            }
        }
        return false;
    }
}
