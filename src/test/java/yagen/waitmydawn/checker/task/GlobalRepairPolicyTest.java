package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.VersionUpdateStrategy;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalRepairPolicyTest {
    @TempDir Path temp;

    @Test
    void 默认全部自动十轮且文件不存在不写盘() throws Exception {
        GlobalRepairPolicy.Snapshot snapshot = GlobalRepairPolicy.load(temp.resolve("tool"));
        assertFalse(snapshot.saved());
        assertEquals(10, snapshot.policy().maxRounds());
        assertTrue(snapshot.policy().modes().values().stream()
                .allMatch(mode -> mode == RepairPolicy.Mode.AUTO));
        assertEquals(VersionUpdateStrategy.MINIMUM, snapshot.policy().versionStrategy());
        assertFalse(java.nio.file.Files.exists(
                temp.resolve("tool/state/repair-policy-v1.json")));
    }

    @Test
    void 保存后所有实例读取同一策略() throws Exception {
        RepairPolicy policy = new RepairPolicy(Map.of(
                RepairPolicy.Category.DEPENDENCY, RepairPolicy.Mode.STOP), 7,
                VersionUpdateStrategy.LATEST);
        Path tool = temp.resolve("tool");
        try (var tasks = new TaskService()) {
            TaskResult<RepairPolicy> saved = tasks.submit("policy",
                            GlobalRepairPolicy.save(tool, policy), null)
                    .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.SUCCEEDED, saved.state(), saved.message());
        }
        GlobalRepairPolicy.Snapshot first = GlobalRepairPolicy.load(tool);
        GlobalRepairPolicy.Snapshot second = GlobalRepairPolicy.load(tool);
        assertTrue(first.saved());
        assertEquals(first.policy(), second.policy());
        assertEquals(RepairPolicy.Mode.STOP, first.policy().modes().get(
                RepairPolicy.Category.DEPENDENCY));
        assertEquals(7, first.policy().maxRounds());
        assertEquals(VersionUpdateStrategy.LATEST, second.policy().versionStrategy());
    }

    @Test
    void 旧版策略文件缺少版本策略时使用最低版本默认值() throws Exception {
        Path state = java.nio.file.Files.createDirectories(
                temp.resolve("tool/state"));
        java.nio.file.Files.writeString(state.resolve("repair-policy-v1.json"), """
                {"schema":1,"policy":{"modes":{"OTHER":"ASK"},"maxRounds":6}}
                """);

        GlobalRepairPolicy.Snapshot snapshot = GlobalRepairPolicy.load(temp.resolve("tool"));

        assertTrue(snapshot.saved());
        assertEquals(VersionUpdateStrategy.MINIMUM, snapshot.policy().versionStrategy());
        assertEquals(RepairPolicy.Mode.ASK,
                snapshot.policy().modes().get(RepairPolicy.Category.OTHER));
        assertEquals(RepairPolicy.Mode.AUTO,
                snapshot.policy().modes().get(RepairPolicy.Category.VERSION_MISMATCH));
    }
}
