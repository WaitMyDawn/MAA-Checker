package yagen.waitmydawn.checker.action;

import java.util.List;

/**
 * 一轮的动作计划。
 *
 * @param actions 可自动执行的动作（顺序 A → B → C，与用户确认的"先补装、补不到才级联"一致）
 * @param blocked 想做但做不了的事（如"元凶无法定位""级联规模过大"），必须原样展示，不能静默跳过
 * @param notes   说明性信息（如"minecraft 区间不满足属静态误判，不动作"）
 */
public record ActionPlan(List<PlannedAction> actions, List<String> blocked, List<String> notes) {

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public long countOf(ActionKind kind) {
        return actions.stream().filter(a -> a.kind() == kind).count();
    }
}
