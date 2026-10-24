package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceScanner;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 真实实例历史发现索引。
 *
 * <p>索引只保存实例位置与曾见过的会话 UUID，用于发现全局记录和识别实例移动；
 * 展示状态始终由实例内工作流文件重新读取。单条损坏记录不能遮蔽同一实例的其他会话。
 */
public final class WorkflowHistoryIndex {
    public enum Status { AVAILABLE, MOVED, UNAVAILABLE, CORRUPT }

    public record Entry(Status status, String instanceName, Path instanceDirectory,
                        UUID workflowId, long createdAt, RepairWorkflow.Phase phase,
                        String message, int rounds, String operationSummary, String report,
                        String problem, GameInstance instance) {
        public boolean canOpen() {
            return status == Status.AVAILABLE && instance != null && workflowId != null;
        }

        public boolean unfinished() {
            return status == Status.AVAILABLE && phase != RepairWorkflow.Phase.VERIFIED
                    && phase != RepairWorkflow.Phase.RESTORED;
        }
    }

    public record Snapshot(Path gameDirectory, List<Entry> entries, List<String> problems) {
        public Snapshot {
            entries = List.copyOf(entries);
            problems = List.copyOf(problems);
        }
    }

    public record KnownInstance(String instanceId, String name, String directory,
                                List<String> sessionIds, long lastSeen) {
        public KnownInstance {
            sessionIds = List.copyOf(sessionIds);
        }
    }

    public record Registry(int schema, List<KnownInstance> instances) {
        public Registry {
            instances = List.copyOf(instances);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "history-index-v1.json";

    private WorkflowHistoryIndex() {
    }

    public static void remember(Path toolRoot, Collection<GameInstance> instances) throws IOException {
        Path index = indexPath(toolRoot);
        Registry old;
        try {
            old = readRegistry(index);
        } catch (IOException corrupt) {
            quarantine(index);
            old = new Registry(1, List.of());
        }
        Map<String, KnownInstance> known = new LinkedHashMap<>();
        for (KnownInstance item : old.instances()) known.put(key(Path.of(item.directory())), item);
        long now = System.currentTimeMillis();
        for (GameInstance instance : instances) {
            String instanceId;
            try {
                instanceId = InstanceIdentity.of(instance).id();
            } catch (IOException e) {
                instanceId = "";
            }
            Path directory = instance.dir().toAbsolutePath().normalize();
            known.put(key(directory), new KnownInstance(instanceId, instance.name(), directory.toString(),
                    sessionIds(instance), now));
        }
        List<KnownInstance> sorted = known.values().stream()
                .sorted(Comparator.comparingLong(KnownInstance::lastSeen).reversed())
                .toList();
        writeRegistry(index, new Registry(1, sorted));
    }

    /**
     * 显式重新关联成功后移除失效旧路径，并把当前实例的会话集合写回索引。
     * 索引仍是派生数据；调用方已经完成工作流与事务迁移，不能依赖本方法保证业务成功。
     */
    public static void reassociate(Path toolRoot, Path oldDirectory, GameInstance instance) throws IOException {
        Path index = indexPath(toolRoot);
        Registry old;
        try {
            old = readRegistry(index);
        } catch (IOException corrupt) {
            quarantine(index);
            old = new Registry(1, List.of());
        }
        String oldKey = key(oldDirectory);
        Map<String, KnownInstance> known = new LinkedHashMap<>();
        for (KnownInstance item : old.instances()) {
            if (!key(Path.of(item.directory())).equals(oldKey)) known.put(key(Path.of(item.directory())), item);
        }
        String instanceId;
        try {
            instanceId = InstanceIdentity.of(instance).id();
        } catch (IOException e) {
            instanceId = "";
        }
        Path directory = instance.dir().toAbsolutePath().normalize();
        known.put(key(directory), new KnownInstance(instanceId, instance.name(), directory.toString(),
                sessionIds(instance), System.currentTimeMillis()));
        List<KnownInstance> sorted = known.values().stream()
                .sorted(Comparator.comparingLong(KnownInstance::lastSeen).reversed())
                .toList();
        writeRegistry(index, new Registry(1, sorted));
    }

    public static Snapshot load(Path toolRoot, Path gameDirectory) {
        List<String> problems = new ArrayList<>();
        List<KnownInstance> known = List.of();
        try {
            known = readRegistry(indexPath(toolRoot)).instances();
        } catch (IOException e) {
            problems.add("历史索引不可读：" + e.getMessage());
        }

        List<GameInstance> current = new ArrayList<>();
        if (gameDirectory != null && Files.isDirectory(gameDirectory)) {
            try {
                current.addAll(InstanceScanner.scanAll(gameDirectory));
            } catch (IOException e) {
                problems.add("无法扫描当前游戏目录：" + e.getMessage());
            }
        }

        Map<String, GameInstance> currentByPath = new LinkedHashMap<>();
        for (GameInstance instance : current) currentByPath.put(key(instance.dir()), instance);

        for (KnownInstance item : known) {
            Path directory = Path.of(item.directory()).toAbsolutePath().normalize();
            if (currentByPath.containsKey(key(directory))) continue;
            if (!Files.isDirectory(directory)) continue;
            Path versions = directory.getParent();
            Path gameRoot = versions == null ? null : versions.getParent();
            if (gameRoot == null) continue;
            GameInstance found = InstanceScanner.scanOne(gameRoot, directory);
            if (found != null) currentByPath.put(key(directory), found);
        }

        List<Entry> entries = new ArrayList<>();
        Map<UUID, String> sessionOwners = new LinkedHashMap<>();
        for (GameInstance instance : currentByPath.values()) {
            scanInstance(instance, entries, sessionOwners, problems);
        }

        for (KnownInstance item : known) {
            Path directory = Path.of(item.directory());
            if (Files.isDirectory(directory)) continue;
            List<String> movedTo = new ArrayList<>();
            for (String raw : item.sessionIds()) {
                try {
                    String owner = sessionOwners.get(UUID.fromString(raw));
                    if (owner != null && !movedTo.contains(owner)) movedTo.add(owner);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (!movedTo.isEmpty()) {
                entries.add(new Entry(Status.MOVED, item.name(), directory, null, item.lastSeen(),
                        null, "实例路径已变化", 0, "", "", "相同会话已在 " + String.join("、", movedTo)
                        + " 发现；请打开新实例的存储页检查恢复资料并显式重新关联", null));
            } else {
                entries.add(new Entry(Status.UNAVAILABLE, item.name(), directory, null, item.lastSeen(),
                        null, "实例路径不可用", 0, "", "", "目录不存在或已移动到其他游戏根", null));
            }
        }

        entries.sort(Comparator
                .comparing((Entry e) -> e.status() == Status.AVAILABLE && !e.unfinished() ? 1 : 0)
                .thenComparing(Comparator.comparingLong(Entry::createdAt).reversed()));
        return new Snapshot(gameDirectory, entries, problems);
    }

    private static void scanInstance(GameInstance instance, List<Entry> entries,
                                     Map<UUID, String> owners, List<String> problems) {
        Path workflows = instance.dir().resolve(".maa-checker").resolve("workflows");
        if (!Files.isDirectory(workflows, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> files = Files.list(workflows)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("policy.json") || name.contains(".tmp-")) continue;
                UUID id = null;
                if (name.endsWith(".json")) {
                    try {
                        id = UUID.fromString(name.substring(0, name.length() - 5));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (id != null) {
                    owners.put(id, instance.name() + "（" + instance.dir() + "）");
                }
                try {
                    RepairWorkflow.State state = RepairWorkflow.readHistoryEntry(instance, file);
                    entries.add(toEntry(instance, state));
                } catch (Exception e) {
                    long modified = 0;
                    try {
                        modified = Files.getLastModifiedTime(file).toMillis();
                    } catch (IOException ignored) {
                    }
                    entries.add(new Entry(Status.CORRUPT, instance.name(), instance.dir(), id, modified,
                            null, "会话记录损坏", 0, "", "", file.getFileName() + "：" + e.getMessage(), instance));
                    problems.add(instance.name() + " / " + file.getFileName() + " 读取失败：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            entries.add(new Entry(Status.CORRUPT, instance.name(), instance.dir(), null,
                    System.currentTimeMillis(), null, "无法读取会话目录", 0, "", "",
                    workflows + "：" + e.getMessage(), instance));
            problems.add(instance.name() + " 会话目录读取失败：" + e.getMessage());
        }
    }

    private static Entry toEntry(GameInstance instance, RepairWorkflow.State state) {
        int rounds = state.rounds().size();
        Map<ActionRecord.Kind, Integer> counts = new EnumMap<>(ActionRecord.Kind.class);
        for (RepairWorkflow.Round round : state.rounds()) {
            for (RepairWorkflow.Event event : round.events()) {
                ActionRecord action = event.committed();
                if (action == null || action.kind() == ActionRecord.Kind.CACHE_LIBRARY
                        || action.kind() == ActionRecord.Kind.INSTANCE_LIBRARY) continue;
                counts.merge(action.kind(), 1, Integer::sum);
            }
        }
        String summary = actionSummary(counts);
        String report = state.rounds().isEmpty() ? "" : Objects.toString(state.round().report(), "");
        return new Entry(Status.AVAILABLE, instance.name(), instance.dir(), state.id(), state.createdAt(),
                state.phase(), state.message(), rounds, summary, report, "", instance);
    }

    private static String actionSummary(Map<ActionRecord.Kind, Integer> counts) {
        if (counts.isEmpty()) return "仅诊断，无文件动作";
        List<String> parts = new ArrayList<>();
        int accounted = 0;
        append(parts, counts, ActionRecord.Kind.INSTALL_MOD, "补装");
        accounted += counts.getOrDefault(ActionRecord.Kind.INSTALL_MOD, 0);
        append(parts, counts, ActionRecord.Kind.REMOVE_MOD, "停用");
        accounted += counts.getOrDefault(ActionRecord.Kind.REMOVE_MOD, 0);
        append(parts, counts, ActionRecord.Kind.REPLACE_VERSION, "版本调整");
        accounted += counts.getOrDefault(ActionRecord.Kind.REPLACE_VERSION, 0);
        append(parts, counts, ActionRecord.Kind.DISABLE_PACK_MARKER, "停用整合包补全");
        accounted += counts.getOrDefault(ActionRecord.Kind.DISABLE_PACK_MARKER, 0);
        int other = counts.values().stream().mapToInt(Integer::intValue).sum() - accounted;
        if (other > 0) parts.add("其他 " + other);
        return String.join(" · ", parts);
    }

    private static void append(List<String> parts, Map<ActionRecord.Kind, Integer> counts,
                               ActionRecord.Kind kind, String label) {
        int n = counts.getOrDefault(kind, 0);
        if (n > 0) parts.add(label + " " + n);
    }

    private static List<String> sessionIds(GameInstance instance) {
        Path workflows = instance.dir().resolve(".maa-checker").resolve("workflows");
        if (!Files.isDirectory(workflows, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> files = Files.list(workflows)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !name.equals("policy.json"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .filter(raw -> {
                        try {
                            UUID.fromString(raw);
                            return true;
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Registry readRegistry(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Registry(1, List.of());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("索引不是普通文件");
        Registry registry = JSON.readValue(Files.readAllBytes(file), Registry.class);
        if (registry.schema() != 1) throw new IOException("不支持的索引版本：" + registry.schema());
        return registry;
    }

    private static void writeRegistry(Path file, Registry registry) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(INDEX + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(registry),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void quarantine(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        Path target = file.resolveSibling(INDEX + ".corrupt-" + System.currentTimeMillis());
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path indexPath(Path toolRoot) {
        return toolRoot.toAbsolutePath().normalize().resolve("state").resolve(INDEX);
    }

    private static String key(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? value.toLowerCase(Locale.ROOT) : value;
    }
}
