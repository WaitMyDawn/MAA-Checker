package yagen.waitmydawn.checker.action;

import yagen.waitmydawn.checker.core.Diagnosis;
import yagen.waitmydawn.checker.core.StaticDepsScanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从诊断结论生成动作计划。策略全部来自用户确认过的口径：
 *
 * <ol>
 *   <li><b>顺序 A → B → C</b>：先升加载器、再补前置、最后才摘模组（"先补装、补不到才级联"）；</li>
 *   <li><b>B 有墓碑就打住</b>：要补的前置若已在墓碑里，禁止再装，只能把"需要它的那个模组"摘掉；</li>
 *   <li><b>级联只在依赖型故障上做</b>：能解析出 JAR 依赖图时，摘掉 X 要连"依赖 X 的模组"一起摘
 *       （它们缺前置必然崩）；非依赖型故障（mixin 冲突、重复类、NPE）只摘报错的那一个自己；</li>
 *   <li><b>不做核心模组判断</b>：只按"依赖影响面"排序，能让游戏跑起来才是硬道理；</li>
 *   <li><b>级联规模上限</b>：一次级联超过 {@value #DEFAULT_CASCADE_LIMIT} 个模组时不自动执行，
 *       转为"需人工确认"——这是防止一次误判毁掉半个包的保险丝，不是策略。</li>
 * </ol>
 */
public final class ActionPlanner {

    public static final int DEFAULT_CASCADE_LIMIT = 40;

    private static final Pattern LOWER_BOUND = Pattern.compile("^[\\[(]\\s*([^,)\\]\\s]+)");

    private ActionPlanner() {
    }

    public static ActionPlan plan(String loader, Diagnosis diag, StaticDepsScanner.Report stat,
                                  Map<String, String> jarIndex, Tombstones tombs, int cascadeLimit) {
        List<PlannedAction> actions = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 低置信渠道（堆栈/类名反查）每轮最多采纳一个：一次误判摘掉一堆的代价太大
        boolean lowConfidenceUsed = false;

        // ---------- A：加载器版本 ----------
        List<Diagnosis.Issue> loaderIssues = diag.issues().stream()
                .filter(i -> i.kind() == Diagnosis.Kind.LOADER_TOO_OLD).toList();
        if (!loaderIssues.isEmpty()) {
            // A 目前只对 NeoForge 做出了"可验证的就地切换"（installer 有稳定 CLI + 产物 JSON 结构固定）。
            // 其它加载器不乱动：升级不了就如实中止，把决定权交回用户。
            if (!"neoforge".equals(loader)) {
                blocked.add("检测到 " + loader + " 版本不足（" + loaderIssues.size()
                        + " 个模组要求更高版本），但本工具目前只支持就地升级 NeoForge。"
                        + "请手动升级加载器后重新检验。");
                return new ActionPlan(actions, blocked, notes);
            }
            String min = null;
            for (Diagnosis.Issue i : loaderIssues) {
                String lb = lowerBound(i.expectedRange());
                if (lb != null && (min == null || yagen.waitmydawn.checker.core.VersionRanges
                        .compare(lb, min) > 0)) {
                    min = lb;
                }
            }
            actions.add(new PlannedAction(ActionKind.UPGRADE_LOADER, loader, null, "LOADER_TOO_OLD",
                    loaderIssues.size() + " 个模组要求更高的 " + loader + " 版本，当前 "
                            + loaderIssues.get(0).actualVersion() + "，需至少 " + min,
                    texts(loaderIssues), min, null, null));
        }

        // ---------- B / C：逐条问题 ----------
        for (Diagnosis.Issue i : diag.issues()) {
            switch (i.kind()) {
                case LOADER_TOO_OLD -> { /* 已在 A 里合并处理 */ }
                case MISSING_REQUIRED -> {
                    if (tombs.contains(i.modId())) {
                        // 前置被墓碑禁止补装 → 按规则只能摘掉依赖方（并级联它自己的依赖者）
                        cascade(actions, blocked, stat, jarIndex, tombs, seen, i.requestedBy(),
                                "MISSING_REQUIRED",
                                "前置 " + i.modId() + " 已进墓碑（此前判定它有害），禁止再装，"
                                        + "因此只能摘掉需要它的 " + i.requestedBy(),
                                texts(List.of(i)), cascadeLimit, notes);
                    } else {
                        String key = "INSTALL_DEP|" + i.modId();
                        if (seen.add(key)) {
                            actions.add(new PlannedAction(ActionKind.INSTALL_DEP, i.modId(), null,
                                    "MISSING_REQUIRED",
                                    i.requestedBy() + " 缺少必需前置 " + i.modId()
                                            + "（要求 " + i.expectedRange() + "）",
                                    texts(List.of(i)), i.requestedBy(), null, i.expectedRange()));
                        }
                    }
                }
                case MOD_LOAD_FAILURE, MIXIN_FAILURE, DUPLICATE_MOD, CRASH_OTHER,
                     BAD_VERSION_FORMAT, CAUSED_BY_ATTRIBUTED, STACK_ATTRIBUTED -> {
                    String culprit = i.modId() != null ? i.modId() : i.requestedBy();
                    if (culprit == null) {
                        blocked.add("发现 " + i.kind() + " 类故障，但日志里没有可定位的模组名，"
                                + "需要人工处理：" + i.detail());
                        break;
                    }
                    String jar = i.jarFile() != null ? i.jarFile() : jarIndex.get(culprit);
                    if (jar == null) {
                        blocked.add("定位到模组 " + culprit + "，但在 mods 目录里找不到它的 jar"
                                + "（可能是嵌套在其它 jar 里的附属），需要人工处理：" + i.detail());
                        break;
                    }
                    if (i.kind() == Diagnosis.Kind.STACK_ATTRIBUTED && lowConfidenceUsed) {
                        notes.add("本轮已采纳过 1 个'堆栈归因'摘除动作，这条留到下一轮（避免一次误判摘太多）："
                                + i.describe());
                        break;
                    }
                    if (seen.add("REMOVE_MOD|" + culprit)) {
                        actions.add(new PlannedAction(ActionKind.REMOVE_MOD, culprit, jar,
                                i.kind().name(),
                                "非依赖型故障（" + kindZh(i.kind()) + "）：只能摘除报错者自身，不做级联",
                                texts(List.of(i)), null, null, null));
                        if (i.kind() == Diagnosis.Kind.STACK_ATTRIBUTED) lowConfidenceUsed = true;
                    }
                }
                case MOD_VERSION_MISMATCH -> notes.add("模组版本区间不满足（静态判定可能与真实加载器语义不同），"
                        + "本轮不自动动作：" + i.describe());
            }
        }

        // 同一个模组既被建议摘除、又被建议补装它的前置时，以摘除为准（摘了就不用补）
        Set<String> removed = new LinkedHashSet<>();
        actions.stream().filter(a -> a.kind() == ActionKind.REMOVE_MOD)
                .forEach(a -> removed.add(a.modId()));
        actions.removeIf(a -> a.kind() == ActionKind.INSTALL_DEP && removed.contains(a.target()));

        return new ActionPlan(actions, blocked, notes);
    }

    /** 级联摘除：种子 + 所有"依赖种子"的模组（按 JAR 依赖图），逐个变成独立动作便于台账与还原 */
    private static void cascade(List<PlannedAction> actions, List<String> blocked,
                                StaticDepsScanner.Report stat, Map<String, String> jarIndex,
                                Tombstones tombs, Set<String> seen, String seed, String category,
                                String reason, List<String> errorText, int limit,
                                List<String> notes) {
        if (seed == null || jarIndex.get(seed) == null) {
            blocked.add("需要摘除 " + seed + "，但它在 mods 目录里找不到对应 jar（可能嵌套在别的 jar 里），"
                    + "需要人工处理");
            return;
        }
        LinkedHashSet<String> closure = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(seed);
        closure.add(seed);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String dep : stat.dependentsOf().getOrDefault(cur, List.of())) {
                if (closure.add(dep) && jarIndex.containsKey(dep)) queue.add(dep);
            }
        }
        closure.removeIf(m -> jarIndex.get(m) == null);      // 非顶层 jar 摘不掉，不进入级联
        if (closure.size() > limit) {
            blocked.add("摘除 " + seed + " 将级联 " + closure.size() + " 个模组（超过上限 " + limit
                    + "），为避免误判毁包，本轮不自动执行。受影响清单：" + String.join(", ", closure));
            return;
        }
        for (String m : closure) {
            if (!seen.add("REMOVE_MOD|" + m)) continue;
            boolean isSeed = m.equals(seed);
            actions.add(new PlannedAction(ActionKind.REMOVE_MOD, m, jarIndex.get(m),
                    isSeed ? category : "CASCADE(" + category + ")",
                    isSeed ? reason
                            : "级联：摘掉 " + seed + " 后，" + m + " 缺少必需前置会崩，因此一并摘除",
                    errorText, null, isSeed ? null : seed, null));
        }
        if (closure.size() > 1) {
            notes.add("依赖型级联：摘除 " + seed + " 连带 " + (closure.size() - 1)
                    + " 个依赖它的模组（" + String.join(", ", closure) + "）");
        }
    }

    /** 从 "[21.1.247,)" / "[5.13,6.0)" 里取最低要求版本 */
    static String lowerBound(String range) {
        if (range == null) return null;
        Matcher m = LOWER_BOUND.matcher(range.trim());
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 兜底路径：前置补装失败时，把"需要它的那个模组"摘掉（依赖型故障 → 允许级联）。
     *
     * @return 摘除动作清单 + 无法执行的原因（非空时 actions 一定是空的）
     */
    public static Fallback fallbackRemoval(StaticDepsScanner.Report stat,
                                           Map<String, String> jarIndex, String requester,
                                           String why, List<String> errorText, int cascadeLimit) {
        List<PlannedAction> actions = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        cascade(actions, blocked, stat, jarIndex, null, new HashSet<>(), requester,
                "MISSING_REQUIRED", why, errorText, cascadeLimit, notes);
        return new Fallback(actions, blocked.isEmpty() ? null : String.join("；", blocked));
    }

    public record Fallback(List<PlannedAction> actions, String blocked) {
    }

    private static String kindZh(Diagnosis.Kind k) {
        return switch (k) {
            case MIXIN_FAILURE -> "Mixin 应用失败/冲突";
            case DUPLICATE_MOD -> "重复模组或重复类";
            case MOD_LOAD_FAILURE -> "模组自身加载失败";
            case CRASH_OTHER -> "其它启动崩溃";
            case BAD_VERSION_FORMAT -> "版本号格式非法（加载器解析不了它声明的版本号）";
            case CAUSED_BY_ATTRIBUTED -> "崩溃链归因（Caused by 里的 from mod）";
            case STACK_ATTRIBUTED -> "堆栈归因（最低置信）";
            default -> k.name();
        };
    }

    private static List<String> texts(List<Diagnosis.Issue> issues) {
        List<String> out = new ArrayList<>();
        for (Diagnosis.Issue i : issues) {
            if (i.detail() != null && !i.detail().isBlank()) out.add(i.detail());
            out.add(i.describe());
        }
        return out;
    }
}
