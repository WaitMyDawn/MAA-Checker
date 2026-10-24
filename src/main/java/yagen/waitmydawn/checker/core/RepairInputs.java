package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 诊断输入快照；只绑定版本 JSON、实例 mods 顶层 JAR 和整合包标记。
 * 世界、普通配置、账号资料、资源文件和共享 libraries 不在轮前快照范围内。
 */
public record RepairInputs(String versionFile, String modsDirectory, Map<String, String> hashes) {
    public RepairInputs { hashes = Map.copyOf(hashes); }

    public static RepairInputs capture(GameInstance instance) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("version", hash(instance.versionJson()));
        Path mods = instance.modsDir().toAbsolutePath().normalize();
        if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            mods = mods.toRealPath();
            try (var paths = Files.list(mods)) {
                for (Path file : paths.toList()) {
                    if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                    hashes.put("mod/" + file.getFileName(), hash(file));
                }
            }
        }
        Path marker = instance.dir().resolve(ModpackMarker.FILE);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) hashes.put("marker", hash(marker));
        return new RepairInputs(instance.versionJson().toRealPath().toString(), mods.toString(), hashes);
    }

    public static String hash(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        for (Path part : absolute) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".env") || name.equals("secrets") || name.equals("credentials")) {
                throw new IOException("修复输入不能来自敏感配置路径");
            }
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().equals(absolute)) {
            throw new IOException("修复输入必须为普通文件：" + absolute.getFileName());
        }
        try (var input = Files.newInputStream(absolute, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
