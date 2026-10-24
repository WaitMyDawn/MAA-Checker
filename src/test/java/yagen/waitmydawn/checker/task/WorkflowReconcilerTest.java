package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.RepairInputs;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务账本与轮次事件对账：事务是事实来源，事件是投影；补齐必须幂等且不执行文件动作。
 */
class WorkflowReconcilerTest {

    @TempDir Path temp;

    @Test
    void 已提交但未写入事件的动作为补齐一次且重复对账不再追加() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID transaction;
        try (var session = TransactionSession.create(stateRoot, instance)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar",
                    RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(transaction), null);
        var state = state(instance, List.of(round));

        WorkflowReconciler.Result first = WorkflowReconciler.reconcile(instance, stateRoot, state, Set.of());
        assertEquals(0, first.issues().size(), first.issues().toString());
        assertEquals(1, first.appendedEvents());
        var action = first.state().round().events().getFirst().committed();
        assertEquals("broken.jar", action.filename());
        assertEquals(ActionRecord.State.APPLIED, action.state());

        WorkflowReconciler.Result second = WorkflowReconciler.reconcile(instance, stateRoot,
                first.state(), Set.of());
        assertEquals(0, second.appendedEvents());
        assertEquals(1, second.state().round().events().size());
    }

    @Test
    void 缺少事务账本时上报而不是静默成功() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID missing = UUID.randomUUID();
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(missing), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, temp.resolve("state"),
                state(instance, List.of(round)), Set.of());

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("缺少事务账本")),
                result.issues().toString());
        assertEquals(0, result.appendedEvents());
    }

    @Test
    void 有文件动作但未关联到轮次的事务必须上报() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        try (var session = TransactionSession.create(stateRoot, instance)) {
            session.removeMod("broken", "broken.jar",
                    RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        RoundSnapshot before = RoundSnapshot.capture(instance);
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot,
                state(instance, List.of(round)), Set.of());

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("事务未关联")),
                result.issues().toString());
    }

    @Test
    void 未完成动作上报且不伪造事件() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID transaction;
        try (var session = TransactionSession.create(stateRoot, instance, (point, action) -> {
            if (point == TransactionSession.Point.AFTER_INTENT) throw new IOException("fixture crash");
        })) {
            transaction = session.header().sessionId();
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> session.removeMod(
                    "broken", "broken.jar", RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    1, "fixture removal", List.of("fixture evidence")));
        }
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(transaction), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot,
                state(instance, List.of(round)), Set.of());

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("未完成动作")),
                result.issues().toString());
        assertEquals(0, result.appendedEvents());
        assertTrue(result.state().round().events().isEmpty());
    }

    @Test
    void 空事务不视为未关联噪点() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path stateRoot = temp.resolve("state");
        try (var ignored = TransactionSession.create(stateRoot, instance)) {
            // 会话在首次文件动作前终止：没有事实，也没有事件。
        }
        RoundSnapshot before = RoundSnapshot.capture(instance);
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot,
                state(instance, List.of(round)), Set.of());

        assertEquals(0, result.issues().size(), result.issues().toString());
    }

    @Test
    void 同一事务重复关联时停止对账() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID transaction;
        try (var session = TransactionSession.create(stateRoot, instance)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar",
                    RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(transaction, transaction), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot,
                state(instance, List.of(round)), Set.of());

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("动作 ID 重复")),
                result.issues().toString());
        assertEquals(1, result.appendedEvents());
    }

    @Test
    void 动作轮次与所属轮次不符时停止对账() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID transaction;
        try (var session = TransactionSession.create(stateRoot, instance)) {
            transaction = session.header().sessionId();
            session.removeMod("broken", "broken.jar",
                    RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    2, "fixture removal", List.of("fixture evidence"));
        }
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(), List.of(transaction), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot,
                state(instance, List.of(round)), Set.of());

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("与第 1 轮不符")),
                result.issues().toString());
    }

    @Test
    void 事件引用未关联动作时停止对账() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID transaction;
        ActionRecord action;
        try (var session = TransactionSession.create(stateRoot, instance)) {
            transaction = session.header().sessionId();
            action = session.removeMod("broken", "broken.jar",
                    RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    1, "fixture removal", List.of("fixture evidence"));
        }
        var event = new RepairWorkflow.Event("broken.jar", "已提交", "fixture", action);
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.PAUSED, "fixture", List.of(event), List.of(), null);

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot,
                state(instance, List.of(round)), Set.of(transaction));

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("事件引用了未知动作")),
                result.issues().toString());
    }

    @Test
    void 恢复凭据中的未完成动作也必须停止对账() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path stateRoot = temp.resolve("state");
        RoundSnapshot before = RoundSnapshot.capture(instance);
        UUID receipt;
        try (var session = TransactionSession.create(stateRoot, instance, (point, action) -> {
            if (point == TransactionSession.Point.AFTER_INTENT) throw new IOException("fixture crash");
        })) {
            receipt = session.header().sessionId();
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> session.removeMod(
                    "broken", "broken.jar", RepairInputs.hash(instance.modsDir().resolve("broken.jar")),
                    1, "fixture removal", List.of("fixture evidence")));
        }
        var round = new RepairWorkflow.Round(UUID.randomUUID(), 1, before,
                RepairWorkflow.Phase.RESTORING, "fixture", List.of(), List.of(), null);
        var state = new RepairWorkflow.State(1, UUID.randomUUID(), InstanceIdentity.of(instance),
                System.currentTimeMillis(), RepairPolicy.defaults(), 0, RepairWorkflow.Phase.RESTORING,
                "fixture", List.of(round), null, receipt, null, List.of(receipt));

        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot, state, Set.of());

        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("恢复凭据存在未完成动作")),
                result.issues().toString());
        assertTrue(result.state().round().events().isEmpty());
    }

    private static RepairWorkflow.State state(GameInstance instance, List<RepairWorkflow.Round> rounds)
            throws IOException {
        return new RepairWorkflow.State(1, UUID.randomUUID(), InstanceIdentity.of(instance),
                System.currentTimeMillis(), RepairPolicy.defaults(), 0, RepairWorkflow.Phase.PAUSED,
                "fixture", rounds, null, null, null, List.of());
    }
}
