package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceScanner;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryIntegrityTest {
    @TempDir Path temp;

    @Test
    void 移动实例显式重新关联后历史读取且保留事务仍可实际还原() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(original);
        Path tool = temp.resolve("tool");
        UUID transaction;
        try (var session = TransactionSession.create(tool.resolve("state"), original)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar", RepairInputs.hash(broken),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        RepairWorkflow.State state = writeState(original, snapshot, transaction);
        InstanceIdentity oldIdentity = InstanceIdentity.of(original);
        WorkflowHistoryIndex.remember(tool, List.of(original));
        assertFalse(Files.exists(broken));

        Path movedPath = original.dir().resolveSibling("renamed-instance");
        Files.move(original.dir(), movedPath);
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);
        assertNotNull(moved);
        InstanceIdentity newIdentity = InstanceIdentity.of(moved);

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(tool, moved);
        assertTrue(report.needsReassociation());
        assertEquals(1, report.movedWorkflows());
        assertEquals(0, report.missingObjects());
        RecoveryIntegrity.Candidate candidate = report.candidates().getFirst();
        assertTrue(candidate.executable(), candidate.reason());
        assertEquals(oldIdentity, candidate.source());
        assertEquals(List.of(state.id()), candidate.workflows());
        assertEquals(List.of(transaction), candidate.transactions());
        assertThrows(IOException.class, () -> RepairWorkflow.history(moved));

        RecoveryIntegrity.Plan preview = RecoveryIntegrity.preview(tool, moved,
                oldIdentity.id(), () -> { });
        assertTrue(preview.executable(), preview.reason());
        RecoveryIntegrity.Result result = RecoveryIntegrity.reassociate(tool, moved, preview, () -> { });
        assertEquals(1, result.workflows());
        assertEquals(1, result.copiedTransactions());

        RepairWorkflow.State reopened = RepairWorkflow.history(moved).getFirst();
        assertEquals(newIdentity, reopened.instance());
        assertEquals(moved.versionJson().toRealPath().toString(),
                reopened.round().before().inputs().versionFile());
        assertEquals(moved.modsDir().toRealPath().toString(),
                reopened.round().before().inputs().modsDirectory());
        RecoveryLineage lineage = RecoveryLineage.read(moved).orElseThrow();
        assertEquals(newIdentity.id(), lineage.identityId());
        assertTrue(lineage.previousIdentityIds().contains(oldIdentity.id()));

        var migratedHistory = TransactionSession.history(tool.resolve("state"), newIdentity);
        assertEquals(1, migratedHistory.size());
        assertEquals(newIdentity.id(), migratedHistory.getFirst().actions().getFirst().instanceId());
        assertTrue(Files.exists(tool.resolve("state/transactions-v1/" + oldIdentity.id())));

        try (var session = TransactionSession.resume(tool.resolve("state"), moved, transaction)) {
            session.restoreAll();
        }
        assertTrue(Files.exists(moved.modsDir().resolve("broken.jar")));
        assertFalse(WorkflowHistoryIndex.load(tool, original.gameRoot()).entries().stream()
                .anyMatch(entry -> entry.status() == WorkflowHistoryIndex.Status.MOVED));
        assertTrue(WorkflowHistoryIndex.load(tool, original.gameRoot()).entries().stream()
                .anyMatch(entry -> entry.workflowId().equals(state.id())
                        && entry.status() == WorkflowHistoryIndex.Status.AVAILABLE));
    }

    @Test
    void 同名副本在旧位置仍可用时拒绝自动改绑() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("old-game"), "repairable");
        RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RepairWorkflow.State state = writeState(original, RoundSnapshot.capture(original), null);
        GameInstance copy = RepairTasksTest.fixture(temp.resolve("new-game"), "repairable");
        copyDirectory(original.dir().resolve(".maa-checker"),
                copy.dir().resolve(".maa-checker"));

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(temp.resolve("tool"), copy);
        RecoveryIntegrity.Candidate candidate = report.candidates().getFirst();
        assertFalse(candidate.executable());
        assertTrue(candidate.reason().contains("旧实例目录仍存在"), candidate.reason());
        assertTrue(Files.exists(copy.dir().resolve(".maa-checker/workflows/" + state.id() + ".json")));
        assertThrows(IOException.class, () -> RepairWorkflow.history(copy));
    }

    @Test
    void 事务账本缺失时提供阻断原因而不迁移() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(original);
        Path tool = temp.resolve("tool");
        UUID transaction;
        try (var session = TransactionSession.create(tool.resolve("state"), original)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar", RepairInputs.hash(broken),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        RepairWorkflow.State state = writeState(original, snapshot, transaction);
        InstanceIdentity old = InstanceIdentity.of(original);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        deleteTree(tool.resolve("state/transactions-v1/" + old.id()));
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(tool, moved);
        RecoveryIntegrity.Candidate candidate = report.candidates().getFirst();
        assertFalse(candidate.executable());
        assertTrue(candidate.reason().contains("缺少事务账本"), candidate.reason());
        RecoveryIntegrity.Plan plan = RecoveryIntegrity.preview(tool, moved,
                old.id(), () -> { });
        assertFalse(plan.executable());
        assertThrows(IOException.class,
                () -> RecoveryIntegrity.reassociate(tool, moved, plan, () -> { }));
        assertTrue(Files.exists(moved.dir().resolve(".maa-checker/workflows/" + state.id() + ".json")));
    }

    @Test
    void 轮前对象缺失时不声称恢复点可用() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(original);
        RepairWorkflow.State state = writeState(original, snapshot, null);
        String removedHash = snapshot.inputs().hashes().get("mod/broken.jar");
        Path object = original.dir().resolve(".maa-checker/objects/" + removedHash + ".jar");
        InstanceIdentity old = InstanceIdentity.of(original);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        Path movedObject = movedPath.resolve(".maa-checker/objects/" + removedHash + ".jar");
        Files.delete(movedObject);
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(temp.resolve("tool"), moved);
        RecoveryIntegrity.Candidate candidate = report.candidates().getFirst();
        assertFalse(candidate.executable());
        assertTrue(candidate.reason().contains("快照对象缺失"), candidate.reason());
        assertTrue(report.missingObjects() > 0);
        assertTrue(Files.exists(movedPath.resolve(".maa-checker/workflows/" + state.id() + ".json")));
    }

    @Test
    void 工作流损坏时给出错误报告且不存在猜测迁移入口() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairWorkflow.State state = writeState(original, RoundSnapshot.capture(original), null);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        Files.writeString(movedPath.resolve(".maa-checker/workflows/" + state.id() + ".json"),
                "{broken");
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(temp.resolve("tool"), moved);
        assertTrue(report.corruptWorkflows() > 0);
        assertTrue(report.hasErrors());
        assertTrue(report.candidates().isEmpty());
        assertTrue(report.findings().stream()
                .anyMatch(value -> value.code().equals("WORKFLOW_CORRUPT")));
    }

    @Test
    void 事务已迁移但工作流未迁移时可幂等复用并继续() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(original);
        Path tool = temp.resolve("tool");
        UUID transaction;
        try (var session = TransactionSession.create(tool.resolve("state"), original)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar", RepairInputs.hash(broken),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        RepairWorkflow.State state = writeState(original, snapshot, transaction);
        InstanceIdentity old = InstanceIdentity.of(original);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);
        InstanceIdentity current = InstanceIdentity.of(moved);
        TransactionSession.reassociate(tool.resolve("state"), old, current,
                List.of(transaction), () -> { });
        deleteTree(tool.resolve("state/transactions-v1/" + old.id()));

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(tool, moved);
        assertTrue(report.candidates().getFirst().executable(), report.findings().toString());
        RecoveryIntegrity.Plan preview = RecoveryIntegrity.preview(tool, moved,
                old.id(), () -> { });
        RecoveryIntegrity.Result result = RecoveryIntegrity.reassociate(tool, moved, preview, () -> { });
        assertEquals(0, result.copiedTransactions());
        assertEquals(1, result.reusedTransactions());
        assertEquals(state.id(), RepairWorkflow.history(moved).getFirst().id());
    }

    @Test
    void 报告明确说明轮前快照不包含世界普通配置和共享库() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(temp.resolve("tool"), instance);
        assertTrue(report.healthy());
        assertEquals(3, report.snapshotScope().size());
        assertTrue(String.join(" ", report.snapshotScope()).contains("世界与存档"));
        assertTrue(String.join(" ", report.snapshotScope()).contains("共享 libraries"));
    }

    @Test
    void 没有工作流时仍可通过谱系识别移动并显式更新绑定() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        InstanceIdentity old = InstanceIdentity.of(original);
        RecoveryLineage first = RecoveryLineage.ensure(original, old);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);
        InstanceIdentity current = InstanceIdentity.of(moved);
        assertThrows(IOException.class, () -> RecoveryLineage.ensure(moved, current));

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(temp.resolve("tool"), moved);
        RecoveryIntegrity.Candidate candidate = report.candidates().getFirst();
        assertTrue(candidate.executable(), candidate.reason());
        assertTrue(candidate.workflows().isEmpty());
        RecoveryIntegrity.Plan preview = RecoveryIntegrity.preview(
                temp.resolve("tool"), moved, old.id(), () -> { });
        RecoveryIntegrity.reassociate(temp.resolve("tool"), moved, preview, () -> { });

        RecoveryLineage updated = RecoveryLineage.read(moved).orElseThrow();
        assertEquals(first.recoveryId(), updated.recoveryId());
        assertEquals(current.id(), updated.identityId());
        assertTrue(RepairWorkflow.history(moved).isEmpty());
    }

    @Test
    void 事务账本损坏时返回完整性报告而不是抛出后丢失诊断() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(original);
        Path tool = temp.resolve("tool");
        UUID transaction;
        try (var session = TransactionSession.create(tool.resolve("state"), original)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar", RepairInputs.hash(broken),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        writeState(original, snapshot, transaction);
        InstanceIdentity old = InstanceIdentity.of(original);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        Files.writeString(tool.resolve("state/transactions-v1/" + old.id() + "/"
                + transaction + "/session.json"), "{broken");
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(tool, moved);
        assertTrue(report.hasErrors());
        assertTrue(report.findings().stream()
                .anyMatch(value -> value.code().equals("TRANSACTION_CORRUPT")));
        assertFalse(report.candidates().getFirst().executable());
        assertTrue(report.candidates().getFirst().reason().contains("事务账本"),
                report.candidates().getFirst().reason());
    }

    @Test
    void 目标已有同ID但内容不同的事务时阻断重新关联() throws Exception {
        GameInstance original = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(original.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(original);
        Path tool = temp.resolve("tool");
        UUID transaction;
        try (var session = TransactionSession.create(tool.resolve("state"), original)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar", RepairInputs.hash(broken),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        writeState(original, snapshot, transaction);
        InstanceIdentity old = InstanceIdentity.of(original);
        Path movedPath = original.dir().resolveSibling("moved");
        Files.move(original.dir(), movedPath);
        GameInstance moved = InstanceScanner.scanOne(original.gameRoot(), movedPath);
        InstanceIdentity current = InstanceIdentity.of(moved);
        TransactionSession.reassociate(tool.resolve("state"), old, current,
                List.of(transaction), () -> { });
        Path targetSession = tool.resolve("state/transactions-v1/" + current.id() + "/" + transaction);
        Path actionFile;
        try (var files = Files.list(targetSession)) {
            actionFile = files.filter(path -> path.getFileName().toString().endsWith(".json")
                            && !path.getFileName().toString().equals("session.json"))
                    .findFirst().orElseThrow();
        }
        var json = (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllBytes(actionFile));
        json.put("reason", "different target evidence");
        Files.write(actionFile, new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsBytes(json));

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(tool, moved);
        RecoveryIntegrity.Candidate candidate = report.candidates().getFirst();
        assertFalse(candidate.executable());
        assertTrue(candidate.reason().contains("内容不同"), candidate.reason());
    }

    @Test
    void 当前身份轮前对象缺失也进入完整性报告() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        writeState(instance, snapshot, null);
        String hash = snapshot.inputs().hashes().get("mod/broken.jar");
        Files.delete(instance.dir().resolve(".maa-checker/objects/" + hash + ".jar"));

        RecoveryIntegrity.Report report = RecoveryIntegrity.inspect(temp.resolve("tool"), instance);
        assertFalse(report.healthy());
        assertTrue(report.missingObjects() > 0);
        assertTrue(report.findings().stream()
                .anyMatch(value -> value.code().equals("CURRENT_OBJECT_MISSING")));
    }

    private static RepairWorkflow.State writeState(GameInstance instance, RoundSnapshot snapshot,
                                                    UUID transaction) throws Exception {
        UUID workflow = UUID.randomUUID();
        RepairWorkflow.Round round = new RepairWorkflow.Round(UUID.randomUUID(), 1, snapshot,
                RepairWorkflow.Phase.PAUSED, "fixture moved instance",
                List.of(), transaction == null ? List.of() : List.of(transaction), "");
        RepairWorkflow.State state = new RepairWorkflow.State(1, workflow,
                InstanceIdentity.of(instance), System.currentTimeMillis(), RepairPolicy.defaults(),
                0, RepairWorkflow.Phase.PAUSED, "fixture moved instance", List.of(round),
                null, null, null, List.of());
        RoundSnapshot.writeJournal(instance, workflow + ".json", state);
        return state;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(
                    Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(
                    Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
