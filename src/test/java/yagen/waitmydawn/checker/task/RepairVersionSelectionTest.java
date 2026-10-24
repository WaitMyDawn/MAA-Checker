package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.checker.core.VersionUpdateStrategy;
import yagen.waitmydawn.checker.net.ModrinthClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RepairVersionSelectionTest {
    private static final List<ModrinthClient.Candidate> VERSIONS = List.of(
            candidate("1.21.1-3.16.3", "2026-02-01T00:00:00Z"),
            candidate("1.21.1-3.16.5", "2026-03-01T00:00:00Z"));

    @Test
    void 最低版本策略选择满足区间的最小版本() {
        assertEquals("1.21.1-3.16.3",
                RepairTasks.selectVersion(VERSIONS, VersionUpdateStrategy.MINIMUM).versionNumber());
    }

    @Test
    void 最新策略按发布时间选择最新兼容版本() {
        assertEquals("1.21.1-3.16.5",
                RepairTasks.selectVersion(VERSIONS, VersionUpdateStrategy.LATEST).versionNumber());
    }

    @Test
    void 自选策略必须交给交互界面() {
        assertNull(RepairTasks.selectVersion(VERSIONS, VersionUpdateStrategy.CHOOSE));
    }

    private static ModrinthClient.Candidate candidate(String version, String date) {
        return new ModrinthClient.Candidate("demo", "Demo", "p", version, version,
                "demo-" + version + ".jar", "https://cdn.example/" + version, 1024,
                "mod", date);
    }
}
