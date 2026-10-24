package yagen.waitmydawn.checker.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.PathSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

final class TransactionFiles {
    static final ObjectMapper JSON = new ObjectMapper();
    private TransactionFiles() { }

    static Path directory(Path parent, String name, boolean create) throws IOException {
        Path child = parent.resolve(name);
        if (create && !Files.exists(child, NOFOLLOW_LINKS)) Files.createDirectory(child);
        if (!PathSafety.isDirectory(child)) {
            throw new IOException("事务目录不是普通目录或存在链接：" + child);
        }
        return child;
    }

    static void regular(Path file) throws IOException {
        if (!PathSafety.isRegularFile(file)) {
            throw new IOException("事务文件不是普通文件或存在链接：" + file);
        }
    }

    static String hash(Path file) throws IOException {
        regular(file);
        try (var input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void copyVerified(Path source, Path target, String expected) throws IOException {
        regular(source);
        Files.copy(source, target, NOFOLLOW_LINKS);
        try (FileChannel channel = FileChannel.open(target, WRITE, NOFOLLOW_LINKS)) { channel.force(true); }
        if (!hash(target).equals(expected)) throw new IOException("准备文件的摘要与预期不符：" + target);
    }

    static <T> T read(Path path, Class<T> type) throws IOException {
        regular(path);
        return JSON.readValue(Files.readAllBytes(path), type);
    }

    /** 原子替换仅用于本工具的机器记录；实例目标发布使用不覆盖的移动。 */
    static void write(Path file, Object value) throws IOException {
        if (!PathSafety.isDirectory(file.getParent())) throw new IOException("台账目录已发生链接变化");
        if (Files.exists(file, NOFOLLOW_LINKS)) regular(file);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    static void filename(String filename) {
        if (filename == null || filename.isBlank() || filename.contains(":") || filename.contains("/")
                || filename.contains("\\") || filename.equals(".") || filename.equals("..")
                || filename.toLowerCase(java.util.Locale.ROOT).startsWith(".env")
                || filename.endsWith(".") || filename.endsWith(" ")) {
            throw new IllegalArgumentException("只允许单个普通文件名");
        }
    }
}
