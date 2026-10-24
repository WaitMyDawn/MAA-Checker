package yagen.waitmydawn.checker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.Diagnosis;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.LogDiagnoser;
import yagen.waitmydawn.checker.core.ModJarIndex;
import yagen.waitmydawn.checker.core.StaticDepsScanner;
import yagen.waitmydawn.checker.task.RepairPolicy;
import yagen.waitmydawn.checker.task.RepairWorkflow;
import yagen.waitmydawn.checker.task.RepairTasksTest;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RoundSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可重复的规模探针；记录相对耗时与 JVM 堆峰值，不把固定阈值当成所有机器上的性能承诺。 */
class ScaleProbeTest {
    @TempDir Path temp;

    @Test
    void 三百模组与二十兆日志探针() throws Exception {
        GameInstance instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        for (int index = 0; index < 300; index++) {
            RepairTasksTest.mod(instance.modsDir().resolve("bulk-" + index + ".jar"),
                    "bulk_" + index);
        }
        long scanStart = System.nanoTime();
        try (HeapPeak ignored = new HeapPeak()) {
            ModJarIndex.Meta meta = ModJarIndex.scanMeta(instance.modsDir());
            StaticDepsScanner.Report staticReport = StaticDepsScanner.scan(instance);
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
            assertTrue(meta.jarOf().keySet().stream().anyMatch(id -> id.equals("bulk_299")));
            assertTrue(staticReport.modCount() >= 300);
            System.out.printf("SCALE mods=300 scan_ms=%d heap_peak_mb=%.1f%n",
                    scanMs, ignored.peakBytes() / 1048576.0);
        }

        Path log = temp.resolve("scale/latest.log");
        Files.createDirectories(log.getParent());
        StringBuilder block = new StringBuilder();
        for (int line = 0; line < 1000; line++) {
            block.append("[12:00:00] [Render thread/INFO]: synthetic line ")
                    .append(line).append(" mod loading progress\n");
        }
        try (var writer = Files.newBufferedWriter(log)) {
            for (int repeat = 0; repeat < 300; repeat++) {
                writer.append(block);
                if (repeat == 299) {
                    writer.write("Exception in thread \"main\" java.lang.IllegalStateException: fixture scale\n");
                }
            }
        }
        long bytes = Files.size(log);
        long diagnoseStart = System.nanoTime();
        try (HeapPeak ignored = new HeapPeak()) {
            Diagnosis diagnosis = LogDiagnoser.diagnoseFiles(List.of(log), Map.of());
            long diagnoseMs = (System.nanoTime() - diagnoseStart) / 1_000_000;
            assertTrue(bytes >= 15L * 1024 * 1024);
            assertTrue(!diagnosis.issues().isEmpty());
            System.out.printf("SCALE log_mb=%.1f diagnose_ms=%d heap_peak_mb=%.1f%n",
                    bytes / 1048576.0, diagnoseMs, ignored.peakBytes() / 1048576.0);
        }

        RoundSnapshot snapshot = RoundSnapshot.capture(instance);
        for (int index = 0; index < 120; index++) {
            RepairWorkflow.State state = state(instance, snapshot, index);
            RoundSnapshot.writeJournal(instance, state.id() + ".json", state);
        }
        long historyStart = System.nanoTime();
        try (HeapPeak ignored = new HeapPeak()) {
            List<RepairWorkflow.State> history = RepairWorkflow.history(instance);
            long historyMs = (System.nanoTime() - historyStart) / 1_000_000;
            assertTrue(history.size() == 120);
            System.out.printf("SCALE histories=120 history_ms=%d heap_peak_mb=%.1f%n",
                    historyMs, ignored.peakBytes() / 1048576.0);
        }
    }

    private static RepairWorkflow.State state(GameInstance instance, RoundSnapshot snapshot,
                                               int index) throws Exception {
        UUID workflow = UUID.randomUUID();
        RepairWorkflow.Round round = new RepairWorkflow.Round(UUID.randomUUID(), 1, snapshot,
                RepairWorkflow.Phase.PAUSED, "scale history " + index,
                List.of(), List.of(), "");
        return new RepairWorkflow.State(1, workflow, InstanceIdentity.of(instance),
                System.currentTimeMillis() + index, RepairPolicy.defaults(), 0,
                RepairWorkflow.Phase.PAUSED, "scale history " + index,
                List.of(round), null, null, null, List.of());
    }

    private static final class HeapPeak implements AutoCloseable {
        private final AtomicLong peak;
        private final Thread sampler;
        private volatile boolean running = true;
        private final java.lang.management.MemoryMXBean memory =
                java.lang.management.ManagementFactory.getMemoryMXBean();

        private HeapPeak() {
            peak = new AtomicLong(heap());
            sampler = Thread.ofVirtual().name("scale-heap-sampler").start(() -> {
                while (running) {
                    peak.accumulateAndGet(heap(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        private long heap() {
            return memory.getHeapMemoryUsage().getUsed();
        }

        private long peakBytes() {
            return Math.max(peak.get(), heap());
        }

        @Override
        public void close() throws Exception {
            running = false;
            sampler.join();
        }
    }
}
