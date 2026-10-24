package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.action.Ledger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionRestoreOrderTest {
    @Test
    void 还原入口兼容早期时间戳及唯一会话编号(@TempDir Path tmp) throws Exception {
        Path instanceDir = Files.createDirectories(tmp.resolve("game/versions/example"));
        Files.createDirectories(instanceDir.resolve("mods"));
        Files.writeString(instanceDir.resolve("example.json"), "{\"id\":\"example\",\"mainClass\":\"fixture\",\"libraries\":[]}");
        GameInstance instance = InstanceScanner.scanOne(tmp.resolve("game"), instanceDir);
        Path tool = tmp.resolve("tool");
        Path state = tool.resolve("state/example");
        for (String session : List.of("", "20261023-120000", "20261023-120001-" + java.util.UUID.randomUUID())) {
            Ledger ledger = Ledger.create(state, session, 1, "example");
            ledger.add("FIXTURE", "entry-" + session, null, "FIXTURE", "compatibility fixture", List.of(), Map.of());
        }
        List<String> output = new java.util.ArrayList<>();
        assertEquals(0, CheckerEngine.restore(instance, tool, null, output::add));
        assertEquals(3, output.stream().filter(line -> line.contains("跳过未知条目: FIXTURE entry-")).count());
    }

    @Test
    void 旧台账超过九轮仍按数值逆序恢复(@TempDir Path tmp) throws Exception {
        Path instanceDir = Files.createDirectories(tmp.resolve("game/versions/example"));
        Files.createDirectories(instanceDir.resolve("mods"));
        Files.writeString(instanceDir.resolve("example.json"), "{\"id\":\"example\",\"mainClass\":\"fixture\",\"libraries\":[]}");
        GameInstance instance = InstanceScanner.scanOne(tmp.resolve("game"), instanceDir);
        Path tool = tmp.resolve("tool");
        Path state = tool.resolve("state/example");
        for (int round = 1; round <= 12; round++) {
            Ledger ledger = Ledger.create(state, "fixture-session", round, "example");
            ledger.add("FIXTURE", "round-" + round, null, "FIXTURE", "order fixture", List.of(), Map.of());
        }
        RunJournal journal = RunJournal.create(tool, "example", line -> { });
        try {
            var steps = CheckerEngine.restoreSession(tool, instance, "fixture-session", journal);
            assertEquals("跳过未知条目: FIXTURE round-12", steps.getFirst());
            assertEquals("跳过未知条目: FIXTURE round-1", steps.getLast());
            assertEquals(12, steps.size());
        } finally { journal.close(); }
    }
}
