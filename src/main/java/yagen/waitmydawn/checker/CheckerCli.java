package yagen.waitmydawn.checker;

import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.Diagnosis;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceLauncher;
import yagen.waitmydawn.checker.core.InstanceScanner;
import yagen.waitmydawn.checker.core.JavaLocator;
import yagen.waitmydawn.checker.core.LibraryRepair;
import yagen.waitmydawn.checker.core.LaunchSpec;
import yagen.waitmydawn.checker.core.LogDiagnoser;
import yagen.waitmydawn.checker.core.ProxyConfig;
import yagen.waitmydawn.checker.core.StaticDepsScanner;
import yagen.waitmydawn.checker.net.Http;
import yagen.waitmydawn.checker.net.NetworkAskers;
import yagen.waitmydawn.checker.net.NetworkManager;
import yagen.waitmydawn.checker.ui.CheckerGui;
import yagen.waitmydawn.checker.task.CheckerTasks;
import yagen.waitmydawn.checker.task.RepairPolicy;
import yagen.waitmydawn.checker.task.RepairWorkflow;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskEvent;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 命令行入口。
 *
 * <p>负责解析参数、装配选项并分发到新版任务服务或显式旧版兼容入口。检查始终使用
 * {@link CheckerTasks}；修复和还原默认使用 {@link RepairWorkflow}，旧 A/B/C 台账只能通过
 * {@code --legacy-fix/--legacy-restore} 显式启用。
 *
 * <p>不带任何参数启动 = 打开图形界面（双击 jar 或 launcher 的 launch.bat 就是这个路径）。
 */
public final class CheckerCli {

    public static void main(String[] args) throws Exception {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            System.err.println("MAA-Checker 执行失败：" + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    /** 可直接测试的命令行执行入口；除启动 Swing/JavaFX 外不主动终止 JVM。 */
    static int run(String[] args) throws Exception {
        // 必须在任何网络类初始化之前执行：实测 Java 走 IPv6 连 maven.neoforged.net 会卡死到超时，
        // 强制 IPv4 后同一文件 3.5s 下完（curl 两种协议栈都正常）。想用 IPv6 可加 -Dmaa.preferIPv6=true。
        if (!"true".equalsIgnoreCase(System.getProperty("maa.preferIPv6"))) {
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
        Path gameDir = null;   // 未显式指定时：先用上次记住的，再自动探测
        Path toolRoot = resolveToolRoot();
        String target = null;
        String mode = args.length == 0 ? "gui" : null;
        String mx = "3G";
        int timeout = 180;
        int grace = 15;
        int rounds = yagen.waitmydawn.checker.core.CheckerEngine.DEFAULT_FIX_ROUNDS;
        int repeat = 1;
        int flakyRetry = 1;
        int cascadeLimit = yagen.waitmydawn.checker.action.ActionPlanner.DEFAULT_CASCADE_LIMIT;
        boolean list = false;
        boolean listJava = false;
        boolean force = false;
        boolean dryRun = false;
        boolean dialog = false;
        boolean gui = false;
        boolean allowDirectCdn = false;
        boolean allowMirrorFile = true;
        String netPolicy = "ask";
        // 轮次用完还没修好怎么办：ask=问用户（默认）/ stop=停止保留改动 / restore=停止并还原
        String onExhausted = "ask";
        String cliProxy = null;
        boolean noProxy = false;
        String setProxy = null;
        boolean clearProxy = false;
        boolean testProxy = false;
        String probeMc = "1.21.1";
        String probeLoader = "neoforge";
        Integer restoreRound = null;
        String policyChoice = "saved";
        boolean policyExplicit = false;
        boolean roundsExplicit = false;
        boolean onExhaustedExplicit = false;
        UUID workflowId = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--game-dir" -> gameDir = Paths.get(args[++i]);
                case "--tool-root" -> toolRoot = Paths.get(args[++i]).toAbsolutePath();
                case "--list" -> list = true;
                case "--java" -> listJava = true;
                case "--gui" -> gui = true;
                case "--scan" -> {
                    mode = "scan";
                    target = args[++i];
                }
                case "--check" -> {
                    mode = "check";
                    target = args[++i];
                }
                case "--fix" -> {
                    mode = "fix";
                    target = args[++i];
                }
                case "--legacy-fix" -> {
                    mode = "legacy-fix";
                    target = args[++i];
                }
                case "--restore" -> {
                    mode = "restore";
                    target = args[++i];
                }
                case "--legacy-restore" -> {
                    mode = "legacy-restore";
                    target = args[++i];
                }
                case "--diagnose" -> {
                    mode = "diagnose";
                    target = args[++i];
                }
                case "--repair-libraries" -> {
                    mode = "repair-libraries";
                    target = args[++i];
                }
                case "--probe-dep" -> {
                    mode = "probe-dep";
                    target = args[++i];
                }
                case "--round" -> restoreRound = Integer.parseInt(args[++i]);
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--retry" -> flakyRetry = Math.max(0, Math.min(3, Integer.parseInt(args[++i])));
                case "--dialog" -> dialog = true;
                case "--net" -> netPolicy = args[++i];
                case "--direct-cdn" -> allowDirectCdn = true;
                case "--no-mirror" -> allowMirrorFile = false;
                case "--mx" -> mx = args[++i];
                case "--timeout" -> timeout = Integer.parseInt(args[++i]);
                case "--grace" -> grace = Integer.parseInt(args[++i]);
                case "--rounds" -> {
                    rounds = Math.max(1, Math.min(
                            CheckerEngine.MAX_TOTAL_FIX_ROUNDS, Integer.parseInt(args[++i])));
                    roundsExplicit = true;
                }
                case "--on-exhausted" -> {
                    onExhausted = args[++i];
                    onExhaustedExplicit = true;
                }
                case "--policy" -> {
                    policyChoice = args[++i];
                    policyExplicit = true;
                }
                case "--workflow" -> {
                    try {
                        workflowId = UUID.fromString(args[++i]);
                    } catch (IllegalArgumentException e) {
                        System.err.println("--workflow 需要有效 UUID");
                        return 2;
                    }
                }
                case "--cascade-limit" -> cascadeLimit = Integer.parseInt(args[++i]);
                case "--dry-run" -> dryRun = true;
                case "--force" -> force = true;
                case "--proxy" -> cliProxy = args[++i];
                case "--no-proxy" -> noProxy = true;
                case "--set-proxy" -> setProxy = args[++i];
                case "--clear-proxy" -> clearProxy = true;
                case "--test-proxy" -> testProxy = true;
                case "--mc" -> probeMc = args[++i];
                case "--loader" -> probeLoader = args[++i];
                case "--help", "-h" -> {
                    printHelp();
                    return 0;
                }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    printHelp();
                    return 2;
                }
            }
        }
        if (workflowId != null && !"restore".equals(mode)) {
            System.err.println("--workflow 只能与新版 --restore 一起使用。");
            return 2;
        }
        if (policyExplicit && !"fix".equals(mode) && !"legacy-fix".equals(mode)) {
            System.err.println("--policy 只能与 --fix 或 --legacy-fix 一起使用。");
            return 2;
        }
        try {
            CliWorkflow.policyChoice(policyChoice);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        String exhausted = onExhausted.toLowerCase(java.util.Locale.ROOT);
        if (!exhausted.equals("ask") && !exhausted.equals("stop")
                && !exhausted.equals("keep") && !exhausted.equals("restore")) {
            System.err.println("--on-exhausted 只允许 ask、stop、keep 或 restore：" + onExhausted);
            return 2;
        }

        if (setProxy != null) {
            ProxyConfig.save(toolRoot, setProxy);
            String err = ProxyConfig.probe(ProxyConfig.loadConfigured(toolRoot));
            System.out.println("已保存代理设置到 " + toolRoot.resolve("config.properties") + "："
                    + ProxyConfig.loadConfigured(toolRoot));
            System.out.println(err == null ? "探活：可用 ✓"
                    : "探活：失败（" + err + "）——仍已保存，可用 --test-proxy 复查");
            return 0;
        }
        if (clearProxy) {
            ProxyConfig.clear(toolRoot);
            System.out.println("已清除软件里的代理设置（仍会自动探测系统代理）。");
            return 0;
        }
        // 代理优先级：--proxy > 软件设置 > 自动探测系统代理；探测到的会先探活，不通自动回退直连
        ProxyConfig.Setting proxySetting = ProxyConfig.resolve(cliProxy, noProxy, toolRoot,
                System.out::println);
        // 只有"端到端可用"的代理才装配到 HttpClient 上；探测到但不通的（端口在听/隧道通但出不去）
        // 一律当没有代理，避免把请求塞进一个死代理里等超时
        Http.setProxy(proxySetting.usable() ? proxySetting.url() : null);

        if (gameDir == null) gameDir = yagen.waitmydawn.checker.core.GameDirLocator.detect(toolRoot);
        if (gui || "gui".equals(mode)) {
            CheckerGui.launch(toolRoot, gameDir, proxySetting);
            return 0;
        }
        if (testProxy) {
            return doTestProxy(proxySetting);
        }
        if ("probe-dep".equals(mode)) {
            return doProbeDep(target, probeMc, probeLoader);
        }
        if ("diagnose".equals(mode)) {
            return doDiagnose(target);
        }
        if ("repair-libraries".equals(mode)) {
            // 需要在下面解析实例，所以放到实例查找之后处理（见 switch）
        }
        if (listJava) {
            printJava(toolRoot);
            return 0;
        }
        if (!Files.isDirectory(gameDir)) {
            System.err.println("游戏目录不存在: " + gameDir);
            return 2;
        }
        List<GameInstance> instances;
        try (TaskService tasks = new TaskService()) {
            var result = tasks.submit(gameDir.toString(), CheckerTasks.scan(gameDir), null)
                    .completion().toCompletableFuture().get();
            if (result.value() == null) throw new IllegalStateException(result.message(), result.cause());
            instances = result.value();
        }
        if (list) {
            System.out.println("--- 识别到 " + instances.size() + " 个实例（" + gameDir + "）---");
            instances.forEach(i -> System.out.println("  " + i.describe()));
            if (!instances.isEmpty()) yagen.waitmydawn.checker.core.AppConfig
                    .set(toolRoot, yagen.waitmydawn.checker.core.AppConfig.KEY_GAME_DIR,
                            gameDir.toString());
            return 0;
        }
        if (mode == null) {
            printHelp();
            return 0;
        }
        final String wanted = target;
        GameInstance inst = instances.stream().filter(i -> i.name().equals(wanted))
                .findFirst().orElse(null);
        if (inst == null) {
            System.err.println("没找到实例 \"" + target + "\"，可用实例：");
            instances.forEach(i -> System.err.println("  " + i.name()));
            return 2;
        }
        NetworkManager.Asker asker = "remove-only".equalsIgnoreCase(netPolicy)
                || "abort".equalsIgnoreCase(netPolicy) ? null
                : (dialog ? NetworkAskers.dialog() : NetworkAskers.console(System.out::println));
        // 轮次用完的处置：ask 用控制台/弹窗（无交互终端时控制台实现自动按"停止"处理），
        // stop/restore 直接给固定答案——无人值守脚本用这两个，脚本行为才是确定的
        yagen.waitmydawn.checker.core.RoundAsker roundAsker = switch (onExhausted.toLowerCase()) {
            case "stop", "keep" -> yagen.waitmydawn.checker.ui.RoundAskers
                    .fixed(yagen.waitmydawn.checker.core.RoundAsker.Choice.STOP_KEEP);
            case "restore" -> yagen.waitmydawn.checker.ui.RoundAskers
                    .fixed(yagen.waitmydawn.checker.core.RoundAsker.Choice.STOP_AND_RESTORE);
            default -> dialog ? yagen.waitmydawn.checker.ui.RoundAskers.dialog()
                    : yagen.waitmydawn.checker.ui.RoundAskers.console(System.out::println);
        };
        CheckerEngine.Options o = new CheckerEngine.Options(mx, timeout, grace, rounds, cascadeLimit,
                dryRun, force, repeat, flakyRetry, netPolicy, proxySetting, asker, allowDirectCdn,
                allowMirrorFile, roundAsker, dialog
                        ? yagen.waitmydawn.checker.ui.Notifiers.dialog()
                        : yagen.waitmydawn.checker.ui.Notifiers.console());
        boolean interactive = dialog || System.console() != null;
        int code = switch (mode) {
            case "scan" -> doScan(inst, toolRoot);
            case "check" -> runCheck(inst, toolRoot, o);
            case "fix" -> runWorkflowFix(inst, toolRoot, o, policyChoice, rounds,
                    roundsExplicit, interactive, dialog, onExhaustedExplicit);
            case "legacy-fix" -> runLegacyFix(inst, toolRoot, o, policyChoice, onExhaustedExplicit);
            case "restore" -> runWorkflowRestore(inst, toolRoot, workflowId, restoreRound,
                    dryRun, System.out::println);
            case "legacy-restore" -> runLegacyRestore(inst, toolRoot, restoreRound, workflowId);
            case "repair-libraries" -> doRepairLibraries(inst, proxySetting);
            default -> 2;
        };
        return code;
    }

    private static int runWorkflowFix(GameInstance instance, Path toolRoot,
                                      CheckerEngine.Options options, String requestedPolicy,
                                      int rounds, boolean roundsOverride, boolean interactive,
                                      boolean dialog, boolean onExhaustedExplicit) throws Exception {
        System.out.println("说明：--fix 已切换到新版事务工作流。自动授权必须来自 "
                + "--policy auto 或已保存策略；旧行为请显式使用 --legacy-fix。");
        if (options.dryRun()) {
            System.err.println("新版 --fix 不执行 dry-run，且不会在预演模式下修改实例；"
                    + "如需旧版计划预演，请使用 --legacy-fix --dry-run。");
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        if (onExhaustedExplicit) {
            System.err.println("--on-exhausted 属于旧版兼容参数；新版使用保存策略或 "
                    + "--policy，并在达到轮次上限后保留暂停记录。");
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        CliWorkflow.PolicyChoice choice;
        try {
            choice = CliWorkflow.policyChoice(requestedPolicy);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        CliWorkflow.ResolvedPolicy resolved;
        try {
            resolved = CliWorkflow.resolvePolicy(instance, toolRoot, choice, rounds,
                    roundsOverride, interactive);
        } catch (Exception e) {
            System.err.println("策略不可执行：" + e.getMessage());
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        System.out.println("修复策略：" + resolved.description()
                + "，轮次上限 " + resolved.policy().maxRounds());
        TaskResult<RepairWorkflow.State> result = CliWorkflow.fix(instance, toolRoot, options,
                resolved.policy(), CliWorkflow.decider(dialog, System.out::println),
                System.out::println);
        System.out.println(result.message());
        if (result.value() != null) {
            System.out.println("工作流: " + result.value().id() + " / "
                    + result.value().phase() + " / " + result.value().rounds().size() + " 轮");
        }
        return CliWorkflow.exitCode(result);
    }

    private static int runLegacyFix(GameInstance instance, Path toolRoot,
                                    CheckerEngine.Options options, String requestedPolicy,
                                    boolean onExhaustedExplicit) throws Exception {
        System.out.println("警告：--legacy-fix 使用旧台账与旧 A/B/C 策略，"
                + "仅用于兼容既有脚本；无法使用策略/历史等新版能力。");
        if (!"saved".equalsIgnoreCase(requestedPolicy) || onExhaustedExplicit) {
            System.out.println("提示：--policy 和 --on-exhausted 在 --legacy-fix 下由旧参数 "
                    + "--rounds/--on-exhausted 直接控制。");
        }
        return CheckerEngine.fix(instance, toolRoot, options, System.out::println);
    }

    private static int runWorkflowRestore(GameInstance instance, Path toolRoot, UUID workflowId,
                                          Integer requestedRound, boolean dryRun,
                                          java.util.function.Consumer<String> sink) throws Exception {
        List<RepairWorkflow.State> history;
        try {
            history = RepairWorkflow.history(instance);
        } catch (Exception e) {
            System.err.println("新版工作流历史无法读取，未回退旧台账：" + e.getMessage());
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        if (history.isEmpty()) {
            if (workflowId != null) {
                System.err.println("指定了 --workflow，但该实例没有新版工作流记录：" + workflowId);
                return CliWorkflow.EXIT_NEEDS_ATTENTION;
            }
            System.out.println("没有新版事务工作流记录；使用旧台账兼容恢复。");
            return CheckerEngine.restore(instance, toolRoot, requestedRound, sink);
        }
        RepairWorkflow.State state;
        try {
            state = CliWorkflow.selectHistory(instance, workflowId);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        int targetRound = requestedRound == null
                ? state.round().number() : requestedRound;
        TaskResult<RepairWorkflow.RestorePreview> previewResult;
        try {
            previewResult = CliWorkflow.previewRestore(instance, toolRoot, state.id(), targetRound);
        } catch (Exception e) {
            System.err.println("生成恢复预览失败：" + e.getMessage());
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        if (previewResult.state() != TaskSnapshot.State.SUCCEEDED
                || previewResult.value() == null) {
            System.err.println("恢复预览失败：" + previewResult.message());
            return CliWorkflow.exitCode(previewResult);
        }
        RepairWorkflow.RestorePreview preview = previewResult.value();
        System.out.println("工作流: " + state.id() + "，恢复到第 " + targetRound + " 轮前");
        System.out.println("恢复计划: " + preview.plan().summary()
                + "（摘要 " + preview.plan().shortDigest() + "）");
        preview.plan().steps().forEach(step -> System.out.println("  - " + step.key()
                + " · " + java.util.Objects.toString(step.fromHash(), "不存在")
                + " → " + java.util.Objects.toString(step.toHash(), "不存在")));
        if (dryRun) {
            System.out.println("dry-run：只输出恢复计划，未修改任何文件。");
            return CliWorkflow.EXIT_SUCCESS;
        }
        TaskResult<RepairWorkflow.State> restored = CliWorkflow.restore(instance, toolRoot,
                preview, sink);
        System.out.println(restored.message());
        return CliWorkflow.exitCode(restored);
    }

    private static int runLegacyRestore(GameInstance instance, Path toolRoot,
                                        Integer requestedRound, UUID workflowId) throws Exception {
        if (workflowId != null) {
            System.err.println("--workflow 只适用于新版 --restore；--legacy-restore 不支持工作流 UUID。");
            return CliWorkflow.EXIT_NEEDS_ATTENTION;
        }
        System.out.println("警告：--legacy-restore 使用旧台账恢复，仅用于没有新版工作流的兼容记录。");
        return CheckerEngine.restore(instance, toolRoot, requestedRound, System.out::println);
    }

    private static int runCheck(GameInstance inst, Path toolRoot, CheckerEngine.Options options) throws Exception {
        try (TaskService tasks = new TaskService()) {
            Thread shutdown = new Thread(tasks::close, "checker-check-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                var handle = tasks.submit(inst.dir().toAbsolutePath().normalize().toString(),
                        CheckerTasks.check(inst, toolRoot, options), event -> {
                            if (event.kind() == TaskEvent.Kind.LOG) System.out.println(event.text());
                        });
                var result = handle.completion().toCompletableFuture().get();
                System.out.println(result.message());
                return CheckerTasks.exitCode(result);
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { }
            }
        }
    }

    // ==================================================================================
    // 只读能力：静态预检 / Java 探测 / 离线诊断 / 依赖解析探测 / 网络自检
    // ==================================================================================

    private static int doScan(GameInstance inst, Path toolRoot) throws Exception {
        System.out.println("--- 静态依赖预检: " + inst.name() + " ---");
        System.out.println("  " + inst.describe());
        int need = InstanceLauncher.requiredJavaOf(inst);
        JavaLocator.JavaHome java = JavaLocator.find(need, toolRoot);
        System.out.println("  需要 Java " + need + " → " + (java == null
                ? "未找到（需要提示用户安装）" : java.versionLine() + " @ " + java.javaExe()));
        StaticDepsScanner.Report r = StaticDepsScanner.scan(inst);
        printStaticReport(r);
        return r.highConfidence().isEmpty() ? 0 : 1;
    }

    private static void printStaticReport(StaticDepsScanner.Report r) {
        System.out.println("  扫描 jar=" + r.jarCount() + "（含嵌套 jar " + r.nestedJarCount()
                + "），解析到 mod=" + r.modCount());
        System.out.println();
        System.out.println("  【高置信问题】" + r.highConfidence().size() + " 条（可用于自动决策）");
        r.highConfidence().forEach(p -> System.out.println("   · " + p.describe()));
        System.out.println();
        System.out.println("  【需人工复核】" + r.needsReview().size() + " 条（静态阶段不下结论）");
        r.needsReview().stream().limit(10).forEach(p -> System.out.println("   · " + p.describe()));
        System.out.println();
        System.out.println("  【影响面】被依赖最多的模组 Top 5");
        r.dependentsOf().entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(5)
                .forEach(e -> System.out.println("   · " + e.getKey() + " ← "
                        + e.getValue().size() + " 个依赖者"));
        System.out.println();
        System.out.println("  【叶子候选】（没人依赖，摘除不连累他人）共 " + r.leaves().size() + " 个");
        System.out.println("   " + String.join(", ", r.leaves().stream().limit(20).toList())
                + (r.leaves().size() > 20 ? " …" : ""));
    }

    /**
     * `--repair-libraries <实例>`：按实例版本 JSON 补齐缺失的 libraries（只增不改不删）。
     *
     * <p>用途：库文件被误删 / 被杀毒清理 / 被"递归删除穿透链接"清空时，游戏会报
     * "找不到主类 xxx" 这种看不懂的错，所有实例一起挂。这条命令能把地基按 sha1 补回来，
     * 不需要重装游戏、也不用重新下载整合包。
     */
    private static int doRepairLibraries(GameInstance inst, ProxyConfig.Setting setting)
            throws Exception {
        System.out.println("=== 补齐库文件: " + inst.name() + " ===");
        System.out.println("游戏目录: " + inst.gameRoot());
        // 无人值守：补库失败时给出可操作建议，而不是挂在那里等输入
        yagen.waitmydawn.checker.net.NetworkManager nm =
                new yagen.waitmydawn.checker.net.NetworkManager(setting, null, true, false,
                        System.out::println);
        try {
            LibraryRepair.Report r = LibraryRepair.repair(inst, nm, System.out::println);
            System.out.println();
            System.out.println("库总数 " + r.total() + "，本次缺失 " + r.missing()
                    + "，补齐 " + r.fixed() + "，失败 " + r.failed().size());
            r.failed().forEach(f -> System.out.println("   ✗ " + f));
            System.out.println(r.failed().isEmpty()
                    ? "结论：库文件已完整，可以启动游戏了。"
                    : "结论：仍有缺失（见上面清单），请确认代理可用后重跑本命令。");
            return r.failed().isEmpty() ? 0 : 1;
        } catch (Exception e) {
            System.out.println("补齐失败：" + e.getMessage());
            System.out.println("建议：确认代理已打开（或加 --proxy http://127.0.0.1:7890）后重跑。");
            return 2;
        }
    }

    /**
     * `--diagnose <文件>`：对已有的 latest.log / 客户端 stdout / 崩溃报告做一次离线诊断。
     * 用途：复盘"上次那次崩溃到底是谁"。可传多个文件（逗号分隔），会合并起来一起看。
     */
    private static int doDiagnose(String paths) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String p : paths.split(",")) {
            if (!p.isBlank()) files.add(Paths.get(p.trim()));
        }
        System.out.println("=== 离线诊断 ===");
        files.forEach(f -> System.out.println("  输入: " + f
                + (Files.isRegularFile(f) ? "" : "（不存在）")));
        Diagnosis d = LogDiagnoser.diagnoseFiles(files, java.util.Map.of());
        if (d.issues().isEmpty()) {
            System.out.println("结论：没有解析出结构化故障。");
            d.attributions().forEach(a -> System.out.println("  归因| " + a));
            d.evidenceLines().stream().limit(10).forEach(l -> System.out.println("  证据| " + l));
            return 1;
        }
        System.out.println("解析出 " + d.issues().size() + " 个问题：");
        d.issues().forEach(i -> {
            System.out.println("  · [" + i.kind() + "] " + i.describe());
            System.out.println("    建议: " + i.suggestion());
            if (i.detail() != null) System.out.println("    原文: " + i.detail());
        });
        if (!d.attributions().isEmpty()) {
            System.out.println("归因过程（为什么怀疑它 / 为什么没怀疑另一个）：");
            d.attributions().forEach(a -> System.out.println("  · " + a));
        }
        return 0;
    }

    /** `--probe-dep <modId>`：只做"modId → 候选项目 → 版本 → 文件"的解析，不下载、不改任何文件 */
    private static int doProbeDep(String modId, String mcVersion, String loader) throws Exception {
        System.out.println("=== 依赖解析探测: " + modId + "（" + loader + "/" + mcVersion + "）===");
        System.out.println("闸门: 必须是真 mod（datapack/resourcepack 不算）+ loader 严格等于 "
                + loader + " + 当前环境下选得出可用 jar");
        yagen.waitmydawn.checker.net.ModrinthClient.Resolution res =
                yagen.waitmydawn.checker.net.ModrinthClient.resolve(
                        modId, mcVersion, loader, "*", System.out::println);
        if (!res.found()) {
            System.out.println("结论：Modrinth 上找不到可用的匹配版本（会走'补不到→摘除依赖方'）。");
            res.rejected().forEach(r -> System.out.println("  淘汰依据: " + r));
            res.sameName().forEach(s -> System.out.println("  同名候选（未采用）: " + s));
            return 1;
        }
        System.out.println("结论：可补装（候选 " + res.candidates().size() + " 个，按可信度排序）");
        int i = 0;
        for (yagen.waitmydawn.checker.net.ModrinthClient.Candidate c : res.candidates()) {
            System.out.println("  [" + (++i) + "] 项目 slug : " + c.slug() + "（" + c.title() + "）");
            System.out.println("      版本      : " + c.versionNumber());
            System.out.println("      文件      : " + c.fileName() + "（" + (c.size() / 1024) + " KB）");
            System.out.println("      下载地址  : " + c.url());
        }
        System.out.println("  说明：正式补装会下载候选并核对 jar 内声明的 modId，对得上才装进 mods。");
        return 0;
    }

    /**
     * `--test-proxy`：把"补装模组"要走的三步逐一实测——搜索接口、版本接口、**从 CDN 真下载一个 jar**。
     * 用户在国内线路上最需要确认的就是最后那一步。
     */
    private static int doTestProxy(ProxyConfig.Setting setting) throws Exception {
        long t0 = System.currentTimeMillis();
        System.out.println("=== 网络连通性测试（目标：20 秒内出结论）===");
        System.out.println("代理探测: " + setting.oneLine());
        int fail = 0;
        System.out.println("[1/4] 镜像 API（mod.mcimirror.top）…");
        String mirrorErr = Http.probe("https://mod.mcimirror.top/modrinth/v2/search?limit=1", null, 5000);
        System.out.println(mirrorErr == null ? "      ✓ 可用（找地址会走镜像，快很多）"
                : "      ✗ 不可用：" + mirrorErr + "（会回退官方 API）");
        // 镜像通了就不必再花时间测官方直连（那 5 秒对用户没意义）；镜像挂了才需要知道官方行不行
        if (mirrorErr != null) {
            System.out.println("[2/4] 官方 API（api.modrinth.com）直连…");
            String err = Http.probe("https://api.modrinth.com/v2/search?limit=1", null, 5000);
            System.out.println(err == null ? "      ✓ 可用" : "      ✗ 不通：" + err);
            if (err != null) fail++;
        } else {
            System.out.println("[2/4] 官方 API：跳过（镜像已可用）");
        }

        System.out.println("[3/4] 解析一个 modId → slug → 版本 → 下载地址…");
        yagen.waitmydawn.checker.net.ModrinthClient.Candidate c = null;
        // 走通道管理器：镜像优先、失败回退官方；无人值守（asker=null）→ 不会挂在这里问用户
        yagen.waitmydawn.checker.net.NetworkManager nm =
                new yagen.waitmydawn.checker.net.NetworkManager(setting, null, true, true,
                        System.out::println);
        try {
            c = yagen.waitmydawn.checker.net.ModrinthClient.findDependency(
                    "kubejs", "1.21.1", "neoforge", "*", nm, System.out::println);
            System.out.println(c == null ? "      ✗ 没解析到候选版本"
                    : "      ✓ slug=" + c.slug() + " 版本=" + c.versionNumber()
                    + " 文件=" + c.fileName());
            if (c == null) fail++;
        } catch (Exception e) {
            System.out.println("      ✗ " + e.getMessage());
            fail++;
        }

        System.out.println("[4/4] 真下一份（判据：连接 8s，5 秒内 <13KB/s 算不稳定）…");
        if (c == null) {
            System.out.println("      - 跳过（上一步没拿到文件地址）");
        } else {
            Path tmp = Files.createTempDirectory("maa-checker-proxytest").resolve(c.fileName());
            try {
                // 用与正式下载完全相同的通道逻辑与判据（含"已判死通道不重复试"）
                Http.DownloadOutcome o = nm.download(c.url(), c.mirrorUrl(), c.size(), tmp);
                long ms = System.currentTimeMillis() - t0;
                if (o.ok()) {
                    System.out.printf("      ✓ 下载成功 %d 字节，用时 %.1fs（%.0f KB/s）%n",
                            o.bytes(), ms / 1000.0, o.bytes() / 1024.0 / (ms / 1000.0));
                } else {
                    System.out.println("      ✗ 下载失败[" + o.kind() + "] " + o.detail());
                    fail++;
                }
            } catch (yagen.waitmydawn.checker.net.NetworkManager.RemoveOnlyRequested ro) {
                System.out.println("      ✗ 下载不通（无人值守模式不会等你开代理）——需要代理才能补装模组");
                fail++;
            } catch (yagen.waitmydawn.checker.net.NetworkManager.StopRequested st) {
                System.out.println("      ✗ 用户中止");
            } catch (Exception e) {
                System.out.println("      ✗ 下载失败: " + e.getMessage());
                fail++;
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                    Files.deleteIfExists(tmp.getParent());
                } catch (Exception ignored) {
                }
            }
        }
        System.out.println(fail == 0
                ? "结论：网络通畅，可以执行【动作B 补装前置】。"
                : "结论：有 " + fail + " 项失败。下载需要代理：在 Clash / V2Ray 里打开【系统代理】"
                        + "（本工具会自动探测到并做端到端探活），或用 --set-proxy "
                        + "http://127.0.0.1:7890 固定设置；没有代理时只能做摘除（--net remove-only）。");
        System.out.printf("（本次自检耗时 %.1fs）%n", (System.currentTimeMillis() - t0) / 1000.0);
        return fail == 0 ? 0 : 1;
    }

    private static void printJava(Path toolRoot) {
        System.out.println("--- 探测到的 Java（顺序即优先级）---");
        List<JavaLocator.JavaHome> all = JavaLocator.listAll(toolRoot);
        if (all.isEmpty()) System.out.println("  （未探测到任何 Java，请先安装 JDK/JRE 并加入 PATH）");
        all.forEach(j -> System.out.printf("  Java %-3d %s  (%s)%n",
                j.major(), j.versionLine(), j.javaExe()));
    }

    // ==================================================================================
    // 路径与帮助
    // ==================================================================================

    /** 软件目录：优先系统属性（启动脚本会设），其次 jar 所在目录，最后当前目录 */
    static Path resolveToolRoot() {
        String home = System.getProperty("maachecker.home");
        if (home != null && !home.isBlank()) return Paths.get(home).toAbsolutePath();
        try {
            Path self = Paths.get(CheckerCli.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            String name = self.getFileName() == null ? ""
                    : self.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            // .jar：普通 fat jar；.exe：Launch4j 把 jar 嵌进 exe 后，代码位置就是那个 exe
            if (name.endsWith(".jar") || name.endsWith(".exe")) {
                Path dir = self.getParent();
                // jpackage app-image 的布局是 <软件目录>\app\maa-checker.jar → 上跳一级，
                // 让 logs/bin/state 落在 exe 旁边，而不是埋在 app\ 子目录里
                if (dir != null && "app".equalsIgnoreCase(String.valueOf(dir.getFileName()))) {
                    dir = dir.getParent();
                }
                // 开发态：java -jar target/maa-checker.jar → 用当前工作目录，
                // 免得 logs/bin/state 跑进 target/ 里被 mvn clean 一起清掉
                if (dir != null && "target".equalsIgnoreCase(String.valueOf(dir.getFileName()))) {
                    dir = Paths.get("").toAbsolutePath();
                }
                return dir == null ? Paths.get("").toAbsolutePath() : dir.toAbsolutePath();
            }
        } catch (Exception ignored) {
            // 开发态（target/classes）走当前目录
        }
        return Paths.get("").toAbsolutePath();
    }

    static Path defaultGameDir() {
        return yagen.waitmydawn.checker.core.GameDirLocator.defaultGameDir();
    }

    private static void printHelp() {
        System.out.println("""
                MAA-Checker —— Minecraft 整合包可行性检验器（就地检验 + 自动修复已安装实例）

                直接双击 / 不带参数运行 = 打开图形界面。

                用法:
                  --gui                打开图形界面
                  --game-dir <路径>    游戏目录（.minecraft），默认 %APPDATA%/.minecraft
                  --list               列出识别到的实例
                  --java               列出探测到的 Java
                  --scan <实例名>      静态依赖预检（只读，不启动、不修改任何文件）
                  --check <实例名>     跑一轮：静态预检 → 启动 → 盯日志 → 诊断 → 报告
                  --fix <实例名>       新版事务修复；默认读取全局策略，无保存时使用全自动 10 轮
                  --policy saved|auto|ask|stop
                                       新版修复授权来源；auto 必须显式指定，ASK 需要交互终端或 --dialog
                  --legacy-fix <实例名>
                                       旧版兼容修复（旧台账 / A+B+C），仅用于既有脚本
                  --restore <实例名>   还原新版工作流；没有新版记录时兼容回退旧台账
                  --workflow <UUID>    指定要还原的新版工作流，默认最新一条
                  --round N            还原指定轮次前；新工作流默认最新轮次
                  --legacy-restore <实例名>
                                       强制使用旧台账恢复，仅用于兼容旧记录
                  --repeat N           --check 连跑 N 次，给出稳定性结论（默认 1）
                  --retry N            偶发崩溃先原样重启的次数（默认 1，上限 3）
                  --rounds N           显式覆盖本次修复轮次上限（默认沿用全局策略的 10 轮，可用上限 20）
                  --on-exhausted ask|stop|restore
                                       旧版兼容修复的轮次用尽策略：ask=问我要追加几轮（默认，
                                       无交互终端时按 stop 处理）/ stop=停止保留改动 /
                                       restore=停止并还原本次会话的全部改动
                  --cascade-limit N    一次级联摘除的模组数上限（默认 40）
                  --net ask|remove-only|abort
                                       网络不通时的策略：ask=询问用户（默认）、
                                       remove-only=只做摘除不补装、abort=直接中止
                  --dialog             需要用户决策时用弹窗（GUI 默认就是弹窗）
                  --diagnose <文件>    离线诊断已有日志/崩溃报告（可逗号分隔多个）
                  --probe-dep <modId>  只解析 modId→候选项目→版本→文件（含闸门淘汰依据），不下载
                  --test-proxy         实测搜索接口 / 版本解析 / 从 CDN 真下一份
                  --proxy <地址>       本次走代理（如 http://127.0.0.1:7890）
                  --set-proxy <地址>   把代理写进软件设置，以后自动生效
                  --clear-proxy        清除软件里的代理设置
                  --no-proxy           强制直连（排查用）
                  --mx 3G              游戏最大堆（默认 3G）
                  --timeout 180        每轮整体超时秒数（默认 180）
                  --grace 15           进主菜单后继续观察秒数（默认 15）
                  --dry-run            只输出计划；新版 --restore 可直接预览，旧版修复需配 --legacy-fix
                  --force              跳过"疑似游戏正在运行"的安全闸
                  --tool-root <路径>   软件目录（logs/、bin/、state/ 落在这里）

                修复、还原和准备区清理对同一实例互斥；已有操作（含等待决定）时立即失败，不等待、不抢占。

                版本兼容：--fix/--restore 已切换到新版工作流；旧 A+B+C 与旧台账入口须显式使用
                --legacy-fix/--legacy-restore。非交互进程遇到 ASK 会在修改文件前失败。

                退出码: 0=通过  1=检查仍有问题  2=需要人工处理/前置条件不足  130=任务取消
                """);
    }
}
