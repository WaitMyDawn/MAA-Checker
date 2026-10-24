package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySizeTest {
    @Test
    void 输入值按四兆步长取最近值并受上下限约束() {
        assertEquals(3072, MemorySize.snap(3071));
        assertEquals(3072, MemorySize.snap(3073));
        assertEquals(3076, MemorySize.snap(3074));
        assertEquals(MemorySize.MIN_MB, MemorySize.snap(-100));
        assertEquals(MemorySize.maximumMb(), MemorySize.snap(Integer.MAX_VALUE));
    }

    @Test
    void 超过物理内存一半需要确认() {
        int half = MemorySize.physicalMb() / 2;
        assertFalse(MemorySize.needsConfirmation(Math.max(0, half - 4)));
        assertTrue(MemorySize.needsConfirmation(half + 4));
    }
}
