package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceStorage;
import yagen.waitmydawn.checker.core.PathSafety;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.core.RunJournal;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.TransactionSession;
import yagen.waitmydawn.checker.transaction.WorkflowLease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 本工具存储的引用安全回收。
 *
 * <p>预览会记录候选路径、大小、修改时间和 SHA-256。执行时在实例租约内重新扫描并比较整份
 * 计划摘要，只有内容和引用关系都未变化才继续；任何缺失账本、未完成事务或异常文件都使
 * 操作失败关闭。
 */
public final class StorageReclaim {
    public record Item(Path path, long size, long modified, String sha256, String label) {
    }

    public record Plan(GameInstance instance, UUID branch, String digest, List<Item> files,
                       List<Path> directories, long bytes, int rounds, List<String> lostPoints,
                       boolean executable, String reason) {
        public Plan {
            files = List.copyOf(files);
            directories = List.copyOf(directories);
            lostPoints = List.copyOf(lostPoints);
        }
    }

    public record Result(String digest, int files, int directories, long bytes, String message) {
    }

    private record References(List<RepairWorkflow.State> sessions,
                              Map<UUID, TransactionSession.History> transactions,
                              Set<String> objectHashes, Set<String> runtimes, Set<Path> reports) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private StorageReclaim() {
    }

    /**
     * 预览所有工作流分支。分支按其 UUID 和轮次关联事务，不使用可能重复的轮号串账。
     */
    public static List<Plan> previewBranches(Path toolRoot, GameInstance instance,
                                             Runnable checkpoint) throws IOException {
        References refs = references(toolRoot, instance, checkpoint);
        List<Plan> plans = new ArrayList<>();
        for (RepairWorkflow.State state : refs.sessions()) {
            checkpoint.run();
            plans.add(branchPlan(toolRoot, instance, refs, state, checkpoint));
        }
        return List.copyOf(plans);
    }

    public static Plan previewOrphans(Path toolRoot, GameInstance instance,
                                      Runnable checkpoint) throws IOException {
        References refs = references(toolRoot, instance, checkpoint);
        List<Item> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        Path storage = InstanceStorage.root(instance, false);

        Path objects = storage.resolve("objects");
        for (Path file : regularFiles(objects, checkpoint)) {
            String name = file.getFileName().toString();
            if (!objectName(name)) continue;
            String hash = name.substring(0, 64);
            if (!refs.objectHashes().contains(hash) && !sensitive(file)) {
                files.add(item(file, "孤儿恢复对象"));
            }
        }

        Path reportsRoot = toolRoot.resolve("logs").resolve(RunJournal.safeName(instance.name()));
        Set<String> reportRefs = new HashSet<>();
        for (Path path : refs.reports()) reportRefs.add(PathSafety.key(path));
        for (Path file : regularFiles(reportsRoot, checkpoint)) {
            if (!reportRefs.contains(PathSafety.key(file)) && !sensitive(file)) {
                files.add(item(file, "未被轮次引用的报告或日志"));
            }
        }

        Path runtime = storage.resolve("runtime");
        for (Path file : regularFiles(runtime, checkpoint)) {
            Path relative = runtime.relativize(file);
            if (relative.getNameCount() < 2) continue;
            String runtimeId = relative.getName(0).toString();
            if (!isUuid(runtimeId)) continue;
            if (!refs.runtimes().contains(runtimeId) && !sensitive(file)) {
                files.add(item(file, "旧实例运行库"));
            }
        }

        collectReclaimed(storage.resolve("workflows"), files, directories, checkpoint);
        Path transactionRoot = toolRoot.resolve("state").resolve("transactions-v1")
                .resolve(InstanceIdentity.of(instance).id());
        collectReclaimed(transactionRoot, files, directories, checkpoint);
        collectInterruptedBranchTransactions(instance, refs, transactionRoot,
                files, directories, checkpoint);

        files.sort(Comparator.comparing(value -> value.path().toString()));
        directories.sort(Comparator.comparing(Path::toString));
        String digest = digest(instance, null, files, directories, List.of());
        long bytes = files.stream().mapToLong(Item::size).sum();
        String reason = files.isEmpty()
                ? "没有可回收的孤儿数据"
                : "仅清理未被任何轮次、恢复凭据或当前配置引用的数据";
        return new Plan(instance, null, digest, files, directories, bytes, 0, List.of(),
                !files.isEmpty(), reason);
    }

    public static Result cleanOrphans(Path toolRoot, GameInstance instance, Plan preview,
                                      Runnable checkpoint) throws IOException {
        if (preview.branch() != null) throw new IOException("孤儿清理计划不能包含历史分支");
        try (var lease = WorkflowLease.acquire(instance)) {
            Plan current = previewOrphans(toolRoot, instance, checkpoint);
            if (!current.digest().equals(preview.digest())) {
                throw new IOException("可回收内容或引用关系已变化，请重新预览");
            }
            if (!current.executable()) throw new IOException(current.reason());
            for (Item value : current.files()) {
                checkpoint.run();
                verify(value);
                Files.delete(value.path());
            }
            Path sessionRoot = toolRoot.resolve("state").resolve("transactions-v1")
                    .resolve(InstanceIdentity.of(instance).id());
            for (Path directory : current.directories().reversed()) {
                checkpoint.run();
                deleteEmptyRecoveryDirectory(directory, sessionRoot);
            }
            return new Result(current.digest(), current.files().size(), current.directories().size(),
                    current.bytes(), "孤儿数据清理完成");
        }
    }

    public static Plan previewBranch(Path toolRoot, GameInstance instance, UUID workflowId,
                                     Runnable checkpoint) throws IOException {
        References refs = references(toolRoot, instance, checkpoint);
        RepairWorkflow.State target = refs.sessions().stream()
                .filter(state -> state.id().equals(workflowId))
                .findFirst()
                .orElseThrow(() -> new IOException("历史分支不存在或已变化"));
        return branchPlan(toolRoot, instance, refs, target, checkpoint);
    }

    public static Result deleteBranch(Path toolRoot, GameInstance instance, Plan preview,
                                      Runnable checkpoint) throws IOException {
        if (!preview.executable() || preview.branch() == null) throw new IOException(preview.reason());
        try (var lease = WorkflowLease.acquire(instance)) {
            Plan current = previewBranch(toolRoot, instance, preview.branch(), checkpoint);
            if (!current.executable()) throw new IOException(current.reason());
            if (!current.digest().equals(preview.digest())) {
                throw new IOException("历史分支、文件内容或引用关系已变化，请重新预览");
            }

            for (Item value : current.files()) {
                checkpoint.run();
                verify(value);
            }

            Path workflow = InstanceStorage.root(instance, false).resolve("workflows")
                    .resolve(current.branch() + ".json");
            if (current.files().stream().noneMatch(value -> PathSafety.samePath(value.path(), workflow))) {
                throw new IOException("回收计划缺少历史分支记录");
            }
            Path hiddenWorkflow = workflow.resolveSibling(workflow.getFileName() + ".reclaimed");
            if (Files.exists(hiddenWorkflow, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("已有同名回收残留，请先清理孤儿数据");
            }

            Path sessionRoot = toolRoot.resolve("state").resolve("transactions-v1")
                    .resolve(InstanceIdentity.of(instance).id());
            for (Path directory : current.directories()) {
                validateTransactionDirectory(sessionRoot, directory);
                Path target = sessionRoot.resolve(".reclaimed-" + directory.getFileName());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("事务回收残留已存在，请先清理孤儿数据");
                }
            }

            // 先隐藏工作流，再暂存事务目录。中断后两处残留都会进入下一次孤儿预览。
            Files.move(workflow, hiddenWorkflow);
            List<Path> hiddenTransactions = new ArrayList<>();
            try {
                for (Path directory : current.directories()) {
                    checkpoint.run();
                    Path target = sessionRoot.resolve(".reclaimed-" + directory.getFileName());
                    Files.move(directory, target);
                    hiddenTransactions.add(target);
                }
                Files.delete(hiddenWorkflow);
                for (int index = 0; index < current.directories().size(); index++) {
                    checkpoint.run();
                    Path original = current.directories().get(index);
                    Path hidden = hiddenTransactions.get(index);
                    deleteTransactionFiles(original, hidden, current.files(), checkpoint);
                    deleteEmptyRecoveryDirectory(hidden, sessionRoot);
                }
            } catch (IOException | RuntimeException e) {
                throw new IOException("历史分支回收未完成；已隐藏的内容保留为回收残留，重新打开存储页可继续清理", e);
            }
            return new Result(current.digest(), current.files().size(),
                    hiddenTransactions.size() + 1, current.bytes(),
                    "历史分支已删除，关联事务已回收；保留的恢复对象可在孤儿清理中复核");
        }
    }

    private static Plan branchPlan(Path toolRoot, GameInstance instance, References refs,
                                   RepairWorkflow.State target, Runnable checkpoint) throws IOException {
        Set<UUID> targetTransactions = WorkflowReconciler.associations(target);
        Set<UUID> otherTransactions = new LinkedHashSet<>();
        for (RepairWorkflow.State state : refs.sessions()) {
            if (!state.id().equals(target.id())) {
                otherTransactions.addAll(WorkflowReconciler.associations(state));
            }
        }
        Set<UUID> shared = new TreeSet<>(targetTransactions);
        shared.retainAll(otherTransactions);

        List<String> lost = target.rounds().stream()
                .map(round -> "第 " + round.number() + " 轮 [" + round.id() + "] · "
                        + UiText.phase(round.phase()) + " · " + round.message())
                .toList();
        boolean settled = target.phase() == RepairWorkflow.Phase.VERIFIED
                || target.phase() == RepairWorkflow.Phase.RESTORED;
        String transactionProblem = transactionProblem(refs, target);
        String reason = !settled
                ? "会话尚未完成或仍有待处理动作，不能删除"
                : target.restoreReceipt() != null || target.restoreTarget() != null
                ? "会话仍有未完成的轮前还原，不能删除"
                : !shared.isEmpty()
                ? "目标事务仍被其他会话或恢复凭据引用，不能删除"
                : transactionProblem;
        if (!settled || !shared.isEmpty() || target.restoreReceipt() != null
                || target.restoreTarget() != null || !transactionProblem.isBlank()) {
            return new Plan(instance, target.id(),
                    digest(instance, target.id(), List.of(), List.of(), lost),
                    List.of(), List.of(), 0, target.rounds().size(), lost, false, reason);
        }

        Path workflow = InstanceStorage.root(instance, false).resolve("workflows")
                .resolve(target.id() + ".json");
        if (!Files.isRegularFile(workflow, LinkOption.NOFOLLOW_LINKS)) {
            return new Plan(instance, target.id(),
                    digest(instance, target.id(), List.of(), List.of(), lost),
                    List.of(), List.of(), 0, target.rounds().size(), lost, false,
                    "工作流记录不存在，不能执行删除");
        }

        Path sessionRoot = toolRoot.resolve("state").resolve("transactions-v1")
                .resolve(InstanceIdentity.of(instance).id());
        Path hiddenWorkflow = workflow.resolveSibling(workflow.getFileName() + ".reclaimed");
        if (Files.exists(hiddenWorkflow, LinkOption.NOFOLLOW_LINKS)) {
            return new Plan(instance, target.id(),
                    digest(instance, target.id(), List.of(), List.of(), lost),
                    List.of(), List.of(), 0, target.rounds().size(), lost, false,
                    "已有历史分支回收残留，请先清理孤儿数据");
        }

        List<Item> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        files.add(item(workflow, "工作流记录"));
        for (UUID id : targetTransactions) {
            Path directory = sessionRoot.resolve(id.toString());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            validateTransactionDirectory(sessionRoot, directory);
            Path hidden = sessionRoot.resolve(".reclaimed-" + directory.getFileName());
            if (Files.exists(hidden, LinkOption.NOFOLLOW_LINKS)) {
                return new Plan(instance, target.id(),
                        digest(instance, target.id(), List.of(), List.of(), lost),
                        List.of(), List.of(), 0, target.rounds().size(), lost, false,
                        "已有事务回收残留，请先清理孤儿数据");
            }
            for (Path file : regularFiles(directory, checkpoint)) {
                if (sensitive(file)) throw new IOException("事务备份包含受保护文件，拒绝回收");
                files.add(item(file, "分支事务备份"));
            }
            directories.add(directory);
        }
        files.sort(Comparator.comparing(value -> value.path().toString()));
        directories.sort(Comparator.comparing(Path::toString));
        long bytes = files.stream().mapToLong(Item::size).sum();
        return new Plan(instance, target.id(),
                digest(instance, target.id(), files, directories, lost),
                files, directories, bytes, target.rounds().size(), lost, true,
                "删除后这些轮次的还原点将永久失去；共享恢复对象不会随分支删除");
    }

    /**
     * 修复事务中仍为 APPLIED 的普通动作可能承担墓碑或禁止重装语义，不能通过删除账本绕过。
     * 恢复凭据中的 RESTORE_* 动作不承担该语义，但同样不得处于未完成状态。
     */
    private static String transactionProblem(References refs, RepairWorkflow.State target) {
        Set<UUID> roundTransactions = new LinkedHashSet<>();
        for (RepairWorkflow.Round round : target.rounds()) {
            roundTransactions.addAll(round.transactions());
        }
        Set<UUID> receiptTransactions = new LinkedHashSet<>(target.restoreReceipts());
        if (target.restoreReceipt() != null) receiptTransactions.add(target.restoreReceipt());

        for (UUID id : roundTransactions) {
            TransactionSession.History history = refs.transactions().get(id);
            if (history == null) return "缺少修复事务账本：" + id;
            for (ActionRecord action : history.actions()) {
                if (action.unresolved()) return "修复事务仍有未完成动作：" + action.filename();
                if (action.kind().name().startsWith("RESTORE_")) {
                    return "修复事务包含恢复动作，账本关联不一致：" + action.filename();
                }
                if (action.state() == ActionRecord.State.APPLIED) {
                    return "修复事务仍有生效动作，删除会改变停用或恢复语义：" + action.filename();
                }
            }
        }
        for (UUID id : receiptTransactions) {
            TransactionSession.History history = refs.transactions().get(id);
            if (history == null) return "缺少恢复凭据账本：" + id;
            for (ActionRecord action : history.actions()) {
                if (action.unresolved()) return "恢复凭据仍有未完成动作：" + action.filename();
                if (!action.kind().name().startsWith("RESTORE_")) {
                    return "恢复凭据包含非恢复动作，账本关联不一致：" + action.filename();
                }
            }
        }
        return "";
    }

    private static References references(Path toolRoot, GameInstance instance,
                                         Runnable checkpoint) throws IOException {
        List<RepairWorkflow.State> sessions = RepairWorkflow.history(instance);
        Map<UUID, TransactionSession.History> transactions = new HashMap<>();
        for (TransactionSession.History history : TransactionSession.history(toolRoot.resolve("state"), instance)) {
            checkpoint.run();
            if (transactions.putIfAbsent(history.header().sessionId(), history) != null) {
                throw new IOException("事务会话重复：" + history.header().sessionId());
            }
        }

        Set<String> hashes = new LinkedHashSet<>();
        Set<String> runtimes = new LinkedHashSet<>();
        Set<Path> reports = new LinkedHashSet<>();
        for (RepairWorkflow.State state : sessions) {
            checkpoint.run();
            for (RepairWorkflow.Round round : state.rounds()) {
                hashes.addAll(round.before().inputs().hashes().values());
                if (round.report() != null && !round.report().isBlank()) {
                    reports.add(Path.of(round.report()).toAbsolutePath().normalize());
                }
            }
        }

        Path objects = InstanceStorage.root(instance, false).resolve("objects");
        for (String hash : hashes) {
            Path json = objects.resolve(hash + ".json");
            if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) continue;
            JsonNode node = JSON.readTree(Files.readAllBytes(json));
            String runtime = node.path("maaCheckerRuntime").asText("");
            if (isUuid(runtime)) runtimes.add(runtime);
        }
        JsonNode current = JSON.readTree(Files.readAllBytes(instance.versionJson()));
        String runtime = current.path("maaCheckerRuntime").asText("");
        if (isUuid(runtime)) runtimes.add(runtime);
        return new References(List.copyOf(sessions), Map.copyOf(transactions), Set.copyOf(hashes),
                Set.copyOf(runtimes), Set.copyOf(reports));
    }

    private static void collectReclaimed(Path root, List<Item> files, List<Path> directories,
                                         Runnable checkpoint) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                boolean recoveredFile = name.endsWith(".json.reclaimed");
                boolean recoveredDirectory = name.startsWith(".reclaimed-")
                        && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                if (recoveredDirectory) {
                    List<Path> children = regularFiles(entry, checkpoint);
                    for (Path file : children) {
                        if (sensitive(file)) throw new IOException("回收残留包含受保护文件，拒绝清理");
                        files.add(item(file, "上次清理残留"));
                    }
                    directories.add(entry);
                } else if (recoveredFile && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && !sensitive(entry)) {
                    files.add(item(entry, "上次清理残留"));
                }
            }
        }
    }

    /**
     * 分支删除先隐藏工作流，再逐目录改名。若中间中断，原事务目录仍是隐藏工作流的唯一副本；
     * 通过隐藏状态中的会话 ID 找回它们，同时跳过任何仍被可见分支共享的事务。
     */
    private static void collectInterruptedBranchTransactions(GameInstance instance, References refs,
                                                              Path sessionRoot, List<Item> files,
                                                              List<Path> directories,
                                                              Runnable checkpoint) throws IOException {
        Path workflowRoot = InstanceStorage.root(instance, false).resolve("workflows");
        if (!Files.isDirectory(workflowRoot, LinkOption.NOFOLLOW_LINKS)) return;
        Set<UUID> visible = new LinkedHashSet<>();
        for (RepairWorkflow.State state : refs.sessions()) {
            visible.addAll(WorkflowReconciler.associations(state));
        }
        Set<String> seenFiles = new HashSet<>(files.stream()
                .map(value -> PathSafety.key(value.path())).toList());
        Set<String> seenDirectories = new HashSet<>(directories.stream()
                .map(PathSafety::key).toList());

        try (var entries = Files.list(workflowRoot)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".json.reclaimed")) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
                    throw new IOException("回收中的工作流残留不是普通文件");
                }
                String workflowId = name.substring(0, name.length() - ".json.reclaimed".length());
                UUID id;
                try {
                    id = UUID.fromString(workflowId);
                } catch (IllegalArgumentException e) {
                    throw new IOException("回收中的工作流残留身份无效", e);
                }
                RepairWorkflow.State state;
                try {
                    state = JSON.readValue(Files.readAllBytes(entry), RepairWorkflow.State.class);
                } catch (Exception e) {
                    throw new IOException("无法读取回收中的工作流残留：" + entry.getFileName(), e);
                }
                if (!id.equals(state.id())
                        || !InstanceIdentity.of(instance).equals(state.instance())
                        || state.rounds() == null) {
                    throw new IOException("回收中的工作流残留身份或格式不匹配");
                }
                checkpoint.run();
                for (UUID transaction : WorkflowReconciler.associations(state)) {
                    if (visible.contains(transaction)) continue;
                    Path directory = sessionRoot.resolve(transaction.toString());
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                    validateTransactionDirectory(sessionRoot, directory);
                    for (Path file : regularFiles(directory, checkpoint)) {
                        if (sensitive(file)) {
                            throw new IOException("中断分支的事务备份包含受保护文件，拒绝回收");
                        }
                        if (seenFiles.add(PathSafety.key(file))) {
                            files.add(item(file, "上次清理残留"));
                        }
                    }
                    if (seenDirectories.add(PathSafety.key(directory))) {
                        directories.add(directory);
                    }
                }
            }
        }
    }

    private static List<Path> regularFiles(Path root, Runnable checkpoint) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("回收范围不是普通目录：" + root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkpoint.run();
                if (!attrs.isDirectory() || !PathSafety.isDirectory(dir)) {
                    throw new IOException("回收范围包含目录链接");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkpoint.run();
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new IOException("回收范围包含非普通文件：" + file);
                }
                files.add(file.toAbsolutePath().normalize());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static void deleteTransactionFiles(Path originalRoot, Path hiddenRoot,
                                               List<Item> files, Runnable checkpoint) throws IOException {
        Path normalizedOriginal = PathSafety.canonical(originalRoot);
        for (Item value : files) {
            Path canonical = PathSafety.canonical(value.path());
            if (!canonical.startsWith(normalizedOriginal)) continue;
            checkpoint.run();
            Path moved = hiddenRoot.resolve(normalizedOriginal.relativize(canonical)).normalize();
            verifyAt(value, moved);
            Files.delete(moved);
        }
    }

    /**
     * 只移除已经为空的确认目录。任何未列入预览的新文件或链接都会在第 0 个删除前被发现，
     * 即使删除期间出现，也会因目录非空而保留，绝不会递归删除未知内容。
     */
    private static void deleteEmptyRecoveryDirectory(Path root, Path sessionRoot) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        boolean reclaimed = absolute.getFileName().toString().startsWith(".reclaimed-");
        boolean interruptedTransaction = absolute.getParent() != null
                && PathSafety.samePath(absolute.getParent(), sessionRoot)
                && isUuid(absolute.getFileName().toString());
        if (!reclaimed && !interruptedTransaction) {
            throw new IOException("只删除明确的回收残留目录");
        }
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("回收残留目录不存在或类型已变化");
        }
        if (!regularFiles(absolute, () -> { }).isEmpty()) {
            throw new IOException("回收目录出现未列入预览的内容，保留且停止删除：" + absolute);
        }
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isDirectory() || !PathSafety.isDirectory(dir)
                        || sensitive(dir)) {
                    throw new IOException("回收目录包含链接或受保护路径");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                throw new IOException("回收目录在删除期间出现新内容，保留：" + file);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void validateTransactionDirectory(Path sessionRoot, Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!PathSafety.samePath(normalized.getParent(), sessionRoot)
                || !isUuid(normalized.getFileName().toString())) {
            throw new IOException("事务回收目录不在当前实例事务根内：" + directory);
        }
    }

    private static Item item(Path file, String label) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException("回收候选不是普通文件：" + file);
        }
        Path normalized = file.toAbsolutePath().normalize();
        return new Item(normalized, attrs.size(), attrs.lastModifiedTime().toMillis(),
                RepairInputs.hash(normalized), label);
    }

    private static void verify(Item value) throws IOException {
        verifyAt(value, value.path());
    }

    private static void verifyAt(Item value, Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || Files.isSymbolicLink(path)
                || attrs.size() != value.size()
                || attrs.lastModifiedTime().toMillis() != value.modified()
                || !RepairInputs.hash(path).equals(value.sha256())) {
            throw new IOException("回收候选内容已变化：" + path);
        }
    }

    private static String digest(GameInstance instance, UUID branch, List<Item> files,
                                 List<Path> directories, List<String> lost) throws IOException {
        StringBuilder value = new StringBuilder(InstanceIdentity.of(instance).id()).append('|')
                .append(branch == null ? "-" : branch).append('\n');
        for (Item file : files) {
            value.append("f|").append(file.path()).append('|').append(file.size()).append('|')
                    .append(file.modified()).append('|').append(file.sha256()).append('|')
                    .append(file.label()).append('\n');
        }
        for (Path directory : directories) {
            value.append("d|").append(directory.toAbsolutePath().normalize()).append('\n');
        }
        for (String point : lost) value.append("l|").append(point).append('\n');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean objectName(String name) {
        return name.matches("[a-f0-9]{64}\\.(jar|json|cfg)");
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

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class UiText {
        private static String phase(RepairWorkflow.Phase phase) {
            return switch (phase) {
                case CHECKING -> "检查中";
                case PLANNING -> "规划中";
                case WAITING -> "等待决定";
                case APPLYING -> "应用与验证中";
                case VERIFIED -> "验证通过";
                case NOT_PASSED -> "验证未通过";
                case PAUSED -> "已暂停";
                case RESTORING -> "恢复进行中";
                case RESTORED -> "已还原";
            };
        }
    }
}
