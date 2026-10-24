package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowHistoryIndexTest {
    @Test
    void 有效会话与损坏记录分开且损坏记录不遮蔽其他会话(@TempDir Path temp) throws Exception {
        Path game = temp.resolve("game");
        var instance = RepairTasksTest.fixture(game, "repairable");
        RepairWorkflow.State paused = state(instance, RepairWorkflow.Phase.PAUSED,
                System.currentTimeMillis() - 1_000, "等待用户决定");
        RepairWorkflow.State verified = state(instance, RepairWorkflow.Phase.VERIFIED,
                System.currentTimeMillis(), "修复后启动检验通过");
        RoundSnapshot.writeJournal(instance, paused.id() + ".json", paused);
        RoundSnapshot.writeJournal(instance, verified.id() + ".json", verified);
        Files.writeString(instance.dir().resolve(".maa-checker/workflows/broken.json"), "{broken");

        Path tool = temp.resolve("tool");
        WorkflowHistoryIndex.remember(tool, List.of(instance));
        WorkflowHistoryIndex.Snapshot snapshot = WorkflowHistoryIndex.load(tool, game);

        assertEquals(2, snapshot.entries().stream()
                .filter(e -> e.status() == WorkflowHistoryIndex.Status.AVAILABLE).count());
        assertEquals(1, snapshot.entries().stream()
                .filter(e -> e.status() == WorkflowHistoryIndex.Status.CORRUPT).count());
        WorkflowHistoryIndex.Entry open = snapshot.entries().stream()
                .filter(e -> paused.id().equals(e.workflowId())).findFirst().orElseThrow();
        assertEquals(RepairWorkflow.Phase.PAUSED, open.phase());
        assertTrue(open.unfinished());
        assertEquals(1, open.rounds());
        assertEquals(instance.dir(), open.instanceDirectory());
        assertTrue(open.canOpen());
        assertFalse(snapshot.problems().isEmpty());
    }

    @Test
    void 实例移动会显示旧路径不可用且指出新位置但不自动改绑(@TempDir Path temp) throws Exception {
        Path game = temp.resolve("game");
        var instance = RepairTasksTest.fixture(game, "repairable");
        RepairWorkflow.State session = state(instance, RepairWorkflow.Phase.PAUSED,
                System.currentTimeMillis(), "等待继续");
        RoundSnapshot.writeJournal(instance, session.id() + ".json", session);
        Path tool = temp.resolve("tool");
        WorkflowHistoryIndex.remember(tool, List.of(instance));

        Path moved = instance.dir().resolveSibling("renamed-instance");
        Files.move(instance.dir(), moved);
        WorkflowHistoryIndex.Snapshot snapshot = WorkflowHistoryIndex.load(tool, game);

        WorkflowHistoryIndex.Entry old = snapshot.entries().stream()
                .filter(e -> e.status() == WorkflowHistoryIndex.Status.MOVED).findFirst().orElseThrow();
        assertEquals("fixture", old.instanceName());
        assertTrue(old.problem().contains("renamed-instance"));
        assertFalse(old.canOpen());
        assertTrue(snapshot.entries().stream()
                .anyMatch(e -> e.status() == WorkflowHistoryIndex.Status.CORRUPT
                        && e.instanceName().equals("renamed-instance")));
    }

    @Test
    void 路径彻底不可用时不伪造成功记录(@TempDir Path temp) throws Exception {
        Path game = temp.resolve("game");
        var instance = RepairTasksTest.fixture(game, "repairable");
        Path tool = temp.resolve("tool");
        WorkflowHistoryIndex.remember(tool, List.of(instance));
        Path outside = temp.resolve("moved-away");
        Files.move(instance.dir(), outside);

        WorkflowHistoryIndex.Snapshot snapshot = WorkflowHistoryIndex.load(tool, game);
        WorkflowHistoryIndex.Entry unavailable = snapshot.entries().stream()
                .filter(e -> e.status() == WorkflowHistoryIndex.Status.UNAVAILABLE)
                .findFirst().orElseThrow();
        assertEquals("fixture", unavailable.instanceName());
        assertFalse(unavailable.canOpen());
        assertEquals(0, unavailable.rounds());
    }

    private static RepairWorkflow.State state(yagen.waitmydawn.checker.core.GameInstance instance,
                                               RepairWorkflow.Phase phase, long createdAt,
                                               String message) throws Exception {
        UUID workflow = UUID.randomUUID();
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, RoundSnapshot.capture(instance),
                phase, message, List.of(), List.of(), "");
        return new RepairWorkflow.State(1, workflow, InstanceIdentity.of(instance), createdAt,
                RepairPolicy.defaults(), 0, phase, message, List.of(round),
                null, null, null, List.of());
    }
}
