package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * 返回路径的稳定表示。已存在路径展开 8.3 短名和链接；不存在的尾部路径绑定到最近的
     * 已存在祖先，避免调用方因“文件尚未创建”退回另一套字符串表示。
     */
    public static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException | RuntimeException ignored) {
            // 恢复和预览会处理尚未创建的目标；此时仍应统一已存在前缀的表示。
        }
        List<Path> missing = new ArrayList<>();
        Path ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = ancestor.getFileName();
            if (name == null) return absolute;
            missing.add(0, name);
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) return absolute;
        try {
            Path resolved = ancestor.toRealPath();
            for (Path name : missing) resolved = resolved.resolve(name);
            return resolved.normalize();
        } catch (IOException | RuntimeException ignored) {
            return absolute;
        }
    }

    /** 比较两个路径是否指向同一文件或目录，兼容大小写之外的 Windows 路径别名。 */
    public static boolean samePath(Path left, Path right) {
        Path a = left.toAbsolutePath().normalize();
        Path b = right.toAbsolutePath().normalize();
        if (a.equals(b)) return true;
        try {
            if (Files.exists(a, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(b, LinkOption.NOFOLLOW_LINKS)) {
                return Files.isSameFile(a, b);
            }
        } catch (IOException | RuntimeException ignored) {
            // 路径可能在检查期间被移动；随后用规范路径统一已存在前缀。
        }
        return canonical(a).equals(canonical(b));
    }

    /** 用作路径索引键；Windows 文件系统大小写不敏感，键也按该语义归一化。 */
    public static String key(Path path) {
        String value = canonical(path).toString();
        return isWindows() ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
