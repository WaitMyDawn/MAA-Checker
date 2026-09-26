package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 自研最小启动器：把"已安装好的实例"离线启动起来，并盯日志给出客观结论。
 *
 * <p>为什么自己做而不用 HMCL/PCL2：
 * 两个启动器都没有稳定的命令行接口（HMCL 3.16 的 CLI 只覆盖导出/脚本，不能"启动某个实例并告诉我结果"），
 * 而实例版本 JSON 本身就是一份完整的启动模板（classpath / jvm 参数 / 游戏参数 / 占位符），
 * 自己渲染反而更可控、更可解释、也更容易出结论。
 *
 * <p>三个实测校准过的关键点（PoC 阶段踩出来的，改动前请先看这里）：
 * <ol>
 *   <li><b>成功判据必须用后期行</b>：{@code Setting user:} 只出现在 20s 左右，那时客户端还在构造 mod，
 *       拿它当成功会假 PASS。可靠信号是声音子系统/图集创建这类后期行。</li>
 *   <li><b>检测到失败标志后要再等 2.5s</b>：{@code Missing or unsupported mandatory dependencies:}
 *       之后的 Mod ID 明细块是同一秒内继续写出的，立刻抓只会拿到表头。</li>
 *   <li><b>区分新旧日志要用"内容锚点"</b>：Windows 上浮点 birthtimeMs 会亚毫秒抖动，
 *       用文件时间判断会把上一轮日志当成新内容 → 改为启动前记住旧日志末尾 120 字符，只认锚点之后的新增。</li>
 * </ol>
 */
public final class InstanceLauncher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 进度标记（早期行）：只说明"它在动"，不能当成功 */
    public static final List<String> PROGRESS_MARKERS = List.of(
            "Setting user:", "Backend library:", "LWJGL Version");

    /** 成功标记（后期行）：1.21.1 NeoForge 实测进主菜单的可靠信号 */
    public static final List<String> SUCCESS_MARKERS = List.of(
            "Sound engine started",
            "OpenAL initialized",
            "Created: 1024x512x4 minecraft:textures/atlas/blocks.png");

    /** 失败标记：模组加载失败/启动期崩溃的确定性信号（按 1.21.1 NeoForge 实测日志校准） */
    public static final List<String> FAILURE_MARKERS = List.of(
            "Missing or unsupported mandatory dependencies",
            "Mod loading failures have occurred",
            "ModLoadingCrashException",
            "Mod loading issue for:",
            "Loading errors encountered",
            "A mod crashed on startup",
            "A mod crashed",
            "Mixin apply failed",
            "Incompatible mods found",
            "Crash report saved to",
            "Failed to start the minecraft server",
            "ModLoadingException",
            "Exception in thread \"main\"");

    /** 锚点长度：旧日志末尾保留多少字符用于定位"本次新增" */
    private static final int ANCHOR_CHARS = 120;
    /** 命中失败标志后等待明细块写完的时间（实测）。注意这是按日志行为定死的，不是"感觉" */
    private static final long FAIL_BLOCK_SETTLE_MS = 2500L;
    private static final long POLL_MS = 500L;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    private InstanceLauncher() {
    }

    /** 该实例需要什么 Java：以版本 JSON 自己声明的为准，缺字段才回退到按 MC 版本推断 */
    public static int requiredJavaOf(GameInstance inst) {
        try {
            JsonNode v = MAPPER.readTree(Files.readString(inst.versionJson(), StandardCharsets.UTF_8));
            int m = v.path("javaVersion").path("majorVersion").asInt(0);
            if (m > 0) return m;
        } catch (Exception ignored) {
            // 读不出来就走经验值，不静默当 0
        }
        return heuristicJava(inst.mcVersion());
    }

    /** 经验值（仅在没有 javaVersion 字段时使用；与启动器行为一致：1.21/1.20.5+ 要 21，1.18~1.20.4 要 17） */
    public static int heuristicJava(String mcVersion) {
        if (mcVersion == null) return 17;
        if (mcVersion.startsWith("1.21") || mcVersion.startsWith("1.20.5")
                || mcVersion.startsWith("1.20.6")) return 21;
        if (mcVersion.startsWith("1.18") || mcVersion.startsWith("1.19")
                || mcVersion.startsWith("1.20")) return 17;
        return 8;
    }

    // ==================================================================================
    // 一、组装启动命令
    // ==================================================================================

    /**
     * 按实例的版本 JSON 渲染出完整启动命令。
     *
     * @throws IOException            版本 JSON 读不出来
     * @throws IllegalStateException  缺少必要前提（natives 目录、客户端 jar、Java）
     */
    public static LaunchSpec build(GameInstance inst, JavaLocator.JavaHome java, Path stdoutLog,
                                   String mx, int width, int height) throws IOException {
        if (java == null) throw new IllegalStateException("未找到满足版本要求的 Java");
        JsonNode v = MAPPER.readTree(Files.readString(inst.versionJson(), StandardCharsets.UTF_8));
        List<String> notes = new ArrayList<>();

        String mainClass = v.path("mainClass").asText("");
        if (mainClass.isBlank()) throw new IllegalStateException("版本 JSON 里没有 mainClass");

        String clientJarName = v.path("jar").asText("");
        if (clientJarName.isBlank()) {
            String f = inst.versionJson().getFileName().toString();
            clientJarName = f.substring(0, f.length() - ".json".length());
        }
        Path clientJar = inst.dir().resolve(clientJarName + ".jar");
        if (!Files.isRegularFile(clientJar)) {
            throw new IllegalStateException("客户端 jar 不存在: " + clientJar
                    + "（请先用启动器把这个实例完整装好）");
        }

        Path natives = findNativesDir(inst.dir());
        if (natives == null) {
            throw new IllegalStateException("实例目录里没有 natives-* 目录: " + inst.dir()
                    + "（请先用启动器启动过一次，让它把 natives 解压出来）");
        }

        Path gameRoot = inst.gameRoot();
        Path libRoot = gameRoot.resolve("libraries");
        String sep = System.getProperty("path.separator", ";");
        List<String> cpEntries = new ArrayList<>();
        int missingLibs = 0;
        int skippedOtherOs = 0;
        for (JsonNode lib : v.path("libraries")) {
            // 库条目也有 rules：不筛的话会把 linux/macos 的 natives 也算进 classpath，
            // 于是"27 个库文件缺失"这种噪音警报就出来了（实测：那些文件启动器根本不会下载）
            if (!rulesAllow(lib.path("rules"), osName(), defaultFeatures())) {
                skippedOtherOs++;
                continue;
            }
            String rel = lib.path("downloads").path("artifact").path("path").asText("");
            if (rel.isBlank()) {
                // HMCL 合并过的 JSON 里可能只有 name，没有 downloads（极少见）：只提示，不静默跳过
                String name = lib.path("name").asText("");
                if (!name.isBlank()) notes.add("库条目缺少 downloads.artifact.path，已忽略: " + name);
                continue;
            }
            Path p = libRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                missingLibs++;
                continue;
            }
            cpEntries.add(p.toString());
        }
        if (missingLibs > 0) {
            notes.add("有 " + missingLibs + " 个库文件在 " + libRoot + " 里缺失（classpath 已跳过）");
        }
        if (skippedOtherOs > 0) {
            notes.add("已按平台规则跳过 " + skippedOtherOs + " 个非本平台库条目（其它系统的 natives，属正常）");
        }
        cpEntries.add(clientJar.toString());
        String classpath = String.join(sep, cpEntries);

        // 离线凭据：优先复用 usercache.json 里的玩家名与 uuid，保持与实例一致；否则造一个
        String playerName = "MaaTester";
        String playerUuid = "00000000000040008000000000000000";
        Path cache = inst.dir().resolve("usercache.json");
        if (Files.isRegularFile(cache)) {
            try {
                JsonNode arr = MAPPER.readTree(Files.readString(cache, StandardCharsets.UTF_8));
                if (arr.isArray() && arr.size() > 0 && arr.get(0).hasNonNull("name")) {
                    playerName = arr.get(0).path("name").asText(playerName);
                    playerUuid = arr.get(0).path("uuid").asText(playerUuid).replace("-", "");
                }
            } catch (Exception e) {
                notes.add("usercache.json 解析失败，已用默认离线身份: " + e.getClass().getSimpleName());
            }
        } else {
            notes.add("实例内没有 usercache.json，使用默认离线身份 " + playerName);
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", "MAA-Checker");
        vars.put("launcher_version", "0.1");
        vars.put("classpath", classpath);
        vars.put("classpath_separator", sep);
        vars.put("library_directory", libRoot.toString());
        vars.put("version_name", v.path("id").asText(inst.name()));
        vars.put("primary_jar_name", clientJarName + ".jar");
        vars.put("game_directory", inst.dir().toString());
        vars.put("assets_root", gameRoot.resolve("assets").toString());
        vars.put("assets_index_name", v.path("assetIndex").path("id").asText("legacy"));
        vars.put("auth_player_name", playerName);
        vars.put("auth_uuid", playerUuid);
        vars.put("auth_access_token", "0");        // 离线：令牌固定 0，且日志里会被脱敏
        vars.put("auth_session", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("version_type", v.path("type").asText("release"));
        vars.put("resolution_width", String.valueOf(width));
        vars.put("resolution_height", String.valueOf(height));

        JsonNode arguments = v.path("arguments");
        List<String> jvmArgs = renderArgs(arguments.path("jvm"), vars, notes, "jvm");
        List<String> gameArgs;
        if (arguments.path("game").isArray()) {
            gameArgs = renderArgs(arguments.path("game"), vars, notes, "game");
        } else {
            // 老版本格式（1.13 以前的 minecraftArguments 一行字符串）兜底
            String legacy = v.path("minecraftArguments").asText("");
            gameArgs = legacy.isBlank() ? List.of()
                    : renderArgs(MAPPER.createArrayNode().add(legacy), vars, notes, "game");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(java.javaExe().toString());
        cmd.add("-Xmx" + mx);
        cmd.add("-Xms1G");
        cmd.addAll(jvmArgs);
        cmd.add(mainClass);
        cmd.addAll(gameArgs);

        int requiredJava = v.path("javaVersion").path("majorVersion").asInt(java.major());
        return new LaunchSpec(List.copyOf(cmd), desensitize(cmd), inst.dir(),
                inst.dir().resolve("logs").resolve("latest.log"), natives, stdoutLog,
                stdoutLog.getParent(),
                requiredJava, List.copyOf(notes));
    }

    /**
     * 渲染参数数组：字符串直接替换占位符；带 rules 的对象按 OS 与 features 判定。
     *
     * <p>features 全部由我们（启动方）决定，等价于"没有正版账号、没有 quickPlay、有自定义分辨率"：
     * 这样 {@code --demo} 与四个 quickPlay 参数会被正确跳过。
     * （PoC 这一步是错的：直接 String(object) 变成 "[object Object]" 六个垃圾参数，游戏能忽略但它们毫无意义。）
     */
    public static List<String> renderArgs(JsonNode args, Map<String, String> vars,
                                          List<String> notes, String label) {
        return renderArgs(args, vars, notes, label, osName(), defaultFeatures());
    }

    /**
     * 启动方能提供的能力：没有正版账号、没有 quickPlay、分辨率由我们指定。
     * 版本 JSON 里的 {@code features} 规则就是按这张表取舍的。
     */
    static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", true);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);
        return features;
    }

    static List<String> renderArgs(JsonNode args, Map<String, String> vars, List<String> notes,
                                   String label, String osName, Map<String, Boolean> features) {
        List<String> out = new ArrayList<>();
        if (args == null || !args.isArray()) return out;
        for (JsonNode e : args) {
            if (e.isTextual()) {
                String s = substitute(e.asText(), vars);
                if (!s.isBlank()) out.add(s);
                continue;
            }
            if (!e.isObject()) continue;
            if (!rulesAllow(e.path("rules"), osName, features)) continue;
            for (JsonNode val : e.path("value")) {
                String s = substitute(val.asText(), vars);
                if (!s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    /** 与官方启动器一致的 rules 语义：逐条匹配，最后一条匹配的 action 说了算；没有规则=允许 */
    static boolean rulesAllow(JsonNode rules, String osName, Map<String, Boolean> features) {
        if (rules == null || !rules.isArray() || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonNode r : rules) {
            JsonNode os = r.path("os");
            if (os.isObject() && os.hasNonNull("name")
                    && !"universal".equals(os.path("name").asText())
                    && !osName.equals(os.path("name").asText())) {
                continue;
            }
            JsonNode feat = r.path("features");
            if (feat.isObject()) {
                boolean all = true;
                for (var it = feat.fields(); it.hasNext(); ) {
                    var en = it.next();
                    boolean want = en.getValue().asBoolean();
                    boolean have = features.getOrDefault(en.getKey(), false);
                    if (want != have) {
                        all = false;
                        break;
                    }
                }
                if (!all) continue;
            }
            allowed = "allow".equals(r.path("action").asText());
        }
        return allowed;
    }

    static String substitute(String s, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 命令脱敏：只处理 accessToken/session 的值，其余原样 */
    static String desensitize(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd.size());
        boolean maskNext = false;
        for (String c : cmd) {
            if (maskNext) {
                out.add("<token>");
                maskNext = false;
                continue;
            }
            out.add(c);
            if ("--accessToken".equals(c) || "--clientId".equals(c)
                    || "--session".equals(c) || "--auth_xuid".equals(c)) {
                maskNext = true;
            }
        }
        return String.join(" ", out);
    }

    /** 实例里已解压的 natives 目录：名字不固定（natives-windows-x86_64 / natives-windows），按前缀找 */
    public static Path findNativesDir(Path instDir) {
        try (Stream<Path> s = Files.list(instDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("natives-"))
                    .sorted()
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    // ==================================================================================
    // 二、启动 + 盯日志判结论
    // ==================================================================================

    /**
     * 启动并观察，直到得出结论；无论结论如何都会把本次新增的日志切片落到 {@code runDir/fresh-latest.log}。
     *
     * @param timeoutSeconds 整体超时（超时判 TIMEOUT）
     * @param graceSeconds   命中成功标记后继续观察的秒数（防"刚进主菜单就崩"）
     */
    public static LaunchResult run(LaunchSpec spec, int timeoutSeconds, int graceSeconds,
                                   Consumer<String> log) throws IOException, InterruptedException {
        Files.createDirectories(spec.stdoutLog().getParent());
        Anchor anchor = captureAnchor(spec.latestLog());
        if (anchor.usable()) {
            log.accept("（启动前 latest.log 已有 " + anchor.offset()
                    + " 字符，只认该偏移之后的新内容）");
        }
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(spec.command())
                .directory(spec.workDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(spec.stdoutLog().toFile())
                .start();
        log.accept("进程已启动 pid=" + p.pid() + "（-Xmx" + spec.command().get(1).substring(4)
                + "），监视 " + spec.latestLog());

        long deadline = t0 + timeoutSeconds * 1000L;
        long passSeenAt = -1L;
        String evidence = null;
        LaunchVerdict verdict = null;
        List<String> timeline = new ArrayList<>();
        Set<String> seenProgress = new HashSet<>();

        while (verdict == null) {
            Thread.sleep(POLL_MS);
            String text = readText(spec.latestLog());
            if (text != null) {
                String fresh = sliceAfterAnchor(text, anchor);
                for (String m : PROGRESS_MARKERS) {
                    if (!seenProgress.contains(m) && fresh.contains(m)) {
                        seenProgress.add(m);
                        String line = secs(t0) + "s: " + m;
                        timeline.add(line);
                        log.accept("   …进度 " + line);
                    }
                }
                for (String m : FAILURE_MARKERS) {
                    if (fresh.contains(m)) {
                        evidence = m;
                        log.accept("❌ 命中崩溃判据 \"" + m + "\"（启动后 " + secs(t0)
                                + "s），等 " + (FAIL_BLOCK_SETTLE_MS / 1000) + "s 抓完整明细块…");
                        // 明细块（Mod ID 列表）是同一秒内继续写出的，立刻抓只会拿到表头
                        Thread.sleep(FAIL_BLOCK_SETTLE_MS);
                        verdict = LaunchVerdict.FAIL;
                        break;
                    }
                }
                if (verdict == null && passSeenAt < 0) {
                    for (String m : SUCCESS_MARKERS) {
                        if (fresh.contains(m)) {
                            passSeenAt = System.currentTimeMillis();
                            evidence = m;
                            log.accept("✅ 命中主菜单判据 \"" + m + "\"（启动后 " + secs(t0)
                                    + "s），再观察 " + graceSeconds + "s 确认不会刚进界面就崩…");
                            break;
                        }
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (verdict == null && passSeenAt > 0 && now - passSeenAt >= graceSeconds * 1000L) {
                verdict = LaunchVerdict.PASS;
            }
            if (verdict == null && !p.isAlive()) {
                int code = p.exitValue();
                evidence = "进程退出 code=" + code;
                verdict = code != 0 ? LaunchVerdict.FAIL : LaunchVerdict.EXITED;
                log.accept("⚠️ 游戏进程已自行退出 code=" + code);
            }
            if (verdict == null && now > deadline) {
                evidence = timeoutSeconds + "s 内未命中任何结论性标记";
                verdict = LaunchVerdict.TIMEOUT;
                log.accept("⏱️ 超时：" + evidence);
            }
        }

        // 结论落地前再等一会儿，保证明细块/崩溃报告写盘完成
        Thread.sleep(800L);
        Path freshLog = spec.runDir() == null ? null : spec.runDir().resolve("fresh-latest.log");
        if (freshLog != null) {
            String text = readText(spec.latestLog());
            String fresh = sliceAfterAnchor(text, anchor);
            Files.createDirectories(freshLog.getParent());
            // 只写日志本身、不加任何头注释：诊断器要按行匹配，掺进自己的注释会污染证据
            Files.writeString(freshLog, fresh, StandardCharsets.UTF_8);
            log.accept("本次新增日志切片 " + fresh.length() + " 字符（" + anchorVerdict(text, anchor)
                    + "）");
        }

        boolean killed = false;
        if (p.isAlive()) {
            killTree(p);
            killed = true;
            log.accept("已结束游戏进程 pid=" + p.pid() + "（连同子进程）");
        }
        int exitCode = p.isAlive() ? Integer.MIN_VALUE : p.exitValue();
        return new LaunchResult(verdict, evidence, System.currentTimeMillis() - t0, exitCode,
                killed, spec.runDir(), spec.stdoutLog(), freshLog, List.copyOf(timeline));
    }

    /** 结束进程树：先子后父，避免 Minecraft 的子进程（如原生库加载器）变孤儿 */
    public static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 日志锚点 = 启动前的<b>字符长度</b> + 文件<b>开头</b>的指纹。
     *
     * <p>这是踩了两次坑之后定下来的方案，改之前请先看这两次：
     * <ol>
     *   <li>只用"末尾 120 字符"做内容锚点：两次启动的启动期日志几乎逐字相同，旧日志的末尾在新日志里原样出现，
     *       {@code lastIndexOf} 切出空串 → 诊断变成"没有故障"，修复闭环静默空转；</li>
     *   <li>改用"长度 + 末尾指纹"：这个包每次都在同一步崩溃，新日志长度恰好与旧日志相同、末尾行也相同，
     *       于是又被判成"没新增"。</li>
     * </ol>
     * 结论：判断"新旧"要看<b>开头</b>——日志第一行带时间戳，同一次启动的头永远不可能与上一次相同。
     * 头一致 ⇒ 是同一次进程在追加（从旧长度处切）；头不一致 ⇒ 日志被轮转/重建（整份都是新的）。
     */
    record Anchor(long offset, String head) {
        boolean usable() {
            return offset > 0 && head != null && !head.isEmpty();
        }
    }

    /** 开头指纹长度：足够覆盖带时间戳的第一行 */
    private static final int HEAD_CHARS = 200;

    /** 启动前抓锚点：记录当前文件长度与末尾指纹 */
    static Anchor captureAnchor(Path latestLog) {
        String text = readText(latestLog);
        if (text == null || text.length() <= ANCHOR_CHARS) {
            return new Anchor(0L, null);            // 没有旧日志：整份都算新的
        }
        // 注意：这里必须是"字符数"而不是 Files.size() 的字节数——日志里有中文路径（实例名），
        // 一个汉字 3 字节，两者会差出几百，偏移一错锚点就永远对不上（实测踩过）。
        return new Anchor(text.length(), text.substring(0, Math.min(HEAD_CHARS, text.length())));
    }

    /**
     * 只取本次新增的内容：
     * 文件头与启动前一致（同一次进程继续追加）→ 从旧长度处切；
     * 否则（日志被轮转/重建，或有任何不确定）→ 整份都当新的（宁可多看旧内容，也不能漏掉本次故障）。
     */
    static String sliceAfterAnchor(String text, Anchor a) {
        if (text == null) return "";
        if (!isContinuation(text, a)) return text;
        return text.substring((int) a.offset());
    }

    /** 是否"同一个日志文件在被继续写"（决定能否用偏移切片） */
    static boolean isContinuation(String text, Anchor a) {
        return a.usable() && text.length() >= a.offset() && text.startsWith(a.head());
    }

    /** 供日志展示的判定说明 */
    static String anchorVerdict(String text, Anchor a) {
        if (!a.usable()) return "启动前没有可用旧日志 → 整份都算本次新增";
        if (isContinuation(text, a)) return "同一日志文件追加 → 只取第 " + a.offset() + " 字符之后";
        return "日志被轮转/重建（头部指纹不一致）→ 整份都算本次新增";
    }

    private static String readText(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String secs(long t0) {
        return String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 1000.0);
    }
}
