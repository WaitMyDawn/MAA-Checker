package yagen.waitmydawn.checker.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.JavaLocator;
import yagen.waitmydawn.checker.net.Http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 动作 A：就地升级 NeoForge 加载器（改实例版本 JSON，可完整还原）。
 *
 * <p>为什么是"改 patches + 平铺字段"而不是"换个版本目录"：
 * HMCL 的实例 JSON 是 {@code root=true} 的已解析版本，里面既有平铺字段（启动真正用的），
 * 也有 {@code patches[]}（记录来源，HMCL 的"更新加载器"也用这份）。只改一边就会出现
 * "我们启动是新版、玩家用 HMCL 启动还是旧版"（或反过来）。两边都改才自洽。
 *
 * <p>四步（每步都可解释、可回滚）：
 * <ol>
 *   <li>下载官方 installer 并 {@code --install-client} 到游戏根目录，产出
 *       {@code versions/neoforge-<v>/neoforge-<v>.json}（installer 需要 launcher_profiles.json，先备份）；</li>
 *   <li>用产出 JSON 重建 {@code patches[neoforge]}；</li>
 *   <li>平铺字段做"差量"：删掉旧加载器贡献的 jvm/game 参数与库，再把新的加进去
 *       （不做全量重算——全量重算需要 Mojang 原始版本 JSON，而这里没有）；</li>
 *   <li>备份原 JSON 后写回，台账记录备份路径与新旧版本。</li>
 * </ol>
 */
public final class LoaderPatch {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 旧加载器在平铺 jvm 参数里留下的、必须删掉的 token */
    private static final Set<String> LOADER_JVM_PREFIXES = Set.of(
            "-DignoreList=", "-DlibraryDirectory=", "-Djava.net.preferIPv6Addresses=");
    /** 这些是"旗标 + 一个值"的形式，删旗标时要连值一起删 */
    private static final Set<String> LOADER_JVM_FLAGS = Set.of(
            "-p", "--add-modules", "--add-opens", "--add-exports");
    /** 旧加载器在平铺 game 参数里留下的旗标 */
    private static final Set<String> LOADER_GAME_FLAGS = Set.of(
            "--fml.neoForgeVersion", "--fml.fmlVersion", "--fml.mcVersion",
            "--fml.neoFormVersion", "--launchTarget");

    private LoaderPatch() {
    }

    /**
     * @return 台账 data（还原所需的一切）：from/to/jsonBackup/installerJar/producedDir/launcherProfilesBackup
     */
    public static Map<String, String> upgrade(GameInstance inst, String fromVersion, String targetVersion,
                                              JavaLocator.JavaHome java, Path workDir, Path cacheDir,
                                              Path toolRoot,
                                              yagen.waitmydawn.checker.net.NetworkManager nm,
                                              Consumer<String> log) throws Exception {
        Path gameRoot = inst.gameRoot();
        Path instJson = inst.versionJson();
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                Files.readString(instJson, StandardCharsets.UTF_8));
        JsonNode patchesNode = root.path("patches");
        if (!patchesNode.isArray() || patchesNode.isEmpty()) {
            throw new IllegalStateException("实例版本 JSON 里没有 patches[]，不是 HMCL 的 root+patches 结构；"
                    + "为避免改坏，本工具拒绝就地改加载器。");
        }
        ArrayNode patches = (ArrayNode) patchesNode;
        int idx = -1;
        for (int i = 0; i < patches.size(); i++) {
            String id = patches.get(i).path("id").asText("");
            if ("neoforge".equals(id) || "forge".equals(id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalStateException("patches[] 里没找到加载器补丁（neoforge/forge），拒绝改。");
        }
        ObjectNode oldPatch = (ObjectNode) patches.get(idx);

        // ---------- 1) installer ----------
        String installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + targetVersion + "/neoforge-" + targetVersion + "-installer.jar";
        Path installer = cacheDir.resolve("neoforge-" + targetVersion + "-installer.jar");
        if (!Files.isRegularFile(installer)) {
            log.accept("   下载 installer: " + installerUrl);
            // NeoForge 的 installer 走 maven（通常直连可用），所以这里不需要镜像入口；
            // 但仍交给通道管理器，好让"代理/直连"策略统一生效
            yagen.waitmydawn.checker.net.Http.DownloadOutcome o = nm != null
                    ? nm.download(installerUrl, null, 0, installer)
                    : Http.download(installerUrl, installer, log, 0);
            if (!o.ok()) throw new IOException("下载 installer 失败：" + o.detail());
            long size = o.bytes();
            log.accept("   installer 已下载 " + (size / 1024 / 1024) + " MB");
        } else {
            log.accept("   复用已缓存的 installer: " + installer.getFileName());
        }

        // ---------- 1b) 装到游戏目录，但把"我们造出来的东西"精确记下来，装完即清 ----------
        //
        // 这里刻意**不再使用沙盒 + junction**（2026-09-22 事故复盘）：
        // 曾经的做法是"installer 指向软件目录下的沙盒，沙盒里 libraries 用 junction 指回真实目录"，
        // 虽然能实现"游戏目录零残留"，但它引入了一个致命风险——
        // **任何递归删除（Remove-Item -Recurse / rd /s）都会穿透 junction，把真实 libraries 删空**，
        // 实测已经发生过一次（用户清理沙盒时整个 libraries 被清空，所有实例都起不来）。
        // 现在改为：installer 照常对游戏目录跑（libraries 必须落在真实目录，这是运行时必需品），
        // 但我们**精确记录**这次新造出的 versions 目录与 launcher_profiles.json 的变化，装完立刻清理。
        String mc = inst.mcVersion();
        Path versionsRoot = gameRoot.resolve("versions");
        Path producedDir = versionsRoot.resolve("neoforge-" + targetVersion);
        Path vanillaDir = versionsRoot.resolve(mc);
        boolean producedExisted = Files.isDirectory(producedDir);
        boolean vanillaExisted = Files.isDirectory(vanillaDir);

        Path launcherProfiles = gameRoot.resolve("launcher_profiles.json");
        Path lpBackup = null;
        boolean lpCreated = false;
        if (Files.isRegularFile(launcherProfiles)) {
            lpBackup = cacheDir.resolve("launcher_profiles.json.bak");
            Files.copy(launcherProfiles, lpBackup, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(launcherProfiles, "{\"profiles\":{}}", StandardCharsets.UTF_8);
            lpCreated = true;
            log.accept("   已补建 launcher_profiles.json（installer 的前置要求，结束时会还原/删除）");
        }

        // 原版客户端 jar：实例 jar 就是原版客户端 jar（sha1 与 JSON 声明一致，已实测），
        // 提前放到 versions/<mc>/<mc>.jar 能省掉 installer 那 25MB 下载
        boolean providedClientJar = false;
        String clientSha1 = root.path("downloads").path("client").path("sha1").asText("");
        String clientJarName = root.path("jar").asText("");
        if (!Files.isDirectory(vanillaDir)) Files.createDirectories(vanillaDir);
        if (!clientSha1.isBlank() && !clientJarName.isBlank()) {
            Path instJar = inst.dir().resolve(clientJarName + ".jar");
            Path targetJar = vanillaDir.resolve(mc + ".jar");
            if (Files.isRegularFile(instJar) && !Files.isRegularFile(targetJar)
                    && clientSha1.equalsIgnoreCase(sha1(instJar))) {
                if (linkFile(targetJar, instJar)) {
                    providedClientJar = true;
                    log.accept("   原版客户端 jar 已硬链接到 versions/" + mc + "（省 25MB 下载）");
                } else {
                    Files.copy(instJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
                    providedClientJar = true;
                    log.accept("   原版客户端 jar 已复制到 versions/" + mc + "（跨卷无法硬链接）");
                }
            }
        }
        Path installerLog = workDir.resolve("installer-" + targetVersion + ".log");
        log.accept("   运行 installer --install-client（可能下载若干库，日志见 "
                + installerLog.getFileName() + "）");
        // installer 自己也是 Java 程序，它要下库：同样钉 IPv4，并且把用户的代理透传给它
        List<String> installerCmd = new ArrayList<>(List.of(java.javaExe().toString(),
                "-Djava.net.preferIPv4Stack=true"));
        // 用网络管理器当前配置的代理（而不是曾经的全局值）：installer 也要能借到代理下库
        String proxy = nm != null ? nm.proxy() : Http.proxy();
        if (proxy != null) {
            try {
                // 注意：本方法有个形参就叫 java（JavaLocator.JavaHome），所以绝不能写 java.net.URI，
                // 全限定名会被当成"变量 java 的 net 字段"。用 import 后的简单名。
                URI u = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
                installerCmd.add("-Dhttp.proxyHost=" + u.getHost());
                installerCmd.add("-Dhttp.proxyPort=" + (u.getPort() > 0 ? u.getPort() : 8080));
                installerCmd.add("-Dhttps.proxyHost=" + u.getHost());
                installerCmd.add("-Dhttps.proxyPort=" + (u.getPort() > 0 ? u.getPort() : 8080));
                log.accept("   installer 将走代理 " + proxy);
            } catch (Exception e) {
                log.accept("   ⚠️ 代理地址解析失败，installer 按直连跑: " + proxy);
            }
        }
        installerCmd.add("-jar");
        installerCmd.add(installer.toString());
        installerCmd.add("--install-client");
        installerCmd.add(gameRoot.toString());
        ProcessBuilder pb = new ProcessBuilder(installerCmd);
        pb.directory(gameRoot.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(installerLog.toFile());
        Process p = pb.start();
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new IllegalStateException("installer 超过 15 分钟未结束，已中止；见 " + installerLog);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("installer 退出码 " + p.exitValue() + "，见 " + installerLog);
        }

        // ---------- 2) 用产出 JSON 重建 patch ----------
        Path produced = producedDir.resolve("neoforge-" + targetVersion + ".json");
        if (!Files.isRegularFile(produced)) {
            throw new IllegalStateException("installer 没有产出预期文件: " + produced);
        }
        ObjectNode nf = (ObjectNode) MAPPER.readTree(Files.readString(produced, StandardCharsets.UTF_8));
        JsonNode oldPatchCopy = oldPatch.deepCopy();
        ObjectNode newPatch = MAPPER.createObjectNode();
        newPatch.put("id", oldPatch.path("id").asText("neoforge"));
        newPatch.put("version", targetVersion);
        newPatch.put("priority", oldPatch.path("priority").asInt(30000));
        for (String f : List.of("mainClass", "inheritsFrom", "type", "time", "releaseTime", "arguments",
                "libraries")) {
            if (nf.has(f)) newPatch.set(f, nf.get(f));
        }
        patches.set(idx, newPatch);

        // ---------- 3) 平铺字段做差量 ----------
        ObjectNode args = (ObjectNode) root.path("arguments");
        List<String> newJvm = textual(args, nf, "jvm");
        List<String> newGame = textual(args, nf, "game");
        if (newJvm.isEmpty() && newGame.isEmpty()) {
            throw new IllegalStateException("installer 产出的 JSON 里没有可用的 arguments，拒绝写坏实例 JSON");
        }
        int removedJvm = dropTokens((ArrayNode) args.path("jvm"), LOADER_JVM_PREFIXES, LOADER_JVM_FLAGS);
        int removedGame = dropTokens((ArrayNode) args.path("game"), Set.of(), LOADER_GAME_FLAGS);
        ArrayNode jvm = (ArrayNode) args.path("jvm");
        newJvm.forEach(jvm::add);
        ArrayNode game = (ArrayNode) args.path("game");
        newGame.forEach(game::add);

        int removedLibs = dropLibraries((ArrayNode) root.path("libraries"),
                oldPatchCopy.path("libraries"));
        ArrayNode libs = (ArrayNode) root.path("libraries");
        Set<String> have = new LinkedHashSet<>();
        for (JsonNode l : libs) have.add(l.path("name").asText());
        int addedLibs = 0;
        for (JsonNode l : newPatch.path("libraries")) {
            if (have.add(l.path("name").asText())) {
                libs.add(l);
                addedLibs++;
            }
        }
        String mainClass = newPatch.path("mainClass").asText("");
        if (!mainClass.isBlank()) root.put("mainClass", mainClass);

        // ---------- 4) 备份后写回 ----------
        String backupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now());
        Path backup = instJson.resolveSibling(instJson.getFileName() + ".maa-backup-" + backupStamp);
        Files.copy(instJson, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(instJson, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        log.accept("   已就地更新实例 JSON：删除旧加载器参数 jvm=" + removedJvm + "/game=" + removedGame
                + "，日志库 " + removedLibs + " 个，新增 " + addedLibs + " 个；备份 "
                + backup.getFileName());

        // ---------- 5) 清理"我们这次新造出来的东西"（只有走到这里才算成功）----------
        //
        // 只删两种情况：① 这次才出现的 versions/<mc>；② 这次才出现的 versions/neoforge-<ver>。
        // 本来就在的目录一律不动（别的实例可能在用）。删之前再做一次"越界检查"：
        // 解析后的真实路径必须仍在游戏目录内，防止任何链接把删除引到别处去。
        List<String> cleaned = new ArrayList<>();
        if (!vanillaExisted && Files.isDirectory(vanillaDir) && insideGameRoot(vanillaDir, gameRoot)) {
            deleteTreeSkippingLinks(vanillaDir);
            cleaned.add("versions/" + mc);
        }
        if (!producedExisted && Files.isDirectory(producedDir) && insideGameRoot(producedDir, gameRoot)) {
            deleteTreeSkippingLinks(producedDir);
            cleaned.add("versions/neoforge-" + targetVersion);
        }
        if (!cleaned.isEmpty()) {
            log.accept("   已清理 installer 的临时产物：" + String.join("、", cleaned)
                    + "（libraries 里的库是运行时必需品，保留）");
        }
        // launcher_profiles.json：本来有就还原，本来没有就删掉（避免启动器列表里多出 NeoForge 条目）
        if (lpBackup != null && Files.isRegularFile(lpBackup)) {
            Files.copy(lpBackup, launcherProfiles, StandardCopyOption.REPLACE_EXISTING);
            log.accept("   已还原 launcher_profiles.json（去掉 installer 注入的 profile）");
        } else if (lpCreated) {
            Files.deleteIfExists(launcherProfiles);
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("from", fromVersion);
        data.put("to", targetVersion);
        data.put("jsonBackup", backup.toString());
        data.put("installerJar", installer.toString());
        data.put("installerLog", installerLog.toString());
        data.put("providedClientJar", String.valueOf(providedClientJar));
        data.put("cleanedPaths", String.join("、", cleaned));
        data.put("vanillaExistedBefore", String.valueOf(vanillaExisted));
        data.put("producedExistedBefore", String.valueOf(producedExisted));
        return data;
    }

    /** 越界检查：路径解析后的真实位置必须仍在游戏目录内（防止链接把删除引到别处） */
    static boolean insideGameRoot(Path path, Path gameRoot) {
        try {
            Path real = path.toRealPath();
            Path root = gameRoot.toRealPath();
            return real.startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }

    /** 取产出 JSON 里 arguments.<group> 的纯字符串参数（rules 对象一律不搬：那是启动器能力，不是加载器要求） */
    private static List<String> textual(ObjectNode targetArgs, ObjectNode nf, String group) {
        List<String> out = new ArrayList<>();
        for (JsonNode a : nf.path("arguments").path(group)) {
            if (a.isTextual()) {
                for (String t : a.asText().split("\\s+")) {
                    if (!t.isBlank()) out.add(t);
                }
            }
        }
        return out;
    }

    /** 删掉旧加载器留下的 token；返回删除个数（旗标会连它后面的值一起删） */
    private static int dropTokens(ArrayNode array, Set<String> prefixes, Set<String> flags) {
        if (array == null || !array.isArray()) return 0;
        List<JsonNode> keep = new ArrayList<>();
        int removed = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonNode e = array.get(i);
            String s = e.isTextual() ? e.asText() : "";
            boolean drop = prefixes.stream().anyMatch(s::startsWith) || flags.contains(s);
            if (drop) {
                removed++;
                if (flags.contains(s) && i + 1 < array.size()) {
                    i++;                    // 旗标后面的值（路径/模块名）跟着一起删
                    removed++;
                }
                continue;
            }
            keep.add(e);
        }
        array.removeAll();
        keep.forEach(array::add);
        return removed;
    }

    private static int dropLibraries(ArrayNode libs, JsonNode oldPatchLibraries) {
        if (libs == null || !libs.isArray()) return 0;
        Set<String> oldNames = new LinkedHashSet<>();
        for (JsonNode l : oldPatchLibraries) oldNames.add(l.path("name").asText());
        List<JsonNode> keep = new ArrayList<>();
        int removed = 0;
        for (JsonNode l : libs) {
            if (oldNames.contains(l.path("name").asText())) {
                removed++;
                continue;
            }
            keep.add(l);
        }
        libs.removeAll();
        keep.forEach(libs::add);
        return removed;
    }

    // ----------------------------------------------------------------------------------
    // 沙盒用到的 Windows 文件系统技巧（结论都是实测出来的，改之前先看注释）
    // ----------------------------------------------------------------------------------

    /**
     * 建目录 <b>junction</b>（{@code mklink /J}）：让 installer 以为自己在一个完整的启动器目录里，
     * 而 {@code libraries/} 实际指向游戏目录 —— 库是运行时必需品、必须落在真实目录，
     * 同时避免 installer 把上百 MB 的库再下一遍。
     *
     * <p>实测：{@code mklink /J} <b>不需要管理员权限</b>；失败（权限策略 / 非 NTFS）返回 false，
     * 调用方降级为"让 installer 自己下库"。
     */
    static boolean linkDirectory(Path link, Path target, Consumer<String> log) {
        try {
            if (!Files.isDirectory(target)) return false;
            Files.createDirectories(link.getParent());
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            boolean ok = p.exitValue() == 0 && Files.isDirectory(link);
            if (!ok && log != null) {
                log.accept("   ⚠️ mklink /J 失败（" + out.strip() + "），libraries 不共享");
            }
            return ok;
        } catch (Exception e) {
            if (log != null) log.accept("   ⚠️ 建立目录链接异常：" + e.getMessage());
            return false;
        }
    }

    /**
     * 建文件 <b>硬链接</b>（{@code mklink /H}）：把实例 jar 当作原版客户端 jar 交给 installer
     * （实测两者 sha1 完全一致，能过 installer 的校验），省掉 25MB 下载且不额外占空间。
     * 硬链接要求同卷；跨卷返回 false，由调用方改为复制。
     */
    static boolean linkFile(Path link, Path target) {
        try {
            if (!Files.isRegularFile(target)) return false;
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/H",
                    link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            return p.exitValue() == 0 && Files.isRegularFile(link);
        } catch (Exception e) {
            return false;
        }
    }

    /** 文件 sha1（用于核对"实例 jar 是否就是原版客户端 jar"） */
    static String sha1(Path p) {
        try (var in = Files.newInputStream(p);
             var din = new java.security.DigestInputStream(in,
                     java.security.MessageDigest.getInstance("SHA-1"))) {
            din.transferTo(java.io.OutputStream.nullOutputStream());
            return java.util.HexFormat.of().formatHex(din.getMessageDigest().digest());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 清理沙盒：<b>先用 {@code rd}（不带 /s）删掉 junction</b>，再删剩余内容。
     *
     * <p>这是全项目最危险的一处，务必保持这个顺序：实测 {@code rd <junction>} 只删链接、
     * 真实 libraries 里的文件原样保留；而 {@code Remove-Item -Recurse} / {@code rd /s}
     * 会<b>穿透 junction</b> 把真实 libraries 删掉。
     * {@link #deleteTreeSkippingLinks} 里再跳过"目录链接"，双保险。
     *
     * @return true = 沙盒已清干净
     */
    static boolean cleanupSandbox(Path sandbox, Path junction, Consumer<String> log) {
        try {
            // 不依赖"链接探测"：实测 JDK 无法可靠识别 Windows junction
            // （Files.isSymbolicLink 返回 false；带 NOFOLLOW_LINKS 的 isDirectory 也返回 true）。
            // 我们知道 junction 的确切路径，直接对它执行 rd（不带 /s）——它只删链接、不动目标内容。
            if (junction != null && Files.exists(junction)) {
                runRd(junction);
                if (Files.exists(junction)) {
                    if (log != null) {
                        log.accept("   ⚠️ libraries 链接未能删除，已放弃清理沙盒"
                                + "（宁可留垃圾也不能误删真实 libraries）: " + junction);
                    }
                    return false;
                }
            }
            deleteTreeSkippingLinks(sandbox);
            return !Files.exists(sandbox);
        } catch (Exception e) {
            if (log != null) {
                log.accept("   ⚠️ 沙盒清理失败：" + e.getMessage() + "（可手动删除 " + sandbox + "）");
            }
            return false;
        }
    }

    /**
     * 自底向上删除沙盒内容。
     *
     * <p>安全策略：只删"自己能删掉的东西"。遇到删不掉的目录（可能是链接/被占用），用
     * {@code rd}（<b>不带 /s</b>）再试一次——它只删链接本身；仍然删不掉就<b>跳过并留下</b>，
     * 绝不改成递归删除（那会穿透 junction 删到真实 libraries）。
     */
    static void deleteTreeSkippingLinks(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Path root = dir.toRealPath();
        List<Path> paths = new ArrayList<>();
        try (var s = Files.walk(dir)) {
            s.forEach(paths::add);
        }
        paths.sort(java.util.Comparator.reverseOrder());
        for (Path p : paths) {
            // ★ 越界防护（2026-09-22 事故后加的）：解析后的真实位置必须仍在删除根之内。
            // junction/symlink 指向外部时 toRealPath() 会落到外面 → 直接跳过，绝不删。
            try {
                if (!p.toRealPath().startsWith(root)) continue;
            } catch (IOException e) {
                continue;
            }
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                runRd(p);      // 链接/被占用：rd 只删链接本身，不穿透
            }
        }
    }

    /** 执行 {@code rd <路径>}（不带 /s）：删除目录/junction 链接本身，绝不递归穿透 */
    static void runRd(Path path) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "rd", path.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception ignored) {
            // 删不掉就留着，由调用方报告残留
        }
    }

    /**
     * 尽力判断"是否是目录链接"——<b>只用于日志/提示，不能作为删除决策依据</b>。
     *
     * <p>实测坑：JDK 里 {@code Files.isSymbolicLink} 对 Windows junction 返回 false，
     * 而带 {@code NOFOLLOW_LINKS} 的 {@code isDirectory} 也返回 true，
     * 所以下面这个组合在 junction 上同样会返回 false（保留它是为了符号链接场景 + 可读性）。
     */
    static boolean isDirectoryLink(Path p) {
        try {
            return Files.isDirectory(p)
                    && !Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (Exception e) {
            return false;
        }
    }
}
