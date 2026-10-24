package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 轮次边界的可恢复内容。世界、存档、账号和游戏日志不属于修复快照。 */
public record RoundSnapshot(RepairInputs inputs, String minecraft, String loader, String loaderVersion, List<Mod> mods) {
    public record Mod(String filename, String hash, Map<String, String> versions) {
        public Mod { versions = Map.copyOf(versions); }
    }
    public RoundSnapshot { mods = List.copyOf(mods); }

    public static RoundSnapshot capture(GameInstance instance) throws IOException {
        InstanceStorage.requireIsolatedMods(instance);
        RepairInputs before = RepairInputs.capture(instance);
        var index = ModJarIndex.scanMeta(instance.modsDir());
        List<Mod> mods = new ArrayList<>();
        for (var entry : new TreeMap<>(before.hashes()).entrySet()) {
            String key = entry.getKey();
            Path source = source(instance, key);
            Path object = object(instance, key, entry.getValue(), true);
            if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) {
                Path temp = object.resolveSibling(UUID.randomUUID() + ".tmp");
                try {
                    TransactionFiles.copyVerified(source, temp, entry.getValue());
                    Files.move(temp, object);
                } finally { Files.deleteIfExists(temp); }
            } else if (!RepairInputs.hash(object).equals(entry.getValue())) throw new IOException("轮次快照对象已损坏");
            if (key.startsWith("mod/")) {
                String filename = key.substring(4);
                Map<String, String> versions = new TreeMap<>();
                index.jarOf().forEach((id, jar) -> { if (filename.equals(jar)) versions.put(id, Objects.toString(index.versionOf(id), "?")); });
                mods.add(new Mod(filename, entry.getValue(), versions));
            }
        }
        if (!RepairInputs.capture(instance).equals(before)) throw new IOException("生成快照期间实例发生变化，请关闭游戏后重试");
        return new RoundSnapshot(before, instance.mcVersion(), instance.loader(), instance.loaderVersion(), mods);
    }

    public Path candidate(GameInstance instance, String key) throws IOException {
        String hash = inputs.hashes().get(key);
        if (hash == null) return null;
        Path object = object(instance, key, hash, false);
        if (!RepairInputs.hash(object).equals(hash)) throw new IOException("轮次快照对象缺失或损坏");
        return object;
    }

    public static List<String> differences(RepairInputs current, RepairInputs desired) {
        Set<String> keys = new TreeSet<>(current.hashes().keySet());
        keys.addAll(desired.hashes().keySet());
        return keys.stream().filter(k -> !Objects.equals(current.hashes().get(k), desired.hashes().get(k))).toList();
    }

    private static Path source(GameInstance i, String key) throws IOException {
        if (key.equals("version")) return i.versionJson();
        if (key.equals("marker")) return i.dir().resolve("modpack.cfg");
        if (!key.startsWith("mod/")) throw new IOException("未知快照目标");
        String filename = key.substring(4);
        TransactionFiles.filename(filename);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IOException("快照仅允许模组 JAR");
        return i.modsDir().resolve(filename);
    }

    private static Path object(GameInstance i, String key, String hash, boolean create) throws IOException {
        source(i, key);
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("快照摘要无效");
        Path objects = InstanceStorage.directory(InstanceStorage.root(i, create), "objects", create);
        return objects.resolve(hash + (key.equals("version") ? ".json" : key.equals("marker") ? ".cfg" : ".jar"));
    }

    /** 与事务日志使用相同的原子持久化协议，避免 UI 或任务层直接写半份会话。 */
    public static void writeJournal(GameInstance instance, String name, Object value) throws IOException {
        TransactionFiles.filename(name);
        Path directory = InstanceStorage.directory(InstanceStorage.root(instance, true), "workflows", true);
        TransactionFiles.write(directory.resolve(name), value);
    }

    public static <T> T readJournal(GameInstance instance, String name, Class<T> type) throws IOException {
        TransactionFiles.filename(name);
        Path directory = InstanceStorage.directory(InstanceStorage.root(instance, false), "workflows", false);
        return TransactionFiles.read(directory.resolve(name), type);
    }
}
