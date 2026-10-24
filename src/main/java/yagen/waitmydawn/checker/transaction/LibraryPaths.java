package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.GameInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 共享库目标只允许绑定的 libraries 根下的普通 Maven JAR 路径。 */
public final class LibraryPaths {
    private LibraryPaths() { }

    public static Path root(GameInstance instance) throws IOException {
        Path root = instance.gameRoot().toRealPath().resolve("libraries");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) TransactionFiles.directory(root.getParent(), "libraries", false);
        return root;
    }

    public static void relative(String path) throws IOException {
        if (path == null || path.contains("\\") || path.startsWith("/") || !path.endsWith(".jar")) throw new IOException("非法共享库路径");
        for (String segment : path.split("/", -1)) {
            try { TransactionFiles.filename(segment); }
            catch (IllegalArgumentException e) { throw new IOException("非法共享库路径", e); }
            if (segment.equalsIgnoreCase("secrets") || segment.equalsIgnoreCase("credentials")) throw new IOException("共享库路径包含受保护目录");
        }
    }

    public static String key(GameInstance instance, String relative) throws IOException {
        relative(relative);
        String root = root(instance).toString();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) root = root.toLowerCase(Locale.ROOT);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(root.getBytes(StandardCharsets.UTF_8))) + "/" + relative; }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void validateKey(String key) throws IOException {
        if (key == null || key.length() < 66 || !key.substring(0, 65).matches("[a-f0-9]{64}/")) throw new IOException("共享库根绑定无效");
        relative(key.substring(65));
    }

    static Path target(GameInstance instance, String key, boolean create) throws IOException {
        validateKey(key);
        String relative = key.substring(65);
        if (!key(instance, relative).equals(key)) throw new IOException("共享库根目录与会话绑定不一致");
        Path path = root(instance);
        if (create) path = TransactionFiles.directory(path.getParent(), "libraries", true);
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            Path child = path.resolve(parts[i]);
            if (create || Files.exists(child, LinkOption.NOFOLLOW_LINKS)) path = TransactionFiles.directory(path, parts[i], create);
            else path = child;
        }
        return path.resolve(parts[parts.length - 1]);
    }

    public static Path target(GameInstance instance, String relative) throws IOException {
        return target(instance, key(instance, relative), false);
    }

    /** 解析事务台账中已绑定共享库根的 key，只读且不创建目录。 */
    public static Path resolveKey(GameInstance instance, String key) throws IOException {
        return target(instance, key, false);
    }
}
