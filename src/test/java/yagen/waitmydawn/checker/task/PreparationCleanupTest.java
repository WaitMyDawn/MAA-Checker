package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.transaction.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PreparationCleanupTest {
    @TempDir Path temp;
    private <T> TaskResult<T> run(TaskService.Work<T> work) throws Exception {
        try (var tasks = new TaskService()) { return tasks.submit("fixture", work, null).completion().toCompletableFuture().get(5, TimeUnit.SECONDS); }
    }
    @Test void 清理仅删除已预览的准备文件且保留快照和实例输入() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        var before = RepairInputs.capture(i); var snapshot = RoundSnapshot.capture(i);
        Path root = Files.createDirectories(i.dir().resolve(".maa-checker/preparation/prepare-fixture/game"));
        Files.writeString(root.resolve("artifact.jar"), "generated");
        Files.writeString(root.getParent().resolve("installer.log"), "generated log");
        var preview = run(PreparationCleanup.preview(i)).value();
        assertEquals(2, preview.files().size()); assertTrue(preview.bytes() > 0);
        assertEquals(2, run(PreparationCleanup.clear(i, preview)).value());
        assertEquals(before, RepairInputs.capture(i)); assertNotNull(snapshot.candidate(i, "mod/broken.jar"));
        assertTrue(run(PreparationCleanup.preview(i)).value().files().isEmpty());
    }
    @Test void 预览后变化未知内容及活动会话均阻止清理() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path root = Files.createDirectories(i.dir().resolve(".maa-checker/preparation/candidate-fixture"));
        Path file = Files.writeString(root.resolve("artifact.jar"), "generated");
        var preview = run(PreparationCleanup.preview(i)).value();
        Files.writeString(file, "changed");
        assertEquals(TaskSnapshot.State.FAILED, run(PreparationCleanup.clear(i, preview)).state()); assertTrue(Files.exists(file));
        preview = run(PreparationCleanup.preview(i)).value();
        try (var lease = WorkflowLease.acquire(i)) { assertEquals(TaskSnapshot.State.FAILED, run(PreparationCleanup.clear(i, preview)).state()); }
        Files.createDirectories(root.getParent().resolve("unrecognized"));
        assertEquals(TaskSnapshot.State.FAILED, run(PreparationCleanup.preview(i)).state()); assertTrue(Files.exists(file));
    }
}
