package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.GameInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 身份绑定规范实例、模组目录与版本文件，不能仅按实例显示名区分历史。 */
public record InstanceIdentity(String id, String directory, String modsDirectory, String versionFile) {
    public static InstanceIdentity of(GameInstance instance) throws IOException {
        Path directory = instance.dir().toRealPath();
        Path mods = instance.modsDir().toRealPath();
        Path requested = instance.versionJson().toAbsolutePath().normalize();
        if (!requested.getParent().toRealPath().equals(directory) || !requested.getFileName().toString().endsWith(".json")) {
            throw new IOException("版本元数据必须位于实例根目录");
        }
        Path version = directory.resolve(requested.getFileName());
        // 版本替换在撤下原文件后可能中断；身份仍由受限路径确定，恢复不能依赖目标存在。
        if (java.nio.file.Files.exists(version, java.nio.file.LinkOption.NOFOLLOW_LINKS)) TransactionFiles.regular(version);
        String key = directory + "\n" + mods + "\n" + version;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) key = key.toLowerCase(Locale.ROOT);
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            return new InstanceIdentity(id, directory.toString(), mods.toString(), version.toString());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
