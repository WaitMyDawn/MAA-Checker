package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.action.*;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceStorage;
import yagen.waitmydawn.checker.core.VersionUpdateStrategy;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairPolicyTest {
    @Test void 规划器问题指纹映射到独立分类且未知默认停止() {
        var policy = RepairPolicy.defaults();
        Map<String, RepairPolicy.Category> cases = Map.of("MIXIN_FAILURE", RepairPolicy.Category.MIXIN,
                "DUPLICATE_MOD", RepairPolicy.Category.DUPLICATE, "BAD_VERSION_FORMAT", RepairPolicy.Category.BAD_VERSION,
                "MOD_VERSION_MISMATCH", RepairPolicy.Category.VERSION_MISMATCH,
                "CAUSED_BY_ATTRIBUTED", RepairPolicy.Category.ATTRIBUTION, "STACK_ATTRIBUTED", RepairPolicy.Category.ATTRIBUTION,
                "MOD_LOAD_FAILURE", RepairPolicy.Category.MOD_FAILURE, "MISSING_REQUIRED", RepairPolicy.Category.CASCADE,
                "CASCADE(MISSING_REQUIRED)", RepairPolicy.Category.CASCADE, "unknown", RepairPolicy.Category.OTHER);
        cases.forEach((fingerprint, expected) -> assertEquals(expected, policy.category(new PlannedAction(ActionKind.REMOVE_MOD, "mod", "mod.jar", fingerprint, "evidence", List.of("evidence"), null, null, null))));
        assertEquals(RepairPolicy.Mode.STOP, policy.modes().get(RepairPolicy.Category.OTHER));
        assertEquals(RepairPolicy.Category.VERSION_MISMATCH,
                policy.category(new PlannedAction(ActionKind.UPDATE_MOD, "mod", "mod.jar",
                        "MOD_VERSION_MISMATCH", "evidence", List.of("evidence"),
                        null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new RepairPolicy(Map.of(), 0));
    }

    @Test void 策略快照区分首次默认值与已保存值且损坏文件不回退(@TempDir Path temp) throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairWorkflow.PolicySnapshot absent = RepairWorkflow.policySnapshot(instance);
        assertFalse(absent.saved());
        assertEquals(RepairPolicy.defaults(), absent.policy());

        RepairPolicy saved = new RepairPolicy(Map.of(
                RepairPolicy.Category.DEPENDENCY, RepairPolicy.Mode.AUTO,
                RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.STOP,
                RepairPolicy.Category.OTHER, RepairPolicy.Mode.ASK), 6,
                VersionUpdateStrategy.CHOOSE);
        RoundSnapshot.writeJournal(instance, "policy.json", saved);
        RepairWorkflow.PolicySnapshot loaded = RepairWorkflow.policySnapshot(instance);
        assertTrue(loaded.saved());
        assertEquals(saved, loaded.policy());
        assertEquals(VersionUpdateStrategy.CHOOSE, loaded.policy().versionStrategy());

        Path workflows = InstanceStorage.directory(InstanceStorage.root(instance, false), "workflows", false);
        Files.writeString(workflows.resolve("policy.json"), "{broken");
        assertThrows(IOException.class, () -> RepairWorkflow.policySnapshot(instance));
    }
}
