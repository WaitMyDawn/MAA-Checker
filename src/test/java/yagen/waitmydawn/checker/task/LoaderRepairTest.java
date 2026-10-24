package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import yagen.waitmydawn.checker.action.LoaderPreparation;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.transaction.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class LoaderRepairTest {
    @TempDir Path temp;
    private CheckerEngine.Options options() {
        var o = CheckerEngine.Options.defaults();
        return new CheckerEngine.Options("1G", 10, 0, o.rounds(), o.cascadeLimit(), false, true,
                1, 0, "abort", null, null, false, false, null, o.notifier());
    }
    private <T> TaskResult<T> run(TaskService.Work<T> work) throws Exception {
        try (var tasks = new TaskService()) { return tasks.submit("fixture", work, null).completion().toCompletableFuture().get(25, TimeUnit.SECONDS); }
    }
    private RepairTasks.Plan plan(GameInstance instance, Path installer) throws Exception {
        var diagnosis = run(CheckerTasks.diagnose(instance, temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.FAILED, diagnosis.state(), diagnosis.message());
        var plan = run(RepairTasks.prepare(diagnosis.value(), temp.resolve("tool"), options(), (a, i, c) -> List.of(),
                LoaderFixture.factory(installer, temp.resolve("preparation"))));
        assertEquals(TaskSnapshot.State.SUCCEEDED, plan.state(), plan.message());
        assertTrue(plan.value().executable());
        return plan.value();
    }

    @Test
    void 安装器隔离准备审核发布验证与配置恢复() throws Exception {
        var instance = LoaderFixture.game(temp.resolve("game"));
        String original = RepairInputs.hash(instance.versionJson());
        String profiles = Files.readString(instance.gameRoot().resolve("launcher_profiles.json"));
        Path installer = LoaderFixture.installer(temp.resolve("installer.jar"));
        var plan = plan(instance, installer);
        assertEquals(original, RepairInputs.hash(instance.versionJson()));
        Path added = LibraryPaths.target(instance, "test/loader/21.1.10/loader-21.1.10.jar");
        assertFalse(Files.exists(added), "方案准备不能发布共享库");
        assertEquals(profiles, Files.readString(instance.gameRoot().resolve("launcher_profiles.json")));
        assertFalse(Files.exists(instance.gameRoot().resolve("versions/neoforge-21.1.10")));
        assertEquals(3, plan.loader().additions(), "只新增显式加载器库和缺失的两个 NeoForge 隐式产物；原版客户端库复用共享文件");
        assertTrue(plan.loader().libraries().stream().anyMatch(lib ->
                lib.relative().equals("net/neoforged/neoforge/21.1.10/neoforge-21.1.10-universal.jar")));
        assertTrue(plan.loader().libraries().stream().anyMatch(lib ->
                lib.relative().equals("net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar")));
        var executed = run(RepairTasks.execute(plan, temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.SUCCEEDED, executed.state(), executed.message());
        assertEquals("21.1.10", InstanceScanner.scanOne(instance.gameRoot(), instance.dir()).loaderVersion());
        assertTrue(Files.isRegularFile(added));
        assertFalse(Files.readString(instance.versionJson()).contains("maaCheckerRuntime"),
                "外部启动器必须使用标准共享 libraries 布局");
        assertEquals(profiles, Files.readString(instance.gameRoot().resolve("launcher_profiles.json")));
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
        var restore = run(RepairTasks.restore(instance, temp.resolve("tool"), executed.value().sessionId()));
        assertEquals(TaskSnapshot.State.SUCCEEDED, restore.state(), restore.message());
        assertEquals(original, RepairInputs.hash(instance.versionJson()));
        assertTrue(Files.exists(added), "版本专属共享库作为缓存保留，避免影响其他实例");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "exit", "escape", "wrong-mc", "wrong-hash"})
    void 失败或非法产物不修改真实目录(String mode) throws Exception {
        var instance = LoaderFixture.game(temp.resolve("game"));
        var before = RepairInputs.capture(instance);
        Path installer = LoaderFixture.installer(temp.resolve(mode + ".jar"));
        var result = run(context -> TaskResult.success(LoaderPreparation.prepare(instance, "21.1.10", installer,
                LoaderFixture.javaHome(), temp.resolve("preparation"), null, 10, context), "prepared"));
        assertEquals(TaskSnapshot.State.FAILED, result.state(), result.message());
        assertEquals(before, RepairInputs.capture(instance));
        assertFalse(Files.exists(instance.gameRoot().resolve("libraries/test/loader/21.1.10")));
    }

    @Test
    void 超时和取消清理安装器进程() throws Exception {
        var instance = LoaderFixture.game(temp.resolve("game"));
        var before = RepairInputs.capture(instance);
        Path installer = LoaderFixture.installer(temp.resolve("hang.jar"));
        Path cache = temp.resolve("preparation");
        var timeout = run(context -> TaskResult.success(LoaderPreparation.prepare(instance, "21.1.10", installer,
                LoaderFixture.javaHome(), cache, null, 1, context), "prepared"));
        assertEquals(TaskSnapshot.State.FAILED, timeout.state());
        assertTrue(timeout.message().contains("超时"));
        try (var tasks = new TaskService()) {
            var task = tasks.submit("fixture", context -> TaskResult.success(LoaderPreparation.prepare(instance, "21.1.10", installer,
                    LoaderFixture.javaHome(), temp.resolve("cancel"), null, 30, context), "prepared"), null);
            Path pidFile = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pidFile == null && System.nanoTime() < deadline) {
                if (Files.isDirectory(temp.resolve("cancel"))) {
                    try (var files = Files.walk(temp.resolve("cancel"))) { pidFile = files.filter(p -> p.getFileName().toString().equals("fixture.pid")).findFirst().orElse(null); }
                }
                if (pidFile == null) Thread.sleep(20);
            }
            assertNotNull(pidFile);
            long pid = readPid(pidFile, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            task.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED, task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
        assertEquals(before, RepairInputs.capture(instance));
    }

    @Test
    void 共享库目录链接在复制前被拒绝() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var instance = LoaderFixture.game(temp.resolve("game"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path sentinel = RepairTasksTest.mod(outside.resolve("keep.jar"), "sentinel");
        String original = RepairInputs.hash(sentinel);
        Path link = instance.gameRoot().resolve("libraries/linked");
        try (ProcessScope processes = new ProcessScope()) {
            Process process = processes.start(new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }
        try {
            Path installer = LoaderFixture.installer(temp.resolve("installer.jar"));
            var result = run(context -> TaskResult.success(LoaderPreparation.prepare(instance, "21.1.10", installer,
                    LoaderFixture.javaHome(), temp.resolve("preparation"), null, 10, context), "prepared"));
            assertEquals(TaskSnapshot.State.FAILED, result.state());
            assertTrue(result.message().contains("链接"));
            assertEquals(original, RepairInputs.hash(sentinel));
        } finally { Files.deleteIfExists(link); }
    }

    @Test
    void 实例独立库冲突或审核后候选变化拒绝发布() throws Exception {
        var instance = LoaderFixture.game(temp.resolve("game"));
        Path installer = LoaderFixture.installer(temp.resolve("installer.jar"));
        var plan = plan(instance, installer);
        Path added = LibraryPaths.target(instance, "test/loader/21.1.10/loader-21.1.10.jar");
        RepairTasksTest.mod(added, "external_content");
        String external = RepairInputs.hash(added);
        var conflict = run(RepairTasks.execute(plan, temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.FAILED, conflict.state());
        assertEquals(external, RepairInputs.hash(added));
        assertEquals("21.1.1", InstanceScanner.scanOne(instance.gameRoot(), instance.dir()).loaderVersion());
        Files.delete(added);
        Files.writeString(plan.loader().versionJson(), "{\"id\":\"tampered\"}");
        assertEquals(TaskSnapshot.State.FAILED, run(RepairTasks.execute(plan, temp.resolve("tool"), options())).state());
        assertFalse(Files.exists(added));
    }

    private static long readPid(Path file, long deadline) throws Exception {
        while (System.nanoTime() < deadline) {
            try {
                String value = Files.readString(file).trim();
                if (!value.isEmpty()) return Long.parseLong(value);
            } catch (IOException | NumberFormatException ignored) {
                // The installer may still be publishing the complete pid.
            }
            Thread.sleep(10);
        }
        fail("pid signal was not readable before the deadline: " + file);
        return -1;
    }
}
