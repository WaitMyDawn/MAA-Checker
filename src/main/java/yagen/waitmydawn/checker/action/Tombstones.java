package yagen.waitmydawn.checker.action;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 墓碑集合：<b>凡是被判定要移除的模组，之后永远不允许再被装回来</b>。
 *
 * <p>这是防"增删循环"的唯一硬机制（用户明确要求）：没有它就会出现
 * A 需要 B → 装 B → B 导致 C 崩 → 删 B → 下一轮又因为 A 需要 B 再装 B……的死循环。
 * 有了墓碑，第二次遇到"缺 B"时系统只能选另一条路：把需要 B 的 A 也摘掉。
 */
public final class Tombstones {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @param modId 被禁的模组；@param reason 为什么禁；@param round 第几轮判定的 */
    public record Entry(String modId, String jarFile, String reason, int round) {
    }

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private Tombstones(Path file) {
        this.file = file;
    }

    public static Tombstones load(Path stateDir) {
        Tombstones t = new Tombstones(stateDir.resolve("tombstones.json"));
        if (!Files.isRegularFile(t.file)) return t;
        try {
            JsonNode arr = MAPPER.readTree(Files.readString(t.file, StandardCharsets.UTF_8));
            for (JsonNode n : arr.path("tombstones")) {
                Entry e = new Entry(n.path("modId").asText(), n.path("jarFile").asText(null),
                        n.path("reason").asText(""), n.path("round").asInt(0));
                t.entries.put(e.modId(), e);
            }
        } catch (Exception e) {
            // 墓碑文件坏了要吵出来：静默当成"没有墓碑"会导致死循环复发
            System.err.println("⚠️ 墓碑文件损坏，已忽略: " + t.file + "（" + e.getMessage() + "）");
        }
        return t;
    }

    public boolean contains(String modId) {
        return modId != null && entries.containsKey(modId);
    }

    public void add(String modId, String jarFile, String reason, int round) {
        if (modId == null || modId.isBlank()) return;
        entries.put(modId, new Entry(modId, jarFile, reason, round));
    }

    /** 还原时把本轮加的墓碑撤掉（还原到检验前的状态，墓碑也必须一起还原） */
    public void removeFromRound(int round) {
        entries.values().removeIf(e -> e.round() == round);
    }

    public List<Entry> all() {
        return List.copyOf(entries.values());
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "被判定移除的模组，后续轮次永不允许再装回（防增删循环）");
        root.put("tombstones", new ArrayList<>(entries.values()));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }
}
