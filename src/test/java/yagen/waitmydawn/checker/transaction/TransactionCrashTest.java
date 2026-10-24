package yagen.waitmydawn.checker.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import yagen.waitmydawn.checker.task.ProcessScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TransactionCrashTest {
    @ParameterizedTest
    @EnumSource(value = TransactionSession.Point.class, names = {"AFTER_INTENT", "AFTER_MUTATION", "BEFORE_COMMIT"})
    void 共享缓存发布时JVM直接退出后可释放会话而不删除缓存(TransactionSession.Point point, @TempDir Path tmp) throws Exception {
        Path game = tmp.resolve("game");
        var instance = TransactionSessionTest.instance(game);
        Path candidate = TransactionSessionTest.candidate(tmp, ActionRecord.Kind.INSTALL_MOD);
        Path state = tmp.resolve("state");
        try (ProcessScope processes = new ProcessScope()) {
            Process process = processes.start(new ProcessBuilder(TransactionProcessFixture.command("crash-cache", game, state, point.name()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(71, process.exitValue());
        }
        UUID id = UUID.fromString(Files.readString(state.resolve("session-id")));
        try (TransactionSession resumed = TransactionSession.resume(state, instance, id)) {
            assertEquals(ActionRecord.State.REVERTED, resumed.recoverPending().getFirst().state());
        }
        Path target = LibraryPaths.target(instance, "test/example/1/example.jar");
        if (Files.exists(target)) assertEquals(TransactionFiles.hash(candidate), TransactionFiles.hash(target));
    }
    @ParameterizedTest
    @EnumSource(value = TransactionSession.Point.class, names = {"AFTER_WITHDRAW", "AFTER_MUTATION", "BEFORE_COMMIT"})
    void 元数据替换期间JVM退出后可恢复缺失的版本文件(TransactionSession.Point point, @TempDir Path tmp) throws Exception {
        Path game = tmp.resolve("game");
        var instance = TransactionSessionTest.instance(game);
        String original = TransactionFiles.hash(instance.versionJson());
        TransactionSessionTest.candidate(tmp, ActionRecord.Kind.REPLACE_VERSION);
        Path state = tmp.resolve("state");
        try (ProcessScope processes = new ProcessScope()) {
            Process process = processes.start(new ProcessBuilder(TransactionProcessFixture.command("crash-version", game, state, point.name()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(71, process.exitValue());
        }
        UUID id = UUID.fromString(Files.readString(state.resolve("session-id")));
        try (TransactionSession resumed = TransactionSession.resume(state, instance, id)) {
            assertEquals(ActionRecord.State.REVERTED, resumed.recoverPending().getFirst().state());
        }
        assertEquals(original, TransactionFiles.hash(instance.versionJson()));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionSession.Point.class, names = {"AFTER_INTENT", "AFTER_MUTATION", "BEFORE_COMMIT"})
    void JVM直接退出后记录和实例锁仍可恢复(TransactionSession.Point point, @TempDir Path tmp) throws Exception {
        Path game = tmp.resolve("game");
        var instance = TransactionSessionTest.instance(game);
        TransactionSessionTest.candidate(tmp, ActionRecord.Kind.INSTALL_MOD);
        Path state = tmp.resolve("state");
        try (ProcessScope processes = new ProcessScope()) {
            Process process = processes.start(new ProcessBuilder(TransactionProcessFixture.command("crash", game, state, point.name()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "故障注入子 JVM 超时");
            assertEquals(71, process.exitValue());
        }
        UUID session = UUID.fromString(Files.readString(state.resolve("session-id")));
        try (TransactionSession resumed = TransactionSession.resume(state, instance, session)) {
            assertEquals(ActionRecord.State.REVERTED, resumed.recoverPending().getFirst().state());
            assertTrue(resumed.recoverPending().isEmpty());
        }
        assertFalse(Files.exists(instance.modsDir().resolve("example.jar")));
    }

    @Test
    void 独立JVM不能并发修改同一实例(@TempDir Path tmp) throws Exception {
        Path game = tmp.resolve("game");
        var instance = TransactionSessionTest.instance(game);
        try (TransactionSession owner = TransactionSession.create(tmp.resolve("owner-state"), instance);
             ProcessScope processes = new ProcessScope()) {
            Process other = processes.start(new ProcessBuilder(TransactionProcessFixture.command("lock", game, tmp.resolve("other-state"), "unused"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD));
            assertTrue(other.waitFor(10, TimeUnit.SECONDS));
            assertEquals(23, other.exitValue());
            assertNotNull(owner.header());
        }
        try (TransactionSession reopened = TransactionSession.create(tmp.resolve("after-state"), instance)) {
            assertNotNull(reopened.header());
        }
    }
}
