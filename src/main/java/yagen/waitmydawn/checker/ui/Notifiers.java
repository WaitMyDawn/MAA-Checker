package yagen.waitmydawn.checker.ui;

import yagen.waitmydawn.checker.core.Notifier;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * {@link Notifier} 的两个实现：弹窗（GUI / {@code --dialog}）与控制台（CLI）。
 *
 * <p>与 {@code NetworkAskers} / {@code RoundAskers} 同风格：Swing 是 JDK 自带的，零依赖。
 */
public final class Notifiers {

    private Notifiers() {
    }

    /** 弹窗：标题 + 多行说明，用户点掉才继续 */
    public static Notifier dialog() {
        return (title, message) -> {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            for (String line : message.split("\\R")) {
                panel.add(new JLabel(line.isEmpty() ? " " : line));
            }
            JOptionPane.showMessageDialog(null, panel, "MAA-Checker — " + title,
                    JOptionPane.INFORMATION_MESSAGE);
        };
    }

    /** 控制台：CLI 用，醒目地打一段 */
    public static Notifier console() {
        return (title, message) -> {
            System.out.println();
            System.out.println("┌─ " + title);
            for (String line : message.split("\\R")) System.out.println("│ " + line);
            System.out.println("└─");
            System.out.println();
        };
    }
}
