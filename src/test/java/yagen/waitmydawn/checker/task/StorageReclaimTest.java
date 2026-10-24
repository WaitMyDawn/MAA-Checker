package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.core.RunJournal;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageReclaimTest {
    @TempDir Path temp;

    @Test
    void 孤儿对象预览后清理且任意轮次引用对象保持不动() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        writeState(instance, RepairWorkflow.Phase.PAUSED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.PAUSED, List.of())),
                null, null, List.of());
        Path tool = temp.resolve("tool");

        Path objects = instance.dir().resolve(".maa-checker/objects");
        Path orphan = objects.resolve("a".repeat(64) + ".jar");
        Files.writeString(orphan, "orphan");
        Path runtimeOrphan = instance.dir().resolve(".maa-checker/runtime")
                .resolve(UUID.randomUUID().toString()).resolve("libraries/old.jar");
        Files.createDirectories(runtimeOrphan.getParent());
        Files.writeString(runtimeOrphan, "old runtime");
        Path reportOrphan = tool.resolve("logs").resolve(RunJournal.safeName(instance.name()))
                .resolve("old-run/report.txt");
        Files.createDirectories(reportOrphan.getParent());
        Files.writeString(reportOrphan, "old report");
        List<Path> referenced = snapshot.inputs().hashes().entrySet().stream()
                .map(entry -> objects.resolve(entry.getValue() + suffix(entry.getKey())))
                .toList();

        StorageReclaim.Plan preview = StorageReclaim.previewOrphans(tool, instance, () -> { });
        assertTrue(preview.files().stream().anyMatch(item -> item.path().equals(orphan)));
        assertTrue(preview.files().stream().anyMatch(item -> item.path().equals(runtimeOrphan)));
        assertTrue(preview.files().stream().anyMatch(item -> item.path().equals(reportOrphan)));
        for (Path path : referenced) {
            assertFalse(preview.files().stream().anyMatch(item -> item.path().equals(path)), path.toString());
        }

        StorageReclaim.cleanOrphans(tool, instance, preview, () -> { });
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(runtimeOrphan));
        assertFalse(Files.exists(reportOrphan));
        referenced.forEach(path -> assertTrue(Files.exists(path), path.toString()));
    }

    @Test
    void 两个分支共享恢复对象时删除旧分支不会伤及保留分支() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot shared = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID transaction = emptyTransaction(tool, instance);
        RepairWorkflow.State target = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, shared, RepairWorkflow.Phase.VERIFIED, List.of(transaction))),
                null, null, List.of());
        RepairWorkflow.State retained = writeState(instance, RepairWorkflow.Phase.PAUSED,
                List.of(round(1, shared, RepairWorkflow.Phase.PAUSED, List.of())),
                null, null, List.of());

        StorageReclaim.Plan preview = StorageReclaim.previewBranch(tool, instance, target.id(), () -> { });
        assertTrue(preview.executable(), preview.reason());
        StorageReclaim.deleteBranch(tool, instance, preview, () -> { });

        assertThrows(IOException.class,
                () -> RepairWorkflow.load(instance, target.id()));
        assertEquals(retained, RepairWorkflow.load(instance, retained.id()));
        for (String hash : shared.inputs().hashes().values()) {
            Path object = instance.dir().resolve(".maa-checker/objects")
                    .resolve(hash + suffixForKey(shared, hash));
            assertTrue(Files.exists(object), "保留分支仍引用该恢复对象：" + object);
        }
        StorageReclaim.Plan orphans = StorageReclaim.previewOrphans(tool, instance, () -> { });
        for (String hash : shared.inputs().hashes().values()) {
            assertFalse(orphans.files().stream()
                    .anyMatch(item -> item.path().getFileName().toString().startsWith(hash)));
        }
    }

    @Test
    void 未完成会话暂停会话未解决事务和共享事务均阻断分支删除() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");

        RepairWorkflow.State paused = writeState(instance, RepairWorkflow.Phase.PAUSED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.PAUSED, List.of())),
                null, null, List.of());
        assertFalse(StorageReclaim.previewBranch(tool, instance, paused.id(), () -> { }).executable());

        UUID unresolved = unresolvedRemoval(tool, instance, broken);
        RepairWorkflow.State unresolvedState = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(unresolved))),
                null, null, List.of());
        StorageReclaim.Plan unresolvedPlan =
                StorageReclaim.previewBranch(tool, instance, unresolvedState.id(), () -> { });
        assertFalse(unresolvedPlan.executable());
        assertTrue(unresolvedPlan.reason().contains("未完成"), unresolvedPlan.reason());

        GameInstance sharedInstance = RepairTasksTest.fixture(temp.resolve("shared-game"), "repairable");
        RoundSnapshot sharedSnapshot = RoundSnapshot.capture(sharedInstance);
        Path sharedTool = temp.resolve("shared-tool");
        UUID sharedTransaction = emptyTransaction(sharedTool, sharedInstance);
        RepairWorkflow.State first = writeState(sharedInstance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, sharedSnapshot, RepairWorkflow.Phase.VERIFIED, List.of(sharedTransaction))),
                null, null, List.of());
        writeState(sharedInstance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, sharedSnapshot, RepairWorkflow.Phase.VERIFIED, List.of(sharedTransaction))),
                null, null, List.of());
        StorageReclaim.Plan shared =
                StorageReclaim.previewBranch(sharedTool, sharedInstance, first.id(), () -> { });
        assertFalse(shared.executable());
        assertTrue(shared.reason().contains("其他会话"), shared.reason());
    }

    @Test
    void 已验证分支仍有生效停用动作时拒绝删除以保护墓碑语义() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID transaction;
        try (var session = TransactionSession.create(tool.resolve("state"), instance)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar", RepairInputs.hash(broken),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        RepairWorkflow.State state = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(transaction))),
                null, null, List.of());

        StorageReclaim.Plan plan = StorageReclaim.previewBranch(tool, instance, state.id(), () -> { });
        assertFalse(plan.executable());
        assertTrue(plan.reason().contains("生效动作"), plan.reason());
        assertTrue(Files.exists(instance.dir().resolve(".maa-checker/workflows/" + state.id() + ".json")));
    }

    @Test
    void 已还原分支删除前列出全部失去还原点并清理自身事务() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID first = emptyTransaction(tool, instance);
        UUID second = emptyTransaction(tool, instance);
        UUID firstRound = UUID.randomUUID();
        UUID secondRound = UUID.randomUUID();
        RepairWorkflow.State target = writeState(instance, RepairWorkflow.Phase.RESTORED,
                List.of(new RepairWorkflow.Round(firstRound, 1, snapshot, RepairWorkflow.Phase.RESTORED,
                                "第一分支", List.of(), List.of(first), null),
                        new RepairWorkflow.Round(secondRound, 1, snapshot, RepairWorkflow.Phase.RESTORED,
                                "重复轮号也不能串账", List.of(), List.of(second), null)),
                null, null, List.of());
        UUID retainedTransaction = emptyTransaction(tool, instance);
        RepairWorkflow.State retained = writeState(instance, RepairWorkflow.Phase.PAUSED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.PAUSED, List.of(retainedTransaction))),
                null, null, List.of());

        StorageReclaim.Plan plan = StorageReclaim.previewBranch(tool, instance, target.id(), () -> { });
        assertTrue(plan.executable(), plan.reason());
        assertEquals(2, plan.lostPoints().size());
        assertTrue(plan.lostPoints().stream().anyMatch(value -> value.contains(firstRound.toString())));
        assertTrue(plan.lostPoints().stream().anyMatch(value -> value.contains(secondRound.toString())));

        StorageReclaim.deleteBranch(tool, instance, plan, () -> { });
        Path transactions = tool.resolve("state/transactions-v1/" + InstanceIdentity.of(instance).id());
        assertFalse(Files.exists(transactions.resolve(first.toString())));
        assertFalse(Files.exists(transactions.resolve(second.toString())));
        assertTrue(Files.exists(transactions.resolve(retainedTransaction.toString())));
        assertEquals(retained, RepairWorkflow.load(instance, retained.id()));
    }

    @Test
    void 预览后外部修改候选即使恢复时间戳也失败关闭() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        Path orphan = instance.dir().resolve(".maa-checker/objects/" + "b".repeat(64) + ".jar");
        Files.writeString(orphan, "before");
        StorageReclaim.Plan preview = StorageReclaim.previewOrphans(tool, instance, () -> { });
        StorageReclaim.Item item = preview.files().stream()
                .filter(value -> value.path().equals(orphan))
                .findFirst().orElseThrow();

        Files.writeString(orphan, "changed");
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(item.modified()));

        IOException failure = assertThrows(IOException.class,
                () -> StorageReclaim.cleanOrphans(tool, instance, preview, () -> { }));
        assertTrue(failure.getMessage().contains("变化") || failure.getMessage().contains("内容"),
                failure.getMessage());
        assertEquals("changed", Files.readString(orphan));
    }

    @Test
    void 删除被文件系统拒绝时保留候选并允许修复后重试() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        Path orphan = instance.dir().resolve(".maa-checker/objects/" + "e".repeat(64) + ".jar");
        Files.writeString(orphan, "readonly");
        try {
            Files.setAttribute(orphan, "dos:readonly", true);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            Assumptions.assumeTrue(false, "当前文件系统不支持 Windows 只读属性：" + e.getMessage());
            return;
        }
        StorageReclaim.Plan preview = StorageReclaim.previewOrphans(tool, instance, () -> { });

        assertThrows(IOException.class,
                () -> StorageReclaim.cleanOrphans(tool, instance, preview, () -> { }));
        assertTrue(Files.exists(orphan));

        Files.setAttribute(orphan, "dos:readonly", false);
        StorageReclaim.cleanOrphans(tool, instance,
                StorageReclaim.previewOrphans(tool, instance, () -> { }), () -> { });
        assertFalse(Files.exists(orphan));
    }

    @Test
    void 敏感路径不进入孤儿候选且目录链接拒绝回收() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        Path objects = Files.createDirectories(instance.dir().resolve(".maa-checker/objects"));
        Path sensitive = objects.resolve("secrets");
        Files.createDirectories(sensitive);
        Path protectedObject = sensitive.resolve("c".repeat(64) + ".jar");
        Files.writeString(protectedObject, "protected");
        Path unknown = Files.writeString(objects.resolve("unrecognized.tmp"), "unknown");

        StorageReclaim.Plan preview = StorageReclaim.previewOrphans(tool, instance, () -> { });
        assertFalse(preview.files().stream().anyMatch(item -> item.path().equals(protectedObject)));
        assertFalse(preview.files().stream().anyMatch(item -> item.path().equals(unknown)));

        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path link = objects.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileAlreadyExistsException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前文件系统不允许创建目录链接：" + e.getMessage());
            return;
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "当前账户无权创建目录链接：" + e.getMessage());
            return;
        }
        assertThrows(IOException.class, () -> StorageReclaim.previewOrphans(tool, instance, () -> { }));
    }

    @Test
    void 中断残留会在下次预览中继续回收() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID transaction = emptyTransaction(tool, instance);
        RepairWorkflow.State state = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(transaction))),
                null, null, List.of());
        Path visibleWorkflow = instance.dir().resolve(".maa-checker/workflows/" + state.id() + ".json");
        Path hiddenWorkflow = visibleWorkflow.resolveSibling(visibleWorkflow.getFileName() + ".reclaimed");
        Files.move(visibleWorkflow, hiddenWorkflow);
        Path transactions = Files.createDirectories(tool.resolve("state/transactions-v1")
                .resolve(InstanceIdentity.of(instance).id()));
        Path hiddenTransaction = transactions.resolve(".reclaimed-" + transaction);
        Files.move(transactions.resolve(transaction.toString()), hiddenTransaction);

        StorageReclaim.Plan preview = StorageReclaim.previewOrphans(tool, instance, () -> { });
        assertTrue(preview.files().stream().anyMatch(item -> item.path().equals(hiddenWorkflow)));
        assertTrue(preview.files().stream()
                .anyMatch(item -> item.path().startsWith(hiddenTransaction)));
        StorageReclaim.cleanOrphans(tool, instance, preview, () -> { });
        assertFalse(Files.exists(hiddenWorkflow));
        assertFalse(Files.exists(hiddenTransaction));
    }

    @Test
    void 分支删除在暂存后取消会保留残留并由下一次预览继续清理() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID transaction = emptyTransaction(tool, instance);
        RepairWorkflow.State state = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(transaction))),
                null, null, List.of());
        StorageReclaim.Plan preview = StorageReclaim.previewBranch(tool, instance, state.id(), () -> { });
        Path workflow = instance.dir().resolve(".maa-checker/workflows/" + state.id() + ".json");
        Path hiddenWorkflow = workflow.resolveSibling(workflow.getFileName() + ".reclaimed");
        Path transactionDirectory = tool.resolve("state/transactions-v1")
                .resolve(InstanceIdentity.of(instance).id()).resolve(transaction.toString());

        IOException failure = assertThrows(IOException.class, () -> StorageReclaim.deleteBranch(
                tool, instance, preview, () -> {
                    if (!Files.exists(workflow) && Files.exists(hiddenWorkflow)) {
                        throw new CancellationException("fixture cancel");
                    }
                }));
        assertTrue(failure.getMessage().contains("残留"), failure.getMessage());
        assertTrue(Files.exists(hiddenWorkflow));
        assertTrue(Files.exists(transactionDirectory));

        StorageReclaim.Plan residue = StorageReclaim.previewOrphans(tool, instance, () -> { });
        assertTrue(residue.files().stream().anyMatch(item -> item.path().equals(hiddenWorkflow)));
        StorageReclaim.cleanOrphans(tool, instance, residue, () -> { });
        assertFalse(Files.exists(hiddenWorkflow));
        assertFalse(Files.exists(transactionDirectory));
    }

    @Test
    void 删除期间出现未预览文件时保留新文件并报告残留() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID transaction = emptyTransaction(tool, instance);
        RepairWorkflow.State state = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(transaction))),
                null, null, List.of());
        StorageReclaim.Plan preview = StorageReclaim.previewBranch(tool, instance, state.id(), () -> { });
        Path sessionRoot = tool.resolve("state/transactions-v1")
                .resolve(InstanceIdentity.of(instance).id());
        Path hiddenTransaction = sessionRoot.resolve(".reclaimed-" + transaction);
        Path external = hiddenTransaction.resolve("external.new");
        AtomicBoolean injected = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class, () -> StorageReclaim.deleteBranch(
                tool, instance, preview, () -> {
                    if (Files.exists(hiddenTransaction)
                            && injected.compareAndSet(false, true)) {
                        try {
                            Files.writeString(external, "external");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                }));
        assertTrue(failure.getMessage().contains("残留"), failure.getMessage());
        assertTrue(Files.exists(external));
        assertEquals("external", Files.readString(external));
    }

    @Test
    void 删除一个旧分支后保留下来的事务仍可实际恢复文件() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path keep = RepairTasksTest.mod(instance.modsDir().resolve("keep.jar"), "keep");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        UUID retainedTransaction;
        try (var session = TransactionSession.create(tool.resolve("state"), instance)) {
            retainedTransaction = session.header().sessionId();
            session.removeMod("keep", "keep.jar", RepairInputs.hash(keep),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(retainedTransaction))),
                null, null, List.of());
        UUID removable = emptyTransaction(tool, instance);
        RepairWorkflow.State old = writeState(instance, RepairWorkflow.Phase.VERIFIED,
                List.of(round(1, snapshot, RepairWorkflow.Phase.VERIFIED, List.of(removable))),
                null, null, List.of());

        StorageReclaim.deleteBranch(tool, instance,
                StorageReclaim.previewBranch(tool, instance, old.id(), () -> { }), () -> { });
        assertFalse(Files.exists(keep));

        try (var session = TransactionSession.resume(tool.resolve("state"), instance, retainedTransaction)) {
            session.restoreAll();
        }
        assertTrue(Files.exists(keep));
    }

    private UUID emptyTransaction(Path tool, GameInstance instance) throws Exception {
        try (var session = TransactionSession.create(tool.resolve("state"), instance)) {
            return session.header().sessionId();
        }
    }

    private UUID unresolvedRemoval(Path tool, GameInstance instance, Path broken) throws Exception {
        try (var session = TransactionSession.create(tool.resolve("state"), instance, (point, action) -> {
            if (point == TransactionSession.Point.AFTER_INTENT) throw new IOException("fixture crash");
        })) {
            UUID id = session.header().sessionId();
            assertThrows(IOException.class, () -> session.removeMod("broken", "broken.jar",
                    RepairInputs.hash(broken), 1, "fixture removal", List.of("fixture evidence")));
            return id;
        }
    }

    private RepairWorkflow.State writeState(GameInstance instance, RepairWorkflow.Phase phase,
                                             List<RepairWorkflow.Round> rounds, UUID receipt,
                                             Integer restoreTarget, List<UUID> receipts) throws Exception {
        RepairWorkflow.State state = new RepairWorkflow.State(1, UUID.randomUUID(),
                InstanceIdentity.of(instance), System.nanoTime(), RepairPolicy.defaults(), 0,
                phase, "fixture", rounds, null, receipt, restoreTarget, receipts);
        RoundSnapshot.writeJournal(instance, state.id() + ".json", state);
        return state;
    }

    private static RepairWorkflow.Round round(int number, RoundSnapshot snapshot,
                                               RepairWorkflow.Phase phase, List<UUID> transactions) {
        return new RepairWorkflow.Round(UUID.randomUUID(), number, snapshot, phase,
                "fixture round " + number, List.of(), transactions, null);
    }

    private static String suffix(String key) {
        return key.equals("version") ? ".json" : key.equals("marker") ? ".cfg" : ".jar";
    }

    private static String suffixForKey(RoundSnapshot snapshot, String hash) {
        for (Map.Entry<String, String> entry : snapshot.inputs().hashes().entrySet()) {
            if (entry.getValue().equals(hash)) return suffix(entry.getKey());
        }
        throw new IllegalArgumentException(hash);
    }
}
