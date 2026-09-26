package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java 探测：本工具自身与游戏各需要一份 Java，且版本要求不同（1.21.1 要 21，1.20.1 要 17，1.16 要 8）。
 *
 * 探测顺序（找不到就明确报错，不静默用错版本）：
 * 1) 软件目录下的 jre/（将来内置 JRE 时优先）
 * 2) JAVA_HOME
 * 3) PATH 里的 java
 * 4) HMCL 自带运行时 %APPDATA%/.hmcl/java/windows-x86_64/<runtime>/bin/java.exe
 * 5) 常见安装路径（Microsoft JDK / Oracle / Adoptium / Zulu）
 *
 * 注意：java -version 输出在 stderr 且退出码为 0，必须两个流都看。
 */
public final class JavaLocator {

    public record JavaHome(Path javaExe, int major, String versionLine) {
    }

    private static final Pattern VERSION = Pattern.compile("version \"(\\d+)");

    private JavaLocator() {
    }

    /** 找到满足 major 需求的最低候选（优先顺序见类注释） */
    public static JavaHome find(int requiredMajor, Path toolRoot) {
        for (Path candidate : candidates(toolRoot)) {
            JavaHome home = probe(candidate);
            if (home != null && home.major() >= requiredMajor) return home;
        }
        return null;
    }

    /** 列出所有可用 Java（UI 里给用户手动选） */
    public static List<JavaHome> listAll(Path toolRoot) {
        List<JavaHome> out = new ArrayList<>();
        for (Path candidate : candidates(toolRoot)) {
            JavaHome home = probe(candidate);
            if (home != null && out.stream().noneMatch(h -> h.javaExe().equals(home.javaExe()))) out.add(home);
        }
        return out;
    }

    private static List<Path> candidates(Path toolRoot) {
        List<Path> out = new ArrayList<>();
        String exe = isWindows() ? "java.exe" : "java";
        if (toolRoot != null) {
            out.add(toolRoot.resolve("jre/bin").resolve(exe));
            // 自带运行时的发行版（jpackage app-image）里就在这里；顺便兜住"机器上没装 Java"的用户
            out.add(toolRoot.resolve("runtime/bin").resolve(exe));
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) out.add(Paths.get(javaHome, "bin", exe));
        // PATH 里的 java（不解析绝对路径，交给 ProcessBuilder 查 PATH）
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path hmcl = Paths.get(appData, ".hmcl", "java", "windows-x86_64");
            if (Files.isDirectory(hmcl)) {
                try (Stream<Path> runtimes = Files.list(hmcl)) {
                    for (Path r : runtimes.sorted().toList()) out.add(r.resolve("bin").resolve(exe));
                } catch (IOException ignored) {
                }
            }
        }
        for (String base : new String[]{"C:/Program Files/Microsoft", "C:/Program Files/Java",
                "C:/Program Files/Eclipse Adoptium", "C:/Program Files/Zulu"}) {
            Path dir = Paths.get(base);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> kids = Files.list(dir)) {
                for (Path k : kids.sorted().toList()) out.add(k.resolve("bin").resolve(exe));
            } catch (IOException ignored) {
            }
        }
        out.add(Paths.get("java"));   // 最后兜底：交给系统 PATH
        return out;
    }

    private static JavaHome probe(Path javaExe) {
        try {
            if (!javaExe.toString().equals("java") && !Files.isRegularFile(javaExe)) return null;
        } catch (Exception e) {
            return null;
        }
        try {
            Process p = new ProcessBuilder(javaExe.toString(), "-version").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            Matcher m = VERSION.matcher(text);
            if (!m.find()) return null;
            int major = Integer.parseInt(m.group(1));
            // 老版本串是 1.8.0_421 / 1.7.0 这类：第一位恒为 1，真正的版本在第二位
            if (major == 1) {
                Matcher legacy = Pattern.compile("version \"1\\.(\\d+)").matcher(text);
                if (legacy.find()) major = Integer.parseInt(legacy.group(1));
            }
            return new JavaHome(javaExe, major, text.lines().findFirst().orElse("").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
