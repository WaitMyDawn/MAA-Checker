package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.InstanceScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CheckerTasksIntegrationTest {
    private Path fixture(Path root, String mode) throws Exception {
        Path instance = Files.createDirectories(root.resolve("versions/fixture"));
        Files.createDirectories(instance.resolve("logs"));
        Files.createDirectories(instance.resolve("mods"));
        Files.createDirectories(instance.resolve("natives-windows-x86_64"));
        String classFile = ProcessFixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(instance.resolve("fixture.jar")));
             var input = ProcessFixture.class.getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(input);
            jar.putNextEntry(new JarEntry(classFile));
            input.transferTo(jar);
            jar.closeEntry();
        }
        var json = Map.of("id", "fixture", "inheritsFrom", "1.21.1", "mainClass", ProcessFixture.class.getName(),
                "javaVersion", Map.of("majorVersion", 21), "libraries", List.of(),
                "arguments", Map.of("jvm", List.of("-cp", "${classpath}"),
                        "game", List.of(mode, "${game_directory}/logs/latest.log")));
        new ObjectMapper().writeValue(instance.resolve("fixture.json").toFile(), json);
        return instance;
    }

    @Test
    void 共享检验任务真实调用引擎并生成报告(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("game");
        Path instance = fixture(root, "marker-live");
        List<TaskEvent> events = new CopyOnWriteArrayList<>();
        Path artifacts = tmp.resolve("artifacts");
        var defaults = CheckerEngine.Options.defaults();
        var options = new CheckerEngine.Options("1G", 10, 0, defaults.rounds(), defaults.cascadeLimit(),
                false, true, 1, 0, "abort", null, null, false, false, null, defaults.notifier());
        try (TaskService tasks = new TaskService()) {
            var result = tasks.submit("fixture", CheckerTasks.check(InstanceScanner.scanOne(root, instance), artifacts, options), events::add)
                    .completion().toCompletableFuture().get(45, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.SUCCEEDED, result.state(), result.message());
            assertEquals(0, CheckerTasks.exitCode(result));
            assertTrue(events.stream().anyMatch(e -> e.snapshot().phase() == TaskSnapshot.Phase.OBSERVING));
            assertTrue(events.stream().anyMatch(e -> e.snapshot().phase() == TaskSnapshot.Phase.DIAGNOSING));
            assertEquals(TaskSnapshot.Phase.FINISHED, events.getLast().snapshot().phase());
        }
        try (var files = Files.walk(artifacts)) {
            Path report = files.filter(p -> p.getFileName().toString().equals("report.json")).findFirst().orElseThrow();
            assertEquals("PASS", new ObjectMapper().readTree(report.toFile()).path("launch").path("verdict").asText());
        }
        try (var mods = Files.list(instance.resolve("mods"))) { assertEquals(0, mods.count()); }
    }

    @Test
    void 结构化诊断保留真实失败与报告目录(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("game");
        Path instance = fixture(root, "marker-exit");
        var defaults = CheckerEngine.Options.defaults();
        var options = new CheckerEngine.Options("1G", 10, 0, defaults.rounds(), defaults.cascadeLimit(),
                false, true, 1, 0, "abort", null, null, false, false, null, defaults.notifier());
        try (TaskService tasks = new TaskService()) {
            var result = tasks.submit("fixture", CheckerTasks.diagnose(InstanceScanner.scanOne(root, instance),
                    tmp.resolve("artifacts"), options), null).completion().toCompletableFuture().get(45, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.FAILED, result.state());
            assertNotNull(result.value());
            assertEquals(1, result.value().exitCode());
            assertEquals(1, result.value().rounds().size());
            assertNotEquals(yagen.waitmydawn.checker.core.LaunchVerdict.PASS, result.value().rounds().getFirst().launch().verdict());
            assertTrue(Files.isRegularFile(result.value().runDirectory().resolve("report.json")));
            assertThrows(UnsupportedOperationException.class, () -> result.value().rounds().clear());
        }
    }

    @Test
    void 启动条件未满足不伪造诊断结果(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("game");
        Path instance = fixture(root, "marker-live");
        Files.writeString(instance.resolve("logs/latest.log"), "already active");
        try (TaskService tasks = new TaskService()) {
            var result = tasks.submit("fixture", CheckerTasks.diagnose(InstanceScanner.scanOne(root, instance),
                    tmp.resolve("artifacts"), CheckerEngine.Options.defaults()), null)
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.NEEDS_ATTENTION, result.state());
            assertEquals(2, result.value().exitCode());
            assertTrue(result.value().rounds().isEmpty());
            assertTrue(Files.isRegularFile(result.value().runDirectory().resolve("journal.log")));
            assertFalse(Files.exists(result.value().runDirectory().resolve("report.json")));
        }
    }

    @Test
    void 同名会话不覆盖输出目录(@TempDir Path tmp) throws Exception {
        var first = yagen.waitmydawn.checker.core.RunJournal.create(tmp, "same", null);
        var second = yagen.waitmydawn.checker.core.RunJournal.create(tmp, "same", null);
        try {
            assertNotEquals(first.runDir(), second.runDir());
            first.log("first");
            second.log("second");
        } finally { first.close(); second.close(); }
        assertEquals("first", Files.readString(first.runDir().resolve("journal.log")).strip());
        assertEquals("second", Files.readString(second.runDir().resolve("journal.log")).strip());
    }

    @Test
    void CLI退出码区分取消失败与需人工处理() {
        assertEquals(130, CheckerTasks.exitCode(new TaskResult<>(TaskSnapshot.State.CANCELLED, null, "stopped", null)));
        assertEquals(1, CheckerTasks.exitCode(new TaskResult<>(TaskSnapshot.State.FAILED, 1, "failed", null)));
        assertEquals(2, CheckerTasks.exitCode(new TaskResult<>(TaskSnapshot.State.NEEDS_ATTENTION, 2, "review", null)));
        assertEquals(2, CheckerTasks.exitCode(TaskResult.failure("exception", new java.io.IOException())));
    }
}
