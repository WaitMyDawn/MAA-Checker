package yagen.waitmydawn.checker.transaction;

import yagen.waitmydawn.checker.core.RepairInputs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 轮次快照的恢复计划：差异计算只说明“改什么”，本类说明“按什么顺序改”。
 *
 * <p>执行顺序固定为 <b>模组 → 版本配置 → HMCL 标记</b>：
 * <ul>
 *   <li>模组先落盘，避免目标版本配置切换到仍缺少模组的状态；</li>
 *   <li>版本配置在模组之后切换；调用方须在切换前确认它引用的实例运行库已存在；</li>
 *   <li>标记最后恢复（或在目标状态中不存在时最后停用），避免恢复尚未完成时
 *       启动器按整合包清单补回被摘除的文件。</li>
 * </ul>
 *
 * <p>摘要覆盖工作流/轮次身份、当前与目标输入指纹以及有序步骤。预览与执行必须比较同一摘要；
 * 摘要只用于恢复流程的内存对象，不改变快照范围，也不持久化到轮次记录。
 */
public record RestorePlan(UUID workflow, UUID round, String currentDigest, String targetDigest,
                          List<Step> steps) {

    /** 语义阶段；枚举顺序同时是执行顺序。 */
    public enum Phase { MOD, VERSION, MARKER }

    /**
     * 单个恢复步骤。
     *
     * @param fromHash 当前内容摘要；{@code null} 表示当前不存在
     * @param toHash   目标内容摘要；{@code null} 表示目标状态中不存在
     */
    public record Step(Phase phase, String key, String fromHash, String toHash) {

        public Step {
            Objects.requireNonNull(phase, "phase");
            if (key == null || key.isBlank()) throw new IllegalArgumentException("恢复步骤缺少目标");
            if (fromHash == null && toHash == null) throw new IllegalArgumentException("恢复步骤没有内容变化");
        }
    }

    public RestorePlan {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(round, "round");
        Objects.requireNonNull(currentDigest, "currentDigest");
        Objects.requireNonNull(targetDigest, "targetDigest");
        steps = List.copyOf(steps);
    }

    /**
     * 由当前输入与目标快照输入生成语义化恢复计划。
     *
     * @throws IllegalArgumentException 快照包含无法归类的前缀（防止未来新增对象被静默按字符串排序）
     */
    public static RestorePlan between(UUID workflow, UUID round, RepairInputs current, RepairInputs desired) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(desired, "desired");
        Set<String> keys = new TreeSet<>(current.hashes().keySet());
        keys.addAll(desired.hashes().keySet());
        List<Step> steps = new ArrayList<>();
        for (String key : keys) {
            String from = current.hashes().get(key);
            String to = desired.hashes().get(key);
            if (Objects.equals(from, to)) continue;
            steps.add(new Step(phaseOf(key), key, from, to));
        }
        // 同一阶段内按键名稳定排序；跨阶段由 Phase 枚举顺序决定，不能直接使用字符串排序。
        steps.sort(Comparator.comparing((Step step) -> step.phase().ordinal()).thenComparing(Step::key));
        return new RestorePlan(workflow, round, inputsDigest(current), inputsDigest(desired), List.copyOf(steps));
    }

    public boolean empty() {
        return steps.isEmpty();
    }

    /** 按执行顺序返回恢复目标键；用于与既有预览调用方保持兼容。 */
    public List<String> keys() {
        return steps.stream().map(Step::key).toList();
    }

    /** 计划身份摘要；包含有序步骤，因此顺序或内容变化都会得到不同摘要。 */
    public String digest() {
        StringBuilder canonical = new StringBuilder();
        canonical.append("workflow=").append(workflow).append('\n');
        canonical.append("round=").append(round).append('\n');
        canonical.append("current=").append(currentDigest).append('\n');
        canonical.append("target=").append(targetDigest).append('\n');
        for (Step step : steps) {
            canonical.append("step=").append(step.phase().ordinal()).append('|')
                    .append(step.key()).append('|')
                    .append(value(step.fromHash())).append('|')
                    .append(value(step.toHash())).append('\n');
        }
        return sha256(canonical.toString());
    }

    public String shortDigest() {
        return digest().substring(0, 12);
    }

    /** 人读摘要，供日志与预览说明使用。 */
    public String summary() {
        long mods = steps.stream().filter(step -> step.phase() == Phase.MOD).count();
        long versions = steps.stream().filter(step -> step.phase() == Phase.VERSION).count();
        long markers = steps.stream().filter(step -> step.phase() == Phase.MARKER).count();
        return "模组 " + mods + " 项、版本配置 " + versions + " 项、HMCL 标记 " + markers + " 项";
    }

    static Phase phaseOf(String key) {
        if (key.equals("marker")) return Phase.MARKER;
        if (key.equals("version")) return Phase.VERSION;
        if (key.startsWith("mod/")) return Phase.MOD;
        throw new IllegalArgumentException("未知的轮次快照目标: " + key);
    }

    private static String inputsDigest(RepairInputs inputs) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("versionFile=").append(inputs.versionFile()).append('\n');
        canonical.append("modsDirectory=").append(inputs.modsDirectory()).append('\n');
        new TreeMap<>(inputs.hashes()).forEach((key, hash) ->
                canonical.append(key).append('=').append(hash).append('\n'));
        return sha256(canonical.toString());
    }

    private static String value(String hash) {
        return hash == null ? "-" : hash;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
