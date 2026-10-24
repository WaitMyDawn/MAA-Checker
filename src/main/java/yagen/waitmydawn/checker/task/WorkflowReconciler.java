package yagen.waitmydawn.checker.task;

import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件事务账本与轮次事件的幂等对账。
 *
 * <p>事务账本记录实际文件动作，是事实来源；轮次事件是业务时间线的投影。动作提交与事件写入
 * 不属于同一个原子操作，进程可能在两者之间终止。对账只补齐缺失的事件，不重复执行文件动作，
 * 也不根据日志猜测。
 *
 * <p>无法安全补齐的情况（缺少事务账本、未完成动作、事件引用未知动作、事务未关联到任何轮次或
 * 恢复凭据）作为问题上报；调用方必须停止自动修复，不能把会话重置为新会话。
 */
final class WorkflowReconciler {

    /**
     * @param state          补齐事件后的状态；无变化时与输入同一实例
     * @param issues         需要人工处理或由恢复流程解决的问题
     * @param appendedEvents 本次补齐的事件数量
     */
    record Result(RepairWorkflow.State state, List<String> issues, int appendedEvents) {
        boolean changed() {
            return appendedEvents > 0;
        }
    }

    private WorkflowReconciler() {
    }

    /** 一个工作流引用的全部事务会话：各轮修复事务与快照恢复凭据。 */
    static Set<UUID> associations(RepairWorkflow.State state) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (RepairWorkflow.Round round : state.rounds()) ids.addAll(round.transactions());
        ids.addAll(state.restoreReceipts());
        if (state.restoreReceipt() != null) ids.add(state.restoreReceipt());
        return ids;
    }

    /**
     * 对账一个工作流状态。
     *
     * @param associatedElsewhere 其他工作流已关联的事务；用于区分“本会话漏关联”和“别的会话的事务”
     */
    static Result reconcile(GameInstance instance, Path stateRoot, RepairWorkflow.State state,
                            Set<UUID> associatedElsewhere) throws IOException {
        List<TransactionSession.History> histories = TransactionSession.history(stateRoot, instance);
        Map<UUID, TransactionSession.History> bySession = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        for (TransactionSession.History history : histories) {
            UUID id = history.header().sessionId();
            if (bySession.putIfAbsent(id, history) != null) issues.add("事务会话重复：" + id);
        }

        Set<UUID> known = new LinkedHashSet<>(associatedElsewhere);
        known.addAll(associations(state));
        Set<UUID> seenActions = new HashSet<>();
        List<RepairWorkflow.Round> rounds = new ArrayList<>(state.rounds());
        int appended = 0;

        for (int index = 0; index < rounds.size(); index++) {
            RepairWorkflow.Round round = rounds.get(index);
            List<RepairWorkflow.Event> events = new ArrayList<>(round.events());
            Set<UUID> roundActions = new LinkedHashSet<>();
            boolean changed = false;
            for (UUID transaction : round.transactions()) {
                TransactionSession.History history = bySession.get(transaction);
                if (history == null) {
                    issues.add("第 " + round.number() + " 轮缺少事务账本：" + transaction
                            + "（查找目录 " + stateRoot.toAbsolutePath().normalize() + "）");
                    continue;
                }
                for (ActionRecord action : history.actions()) {
                    if (!roundActions.add(action.id()) || !seenActions.add(action.id())) {
                        issues.add("动作 ID 重复：" + action.id());
                    }
                    if (action.round() != round.number()) {
                        issues.add("动作 " + action.filename() + " 的轮次 " + action.round()
                                + " 与第 " + round.number() + " 轮不符");
                    }
                    if (action.kind().name().startsWith("RESTORE_")) {
                        issues.add("第 " + round.number() + " 轮的修复事务包含恢复动作：" + action.filename());
                    }
                    if (action.unresolved()) {
                        issues.add("第 " + round.number() + " 轮存在未完成动作："
                                + action.filename() + "（" + action.state() + "）");
                        continue;
                    }
                    boolean recorded = events.stream().anyMatch(event -> event.committed() != null
                            && event.committed().id().equals(action.id()));
                    if (!recorded) {
                        if (action.state() == ActionRecord.State.APPLIED) {
                            events.add(new RepairWorkflow.Event(action.filename(), "已提交",
                                    action.reason() + "（会话对账补齐）", action));
                        } else {
                            // 恢复动作可能来自 PREPARED 或 APPLIED；无法证明曾经提交，不做虚假声明。
                            events.add(new RepairWorkflow.Event(action.filename(), "已恢复（提交记录缺失）",
                                    action.reason() + "；事务动作当前为 " + action.state()
                                            + "，无法确认是否曾提交（会话对账补齐）", action));
                        }
                        appended++;
                        changed = true;
                    }
                }
            }

            Set<UUID> eventActions = new HashSet<>();
            for (RepairWorkflow.Event event : events) {
                if (event.committed() == null) continue;
                if (!roundActions.contains(event.committed().id())) {
                    issues.add("第 " + round.number() + " 轮事件引用了未知动作："
                            + event.committed().filename());
                }
                if (!eventActions.add(event.committed().id())) {
                    issues.add("第 " + round.number() + " 轮动作被重复记录："
                            + event.committed().filename());
                }
            }
            if (changed) {
                rounds.set(index, new RepairWorkflow.Round(round.id(), round.number(), round.before(),
                        round.phase(), round.message(), events, round.transactions(), round.report()));
            }
        }

        Set<UUID> restoreSessions = new LinkedHashSet<>(state.restoreReceipts());
        if (state.restoreReceipt() != null) restoreSessions.add(state.restoreReceipt());
        for (UUID restore : restoreSessions) {
            TransactionSession.History history = bySession.get(restore);
            if (history == null) {
                issues.add("恢复凭据缺少事务账本：" + restore
                        + "（查找目录 " + stateRoot.toAbsolutePath().normalize() + "）");
                continue;
            }
            for (ActionRecord action : history.actions()) {
                if (!seenActions.add(action.id())) issues.add("动作 ID 重复：" + action.id());
                if (!action.kind().name().startsWith("RESTORE_")) {
                    issues.add("恢复凭据包含非恢复动作：" + action.filename());
                }
                if (action.unresolved()) {
                    issues.add("恢复凭据存在未完成动作：" + action.filename()
                            + "（" + action.state() + "）");
                }
            }
        }

        for (TransactionSession.History history : histories) {
            UUID id = history.header().sessionId();
            if (known.contains(id) || history.actions().isEmpty()) continue;
            issues.add("事务未关联到轮次或恢复凭据：" + id + "（" + history.actions().size() + " 个动作）");
        }

        RepairWorkflow.State reconciled = appended == 0 ? state
                : new RepairWorkflow.State(state.schema(), state.id(), state.instance(), state.createdAt(),
                        state.policy(), state.current(), state.phase(), state.message(), rounds,
                        state.decision(), state.restoreReceipt(), state.restoreTarget(),
                        state.restoreReceipts());
        return new Result(reconciled, List.copyOf(issues), appended);
    }

    /** 新工作流启动前检查：不允许存在已修改文件却未关联到任何轮次的事务。 */
    static List<String> unassociated(GameInstance instance, Path stateRoot, Set<UUID> known)
            throws IOException {
        List<String> issues = new ArrayList<>();
        for (TransactionSession.History history : TransactionSession.history(stateRoot, instance)) {
            if (known.contains(history.header().sessionId()) || history.actions().isEmpty()) continue;
            issues.add("事务未关联到轮次或恢复凭据：" + history.header().sessionId()
                    + "（" + history.actions().size() + " 个动作）");
        }
        return List.copyOf(issues);
    }
}
