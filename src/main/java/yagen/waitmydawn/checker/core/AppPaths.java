package yagen.waitmydawn.checker.core;

import java.nio.file.Path;

/** 应用运行路径。桌面用户数据与安装目录分离，升级或卸载程序时不触碰恢复资料。 */
public final class AppPaths {
    private static final String USER_DATA_PROPERTY = "maachecker.userData";

    private AppPaths() {
    }

    public static Path desktopDataRoot() {
        String override = System.getProperty(USER_DATA_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local).resolve("MAA-Checker").toAbsolutePath().normalize();
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("无法确定桌面用户数据目录");
        }
        return Path.of(home).resolve(".maa-checker").toAbsolutePath().normalize();
    }
}
