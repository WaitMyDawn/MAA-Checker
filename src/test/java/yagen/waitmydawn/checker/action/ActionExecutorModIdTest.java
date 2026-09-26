package yagen.waitmydawn.checker.action;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 补装前的 modId 核对规则：空集（读不到声明）必须当"无法确认"，绝不能当通过 */
class ActionExecutorModIdTest {

    @Test
    void 声明里含目标modId才算通过_大小写不敏感() {
        assertTrue(ActionExecutor.declares(Set.of("relics"), "relics"));
        assertTrue(ActionExecutor.declares(Set.of("Ars_Nouveau"), "ars_nouveau"));
        assertTrue(ActionExecutor.declares(Set.of("relics", "relics_api"), "relics_api"),
                "一个 jar 声明多个 modId 时，命中任意一个即可");
    }

    @Test
    void 读不到任何声明时必须判为无法确认() {
        assertFalse(ActionExecutor.declares(Set.of(), "relics"));
        assertFalse(ActionExecutor.declares(Set.of("dream_relics"), "relics"),
                "同名模组（modId 不同）不能被当成目标");
    }
}
