package yagen.waitmydawn.checker.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryTransactionTest {
    @ParameterizedTest
    @EnumSource(value = TransactionSession.Point.class, names = {"BEFORE_INTENT", "AFTER_INTENT", "AFTER_MUTATION", "BEFORE_COMMIT"})
    void 缓存发布中断后保留已发布内容且不续跑(TransactionSession.Point fault, @TempDir Path temp) throws Exception {
        var instance = TransactionSessionTest.instance(temp.resolve("game"));
        Path candidate = TransactionSessionTest.candidate(temp, ActionRecord.Kind.INSTALL_MOD);
        String hash = TransactionFiles.hash(candidate);
        UUID id;
        try (var session = TransactionSession.create(temp.resolve("state"), instance, (point, action) -> {
            if (point == fault) throw new java.io.IOException("fixture");
        })) {
            id = session.header().sessionId();
            assertThrows(java.io.IOException.class, () -> session.cacheLibrary("test/example/1/example.jar", candidate, hash, 1, "fixture", List.of("fixture")));
        }
        try (var session = TransactionSession.resume(temp.resolve("state"), instance, id)) {
            assertTrue(session.recoverPending().stream().allMatch(o -> o.state() == ActionRecord.State.REVERTED));
            assertTrue(session.recoverPending().isEmpty());
        }
        Path target = LibraryPaths.target(instance, "test/example/1/example.jar");
        if (Files.exists(target)) assertEquals(hash, TransactionFiles.hash(target));
    }

    @Test
    void 缓存不覆盖不删除并校验根绑定(@TempDir Path temp) throws Exception {
        var instance = TransactionSessionTest.instance(temp.resolve("game"));
        Path candidate = TransactionSessionTest.candidate(temp, ActionRecord.Kind.INSTALL_MOD);
        UUID id;
        try (var session = TransactionSession.create(temp.resolve("state"), instance)) {
            id = session.header().sessionId();
            session.cacheLibrary("test/example/1/example.jar", candidate, TransactionFiles.hash(candidate), 1, "fixture", List.of("fixture"));
            assertThrows(FileAlreadyExistsException.class, () -> session.cacheLibrary("test/example/1/example.jar", candidate, TransactionFiles.hash(candidate), 1, "fixture", List.of("fixture")));
            Path target = LibraryPaths.target(instance, "test/example/1/example.jar");
            Files.writeString(target, "used by another instance");
            assertTrue(session.restoreAll().getFirst().message().contains("保留"));
            assertEquals("used by another instance", Files.readString(target));
        }
        Path otherRoot = Files.createDirectories(temp.resolve("other"));
        var wrongBinding = new yagen.waitmydawn.checker.core.GameInstance(instance.name(), instance.dir(), instance.versionJson(),
                instance.mcVersion(), instance.loader(), instance.loaderVersion(), instance.modsDir(), 0, otherRoot);
        assertThrows(java.io.IOException.class, () -> TransactionSession.resume(temp.resolve("state"), wrongBinding, id));
    }

    @Test
    void 非法库路径被拒绝且独立实例不争用共享游戏根(@TempDir Path temp) throws Exception {
        var instance = TransactionSessionTest.instance(temp.resolve("game"));
        Path candidate = TransactionSessionTest.candidate(temp, ActionRecord.Kind.INSTALL_MOD);
        try (var session = TransactionSession.create(temp.resolve("state"), instance)) {
            for (String path : List.of("../outside.jar", "C:/outside.jar", "group/../outside.jar", "group//file.jar", "secrets/file.jar", "a/.env.jar")) {
                assertThrows(java.io.IOException.class, () -> session.cacheLibrary(path, candidate, TransactionFiles.hash(candidate), 1, "fixture", List.of("fixture")));
            }
            Path second = Files.createDirectories(instance.gameRoot().resolve("versions/second"));
            Files.createDirectories(second.resolve("mods"));
            Files.writeString(second.resolve("second.json"), "{\"id\":\"second\",\"mainClass\":\"fixture\",\"libraries\":[]}");
            var other = yagen.waitmydawn.checker.core.InstanceScanner.scanOne(instance.gameRoot(), second);
            try (var independent = TransactionSession.create(temp.resolve("state"), other)) { assertNotNull(independent.header()); }
        }
        assertFalse(Files.exists(temp.resolve("outside.jar")));
    }
}
