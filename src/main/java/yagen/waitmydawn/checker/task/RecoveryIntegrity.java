package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceStorage;
import yagen.waitmydawn.checker.core.PathSafety;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 实例移动后的恢复资料完整性检查与显式重新关联。
 *
 * <p>身份仍由实例目录、模组目录和版本文件路径派生。移动后不会按实例名或 Minecraft 版本
 * 自动绑定旧恢复资料；只有实例内仍保留同一批工作流记录、旧位置已失效，并且事务账本与
 * 轮前对象完整时，用户才能预览并确认迁移。
 */
public final class RecoveryIntegrity {
    public enum Severity { INFO, WARNING, ERROR }

    public record Finding(String code, Severity severity, String title, String detail,
                          String nextStep) {
    }

    public record Candidate(InstanceIdentity source, List<UUID> workflows,
                            Map<UUID, String> workflowHashes, List<UUID> transactions,
                            int rounds, String digest,
                            boolean executable, String reason, List<String> warnings) {
        public Candidate {
            workflows = List.copyOf(workflows);
            workflowHashes = Map.copyOf(workflowHashes);
            transactions = List.copyOf(transactions);
            warnings = List.copyOf(warnings);
        }
    }

    public record Report(GameInstance instance, InstanceIdentity identity, UUID recoveryId,
                         List<Candidate> candidates, List<Finding> findings,
                         int currentWorkflows, int movedWorkflows, int corruptWorkflows,
                         int currentTransactions, int missingObjects,
                         List<String> snapshotScope) {
        public Report {
            candidates = List.copyOf(candidates);
            findings = List.copyOf(findings);
            snapshotScope = List.copyOf(snapshotScope);
        }

        public boolean needsReassociation() {
            return !candidates.isEmpty();
        }

        public boolean hasErrors() {
            return findings.stream().anyMatch(value -> value.severity() == Severity.ERROR);
        }

        public boolean healthy() {
            return !needsReassociation() && !hasErrors() && corruptWorkflows == 0;
        }
    }

    public record Plan(GameInstance instance, InstanceIdentity source, InstanceIdentity target,
                       String digest, List<UUID> workflows, Map<UUID, String> workflowHashes,
                       List<UUID> transactions,
                       int rounds, List<String> warnings, boolean executable, String reason) {
        public Plan {
            workflows = List.copyOf(workflows);
            workflowHashes = Map.copyOf(workflowHashes);
            transactions = List.copyOf(transactions);
            warnings = List.copyOf(warnings);
        }
    }

    public record Result(RecoveryLineage lineage, int workflows, int copiedTransactions,
                         int reusedTransactions, List<String> warnings, String message) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    private record WorkflowFile(Path path, UUID id, RepairWorkflow.State state, String sha256) {
    }

    private record CandidateBuilder(InstanceIdentity source, List<WorkflowFile> workflows,
                                    int rounds) {
    }

    private record Inspected(Report report, Map<String, Candidate> candidates) {
    }

    private record TransactionRead(List<TransactionSession.History> histories, String error) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SNAPSHOT_SCOPE = List.of(
            "包含：版本 JSON、实例 mods 目录顶层 JAR、modpack.cfg 整合包标记。",
            "不包含：世界与存档、普通配置目录、账号资料、资源文件、共享 libraries 全量备份。",
            "实例移动时必须保留整个版本目录中的 .maa-checker；仅复制 mods 或版本 JSON 无法还原旧记录。"
    );

    private RecoveryIntegrity() {
    }

    public static Report inspect(Path toolRoot, GameInstance instance) throws IOException {
        return inspect(toolRoot, instance, () -> { });
    }

    public static Report inspect(Path toolRoot, GameInstance instance, Runnable checkpoint) throws IOException {
        return inspectInternal(toolRoot, instance, checkpoint).report();
    }

    public static Plan preview(Path toolRoot, GameInstance instance, String sourceIdentity,
                               Runnable checkpoint) throws IOException {
        try (var lease = WorkflowLease.acquire(instance)) {
            Inspected inspected = inspectInternal(toolRoot, instance, checkpoint);
            Candidate candidate = inspected.candidates().get(sourceIdentity);
            if (candidate == null) {
                throw new IOException("待重新关联的旧身份不存在或记录已变化");
            }
            return new Plan(instance, candidate.source(), inspected.report().identity(),
                    candidate.digest(), candidate.workflows(), candidate.workflowHashes(),
                    candidate.transactions(),
                    candidate.rounds(), candidate.warnings(), candidate.executable(),
                    candidate.reason());
        }
    }

    public static Result reassociate(Path toolRoot, GameInstance instance, Plan preview,
                                     Runnable checkpoint) throws IOException {
        if (!preview.executable()) throw new IOException(preview.reason());
        InstanceStorage.requireIsolatedMods(instance);
        try (var lease = WorkflowLease.acquire(instance)) {
            Inspected inspected = inspectInternal(toolRoot, instance, checkpoint);
            Candidate current = inspected.candidates().get(preview.source().id());
            if (current == null || !current.executable()) {
                throw new IOException(current == null
                        ? "待重新关联的旧身份已变化，请重新预览"
                        : current.reason());
            }
            if (!current.digest().equals(preview.digest())
                    || !current.workflows().equals(preview.workflows())
                    || !current.workflowHashes().equals(preview.workflowHashes())
                    || !current.transactions().equals(preview.transactions())
                    || !inspected.report().identity().equals(preview.target())) {
                throw new IOException("恢复资料、引用或目标实例身份已变化，请重新预览");
            }

            TransactionSession.Reassociation copied = TransactionSession.reassociate(
                    toolRoot.resolve("state"), preview.source(), preview.target(),
                    preview.transactions(), checkpoint);
            try {
                for (UUID workflow : preview.workflows()) {
                    checkpoint.run();
                    WorkflowFile file = workflowFile(instance, workflow);
                    if (!file.state().instance().equals(preview.source())) continue;
                    if (!file.sha256().equals(preview.workflowHashes().get(workflow))) {
                        throw new IOException("工作流内容在预览后发生变化：" + workflow);
                    }
                    RepairWorkflow.State migrated = migrate(file.state(), preview.target());
                    RoundSnapshot.writeJournal(instance, workflow + ".json", migrated);
                }
            } catch (IOException | RuntimeException e) {
                throw new IOException("事务账本已迁移，但工作流改写未完成；请重新预览继续，原旧身份资料仍保留", e);
            }

            RecoveryLineage lineage = RecoveryLineage.reassociate(instance, preview.source(),
                    preview.target());
            List<String> warnings = new ArrayList<>(preview.warnings());
            try {
                WorkflowHistoryIndex.reassociate(toolRoot, Path.of(preview.source().directory()), instance);
            } catch (IOException e) {
                warnings.add("历史发现索引未更新；刷新扫描后可从当前实例重新建立：" + e.getMessage());
            }

            Set<UUID> present = new HashSet<>();
            for (RepairWorkflow.State state : RepairWorkflow.history(instance)) present.add(state.id());
            if (!present.containsAll(preview.workflows())) {
                throw new IOException("重新关联后仍有工作流无法从当前身份读取，请重新检查完整性");
            }
            Set<UUID> transactions = new HashSet<>();
            for (TransactionSession.History history : TransactionSession.history(
                    toolRoot.resolve("state"), preview.target())) {
                transactions.add(history.header().sessionId());
            }
            if (!transactions.containsAll(preview.transactions())) {
                throw new IOException("重新关联后事务账本无法从当前身份完整读取，请重新检查完整性");
            }
            return new Result(lineage, preview.workflows().size(),
                    copied.copied().size(), copied.reused().size(), List.copyOf(warnings),
                    "恢复资料已重新关联：迁移 " + preview.workflows().size()
                            + " 个工作流，复制 " + copied.copied().size() + " 个事务账本");
        }
    }

    private static Inspected inspectInternal(Path toolRoot, GameInstance instance,
                                              Runnable checkpoint) throws IOException {
        InstanceIdentity identity = InstanceIdentity.of(instance);
        List<Finding> findings = new ArrayList<>();
        UUID recoveryId = null;
        InstanceIdentity lineageOld = null;
        try {
            Optional<RecoveryLineage> lineage = RecoveryLineage.read(instance);
            if (lineage.isPresent()) {
                RecoveryLineage value = lineage.get();
                recoveryId = value.recoveryId();
                if (!identity.id().equals(value.identityId())) {
                    lineageOld = new InstanceIdentity(value.identityId(), value.directory(),
                            value.modsDirectory(), value.versionFile());
                    findings.add(new Finding("MOVE_LINEAGE", Severity.INFO, "恢复谱系来自旧路径",
                            "实例内谱系仍绑定 " + value.directory() + "。",
                            "核对存储页候选后显式重新关联；不会按实例名自动绑定。"));
                }
            } else {
                findings.add(new Finding("LINEAGE_NEW", Severity.INFO, "尚无恢复谱系",
                        "当前实例还没有重新关联记录；下一次持久化工作流时会创建稳定谱系 UUID。",
                        "无需操作。"));
            }
        } catch (Exception e) {
            findings.add(new Finding("LINEAGE_CORRUPT", Severity.ERROR, "恢复谱系损坏",
                    e.getMessage(), "保留原文件并恢复有效备份；不能从名称猜测旧身份。"));
        }

        List<WorkflowFile> currentWorkflows = new ArrayList<>();
        Map<String, CandidateBuilder> moved = new LinkedHashMap<>();
        int corruptWorkflows = 0;
        Path workflows = InstanceStorage.root(instance, false).resolve("workflows");
        if (Files.isDirectory(workflows, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(workflows)) {
                for (Path file : files.sorted().toList()) {
                    checkpoint.run();
                    String name = file.getFileName().toString();
                    if (name.equals("policy.json") || name.contains(".tmp-")
                            || name.endsWith(".reclaimed")) continue;
                    if (!name.endsWith(".json")) {
                        corruptWorkflows++;
                        findings.add(new Finding("WORKFLOW_UNKNOWN", Severity.ERROR, "未知工作流文件",
                                name, "保留文件并人工确认来源；自动迁移不会处理未知文件。"));
                        continue;
                    }
                    UUID id;
                    try {
                        id = UUID.fromString(name.substring(0, name.length() - 5));
                    } catch (IllegalArgumentException e) {
                        corruptWorkflows++;
                        findings.add(new Finding("WORKFLOW_NAME", Severity.ERROR, "工作流文件名无效",
                                name, "恢复原文件或移走后再检查；不会从内容猜测会话 ID。"));
                        continue;
                    }
                    try {
                        WorkflowFile parsed = workflowFile(instance, id);
                        if (parsed.state().instance().equals(identity)) {
                            RepairWorkflow.readHistoryEntry(instance, parsed.path());
                            currentWorkflows.add(parsed);
                        } else {
                            CandidateBuilder builder = moved.computeIfAbsent(
                                    parsed.state().instance().id(),
                                    ignored -> new CandidateBuilder(parsed.state().instance(),
                                            new ArrayList<>(), 0));
                            if (!builder.source().equals(parsed.state().instance())) {
                                throw new IOException("同一旧身份 ID 对应了不同路径绑定");
                            }
                            builder.workflows().add(parsed);
                        }
                    } catch (Exception e) {
                        corruptWorkflows++;
                        findings.add(new Finding("WORKFLOW_CORRUPT", Severity.ERROR,
                                "工作流记录损坏", name + "：" + e.getMessage(),
                                "保留原文件并恢复有效备份；不会从可读摘要或文件名猜测快照。"));
                    }
                }
            }
        }
        if (lineageOld != null) {
            InstanceIdentity lineageIdentity = lineageOld;
            moved.computeIfAbsent(lineageIdentity.id(), ignored ->
                    new CandidateBuilder(lineageIdentity, new ArrayList<>(), 0));
        }
        if (moved.size() > 1) {
            findings.add(new Finding("MULTIPLE_OLD_IDENTITIES", Severity.ERROR,
                    "存在多个旧实例身份", String.join("、", moved.keySet()),
                    "一次只能显式关联一个可证明的旧身份；请保留记录并人工拆分来源。"));
        }

        TransactionRead currentTransactions = safeTransactions(toolRoot.resolve("state"), identity);
        if (currentTransactions.error() != null) {
            findings.add(new Finding("TRANSACTION_CORRUPT", Severity.ERROR, "当前身份事务账本损坏",
                    currentTransactions.error(),
                    "保留原目录并恢复有效账本；不能删除后再声称恢复点可用。"));
        }
        int currentMissingObjects = missingObjects(instance,
                new CandidateBuilder(identity, currentWorkflows, 0), checkpoint);
        if (currentMissingObjects > 0) {
            findings.add(new Finding("CURRENT_OBJECT_MISSING", Severity.ERROR,
                    "当前工作流的轮前对象缺失",
                    "当前身份有 " + currentMissingObjects + " 个快照对象缺失或摘要不符。",
                    "先恢复内容对象或移走损坏轮次；不能把这些恢复点描述为可用。"));
        }
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int movedWorkflows = 0;
        int missingObjects = 0;
        for (CandidateBuilder builder : moved.values()) {
            movedWorkflows += builder.workflows().size();
            Candidate candidate = evaluateCandidate(toolRoot, instance, identity, builder,
                    currentWorkflows, corruptWorkflows, moved.size() > 1,
                    currentTransactions.histories(), currentTransactions.error() != null,
                    currentMissingObjects, findings, checkpoint);
            candidates.put(candidate.source().id(), candidate);
            missingObjects += missingObjects(instance, builder, checkpoint);
        }

        Report report = new Report(instance, identity, recoveryId,
                List.copyOf(candidates.values()), List.copyOf(findings),
                currentWorkflows.size(), movedWorkflows, corruptWorkflows,
                currentTransactions.histories().size(), currentMissingObjects + missingObjects,
                SNAPSHOT_SCOPE);
        return new Inspected(report, Map.copyOf(candidates));
    }

    private static Candidate evaluateCandidate(Path toolRoot, GameInstance instance,
                                               InstanceIdentity target, CandidateBuilder builder,
                                               List<WorkflowFile> currentWorkflows,
                                               int corruptWorkflows, boolean multipleOld,
                                               List<TransactionSession.History> currentTransactions,
                                               boolean currentTransactionsCorrupt,
                                               int currentMissingObjects,
                                               List<Finding> findings, Runnable checkpoint) throws IOException {
        InstanceIdentity source = builder.source();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Path newMods = Path.of(target.modsDirectory()).toAbsolutePath().normalize();
        try {
            Path oldDirectory = Path.of(source.directory()).toAbsolutePath().normalize();
            if (Files.exists(oldDirectory, LinkOption.NOFOLLOW_LINKS)) {
                blockers.add("旧实例目录仍存在；不能用副本静默改绑仍然可用的身份");
            }
            Path oldMods = Path.of(source.modsDirectory()).toAbsolutePath().normalize();
            if (!samePath(oldMods, newMods) && Files.exists(oldMods, LinkOption.NOFOLLOW_LINKS)) {
                blockers.add("旧 mods 目录仍存在且与新实例不一致");
            }
        } catch (RuntimeException e) {
            blockers.add("旧身份路径格式无效：" + e.getMessage());
        }
        if (builder.workflows().isEmpty()) {
            warnings.add("旧谱系存在，但实例目录中没有可迁移工作流；仅会更新谱系绑定");
        }
        for (WorkflowFile file : builder.workflows()) {
            for (RepairWorkflow.Round round : file.state().rounds()) {
                if (!Objects.equals(file.state().instance(), source)
                        || !round.before().inputs().versionFile().equals(source.versionFile())
                        || !round.before().inputs().modsDirectory().equals(source.modsDirectory())) {
                    blockers.add("工作流 " + file.id() + " 的轮次快照未绑定声明旧身份");
                    break;
                }
                if (!Objects.equals(round.before().minecraft(), instance.mcVersion())) {
                    blockers.add("Minecraft 版本不一致：记录 " + round.before().minecraft()
                            + "，当前 " + instance.mcVersion());
                    break;
                }
                if (!Objects.equals(round.before().loader(), instance.loader())) {
                    blockers.add("加载器类型不一致：记录 " + round.before().loader()
                            + "，当前 " + instance.loader());
                    break;
                }
            }
        }
        if (corruptWorkflows > 0) blockers.add("存在 " + corruptWorkflows + " 条损坏记录，迁移后会继续阻断历史读取");
        if (multipleOld) blockers.add("实例内存在多个旧身份，不能自动选择");
        if (currentTransactionsCorrupt) blockers.add("当前身份事务账本损坏，需先恢复");
        if (currentMissingObjects > 0) {
            blockers.add("当前工作流已有 " + currentMissingObjects + " 个轮前对象缺失，需先处理");
        }

        int rounds = builder.workflows().stream().mapToInt(file -> file.state().rounds().size()).sum();
        Set<UUID> workflowIds = new LinkedHashSet<>();
        Map<UUID, String> workflowHashes = new LinkedHashMap<>();
        Set<UUID> transactionIds = new LinkedHashSet<>();
        for (WorkflowFile file : builder.workflows()) {
            workflowIds.add(file.id());
            workflowHashes.put(file.id(), file.sha256());
            transactionIds.addAll(WorkflowReconciler.associations(file.state()));
        }
        Map<UUID, TransactionSession.History> oldTransactions = new LinkedHashMap<>();
        TransactionRead sourceRead = safeTransactions(toolRoot.resolve("state"), source);
        if (sourceRead.error() != null) {
            blockers.add("旧身份事务账本无法完整读取");
            findings.add(new Finding("TRANSACTION_CORRUPT", Severity.ERROR, "旧身份事务账本损坏",
                    source.directory() + "：" + sourceRead.error(),
                    "保留原目录并恢复有效账本；不能删除后再声称恢复点可用。"));
        }
        List<TransactionSession.History> sourceTransactions = sourceRead.histories();
        for (TransactionSession.History history : sourceTransactions) {
            oldTransactions.put(history.header().sessionId(), history);
        }
        Map<UUID, TransactionSession.History> targetTransactions = new LinkedHashMap<>();
        for (TransactionSession.History history : currentTransactions) {
            targetTransactions.put(history.header().sessionId(), history);
        }
        for (UUID id : transactionIds) {
            if (!oldTransactions.containsKey(id) && !targetTransactions.containsKey(id)) {
                blockers.add("缺少事务账本：" + id);
            } else if (oldTransactions.containsKey(id) && targetTransactions.containsKey(id)) {
                try {
                    if (!targetTransactions.get(id).equals(TransactionSession.projected(
                            oldTransactions.get(id), target))) {
                        blockers.add("目标身份已有内容不同的事务账本：" + id);
                    }
                } catch (IOException e) {
                    blockers.add("事务账本身份投影失败：" + id);
                }
            }
        }

        int missing = missingObjects(instance, builder, checkpoint);
        if (missing > 0) blockers.add("轮前快照对象缺失或损坏：" + missing + " 个");
        for (WorkflowFile file : builder.workflows()) {
            for (RepairWorkflow.Round round : file.state().rounds()) {
                if (round.report() != null && !round.report().isBlank()
                        && !Files.isRegularFile(Path.of(round.report()), LinkOption.NOFOLLOW_LINKS)) {
                    warnings.add("报告缺失但仍可从轮次台账恢复：" + round.report());
                }
            }
        }
        Set<UUID> alreadyCurrent = currentWorkflows.stream().map(WorkflowFile::id)
                .collect(java.util.stream.Collectors.toSet());
        for (UUID id : workflowIds) {
            if (alreadyCurrent.contains(id)) blockers.add("当前身份已存在同 ID 工作流：" + id);
        }

        String digest = digest(toolRoot, instance, source, target, builder.workflows(),
                transactionIds, checkpoint);
        String reason = blockers.isEmpty()
                ? "旧位置已失效，记录、快照与事务关联通过校验；确认后迁移恢复资料"
                : String.join("；", blockers);
        if (!blockers.isEmpty()) {
            findings.add(new Finding("MOVE_BLOCKED", Severity.ERROR, "恢复资料不能自动重新关联",
                    reason, "保留旧位置、工作流、对象和事务账本；修复缺失或冲突后重新检查。"));
        } else {
            findings.add(new Finding("MOVE_READY", Severity.WARNING, "发现可重新关联的恢复资料",
                    source.directory() + " → " + target.directory(),
                    "打开预览核对工作流、事务和快照，再显式确认迁移。"));
        }
        return new Candidate(source, new ArrayList<>(workflowIds), workflowHashes,
                new ArrayList<>(transactionIds), rounds, digest, blockers.isEmpty(),
                reason, warnings);
    }

    private static int missingObjects(GameInstance instance, CandidateBuilder builder,
                                      Runnable checkpoint) throws IOException {
        Path objects = InstanceStorage.root(instance, false).resolve("objects");
        Set<String> verified = new HashSet<>();
        int missing = 0;
        for (WorkflowFile file : builder.workflows()) {
            for (RepairWorkflow.Round round : file.state().rounds()) {
                for (Map.Entry<String, String> input : round.before().inputs().hashes().entrySet()) {
                    checkpoint.run();
                    String key = entryKey(input.getKey(), input.getValue());
                    if (!verified.add(key)) continue;
                    Path object = objects.resolve(input.getValue() + suffix(input.getKey()));
                    if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(object)
                            || !RepairInputs.hash(object).equals(input.getValue())) {
                        missing++;
                    }
                }
            }
        }
        return missing;
    }

    private static TransactionRead safeTransactions(Path stateRoot, InstanceIdentity identity) {
        try {
            return new TransactionRead(TransactionSession.history(stateRoot, identity), null);
        } catch (Exception e) {
            return new TransactionRead(List.of(), e.getMessage());
        }
    }

    private static WorkflowFile workflowFile(GameInstance instance, UUID id) throws IOException {
        Path file = InstanceStorage.root(instance, false).resolve("workflows").resolve(id + ".json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("工作流不存在或不是普通文件");
        }
        RepairWorkflow.State state;
        try {
            state = JSON.readValue(Files.readAllBytes(file), RepairWorkflow.State.class);
        } catch (Exception e) {
            throw new IOException("工作流 JSON 无法读取", e);
        }
        validateShape(state, id);
        return new WorkflowFile(file.toAbsolutePath().normalize(), id, state, RepairInputs.hash(file));
    }

    private static void validateShape(RepairWorkflow.State state, UUID id) throws IOException {
        if (state == null || state.schema() != 1 || !id.equals(state.id())
                || state.instance() == null || state.policy() == null || state.rounds() == null
                || state.rounds().isEmpty() || state.current() < 0
                || state.current() >= state.rounds().size()) {
            throw new IOException("工作流身份、版本或轮次索引无效");
        }
        Set<UUID> rounds = new HashSet<>();
        for (RepairWorkflow.Round round : state.rounds()) {
            if (round == null || round.id() == null || !rounds.add(round.id())
                    || round.number() < 1 || round.before() == null
                    || round.before().inputs() == null) {
                throw new IOException("工作流轮次身份或快照无效");
            }
            if (!round.before().inputs().versionFile().equals(state.instance().versionFile())
                    || !round.before().inputs().modsDirectory().equals(state.instance().modsDirectory())) {
                throw new IOException("轮次快照没有绑定工作流声明身份");
            }
            for (Map.Entry<String, String> hash : round.before().inputs().hashes().entrySet()) {
                if (!(hash.getKey().equals("version") || hash.getKey().equals("marker")
                        || hash.getKey().startsWith("mod/"))
                        || !Objects.toString(hash.getValue(), "").matches("[a-f0-9]{64}")) {
                    throw new IOException("轮次快照键或内容指纹无效");
                }
            }
        }
    }

    private static RepairWorkflow.State migrate(RepairWorkflow.State state, InstanceIdentity target) {
        List<RepairWorkflow.Round> rounds = new ArrayList<>();
        for (RepairWorkflow.Round round : state.rounds()) {
            RoundSnapshot old = round.before();
            RepairInputs inputs = new RepairInputs(target.versionFile(), target.modsDirectory(),
                    old.inputs().hashes());
            RoundSnapshot migrated = new RoundSnapshot(inputs, old.minecraft(), old.loader(),
                    old.loaderVersion(), old.mods());
            rounds.add(new RepairWorkflow.Round(round.id(), round.number(), migrated, round.phase(),
                    round.message(), round.events(), round.transactions(), round.report()));
        }
        return new RepairWorkflow.State(state.schema(), state.id(), target, state.createdAt(),
                state.policy(), state.current(), state.phase(), state.message(), rounds,
                state.decision(), state.restoreReceipt(), state.restoreTarget(),
                state.restoreReceipts());
    }

    private static String digest(Path toolRoot, GameInstance instance, InstanceIdentity source,
                                 InstanceIdentity target, List<WorkflowFile> workflows,
                                 Set<UUID> transactions, Runnable checkpoint) throws IOException {
        StringBuilder value = new StringBuilder("reassociate-v1\n");
        value.append(source.id()).append('|').append(target.id()).append('\n');
        List<Path> files = new ArrayList<>();
        for (WorkflowFile workflow : workflows) files.add(workflow.path());
        Path transactionsRoot = toolRoot.resolve("state").resolve("transactions-v1");
        for (UUID id : transactions) {
            Path oldSession = transactionsRoot.resolve(source.id()).resolve(id.toString());
            Path newSession = transactionsRoot.resolve(target.id()).resolve(id.toString());
            if (Files.isDirectory(oldSession, LinkOption.NOFOLLOW_LINKS)) collectFiles(oldSession, files, checkpoint);
            else if (Files.isDirectory(newSession, LinkOption.NOFOLLOW_LINKS)) collectFiles(newSession, files, checkpoint);
        }
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            value.append(file.toAbsolutePath().normalize()).append('|').append(attrs.size())
                    .append('|').append(attrs.lastModifiedTime().toMillis()).append('|')
                    .append(RepairInputs.hash(file)).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collectFiles(Path root, List<Path> files, Runnable checkpoint) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkpoint.run();
                if (!attrs.isDirectory() || !PathSafety.isDirectory(dir)) {
                    throw new IOException("事务账本包含目录链接");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkpoint.run();
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new IOException("事务账本包含非普通文件");
                }
                files.add(file.toAbsolutePath().normalize());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String suffix(String key) {
        if (key.equals("version")) return ".json";
        if (key.equals("marker")) return ".cfg";
        if (key.startsWith("mod/")) return ".jar";
        throw new IllegalArgumentException("未知快照键：" + key);
    }

    private static String entryKey(String key, String hash) {
        return key + "|" + hash;
    }

    private static boolean samePath(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }
}
