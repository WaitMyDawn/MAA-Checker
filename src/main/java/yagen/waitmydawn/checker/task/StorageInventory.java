package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceStorage;
import yagen.waitmydawn.checker.core.PathSafety;
import yagen.waitmydawn.checker.core.RunJournal;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.LibraryPaths;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 只读盘点本工具产生的存储。
 *
 * <p>统计只读取文件元数据和恢复台账；不读取用户存档、账号或配置正文。
 * “可回收候选”仍需 S4-02 的引用复核与显式确认，不能据此直接删除。
 */
public final class StorageInventory {
    public enum State { CLEANABLE, CANDIDATE, PROTECTED, UNKNOWN }

    public record Category(String id, String title, String detail, long logicalBytes, long diskBytes,
                           long cleanableBytes, long candidateBytes, long protectedBytes,
                           long unknownBytes, int files) {
        public State state() {
            if (unknownBytes > 0) return State.UNKNOWN;
            if (candidateBytes > 0) return State.CANDIDATE;
            if (cleanableBytes > 0) return State.CLEANABLE;
            return State.PROTECTED;
        }
    }

    public record Snapshot(GameInstance instance, List<Category> categories, long logicalBytes,
                           long diskBytes, long cleanableBytes, long candidateBytes,
                           long protectedBytes, long unknownBytes, List<String> issues) {
        public Snapshot {
            categories = List.copyOf(categories);
            issues = List.copyOf(issues);
        }
    }

    private record Item(Path path, long size) { }
    private record FileSet(List<Item> cleanable, List<Item> candidate, List<Item> protectedFiles,
                         List<Item> unknown) {
        static FileSet empty() {
            return new FileSet(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }
    private record SessionData(List<RepairWorkflow.State> sessions, Set<String> objectHashes,
                               Set<String> runtimes, Set<Path> reports, boolean complete) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private StorageInventory() {
    }

    public static TaskService.Work<Snapshot> scan(Path toolRoot, GameInstance instance) {
        return context -> {
            context.phase(TaskSnapshot.Phase.SCANNING, "正在统计实例恢复数据与工具存储");
            return TaskResult.success(inspect(toolRoot, instance, context::checkpoint), "存储占用统计完成");
        };
    }

    public static Snapshot inspect(Path toolRoot, GameInstance instance) throws IOException {
        return inspect(toolRoot, instance, () -> { });
    }

    static Snapshot inspect(Path toolRoot, GameInstance instance, Runnable checkpoint) throws IOException {
        List<Category> categories = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Path storage = InstanceStorage.root(instance, false);
        SessionData sessions = readSessions(instance, issues, checkpoint);

        Path preparation = storage.resolve("preparation");
        FileSet preparationFiles = FileSet.empty();
        try {
            PreparationCleanup.Preview preview = PreparationCleanup.inspect(instance);
            for (PreparationCleanup.File file : preview.files()) {
                preparationFiles.cleanable.add(new Item(preparation.resolve(file.relative()), file.size()));
            }
        } catch (Exception e) {
            preparationFiles.unknown.addAll(scanTree(preparation, issues, checkpoint));
            issues.add("准备缓存包含未知内容，未计入可立即清理：" + e.getMessage());
        }
        categories.add(category("preparation", "准备缓存",
                "下载候选与受控 installer 准备目录；已有安全检查，可立即清理", preparationFiles, 0));

        Path objects = storage.resolve("objects");
        FileSet objectFiles = FileSet.empty();
        Map<String, Item> objectByHash = new LinkedHashMap<>();
        for (Item item : scanTree(objects, issues, checkpoint)) {
            String name = item.path().getFileName().toString();
            int dot = name.indexOf('.');
            String hash = dot > 0 ? name.substring(0, dot) : name;
            objectByHash.put(hash, item);
            if (sensitive(item.path())) objectFiles.unknown.add(item);
            else if (sessions.objectHashes().contains(hash)) objectFiles.protectedFiles.add(item);
            else if (sessions.complete()) objectFiles.candidate.add(item);
            else objectFiles.unknown.add(item);
        }
        long logicalSnapshots = sessions.sessions().stream()
                .flatMap(state -> state.rounds().stream())
                .flatMap(round -> round.before().inputs().hashes().values().stream())
                .mapToLong(hash -> objectByHash.containsKey(hash) ? objectByHash.get(hash).size() : 0)
                .sum();
        categories.add(category("snapshots", "恢复点对象",
                "按 SHA-256 去重保存的模组、版本 JSON 与 HMCL 标记；被轮次引用时受保护", objectFiles, logicalSnapshots));

        categories.add(category("workflows", "工作流记录",
                "轮次初态、授权与验证时间线；删除会使对应恢复点失去说明",
                all(storage.resolve("workflows"), State.PROTECTED, issues, checkpoint), 0));

        FileSet transactions = FileSet.empty();
        Path transactionStore = null;
        try {
            InstanceIdentity identity = InstanceIdentity.of(instance);
            transactionStore = toolRoot.resolve("state").resolve("transactions-v1").resolve(identity.id());
            transactions.protectedFiles.addAll(scanTree(transactionStore, issues, checkpoint));
            TransactionSession.history(toolRoot.resolve("state"), instance);
        } catch (Exception e) {
            if (transactionStore != null) {
                transactions.unknown.addAll(scanTree(transactionStore, issues, checkpoint));
                transactions.protectedFiles.removeAll(transactions.unknown);
            }
            issues.add("事务台账无法完整校验，备份标记为未知：" + e.getMessage());
        }
        categories.add(category("transactions", "事务备份",
                "逐文件修改前原件与恢复状态；不能脱离台账单独清理", transactions, 0));

        FileSet runtime = FileSet.empty();
        for (Item item : scanTree(storage.resolve("runtime"), issues, checkpoint)) {
            Path relative = storage.resolve("runtime").relativize(item.path());
            String runtimeId = relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
            if (sensitive(item.path())) runtime.unknown.add(item);
            else if (sessions.runtimes().contains(runtimeId)) runtime.protectedFiles.add(item);
            else if (sessions.complete()) runtime.candidate.add(item);
            else runtime.unknown.add(item);
        }
        categories.add(category("runtime", "实例私有运行库",
                "旧版配置的实例独立加载器库；仍被版本配置或轮前快照引用时受保护", runtime, 0));

        FileSet shared = new FileSet(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Set<Path> sharedSeen = new LinkedHashSet<>();
        try {
            for (TransactionSession.History history : TransactionSession.history(toolRoot.resolve("state"), instance)) {
                for (ActionRecord action : history.actions()) {
                    if (action.kind() != ActionRecord.Kind.CACHE_LIBRARY) continue;
                    Path target = LibraryPaths.resolveKey(instance, action.filename());
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !sharedSeen.add(target)) continue;
                    shared.protectedFiles.add(new Item(target, Files.size(target)));
                }
            }
        } catch (Exception e) {
            issues.add("共享版本库引用不完整，未估算回收：" + e.getMessage());
        }
        categories.add(category("shared-libraries", "版本专属共享库",
                "与其他实例共用的已校验加载器库；只能作为安全缓存保留", shared, 0));

        Path reportsRoot = toolRoot.resolve("logs").resolve(RunJournal.safeName(instance.name()));
        FileSet reports = FileSet.empty();
        Set<Path> normalizedReports = new HashSet<>();
        for (Path report : sessions.reports()) normalizedReports.add(report.toAbsolutePath().normalize());
        for (Item item : scanTree(reportsRoot, issues, checkpoint)) {
            if (sensitive(item.path())) reports.unknown.add(item);
            else if (normalizedReports.contains(item.path().toAbsolutePath().normalize())) reports.protectedFiles.add(item);
            else reports.candidate.add(item);
        }
        categories.add(category("reports", "运行报告与日志",
                "本次实例的检查报告和完整日志；未被轮次引用时列为清理候选", reports, 0));

        FileSet index = FileSet.empty();
        addFile(index.protectedFiles, toolRoot.resolve("state").resolve("history-index-v1.json"));
        addFile(index.protectedFiles, toolRoot.resolve("state").resolve("repair-policy-v1.json"));
        addFile(index.protectedFiles, toolRoot.resolve("state").resolve("ui-settings-v1.json"));
        addFile(index.protectedFiles, toolRoot.resolve("config.properties"));
        addFile(index.protectedFiles, storage.resolve("recovery-lineage-v1.json"));
        categories.add(category("index", "索引与配置",
                "历史发现索引、恢复资料谱系和工具配置；体积小且用于恢复路径发现", index, 0));

        long disk = categories.stream().mapToLong(Category::diskBytes).sum();
        long cleanable = categories.stream().mapToLong(Category::cleanableBytes).sum();
        long candidate = categories.stream().mapToLong(Category::candidateBytes).sum();
        long protectedBytes = categories.stream().mapToLong(Category::protectedBytes).sum();
        long unknown = categories.stream().mapToLong(Category::unknownBytes).sum();
        long logical = categories.stream().mapToLong(Category::logicalBytes).sum();
        return new Snapshot(instance, categories, logical, disk, cleanable, candidate,
                protectedBytes, unknown, issues);
    }

    private static Category category(String id, String title, String detail, FileSet files, long logicalOverride) {
        long cleanable = sum(files.cleanable());
        long candidate = sum(files.candidate());
        long protectedBytes = sum(files.protectedFiles());
        long unknown = sum(files.unknown());
        long disk = cleanable + candidate + protectedBytes + unknown;
        int count = files.cleanable().size() + files.candidate().size()
                + files.protectedFiles().size() + files.unknown().size();
        return new Category(id, title, detail, logicalOverride > 0 ? logicalOverride : disk, disk,
                cleanable, candidate, protectedBytes, unknown, count);
    }

    private static long sum(List<Item> files) {
        return files.stream().mapToLong(Item::size).sum();
    }

    private static FileSet all(Path root, State state, List<String> issues, Runnable checkpoint) {
        FileSet files = FileSet.empty();
        for (Item item : scanTree(root, issues, checkpoint)) {
            switch (state) {
                case CLEANABLE -> files.cleanable.add(item);
                case CANDIDATE -> files.candidate.add(item);
                case PROTECTED -> files.protectedFiles.add(item);
                case UNKNOWN -> files.unknown.add(item);
            }
        }
        return files;
    }

    private static List<Item> scanTree(Path root, List<String> issues, Runnable checkpoint) {
        List<Item> result = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return result;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                throws IOException {
                            checkpoint.run();
                            if (!PathSafety.isDirectory(dir)) {
                                throw new IOException("存储目录包含链接：" + dir);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            checkpoint.run();
                            if (attrs.isRegularFile() && PathSafety.isRegularFile(file)) {
                                result.add(new Item(file, attrs.size()));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            issues.add("无法读取存储文件元数据：" + file.getFileName() + "（" + exc.getMessage() + "）");
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            issues.add("存储目录无法完整统计：" + root + "（" + e.getMessage() + "）");
        }
        result.sort(Comparator.comparing(item -> item.path().toString()));
        return result;
    }

    private static void addFile(List<Item> target, Path path) {
        try {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) target.add(new Item(path, Files.size(path)));
        } catch (IOException ignored) {
        }
    }

    private static boolean sensitive(Path file) {
        for (Path part : file.toAbsolutePath().normalize()) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".env") || name.equals("secrets") || name.equals("credentials")
                    || name.endsWith(".key") || name.endsWith(".pem")
                    || name.endsWith(".p12") || name.endsWith(".pfx")) return true;
        }
        return false;
    }

    private static SessionData readSessions(GameInstance instance, List<String> issues, Runnable checkpoint) {
        Path directory = instance.dir().resolve(".maa-checker").resolve("workflows");
        List<RepairWorkflow.State> sessions = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>();
        Set<String> runtimes = new LinkedHashSet<>();
        Set<Path> reports = new LinkedHashSet<>();
        boolean complete = true;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            readRuntimeReferences(instance, Set.of(), runtimes, issues);
            return new SessionData(List.of(), Set.of(), Set.copyOf(runtimes), Set.of(), true);
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("policy.json") || name.contains(".tmp-")) continue;
                try {
                    RepairWorkflow.State state = RepairWorkflow.readHistoryEntry(instance, file);
                    sessions.add(state);
                    for (RepairWorkflow.Round round : state.rounds()) {
                        checkpoint.run();
                        hashes.addAll(round.before().inputs().hashes().values());
                        if (round.report() != null && !round.report().isBlank()) {
                            reports.add(Path.of(round.report()).toAbsolutePath().normalize());
                        }
                    }
                } catch (Exception e) {
                    complete = false;
                    issues.add("工作流记录损坏，无法确认引用范围：" + name + "（" + e.getMessage() + "）");
                }
            }
        } catch (IOException e) {
            complete = false;
            issues.add("无法读取工作流目录：" + e.getMessage());
        }
        readRuntimeReferences(instance, hashes, runtimes, issues);
        return new SessionData(List.copyOf(sessions), Set.copyOf(hashes), Set.copyOf(runtimes),
                Set.copyOf(reports), complete);
    }

    private static void readRuntimeReferences(GameInstance instance, Set<String> hashes,
                                              Set<String> runtimes, List<String> issues) {
        Path objects = instance.dir().resolve(".maa-checker").resolve("objects");
        for (String hash : hashes) {
            Path json = objects.resolve(hash + ".json");
            if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                JsonNode node = JSON.readTree(Files.readAllBytes(json));
                String runtime = node.path("maaCheckerRuntime").asText("");
                if (runtime.matches("[0-9a-fA-F-]{36}")) runtimes.add(runtime);
            } catch (Exception e) {
                issues.add("版本快照无法读取运行库引用：" + json.getFileName());
            }
        }
        try {
            JsonNode current = JSON.readTree(Files.readAllBytes(instance.versionJson()));
            String runtime = current.path("maaCheckerRuntime").asText("");
            if (runtime.matches("[0-9a-fA-F-]{36}")) runtimes.add(runtime);
        } catch (IOException e) {
            issues.add("当前版本配置无法读取运行库引用：" + e.getMessage());
        }
    }
}
