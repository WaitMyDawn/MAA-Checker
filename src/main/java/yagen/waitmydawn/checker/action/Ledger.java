package yagen.waitmydawn.checker.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮改动的台账：<b>既给人看，也给还原用</b>。
 *
 * <p>每条记录都带"类别 + 报错原文"（用户明确要求）：日志里必须能读到
 * "因为【Mixin 冲突】摘除了 X，原文：……"，而不是只写一句"已移除 X"。
 */
public final class Ledger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param data 还原所需的机器可读字段：REMOVE_MOD={movedTo,sha256,size}；
     *             INSTALL_DEP={installedPath,url,projectSlug,versionNumber,sha1}；
     *             UPGRADE_LOADER={from,to,jsonBackup,launcherProfilesBackup,installerJar,createdVersionDir}
     */
    public record Entry(String kind, String modId, String jarFile, String category, String reason,
                        List<String> errorText, Map<String, String> data) {
    }

    private final Path file;
    private final String baseName;
    private int round;
    private String instanceName;
    private List<String> before = new ArrayList<>();
    private List<String> after = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    private Ledger(Path file) {
        this.file = file;
        String n = file.getFileName().toString();
        this.baseName = n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }

    /**
     * @param session 会话标识（一般用启动时间戳）。<b>必须带会话</b>：早期只用 {@code round-N.json}，
     *                结果是"下一次会话的第 1 轮"把上一次会话的第 1 轮台账覆盖掉，
     *                上一次的加载器升级就再也还原不回来了（真实踩过）。
     */
    public static Ledger create(Path stateDir, String session, int round, String instanceName)
            throws IOException {
        Files.createDirectories(stateDir);
        String prefix = (session == null || session.isBlank()) ? "" : session + "-";
        Ledger l = new Ledger(stateDir.resolve(prefix + "round-" + round + ".json"));
        l.round = round;
        l.instanceName = instanceName;
        return l;
    }

    @SuppressWarnings("unchecked")
    public static Ledger load(Path file) throws IOException {
        Ledger l = new Ledger(file);
        Map<String, Object> root = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {
                });
        l.round = ((Number) root.getOrDefault("round", 0)).intValue();
        l.instanceName = String.valueOf(root.getOrDefault("instance", ""));
        l.before = (List<String>) root.getOrDefault("before", List.of());
        l.after = (List<String>) root.getOrDefault("after", List.of());
        for (Map<String, Object> m : (List<Map<String, Object>>) root.getOrDefault("actions",
                List.of())) {
            l.entries.add(new Entry((String) m.get("kind"), (String) m.get("modId"),
                    (String) m.get("jarFile"), (String) m.get("category"), (String) m.get("reason"),
                    (List<String>) m.getOrDefault("errorText", List.of()),
                    (Map<String, String>) m.getOrDefault("data", Map.of())));
        }
        return l;
    }

    public void setBefore(List<String> before) {
        this.before = new ArrayList<>(before);
    }

    public void setAfter(List<String> after) {
        this.after = new ArrayList<>(after);
    }

    public void add(Entry e) {
        entries.add(e);
    }

    public void add(String kind, String modId, String jarFile, String category, String reason,
                    List<String> errorText, Map<String, String> data) {
        entries.add(new Entry(kind, modId, jarFile, category, reason,
                errorText == null ? List.of() : List.copyOf(errorText),
                data == null ? Map.of() : new LinkedHashMap<>(data)));
        // 每加一条就落盘：工具被 Ctrl+C / 断电打断时，已发生的文件改动必须仍然可还原。
        // （第一版是整轮结束才写台账，结果一次强杀就让"升级了加载器却没有记录"）
        try {
            save();
        } catch (IOException e) {
            System.err.println("⚠️ 台账落盘失败（继续执行，但请留意还原可能不完整）: " + e.getMessage());
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int round() {
        return round;
    }

    public Path file() {
        return file;
    }

    public void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("round", round);
        root.put("instance", instanceName);
        root.put("before", before);
        root.put("after", after);
        root.put("actions", entries);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        Files.writeString(file.resolveSibling(baseName + ".md"), markdown(), StandardCharsets.UTF_8);
    }

    /** 人读版台账：类别 + 报错原文 + 还原所需路径，一行不少 */
    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 第 ").append(round).append(" 轮改动台账 —— ").append(instanceName).append('\n').append('\n');
        if (!before.isEmpty()) sb.append("改动前: ").append(String.join("；", before)).append('\n').append('\n');
        for (Entry e : entries) {
            sb.append("## ").append(kindName(e.kind())).append("：").append(e.modId()).append('\n').append('\n');
            sb.append("- 问题类别: ").append(e.category()).append('\n');
            sb.append("- 原因: ").append(e.reason()).append('\n');
            if (e.jarFile() != null) sb.append("- 相关文件: ").append(e.jarFile()).append('\n');
            for (var kv : e.data().entrySet()) {
                sb.append("- ").append(kv.getKey()).append(": ").append(kv.getValue()).append('\n');
            }
            if (!e.errorText().isEmpty()) {
                sb.append("- 报错原文:\n\n```\n");
                e.errorText().forEach(l -> sb.append(l).append('\n'));
                sb.append("```\n");
            }
            sb.append('\n');
        }
        if (!after.isEmpty()) sb.append("改动后: ").append(String.join("；", after)).append('\n');
        return sb.toString();
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "UPGRADE_LOADER" -> "动作A 升级加载器";
            case "INSTALL_DEP" -> "动作B 补装前置";
            case "REMOVE_MOD" -> "动作C 摘除模组";
            default -> kind;
        };
    }
}
