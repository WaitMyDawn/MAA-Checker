package yagen.waitmydawn.checker.net;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * {@link NetworkManager.Asker} 的两个实现：控制台（CLI）与弹窗（GUI / {@code --dialog}）。
 *
 * <p>为什么用 Swing 而不是 JavaFX：JDK 自带 Swing，弹窗零依赖、零打包成本；
 * 将来做正式 GUI 时只要实现同一个 {@code Asker} 接口，逻辑一行都不用改。
 */
public final class NetworkAskers {

    private NetworkAskers() {
    }

    /** 控制台问答：适合命令行用户与自动化脚本（stdin 关闭时返回 STOP，绝不挂死） */
    public static NetworkManager.Asker console(Consumer<String> log) {
        return ask -> {
            System.out.println();
            System.out.println("──────── " + ask.title() + " ────────");
            for (String line : ask.message().split("\\R")) System.out.println("  " + line);
            if (ask.offerProxyInput()) {
                System.out.print("  代理地址（直接回车 = 不填）: ");
                System.out.flush();
            }
            String input = null;
            try {
                if (System.console() == null) {
                    // 没有交互终端（被管道/服务调用）：绝不能在这里"假装用户选了某项"，要说清楚
                    System.out.println("  ⚠️ 当前不是交互式终端（无人值守），按【停止检验并还原】处理。");
                    System.out.println("     想让它自动跑，请用 --net remove-only（只做摘除）"
                            + "或先 --set-proxy 设好代理。");
                    return NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE);
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(System.in,
                        StandardCharsets.UTF_8));
                if (ask.offerProxyInput()) input = r.readLine();
                System.out.println();
                System.out.println("  请选择: [1] 已开好代理，继续重试   [2] 只做摘除并继续   "
                        + "[3] 停止检验并还原");
                System.out.print("  输入 1/2/3: ");
                System.out.flush();
                String pick = r.readLine();
                if (pick == null) return NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE);
                return switch (pick.strip()) {
                    case "2" -> NetworkManager.Answer.of(NetworkManager.Choice.REMOVE_ONLY);
                    case "3" -> NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE);
                    default -> new NetworkManager.Answer(NetworkManager.Choice.CONTINUE, input);
                };
            } catch (Exception e) {
                if (log != null) log.accept("   （读不到输入，按【停止检验并还原】处理）");
                return NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE);
            }
        };
    }

    /**
     * 弹窗问答：标题 + 说明 + 可输入的代理地址 + 三个按钮。
     * 只有真正需要时才弹（直连失败且没有可用代理），不会打扰正常的检验流程。
     */
    public static NetworkManager.Asker dialog() {
        return ask -> {
            JPanel panel = new JPanel();
            panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
            for (String line : ask.message().split("\\R")) {
                panel.add(new JLabel(line.isEmpty() ? " " : line));
            }
            JTextField proxyField = null;
            if (ask.offerProxyInput()) {
                panel.add(new JLabel(" "));
                panel.add(new JLabel("代理地址（例如 http://127.0.0.1:7890）："));
                proxyField = new JTextField(ask.currentProxy() == null ? "" : ask.currentProxy(), 28);
                panel.add(proxyField);
            }
            Object[] options = {"已开好代理，继续", "只做摘除并继续", "停止检验并还原"};
            int pick = JOptionPane.showOptionDialog(null, panel, "MAA-Checker — " + ask.title(),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            return switch (pick) {
                case 1 -> NetworkManager.Answer.of(NetworkManager.Choice.REMOVE_ONLY);
                case 2 -> NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE);
                case 0 -> new NetworkManager.Answer(NetworkManager.Choice.CONTINUE,
                        proxyField == null ? null : proxyField.getText());
                default -> NetworkManager.Answer.of(NetworkManager.Choice.STOP_AND_RESTORE);
            };
        };
    }
}
