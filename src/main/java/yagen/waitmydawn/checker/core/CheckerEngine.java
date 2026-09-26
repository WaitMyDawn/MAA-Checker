package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.action.ActionExecutor;
import yagen.waitmydawn.checker.action.ActionKind;
import yagen.waitmydawn.checker.action.ActionPlan;
import yagen.waitmydawn.checker.action.ActionPlanner;
import yagen.waitmydawn.checker.action.Ledger;
import yagen.waitmydawn.checker.action.PlannedAction;
import yagen.waitmydawn.checker.action.Restore;
import yagen.waitmydawn.checker.action.Tombstones;
import yagen.waitmydawn.checker.net.NetworkManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 检验/修复引擎：**界面无关**的那一半（CLI 与 GUI 共用同一套流程，避免两套行为漂移）。
 *
 * <p>这里只做三件事：跑一轮检验、按轮次修复、还原；产出写进 {@code logs/ bin/ state/}。
 * 所有输出走 {@link Consumer} 日志口，所以命令行可以打到控制台，GUI 可以打到窗口里。
 */
public final class CheckerEngine {

    public static final String TOOL_VERSION = "0.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long RECENT_ACTIVITY_MS = 10_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一轮检验的产物 */
    public record Round(GameInstance inst, JavaLocator.JavaHome java, int requiredJava,
                        StaticDepsScanner.Report stat, LaunchSpec spec, LaunchResult launch,
                        Diagnosis diag, Map<String, String> jarIndex) {
    }

    /** 修复轮次的默认上限与单次运行的硬上限（硬上限只是"防手滑"，改代码即可放宽） */
    public static final int DEFAULT_FIX_ROUNDS = 4;
    public static final int MAX_TOTAL_FIX_ROUNDS = 20;
    /** 用户选择"追加轮次"时默认给多少轮 */
    public static final int DEFAULT_EXTRA_ROUNDS = 2;

    /**
     * 一次运行的参数（CLI 从命令行拼，GUI 从界面控件拼）。
     *
     * @param rounds    自动修复的<b>初始</b>轮次上限（用户可在界面/命令行设定；用完还会问是否追加）
     * @param asker     网络/代理需要用户决策时的交互方式（GUI 传弹窗、CLI 传控制台、无人值守传 null）
     * @param roundAsker 轮次用完还没修好时的交互方式（null = 直接停止，保留改动）
     * @param notifier   单向通知（GUI 弹窗 / CLI 控制台 / 无人值守 {@link Notifier#SILENT}）
     */
    public record Options(String mx, int timeoutSeconds, int graceSeconds, int rounds,
                          int cascadeLimit, boolean dryRun, boolean force, int repeat,
                          int flakyRetry, String netPolicy, ProxyConfig.Setting proxy,
                          NetworkManager.Asker asker, boolean allowDirectCdn,
                          boolean allowMirrorFile, RoundAsker roundAsker, Notifier notifier) {

        public static Options defaults() {
            return new Options("3G", 180, 15, DEFAULT_FIX_ROUNDS, ActionPlanner.DEFAULT_CASCADE_LIMIT,
                    false, false, 1, 1, "ask", null, null, false, false, null, Notifier.SILENT);
        }

        public Options withProxy(ProxyConfig.Setting p) {
            return new Options(mx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, p, asker, allowDirectCdn, allowMirrorFile,
                    roundAsker, notifier);
        }

        public Options withAsker(NetworkManager.Asker a) {
            return new Options(mx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, proxy, a, allowDirectCdn, allowMirrorFile,
                    roundAsker, notifier);
        }

        /** 轮次用完还没修好时怎么问用户（GUI 传弹窗、CLI 传控制台/固定答案） */
        public Options withRoundAsker(RoundAsker a) {
            return new Options(mx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, proxy, asker, allowDirectCdn,
                    allowMirrorFile, a, notifier);
        }

        /** 单向通知怎么发给用户（GUI 弹窗、CLI 控制台） */
        public Options withNotifier(Notifier n) {
            return new Options(mx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, proxy, asker, allowDirectCdn,
                    allowMirrorFile, roundAsker, n == null ? Notifier.SILENT : n);
        }

        /** 自动修复的初始轮次上限（夹到 [1, MAX_TOTAL_FIX_ROUNDS]，防手滑写个 1000） */
        public Options withRounds(int n) {
            int clamped = Math.max(1, Math.min(MAX_TOTAL_FIX_ROUNDS, n));
            return new Options(mx, timeoutSeconds, graceSeconds, clamped, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, proxy, asker, allowDirectCdn,
                    allowMirrorFile, roundAsker, notifier);
        }

        public Options withMx(String newMx) {
            return new Options(newMx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, proxy, asker, allowDirectCdn,
                    allowMirrorFile, roundAsker, notifier);
        }

        /** 网络开关：是否允许直连官方 CDN（默认关）、是否允许试镜像文件入口（默认开） */
        public Options withNetSwitches(boolean directCdn, boolean mirrorFile) {
            return new Options(mx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, netPolicy, proxy, asker, directCdn, mirrorFile,
                    roundAsker, notifier);
        }

        public Options withPolicy(String newPolicy) {
            return new Options(mx, timeoutSeconds, graceSeconds, rounds, cascadeLimit, dryRun,
                    force, repeat, flakyRetry, newPolicy, proxy, asker, allowDirectCdn,
                    allowMirrorFile, roundAsker, notifier);
        }
    }

    private CheckerEngine() {
    }

    // ==================================================================================
    // 单轮 / 多轮检验
    // ==================================================================================

    /**
     * 跑一轮或多轮检验。{@code repeat > 1} 时反复启动——整合包崩溃常常是竞态
     * （实测：同一包里 iceandfire / legendary_monsters 都在 FML 并行派发的客户端初始化里偶发抛异常），
     * 单次 PASS 只能说明"这次能进主菜单"，不能说明稳定。
     */
    public static int check(GameInstance inst, Path toolRoot, Options o, Consumer<String> sink)
            throws Exception {
        RunJournal journal = RunJournal.create(toolRoot, inst.name(), sink);
        try {
            journal.log("=== MAA-Checker " + TOOL_VERSION + " 检验: " + inst.name() + " ===");
            journal.log("时间: " + LocalDateTime.now().format(TIME));
            journal.log("产物目录: " + journal.runDir());
            int pass = 0;
            int done = 0;
            int times = Math.max(1, o.repeat());
            for (int i = 1; i <= times; i++) {
                Path roundDir = times == 1 ? journal.runDir() : journal.runDir().resolve("run-" + i);
                if (times > 1) journal.log("──────── 第 " + i + "/" + times + " 次启动 ────────");
                Round r = runRound(inst, toolRoot, roundDir, journal, o, o.force() || i > 1);
                if (r == null) return 2;
                writeReport(roundDir, r);
                done++;
                if (r.launch().verdict() == LaunchVerdict.PASS) pass++;
                journal.log("⑥ 报告: " + roundDir.resolve("report.txt"));
            }
            if (times > 1) {
                journal.log("");
                journal.log("稳定性结论: " + pass + "/" + done + " 次成功进主菜单"
                        + (pass == done ? "（本轮没复现偶发崩溃；竞态类问题要多跑几次才看得见）"
                        : "（存在偶发失败，请看失败那几轮的 report.txt）"));
            }
            return pass == done ? 0 : 1;
        } finally {
            journal.close();
        }
    }

    /** 跑一轮：静态预检 → Java → 组装命令 → 启动 → 诊断。返回 null 表示前置条件不满足（已写日志） */
    public static Round runRound(GameInstance inst, Path toolRoot, Path roundDir, RunJournal journal,
                                 Options o, boolean force) throws Exception {
        journal.log("实例: " + inst.describe());
        if (!force && recentlyActive(inst)) {
            journal.log("⛔ logs/latest.log 在 10 秒内还被写过，疑似该实例的游戏正在运行。");
            journal.log("   请先关闭游戏；确认没有在跑可加 --force 继续。");
            return null;
        }
        long tStatic = System.currentTimeMillis();
        StaticDepsScanner.Report stat = StaticDepsScanner.scan(inst);
        journal.log("① 静态预检: jar=" + stat.jarCount() + "，mod=" + stat.modCount()
                + "，高置信问题 " + stat.highConfidence().size() + " 条，需人工复核 "
                + stat.needsReview().size() + " 条（" + (System.currentTimeMillis() - tStatic) + "ms）");
        stat.highConfidence().forEach(p -> journal.log("   · " + p.describe()));

        int need = InstanceLauncher.requiredJavaOf(inst);
        JavaLocator.JavaHome java = JavaLocator.find(need, toolRoot);
        if (java == null) {
            journal.log("⛔ 没找到 Java " + need + "+，无法启动。请安装对应 Java 后重试。");
            return null;
        }
        journal.log("② Java: " + java.versionLine() + "（major=" + java.major() + "，需要 " + need
                + "）@ " + java.javaExe());

        Files.createDirectories(roundDir);
        LaunchSpec spec = InstanceLauncher.build(inst, java, roundDir.resolve("client-stdout.log"),
                o.mx(), 854, 480);
        Files.writeString(roundDir.resolve("launch-command.txt"),
                spec.commandText() + System.lineSeparator()
                        + "依赖提醒: " + (spec.notes().isEmpty() ? "无"
                        : String.join(" | ", spec.notes())) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        journal.log("③ 启动命令（已脱敏；完整命令见 launch-command.txt）：");
        journal.log("   " + abbreviate(spec.commandText()));
        spec.notes().forEach(n -> journal.log("   ⚠️ " + n));

        journal.log("④ 启动并观察（-Xmx" + o.mx() + "，超时 " + o.timeoutSeconds()
                + "s，进主菜单后再观察 " + o.graceSeconds() + "s）");
        long launchStart = System.currentTimeMillis();
        LaunchResult result = InstanceLauncher.run(spec, o.timeoutSeconds(), o.graceSeconds(),
                journal::log);
        ModJarIndex.Meta meta = ModJarIndex.scanMeta(inst.modsDir());
        Map<String, String> jarIndex = meta.jarOf();
        // 客户端 stdout 也要喂给诊断器：FML 的 "-- Mod loading issue for: X --" 明细只在它里面
        List<Path> extraLogs = result.stdoutLog() == null ? List.of() : List.of(result.stdoutLog());
        Diagnosis diag = LogDiagnoser.diagnose(inst, result.freshLog(), extraLogs, meta,
                launchStart);
        journal.log("⑤ 结论: " + result.verdict() + "，证据: \"" + result.evidence() + "\"，耗时 "
                + String.format("%.1f", result.elapsedMs() / 1000.0) + "s");
        result.timeline().forEach(t -> journal.log("   时间线 " + t));
        if (diag.hasIssues()) {
            journal.log("   诊断出 " + diag.issues().size() + " 个问题：");
            diag.issues().forEach(i -> journal.log("    · " + i.describe()));
        } else if (result.verdict() != LaunchVerdict.PASS) {
            journal.log("   没能从日志里解析出结构化故障（见 fresh-latest.log 与 client-stdout.log）");
        }
        if (!diag.attributions().isEmpty()) {
            // 归因过程也要写进日志：用户得看得见"为什么怀疑它"，以及"为什么没怀疑另一个"
            journal.log("   归因过程：");
            diag.attributions().forEach(a -> journal.log("    · " + a));
        }
        journal.log("   崩溃报告: " + diag.note());
        diag.evidenceLines().stream().limit(15).forEach(l -> journal.log("   证据| " + l));
        return new Round(inst, java, need, stat, spec, result, diag, jarIndex);
    }

    // ==================================================================================
    // 自动修复闭环
    // ==================================================================================

    public static int fix(GameInstance inst, Path toolRoot, Options o, Consumer<String> sink)
            throws Exception {
        RunJournal journal = RunJournal.create(toolRoot, inst.name(), sink);
        try {
            journal.log("=== MAA-Checker " + TOOL_VERSION + " 自动修复: " + inst.name() + " ===");
            journal.log("时间: " + LocalDateTime.now().format(TIME));
            journal.log("会话目录: " + journal.runDir());
            String session = journal.runDir().getFileName().toString();
            Path stateDir = toolRoot.resolve("state").resolve(RunJournal.safeName(inst.name()));
            boolean preRemoveOnly = "remove-only".equalsIgnoreCase(o.netPolicy());
            NetworkManager.Asker asker = preRemoveOnly || "abort".equalsIgnoreCase(o.netPolicy())
                    ? null : o.asker();
            // 通道策略（用户 2026-09-22 确认）：有可用代理 → 官方本站（经代理）；
            // 没有可用代理 → 直连官方 CDN（5 秒稳定判据）+ 可选镜像文件入口，两者互为兜底
            boolean proxyUsable = o.proxy() != null && o.proxy().usable();
            NetworkManager nm = new NetworkManager(o.proxy(), asker,
                    o.allowDirectCdn() || !proxyUsable, o.allowMirrorFile(), journal::log);
            if (preRemoveOnly) {
                nm.forceRemoveOnly();
                journal.log("网络策略：remove-only —— 本次检验不补装，遇到缺前置直接摘掉依赖方");
            }
            int flakyBudget = o.flakyRetry();
            Tombstones tombs = Tombstones.load(stateDir);
            if (!tombs.all().isEmpty()) {
                journal.log("已知墓碑 " + tombs.all().size() + " 个（这些模组不会再被装入）：");
                tombs.all().forEach(t -> journal.log("   · " + t.modId() + "（第 " + t.round()
                        + " 轮：" + t.reason() + "）"));
            }
            StringBuilder summary = new StringBuilder();
            summary.append("# 自动修复记录 —— ").append(inst.name()).append("\n\n");
            int finalCode = 1;
            // 轮次上限是"起点"、不是"天花板"：用完还没修好会问用户是否追加（见 askWhenRoundsExhausted）。
            // stopReason 非 null = 主动停止（"再跑也没用"），这类停止不该再问"要不要加轮次"。
            int maxRounds = Math.max(1, Math.min(MAX_TOTAL_FIX_ROUNDS, o.rounds()));
            int round = 0;
            boolean passed = false;
            boolean sessionChanged = false;      // 本会话是否真的动过文件（决定要不要停用 HMCL 整合包标记）
            String stopReason = null;
            while (true) {
                while (round < maxRounds && !passed && stopReason == null) {
                    round++;
                    journal.log("");
                    journal.log("──────────────── 第 " + round + "/" + maxRounds + " 轮 ────────────────");
                    Path roundDir = journal.runDir().resolve("round-" + round);
                    Round r = runRound(inst, toolRoot, roundDir, journal, o, o.force() || round > 1);
                    if (r == null) {
                        summary.append("- 第 ").append(round)
                                .append(" 轮：无法检验（缺 Java / 疑似游戏在运行）\n");
                        finalCode = 2;
                        stopReason = "无法检验（缺 Java / 疑似游戏在运行）";
                        break;
                    }
                    writeReport(roundDir, r);
                    summary.append("- 第 ").append(round).append(" 轮：").append(r.launch().verdict())
                            .append("（问题 ").append(r.diag().issues().size()).append(" 条，loader=")
                            .append(r.inst().loaderVersion()).append("，mods=")
                            .append(r.inst().modCount()).append("）\n");
                    if (r.launch().verdict() == LaunchVerdict.PASS) {
                        journal.log("🎉 第 " + round + " 轮通过：整合包可以正常进主菜单。");
                        finalCode = 0;
                        passed = true;
                        break;
                    }
                    if (flakyBudget > 0 && onlyNonDependencyIssues(r.diag())) {
                        flakyBudget--;
                        journal.log("🔄 疑似【偶发崩溃】（非依赖型故障）→ 原样重启一次确认（剩余重试次数 "
                                + flakyBudget + "）");
                        Path retryDir = roundDir.resolve("retry-" + (o.flakyRetry() - flakyBudget));
                        Round r2 = runRound(inst, toolRoot, retryDir, journal, o, true);
                        if (r2 != null) {
                            writeReport(retryDir, r2);
                            if (r2.launch().verdict() == LaunchVerdict.PASS) {
                                Ledger fl = Ledger.create(stateDir, session, round, inst.name());
                                fl.setBefore(List.of("第 1 次启动: FAIL（" + r.launch().evidence() + "）"));
                                fl.setAfter(List.of("重试后 PASS（" + r2.launch().evidence() + "）",
                                        "未改动任何文件：该模组保留"));
                                for (Diagnosis.Issue i : r.diag().issues()) {
                                    fl.add("FLAKY_OBSERVED", i.modId(), i.jarFile(), i.kind().name(),
                                            "第一次启动崩过一次，原样重试后正常。判断为偶发，该模组已保留；"
                                                    + "如反复出现请手动摘除或换版本",
                                            i.detail() == null ? List.of() : List.of(i.detail()),
                                            Map.of("observedRound", String.valueOf(round)));
                                    break;
                                }
                                fl.save();
                                journal.log("⚠️ 重试后正常进主菜单 → 该模组【保留】，偶发崩溃已记进台账："
                                        + fl.file().getFileName());
                                summary.append("  - 偶发崩溃：重试后正常 → 保留（台账已记录风险）\n");
                                finalCode = 0;
                                passed = true;
                                break;
                            }
                            journal.log("❌ 重试仍然崩溃 → 判定为必然崩溃，按摘除处理");
                            summary.append("  - 偶发崩溃：重试仍失败 → 判定必然，改走摘除\n");
                            r = r2;
                        }
                    }
                    ActionPlan plan = ActionPlanner.plan(r.inst().loader(), r.diag(), r.stat(),
                            r.jarIndex(), tombs, o.cascadeLimit());
                    journal.log("计划动作: A(升加载器)=" + plan.countOf(ActionKind.UPGRADE_LOADER)
                            + "，B(补装)=" + plan.countOf(ActionKind.INSTALL_DEP)
                            + "，C(摘除)=" + plan.countOf(ActionKind.REMOVE_MOD));
                    plan.notes().forEach(n -> journal.log("   说明: " + n));
                    plan.blocked().forEach(b -> journal.log("   ⛔ 无法自动处理: " + b));
                    for (PlannedAction a : plan.actions()) {
                        journal.log("   → [" + a.kind() + "] " + a.modId() + "：" + a.reason());
                    }
                    summary.append("  - 计划 ").append(plan.actions().size()).append(" 个动作\n");
                    if (plan.isEmpty()) {
                        journal.log("没有可自动执行的动作，停止（剩余问题需要人工处理）。");
                        summary.append("  - 停止：没有可自动执行的动作\n");
                        stopReason = "没有可自动执行的动作";
                        break;
                    }
                    if (o.dryRun()) {
                        journal.log("dry-run：只输出计划，不执行。");
                        stopReason = "dry-run";
                        break;
                    }

                    Ledger ledger = Ledger.create(stateDir, session, round, inst.name());
                    ledger.setBefore(List.of(r.launch().verdict() + "（" + r.launch().evidence() + "）",
                            "loader=" + r.inst().loaderVersion(), "mods=" + r.inst().modCount(),
                            "问题=" + r.diag().issues().size() + " 条"));
                    ActionExecutor.Result res = ActionExecutor.execute(toolRoot, inst, plan, round,
                            r.java(), ledger, tombs, r.stat(), r.jarIndex(), o.cascadeLimit(), roundDir,
                            nm, journal::log);
                    journal.log("本轮执行: 升级=" + res.upgraded() + "，补装=" + res.installed()
                            + "，摘除=" + res.removed() + "，失败=" + res.failures().size());
                    res.failures().forEach(f -> journal.log("   ✗ " + f));
                    summary.append("  - 执行: 升级 ").append(res.upgraded()).append("，补装 ")
                            .append(res.installed()).append("，摘除 ").append(res.removed())
                            .append("，失败 ").append(res.failures().size()).append('\n');
                    if (res.networkFailure()) {
                        journal.log("⛔ 出现网络级失败：没有可用通道能下到模组文件。");
                        journal.log("   本工具不会把'网络不通'当成'补不到前置'去摘模组；"
                                + "此前改动都有台账，可用【还原】回滚。");
                        summary.append("  - 停止：网络不可达，未做任何删除\n");
                        stopReason = "网络不可达";
                        break;
                    }
                    GameInstance after = InstanceScanner.scanOne(inst.gameRoot(), inst.dir());
                    ledger.setAfter(List.of(
                            "loader=" + (after == null ? "?" : after.loaderVersion()),
                            "mods=" + (after == null ? "?" : after.modCount())));
                    ledger.save();
                    tombs.save();
                    journal.log("台账: " + ledger.file() + " / " + ledger.file().getFileName()
                            .toString().replace(".json", ".md"));
                    if (after != null) inst = after;
                    if (res.changed() == 0) {
                        journal.log("本轮没有产生任何改动，停止（继续跑只会重复同样的失败）。");
                        summary.append("  - 停止：无改动\n");
                        stopReason = "本轮无改动";
                        break;
                    }
                    sessionChanged = true;
                }
                if (passed || stopReason != null) break;

                // 轮次用完还没修好 → 把三条路摆给用户（追加 / 停止 / 停止并还原），不让"4 轮"变成天花板
                RoundAsker.Decision d = askWhenRoundsExhausted(o.roundAsker(), journal, inst,
                        round, maxRounds);
                if (d.choice() == RoundAsker.Choice.EXTEND) {
                    int add = Math.max(0, Math.min(d.extraRounds(), MAX_TOTAL_FIX_ROUNDS - maxRounds));
                    if (add > 0) {
                        journal.log("▶ 已把轮次上限从 " + maxRounds + " 加到 " + (maxRounds + add)
                                + "，继续修。");
                        summary.append("  - 轮次上限 ").append(maxRounds).append(" → ")
                                .append(maxRounds + add).append("（用户选择追加）\n");
                        maxRounds += add;
                        continue;
                    }
                    journal.log("⚠️ 已达总轮次上限 " + MAX_TOTAL_FIX_ROUNDS + "，无法再追加。");
                    summary.append("  - 已达总轮次上限 ").append(MAX_TOTAL_FIX_ROUNDS)
                            .append("，无法再追加\n");
                    break;
                }
                if (d.choice() == RoundAsker.Choice.STOP_AND_RESTORE) {
                    journal.log("⏹ 用完 " + maxRounds + " 轮仍未通过 → 按你的选择停止，"
                            + "并还原本会话的全部改动…");
                    try {
                        restoreSession(toolRoot, inst, session, journal)
                                .forEach(s -> journal.log("   " + s));
                    } catch (Exception e) {
                        journal.log("   ⚠️ 还原时出错: " + e.getMessage()
                                + "（可点【还原到检验前】重试）");
                    }
                    restoreModpackMarker(inst, journal);
                    journal.log("已按你的要求停止并还原。");
                    summary.append("  - 用完 ").append(maxRounds)
                            .append(" 轮未通过 → 用户选择停止并还原\n");
                    finalCode = 2;
                    break;
                }
                journal.log("⏹ 用完 " + maxRounds + " 轮仍未通过，按你的选择停止"
                        + "（改动保留，可随时点【还原到检验前】）。");
                summary.append("  - 用完 ").append(maxRounds)
                        .append(" 轮未通过 → 用户选择停止（保留改动）\n");
                break;
            }
            summary.append("\n还原方式: 在软件里点【还原到检验前】，或命令行 --restore \"")
                    .append(inst.name()).append("\"\n");
            if (!passed && finalCode != 2) {
                // 走到这儿说明是"停下来了"而不是"修好了"：把回滚入口说清楚，别让人自己找
                journal.log("提示：本次仍未修好。改动都记在台账里，可点【还原到检验前】一键回滚。");
            }

            // ------------------------------------------------------------------
            // HMCL 整合包标记：动过文件才需要处理
            //
            // 实测（2026-09-26 万象）：HMCL 每次【启动】都会跑 ModrinthCompletionTask，
            // 按 modpack.cfg 里的 Modrinth 清单逐条校验、缺谁下谁 —— 我们摘掉的模组因此被原样补回来。
            // 解决办法：把标记改名，让 HMCL 不再把它当整合包（用户 2026-09-26 确认的口径）。
            // ------------------------------------------------------------------
            ModpackMarker.Disabled marker = null;
            if (sessionChanged) {
                try {
                    marker = ModpackMarker.disable(inst);
                } catch (Exception e) {
                    journal.log("   ⚠️ 停用 HMCL 整合包标记失败：" + e.getMessage()
                            + "（你可以手动把 modpack.cfg 改名）");
                    summary.append("  - ⚠️ HMCL 整合包标记停用失败：").append(e.getMessage()).append('\n');
                }
                if (marker != null) {
                    journal.log("⚠️ 这个实例是 HMCL 导入的整合包（modpack.cfg 里内嵌着 Modrinth 清单）。");
                    journal.log("   HMCL 每次【启动】都会跑整合包补全任务，按清单把缺失文件下载回来 ——");
                    journal.log("   也就是说，不改这一步，你用 HMCL 启动一次，本次摘掉的模组就会被原样补回（实测确认过）。");
                    journal.log("   已把标记改名为 " + marker.renamed().getFileName()
                            + "（内容一字未改）：HMCL 不再当它是整合包，也就不会自动补全。");
                    journal.log("   想恢复 HMCL 的【更新整合包】入口：【还原到检验前】会一并改回，或手动改回 modpack.cfg。");
                    summary.append("  - HMCL 整合包标记已停用：modpack.cfg → ")
                            .append(marker.renamed().getFileName())
                            .append("（否则 HMCL 启动时会按清单把摘掉的模组补回来；【还原到检验前】会自动改回）\n");
                }
            }

            Files.writeString(journal.runDir().resolve("fix-summary.md"), summary.toString(),
                    StandardCharsets.UTF_8);
            journal.log("");
            journal.log("会话总结: " + journal.runDir().resolve("fix-summary.md"));
            if (marker != null) {
                o.notifier().notify("已停用 HMCL 的整合包标记", String.join("\n",
                        "为什么这么做：",
                        "本实例是 HMCL 导入的 Modrinth 整合包。HMCL 每次【启动】都会按包内清单",
                        "（modpack.cfg 里的 files 列表）检查文件，缺失的会自动下载回来——",
                        "也就是说，不改这一步，你用 HMCL 启动一次，刚才摘掉的模组就会被原样补回，",
                        "等于白修。（HMCL 日志证据：启动后执行 ModrinthCompletionTask → 下载 13 个文件）",
                        "",
                        "做了什么：把标记文件改名（内容一字未改）",
                        "  modpack.cfg  →  " + marker.renamed().getFileName(),
                        "这样 HMCL 不再把它当整合包，也就不会再自动补全。",
                        "",
                        "代价与恢复：HMCL 里【更新整合包】的入口会消失；",
                        "想恢复就点【还原到检验前】（会一并把文件名改回），或手动改回 modpack.cfg。"));
            }
            return finalCode;
        } catch (NetworkManager.StopRequested stop) {
            journal.log("");
            journal.log("⏹ " + stop.getMessage() + " —— 正在把本会话的改动还原到检验前…");
            try {
                restoreSession(toolRoot, inst, journal.runDir().getFileName().toString(), journal)
                        .forEach(s -> journal.log("   " + s));
            } catch (Exception e) {
                journal.log("   ⚠️ 还原时出错: " + e.getMessage() + "（可点【还原】重试）");
            }
            restoreModpackMarker(inst, journal);
            journal.log("已按你的要求停止并还原。");
            return 2;
        } finally {
            journal.close();
        }
    }

    /**
     * 把 HMCL 整合包标记改回 {@code modpack.cfg}（"还原"语义的一部分）。
     *
     * <p>只有被本工具停用过（存在 {@code modpack.cfg.maa-checker-bak*}）才会动手；
     * 没停用过就什么都不做——绝不去碰用户自己的文件。
     */
    private static void restoreModpackMarker(GameInstance inst, RunJournal journal) {
        try {
            Path back = ModpackMarker.restore(inst);
            if (back != null) {
                journal.log("   （HMCL 整合包标记已还原：" + back.getFileName() + "）");
            }
        } catch (Exception e) {
            journal.log("   ⚠️ 还原 HMCL 整合包标记失败: " + e.getMessage());
        }
    }

    /**
     * 轮次用完仍未通过时问用户：追加轮次 / 停止（保留改动）/ 停止并还原。
     *
     * <p>没有 {@code asker}（无人值守、或调用方没装配）时直接按"停止"处理——
     * 绝不能因为一个弹窗就把命令行/CI 挂死。
     */
    static RoundAsker.Decision askWhenRoundsExhausted(RoundAsker asker, RunJournal journal,
                                                     GameInstance inst, int doneRounds,
                                                     int maxRounds) {
        boolean canExtend = maxRounds < MAX_TOTAL_FIX_ROUNDS;
        StringBuilder msg = new StringBuilder();
        msg.append("已经跑完 ").append(maxRounds).append(" 轮，整合包还没能进主菜单。\n");
        msg.append("当前：loader=").append(inst.loaderVersion()).append("，mods=")
                .append(inst.modCount()).append(" 个。\n");
        msg.append("可以再给几轮试试（有些包要更多轮才收敛），也可以现在就收手。");
        if (!canExtend) {
            msg.append("\n（已达总轮次上限 ").append(MAX_TOTAL_FIX_ROUNDS).append("，只能停止或还原）");
        }
        RoundAsker.Ask ask = new RoundAsker.Ask("自动修复的轮次用完了", msg.toString(),
                doneRounds, maxRounds, MAX_TOTAL_FIX_ROUNDS, canExtend, DEFAULT_EXTRA_ROUNDS);
        if (asker == null) {
            journal.log("（无人值守：按【停止】处理，改动保留；要一次给更多轮次请用 --rounds N）");
            return RoundAsker.Decision.stopKeep();
        }
        return asker.ask(ask);
    }

    /** 还原某个会话的所有轮次（从最新一轮往回） */
    public static List<String> restoreSession(Path toolRoot, GameInstance inst, String session,
                                              RunJournal journal) throws Exception {
        Path stateDir = toolRoot.resolve("state").resolve(RunJournal.safeName(inst.name()));
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(stateDir)) {
            try (var s = Files.list(stateDir)) {
                files = s.filter(p -> p.getFileName().toString().startsWith(session + "-round-"))
                        .sorted(Comparator.reverseOrder()).toList();
            }
        }
        Tombstones tombs = Tombstones.load(stateDir);
        List<String> steps = new ArrayList<>();
        for (Path f : files) {
            var m = java.util.regex.Pattern.compile(".*-round-(\\d+)\\.json$")
                    .matcher(f.getFileName().toString());
            if (!m.matches()) continue;
            steps.addAll(Restore.restoreRound(f, inst, Integer.parseInt(m.group(1)), tombs,
                    journal::log));
        }
        tombs.save();
        return steps;
    }

    /**
     * 是否"只有非依赖型故障"——这类故障才值得先原样重启一次确认是否偶发。
     * 缺前置/加载器版本不足属确定性问题，重试没有任何意义。
     */
    public static boolean onlyNonDependencyIssues(Diagnosis d) {
        if (!d.hasIssues()) return false;
        return d.issues().stream().noneMatch(i -> i.kind() == Diagnosis.Kind.MISSING_REQUIRED
                || i.kind() == Diagnosis.Kind.LOADER_TOO_OLD
                || i.kind() == Diagnosis.Kind.MOD_VERSION_MISMATCH
                // 版本号格式非法是确定性的：同一个 jar 每次都会被解析器拒绝，重试纯属浪费一轮
                || i.kind() == Diagnosis.Kind.BAD_VERSION_FORMAT);
    }

    // ==================================================================================
    // 还原（对用户可见的入口）
    // ==================================================================================

    /** @param onlyRound null 表示还原全部历史轮次（从最新往回） */
    public static int restore(GameInstance inst, Path toolRoot, Integer onlyRound,
                              Consumer<String> sink) throws Exception {
        Path stateDir = toolRoot.resolve("state").resolve(RunJournal.safeName(inst.name()));
        if (!Files.isDirectory(stateDir)) {
            sink.accept("没有找到该实例的改动记录: " + stateDir);
            return 0;
        }
        record Item(String session, int round, Path file) {
        }
        List<Item> items = new ArrayList<>();
        try (var s = Files.list(stateDir)) {
            var pattern = java.util.regex.Pattern.compile("^(?:(\\d{8}-\\d{6})-)?round-(\\d+)\\.json$");
            for (Path p : s.toList()) {
                var m = pattern.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                int r = Integer.parseInt(m.group(2));
                if (onlyRound != null && r != onlyRound) continue;
                items.add(new Item(m.group(1) == null ? "(早期)" : m.group(1), r, p));
            }
        }
        items.sort(Comparator.comparing(Item::session).thenComparing(Item::round).reversed());
        if (items.isEmpty()) {
            sink.accept("没有可还原的轮次（" + stateDir + "）");
            return 0;
        }
        Tombstones tombs = Tombstones.load(stateDir);
        sink.accept("=== 还原 " + inst.name() + " ===");
        for (Item it : items) {
            sink.accept("--- 会话 " + it.session() + " 第 " + it.round() + " 轮 ---");
            Restore.restoreRound(it.file(), inst, it.round(), tombs, sink)
                    .forEach(s -> sink.accept("   " + s));
        }
        // HMCL 整合包标记也属于"检验前的状态"：被停用过就改回来（否则用户以后在 HMCL 里看不到"更新整合包"）
        try {
            Path markerBack = ModpackMarker.restore(inst);
            if (markerBack != null) {
                sink.accept("已还原 HMCL 整合包标记: " + markerBack.getFileName()
                        + "（注：HMCL 下次启动会重新校验清单，被摘掉的模组可能会被它补回来）");
            }
        } catch (Exception e) {
            sink.accept("⚠️ 还原 HMCL 整合包标记失败: " + e.getMessage()
                    + "（可手动把 " + ModpackMarker.FILE + ModpackMarker.DISABLED_SUFFIX + " 改回 "
                    + ModpackMarker.FILE + "）");
        }
        GameInstance after = InstanceScanner.scanOne(inst.gameRoot(), inst.dir());
        tombs.save();
        sink.accept("还原完成。当前 mods 数: " + (after == null ? "?" : after.modCount())
                + "；剩余墓碑 " + tombs.all().size() + " 个");
        return 0;
    }

    // ==================================================================================
    // 报告落盘（人读 txt + 机器读 json）
    // ==================================================================================

    public static void writeReport(Path runDir, Round r) throws Exception {
        Files.createDirectories(runDir);
        GameInstance inst = r.inst();
        LaunchResult result = r.launch();
        Diagnosis diag = r.diag();
        StringBuilder sb = new StringBuilder();
        sb.append("MAA-Checker 检验报告").append(System.lineSeparator());
        sb.append("实例: ").append(inst.name()).append(System.lineSeparator());
        sb.append("环境: mc=").append(inst.mcVersion()).append(" loader=").append(inst.loader())
                .append(' ').append(inst.loaderVersion()).append(" mods=").append(inst.modCount())
                .append(System.lineSeparator());
        sb.append("目录: ").append(inst.dir()).append(System.lineSeparator());
        sb.append("Java: ").append(r.java().versionLine()).append("（需要 ")
                .append(r.requiredJava()).append("）").append(System.lineSeparator());
        sb.append("结论: ").append(result.verdict()).append("（证据：").append(result.evidence())
                .append("，耗时 ").append(String.format("%.1f", result.elapsedMs() / 1000.0))
                .append("s，退出码 ").append(result.exitCode()).append("）")
                .append(System.lineSeparator());
        sb.append("时间线: ").append(result.timeline().isEmpty() ? "（无）"
                : String.join(" → ", result.timeline())).append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("静态预检：高置信 ")
                .append(r.stat().highConfidence().size()).append(" 条 / 需人工 ")
                .append(r.stat().needsReview().size()).append(" 条").append(System.lineSeparator());
        r.stat().highConfidence().forEach(p -> sb.append("  · ").append(p.describe())
                .append(System.lineSeparator()));
        sb.append(System.lineSeparator()).append("运行期诊断：").append(diag.issues().size())
                .append(" 条").append(System.lineSeparator());
        diag.issues().forEach(i -> sb.append("  · ").append(i.describe())
                .append(System.lineSeparator()).append("    建议: ").append(i.suggestion())
                .append(System.lineSeparator()));
        if (!diag.attributions().isEmpty()) {
            sb.append(System.lineSeparator()).append("归因过程（为什么怀疑它 / 为什么没怀疑另一个）：")
                    .append(System.lineSeparator());
            diag.attributions().forEach(a -> sb.append("  · ").append(a)
                    .append(System.lineSeparator()));
        }
        sb.append(System.lineSeparator()).append("crash-report: ").append(diag.note())
                .append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("证据行:").append(System.lineSeparator());
        diag.evidenceLines().forEach(l -> sb.append("  ").append(l).append(System.lineSeparator()));
        sb.append(System.lineSeparator()).append("日志: latest.log 切片=")
                .append(result.freshLog()).append(" / 客户端输出=").append(result.stdoutLog())
                .append(System.lineSeparator());
        sb.append("启动命令（脱敏）: ").append(r.spec().commandText()).append(System.lineSeparator());
        Files.writeString(runDir.resolve("report.txt"), sb.toString(), StandardCharsets.UTF_8);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("tool", "MAA-Checker");
        json.put("version", TOOL_VERSION);
        json.put("time", LocalDateTime.now().toString());
        json.put("instance", Map.of("name", inst.name(), "mc", inst.mcVersion(),
                "loader", inst.loader(), "loaderVersion", String.valueOf(inst.loaderVersion()),
                "dir", inst.dir().toString(), "mods", inst.modCount()));
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("verdict", result.verdict().name());
        launch.put("evidence", result.evidence());
        launch.put("elapsedMs", result.elapsedMs());
        launch.put("exitCode", result.exitCode());
        launch.put("killed", result.killed());
        launch.put("timeline", result.timeline());
        launch.put("requiredJava", r.requiredJava());
        launch.put("java", r.java().versionLine() + " @ " + r.java().javaExe());
        launch.put("notes", r.spec().notes());
        json.put("launch", launch);
        json.put("staticPrecheck", Map.of(
                "jarCount", r.stat().jarCount(), "modCount", r.stat().modCount(),
                "highConfidence", r.stat().highConfidence().stream()
                        .map(StaticDepsScanner.Problem::describe).toList(),
                "needsReview", r.stat().needsReview().stream()
                        .map(StaticDepsScanner.Problem::describe).toList()));
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Diagnosis.Issue i : diag.issues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", i.kind().name());
            m.put("modId", i.modId());
            m.put("requestedBy", i.requestedBy());
            m.put("expectedRange", i.expectedRange());
            m.put("actualVersion", i.actualVersion());
            m.put("jarFile", i.jarFile());
            m.put("detail", i.detail());
            m.put("suggestion", i.suggestion());
            m.put("cascadable", i.cascadable());
            issues.add(m);
        }
        Map<String, Object> dx = new LinkedHashMap<>();
        dx.put("issues", issues);
        dx.put("crashReport", String.valueOf(diag.crashReport()));
        dx.put("crashReportIsFresh", diag.crashReportIsFresh());
        dx.put("note", diag.note());
        dx.put("attributions", diag.attributions());
        dx.put("evidenceLines", LogDiagnoser.dedupe(diag.evidenceLines()));
        json.put("diagnosis", dx);
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(runDir.resolve("report.json").toFile(), json);
    }

    public static boolean recentlyActive(GameInstance inst) {
        Path latest = inst.dir().resolve("logs").resolve("latest.log");
        try {
            return Files.isRegularFile(latest)
                    && System.currentTimeMillis()
                    - Files.getLastModifiedTime(latest).toMillis() < RECENT_ACTIVITY_MS;
        } catch (Exception e) {
            return false;
        }
    }

    static String abbreviate(String s) {
        return s.length() <= 900 ? s : s.substring(0, 900) + " …（截断，完整见文件）";
    }
}
