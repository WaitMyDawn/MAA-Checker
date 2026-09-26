package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把"启动失败"翻译成结构化问题清单。
 *
 * <p>只解析<b>本次运行新增</b>的日志切片（由 {@link InstanceLauncher} 用内容锚点切好）。
 * 这是本模块最容易犯错的地方：直接读 latest.log 会把上一轮的故障当成这一轮的，
 * 于是"修好了还说没修好"、"越修越乱"。
 *
 * <p>解析的三类来源（按可信度排序）：
 * <ol>
 *   <li>依赖明细块 {@code Missing or unsupported mandatory dependencies:} —— 有明确的
 *       Mod ID / Requested by / Expected range / Actual version 四元组，<b>可直接驱动动作</b>；</li>
 *   <li>模组加载失败块 {@code Mod loading issue for:} / Mixin / 重复模组 —— 只能定位到"报错者自身"；</li>
 *   <li>crash-report 文件 —— 有 {@code Mod File:} 字段时能指认元凶，没有就只能给出异常链。</li>
 * </ol>
 */
public final class LogDiagnoser {

    /** 依赖明细行的四元组：只吃加载器自己打的格式，避免把模组自定义日志误认成依赖声明 */
    private static final Pattern DEP_LINE = Pattern.compile(
            "Mod ID:\\s*'([^']+)',\\s*Requested by:\\s*'([^']+)',\\s*"
                    + "Expected range:\\s*'([^']*)',\\s*Actual version:\\s*'([^']*)'");

    private static final Pattern MOD_LOAD_ISSUE = Pattern.compile("^Mod loading issue for:\\s*(\\S+)");
    private static final Pattern MOD_FILE = Pattern.compile("^Mod file:\\s*(.+)$");
    private static final Pattern FAILURE_MESSAGE = Pattern.compile("^(Failure message|Reason):\\s*(.+)$");
    private static final Pattern MIXIN_FAILED = Pattern.compile("Mixin apply (for mod (\\S+) )?failed");
    /** "Mixin <配置>:<类> from mod <modid> ..." —— 只有该行确实是失败时才采用（见 isMixinError） */
    private static final Pattern MIXIN_FROM_MOD = Pattern.compile("Mixin\\s+(\\S+)\\s+from\\s+mod\\s+(\\S+)");
    private static final Pattern MIXIN_FROM_GENERIC = Pattern.compile("Mixin\\s+(\\S+)\\s+from\\s+(\\S+)");
    private static final Pattern DUP_MODS = Pattern.compile("(?i)duplicate mods?[^:]*:\\s*(.*)$");
    private static final Pattern MAIN_EXCEPTION = Pattern.compile("^Exception in thread \"main\"\\s+(.*)$");
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by:\\s*(.+)$");
    /**
     * FML 汇总的模组故障块头：{@code -- Mod loading issue for: iceandfire --}。
     * 注意：这块内容<b>只打印在客户端 stdout</b>（我们自己重定向的 client-stdout.log），
     * 实测 latest.log 里没有——早期版本因此完全漏诊，明明有崩溃报告却报"没解析出故障"。
     */
    private static final Pattern ISSUE_HEADER = Pattern.compile("^-{2,}\\s*Mod loading issue for:\\s*(\\S+)\\s*-{2,}$");
    private static final Pattern MOD_FILE_ANY = Pattern.compile("^Mod file:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- 归因渠道（2026-09-26 新增）
    /**
     * L4 指纹：模组声明了一个过不了加载器版本解析的版本号。
     *
     * <p>实测（万象包，2026-09-26）：日志里<b>只有版本号、没有 modId</b>，且这行只在客户端 stdout 里：
     * {@code Caused by: java.lang.IllegalArgumentException: 1-V1-1.21+: Empty pre-release}
     */
    private static final Pattern EMPTY_PRERELEASE = Pattern.compile(
            "IllegalArgumentException:\\s*([^:\\s]+):\\s*Empty pre-release");
    /** 兜底写法：只要出现 Empty pre-release，就取它前面那个词当版本号 */
    private static final Pattern EMPTY_PRERELEASE_LOOSE = Pattern.compile(
            "([^\\s:]+)\\s*:\\s*Empty pre-release");
    /**
     * {@code latest.log} 的模组清单行（FML 每次启动都打）：
     * <pre>		Panda Temple 1-V1-1.21+ (mr_panda_temple)</pre>
     * 右对齐解析：显示名可能带空格、方括号、颜色码（如 {@code Timber [Enchantment] 4.0 (mr_timber_strikeenchantment)}）。
     */
    private static final Pattern MOD_LIST_LINE = Pattern.compile(
            "^\\s*(.+?)\\s+(\\S+)\\s+\\(([^()]+)\\)\\s*$");
    /**
     * {@code debug.log} 的权威映射行（只作交叉验证，debug 日志可能被关掉）：
     * {@code Found valid mod file x.jar with {mr_panda_temple} mods - versions {1-V1-1.21+}}
     */
    private static final Pattern DEBUG_MOD_VERSION = Pattern.compile(
            "with\\s+\\{([^}]+)}\\s+mods\\s+-\\s+versions\\s+\\{([^}]+)}");
    /**
     * 堆栈帧里的模组层：NeoForge 的帧长这样，层名不止 TRANSFORMER 一种（实测都出现过）：
     * <pre>
     *   at TRANSFORMER/legendary_monsters@1.21.1/net.miauczel....
     *   at LAYER SERVICE/preloading.tricks@3.7.2/settingdust....
     *   at MC-BOOTSTRAP/Reflect@1.6.3/net.lenni0451....
     * </pre>
     */
    private static final Pattern TRANSFORMER_FRAME = Pattern.compile(
            "at\\s+([A-Z][A-Z0-9 _-]*)/([^@/\\s]+)@");
    /** 异常文本里的类名：{@code ... in class 'settingdust.preloading_tricks.neoforge.modlauncher.X'} */
    private static final Pattern CLASS_NAME = Pattern.compile("class\\s+'([^']+)'");
    /** {@code Caused by} 块里的 {@code from mod X}（L2） */
    private static final Pattern CAUSED_BY_FROM_MOD = Pattern.compile(
            "from\\s+mod\\s+([^\\s,;:\\]}]+)", Pattern.CASE_INSENSITIVE);

    /** 归因时忽略的"框架层"名字（它们不是模组，摘不得） */
    private static final java.util.Set<String> FRAMEWORK = java.util.Set.of(
            "minecraft", "neoforge", "forge", "fmlcore", "fmlloader", "javafml", "lowcodefml",
            "bootstraplauncher", "securejarhandler", "mclang", "jdk", "java", "javax", "sun",
            "spongepowered", "apache", "slf4j", "log4j");

    /** 落进"证据行"的过滤器：与人看日志时关注的同一批关键字 */
    private static final Pattern EVIDENCE = Pattern.compile(
            "Mod loading issue for:|Failure message:|Mod file:|Missing or unsupported|Mod ID:"
                    + "|Expected range:|Loading errors|Caused by:|Incompatible mods|Mixin apply"
                    + "|duplicate|Mod ID is|Failed to create mod instance|NoClassDefFoundError"
                    + "|NoSuchMethodError|ClassNotFoundException");

    private static final int DETAIL_SCAN_LINES = 24;
    private static final int EVIDENCE_MAX = 200;
    private static final int DETAIL_MAX_CHARS = 400;

    private LogDiagnoser() {
    }

    /**
     * 直接诊断若干日志/崩溃报告文件（不依赖实例目录），供 {@code --diagnose} 使用。
     * 崩溃报告（文件名含 crash）走崩溃报告解析路径；其余按普通日志解析。
     */
    public static Diagnosis diagnoseFiles(List<Path> files, Map<String, String> jarIndex) {
        List<Diagnosis.Issue> issues = new ArrayList<>();
        Map<String, Diagnosis.Issue> map = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        List<String> rawLines = new ArrayList<>();
        for (Path f : files) {
            if (f == null || !Files.isRegularFile(f)) continue;
            String name = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("crash")) {
                parseCrashReport(f, jarIndex, map);
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            rawLines.addAll(lines);
            for (String line : lines) {
                if (evidence.size() < EVIDENCE_MAX && EVIDENCE.matcher(line).find()) {
                    evidence.add(line.strip());
                }
            }
            parseFmlIssueBlocks(lines, jarIndex, map);
            parseModLoadIssues(lines, jarIndex, map);
            parseMixinFailures(lines, jarIndex, map);
            parseDuplicateMods(lines, jarIndex, map);
            parseMainException(lines, map);
        }
        // 归因渠道（离线诊断同样适用：latest.log 的模组清单行本身就带 modId）
        List<String> attributions = new ArrayList<>();
        applyAttributionChannels(rawLines, null, ModJarIndex.ofJarIndex(jarIndex), map, attributions);
        issues.addAll(map.values());
        return new Diagnosis(issues, evidence.isEmpty() ? List.of() : List.copyOf(evidence),
                null, false, "由 --diagnose 直接解析文件", List.copyOf(attributions));
    }

    /** 兼容入口：只有 modId → jar 的索引（老调用方与单测用） */
    public static Diagnosis diagnose(GameInstance inst, Path freshLog, List<Path> extraLogs,
                                     Map<String, String> jarIndex, long launchStart) {
        return diagnose(inst, freshLog, extraLogs, ModJarIndex.ofJarIndex(jarIndex), launchStart);
    }

    /**
     * @param inst        被检验实例（需要 loader 名来区分"加载器版本不足"与"普通版本不匹配"；
     *                    另外 {@code Empty pre-release} 归因会看一眼 {@code logs/debug.log} 做交叉验证）
     * @param freshLog    本次新增日志切片（可为 null：没抓到日志）
     * @param extraLogs   额外日志（客户端 stdout 等）：FML 的故障明细只在这里，必须一起解析
     * @param meta        mods 目录索引（modId → jar / 声明版本，{@link ModJarIndex#scanMeta}）
     * @param launchStart 启动时刻（判断 crash-report 是不是本次新产生的）
     */
    public static Diagnosis diagnose(GameInstance inst, Path freshLog, List<Path> extraLogs,
                                     ModJarIndex.Meta meta, long launchStart) {
        Map<String, String> jarIndex = meta == null ? Map.of() : meta.jarOf();
        List<String> evidence = new ArrayList<>();
        List<String> rawLines = new ArrayList<>();
        if (freshLog != null && Files.isRegularFile(freshLog)) {
            try {
                rawLines = Files.readAllLines(freshLog, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new Diagnosis(List.of(), List.of(),
                        null, false, "日志切片读取失败: " + e.getMessage());
            }
        }
        if (extraLogs != null) {
            for (Path extra : extraLogs) {
                if (extra == null || !Files.isRegularFile(extra)) continue;
                try {
                    rawLines.addAll(Files.readAllLines(extra, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 客户端 stdout 读不到不影响主流程
                }
            }
        }
        for (String line : rawLines) {
            if (evidence.size() >= EVIDENCE_MAX) break;
            if (EVIDENCE.matcher(line).find()) evidence.add(line.strip());
        }

        String text = String.join("\n", rawLines);
        // 用 LinkedHashMap 去重：同一个问题在日志里可能出现多次（重试、多次加载）
        Map<String, Diagnosis.Issue> issues = new LinkedHashMap<>();

        parseDependencyBlock(text, inst, jarIndex, issues);
        parseModLoadIssues(rawLines, jarIndex, issues);
        parseFmlIssueBlocks(rawLines, jarIndex, issues);
        parseMixinFailures(rawLines, jarIndex, issues);
        // 同一次 Mixin 失败常被打印成两行（"Mixin transformation of ... failed" + "MixinApplyError: Mixin X from mod Y"）。
        // 已经能指认到具体模组时，丢掉那条更模糊的，避免把 1 个问题报成 2 个。
        if (issues.values().stream().anyMatch(i -> i.kind() == Diagnosis.Kind.MIXIN_FAILURE
                && i.modId() != null)) {
            issues.values().removeIf(i -> i.kind() == Diagnosis.Kind.MIXIN_FAILURE
                    && i.modId() == null);
        }
        parseDuplicateMods(rawLines, jarIndex, issues);
        parseMainException(rawLines, issues);

        // ---- 归因渠道：日志里没有可定位的 modId 时，按优先级 L4 > L2 > L3 反查肇事模组 ----
        List<String> attributions = new ArrayList<>();
        applyAttributionChannels(rawLines, inst, meta, issues, attributions);

        Path crashReport = null;
        boolean crashFresh = false;
        Path crashDir = inst.dir().resolve("crash-reports");
        if (Files.isDirectory(crashDir)) {
            try (Stream<Path> s = Files.list(crashDir)) {
                Path newest = null;
                long newestTime = 0;
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    long t = Files.getLastModifiedTime(f).toMillis();
                    if (t > newestTime) {
                        newestTime = t;
                        newest = f;
                    }
                }
                if (newest != null) {
                    crashReport = newest;
                    crashFresh = newestTime >= launchStart;
                    if (crashFresh) parseCrashReport(newest, jarIndex, issues);
                }
            } catch (IOException ignored) {
            }
        }

        String note = crashReport == null ? "实例内没有 crash-reports 目录或目录为空"
                : (crashFresh ? "本次新产生崩溃报告: " + crashReport.getFileName()
                : "本次未产生崩溃报告（该故障类型只写日志、不落崩溃报告）");
        return new Diagnosis(List.copyOf(issues.values()), List.copyOf(evidence),
                crashReport, crashFresh, note, List.copyOf(attributions));
    }

    // ------------------------------------------------------------------ 1) 依赖明细块
    private static void parseDependencyBlock(String text, GameInstance inst,
                                             Map<String, String> jarIndex,
                                             Map<String, Diagnosis.Issue> issues) {
        Matcher m = DEP_LINE.matcher(text);
        while (m.find()) {
            String target = m.group(1);
            String requester = m.group(2);
            String range = m.group(3);
            String actual = m.group(4);
            Diagnosis.Kind kind;
            // 顺序很重要：先看"缺的是不是加载器自己"。
            // 加载器缺失/版本不足都属于"loader 侧问题"（动作 A 会重跑 installer 把它补/升回来），
            // 绝不能当成"Modrinth 上缺一个叫 neoforge 的模组"去补装 —— neoforge 不是 Modrinth 模组。
            // 实测场景：libraries 被误删后，运行期会报
            //   Mod ID: 'neoforge', Requested by: 'xxx', Actual version: '[MISSING]'
            //   Mod ID: 'minecraft', ... '[MISSING]'   ← 这就是加载器自己没装好
            boolean loaderMissing = "[MISSING]".equalsIgnoreCase(actual) || actual.isBlank();
            if (target.equals(inst.loader()) || ("minecraft".equals(target) && loaderMissing)) {
                kind = Diagnosis.Kind.LOADER_TOO_OLD;
            } else if (loaderMissing) {
                kind = Diagnosis.Kind.MISSING_REQUIRED;
            } else {
                kind = Diagnosis.Kind.MOD_VERSION_MISMATCH;
            }
            String jar = jarIndex.get(target);
            if (jar == null) jar = jarIndex.get(requester);
            add(issues, new Diagnosis.Issue(kind, target, requester, range, actual, jar,
                    m.group().strip()));
        }
    }

    // ------------------------------------------------------------------ 2) 模组加载失败块
    /**
     * FML 汇总块（{@code -- Mod loading issue for: <modid> --} + Mod file / Failure message）。
     * 这是"某个模组在初始化时抛异常"最准确的定位来源，由客户端 stdout 提供。
     */
    private static void parseFmlIssueBlocks(List<String> lines, Map<String, String> jarIndex,
                                            Map<String, Diagnosis.Issue> issues) {
        String modId = null;
        String modFile = null;
        String reason = null;
        for (int i = 0; i <= lines.size(); i++) {
            String l = i < lines.size() ? lines.get(i).strip() : null;
            Matcher head = l == null ? null : ISSUE_HEADER.matcher(l);
            boolean isHeader = head != null && head.matches();
            if (l == null || isHeader) {
                if (modId != null) {   // 块结束，落一条
                    String jar = modFile != null ? fileNameOf(modFile) : jarIndex.get(modId);
                    add(issues, new Diagnosis.Issue(Diagnosis.Kind.MOD_LOAD_FAILURE, modId, modId,
                            "-", "-", jar,
                            truncate("Failure message: " + (reason == null ? "?" : reason)
                                    + (modFile == null ? "" : "；Mod file: " + modFile))));
                }
                modId = isHeader ? head.group(1) : null;
                modFile = null;
                reason = null;
                continue;
            }
            if (modId == null) continue;
            Matcher f = MOD_FILE_ANY.matcher(l);
            if (f.matches()) {
                modFile = f.group(1).strip();
                continue;
            }
            Matcher r = FAILURE_MESSAGE.matcher(l);
            if (r.matches() && reason == null) reason = r.group(2).strip();
        }
    }

    private static void parseModLoadIssues(List<String> lines, Map<String, String> jarIndex,
                                           Map<String, Diagnosis.Issue> issues) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher head = MOD_LOAD_ISSUE.matcher(lines.get(i).strip());
            if (!head.find()) continue;
            String modId = head.group(1);
            String modFile = null;
            String reason = null;
            for (int j = i + 1; j < Math.min(lines.size(), i + DETAIL_SCAN_LINES); j++) {
                String l = lines.get(j).strip();
                if (MOD_LOAD_ISSUE.matcher(l).find()) break;
                Matcher f = MOD_FILE.matcher(l);
                if (f.find()) {
                    modFile = f.group(1).strip();
                    continue;
                }
                Matcher r = FAILURE_MESSAGE.matcher(l);
                if (r.find() && reason == null) reason = r.group(2).strip();
            }
            String jar = modFile != null ? fileNameOf(modFile) : jarIndex.get(modId);
            String detail = truncate("reason=" + (reason == null ? "?" : reason)
                    + (modFile == null ? "" : "; modFile=" + modFile));
            add(issues, new Diagnosis.Issue(Diagnosis.Kind.MOD_LOAD_FAILURE, modId, modId,
                    "-", "-", jar, detail));
        }
    }

    // ------------------------------------------------------------------ 3) Mixin
    private static void parseMixinFailures(List<String> lines, Map<String, String> jarIndex,
                                           Map<String, Diagnosis.Issue> issues) {
        for (String raw : lines) {
            String line = raw.strip();
            if (!line.contains("Mixin")) continue;
            Matcher applied = MIXIN_FAILED.matcher(line);
            boolean appliedMatched = applied.find();
            // 关键：Mixin 的 WARN（如 "has multiple constructors ... was selected"）不是故障，
            // 只有 ERROR 级或明确的失败短语才算——否则会把无辜模组指成元凶
            if (!appliedMatched && !isMixinError(line)) continue;

            String modId = appliedMatched ? applied.group(2) : null;
            Matcher fromMod = MIXIN_FROM_MOD.matcher(line);
            if (fromMod.find()) modId = fromMod.group(2);

            if (modId != null) {
                add(issues, new Diagnosis.Issue(Diagnosis.Kind.MIXIN_FAILURE, modId, modId,
                        "-", "-", jarIndex.get(modId),
                        truncate("Mixin 应用失败：" + line)));
                continue;
            }
            Matcher generic = MIXIN_FROM_GENERIC.matcher(line);
            String detail = generic.find()
                    ? "Mixin " + generic.group(1) + " from " + generic.group(2)
                    : line;
            add(issues, new Diagnosis.Issue(Diagnosis.Kind.MIXIN_FAILURE, null, null,
                    "-", "-", null, truncate(detail)));
        }
    }

    /**
     * 是否"真正的 Mixin 失败"。只认 ERROR 级或明确的失败短语——
     * 实测踩过：{@code [WARN] Mixin alexscaves.mixins.json:... has multiple constructors, ... was selected}
     * 是正常提示，早期版本把它当成 MIXIN_FAILURE，还会把 modId 解析成 "mod"。
     */
    static boolean isMixinError(String line) {
        if (line.contains("/ERROR]") || line.contains("MixinApplyError")
                || line.contains("MixinTransformerError")) {
            return true;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mixin apply failed")
                || lower.contains("mixin transformation of")
                || lower.contains("failed to apply mixin")
                || lower.contains("could not apply mixin");
    }

    // ------------------------------------------------------------------ 4) 重复模组
    private static void parseDuplicateMods(List<String> lines, Map<String, String> jarIndex,
                                           Map<String, Diagnosis.Issue> issues) {
        for (String raw : lines) {
            Matcher m = DUP_MODS.matcher(raw.strip());
            if (!m.find()) continue;
            String rest = m.group(1).strip();
            String modId = rest.isBlank() ? null : rest.split("[\\s,(]", 2)[0];
            add(issues, new Diagnosis.Issue(Diagnosis.Kind.DUPLICATE_MOD, modId, modId,
                    "-", "-", modId == null ? null : jarIndex.get(modId), truncate(raw.strip())));
        }
    }

    // ------------------------------------------------------------------ 5) 兜底异常
    private static void parseMainException(List<String> lines, Map<String, Diagnosis.Issue> issues) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            Matcher m = MAIN_EXCEPTION.matcher(l);
            if (m.find()) {
                StringBuilder sb = new StringBuilder(m.group(1));
                for (int j = i + 1; j < Math.min(lines.size(), i + DETAIL_SCAN_LINES); j++) {
                    String c = lines.get(j).strip();
                    Matcher cb = CAUSED_BY.matcher(c);
                    if (cb.find()) {
                        sb.append(" | Caused by: ").append(cb.group(1));
                        if (sb.length() > DETAIL_MAX_CHARS) break;
                    }
                }
                add(issues, new Diagnosis.Issue(Diagnosis.Kind.CRASH_OTHER, null, null,
                        "-", "-", null, truncate(sb.toString())));
            }
        }
    }

    // ------------------------------------------------------------------ 6) crash-report
    private static void parseCrashReport(Path report, Map<String, String> jarIndex,
                                         Map<String, Diagnosis.Issue> issues) {
        List<String> lines;
        try (Stream<String> s = Files.lines(report, StandardCharsets.UTF_8)) {
            lines = s.limit(400).toList();
        } catch (IOException e) {
            return;
        }
        // FML 崩溃报告就是同一个 "Mod loading issue for" 块结构（只是字段大小写不同：Mod file:）
        parseFmlIssueBlocks(lines, jarIndex, issues);

        String modFile = null;
        String description = null;
        String caused = null;
        for (String raw : lines) {
            String l = raw.strip();
            Matcher f = MOD_FILE_ANY.matcher(l);
            if (f.matches()) {
                modFile = f.group(1).strip();
            } else if (l.startsWith("Description:")) {
                description = description == null
                        ? l.substring("Description:".length()).strip() : description;
            } else if (caused == null && l.startsWith("Caused by:")) {
                caused = l;
            }
        }
        if (modFile == null && caused == null) return;   // 崩溃报告没给出可用线索，不硬编结论
        String jar = modFile == null ? null : fileNameOf(modFile);
        // 上面的 FML 故障块通常已经给出了具体模组；此时再补一条"崩溃报告兜底"条目只是噪音
        if (jar != null && issues.values().stream().anyMatch(i ->
                i.kind() != Diagnosis.Kind.CRASH_OTHER && jar.equals(i.jarFile()))) {
            return;
        }
        String modId = null;
        if (jar != null) {
            for (var e : jarIndex.entrySet()) {
                if (e.getValue().equals(jar)) {
                    modId = e.getKey();
                    break;
                }
            }
        }
        add(issues, new Diagnosis.Issue(Diagnosis.Kind.CRASH_OTHER, modId, modId, "-", "-", jar,
                truncate("crash-report: " + (description == null ? "" : description + " | ")
                        + (caused == null ? "" : caused))));
    }

    private static void add(Map<String, Diagnosis.Issue> issues, Diagnosis.Issue issue) {
        issues.putIfAbsent(issue.kind() + "|" + issue.modId() + "|" + issue.requestedBy()
                + "|" + issue.expectedRange() + "|" + issue.actualVersion(), issue);
    }

    private static String truncate(String s) {
        return s.length() <= DETAIL_MAX_CHARS ? s : s.substring(0, DETAIL_MAX_CHARS) + "…";
    }

    /** 从日志里的路径字符串取出文件名；路径可能带引号、可能不是我平台能解析的写法，所以不硬用 Path.of */
    static String fileNameOf(String raw) {
        String s = raw.strip().replace("\"", "");
        int cut = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        return cut >= 0 ? s.substring(cut + 1) : s;
    }

    /** 证据行里剔除重复的噪声（同一行重复出现多次只留一条） */
    public static List<String> dedupe(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>(lines);
        return List.copyOf(seen);
    }

    // ==================================================================================
    // 归因渠道：日志里没有 FML 故障块、拿不到 modId 时，按优先级反查"到底是谁"
    //
    //   优先级：L4 版本号指纹（最硬，实测能一锤定音） > L2 Caused by 链的 from mod X > L3 堆栈帧/类名
    //
    //   为什么这个顺序不能乱（2026-09-26 实测，万象包）：
    //   报错文本是 MethodInvocationException ... in class 'settingdust.preloading_tricks...'，
    //   真正的根因却是 Caused by 里的 IllegalArgumentException: 1-V1-1.21+: Empty pre-release。
    //   若按栈帧归因就会去摘 preloading_tricks —— 而摘掉它之后，同一个版本号错误照样出现。
    // ==================================================================================

    /**
     * 三条渠道按优先级降级执行：**L4 版本号指纹 > L2 崩溃链 > L3 堆栈/类名**。
     *
     * <p>高优先级一旦定位到具体模组，低优先级的结果只能写进"归因过程"，**绝不产生动作**——
     * 两条实测教训：
     * <ol>
     *   <li>万象包里顶层异常指向 {@code preloading_tricks}，真根因却是版本号（L4 命中时靠这条压制住）；</li>
     *   <li>修完版本号之后，有一次 Mixin 崩溃里 L2 指向 {@code tensura_iron_spells}、L3 指向
     *       {@code aces_spell_utils}，两条都摘 → 后者进墓碑 → 下一轮又连带摘掉两个依赖它的模组，
     *       <b>一次低置信误判摘了 3 个</b>。</li>
     * </ol>
     * 所以现在的口径是：**每轮只在最可信的那条渠道上摘一个**，剩下的留给下一轮暴露。
     */
    private static void applyAttributionChannels(List<String> rawLines, GameInstance inst,
                                                 ModJarIndex.Meta meta,
                                                 Map<String, Diagnosis.Issue> issues,
                                                 List<String> attributions) {
        parseVersionFormatFingerprint(rawLines, inst, meta, issues, attributions);
        if (attributed(issues, Diagnosis.Kind.BAD_VERSION_FORMAT)) {
            collectLowConfidenceCandidates(rawLines, meta, attributions,
                    "已由版本号指纹定位到肇事模组，下面这些只是报信者，未采用");
            return;
        }
        parseCausedByAttribution(rawLines, meta, issues, attributions);
        if (attributed(issues, Diagnosis.Kind.CAUSED_BY_ATTRIBUTED)) {
            collectLowConfidenceCandidates(rawLines, meta, attributions,
                    "已由崩溃链定位到肇事模组，堆栈/类名候选未采用（避免一次误判摘一堆）");
            return;
        }
        parseStackAttribution(rawLines, meta, issues, attributions);
    }

    /** 某条渠道是否已经定位到了具体模组 */
    private static boolean attributed(Map<String, Diagnosis.Issue> issues, Diagnosis.Kind kind) {
        return issues.values().stream().anyMatch(i -> i.kind() == kind && i.modId() != null);
    }

    /**
     * L4：{@code Empty pre-release} → 反查"谁声明了这个版本号"。
     *
     * <p>只能拿版本号去比，因为加载器抛异常时没带 modId。好在有两条<b>确定性</b>来源：
     * ① {@code latest.log} 里 FML 自己打印的模组清单行（{@code 显示名 版本 (modId)}）；
     * ② mods 目录的"声明版本"索引（{@link ModJarIndex#scanMeta}）。
     * 两者一致 → 高置信；只有一个命中 → 采用并如实记录来源；指向多个/互相矛盾 → 转人工。
     *
     * <p>{@code debug.log} 的 {@code with {modId} mods - versions {ver}} 只做交叉验证加分：
     * 它可被用户关掉或被轮转，不能当唯一依据（用户 2026-09-26 确认的口径）。
     */
    private static void parseVersionFormatFingerprint(List<String> rawLines, GameInstance inst,
                                                     ModJarIndex.Meta meta,
                                                     Map<String, Diagnosis.Issue> issues,
                                                     List<String> attributions) {
        String version = null;
        for (String line : rawLines) {
            Matcher m = EMPTY_PRERELEASE.matcher(line);
            if (m.find()) {
                version = m.group(1).trim();
                break;
            }
            if (line.contains("Empty pre-release")) {
                Matcher loose = EMPTY_PRERELEASE_LOOSE.matcher(line);
                if (loose.find()) {
                    version = loose.group(1).trim();
                    break;
                }
            }
        }
        if (version == null || version.isBlank()) return;

        List<String> byModList = modIdsFromModList(rawLines, version, meta);
        List<String> byDeclared = meta == null ? List.of() : meta.modIdsDeclaring(version);
        List<String> byDebug = modIdsFromDebugLog(inst, version);

        List<String> hits = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        if (!byModList.isEmpty()) {
            hits.addAll(byModList);
            sources.add("latest.log 模组清单");
        }
        if (!byDeclared.isEmpty()) {
            hits.addAll(byDeclared);
            sources.add("jar 声明版本");
        }
        List<String> distinct = hits.stream().distinct().toList();

        String modId = distinct.size() == 1 ? distinct.get(0) : null;
        String why;
        if (modId != null) {
            why = "版本号 " + version + " 唯一指向 " + modId + "（来源：" + String.join(" + ", sources) + "）";
            if (!byDebug.isEmpty() && !byDebug.contains(modId)) {
                why += "；注意 debug.log 指向 " + byDebug + "，但以清单/jar 声明为准";
            }
            String jar = meta == null ? null : meta.jarOf(modId);
            add(issues, new Diagnosis.Issue(Diagnosis.Kind.BAD_VERSION_FORMAT, modId, modId, "-",
                    version, jar, truncate("Empty pre-release: " + why)));
            attributions.add("版本号指纹归因：" + why);
            return;
        }
        if (distinct.isEmpty()) {
            why = "版本号 " + version + " 在 latest.log 模组清单与 jar 声明版本里都找不到归属";
            if (!byDebug.isEmpty()) {
                why += "（debug.log 指向 " + byDebug + "，但按约定它只作交叉验证 → 不据此动作）";
            }
        } else {
            why = "版本号 " + version + " 同时指向多个模组 " + distinct + " → 不猜";
        }
        add(issues, new Diagnosis.Issue(Diagnosis.Kind.BAD_VERSION_FORMAT, null, null, "-",
                version, null, truncate("Empty pre-release: " + why)));
        attributions.add("版本号指纹未能唯一归因：" + why);
    }

    /** 从 latest.log 的模组清单行里找"版本 == version"的 modId（右对齐解析，容忍显示名带空格/颜色码） */
    private static List<String> modIdsFromModList(List<String> lines, String version,
                                                  ModJarIndex.Meta meta) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.contains(version) || !line.contains("(")) continue;
            Matcher m = MOD_LIST_LINE.matcher(line);
            if (!m.matches()) continue;
            if (!m.group(2).trim().equalsIgnoreCase(version)) continue;
            String id = m.group(3).trim();
            // 只认"确实装在 mods 目录里"的模组；索引为空（--diagnose 没给 mods 目录）时只要求名字像 modId
            if (meta != null && !meta.isEmpty()) {
                String canonical = canonical(meta, id);
                if (canonical == null) continue;
                id = canonical;
            } else if (!id.matches("[a-z0-9_.\\-]+")) {
                continue;
            }
            out.add(id);
        }
        return out.stream().distinct().toList();
    }

    /** 交叉验证：debug.log 里的 {@code with {modId} mods - versions {version}} */
    private static List<String> modIdsFromDebugLog(GameInstance inst, String version) {
        if (inst == null) return List.of();
        Path debug = inst.dir().resolve("logs").resolve("debug.log");
        if (!Files.isRegularFile(debug)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<String> s = Files.lines(debug, StandardCharsets.UTF_8)) {
            var it = s.iterator();
            while (it.hasNext()) {
                Matcher m = DEBUG_MOD_VERSION.matcher(it.next());
                while (m.find()) {
                    if (!m.group(2).trim().equalsIgnoreCase(version)) continue;
                    for (String id : m.group(1).split(",")) {
                        String t = id.trim();
                        if (!t.isEmpty()) out.add(t);
                    }
                }
            }
        } catch (IOException ignored) {
            // debug.log 读不了就跳过：它只是加分项
        }
        return out.stream().distinct().toList();
    }

    /** L2：{@code Caused by} 块里的 {@code from mod X}（只采用第一个能对上已安装模组的） */
    private static void parseCausedByAttribution(List<String> lines, ModJarIndex.Meta meta,
                                                Map<String, Diagnosis.Issue> issues,
                                                List<String> attributions) {
        String text = String.join("\n", lines);
        Matcher blocks = Pattern.compile("Caused by:[\\s\\S]*?(?=\\nCaused by:|\\z)",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (blocks.find()) {
            Matcher m = CAUSED_BY_FROM_MOD.matcher(blocks.group());
            if (!m.find()) continue;
            String raw = m.group(1).replaceAll("[\\]\\[,;:]+$", "");
            String id = canonical(meta, raw);
            if (id == null) {
                attributions.add("崩溃链候选 " + raw + "（不在已安装模组里，未采用）");
                continue;
            }
            String jar = meta.jarOf(id);
            add(issues, new Diagnosis.Issue(Diagnosis.Kind.CAUSED_BY_ATTRIBUTED, id, id, "-", "-",
                    jar, truncate("Caused by 链指出 from mod " + raw)));
            attributions.add("崩溃链归因：" + id + "（Caused by 块里的 from mod）");
            return;
        }
    }

    /** L3：堆栈帧 {@code at TRANSFORMER/<modId>@} 与类名（最低置信） */
    private static void parseStackAttribution(List<String> lines, ModJarIndex.Meta meta,
                                             Map<String, Diagnosis.Issue> issues,
                                             List<String> attributions) {
        for (String line : lines) {
            Matcher m = TRANSFORMER_FRAME.matcher(line);
            while (m.find()) {
                String id = canonicalFromFrame(meta, m.group(2));
                if (id == null || isFramework(m.group(2))) continue;
                add(issues, new Diagnosis.Issue(Diagnosis.Kind.STACK_ATTRIBUTED, id, id, "-", "-",
                        meta.jarOf(id), truncate("堆栈帧指出：" + line.strip())));
                attributions.add("栈帧归因：" + id + "（最低置信，每轮最多采纳一个）");
                return;
            }
        }
        // 类名/包名兜底：MethodInvocationException ... in class 'settingdust.preloading_tricks.xxx'
        for (String line : lines) {
            Matcher c = CLASS_NAME.matcher(line);
            while (c.find()) {
                for (String seg : c.group(1).split("\\.")) {
                    if (seg.isBlank() || isFramework(seg)) continue;
                    String id = canonical(meta, seg);
                    if (id == null) continue;
                    add(issues, new Diagnosis.Issue(Diagnosis.Kind.STACK_ATTRIBUTED, id, id, "-", "-",
                            meta.jarOf(id), truncate("类名指出：" + line.strip())));
                    attributions.add("类名归因：" + id + "（最低置信，每轮最多采纳一个）");
                    return;
                }
            }
        }
    }

    /** 记录"低置信候选"（只写归因过程，不产生动作）：L4 已定位时用来说明"为什么没怀疑它" */
    private static void collectLowConfidenceCandidates(List<String> lines, ModJarIndex.Meta meta,
                                                       List<String> attributions, String why) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = TRANSFORMER_FRAME.matcher(line);
            while (m.find()) {
                String id = canonicalFromFrame(meta, m.group(2));
                if (id == null || isFramework(m.group(2))) continue;
                seen.add(id);
            }
            Matcher c = CLASS_NAME.matcher(line);
            while (c.find()) {
                for (String seg : c.group(1).split("\\.")) {
                    String id = canonical(meta, seg);
                    if (id != null && !isFramework(seg)) seen.add(id);
                }
            }
        }
        for (String id : seen) {
            attributions.add("栈帧/类名候选 " + id + "（" + why + "）");
        }
    }

    /** 在索引里大小写不敏感地找 modId；返回索引里的规范写法，找不到返回 null */
    private static String canonical(ModJarIndex.Meta meta, String raw) {
        if (meta == null || raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || meta.jarOf(s) == null) return null;
        for (String id : meta.jarOf().keySet()) {
            if (id.equalsIgnoreCase(s)) return id;
        }
        return s;
    }

    /**
     * 栈帧里的名字通常是"模块名"，而 modId 往往是下划线写法：
     * {@code LAYER SERVICE/preloading.tricks@3.7.2/...} 对应 modId {@code preloading_tricks}。
     * 所以按 原样 → 点换下划线 → 取最后一段 依次试，仍然要求能在索引里对上。
     */
    private static String canonicalFromFrame(ModJarIndex.Meta meta, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = canonical(meta, raw);
        if (id != null) return id;
        id = canonical(meta, raw.replace('.', '_'));
        if (id != null) return id;
        int dot = raw.lastIndexOf('.');
        return dot > 0 ? canonical(meta, raw.substring(dot + 1)) : null;
    }

    /** 框架层名字（minecraft/neoforge/java.* 等）不是模组，任何渠道都不该把它们当肇事者 */
    static boolean isFramework(String name) {
        if (name == null) return true;
        String s = name.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("java.") || s.startsWith("javax.") || s.startsWith("sun.")
                || s.startsWith("com.sun.") || s.startsWith("jdk.")) {
            return true;
        }
        return FRAMEWORK.contains(s) || s.startsWith("org.spongepowered") || s.startsWith("net.minecraft")
                || s.startsWith("net.neoforged") || s.startsWith("net.minecraftforge")
                || s.startsWith("org.apache") || s.startsWith("cpw.mods");
    }
}
