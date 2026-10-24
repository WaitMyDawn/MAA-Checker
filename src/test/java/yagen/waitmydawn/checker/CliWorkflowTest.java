package yagen.waitmydawn.checker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.VersionUpdateStrategy;
import yagen.waitmydawn.checker.task.GlobalRepairPolicy;
import yagen.waitmydawn.checker.task.RepairPolicy;
import yagen.waitmydawn.checker.task.RepairWorkflow;
import yagen.waitmydawn.checker.task.RepairTasksTest;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskSnapshot;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CliWorkflowTest {
    @TempDir Path temp;

    @Test
    void 无保存策略时使用全局全自动十轮默认值() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var resolved = CliWorkflow.resolvePolicy(instance, temp.resolve("tool"),
                CliWorkflow.PolicyChoice.SAVED, 4, false, false);
        assertFalse(resolved.saved());
        assertEquals(10, resolved.policy().maxRounds());
        assertTrue(resolved.policy().modes().values().stream()
                .allMatch(mode -> mode == RepairPolicy.Mode.AUTO));
    }

    @Test
    void 显式AUTO覆盖全部分类且轮次参数显式覆盖() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var resolved = CliWorkflow.resolvePolicy(instance, temp.resolve("tool"),
                CliWorkflow.PolicyChoice.AUTO, 7, true, false);
        assertEquals(7, resolved.policy().maxRounds());
        assertFalse(resolved.saved());
        assertTrue(resolved.policy().modes().values().stream()
                .allMatch(mode -> mode == RepairPolicy.Mode.AUTO));
    }

    @Test
    void 显式模式未指定轮次时沿用全局十轮默认值() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var resolved = CliWorkflow.resolvePolicy(instance, temp.resolve("tool"),
                CliWorkflow.PolicyChoice.AUTO, 4, false, false);
        assertEquals(10, resolved.policy().maxRounds());
    }

    @Test
    void 已保存策略被读取且未显式指定轮次时保留保存值() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        java.util.EnumMap<RepairPolicy.Category, RepairPolicy.Mode> modes =
                new java.util.EnumMap<>(RepairPolicy.Category.class);
        for (RepairPolicy.Category category : RepairPolicy.Category.values()) {
            modes.put(category, RepairPolicy.Mode.AUTO);
        }
        modes.put(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.STOP);
        RepairPolicy saved = new RepairPolicy(modes, 6);
        Path tool = temp.resolve("tool");
        try (var tasks = new yagen.waitmydawn.checker.task.TaskService()) {
            assertEquals(TaskSnapshot.State.SUCCEEDED,
                    tasks.submit("policy", GlobalRepairPolicy.save(tool, saved), null)
                            .completion().toCompletableFuture().get().state());
        }

        var resolved = CliWorkflow.resolvePolicy(instance, tool,
                CliWorkflow.PolicyChoice.SAVED, 4, false, false);
        assertTrue(resolved.saved());
        assertEquals(saved, resolved.policy());
        GameInstance second = RepairTasksTest.fixture(temp.resolve("second"), "repairable");
        assertEquals(saved, CliWorkflow.resolvePolicy(second, tool,
                CliWorkflow.PolicyChoice.SAVED, 4, false, false).policy());
    }

    @Test
    void 显式轮次覆盖不会丢失已保存的版本更新策略() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        java.util.EnumMap<RepairPolicy.Category, RepairPolicy.Mode> modes =
                new java.util.EnumMap<>(RepairPolicy.Category.class);
        for (RepairPolicy.Category category : RepairPolicy.Category.values()) {
            modes.put(category, RepairPolicy.Mode.AUTO);
        }
        RepairPolicy saved = new RepairPolicy(modes, 6, VersionUpdateStrategy.CHOOSE);
        Path tool = temp.resolve("tool");
        try (var tasks = new yagen.waitmydawn.checker.task.TaskService()) {
            tasks.submit("policy", GlobalRepairPolicy.save(tool, saved), null)
                    .completion().toCompletableFuture().get();
        }

        var resolved = CliWorkflow.resolvePolicy(instance, tool,
                CliWorkflow.PolicyChoice.SAVED, 8, true, false);

        assertEquals(VersionUpdateStrategy.CHOOSE, resolved.policy().versionStrategy());
        assertEquals(8, resolved.policy().maxRounds());
    }

    @Test
    void 非交互ASK在开始修复前明确拒绝() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        IOException failure = assertThrows(IOException.class, () ->
                CliWorkflow.resolvePolicy(instance, temp.resolve("tool"),
                        CliWorkflow.PolicyChoice.ASK, 4, false, false));
        assertTrue(failure.getMessage().contains("非交互"));
        assertTrue(failure.getMessage().contains("--policy auto"));
        assertFalse(java.nio.file.Files.exists(
                instance.dir().resolve(".maa-checker/workflows")));
    }

    @Test
    void 非交互STOP是确定策略且可进入任务() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var resolved = CliWorkflow.resolvePolicy(instance, temp.resolve("tool"),
                CliWorkflow.PolicyChoice.STOP, 4, false, false);
        assertTrue(resolved.policy().modes().values().stream()
                .allMatch(mode -> mode == RepairPolicy.Mode.STOP));
    }

    @Test
    void 新任务退出码区分成功待处理失败与取消() {
        assertEquals(0, CliWorkflow.exitCode(TaskResult.success(null, "ok")));
        assertEquals(2, CliWorkflow.exitCode(new TaskResult<>(
                TaskSnapshot.State.NEEDS_ATTENTION, null, "attention", null)));
        assertEquals(2, CliWorkflow.exitCode(TaskResult.failure("failed", null)));
        assertEquals(130, CliWorkflow.exitCode(new TaskResult<>(
                TaskSnapshot.State.CANCELLED, null, "cancelled", null)));
    }

    @Test
    void 默认选择最新工作流且可显式选择UUID() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        RepairWorkflow.State older = state(instance, snapshot, "older", System.currentTimeMillis() - 1000);
        RepairWorkflow.State newer = state(instance, snapshot, "newer", System.currentTimeMillis());
        RoundSnapshot.writeJournal(instance, older.id() + ".json", older);
        RoundSnapshot.writeJournal(instance, newer.id() + ".json", newer);

        assertEquals(newer.id(), CliWorkflow.selectHistory(instance, null).id());
        assertEquals(older.id(), CliWorkflow.selectHistory(instance, older.id()).id());
        assertThrows(IOException.class,
                () -> CliWorkflow.selectHistory(instance, UUID.randomUUID()));
    }

    @Test
    void 命令行帮助与参数组合返回确定退出码() throws Exception {
        assertEquals(0, CheckerCli.run(new String[]{"--help"}));
        assertEquals(2, CheckerCli.run(new String[]{"--check", "fixture", "--policy", "auto"}));
        assertEquals(2, CheckerCli.run(new String[]{"--check", "fixture",
                "--workflow", UUID.randomUUID().toString()}));
        assertEquals(2, CheckerCli.run(new String[]{"--fix", "fixture", "--policy", "unknown"}));
    }

    @Test
    void 命令行fix走新版工作流而非旧台账() throws Exception {
        Path game = temp.resolve("game");
        GameInstance instance = RepairTasksTest.fixture(game, "repairable");
        RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path tool = temp.resolve("tool");

        int code = CheckerCli.run(new String[]{
                "--game-dir", game.toString(), "--tool-root", tool.toString(),
                "--no-proxy", "--net", "abort", "--timeout", "20", "--grace", "0",
                "--fix", "fixture", "--policy", "stop"
        });

        assertEquals(2, code);
        assertEquals(1, RepairWorkflow.history(instance).size());
        assertTrue(RepairWorkflow.history(instance).getFirst().phase()
                == RepairWorkflow.Phase.PAUSED);
        assertFalse(java.nio.file.Files.exists(
                tool.resolve("state").resolve("fixture").resolve("round-1.json")));
    }

    @Test
    void 命令行restore按新版轮次预览并恢复() throws Exception {
        Path game = temp.resolve("game");
        GameInstance instance = RepairTasksTest.fixture(game, "repairable");
        Path broken = RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        RepairWorkflow.State state = state(instance, snapshot, "CLI restore", System.currentTimeMillis());
        RoundSnapshot.writeJournal(instance, state.id() + ".json", state);
        java.nio.file.Files.delete(broken);
        Path tool = temp.resolve("tool");

        int code = CheckerCli.run(new String[]{
                "--game-dir", game.toString(), "--tool-root", tool.toString(),
                "--no-proxy", "--timeout", "20", "--grace", "0",
                "--restore", "fixture", "--workflow", state.id().toString(), "--round", "1"
        });

        assertEquals(0, code);
        assertTrue(java.nio.file.Files.isRegularFile(broken));
        assertEquals(RepairWorkflow.Phase.RESTORED,
                RepairWorkflow.history(instance).getFirst().phase());
    }

    @Test
    void 没有新版历史时restore明确回退旧台账() throws Exception {
        Path game = temp.resolve("game");
        RepairTasksTest.fixture(game, "repairable");
        int code = CheckerCli.run(new String[]{
                "--game-dir", game.toString(), "--tool-root", temp.resolve("tool").toString(),
                "--no-proxy", "--restore", "fixture"
        });
        assertEquals(0, code);
    }

    private static RepairWorkflow.State state(GameInstance instance, RoundSnapshot snapshot,
                                               String message, long createdAt) throws Exception {
        UUID workflow = UUID.randomUUID();
        RepairWorkflow.Round round = new RepairWorkflow.Round(UUID.randomUUID(), 1, snapshot,
                RepairWorkflow.Phase.PAUSED, message, List.of(), List.of(), "");
        return new RepairWorkflow.State(1, workflow, InstanceIdentity.of(instance), createdAt,
                RepairPolicy.defaults(), 0, RepairWorkflow.Phase.PAUSED, message,
                List.of(round), null, null, null, List.of());
    }
}
