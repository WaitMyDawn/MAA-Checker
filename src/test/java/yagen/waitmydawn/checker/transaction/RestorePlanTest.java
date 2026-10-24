package yagen.waitmydawn.checker.transaction;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.checker.core.RepairInputs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 轮次恢复计划：排序、目标缺失和计划摘要的纯逻辑验收。 */
class RestorePlanTest {

    private static final UUID WORKFLOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ROUND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void 恢复步骤按模组_版本配置_标记的顺序执行() {
        Map<String, String> current = hashes(
                "marker", "m2", "version", "v2", "mod/zeta.jar", "z2", "mod/alpha.jar", "a2");
        Map<String, String> target = hashes(
                "marker", "m1", "version", "v1", "mod/zeta.jar", "z1", "mod/alpha.jar", "a1");

        RestorePlan plan = RestorePlan.between(WORKFLOW, ROUND, inputs(current), inputs(target));

        assertEquals(List.of("mod/alpha.jar", "mod/zeta.jar", "version", "marker"), plan.keys(),
                "同阶段按文件名稳定排序，跨阶段必须按语义顺序");
        assertEquals(List.of(RestorePlan.Phase.MOD, RestorePlan.Phase.MOD,
                        RestorePlan.Phase.VERSION, RestorePlan.Phase.MARKER),
                plan.steps().stream().map(RestorePlan.Step::phase).toList());
        assertTrue(plan.summary().contains("模组 2 项"));
        assertEquals(12, plan.shortDigest().length());
    }

    @Test
    void 目标状态没有标记时最后一步是停用而不是提前启用() {
        Map<String, String> current = hashes("version", "v1", "marker", "m1", "mod/a.jar", "a1");
        Map<String, String> target = hashes("version", "v1", "mod/a.jar", "a1");

        RestorePlan plan = RestorePlan.between(WORKFLOW, ROUND, inputs(current), inputs(target));

        assertEquals(1, plan.steps().size());
        RestorePlan.Step marker = plan.steps().getFirst();
        assertEquals(RestorePlan.Phase.MARKER, marker.phase());
        assertEquals("marker", marker.key());
        assertNotNull(marker.fromHash());
        assertNull(marker.toHash(), "目标没有标记，恢复最后应移除它");
    }

    @Test
    void 摘要稳定且随轮次或内容变化() {
        Map<String, String> current = hashes("version", "v2", "mod/a.jar", "a2");
        Map<String, String> target = hashes("version", "v1", "mod/a.jar", "a1");
        RestorePlan first = RestorePlan.between(WORKFLOW, ROUND, inputs(current), inputs(target));
        RestorePlan same = RestorePlan.between(WORKFLOW, ROUND,
                inputs(new LinkedHashMap<>(current)), inputs(new LinkedHashMap<>(target)));
        RestorePlan otherRound = RestorePlan.between(UUID.randomUUID(), ROUND, inputs(current), inputs(target));
        RestorePlan changed = RestorePlan.between(WORKFLOW, ROUND, inputs(current),
                inputs(hashes("version", "v0", "mod/a.jar", "a1")));

        assertEquals(first.digest(), same.digest(), "相同身份与内容必须得到同一计划摘要");
        assertTrue(!first.digest().equals(otherRound.digest()), "工作流身份必须纳入摘要");
        assertTrue(!first.digest().equals(changed.digest()), "目标内容变化必须改变摘要");
    }

    @Test
    void 未知快照键必须拒绝而不能静默参与排序() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RestorePlan.between(WORKFLOW, ROUND,
                        inputs(hashes("unknown/object", "x")), inputs(Map.of())));
        assertTrue(error.getMessage().contains("未知的轮次快照目标"), error.getMessage());
    }

    private static RepairInputs inputs(Map<String, String> hashes) {
        return new RepairInputs("versions/fixture/fixture.json", "versions/fixture/mods", hashes);
    }

    private static Map<String, String> hashes(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }
}
