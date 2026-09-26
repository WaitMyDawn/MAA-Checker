package yagen.waitmydawn.checker.ui;

import yagen.waitmydawn.checker.core.RoundAsker;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * {@link RoundAsker} 的三个实现：弹窗（GUI / {@code --dialog}）、控制台（CLI）、固定答案（无人值守）。
 *
 * <p>与 {@code NetworkAskers} 同风格：Swing 是 JDK 自带的，零依赖零打包成本。
 * 无人值守场景（管道、服务调用）绝不阻塞——控制台实现在没有交互终端时直接返回"停止"，
 * 要脚本自己决定就显式传 {@code --on-exhausted stop|restore}。
 */
public final class RoundAskers {

    private RoundAskers() {
    }

    /** 弹窗：三个按钮 + "追加几轮"输入框 */
    public static RoundAsker dialog() {
        return ask -> {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            for (String line : ask.message().split("\\R")) {
                panel.add(new JLabel(line.isEmpty() ? " " : line));
            }
            JSpinner extra = null;
            if (ask.canExtend()) {
                int max = Math.max(1, ask.maxTotalRounds() - ask.maxRounds());
                panel.add(new JLabel(" "));
                panel.add(new JLabel("追加轮数（最多还能加到 " + ask.maxTotalRounds() + " 轮）："));
                extra = new JSpinner(new SpinnerNumberModel(
                        Math.min(ask.defaultExtraRounds(), max), 1, max, 1));
                panel.add(extra);
            }
            Object[] options = ask.canExtend()
                    ? new Object[]{"追加轮次并继续", "停止（保留改动）", "停止并还原到检验前"}
                    : new Object[]{"停止（保留改动）", "停止并还原到检验前"};
            int pick = JOptionPane.showOptionDialog(null, panel, "MAA-Checker — " + ask.title(),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (!ask.canExtend()) {
                return pick == 1 ? RoundAsker.Decision.stopAndRestore()
                        : RoundAsker.Decision.stopKeep();
            }
            return switch (pick) {
                case 0 -> RoundAsker.Decision.extend(((Number) extra.getValue()).intValue());
                case 2 -> RoundAsker.Decision.stopAndRestore();
                default -> RoundAsker.Decision.stopKeep();
            };
        };
    }

    /** 控制台：没有交互终端（被管道/服务调用）时按【停止】处理，绝不挂死 */
    public static RoundAsker console(Consumer<String> log) {
        return ask -> {
            System.out.println();
            System.out.println("──────── " + ask.title() + " ────────");
            for (String line : ask.message().split("\\R")) System.out.println("  " + line);
            boolean interactive = System.console() != null;
            if (ask.canExtend()) {
                System.out.println();
                System.out.println("  请选择: [1] 追加轮次继续   [2] 停止（保留改动）   [3] 停止并还原");
            } else {
                System.out.println();
                System.out.println("  请选择: [2] 停止（保留改动）   [3] 停止并还原");
            }
            if (!interactive) {
                System.out.println("  ⚠️ 当前不是交互式终端（无人值守），按【停止（保留改动）】处理；"
                        + "想脚本化请用 --on-exhausted stop|restore");
                return RoundAsker.Decision.stopKeep();
            }
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(System.in,
                        StandardCharsets.UTF_8));
                System.out.print("  输入: ");
                System.out.flush();
                String pick = r.readLine();
                if (pick == null) return RoundAsker.Decision.stopKeep();
                String p = pick.strip();
                if ("3".equals(p)) return RoundAsker.Decision.stopAndRestore();
                if ("1".equals(p) && ask.canExtend()) {
                    System.out.print("  追加几轮（默认 " + ask.defaultExtraRounds() + "）: ");
                    System.out.flush();
                    String n = r.readLine();
                    int extra = ask.defaultExtraRounds();
                    try {
                        if (n != null && !n.isBlank()) extra = Integer.parseInt(n.strip());
                    } catch (NumberFormatException ignored) {
                        // 输入不是数字就用默认值，不因为手滑把流程打断
                    }
                    return RoundAsker.Decision.extend(extra);
                }
                return RoundAsker.Decision.stopKeep();
            } catch (Exception e) {
                if (log != null) log.accept("   （读不到输入，按【停止（保留改动）】处理）");
                return RoundAsker.Decision.stopKeep();
            }
        };
    }

    /** 固定答案：无人值守 / {@code --on-exhausted stop|restore} 用 */
    public static RoundAsker fixed(RoundAsker.Choice choice) {
        return ask -> RoundAsker.Decision.of(choice);
    }
}
