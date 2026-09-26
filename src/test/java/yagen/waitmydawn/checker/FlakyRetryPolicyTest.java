package yagen.waitmydawn.checker;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.Diagnosis;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "偶发崩溃要不要先原样重启"的判据（纯策略，无 IO）。
 *
 * <p>只有<b>非依赖型</b>故障才值得重试——缺前置、加载器版本不足是确定性结论，重启一百次也一样。
 */
class FlakyRetryPolicyTest {

    private static Diagnosis.Issue issue(Diagnosis.Kind k) {
        return new Diagnosis.Issue(k, "m", "m", "-", "-", null, "d");
    }

    private static Diagnosis of(Diagnosis.Kind... kinds) {
        return new Diagnosis(List.of(kinds).stream().map(FlakyRetryPolicyTest::issue).toList(),
                List.of(), null, false, "n");
    }

    @Test
    void 模组初始化崩溃属于偶发候选() {
        assertTrue(CheckerEngine.onlyNonDependencyIssues(of(Diagnosis.Kind.MOD_LOAD_FAILURE)));
        assertTrue(CheckerEngine.onlyNonDependencyIssues(of(Diagnosis.Kind.MIXIN_FAILURE)));
        assertTrue(CheckerEngine.onlyNonDependencyIssues(
                of(Diagnosis.Kind.MIXIN_FAILURE, Diagnosis.Kind.CRASH_OTHER)));
    }

    @Test
    void 缺前置与加载器版本不足不重试() {
        assertFalse(CheckerEngine.onlyNonDependencyIssues(of(Diagnosis.Kind.MISSING_REQUIRED)));
        assertFalse(CheckerEngine.onlyNonDependencyIssues(of(Diagnosis.Kind.LOADER_TOO_OLD)));
        assertFalse(CheckerEngine.onlyNonDependencyIssues(
                of(Diagnosis.Kind.MOD_LOAD_FAILURE, Diagnosis.Kind.MISSING_REQUIRED)),
                "只要掺了确定性问题，就不该把整轮判成偶发");
    }

    @Test
    void 没有任何问题时不算偶发候选() {
        assertFalse(CheckerEngine.onlyNonDependencyIssues(of()));
    }
}
