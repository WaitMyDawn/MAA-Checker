package yagen.waitmydawn.checker.core;

import java.lang.management.ManagementFactory;

/** 启动内存的界面约束：4 MB 步长、512 MB 下限、物理内存上限。 */
public final class MemorySize {
    public static final int DEFAULT_MB = 3072;
    public static final int MIN_MB = 512;
    public static final int STEP_MB = 4;

    private MemorySize() {
    }

    public static int physicalMb() {
        long bytes;
        try {
            bytes = ((com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (RuntimeException | LinkageError e) {
            bytes = Runtime.getRuntime().maxMemory();
        }
        long mb = Math.max(MIN_MB, bytes / (1024L * 1024L));
        return (int) Math.min(Integer.MAX_VALUE, mb);
    }

    public static int maximumMb() {
        return Math.max(MIN_MB, physicalMb() / STEP_MB * STEP_MB);
    }

    public static int snap(int requested) {
        long rounded = Math.round(requested / (double) STEP_MB) * STEP_MB;
        return (int) Math.max(MIN_MB, Math.min(maximumMb(), rounded));
    }

    public static boolean needsConfirmation(int mb) {
        return mb > physicalMb() / 2;
    }
}
