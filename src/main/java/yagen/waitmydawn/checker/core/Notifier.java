package yagen.waitmydawn.checker.core;

/**
 * 单向通知（只告诉用户发生了什么，不等他做选择）。
 *
 * <p>和 {@link RoundAsker} 的区别：那个要用户**选**（追加轮次/停止/还原），这个只**告知**。
 * GUI 用弹窗（用户必须点掉才知道），CLI 打到控制台，无人值守可以给 {@link #SILENT}。
 */
public interface Notifier {

    void notify(String title, String message);

    /** 什么都不做（无人值守 / 单测） */
    Notifier SILENT = (title, message) -> {
    };
}
