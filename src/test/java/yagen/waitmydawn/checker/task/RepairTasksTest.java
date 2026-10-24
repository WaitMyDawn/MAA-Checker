package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.action.LoaderPreparation;
import yagen.waitmydawn.checker.net.ModrinthClient;
import yagen.waitmydawn.checker.transaction.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

public class RepairTasksTest {
    @TempDir Path temp;

    private CheckerEngine.Options options() {
        var o = CheckerEngine.Options.defaults();
        return new CheckerEngine.Options("1G", 10, 0, o.rounds(), o.cascadeLimit(), false, true,
                1, 0, "abort", null, null, false, false, null, o.notifier());
    }

    public static GameInstance fixture(Path root, String mode) throws Exception {
        Path dir = Files.createDirectories(root.resolve("versions/fixture"));
        Files.createDirectories(dir.resolve("mods"));
        Files.createDirectories(dir.resolve("logs"));
        Files.createDirectories(dir.resolve("natives-windows-x86_64"));
        String classFile = ProcessFixture.class.getName().replace('.', '/') + ".class";
        try (var jar = new JarOutputStream(Files.newOutputStream(dir.resolve("fixture.jar")));
             var input = ProcessFixture.class.getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(input);
            jar.putNextEntry(new JarEntry(classFile));
            input.transferTo(jar);
            jar.closeEntry();
        }
        new ObjectMapper().writeValue(dir.resolve("fixture.json").toFile(), Map.of(
                "id", "fixture", "inheritsFrom", "1.21.1", "mainClass", ProcessFixture.class.getName(),
                "javaVersion", Map.of("majorVersion", 21), "libraries", List.of(), "arguments",
                Map.of("jvm", List.of("-cp", "${classpath}"), "game", List.of(mode, "${game_directory}/logs/latest.log"))));
        return InstanceScanner.scanOne(root, dir);
    }

    public static Path mod(Path file, String id) throws Exception {
        Files.createDirectories(file.getParent());
        try (var jar = new JarOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(new ObjectMapper().writeValueAsBytes(Map.of("id", id, "version", "1.0", "schemaVersion", 1)));
            jar.closeEntry();
        }
        return file;
    }

    private static Path versionedMod(Path file, String id, String version) throws Exception {
        Files.createDirectories(file.getParent());
        try (var jar = new JarOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(new ObjectMapper().writeValueAsBytes(Map.of(
                    "id", id, "version", version, "name", id, "schemaVersion", 1)));
            jar.closeEntry();
        }
        return file;
    }

    private <T> TaskResult<T> run(TaskService.Work<T> work) throws Exception {
        try (var tasks = new TaskService()) {
            return tasks.submit("fixture", work, null).completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
        }
    }

    private CheckerEngine.CheckReport diagnose(GameInstance instance) throws Exception {
        var result = run(CheckerTasks.diagnose(instance, temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.FAILED, result.state(), result.message());
        assertNotNull(result.value());
        return result.value();
    }

    private CheckerEngine.CheckReport report(GameInstance instance, Diagnosis.Issue issue) throws Exception {
        var round = new CheckerEngine.Round(instance, null, 21, StaticDepsScanner.scan(instance), null, null,
                new Diagnosis(List.of(issue), List.of(issue.detail()), null, false, "fixture"), ModJarIndex.scanMeta(instance.modsDir()).jarOf());
        return new CheckerEngine.CheckReport(1, temp.resolve("report"), List.of(round), RepairInputs.capture(instance));
    }

    @Test
    void 不支持的加载器布局在联网前拒绝且HMCL标记纳入审核() throws Exception {
        GameInstance base = fixture(temp.resolve("game"), "repairable");
        GameInstance neoforge = new GameInstance(base.name(), base.dir(), base.versionJson(), base.mcVersion(),
                "neoforge", "21.1.1", base.modsDir(), 0, base.gameRoot());
        var loader = report(neoforge, new Diagnosis.Issue(Diagnosis.Kind.LOADER_TOO_OLD, "neoforge", "client", "[21.1.10,)", "21.1.1", null, "fixture loader evidence"));
        var result = run(RepairTasks.prepare(loader, temp.resolve("tool"), options(), (a, i, c) -> { fail("加载器受限时不应下载"); return List.of(); }));
        assertEquals(TaskSnapshot.State.FAILED, result.state());
        assertTrue(result.message().contains("HMCL"));

        mod(base.modsDir().resolve("broken.jar"), "broken");
        Files.writeString(base.dir().resolve("modpack.cfg"), "fixture marker");
        var marked = report(base, new Diagnosis.Issue(Diagnosis.Kind.MOD_LOAD_FAILURE, "broken", null, null, null, "broken.jar", "fixture failure"));
        var markerPlan = run(RepairTasks.prepare(marked, temp.resolve("tool"), options(), (a, i, c) -> List.of())).value();
        assertTrue(markerPlan.executable());
        assertTrue(markerPlan.disablePackMarker());
        assertEquals(2, markerPlan.fileCount());
        assertTrue(Files.exists(base.dir().resolve("modpack.cfg")));
        assertTrue(Files.exists(base.modsDir().resolve("broken.jar")));
    }

    @Test
    void 确证无候选时仅生成待审核的替代停用方案() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "installable");
        mod(instance.modsDir().resolve("client.jar"), "client");
        var diagnosis = report(instance, new Diagnosis.Issue(Diagnosis.Kind.MISSING_REQUIRED, "dependency", "client", "[1,)", "[MISSING]", "client.jar", "fixture missing evidence"));
        var plan = run(RepairTasks.prepare(diagnosis, temp.resolve("tool"), options(), (a, i, c) -> List.of())).value();
        assertTrue(plan.executable());
        assertEquals(yagen.waitmydawn.checker.action.ActionKind.REMOVE_MOD, plan.changes().getFirst().action().kind());
        assertTrue(Files.exists(instance.modsDir().resolve("client.jar")), "替代动作在审核前不能执行");
    }

    @Test
    void 版本过低生成先装新版本再移走旧版本的审核方案() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "installable");
        versionedMod(instance.modsDir().resolve("needed-1.0.jar"), "needed", "1.0");
        var diagnosis = report(instance, new Diagnosis.Issue(Diagnosis.Kind.MOD_VERSION_MISMATCH,
                "needed", "client", "[2.0,)", "1.0", "needed-1.0.jar",
                "Mod ID: 'needed', Requested by: 'client', Expected range: '[2.0,)'"));
        Path replacement = versionedMod(temp.resolve("download/needed-2.0.jar"), "needed", "2.0");
        ModrinthClient.Candidate candidate = new ModrinthClient.Candidate(
                "needed", "Needed", "project", "version", "2.0",
                "needed-2.0.jar", "https://cdn.example/needed-2.0.jar", Files.size(replacement),
                "mod", "2026-10-24T00:00:00Z");
        RepairTasks.Candidates candidates = new RepairTasks.Candidates() {
            @Override public List<RepairTasks.Download> resolve(
                    yagen.waitmydawn.checker.action.PlannedAction action,
                    GameInstance ignored, TaskContext context) {
                fail("版本更新应走版本列表，不应调用普通补装");
                return List.of();
            }

            @Override public List<ModrinthClient.Candidate> versions(
                    yagen.waitmydawn.checker.action.PlannedAction action,
                    GameInstance ignored, TaskContext context) {
                return List.of(candidate);
            }

            @Override public List<RepairTasks.Download> resolveSelected(
                    yagen.waitmydawn.checker.action.PlannedAction action,
                    GameInstance ignored, ModrinthClient.Candidate selected,
                    TaskContext context) {
                assertEquals(candidate, selected);
                return List.of(new RepairTasks.Download(replacement, "fixture candidate"));
            }
        };

        var result = run(RepairTasks.prepare(diagnosis, temp.resolve("tool"), options(),
                candidates,
                (i, action, context) -> { throw new AssertionError("不应准备加载器"); },
                new RepairTasks.Gate() {
                    @Override public void authorize(
                            yagen.waitmydawn.checker.action.PlannedAction action) { }
                },
                VersionUpdateStrategy.LATEST)).value();

        assertTrue(result.executable(), result.blocked().toString());
        assertEquals(2, result.changes().size());
        assertEquals(yagen.waitmydawn.checker.action.ActionKind.INSTALL_DEP,
                result.changes().get(0).action().kind());
        assertEquals("needed-2.0.jar", result.changes().get(0).filename());
        assertEquals(yagen.waitmydawn.checker.action.ActionKind.REMOVE_MOD,
                result.changes().get(1).action().kind());
        assertEquals("needed-1.0.jar", result.changes().get(1).filename());
        assertTrue(Files.exists(instance.modsDir().resolve("needed-1.0.jar")));
        assertFalse(Files.exists(instance.modsDir().resolve("needed-2.0.jar")),
                "审核前不能把候选写进 mods");
    }

    @Test
    void 旧停用记录不与新事务混用() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "repairable");
        mod(instance.modsDir().resolve("broken.jar"), "broken");
        var diagnosis = report(instance, new Diagnosis.Issue(Diagnosis.Kind.MOD_LOAD_FAILURE, "broken", null, null, null, "broken.jar", "fixture failure"));
        Path legacy = Files.createDirectories(temp.resolve("tool/state/fixture")).resolve("tombstones.json");
        Files.writeString(legacy, "{\"tombstones\":[{\"modId\":\"old\"}]}");
        var result = run(RepairTasks.prepare(diagnosis, temp.resolve("tool"), options(), (a, i, c) -> List.of()));
        assertEquals(TaskSnapshot.State.FAILED, result.state());
        assertTrue(result.message().contains("旧版停用记录"));
        assertTrue(Files.exists(instance.modsDir().resolve("broken.jar")));
    }

    @Test
    void 审核停用真实事务验证与恢复(@TempDir Path root) throws Exception {
        GameInstance instance = fixture(root, "repairable");
        Path broken = mod(instance.modsDir().resolve("broken.jar"), "broken");
        Files.writeString(instance.dir().resolve("modpack.cfg"), "fixture pack marker");
        String hash = RepairInputs.hash(broken);
        var report = diagnose(instance);
        var prepared = run(RepairTasks.prepare(report, temp.resolve("tool"), options(), (a, i, c) -> { fail("停用不应下载"); return List.of(); }));
        assertEquals(TaskSnapshot.State.SUCCEEDED, prepared.state(), prepared.message());
        assertTrue(prepared.value().executable());
        assertTrue(Files.exists(broken), "预览不能修改实例");
        var executed = run(RepairTasks.execute(prepared.value(), temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.SUCCEEDED, executed.state(), executed.message());
        assertEquals(0, executed.value().verification().exitCode());
        assertFalse(Files.exists(broken));
        assertFalse(Files.exists(instance.dir().resolve("modpack.cfg")));
        var history = TransactionSession.history(temp.resolve("tool/state"), instance);
        assertEquals(ActionRecord.State.APPLIED, history.getFirst().actions().getFirst().state());
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
        var restored = run(RepairTasks.restore(instance, temp.resolve("tool"), executed.value().sessionId()));
        assertEquals(TaskSnapshot.State.SUCCEEDED, restored.state(), restored.message());
        assertEquals(hash, RepairInputs.hash(broken));
        assertEquals("fixture pack marker", Files.readString(instance.dir().resolve("modpack.cfg")));
    }

    @Test
    void 下载候选身份审核补装并再次验证() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "installable");
        Path candidate = mod(temp.resolve("cache/dependency.jar"), "dependency");
        var prepared = run(RepairTasks.prepare(diagnose(instance), temp.resolve("tool"), options(),
                (a, i, c) -> List.of(new RepairTasks.Download(candidate, "fixture source"))));
        assertTrue(prepared.value().executable(), prepared.message());
        assertFalse(Files.exists(instance.modsDir().resolve("dependency.jar")));
        var result = run(RepairTasks.execute(prepared.value(), temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.SUCCEEDED, result.state(), result.message());
        assertEquals(RepairInputs.hash(candidate), RepairInputs.hash(instance.modsDir().resolve("dependency.jar")));
        assertTrue(TransactionSession.history(temp.resolve("tool/state"), instance).getFirst().actions().getFirst()
                .evidence().stream().anyMatch(e -> e.contains("fixture source")));
    }

    @Test
    void 诊断或审核后文件变化拒绝执行() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "repairable");
        Path broken = mod(instance.modsDir().resolve("broken.jar"), "broken");
        var report = diagnose(instance);
        var prepared = run(RepairTasks.prepare(report, temp.resolve("tool"), options(), (a, i, c) -> List.of()));
        mod(broken, "different");
        String changed = RepairInputs.hash(broken);
        var execute = run(RepairTasks.execute(prepared.value(), temp.resolve("tool"), options()));
        assertEquals(TaskSnapshot.State.FAILED, execute.state());
        assertTrue(execute.message().contains("发生变化"));
        assertEquals(changed, RepairInputs.hash(broken));
        var retryPrepare = run(RepairTasks.prepare(report, temp.resolve("tool"), options(), (a, i, c) -> List.of()));
        assertEquals(TaskSnapshot.State.FAILED, retryPrepare.state());
        assertTrue(TransactionSession.history(temp.resolve("tool/state"), instance).isEmpty());
    }

    @Test
    void 网络失败或候选身份错误不改为停用() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "installable");
        var report = diagnose(instance);
        var network = run(RepairTasks.prepare(report, temp.resolve("tool"), options(), (a, i, c) -> { throw new java.io.IOException("fixture offline"); }));
        assertEquals(TaskSnapshot.State.FAILED, network.state());
        Path wrong = mod(temp.resolve("cache/dependency.jar"), "other");
        var mismatch = run(RepairTasks.prepare(report, temp.resolve("tool"), options(), (a, i, c) -> List.of(new RepairTasks.Download(wrong, "fixture"))));
        assertFalse(mismatch.value().executable());
        assertTrue(mismatch.value().changes().isEmpty());
        assertTrue(mismatch.value().blocked().stream().anyMatch(s -> s.contains("未声明")));
        try (var files = Files.list(instance.modsDir())) { assertEquals(0, files.count()); }
    }

    @Test
    void 确认后候选变化拒绝安装() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "installable");
        Path candidate = mod(temp.resolve("cache/dependency.jar"), "dependency");
        var plan = run(RepairTasks.prepare(diagnose(instance), temp.resolve("tool"), options(),
                (a, i, c) -> List.of(new RepairTasks.Download(candidate, "fixture")))).value();
        mod(candidate, "different");
        assertEquals(TaskSnapshot.State.FAILED, run(RepairTasks.execute(plan, temp.resolve("tool"), options())).state());
        assertFalse(Files.exists(instance.modsDir().resolve("dependency.jar")));
    }

    @Test
    void 恢复冲突保留用户文件() throws Exception {
        GameInstance instance = fixture(temp.resolve("game"), "repairable");
        mod(instance.modsDir().resolve("broken.jar"), "broken");
        var plan = run(RepairTasks.prepare(diagnose(instance), temp.resolve("tool"), options(), (a, i, c) -> List.of())).value();
        var execution = run(RepairTasks.execute(plan, temp.resolve("tool"), options())).value();
        Path changed = mod(instance.modsDir().resolve("broken.jar"), "user_replacement");
        String hash = RepairInputs.hash(changed);
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
        var restore = run(RepairTasks.restore(instance, temp.resolve("tool"), execution.sessionId()));
        assertEquals(TaskSnapshot.State.NEEDS_ATTENTION, restore.state());
        assertFalse(restore.value().complete());
        assertEquals(hash, RepairInputs.hash(changed));
    }

    @Test
    void 中文空格长路径可扫描记录执行并还原() throws Exception {
        Path root = temp.resolve("中文 整合 包")
                .resolve("第一层目录-用于验证较长路径")
                .resolve("第二层目录-仍包含空格");
        Files.createDirectories(root);
        GameInstance instance = fixture(root, "repairable");
        Path broken = mod(instance.modsDir().resolve("broken.jar"), "broken");
        var initial = RepairInputs.capture(instance);
        Path tool = temp.resolve("工具 状态-也应该支持空格");

        CheckerEngine.CheckReport report = diagnose(instance);
        var plan = run(RepairTasks.prepare(report, tool, options(), (a, i, c) -> List.of())).value();
        assertTrue(plan.executable(), plan.blocked().toString());
        var execution = run(RepairTasks.execute(plan, tool, options())).value();
        assertFalse(Files.exists(broken));

        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"),
                java.nio.file.attribute.FileTime.fromMillis(0));
        var restore = run(RepairTasks.restore(instance, tool, execution.sessionId()));
        assertEquals(TaskSnapshot.State.SUCCEEDED, restore.state(), restore.message());
        assertEquals(initial, RepairInputs.capture(instance));
        assertTrue(Files.exists(broken));
    }
}
