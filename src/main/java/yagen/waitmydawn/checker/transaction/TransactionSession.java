package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.PathSafety;
import yagen.waitmydawn.checker.core.InstanceStorage;
import yagen.waitmydawn.checker.core.PathSafety;
import yagen.waitmydawn.checker.core.RepairInputs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;
import static yagen.waitmydawn.checker.transaction.ActionRecord.*;

/** 逐文件事务。调用方必须先完成诊断、候选身份验证与授权；本层不推断修复策略。 */
public final class TransactionSession implements AutoCloseable {
    public record Header(int schemaVersion, UUID sessionId, InstanceIdentity instance, long createdAt, int storageLayout) { }
    public record Outcome(UUID actionId, State state, String message) { }
    public record History(Header header, List<ActionRecord> actions) {
        public History { actions = List.copyOf(actions); }
    }
    public record Reassociation(List<UUID> copied, List<UUID> reused) {
        public Reassociation {
            copied = List.copyOf(copied);
            reused = List.copyOf(reused);
        }
    }
    public enum Point { BEFORE_INTENT, AFTER_INTENT, AFTER_WITHDRAW, AFTER_MUTATION, BEFORE_COMMIT,
        AFTER_REVERT_INTENT, AFTER_UNDO_MUTATION, BEFORE_REVERT_COMMIT }
    @FunctionalInterface
    public interface FaultProbe { void at(Point point, ActionRecord action) throws IOException; }
    private static final FaultProbe NO_FAULT = (point, action) -> { };
    private static final Set<Path> JVM_LOCKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final InstanceIdentity identity;
    private final GameInstance binding;
    private final Header header;
    private final Path instanceStore;
    private final Path sessionDir;
    private final WorkflowLease workflowLease;
    private final List<FileChannel> locks = new ArrayList<>();
    private final List<Path> reservations = new ArrayList<>();
    private final FaultProbe probe;
    private boolean closed;
    private boolean faulted;

    public static TransactionSession create(Path stateRoot, GameInstance instance) throws IOException {
        return create(stateRoot, instance, NO_FAULT);
    }

    public static TransactionSession create(Path stateRoot, GameInstance instance, FaultProbe probe) throws IOException {
        return new TransactionSession(stateRoot, instance, UUID.randomUUID(), true, probe);
    }

    public static TransactionSession resume(Path stateRoot, GameInstance instance, UUID sessionId) throws IOException {
        return new TransactionSession(stateRoot, instance, sessionId, false, NO_FAULT);
    }

    private TransactionSession(Path stateRoot, GameInstance instance, UUID sessionId, boolean create, FaultProbe probe) throws IOException {
        this.identity = InstanceIdentity.of(instance);
        this.binding = instance;
        this.probe = Objects.requireNonNull(probe);
        this.workflowLease = WorkflowLease.acquire(instance);
        try {
            if (create) TransactionFiles.regular(Path.of(identity.versionFile()));
            acquireLocks();
            Files.createDirectories(stateRoot);
            Path root = stateRoot.toRealPath();
            Path versioned = TransactionFiles.directory(root, "transactions-v1", create);
            instanceStore = TransactionFiles.directory(versioned, identity.id(), create);
            List<History> previousSessions = readHistory(instanceStore, identity);
            if (create) {
                for (History previous : previousSessions) {
                    if (previous.actions().stream().anyMatch(ActionRecord::unresolved)) {
                        throw new IOException("存在未解决的会话，必须先恢复：" + previous.header().sessionId());
                    }
                }
            }
            sessionDir = TransactionFiles.directory(instanceStore, sessionId.toString(), create);
            if (create) {
                long createdAt = Math.max(System.currentTimeMillis(), previousSessions.stream().mapToLong(h -> h.header().createdAt()).max().orElse(0) + 1);
                int layout = PathSafety.samePath(Path.of(identity.modsDirectory()),
                        Path.of(identity.directory()).resolve("mods")) ? 2 : 0;
                header = new Header(1, sessionId, identity, createdAt, layout);
                TransactionFiles.write(sessionDir.resolve("session.json"), header);
            } else {
                header = TransactionFiles.read(sessionDir.resolve("session.json"), Header.class);
                validateHeader(header, sessionId, identity);
                for (var action : actions()) {
                    if (action.kind() == Kind.CACHE_LIBRARY) LibraryPaths.target(binding, action.filename(), false);
                }
            }
        } catch (IOException | RuntimeException e) {
            try { releaseLocks(); } catch (IOException release) { e.addSuppressed(release); }
            try { workflowLease.close(); } catch (IOException release) { e.addSuppressed(release); }
            throw e;
        }
    }

    public Header header() { return header; }

    private void acquireLocks() throws IOException {
        List<Path> files = new ArrayList<>(Set.of(Path.of(identity.modsDirectory()).resolve(".maa-checker.lock"),
                Path.of(identity.directory()).resolve(".maa-checker.lock")));
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            if (!JVM_LOCKS.add(file)) throw new IOException("实例或共享模组目录已有活动事务");
            reservations.add(file);
            if (Files.exists(file, NOFOLLOW_LINKS)) TransactionFiles.regular(file);
            FileChannel channel = FileChannel.open(file, CREATE, WRITE, NOFOLLOW_LINKS);
            locks.add(channel);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) throw new IOException("实例或共享模组目录被其他事务占用");
            } catch (OverlappingFileLockException e) { throw new IOException("实例或共享模组目录已有活动事务", e); }
        }
    }

    public synchronized ActionRecord installMod(String modId, String filename, Path candidate, String expectedHash, int round,
                                                  String reason, List<String> evidence) throws IOException {
        return apply(Kind.INSTALL_MOD, modId, filename, candidate, expectedHash, round, reason, evidence);
    }

    public synchronized ActionRecord cacheLibrary(String relative, Path candidate, String hash, int round,
                                                  String reason, List<String> evidence) throws IOException {
        writable();
        String key = LibraryPaths.key(binding, relative);
        LibraryPaths.target(binding, key, true);
        return apply(Kind.CACHE_LIBRARY, null, key, candidate, hash, round, reason, evidence);
    }

    public synchronized ActionRecord instanceLibrary(String runtime, String relative, Path candidate, String hash, int round,
                                                      String reason, List<String> evidence) throws IOException {
        writable();
        String key = runtime + "/" + relative;
        privateLibrary(key, true);
        return apply(Kind.INSTANCE_LIBRARY, null, key, candidate, hash, round, reason, evidence);
    }

    public synchronized ActionRecord restoreMod(String filename, Path candidate, String beforeHash, int round, String reason) throws IOException {
        Kind kind = candidate == null ? Kind.RESTORE_REMOVE : beforeHash == null ? Kind.RESTORE_ADD : Kind.RESTORE_REPLACE;
        String hash = beforeHash == null ? RepairInputs.hash(candidate) : beforeHash;
        return apply(kind, "snapshot:" + filename, filename, candidate, hash, round, reason, List.of(reason));
    }

    public synchronized ActionRecord restoreMarker(Path candidate, String beforeHash, int round, String reason) throws IOException {
        if (candidate == null) return disablePackMarker(beforeHash, round, reason, List.of(reason));
        return apply(beforeHash == null ? Kind.RESTORE_MARKER_ADD : Kind.RESTORE_MARKER_REPLACE, null, "modpack.cfg", candidate,
                beforeHash == null ? RepairInputs.hash(candidate) : beforeHash, round, reason, List.of(reason));
    }

    /** 已完成并核验的轮次快照是新的状态基准；保留原动作和恢复凭据，不再次修改文件。 */
    public synchronized void acknowledgeSnapshot(UUID receipt, RepairInputs expected) throws IOException {
        writable();
        if (!RepairInputs.capture(binding).equals(expected)) throw new IOException("快照恢复结果尚未校验");
        for (var action : actions()) {
            if (action.unresolved()) throw new IOException("必须先解决未完成文件动作");
            if (action.state() != State.REVERTED) persist(action.state(State.REVERTED, "已由轮次快照恢复，会话凭据：" + receipt));
        }
    }

    public synchronized ActionRecord removeMod(String modId, String filename, String expectedHash, int round,
                                                 String reason, List<String> evidence) throws IOException {
        return apply(Kind.REMOVE_MOD, modId, filename, null, expectedHash, round, reason, evidence);
    }

    public synchronized ActionRecord replaceVersion(Path candidate, String expectedCurrentHash, int round,
                                                      String reason, List<String> evidence) throws IOException {
        return apply(Kind.REPLACE_VERSION, null, Path.of(identity.versionFile()).getFileName().toString(), candidate,
                expectedCurrentHash, round, reason, evidence);
    }

    public synchronized ActionRecord disablePackMarker(String expectedHash, int round, String reason,
                                                        List<String> evidence) throws IOException {
        return apply(Kind.DISABLE_PACK_MARKER, null, "modpack.cfg", null, expectedHash, round, reason, evidence);
    }

    private ActionRecord apply(Kind kind, String modId, String filename, Path candidate, String expectedHash, int round,
                               String reason, List<String> evidence) throws IOException {
        writable();
        List<ActionRecord> existing = actions();
        if (existing.stream().anyMatch(ActionRecord::unresolved)) throw new IOException("必须先恢复未完成动作或解决冲突");
        if ((kind == Kind.INSTALL_MOD || kind == Kind.REMOVE_MOD) && (modId == null || modId.isBlank())) throw new IllegalArgumentException("模组动作必须携带已验证的 modId");
        for (History other : readHistory(instanceStore, identity)) {
            if (other.header().createdAt() > header.createdAt()) throw new IOException("旧会话不能继续追加动作，请创建新会话");
            if (kind == Kind.INSTALL_MOD && other.actions().stream().anyMatch(action -> action.kind() == Kind.REMOVE_MOD
                    && action.state() != State.REVERTED && action.modId().equals(modId))) {
                throw new IOException("该模组存在未撤销的停用记录，禁止重新安装：" + modId);
            }
        }
        if (kind == Kind.CACHE_LIBRARY) LibraryPaths.validateKey(filename);
        else if (kind == Kind.INSTANCE_LIBRARY) validatePrivateLibrary(filename);
        else TransactionFiles.filename(filename);
        if (round < 1 || reason == null || reason.isBlank() || evidence == null || evidence.isEmpty()) {
            throw new IllegalArgumentException("动作必须包含有效轮次、原因与证据");
        }
        checkHash(expectedHash);
        if (round < existing.stream().mapToInt(ActionRecord::round).max().orElse(1)) throw new IllegalArgumentException("轮次不能倒退");
        Path target = target(kind, filename);
        boolean addition = kind == Kind.INSTALL_MOD || kind == Kind.CACHE_LIBRARY || kind == Kind.INSTANCE_LIBRARY || kind == Kind.RESTORE_ADD || kind == Kind.RESTORE_MARKER_ADD;
        String before = addition ? null : TransactionFiles.hash(target);
        if (addition && Files.exists(target, NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        if (before != null && !before.equals(expectedHash)) throw new IOException("目标内容已变化，取消动作");
        if (candidate != null) validateCandidate(candidate, kind);
        String after = kind == Kind.REMOVE_MOD || kind == Kind.DISABLE_PACK_MARKER || kind == Kind.RESTORE_REMOVE ? null : TransactionFiles.hash(candidate.toAbsolutePath().normalize());
        if (addition && !after.equals(expectedHash)) throw new IOException("候选摘要不符，拒绝安装");
        if (kind == Kind.REPLACE_VERSION) {
            if (!TransactionFiles.JSON.readTree(candidate.toFile()).isObject()) throw new IOException("版本候选必须为 JSON 对象");
        }
        ActionRecord action = new ActionRecord(1, UUID.randomUUID(), header.sessionId(), identity.id(),
                existing.stream().mapToInt(ActionRecord::sequence).max().orElse(0) + 1, round, kind, modId, filename,
                before, after, State.PREPARED, reason, evidence, System.currentTimeMillis(), "已准备，尚未确认提交");
        try {
            Path work = work(action, true);
            if (before != null) TransactionFiles.copyVerified(target, work.resolve("before.bin"), before);
            if (after != null) TransactionFiles.copyVerified(candidate.toAbsolutePath().normalize(), work.resolve("new.bin"), after);
            probe.at(Point.BEFORE_INTENT, action);
            persist(action);
            probe.at(Point.AFTER_INTENT, action);
            if (before != null) {
                if (!TransactionFiles.hash(target).equals(before)) throw new IOException("目标在提交前发生变化");
                Files.move(target, work.resolve("withdrawn.bin"));
                if (!TransactionFiles.hash(work.resolve("withdrawn.bin")).equals(before)) throw new IOException("目标发生并发变更，原文件已保留待人工处理");
                probe.at(Point.AFTER_WITHDRAW, action);
            }
            if (after != null) Files.move(work.resolve("new.bin"), target);
            probe.at(Point.AFTER_MUTATION, action);
            if (after != null && !TransactionFiles.hash(target).equals(after)) throw new IOException("写入后目标摘要不符");
            if (before != null && !TransactionFiles.hash(work.resolve("withdrawn.bin")).equals(before)) throw new IOException("保留的原文件发生外部变化");
            probe.at(Point.BEFORE_COMMIT, action);
            ActionRecord committed = action.state(State.APPLIED, "文件变更已校验并提交");
            persist(committed);
            return committed;
        } catch (IOException | RuntimeException e) { faulted = true; throw e; }
    }

    /** 恢复未完成动作，不自动撤销已经提交的动作。 */
    public synchronized List<Outcome> recoverPending() throws IOException {
        writable();
        requireReverseSessionOrder();
        List<Outcome> outcomes = new ArrayList<>();
        for (ActionRecord action : reversed()) {
            if (action.unresolved()) {
                Outcome outcome = revert(action);
                outcomes.add(outcome);
                if (outcome.state() == State.CONFLICT) break;
            }
        }
        return List.copyOf(outcomes);
    }

    /** 逆序恢复整个会话；遇到冲突停止，避免继续撤销其前置状态。 */
    public synchronized List<Outcome> restoreAll() throws IOException {
        writable();
        requireReverseSessionOrder();
        List<Outcome> outcomes = new ArrayList<>();
        for (ActionRecord action : reversed()) {
            if (action.state() == State.REVERTED) continue;
            Outcome outcome = revert(action);
            outcomes.add(outcome);
            if (outcome.state() == State.CONFLICT) break;
        }
        return List.copyOf(outcomes);
    }

    /** 恢复下一个逆序动作，允许任务服务在两个完整文件动作之间响应取消。 */
    public synchronized Outcome restoreNext() throws IOException {
        writable();
        requireReverseSessionOrder();
        for (ActionRecord action : reversed()) {
            if (action.state() != State.REVERTED) return revert(action);
        }
        return null;
    }

    private Outcome revert(ActionRecord action) throws IOException {
        try {
            if (action.kind() == Kind.CACHE_LIBRARY) {
                target(action.kind(), action.filename());
                String message = "共享库缓存保留，不移除、不覆盖；实例配置由对应版本动作恢复";
                persist(action.state(State.REVERTED, message));
                return new Outcome(action.id(), State.REVERTED, message);
            }
            Path target = target(action.kind(), action.filename());
            String current = Files.exists(target, NOFOLLOW_LINKS) ? TransactionFiles.hash(target) : null;
            if (Objects.equals(current, action.beforeHash())) return finishRevert(action);
            Path work = work(action, false);
            Path withdrawn = work.resolve("withdrawn.bin");
            if (action.beforeHash() != null && Files.exists(withdrawn, NOFOLLOW_LINKS)
                    && !TransactionFiles.hash(withdrawn).equals(action.beforeHash())) {
                return conflict(action, "保留的原文件已被修改，需人工处理");
            }
            if (current != null && !current.equals(action.afterHash())) return conflict(action, "当前文件已被修改或替换，未覆盖、未移除");
            Path restore = null;
            if (action.beforeHash() != null) {
                if (!Files.isRegularFile(work.resolve("before.bin"), NOFOLLOW_LINKS)
                        || !TransactionFiles.hash(work.resolve("before.bin")).equals(action.beforeHash())) return conflict(action, "备份缺失或摘要不符，未改动当前文件");
                restore = work.resolve("restore-" + UUID.randomUUID() + ".bin");
                TransactionFiles.copyVerified(work.resolve("before.bin"), restore, action.beforeHash());
            }
            ActionRecord reverting = action.state(State.REVERTING, "已记录恢复意图");
            persist(reverting);
            probe.at(Point.AFTER_REVERT_INTENT, action);
            if (current != null) {
                if (!TransactionFiles.hash(target).equals(current)) return conflict(action, "恢复前目标发生变化");
                Path quarantined = work.resolve("undo-" + UUID.randomUUID() + ".bin");
                Files.move(target, quarantined);
                if (!TransactionFiles.hash(quarantined).equals(current)) {
                    if (!Files.exists(target, NOFOLLOW_LINKS)) Files.move(quarantined, target);
                    return conflict(action, "恢复期间发生外部修改，已保留文件");
                }
            }
            probe.at(Point.AFTER_UNDO_MUTATION, action);
            if (restore != null) Files.move(restore, target);
            String restored = Files.exists(target, NOFOLLOW_LINKS) ? TransactionFiles.hash(target) : null;
            if (!Objects.equals(restored, action.beforeHash())) return conflict(action, "恢复后内容不符，需人工检查");
            probe.at(Point.BEFORE_REVERT_COMMIT, action);
            return finishRevert(action);
        } catch (IOException | RuntimeException e) { faulted = true; throw e; }
    }

    private Outcome finishRevert(ActionRecord action) throws IOException {
        persist(action.state(State.REVERTED, "已恢复到动作前内容"));
        return new Outcome(action.id(), State.REVERTED, "已恢复到动作前内容");
    }

    private Outcome conflict(ActionRecord action, String message) throws IOException {
        persist(action.state(State.CONFLICT, message));
        return new Outcome(action.id(), State.CONFLICT, message);
    }

    private Path target(Kind kind, String filename) throws IOException {
        if (kind == Kind.CACHE_LIBRARY) return LibraryPaths.target(binding, filename, false);
        if (kind == Kind.INSTANCE_LIBRARY) return privateLibrary(filename, false);
        TransactionFiles.filename(filename);
        if (kind == Kind.DISABLE_PACK_MARKER || kind == Kind.RESTORE_MARKER_ADD || kind == Kind.RESTORE_MARKER_REPLACE) {
            if (!filename.equals("modpack.cfg")) throw new IOException("整合包标记目标不匹配");
            Path directory = Path.of(identity.directory());
            if (!PathSafety.isDirectory(directory)) throw new IOException("实例目录发生链接变化");
            return directory.resolve(filename);
        }
        if (kind == Kind.REPLACE_VERSION) {
            if (!Path.of(identity.versionFile()).getFileName().toString().equals(filename)) throw new IOException("元数据目标不匹配");
            Path target = Path.of(identity.versionFile());
            if (!PathSafety.isDirectory(target.getParent())) throw new IOException("实例目录已发生链接变化");
            return target;
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IOException("模组动作只允许 JAR 文件");
        Path mods = Path.of(identity.modsDirectory());
        if (!PathSafety.isDirectory(mods)) throw new IOException("模组目录已发生链接变化");
        return mods.resolve(filename);
    }

    private Path work(ActionRecord action, boolean create) throws IOException {
        if (header.storageLayout() == 2) {
            Path root = InstanceStorage.directory(InstanceStorage.root(binding, create), "transactions", create);
            Path session = InstanceStorage.directory(root, action.sessionId().toString(), create);
            return InstanceStorage.directory(session, action.id().toString(), create);
        }
        Path parent = target(action.kind(), action.filename()).getParent();
        Path root = TransactionFiles.directory(parent, ".maa-checker-work", create);
        Path session = TransactionFiles.directory(root, action.sessionId().toString(), create);
        return TransactionFiles.directory(session, action.id().toString(), create);
    }

    private void persist(ActionRecord action) throws IOException {
        TransactionFiles.write(sessionDir.resolve(actionFile(action)), action);
    }

    private static String actionFile(ActionRecord action) {
        return String.format(Locale.ROOT, "%08d-%s.json", action.sequence(), action.id());
    }

    public synchronized List<ActionRecord> actions() throws IOException {
        if (closed) throw new IOException("事务会话已关闭");
        return readActions(sessionDir, header);
    }

    private List<ActionRecord> reversed() throws IOException {
        return actions().stream().sorted(Comparator.comparingInt(ActionRecord::sequence).reversed()).toList();
    }

    public static List<History> history(Path stateRoot, GameInstance instance) throws IOException {
        InstanceIdentity identity = InstanceIdentity.of(instance);
        return history(stateRoot, identity);
    }

    /**
     * 按账本内身份读取事务历史，供实例移动后的显式重新关联使用。
     * 该入口不读取当前实例路径，因此旧身份目录失效后仍能校验旧事务内容。
     */
    public static List<History> history(Path stateRoot, InstanceIdentity identity) throws IOException {
        if (!Files.isDirectory(stateRoot)) return List.of();
        Path root = stateRoot.toRealPath();
        if (!Files.exists(root.resolve("transactions-v1"), NOFOLLOW_LINKS)) return List.of();
        Path versioned = TransactionFiles.directory(root, "transactions-v1", false);
        if (!Files.exists(versioned.resolve(identity.id()), NOFOLLOW_LINKS)) return List.of();
        return readHistory(TransactionFiles.directory(versioned, identity.id(), false), identity);
    }

    /**
     * 将指定的已完成事务账本复制到新路径身份下，并把会话头与动作中的实例 ID 改写为目标身份。
     *
     * <p>源目录保持不动；目标中已存在相同内容时按幂等成功处理，内容不同则拒绝覆盖。
     * 工作目录仍由事务原有的 storageLayout 与当前实例路径推导，不需要复制到工具状态目录。
     */
    public static Reassociation reassociate(Path stateRoot, InstanceIdentity source,
                                            InstanceIdentity target, Collection<UUID> sessionIds,
                                            Runnable checkpoint) throws IOException {
        if (source.id().equals(target.id())) throw new IOException("源与目标实例身份相同");
        if (sessionIds == null || sessionIds.isEmpty()) {
            return new Reassociation(List.of(), List.of());
        }
        LinkedHashSet<UUID> requested = new LinkedHashSet<>(sessionIds);
        if (requested.size() != sessionIds.size()) throw new IOException("事务会话列表包含重复身份");
        Map<UUID, History> available = new LinkedHashMap<>();
        for (History history : history(stateRoot, source)) {
            available.put(history.header().sessionId(), history);
        }

        Files.createDirectories(stateRoot);
        Path root = stateRoot.toRealPath();
        Path versioned = TransactionFiles.directory(root, "transactions-v1", true);
        Path targetRoot = TransactionFiles.directory(versioned, target.id(), true);
        List<UUID> copied = new ArrayList<>();
        List<UUID> reused = new ArrayList<>();
        for (UUID id : requested) {
            checkpoint.run();
            History history = available.get(id);
            if (history == null) {
                Path existing = targetRoot.resolve(id.toString());
                if (Files.isDirectory(existing, NOFOLLOW_LINKS)) {
                    readSession(existing, target, id);
                    reused.add(id);
                    continue;
                }
                throw new IOException("旧身份事务账本缺失：" + id);
            }
            History expected = projected(history, target);
            Path existing = targetRoot.resolve(id.toString());
            if (Files.exists(existing, NOFOLLOW_LINKS)) {
                History actual = readSession(existing, target, id);
                if (!actual.equals(expected)) {
                    throw new IOException("目标身份已存在内容不同的事务账本：" + id);
                }
                reused.add(id);
                continue;
            }
            Path staging = TransactionFiles.directory(targetRoot,
                    ".reassociate-" + source.id() + "-" + id + "-" + UUID.randomUUID(), true);
            try {
                TransactionFiles.write(staging.resolve("session.json"), expected.header());
                for (ActionRecord action : expected.actions()) {
                    checkpoint.run();
                    TransactionFiles.write(staging.resolve(actionFile(action)), action);
                }
                History written = readSession(staging, target, id);
                if (!written.equals(expected)) throw new IOException("事务重新关联写入校验失败：" + id);
                checkpoint.run();
                try {
                    Files.move(staging, existing, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, existing);
                }
                copied.add(id);
            } finally {
                if (Files.exists(staging, NOFOLLOW_LINKS)) deleteSessionDirectory(staging);
            }
        }
        return new Reassociation(copied, reused);
    }

    public static History projected(History history, InstanceIdentity target) throws IOException {
        Header header = history.header();
        if (header.instance() == null || header.instance().id().equals(target.id())) {
            throw new IOException("只能把其他身份的完整事务会话投影到目标身份");
        }
        if (history.actions().stream().anyMatch(action -> !header.instance().id().equals(action.instanceId()))) {
            throw new IOException("事务动作与源身份不一致");
        }
        List<ActionRecord> actions = history.actions().stream()
                .map(action -> new ActionRecord(action.schemaVersion(), action.id(), action.sessionId(),
                        target.id(), action.sequence(), action.round(), action.kind(), action.modId(),
                        action.filename(), action.beforeHash(), action.afterHash(), action.state(),
                        action.reason(), action.evidence(), action.createdAt(), action.note()))
                .toList();
        return new History(new Header(header.schemaVersion(), header.sessionId(), target,
                header.createdAt(), header.storageLayout()), actions);
    }

    private static History readSession(Path directory, InstanceIdentity identity,
                                       UUID expected) throws IOException {
        TransactionFiles.directory(directory.getParent(), directory.getFileName().toString(), false);
        Header header = TransactionFiles.read(directory.resolve("session.json"), Header.class);
        validateHeader(header, header.sessionId(), identity);
        if (!expected.equals(header.sessionId())) throw new IOException("事务会话身份与目录请求不匹配");
        return new History(header, readActions(directory, header));
    }

    private static void deleteSessionDirectory(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    throw new IOException("重新关联暂存目录包含异常内容");
                }
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }

    private static List<History> readHistory(Path directory, InstanceIdentity identity) throws IOException {
        List<History> history = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path session : files.toList()) {
                // 回收中断会留下以 .reclaimed- 开头的暂存目录；它们不是可恢复事务，
                // 由存储回收入口继续处理，不能让普通历史读取因此永久失效。
                if (session.getFileName().toString().startsWith(".reclaimed-")) continue;
                UUID id;
                try { id = UUID.fromString(session.getFileName().toString()); }
                catch (IllegalArgumentException e) { throw new IOException("存在未知会话目录，拒绝忽略损坏历史", e); }
                TransactionFiles.directory(directory, id.toString(), false);
                Header header = TransactionFiles.read(session.resolve("session.json"), Header.class);
                validateHeader(header, id, identity);
                history.add(new History(header, readActions(session, header)));
            }
        }
        return history.stream().sorted(Comparator.comparingLong((History h) -> h.header().createdAt()).reversed()).toList();
    }

    private static List<ActionRecord> readActions(Path session, Header header) throws IOException {
        List<ActionRecord> actions = new ArrayList<>();
        Set<Integer> sequences = new HashSet<>();
        try (var files = Files.list(session)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.equals("session.json") || name.contains(".tmp-")) continue;
                if (!name.endsWith(".json")) throw new IOException("未知事务记录文件：" + name);
                ActionRecord action = TransactionFiles.read(file, ActionRecord.class);
                if (action.schemaVersion() != 1 || action.id() == null || !header.sessionId().equals(action.sessionId())
                        || !header.instance().id().equals(action.instanceId()) || action.kind() == null || action.state() == null
                        || action.sequence() < 1 || action.round() < 1 || !sequences.add(action.sequence()) || !actionFile(action).equals(name)) {
                    throw new IOException("动作记录身份或格式不匹配");
                }
                try {
                    if (action.kind() == Kind.CACHE_LIBRARY) LibraryPaths.validateKey(action.filename());
                    else if (action.kind() == Kind.INSTANCE_LIBRARY) validatePrivateLibrary(action.filename());
                    else TransactionFiles.filename(action.filename());
                    if (action.beforeHash() != null) checkHash(action.beforeHash());
                    if (action.afterHash() != null) checkHash(action.afterHash());
                    boolean validHashes = switch (action.kind()) {
                        case INSTALL_MOD, CACHE_LIBRARY, INSTANCE_LIBRARY, RESTORE_ADD, RESTORE_MARKER_ADD -> action.beforeHash() == null && action.afterHash() != null;
                        case REMOVE_MOD, DISABLE_PACK_MARKER, RESTORE_REMOVE -> action.beforeHash() != null && action.afterHash() == null;
                        case REPLACE_VERSION, RESTORE_REPLACE, RESTORE_MARKER_REPLACE -> action.beforeHash() != null && action.afterHash() != null;
                    };
                    if (!validHashes || action.reason() == null || action.reason().isBlank() || action.evidence().isEmpty()) throw new IllegalArgumentException();
                    if ((action.kind() == Kind.INSTALL_MOD || action.kind() == Kind.REMOVE_MOD)
                            && (action.modId() == null || action.modId().isBlank())) throw new IllegalArgumentException();
                    if (action.kind() == Kind.DISABLE_PACK_MARKER && !action.filename().equals("modpack.cfg")) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) { throw new IOException("动作证据或指纹损坏", e); }
                actions.add(action);
            }
        }
        return actions.stream().sorted(Comparator.comparingInt(ActionRecord::sequence)).toList();
    }

    private static void validateHeader(Header header, UUID id, InstanceIdentity identity) throws IOException {
        if (header.schemaVersion() != 1 || !id.equals(header.sessionId()) || !identity.equals(header.instance())
                || (header.storageLayout() != 0 && header.storageLayout() != 2)) {
            throw new IOException("会话版本或实例身份不匹配");
        }
    }

    private static void validatePrivateLibrary(String key) throws IOException {
        int slash = key.indexOf('/');
        if (slash < 0) throw new IOException("实例库路径无效");
        try { UUID.fromString(key.substring(0, slash)); } catch (IllegalArgumentException e) { throw new IOException("实例运行库身份无效", e); }
        LibraryPaths.relative(key.substring(slash + 1));
    }

    private Path privateLibrary(String key, boolean create) throws IOException {
        validatePrivateLibrary(key);
        int slash = key.indexOf('/');
        Path root = InstanceStorage.libraries(binding, key.substring(0, slash), create);
        String[] parts = key.substring(slash + 1).split("/");
        for (int i = 0; i < parts.length - 1; i++) root = InstanceStorage.directory(root, parts[i], create);
        return root.resolve(parts[parts.length - 1]);
    }

    private static void checkHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("必须提供 SHA-256 内容指纹");
    }

    /** 墓碑从动作台账派生，恢复只解除对应动作，不按同号轮次误清其他会话。 */
    public static Set<String> blockedMods(Path stateRoot, GameInstance instance) throws IOException {
        Set<String> blocked = new TreeSet<>();
        for (History session : history(stateRoot, instance)) {
            Set<String> installed = new HashSet<>();
            for (ActionRecord action : session.actions()) {
                if (action.kind() == Kind.INSTALL_MOD && action.state() != State.REVERTED) {
                    installed.add(action.modId());
                }
            }
            for (ActionRecord action : session.actions()) {
                if (action.kind() == Kind.REMOVE_MOD && action.state() != State.REVERTED
                        && !installed.contains(action.modId())) {
                    blocked.add(action.modId());
                }
            }
        }
        return Set.copyOf(blocked);
    }

    private static void validateCandidate(Path candidate, Kind kind) throws IOException {
        String suffix = kind == Kind.REPLACE_VERSION ? ".json" : kind == Kind.RESTORE_MARKER_ADD || kind == Kind.RESTORE_MARKER_REPLACE ? ".cfg" : ".jar";
        if (!candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) throw new IOException("候选文件类型不匹配");
        for (Path component : candidate.toAbsolutePath().normalize()) {
            String name = component.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".env") || name.equals("secrets") || name.equals("credentials")) {
                throw new IOException("候选位于受保护的配置或凭据路径");
            }
        }
    }

    private void requireReverseSessionOrder() throws IOException {
        for (History other : readHistory(instanceStore, identity)) {
            if (other.header().createdAt() > header.createdAt()
                    && other.actions().stream().anyMatch(action -> action.state() != State.REVERTED)) {
                throw new IOException("必须先恢复后续会话：" + other.header().sessionId());
            }
        }
    }

    private void writable() throws IOException {
        if (closed || faulted) throw new IOException("会话已关闭或发生持久化/执行错误，必须重新打开并恢复");
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("事务尚未开始，已停止");
    }

    private void releaseLocks() throws IOException {
        IOException failure = null;
        for (FileChannel channel : locks) {
            try { channel.close(); }
            catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        locks.clear();
        for (Path path : reservations) JVM_LOCKS.remove(path);
        reservations.clear();
        if (failure != null) throw failure;
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        IOException failure = null;
        try { releaseLocks(); }
        catch (IOException e) { failure = e; }
        try { workflowLease.close(); }
        catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
