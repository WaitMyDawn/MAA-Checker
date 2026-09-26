package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本区间判定的单测：静态预检的结论全靠它，所以边界必须钉死。 */
class VersionRangesTest {

    @Test
    @DisplayName("NeoForge 版本区间：21.1.231 不满足 [21.1.238,)、21.1.247 满足")
    void neoforgeRange() {
        assertFalse(VersionRanges.satisfies("21.1.231", "[21.1.238,)"));
        assertTrue(VersionRanges.satisfies("21.1.238", "[21.1.238,)"));
        assertTrue(VersionRanges.satisfies("21.1.247", "[21.1.247,]"));
        assertFalse(VersionRanges.satisfies("21.1.231", "[21.1.247,]"));
    }

    @Test
    @DisplayName("上下界开闭与单值区间")
    void openClosedAndSingle() {
        assertTrue(VersionRanges.satisfies("1.21.1", "[1.21.1]"));
        assertFalse(VersionRanges.satisfies("1.21.0", "[1.21.1]"));
        assertFalse(VersionRanges.satisfies("1.21.1", "[1.21,1.21.1)"));
        assertTrue(VersionRanges.satisfies("1.21.0", "[1.21,1.21.1)"));
        assertTrue(VersionRanges.satisfies("6.0.0", "(,6.0]"));
        assertFalse(VersionRanges.satisfies("6.0.1", "(,6.0]"));
    }

    @Test
    @DisplayName("非标准版本号（带 build 段）也能比较")
    void buildSuffixVersions() {
        assertTrue(VersionRanges.satisfies("2101.7.2-build.315", "[2101.7.2-build.315,)"));
        assertFalse(VersionRanges.satisfies("2101.7.2-build.300", "[2101.7.2-build.315,)"));
    }

    @Test
    @DisplayName("通配与不可解析区间按放行处理（静态预检只报高置信项）")
    void wildcardPasses() {
        assertTrue(VersionRanges.satisfies("1.21.1", "*"));
        assertTrue(VersionRanges.satisfies("1.21.1", ""));
        assertTrue(VersionRanges.satisfies("1.21.1", ">=1.21"));
    }
}
