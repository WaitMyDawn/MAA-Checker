package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 软件的持久化配置（{@code <软件目录>/config.properties}）。
 *
 * <p>目前两类内容：网络（{@code proxy.url}，见 {@link ProxyConfig}）与用户选择
 * （{@code game.dir} = 上次用的游戏目录，下次启动直接沿用，不用再找一遍）。
 */
public final class AppConfig {

    public static final String KEY_GAME_DIR = "game.dir";
    /** 自动修复的初始轮次上限（界面里可改，下次打开沿用） */
    public static final String KEY_FIX_ROUNDS = "fix.rounds";

    private AppConfig() {
    }

    public static String get(Path toolRoot, String key) {
        String v = readAll(toolRoot).get(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    public static void set(Path toolRoot, String key, String value) {
        Map<String, String> all = readAll(toolRoot);
        if (value == null || value.isBlank()) all.remove(key);
        else all.put(key, value);
        writeAll(toolRoot, all);
    }

    /** 读成有序 map（保留用户手写的注释顺序），UTF-8、容忍 BOM */
    public static Map<String, String> readAll(Path toolRoot) {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = toolRoot.resolve("config.properties");
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i <= 0) continue;
                out.put(line.substring(0, i).trim(), unescape(line.substring(i + 1).trim()));
            }
        } catch (IOException e) {
            System.err.println("⚠️ 读取 config.properties 失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 反解旧版写出的转义值。
     *
     * <p>历史原因：早期用 {@code java.util.Properties.store()} 写配置，它会把
     * {@code D:\Minecraft} 转义成 {@code D\:\\Minecraft}。现在改成明文 UTF-8 了，
     * 但用户机器上可能还留着老文件，必须能读回来（实测踩过：直接抛 InvalidPathException）。
     */
    static String unescape(String v) {
        if (v == null || !v.contains("\\")) return v;
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 1 >= v.length()) {
                sb.append(c);
                continue;
            }
            char n = v.charAt(++i);
            switch (n) {
                case 'u' -> {   // Unicode 转义：反斜杠 + u + 4 位十六进制
                    if (i + 4 < v.length()) {
                        try {
                            sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append(n);
                        }
                    } else {
                        sb.append(n);
                    }
                }
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                default -> sb.append(n);      // \: \\ \= 之类，去掉反斜杠
            }
        }
        return sb.toString();
    }

    /**
     * 直接写 UTF-8 文本（不用 Properties.store：它会把中文注释转成 \\uXXXX 转义，
     * 用户拿记事本打开就是一堆编码，没法手工改）。
     */
    public static void writeAll(Path toolRoot, Map<String, String> values) {
        Path f = toolRoot.resolve("config.properties");
        StringBuilder sb = new StringBuilder();
        sb.append("# MAA-Checker 配置（可由界面【设置代理…】或命令行修改）\n");
        sb.append("# proxy.url=http://127.0.0.1:7890   固定代理；留空则自动探测系统代理\n");
        sb.append("# game.dir=D:\\Minecraft\\minecraft\\.minecraft   上次使用的游戏目录\n\n");
        values.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("⚠️ 写入 config.properties 失败: " + e.getMessage());
        }
    }
}
