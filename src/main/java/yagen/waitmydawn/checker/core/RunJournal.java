package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 一轮检验的日志出口：同时写控制台与 {@code <软件目录>/logs/<实例名>/<时间戳>/journal.log}。
 *
 * <p>不需要联网、不回传任何数据——用户在软件目录里就能看到全过程的每一行。
 */
public final class RunJournal {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path runDir;
    private final StringBuilder buffer = new StringBuilder();
    /** 日志出口：CLI 是 System.out，GUI 是窗口里的日志框（同一个引擎，两种界面） */
    private final Consumer<String> sink;

    private RunJournal(Path runDir, Consumer<String> sink) {
        this.runDir = runDir;
        this.sink = sink;
    }

    /** 建本轮目录：logs/&lt;实例名&gt;/&lt;yyyyMMdd-HHmmss&gt;/ */
    public static RunJournal create(Path toolRoot, String instanceName, Consumer<String> sink)
            throws IOException {
        Path dir = toolRoot.resolve("logs").resolve(safeName(instanceName))
                .resolve(STAMP.format(LocalDateTime.now()));
        Files.createDirectories(dir);
        return new RunJournal(dir, sink == null ? m -> { } : sink);
    }

    public Path runDir() {
        return runDir;
    }

    /** 写一行：控制台 + 缓冲（缓冲在 close 时落盘，保证即使异常退出也有日志） */
    public void log(String msg) {
        sink.accept(msg);
        buffer.append(msg).append(System.lineSeparator());
    }

    public void close() {
        try {
            Files.writeString(runDir.resolve("journal.log"), buffer.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            sink.accept("⚠️ journal.log 写入失败: " + e.getMessage());
        }
    }

    /** 实例名可能带空格与中文（实测有"铁魔法冒险之旅"这种），只清掉文件名非法字符 */
    public static String safeName(String name) {
        String s = name == null || name.isBlank() ? "unknown" : name;
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
    }
}
