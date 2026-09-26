package yagen.waitmydawn.checker.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.Diagnosis;
import yagen.waitmydawn.checker.core.StaticDepsScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作规划单测：这些规则一旦写错，轻则修不好包，重则把玩家的整合包摘空。
 * 用例全部对着用户确认过的四条口径写：A→B→C 顺序、墓碑禁止回装、非依赖型不级联、级联有上限。
 */
class ActionPlannerTest {

    @Test
    void 顺序与合并_加载器问题合并成一条且排在补装之前() {
        Diagnosis diag = new Diagnosis(List.of(
                issue(Diagnosis.Kind.LOADER_TOO_OLD, "neoforge", "jei", "[21.1.238,)", "21.1.231"),
                issue(Diagnosis.Kind.LOADER_TOO_OLD, "neoforge", "supplementaries", "[21.1.247,)", "21.1.231"),
                issue(Diagnosis.Kind.MISSING_REQUIRED, "kubejs", "irons_spells_js", "[2101.7.2-build.315,)", "[MISSING]")),
                List.of(), null, false, "n");
        Map<String, String> jars = Map.of("irons_spells_js", "irons_spells_js-1.jar");

        ActionPlan plan = ActionPlanner.plan("neoforge", diag, report(Map.of()), jars,
                Tombstones.load(Path.of("build/tmp/does-not-exist")), 40);

        assertEquals(2, plan.actions().size(), "两条 neoforge 问题必须合并成一条 A，不能重复升级");
        assertEquals(ActionKind.UPGRADE_LOADER, plan.actions().get(0).kind());
        assertEquals("21.1.247", plan.actions().get(0).target(),
                "目标版本要取所有要求里最高的下界（238 与 247 → 247）");
        assertEquals(ActionKind.INSTALL_DEP, plan.actions().get(1).kind());
        assertEquals("irons_spells_js", plan.actions().get(1).target(), "补装动作要记住是给谁补的");
    }

    @Test
    void 墓碑禁止回装_改走摘除依赖方并级联它自己的依赖者() {
        Tombstones tombs = Tombstones.load(Path.of("build/tmp/t1"));
        tombs.add("kubejs", "kubejs-1.jar", "上一轮判定它会导致崩溃", 1);
        Diagnosis diag = new Diagnosis(List.of(
                issue(Diagnosis.Kind.MISSING_REQUIRED, "kubejs", "irons_spells_js", "*", "[MISSING]")),
                List.of(), null, false, "n");
        Map<String, String> jars = Map.of(
                "irons_spells_js", "iss.js.jar", "quest_giver", "quest_giver.jar");
        // irons_spells_js 被摘 → 依赖它的 quest_giver 也要摘（缺前置必然崩）
        StaticDepsScanner.Report stat = report(Map.of("irons_spells_js", List.of("quest_giver")));

        ActionPlan plan = ActionPlanner.plan("neoforge", diag, stat, jars, tombs, 40);

        assertFalse(plan.actions().stream().anyMatch(a -> a.kind() == ActionKind.INSTALL_DEP),
                "进过墓碑的模组绝不能再被装入");
        List<String> removed = plan.actions().stream()
                .filter(a -> a.kind() == ActionKind.REMOVE_MOD).map(PlannedAction::modId).toList();
        assertTrue(removed.contains("irons_spells_js"), removed.toString());
        assertTrue(removed.contains("quest_giver"), "依赖方必须一起摘，否则下一轮必然再崩：" + removed);
        assertNotNull(plan.actions().stream().filter(a -> "quest_giver".equals(a.modId()))
                .findFirst().orElseThrow().cascadeOf(), "级联项要标出级联根，便于日志与还原");
    }

    @Test
    void 非依赖型故障只摘报错者自己不级联() {
        Diagnosis diag = new Diagnosis(List.of(
                issue(Diagnosis.Kind.MIXIN_FAILURE, "badmod", "badmod", "-", "-")),
                List.of(), null, false, "n");
        Map<String, String> jars = Map.of("badmod", "badmod.jar", "victim", "victim.jar");
        StaticDepsScanner.Report stat = report(Map.of("badmod", List.of("victim")));

        ActionPlan plan = ActionPlanner.plan("neoforge", diag, stat, jars,
                Tombstones.load(Path.of("build/tmp/t2")), 40);

        assertEquals(1, plan.actions().size(), "非依赖型故障没有级联能力，只能摘报错的那一个");
        assertEquals("badmod", plan.actions().get(0).modId());
        assertEquals("MIXIN_FAILURE", plan.actions().get(0).category());
        assertTrue(plan.actions().get(0).reason().contains("不做级联"));
    }

    @Test
    void 级联规模超上限时转人工而不是硬删() {
        Map<String, String> jars = new LinkedHashMap<>();
        jars.put("biglib", "biglib.jar");
        List<String> dependents = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            jars.put("m" + i, "m" + i + ".jar");
            dependents.add("m" + i);
        }
        StaticDepsScanner.Report stat = report(Map.of("biglib", dependents));
        Diagnosis diag = new Diagnosis(List.of(
                issue(Diagnosis.Kind.MIXIN_FAILURE, "biglib", "biglib", "-", "-")),
                List.of(), null, false, "n");

        // 非依赖型故障本来就不级联，所以这里用"补装失败→摘依赖方"的路径触发级联
        ActionPlanner.Fallback fb = ActionPlanner.fallbackRemoval(stat, jars, "biglib",
                "前置补装失败", List.of("原文"), 40);

        assertTrue(fb.actions().isEmpty(), "超限必须停下来，不能静默摘 51 个模组");
        assertNotNull(fb.blocked());
        assertTrue(fb.blocked().contains("超过上限"), fb.blocked());

        ActionPlanner.Fallback ok = ActionPlanner.fallbackRemoval(stat, jars, "biglib",
                "前置补装失败", List.of("原文"), 100);
        assertEquals(51, ok.actions().size());
    }

    @Test
    void 非NeoForge加载器不做就地升级_转为提示人工() {
        Diagnosis diag = new Diagnosis(List.of(
                issue(Diagnosis.Kind.LOADER_TOO_OLD, "forge", "somemod", "[47.5,)", "47.4.20")),
                List.of(), null, false, "n");
        ActionPlan plan = ActionPlanner.plan("forge", diag, report(Map.of()), Map.of(),
                Tombstones.load(Path.of("build/tmp/t3")), 40);
        assertTrue(plan.isEmpty());
        assertTrue(plan.blocked().get(0).contains("只支持就地升级 NeoForge"), plan.blocked().toString());
    }

    @Test
    void 版本号格式非法只摘声明它的那个模组() {
        Diagnosis diag = new Diagnosis(List.of(
                new Diagnosis.Issue(Diagnosis.Kind.BAD_VERSION_FORMAT, "mr_panda_temple",
                        "mr_panda_temple", "-", "1-V1-1.21+", "panda-temple-V1-1.21+.jar",
                        "Empty pre-release: 版本号 1-V1-1.21+ 唯一指向 mr_panda_temple")),
                List.of(), null, false, "n");
        Map<String, String> jars = Map.of(
                "mr_panda_temple", "panda-temple-V1-1.21+.jar",
                "preloading_tricks", "preloading-tricks-1.0.jar");
        // 就算有人依赖 panda-temple，也不级联（下一轮缺前置时再按既有规则处理）
        StaticDepsScanner.Report stat = report(Map.of("mr_panda_temple", List.of("victim")));

        ActionPlan plan = ActionPlanner.plan("neoforge", diag, stat, jars,
                Tombstones.load(Path.of("build/tmp/t4")), 40);

        assertEquals(1, plan.actions().size(), "只摘一个：" + plan.actions());
        assertEquals("BAD_VERSION_FORMAT", plan.actions().get(0).category());
        assertEquals("panda-temple-V1-1.21+.jar", plan.actions().get(0).jarFile());
        assertTrue(plan.actions().get(0).reason().contains("不做级联"));
    }

    @Test
    void 低置信的堆栈归因每轮最多采纳一个() {
        // 两条不同模组的堆栈归因：先采纳一条，另一条留到下一轮（一次误判摘一堆的代价太大）
        Diagnosis diag = new Diagnosis(List.of(
                issue(Diagnosis.Kind.STACK_ATTRIBUTED, "mod_a", "mod_a", "-", "-"),
                issue(Diagnosis.Kind.STACK_ATTRIBUTED, "mod_b", "mod_b", "-", "-")),
                List.of(), null, false, "n");
        Map<String, String> jars = Map.of("mod_a", "mod_a.jar", "mod_b", "mod_b.jar");

        ActionPlan plan = ActionPlanner.plan("neoforge", diag, report(Map.of()), jars,
                Tombstones.load(Path.of("build/tmp/t5")), 40);

        assertEquals(1, plan.actions().size(), "低置信渠道每轮只采纳一个：" + plan.actions());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("留到下一轮")), plan.notes().toString());
    }

    private static Diagnosis.Issue issue(Diagnosis.Kind kind, String modId, String requestedBy,
                                         String range, String actual) {
        return new Diagnosis.Issue(kind, modId, requestedBy, range, actual, null,
                "Mod ID: '" + modId + "', Requested by: '" + requestedBy + "'");
    }

    private static StaticDepsScanner.Report report(Map<String, List<String>> dependentsOf) {
        return new StaticDepsScanner.Report(0, 0, 0, List.of(), List.of(),
                new LinkedHashMap<>(dependentsOf), List.of(), "1.21.1", "neoforge", "21.1.231");
    }
}
