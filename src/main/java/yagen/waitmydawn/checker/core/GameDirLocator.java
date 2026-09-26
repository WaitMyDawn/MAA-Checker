package yagen.waitmydawn.checker.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 自动找 {@code .minecraft}：用户第一次打开界面时不该先让他满硬盘找目录。
 *
 * <p>顺序：上次记住的 → 常见启动器/系统位置 → 各盘符下的常见游戏目录。
 * 只有"里面确实有 versions/ 且至少有一个实例"的目录才算命中，避免误选空目录。
 */
public final class GameDirLocator {

    private GameDirLocator() {
    }

    /** @return 找到了就返回；都没找到返回 {@code %APPDATA%/.minecraft}（让用户自己改） */
    public static Path detect(Path toolRoot) {
        String saved = AppConfig.get(toolRoot, AppConfig.KEY_GAME_DIR);
        Path savedPath = toPath(saved);
        if (savedPath != null && hasInstances(savedPath)) return savedPath;
        for (Path p : candidates()) {
            if (hasInstances(p)) return p;
        }
        return savedPath != null ? savedPath : defaultGameDir();
    }

    /** 配置里存的值可能被用户手改坏（或来自老版本的转义格式），解析不出来就当没有 */
    static Path toPath(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Paths.get(s);
        } catch (Exception e) {
            System.err.println("⚠️ 配置里的 game.dir 不是合法路径，已忽略: " + s);
            return null;
        }
    }

    public static Path defaultGameDir() {
        String appData = System.getenv("APPDATA");
        return appData == null ? Paths.get(".minecraft") : Paths.get(appData, ".minecraft");
    }

    static List<Path> candidates() {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        String appData = System.getenv("APPDATA");
        String home = System.getProperty("user.home");
        if (appData != null) {
            out.add(Paths.get(appData, ".minecraft"));
            out.add(Paths.get(appData, ".hmcl", ".minecraft"));
            out.add(Paths.get(appData, ".hmcl"));
        }
        if (home != null) {
            out.add(Paths.get(home, ".minecraft"));
            out.add(Paths.get(home, "AppData", "Roaming", ".minecraft"));
        }
        // 各盘符下的常见放法（HMCL/PCL 用户很常用 D:\Minecraft\...）
        for (char d = 'C'; d <= 'H'; d++) {
            String root = d + ":\\";
            out.add(Paths.get(root, "Minecraft", "minecraft", ".minecraft"));
            out.add(Paths.get(root, "Minecraft", ".minecraft"));
            out.add(Paths.get(root, "Games", ".minecraft"));
            out.add(Paths.get(root, ".minecraft"));
        }
        List<Path> list = new ArrayList<>();
        for (Path p : out) {
            try {
                if (Files.isDirectory(p)) list.add(p.toAbsolutePath());
            } catch (Exception ignored) {
                // 不存在的盘/无权限直接跳过
            }
        }
        return list;
    }

    /** 目录里是否有可识别的实例（这是"选对了"的唯一判据） */
    public static boolean hasInstances(Path gameDir) {
        try {
            if (gameDir == null || !Files.isDirectory(gameDir.resolve("versions"))) return false;
            return !InstanceScanner.scanAll(gameDir).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
