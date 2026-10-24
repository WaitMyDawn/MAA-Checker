package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.core.RunJournal;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StorageInventoryTest {
    @Test
    void 分类统计区分可清理候选受保护与逻辑去重(@TempDir Path temp) throws Exception {
        Path game = temp.resolve("game");
        var instance = RepairTasksTest.fixture(game, "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        Path tool = temp.resolve("tool");
        String report = tool.resolve("logs").resolve(RunJournal.safeName(instance.name()))
                .resolve("run").resolve("report.txt").toString();
        Files.createDirectories(Path.of(report).getParent());
        Files.writeString(Path.of(report), "report");
        state(instance, snapshot, RepairWorkflow.Phase.PAUSED, report);
        state(instance, snapshot, RepairWorkflow.Phase.VERIFIED, report);

        Path preparation = Files.createDirectories(
                instance.dir().resolve(".maa-checker/preparation/prepare-fixture"));
        Files.writeString(preparation.resolve("artifact.jar"), "prepared");
        Path orphan = instance.dir().resolve(".maa-checker/objects/" + "a".repeat(64) + ".jar");
        Files.write(orphan, new byte[4096]);
        Files.createDirectories(tool.resolve("state"));
        Files.writeString(tool.resolve("state/history-index-v1.json"), "{}");
        Files.writeString(tool.resolve("config.properties"), "game.dir=fixture\n");

        Path candidate = RepairTasksTest.mod(temp.resolve("candidate/new.jar"), "newdep");
        try (var session = TransactionSession.create(tool.resolve("state"), instance)) {
            session.installMod("newdep", "new.jar", candidate, RepairInputs.hash(candidate),
                    1, "fixture", List.of("fixture"));
        }

        StorageInventory.Snapshot inventory = StorageInventory.inspect(tool, instance);
        StorageInventory.Category objects = category(inventory, "snapshots");
        StorageInventory.Category workflows = category(inventory, "workflows");
        StorageInventory.Category clean = category(inventory, "preparation");
        StorageInventory.Category reports = category(inventory, "reports");
        StorageInventory.Category transactions = category(inventory, "transactions");

        assertEquals(StorageInventory.State.CLEANABLE, clean.state());
        assertEquals("prepared".length(), clean.cleanableBytes());
        assertTrue(objects.logicalBytes() > objects.protectedBytes(),
                "同一对象被两个轮次引用时，逻辑引用量应大于对应物理占用");
        assertTrue(objects.protectedBytes() > 0);
        assertTrue(objects.candidateBytes() >= 4096);
        assertEquals(StorageInventory.State.PROTECTED, workflows.state());
        assertEquals(StorageInventory.State.PROTECTED, reports.state());
        assertTrue(transactions.protectedBytes() > 0);
        assertEquals(inventory.diskBytes(),
                inventory.categories().stream().mapToLong(StorageInventory.Category::diskBytes).sum());
        assertTrue(inventory.issues().isEmpty(), inventory.issues().toString());
    }

    @Test
    void 损坏台账使未引用对象降级为未知且敏感文件不可回收(@TempDir Path temp) throws Exception {
        var instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        state(instance, snapshot, RepairWorkflow.Phase.PAUSED, null);
        Files.writeString(instance.dir().resolve(".maa-checker/workflows/broken.json"), "{broken");
        Path orphan = instance.dir().resolve(".maa-checker/objects/" + "b".repeat(64) + ".jar");
        Files.write(orphan, new byte[256]);
        Path sensitive = instance.dir().resolve(".maa-checker/objects/.env");
        Files.writeString(sensitive, "not-read");

        StorageInventory.Snapshot inventory = StorageInventory.inspect(temp.resolve("tool"), instance);
        StorageInventory.Category objects = category(inventory, "snapshots");

        assertEquals(StorageInventory.State.UNKNOWN, objects.state());
        assertTrue(objects.unknownBytes() >= 256);
        assertFalse(inventory.issues().isEmpty());
    }

    private static RepairWorkflow.State state(yagen.waitmydawn.checker.core.GameInstance instance,
                                               RoundSnapshot snapshot, RepairWorkflow.Phase phase,
                                               String report) throws Exception {
        UUID workflow = UUID.randomUUID();
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, snapshot, phase,
                "fixture", List.of(), List.of(), report);
        RepairWorkflow.State state = new RepairWorkflow.State(1, workflow,
                InstanceIdentity.of(instance), System.currentTimeMillis(),
                RepairPolicy.defaults(), 0, phase, "fixture", List.of(round),
                null, null, null, List.of());
        RoundSnapshot.writeJournal(instance, workflow + ".json", state);
        return state;
    }

    private static StorageInventory.Category category(StorageInventory.Snapshot snapshot, String id) {
        return snapshot.categories().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
