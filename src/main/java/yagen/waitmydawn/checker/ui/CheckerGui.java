package yagen.waitmydawn.checker.ui;

import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceScanner;
import yagen.waitmydawn.checker.core.ProxyConfig;
import yagen.waitmydawn.checker.core.RunJournal;
import yagen.waitmydawn.checker.net.Http;
import yagen.waitmydawn.checker.net.NetworkAskers;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 图形界面（Swing，JDK 自带、零依赖、打包体积小；与网络弹窗同一套观感）。
 *
 * <p>三区布局，和用户确认的方案一致：
 * <ol>
 *   <li><b>配置区</b>：游戏目录 + 实例下拉 + 内存 + 网络状态（检测 / 设置代理）；</li>
 *   <li><b>运行区</b>：进度条 + 状态 + 实时日志（引擎的每一行都打在这里）；</li>
 *   <li><b>台账区</b>：列出每轮改了什么，可看内容、可一键还原、可打开产物目录。</li>
 * </ol>
 *
 * <p>长任务（启动游戏、下载模组）全部跑在后台线程，UI 更新一律回 EDT——
 * 界面不许卡，这是硬要求。
 */
public final class CheckerGui {

    private final Path toolRoot;
    private final Path gameDir;
    private ProxyConfig.Setting proxy;
    private final List<GameInstance> instances = new ArrayList<>();

    private final JFrame frame = new JFrame("MAA-Checker —— 整合包可行性检验");
    private final JTextField gameDirField = new JTextField(38);
    private final JComboBox<String> instanceBox = new JComboBox<>();
    private final JComboBox<String> memoryBox = new JComboBox<>(
            new String[]{"2G", "3G", "4G", "6G", "8G", "12G"});
    private final JComboBox<String> netPolicyBox = new JComboBox<>(
            new String[]{"询问我", "只做摘除", "直接中止"});
    /** 自动修复的初始轮次上限：用完还会问是否追加，所以这里只是"起点"，不再是天花板 */
    private final JSpinner roundsSpinner = new JSpinner(new SpinnerNumberModel(
            CheckerEngine.DEFAULT_FIX_ROUNDS, 1, CheckerEngine.MAX_TOTAL_FIX_ROUNDS, 1));
    private final JLabel proxyLabel = new JLabel();
    private final JLabel instanceInfo = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("就绪");
    private final JTextArea logArea = new JTextArea();
    private final DefaultListModel<String> ledgerModel = new DefaultListModel<>();
    private final JList<String> ledgerList = new JList<>(ledgerModel);
    private final JTextArea ledgerView = new JTextArea();
    private final List<Path> ledgerFiles = new ArrayList<>();
    private final JButton checkButton = new JButton("开始检验");
    private final JButton fixButton = new JButton("自动修复");
    private final JButton restoreButton = new JButton("还原到检验前");
    private final JButton testNetButton = new JButton("检测网络");
    private final JButton setProxyButton = new JButton("设置代理…");
    private final JButton openDirButton = new JButton("打开产物目录");
    private volatile boolean busy;

    private CheckerGui(Path toolRoot, Path gameDir, ProxyConfig.Setting proxy) {
        this.toolRoot = toolRoot;
        this.gameDir = gameDir;
        this.proxy = proxy;
    }

    public static void launch(Path toolRoot, Path gameDir, ProxyConfig.Setting proxy) {
        SwingUtilities.invokeLater(() -> new CheckerGui(toolRoot, gameDir, proxy).show());
    }

    private void show() {
        try {
            for (UIManager.LookAndFeelInfo lf : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(lf.getName())) {
                    UIManager.setLookAndFeel(lf.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // 用默认观感也能干活
        }
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.add(buildConfigPanel(), BorderLayout.NORTH);
        frame.add(buildCenterPanel(), BorderLayout.CENTER);
        frame.add(buildBottomPanel(), BorderLayout.SOUTH);
        frame.setMinimumSize(new Dimension(980, 680));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        refreshProxyLabel();
        Integer savedRounds = readSavedRounds();
        if (savedRounds != null) roundsSpinner.setValue(savedRounds);
        background(this::reloadInstances, "扫描实例…");
    }

    /** 上次用的轮次上限（记不住就用默认值；配置坏了也不能让界面起不来） */
    private Integer readSavedRounds() {
        try {
            String v = yagen.waitmydawn.checker.core.AppConfig.get(toolRoot,
                    yagen.waitmydawn.checker.core.AppConfig.KEY_FIX_ROUNDS);
            if (v == null) return null;
            return Math.max(1, Math.min(CheckerEngine.MAX_TOTAL_FIX_ROUNDS, Integer.parseInt(v.trim())));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 顶部：配置 + 网络

    private JPanel buildConfigPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("① 选择要检验的整合包实例"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        gameDirField.setText(gameDir == null ? "" : gameDir.toString());
        gameDirField.setEditable(false);
        JButton browse = new JButton("浏览…");
        browse.addActionListener(e -> chooseGameDir());

        c.gridx = 0;
        c.gridy = 0;
        p.add(new JLabel("游戏目录(.minecraft):"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(gameDirField, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(browse, c);

        JButton refresh = new JButton("刷新实例");
        refresh.addActionListener(e -> background(this::reloadInstances, "重新扫描实例…"));
        instanceBox.addActionListener(e -> onInstanceChanged());
        memoryBox.setSelectedItem("3G");

        c.gridx = 0;
        c.gridy = 1;
        p.add(new JLabel("实例:"), c);
        c.gridx = 1;
        p.add(instanceBox, c);
        c.gridx = 2;
        p.add(refresh, c);

        c.gridx = 0;
        c.gridy = 2;
        p.add(new JLabel("游戏内存:"), c);
        c.gridx = 1;
        p.add(memoryBox, c);
        c.gridx = 2;
        JPanel roundsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        roundsPanel.add(new JLabel("自动修复轮次上限:"));
        roundsPanel.add(roundsSpinner);
        p.add(roundsPanel, c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        p.add(instanceInfo, c);
        c.gridwidth = 1;

        JButton test = testNetButton;
        test.addActionListener(e -> background(this::testNetwork, "检测网络…"));
        setProxyButton.addActionListener(e -> askProxy());
        JPanel net = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        net.add(new JLabel("网络:"));
        net.add(proxyLabel);
        net.add(test);
        net.add(setProxyButton);
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 3;
        p.add(net, c);
        c.gridwidth = 1;
        return p;
    }

    private void chooseGameDir() {
        JFileChooser fc = new JFileChooser(gameDirField.getText());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择 .minecraft 目录");
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            // 允许选到第 2 层（有些启动器在 .minecraft/versions 下），这里只做最基本的方向提示
            String chosen = fc.getSelectedFile().getAbsolutePath();
            if (chosen.endsWith("versions")) {
                chosen = Paths.get(chosen).getParent() == null ? chosen
                        : Paths.get(chosen).getParent().toString();
            }
            gameDirField.setText(chosen);
            background(this::reloadInstances, "重新扫描实例…");
        }
    }

    private void refreshProxyLabel() {
        // 把三种"看起来有代理其实没用"的状态说清楚：端口在听但隧道不通 / 隧道通但出不去 / 可用
        proxyLabel.setText(proxy == null ? "未探测代理" : proxy.oneLine());
    }

    private void askProxy() {
        String cur = ProxyConfig.loadConfigured(toolRoot);
        String input = (String) JOptionPane.showInputDialog(frame,
                "填入代理地址（例如 http://127.0.0.1:7890）。\n"
                        + "国内线路直连 Modrinth 下载常常不通；Clash / V2Ray 打开【系统代理】"
                        + "的话，本工具会自动探测，这里可以留空。",
                "设置代理", JOptionPane.QUESTION_MESSAGE, null, null,
                cur == null ? "" : cur);
        if (input == null) return;
        try {
            if (input.isBlank()) {
                ProxyConfig.clear(toolRoot);
                proxy = ProxyConfig.resolve(null, false, toolRoot, null);
            } else {
                ProxyConfig.save(toolRoot, input.trim());
                proxy = ProxyConfig.resolve(input.trim(), false, toolRoot, null);
            }
            Http.setProxy(proxy.url());
            refreshProxyLabel();
            JOptionPane.showMessageDialog(frame, "已保存。当前: " + proxy.describe());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "保存失败: " + e.getMessage(),
                    "出错了", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void testNetwork() {
        log("=== 网络检测 ===");
        // 全部用 8 秒级探测：自检必须快速出结论，不能按"整包下载"的时限去等
        String api = Http.probe("https://api.modrinth.com/v2/search?limit=1", null, 8000);
        log("直连 Modrinth 接口: " + (api == null ? "✓ 可用" : "✗ " + api));
        String cdnDirect = Http.probe(PROBE_FILE, null, 8000);
        log("直连下载 CDN: " + (cdnDirect == null ? "✓ 可达" : "✗ 不可达（" + cdnDirect + "）"));
        boolean hasProxy = proxy != null && proxy.enabled();
        String viaProxy = hasProxy ? Http.probe(PROBE_FILE, proxy.url(), 8000) : null;
        if (hasProxy) {
            log("经代理 " + proxy.url() + ": "
                    + (viaProxy == null ? "✓ 可达" : "✗ 不可达（" + viaProxy + "）"));
        } else {
            log("未配置固定代理（工具仍会自动探测系统代理）");
        }
        log("提示：接口通 ≠ 能下载。国内线路常见现象是接口通、下载 CDN 不通——"
                + "这时打开 Clash / V2Ray 的系统代理即可，工具会自动用上。");
        boolean downloadOk = cdnDirect == null || viaProxy == null;
        setStatus(downloadOk ? "网络可用：能下载模组" : "下载不通：请打开代理（或点【设置代理…】）");
    }

    /** 自检用的公开小文件（kubejs 的某个版本），只用来看"CDN 通不通" */
    private static final String PROBE_FILE =
            "https://cdn.modrinth.com/data/umyGl7zF/versions/THIGFPwf/kubejs-neoforge"
                    + "-2101.7.2-build.377.jar";

    // ------------------------------------------------------------------ 中部：进度 + 日志

    private JPanel buildCenterPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("② 运行进度与实时日志"));
        progress.setStringPainted(true);
        progress.setIndeterminate(false);
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("进度:"), BorderLayout.WEST);
        top.add(progress, BorderLayout.CENTER);
        top.add(status, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ 底部：台账 + 按钮

    private JPanel buildBottomPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("③ 改动台账与还原"));
        ledgerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ledgerList.addListSelectionListener(e -> {
            int i = ledgerList.getSelectedIndex();
            if (i >= 0 && i < ledgerFiles.size()) showLedger(ledgerFiles.get(i));
        });
        ledgerView.setEditable(false);
        ledgerView.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(ledgerList), new JScrollPane(ledgerView));
        split.setDividerLocation(280);
        split.setPreferredSize(new Dimension(900, 220));
        p.add(split, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        checkButton.addActionListener(e -> start(false));
        fixButton.addActionListener(e -> start(true));
        restoreButton.addActionListener(e -> restore());
        openDirButton.addActionListener(e -> openDir(toolRoot));
        buttons.add(checkButton);
        buttons.add(fixButton);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(restoreButton);
        buttons.add(openDirButton);
        buttons.add(new JLabel("  网络不通时:"));
        buttons.add(netPolicyBox);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------------ 行为

    /**
     * 重新扫描实例。Swing 组件只能在 EDT 上碰，所以这里先回到 EDT 取参数、
     * 再把"读磁盘"放到后台线程，最后回 EDT 更新界面——避免"后台线程读下拉框"这种隐患。
     */
    private void reloadInstances() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadInstances);
            return;
        }
        Path dir = Paths.get(gameDirField.getText());
        String keep = instances.isEmpty() ? null : instances.get(
                Math.max(0, instanceBox.getSelectedIndex())).name();
        Thread.ofVirtual().unstarted(() -> {
            List<GameInstance> found = new ArrayList<>();
            try {
                found.addAll(InstanceScanner.scanAll(dir));
            } catch (Exception e) {
                log("扫描实例失败: " + e.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                instances.clear();
                instances.addAll(found);
                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                found.forEach(i -> model.addElement(i.describe()));
                instanceBox.setModel(model);
                int pick = 0;
                if (keep != null) {
                    for (int i = 0; i < found.size(); i++) {
                        if (found.get(i).name().equals(keep)) pick = i;
                    }
                }
                if (!found.isEmpty()) instanceBox.setSelectedIndex(pick);
                onInstanceChanged();
                reloadLedgers();
                if (!found.isEmpty()) {
                    // 记住这个目录：下次打开界面直接是它，不用再找一遍
                    yagen.waitmydawn.checker.core.AppConfig.set(toolRoot,
                            yagen.waitmydawn.checker.core.AppConfig.KEY_GAME_DIR, dir.toString());
                }
                log("识别到 " + found.size() + " 个实例（" + dir + "）");
                setStatus(found.isEmpty()
                        ? "没识别到实例：点【浏览…】选到你的 .minecraft 目录（含 versions 子目录）"
                        : "就绪");
            });
        }).start();
    }

    private void onInstanceChanged() {
        int i = instanceBox.getSelectedIndex();
        if (i < 0 || i >= instances.size()) return;
        GameInstance inst = instances.get(i);
        instanceInfo.setText("mc=" + inst.mcVersion() + "  loader=" + inst.loader() + " "
                + (inst.loaderVersion() == null ? "" : inst.loaderVersion())
                + "  模组 " + inst.modCount() + " 个");
    }

    private GameInstance selected() {
        int i = instanceBox.getSelectedIndex();
        return (i < 0 || i >= instances.size()) ? null : instances.get(i);
    }

    private CheckerEngine.Options options() {
        String policy = switch (netPolicyBox.getSelectedIndex()) {
            case 1 -> "remove-only";
            case 2 -> "abort";
            default -> "ask";
        };
        return CheckerEngine.Options.defaults()
                .withMx(String.valueOf(memoryBox.getSelectedItem()))
                .withRounds((Integer) roundsSpinner.getValue())
                .withProxy(proxy)
                .withAsker(NetworkAskers.dialog())
                .withRoundAsker(RoundAskers.dialog())
                .withNotifier(Notifiers.dialog())
                .withPolicy(policy);
    }

    /** @param fix true=自动修复，false=只跑一轮检验 */
    private void start(boolean fix) {
        GameInstance inst = selected();
        if (inst == null) {
            JOptionPane.showMessageDialog(frame, "请先选择一个实例。", "提示",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (busy) return;
        if (fix) {
            // 记住这次选的轮次上限，下次打开界面直接沿用
            yagen.waitmydawn.checker.core.AppConfig.set(toolRoot,
                    yagen.waitmydawn.checker.core.AppConfig.KEY_FIX_ROUNDS,
                    String.valueOf(roundsSpinner.getValue()));
        }
        if (CheckerEngine.recentlyActive(inst)) {
            int ok = JOptionPane.showConfirmDialog(frame,
                    "该实例的日志在 10 秒内还在写，可能游戏正开着。\n"
                            + "继续会让新启动的游戏和它互相干扰，确定继续吗？",
                    "疑似游戏正在运行", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
        }
        busy = true;
        setButtonsEnabled(false);
        progress.setIndeterminate(true);
        progress.setString("运行中…");
        setStatus(fix ? "自动修复中…" : "检验中…");
        CheckerEngine.Options o = options();
        // 修复/检验都很长（启动游戏 40~90 秒/轮），必须放后台线程，且不能阻塞 EDT
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                if (fix) {
                    CheckerEngine.fix(inst, toolRoot, o, this::log);
                } else {
                    CheckerEngine.check(inst, toolRoot, o, this::log);
                }
            } catch (Exception ex) {
                log("执行出错: " + ex);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    setButtonsEnabled(true);
                    progress.setIndeterminate(false);
                    progress.setValue(0);
                    progress.setString("");
                    setStatus("完成（详见日志与台账）");
                    reloadLedgers();
                    refreshInstanceInfo();
                });
            }
        });
        worker.start();
    }

    private void restore() {
        GameInstance inst = selected();
        if (inst == null) return;
        int ok = JOptionPane.showConfirmDialog(frame,
                "把该实例的改动还原到检验前？\n"
                        + "（按台账逐条反做：补装的删掉、摘除的移回、升过的加载器还原）",
                "确认还原", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION || busy) return;
        busy = true;
        setButtonsEnabled(false);
        setStatus("还原中…");
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                CheckerEngine.restore(inst, toolRoot, null, this::log);
            } catch (Exception ex) {
                log("还原出错: " + ex);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    setButtonsEnabled(true);
                    setStatus("还原完成");
                    reloadInstances();
                });
            }
        });
        worker.start();
    }

    // ------------------------------------------------------------------ 台账

    private void reloadLedgers() {
        GameInstance inst = selected();
        List<Path> files = new ArrayList<>();
        if (inst != null) {
            Path stateDir = toolRoot.resolve("state").resolve(RunJournal.safeName(inst.name()));
            if (Files.isDirectory(stateDir)) {
                try (var s = Files.list(stateDir)) {
                    files = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                            .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                                    .reversed())
                            .toList();
                } catch (Exception e) {
                    log("读取台账失败: " + e.getMessage());
                }
            }
        }
        List<Path> finalFiles = files;
        SwingUtilities.invokeLater(() -> {
            ledgerFiles.clear();
            ledgerFiles.addAll(finalFiles);
            ledgerModel.clear();
            for (Path f : finalFiles) ledgerModel.addElement(f.getFileName().toString());
            if (!finalFiles.isEmpty()) ledgerList.setSelectedIndex(0);
            else {
                ledgerView.setText("（该实例还没有改动台账：还没有执行过自动修复，"
                        + "或者修复过程中没有产生改动）");
            }
        });
    }

    private void showLedger(Path f) {
        try {
            ledgerView.setText(Files.readString(f, StandardCharsets.UTF_8));
            ledgerView.setCaretPosition(0);
        } catch (Exception e) {
            ledgerView.setText("读取失败: " + e.getMessage());
        }
    }

    private void refreshInstanceInfo() {
        Path dir = Paths.get(gameDirField.getText());
        GameInstance inst = selected();
        if (inst == null) return;
        GameInstance fresh = InstanceScanner.scanOne(dir, inst.dir());
        if (fresh != null) {
            int i = instanceBox.getSelectedIndex();
            instances.set(i, fresh);
            // 组合框里也要更新（先删再插，避免直接改 model 触发额外逻辑）
            instanceBox.removeItemAt(i);
            instanceBox.insertItemAt(fresh.describe(), i);
            instanceBox.setSelectedIndex(i);
        }
        onInstanceChanged();
    }

    // ------------------------------------------------------------------ 通用

    private void log(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setStatus(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    private void setButtonsEnabled(boolean enabled) {
        checkButton.setEnabled(enabled);
        fixButton.setEnabled(enabled);
        restoreButton.setEnabled(enabled);
        testNetButton.setEnabled(enabled);
        setProxyButton.setEnabled(enabled);
        instanceBox.setEnabled(enabled);
        memoryBox.setEnabled(enabled);
    }

    private void background(Runnable task, String statusText) {
        setStatus(statusText);
        Thread.ofVirtual().unstarted(task).start();
    }

    private void openDir(Path dir) {
        try {
            if (!Files.isDirectory(dir)) Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "打不开目录: " + e.getMessage(),
                    "出错了", JOptionPane.ERROR_MESSAGE);
        }
    }
}
