package yagen.waitmydawn.checker.core;

/**
 * 自动修复"轮次用完还没修好"时向用户提问的方式（与 {@code NetworkManager.Asker} 同一套思路：
 * 引擎只负责问，控制台/弹窗/固定答案三种实现各管各的交互）。
 *
 * <p>为什么要问：一轮 = 一次真实启动（40~90 秒）+ 一次改动，轮次上限设成 4 只是"默认值"，
 * 不该变成"天花板"。有些包确实要更多轮才收敛，用户也可能想立刻收手甚至回滚——
 * 这时候把三条路摆给用户，比工具自己猜要好。
 */
public interface RoundAsker {

    /** 问用户的问题 */
    record Ask(String title, String message, int doneRounds, int maxRounds, int maxTotalRounds,
               boolean canExtend, int defaultExtraRounds) {
    }

    enum Choice {
        /** 追加轮次，继续修 */
        EXTEND,
        /** 停止，保留已经做的改动（可随时【还原】） */
        STOP_KEEP,
        /** 停止并把本次会话的改动全部还原 */
        STOP_AND_RESTORE
    }

    record Decision(Choice choice, int extraRounds) {

        public static Decision of(Choice c) {
            return new Decision(c, 0);
        }

        public static Decision extend(int extra) {
            return new Decision(Choice.EXTEND, extra);
        }

        public static Decision stopKeep() {
            return of(Choice.STOP_KEEP);
        }

        public static Decision stopAndRestore() {
            return of(Choice.STOP_AND_RESTORE);
        }
    }

    Decision ask(Ask ask);
}
