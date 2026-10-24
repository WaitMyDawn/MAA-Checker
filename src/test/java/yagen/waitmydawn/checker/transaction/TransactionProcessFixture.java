package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.InstanceScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 子 JVM 崩溃与跨进程锁验证，仅接受测试创建的临时实例目录。 */
public final class TransactionProcessFixture {
    static List<String> command(String mode, Path game, Path state, String point) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xmx64m", "-cp",
                System.getProperty("java.class.path"), TransactionProcessFixture.class.getName(), mode, game.toString(), state.toString(), point);
    }

    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[1]);
        Path state = Path.of(args[2]);
        var instance = InstanceScanner.scanOne(game, game.resolve("versions/example"));
        if (args[0].equals("lock")) {
            try (TransactionSession ignored = TransactionSession.create(state, instance)) { System.exit(24); }
            catch (IOException e) { System.exit(23); }
        }
        if (args[0].equals("workflow-lock")) {
            try (WorkflowLease ignored = WorkflowLease.acquire(instance)) { System.exit(24); }
            catch (IOException e) { System.exit(23); }
        }
        var point = TransactionSession.Point.valueOf(args[3]);
        try (TransactionSession session = TransactionSession.create(state, instance, (at, record) -> {
            if (at == point) Runtime.getRuntime().halt(71);
        })) {
            Files.writeString(state.resolve("session-id"), session.header().sessionId().toString());
            if (args[0].equals("crash-version")) {
                Path input = game.resolveSibling("candidate.json");
                session.replaceVersion(input, TransactionFiles.hash(instance.versionJson()), 1, "fixture", List.of("fixture evidence"));
            } else if (args[0].equals("crash-cache")) {
                Path input = game.resolveSibling("candidate.jar");
                session.cacheLibrary("test/example/1/example.jar", input, TransactionFiles.hash(input), 1, "fixture cache", List.of("fixture evidence"));
            } else {
                Path input = game.resolveSibling("candidate.jar");
                session.installMod("example", "example.jar", input, TransactionFiles.hash(input), 1, "fixture", List.of("fixture evidence"));
            }
        }
    }
}
