package yagen.waitmydawn.checker.core;

/**
 * 一次启动检验的结论。
 *
 * <p>四态而不是布尔：PASS / FAIL 是"有明确证据"的结论；TIMEOUT 与 EXITED 是"证据不足"，
 * 三者对用户的含义完全不同（前者要修包，后两者要人看日志），不能压成 true/false。
 */
public enum LaunchVerdict {
    /** 命中后期成功标记，且在观察窗内没崩、进程还活着 */
    PASS,
    /** 命中崩溃/加载失败标记，或进程非 0 退出 */
    FAIL,
    /** 超时未见任何结论性标记 */
    TIMEOUT,
    /** 进程自己退出了（退出码 0），但没命中成功标记——通常是玩家/环境层面提前关闭 */
    EXITED
}
