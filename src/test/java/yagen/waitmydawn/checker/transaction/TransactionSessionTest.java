package yagen.waitmydawn.checker.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static yagen.waitmydawn.checker.transaction.ActionRecord.*;
import static yagen.waitmydawn.checker.transaction.TransactionSession.Point;

class TransactionSessionTest {
    @Test
    void 墓碑按动作派生且恢复同号轮次不会清掉其他会话(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path state = tmp.resolve("state");
        Path first = instance.modsDir().resolve("first.jar");
        Path second = instance.modsDir().resolve("second.jar");
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        UUID older;
        UUID newer;
        try (TransactionSession session = TransactionSession.create(state, instance)) {
            older = session.header().sessionId();
            session.removeMod("same-mod", "first.jar", TransactionFiles.hash(first), 1, "fixture", List.of("evidence"));
        }
        try (TransactionSession session = TransactionSession.create(state, instance)) {
            newer = session.header().sessionId();
            session.removeMod("same-mod", "second.jar", TransactionFiles.hash(second), 1, "fixture", List.of("evidence"));
            Path input = candidate(tmp, Kind.INSTALL_MOD);
            assertThrows(IOException.class, () -> session.installMod("same-mod", "different-name.jar", input, TransactionFiles.hash(input), 2, "fixture", List.of("evidence")));
        }
        try (TransactionSession session = TransactionSession.resume(state, instance, newer)) { session.restoreAll(); }
        assertEquals(java.util.Set.of("same-mod"), TransactionSession.blockedMods(state, instance));
        try (TransactionSession session = TransactionSession.resume(state, instance, older)) { session.restoreAll(); }
        assertTrue(TransactionSession.blockedMods(state, instance).isEmpty());
    }

    @Test
    void 同事务安装新版本后移走旧版本不形成墓碑(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path state = tmp.resolve("state");
        Path oldJar = instance.modsDir().resolve("demo-1.0.jar");
        Files.writeString(oldJar, "old");
        Path replacement = candidate(tmp, Kind.INSTALL_MOD);

        try (TransactionSession session = TransactionSession.create(state, instance)) {
            session.installMod("demo", "demo-2.0.jar", replacement,
                    TransactionFiles.hash(replacement), 1, "版本更新", List.of("fixture"));
            session.removeMod("demo", "demo-1.0.jar", TransactionFiles.hash(oldJar),
                    1, "版本更新后移走旧文件", List.of("fixture"));
        }

        assertTrue(TransactionSession.blockedMods(state, instance).isEmpty(),
                "更新成功后的新版本不能被旧文件移除动作误判为永久停用");
    }

    @Test
    void 事务中取消后重开可恢复未提交动作(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path input = candidate(tmp, Kind.INSTALL_MOD);
        Path state = tmp.resolve("state");
        var id = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var changed = new java.util.concurrent.CountDownLatch(1);
        try (var tasks = new yagen.waitmydawn.checker.task.TaskService()) {
            var handle = tasks.submit("fixture", context -> {
                try (TransactionSession session = TransactionSession.create(state, instance, (point, action) -> {
                    if (point == Point.AFTER_MUTATION) {
                        changed.countDown();
                        try { Thread.sleep(60_000); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("fixture cancellation", e); }
                    }
                })) {
                    id.set(session.header().sessionId());
                    session.installMod("example", "example.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of("evidence"));
                }
                return yagen.waitmydawn.checker.task.TaskResult.success(null, "fixture complete");
            }, null);
            assertTrue(changed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            handle.cancel();
            assertEquals(yagen.waitmydawn.checker.task.TaskSnapshot.State.CANCELLED,
                    handle.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS).state());
        }
        try (TransactionSession session = TransactionSession.resume(state, instance, id.get())) {
            assertEquals(State.PREPARED, session.actions().getFirst().state());
            assertEquals(State.REVERTED, session.recoverPending().getFirst().state());
        }
        assertFalse(Files.exists(instance.modsDir().resolve("example.jar")));
    }

    @Test
    void 暂存目录链接不能把写入引到实例外(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path link = instance.dir().resolve(".maa-checker");
        if (System.getProperty("os.name").startsWith("Windows")) {
            try (var processes = new yagen.waitmydawn.checker.task.ProcessScope()) {
                Process process = processes.start(new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD));
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            }
        } else Files.createSymbolicLink(link, outside);
        try {
            assertThrows(IOException.class,
                    () -> TransactionSession.create(tmp.resolve("state"), instance));
            try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
            assertFalse(Files.exists(instance.modsDir().resolve("example.jar")));
        } finally { Files.delete(link); }
    }

    @Test
    void 备份损坏时保留当前文件并返回冲突(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path candidate = candidate(tmp, Kind.REPLACE_VERSION);
        try (TransactionSession session = TransactionSession.create(tmp.resolve("state"), instance)) {
            ActionRecord record = session.replaceVersion(candidate, TransactionFiles.hash(instance.versionJson()), 1, "fixture", List.of("evidence"));
            Path backup = instance.dir().resolve(".maa-checker/transactions").resolve(session.header().sessionId().toString())
                    .resolve(record.id().toString()).resolve("before.bin");
            Files.writeString(backup, "corrupted-backup");
            assertEquals(State.CONFLICT, session.restoreAll().getFirst().state());
            assertEquals(TransactionFiles.hash(candidate), TransactionFiles.hash(instance.versionJson()));
        }
    }

    @Test
    void 候选摘要不符时不生成变更且历史读取不创建目录(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path state = tmp.resolve("state");
        assertTrue(TransactionSession.history(state, instance).isEmpty());
        assertFalse(Files.exists(state));
        Path candidate = candidate(tmp, Kind.INSTALL_MOD);
        try (TransactionSession session = TransactionSession.create(state, instance)) {
            assertThrows(IOException.class, () -> session.installMod("example", "example.jar", candidate, "0".repeat(64), 1, "fixture", List.of("evidence")));
            assertTrue(session.actions().isEmpty());
            assertFalse(Files.exists(instance.modsDir().resolve("example.jar")));
        }
    }

    @Test
    void 未完成动作阻止新会话并且持久化提交错误后不能追加动作(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path candidate = candidate(tmp, Kind.INSTALL_MOD);
        Path state = tmp.resolve("state");
        UUID id;
        try (TransactionSession session = TransactionSession.create(state, instance, (point, record) -> {
            if (point == Point.BEFORE_COMMIT) throw new IOException("injected storage failure");
        })) {
            id = session.header().sessionId();
            assertThrows(IOException.class, () -> session.installMod("example", "example.jar", candidate, TransactionFiles.hash(candidate), 1, "fixture", List.of("evidence")));
            assertThrows(IOException.class, () -> session.installMod("example", "second.jar", candidate, TransactionFiles.hash(candidate), 2, "fixture", List.of("evidence")));
        }
        assertThrows(IOException.class, () -> TransactionSession.create(state, instance));
        try (TransactionSession session = TransactionSession.resume(state, instance, id)) { session.recoverPending(); }
        try (TransactionSession session = TransactionSession.create(state, instance)) { assertNotNull(session.header()); }
    }

    static GameInstance instance(Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("versions/example"));
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve("example.json"), "{\"id\":\"example\",\"mainClass\":\"fixture\",\"libraries\":[]}");
        return InstanceScanner.scanOne(root, dir);
    }

    static Path candidate(Path tmp, Kind kind) throws Exception {
        Path file = tmp.resolve(kind == Kind.REPLACE_VERSION ? "candidate.json" : "candidate.jar");
        Files.writeString(file, kind == Kind.REPLACE_VERSION ? "{\"id\":\"example\",\"mainClass\":\"updated\"}" : "candidate-content");
        return file;
    }

    static Path target(GameInstance instance, Kind kind) {
        return kind == Kind.REPLACE_VERSION ? instance.versionJson() : kind == Kind.DISABLE_PACK_MARKER
                ? instance.dir().resolve("modpack.cfg") : instance.modsDir().resolve("example.jar");
    }

    static String before(GameInstance instance, Kind kind) throws Exception {
        if (kind == Kind.REMOVE_MOD || kind == Kind.DISABLE_PACK_MARKER) Files.writeString(target(instance, kind), "original-content");
        return kind == Kind.INSTALL_MOD ? null : TransactionFiles.hash(target(instance, kind));
    }

    static ActionRecord apply(TransactionSession session, GameInstance instance, Kind kind, Path candidate, String before, int round) throws Exception {
        return switch (kind) {
            case INSTALL_MOD -> session.installMod("example", "example.jar", candidate, TransactionFiles.hash(candidate), round, "fixture install", List.of("fixture dependency evidence"));
            case REMOVE_MOD -> session.removeMod("example", "example.jar", before, round, "fixture remove", List.of("fixture failure evidence"));
            case REPLACE_VERSION -> session.replaceVersion(candidate, before, round, "fixture version", List.of("fixture loader evidence"));
            case DISABLE_PACK_MARKER -> session.disablePackMarker(before, round, "fixture marker", List.of("fixture removal evidence"));
            default -> throw new IllegalArgumentException("该动作采用独立测试");
        };
    }

    static Stream<Arguments> applyFaults() {
        return Stream.of(Kind.INSTALL_MOD, Kind.REMOVE_MOD, Kind.REPLACE_VERSION, Kind.DISABLE_PACK_MARKER).flatMap(kind -> Stream.of(Point.BEFORE_INTENT, Point.AFTER_INTENT,
                        Point.AFTER_WITHDRAW, Point.AFTER_MUTATION, Point.BEFORE_COMMIT)
                .filter(point -> kind != Kind.INSTALL_MOD || point != Point.AFTER_WITHDRAW)
                .map(point -> Arguments.of(kind, point)));
    }

    @ParameterizedTest
    @MethodSource("applyFaults")
    void 动作中断后重开会话可恢复且不自动续跑(Kind kind, Point point, @TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path input = candidate(tmp, kind);
        String baseline = before(instance, kind);
        Path state = tmp.resolve("state");
        UUID id;
        try (TransactionSession session = TransactionSession.create(state, instance, (at, action) -> {
            if (at == point) throw new IOException("injected " + point);
        })) {
            id = session.header().sessionId();
            assertThrows(IOException.class, () -> apply(session, instance, kind, input, baseline, 1));
            assertThrows(IOException.class, session::recoverPending, "故障会话必须重新打开");
        }
        try (TransactionSession resumed = TransactionSession.resume(state, instance, id)) {
            assertTrue(resumed.recoverPending().stream().allMatch(outcome -> outcome.state() == State.REVERTED));
            assertTrue(resumed.recoverPending().isEmpty());
            assertTrue(resumed.actions().stream().allMatch(action -> action.state() == State.REVERTED));
        }
        Path output = target(instance, kind);
        if (baseline == null) assertFalse(Files.exists(output));
        else assertEquals(baseline, TransactionFiles.hash(output));
    }

    static Stream<Arguments> undoFaults() {
        return Stream.of(Kind.INSTALL_MOD, Kind.REMOVE_MOD, Kind.REPLACE_VERSION, Kind.DISABLE_PACK_MARKER).flatMap(kind -> Stream.of(Point.AFTER_REVERT_INTENT, Point.AFTER_UNDO_MUTATION, Point.BEFORE_REVERT_COMMIT)
                .map(point -> Arguments.of(kind, point)));
    }

    @ParameterizedTest
    @MethodSource("undoFaults")
    void 恢复中断后可以重复恢复而不丢失文件(Kind kind, Point point, @TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path input = candidate(tmp, kind);
        String baseline = before(instance, kind);
        Path state = tmp.resolve("state");
        UUID id;
        try (TransactionSession session = TransactionSession.create(state, instance, (at, action) -> {
            if (at == point) throw new IOException("injected undo " + point);
        })) {
            id = session.header().sessionId();
            apply(session, instance, kind, input, baseline, 1);
            assertThrows(IOException.class, session::restoreAll);
        }
        try (TransactionSession resumed = TransactionSession.resume(state, instance, id)) {
            assertEquals(State.REVERTED, resumed.recoverPending().getFirst().state());
            assertTrue(resumed.restoreAll().isEmpty());
        }
        if (baseline == null) assertFalse(Files.exists(target(instance, kind)));
        else assertEquals(baseline, TransactionFiles.hash(target(instance, kind)));
    }

    @Test
    void 同名文件不覆盖且用户替换后的文件不被恢复删除(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path input = candidate(tmp, Kind.INSTALL_MOD);
        try (TransactionSession session = TransactionSession.create(tmp.resolve("state"), instance)) {
            Files.writeString(instance.modsDir().resolve("example.jar"), "user-original");
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> session.installMod("example", "example.jar", input,
                    TransactionFiles.hash(input), 1, "fixture", List.of("evidence")));
            assertEquals("user-original", Files.readString(instance.modsDir().resolve("example.jar")));
            session.installMod("example", "new.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of("evidence"));
            Files.writeString(instance.modsDir().resolve("new.jar"), "user-replacement");
            assertEquals(State.CONFLICT, session.restoreAll().getFirst().state());
            assertEquals("user-replacement", Files.readString(instance.modsDir().resolve("new.jar")));
            assertThrows(IOException.class, () -> session.installMod("example", "another.jar", input, TransactionFiles.hash(input), 2, "fixture", List.of("evidence")));
        }
    }

    @Test
    void 新会话不能越过未恢复的旧动作而恢复顺序必须由新到旧(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path input = candidate(tmp, Kind.INSTALL_MOD);
        Path state = tmp.resolve("state");
        UUID older;
        UUID newer;
        try (TransactionSession session = TransactionSession.create(state, instance)) {
            older = session.header().sessionId();
            session.installMod("example", "example.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of("evidence"));
        }
        try (TransactionSession session = TransactionSession.create(state, instance)) {
            newer = session.header().sessionId();
            session.removeMod("example", "example.jar", TransactionFiles.hash(input), 1, "fixture", List.of("evidence"));
        }
        try (TransactionSession session = TransactionSession.resume(state, instance, older)) {
            assertThrows(IOException.class, session::restoreAll);
            assertThrows(IOException.class, () -> session.installMod("example", "extra.jar", input, TransactionFiles.hash(input), 2, "fixture", List.of("evidence")));
        }
        try (TransactionSession session = TransactionSession.resume(state, instance, newer)) { session.restoreAll(); }
        try (TransactionSession session = TransactionSession.resume(state, instance, older)) { session.restoreAll(); }
        assertFalse(Files.exists(instance.modsDir().resolve("example.jar")));
    }

    @Test
    void 十二轮元数据替换按数值次序逆向恢复(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        String original = TransactionFiles.hash(instance.versionJson());
        try (TransactionSession session = TransactionSession.create(tmp.resolve("state"), instance)) {
            for (int round = 1; round <= 12; round++) {
                Path input = tmp.resolve("version-" + round + ".json");
                Files.writeString(input, "{\"round\":" + round + "}");
                session.replaceVersion(input, TransactionFiles.hash(instance.versionJson()), round, "fixture", List.of("evidence"));
            }
            List<TransactionSession.Outcome> outcomes = session.restoreAll();
            assertEquals(12, outcomes.size());
            assertEquals(session.actions().getLast().id(), outcomes.getFirst().actionId());
            assertTrue(outcomes.stream().allMatch(outcome -> outcome.state() == State.REVERTED));
        }
        assertEquals(original, TransactionFiles.hash(instance.versionJson()));
    }

    @Test
    void 同名实例历史隔离且共享模组目录互斥(@TempDir Path tmp) throws Exception {
        GameInstance first = instance(tmp.resolve("one"));
        GameInstance second = instance(tmp.resolve("two"));
        assertNotEquals(InstanceIdentity.of(first).id(), InstanceIdentity.of(second).id());
        GameInstance shared = new GameInstance(second.name(), second.dir(), second.versionJson(), second.mcVersion(),
                second.loader(), second.loaderVersion(), first.modsDir(), 0, second.gameRoot());
        try (TransactionSession session = TransactionSession.create(tmp.resolve("state"), first)) {
            assertThrows(IOException.class, () -> TransactionSession.create(tmp.resolve("different-state"), shared));
            assertTrue(TransactionSession.history(tmp.resolve("state"), second).isEmpty());
        }
        try (TransactionSession session = TransactionSession.create(tmp.resolve("different-state"), shared)) {
            assertNotNull(session.header());
        }
    }

    @Test
    void 损坏台账不被忽略且无证据及越界路径被拒绝(@TempDir Path tmp) throws Exception {
        GameInstance instance = instance(tmp.resolve("game"));
        Path state = tmp.resolve("state");
        Path input = candidate(tmp, Kind.INSTALL_MOD);
        UUID id;
        try (TransactionSession session = TransactionSession.create(state, instance)) {
            id = session.header().sessionId();
            assertThrows(IllegalArgumentException.class, () -> session.installMod("example", "../bad.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of("evidence")));
            assertThrows(IllegalArgumentException.class, () -> session.installMod("example", "valid.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of()));
            session.installMod("example", "valid.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of("evidence"));
        }
        Path directory = state.resolve("transactions-v1").resolve(InstanceIdentity.of(instance).id()).resolve(id.toString());
        try (var files = Files.list(directory)) {
            Path record = files.filter(path -> !path.getFileName().toString().equals("session.json")).findFirst().orElseThrow();
            Files.writeString(record, "broken-json");
        }
        assertThrows(IOException.class, () -> TransactionSession.resume(state, instance, id));
        assertThrows(IOException.class, () -> TransactionSession.create(state, instance));
        assertEquals("candidate-content", Files.readString(instance.modsDir().resolve("valid.jar")));
    }
}
