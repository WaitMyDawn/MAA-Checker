package yagen.waitmydawn.checker.action;

import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.JavaLocator;
import yagen.waitmydawn.checker.core.ModJarIndex;
import yagen.waitmydawn.checker.net.ModrinthClient;
import yagen.waitmydawn.checker.net.NeoForgeVersions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 动作执行器：真的动文件的那一层，每一步都写台账。
 *
 * <p>三条不可退让的纪律：
 * <ol>
 *   <li><b>摘除不是删除</b>：移动到 {@code <软件目录>/bin/<实例>/round-<N>/}，还原时移回原位；</li>
 *   <li><b>补装要留证据</b>：记录 Modrinth 项目 slug / 版本号 / 下载地址 / 哈希，还原=删掉这个文件；</li>
 *   <li><b>补不到就改走摘除</b>：前置在 Modrinth 上找不到时，按"先补装、补不到才级联"的顺序，
 *       立刻把需要它的那个模组摘掉（而不是留一个已知必崩的包）。</li>
 *   <li><b>装之前先问 jar"你是谁的"</b>：下载后核对 jar 内声明的 modId，对不上就换下一个候选；
 *       所有候选都对不上（同名模组）时既不安装也<b>不摘除</b>，停下来等人工确认——
 *       猜错的代价比多问一句大得多。</li>
 * </ol>
 */
public final class ActionExecutor {

    /**
     * @param failures       执行失败的动作描述（调用方据此决定停止循环，而不是假装成功继续）
     * @param networkFailure 出现网络级失败（Modrinth/CDN 不可达）。这类失败<b>绝不能</b>当成
     *                       "补不到前置"去摘模组——那会因为在咖啡厅没连上网就毁掉玩家的整合包
     */
    public record Result(int upgraded, int installed, int removed, List<String> failures,
                         boolean networkFailure) {
        public int changed() {
            return upgraded + installed + removed;
        }
    }

    private ActionExecutor() {
    }

    public static Result execute(Path toolRoot, GameInstance inst, ActionPlan plan, int round,
                                 JavaLocator.JavaHome java, Ledger ledger, Tombstones tombs,
                                 yagen.waitmydawn.checker.core.StaticDepsScanner.Report stat,
                                 Map<String, String> jarIndex, int cascadeLimit, Path workDir,
                                 yagen.waitmydawn.checker.net.NetworkManager nm,
                                 Consumer<String> log) throws yagen.waitmydawn.checker.net
                                         .NetworkManager.StopRequested {
        List<PlannedAction> queue = new ArrayList<>(plan.actions());
        List<String> failures = new ArrayList<>();
        int upgraded = 0;
        int installed = 0;
        int removed = 0;
        boolean networkFailure = false;

        for (int i = 0; i < queue.size(); i++) {   // 队列会在"补装失败→改摘除"时增长
            PlannedAction a = queue.get(i);
            try {
                switch (a.kind()) {
                    case UPGRADE_LOADER -> {
                        String target = NeoForgeVersions.latestSatisfying(
                                inst.mcVersion(), a.target(), log);
                        if (target == null) {
                            failures.add("找不到满足 >= " + a.target() + " 的 NeoForge 正式版，加载器未升级");
                            ledger.add("UPGRADE_LOADER", a.modId(), null, a.category(), a.reason(),
                                    a.errorText(), Map.of("result", "NOT_FOUND",
                                            "requiredMin", String.valueOf(a.target())));
                            break;
                        }
                        Map<String, String> data = LoaderPatch.upgrade(inst, a.modId() + " "
                                + loaderVersionOf(inst), target, java, workDir,
                                toolRoot.resolve("state").resolve(safe(inst.name())).resolve("cache"),
                                toolRoot, nm, log);
                        ledger.add("UPGRADE_LOADER", a.modId(), null, a.category(), a.reason(),
                                a.errorText(), data);
                        upgraded++;
                    }
                    case INSTALL_DEP -> {
                        if (nm != null && nm.removeOnly()) {
                            log.accept("   按【只做摘除并继续】执行：不再补装 " + a.modId()
                                    + "，直接摘掉依赖它的 " + a.target());
                            ActionPlanner.Fallback fb = ActionPlanner.fallbackRemoval(stat, jarIndex,
                                    a.target(), "用户选择只做摘除（本次检验不补装），"
                                            + a.target() + " 缺少 " + a.modId() + " 无法运行",
                                    a.errorText(), cascadeLimit);
                            queue.addAll(fb.actions());
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；用户选择只做摘除，改为摘除依赖方",
                                    a.errorText(), Map.of("result", "SKIPPED_REMOVE_ONLY"));
                            break;
                        }
                        ModrinthClient.Resolution res;
                        try {
                            res = ModrinthClient.resolve(a.modId(), inst.mcVersion(), inst.loader(),
                                    a.versionRange(), nm, log);
                        } catch (yagen.waitmydawn.checker.net.NetworkManager.StopRequested stop) {
                            throw stop;
                        } catch (yagen.waitmydawn.checker.net.NetworkManager.RemoveOnlyRequested ro) {
                            ActionPlanner.Fallback fb = ActionPlanner.fallbackRemoval(stat, jarIndex,
                                    a.target(), "用户选择只做摘除，不再补装 " + a.modId(),
                                    a.errorText(), cascadeLimit);
                            queue.addAll(fb.actions());
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；用户选择只做摘除，改为摘除依赖方",
                                    a.errorText(), Map.of("result", "SKIPPED_REMOVE_ONLY"));
                            break;
                        } catch (IOException net) {
                            networkFailure = true;
                            failures.add("Modrinth 查询失败（网络）: " + a.modId() + " — "
                                    + net.getMessage());
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；查询失败（网络不可达，未做任何删除）",
                                    a.errorText(), Map.of("result", "NETWORK_ERROR",
                                            "error", String.valueOf(net.getMessage())));
                            log.accept("   ✗ 网络不可达，无法查询 Modrinth：" + net.getMessage()
                                    + "（不会把它当成'补不到'去删模组）");
                            break;
                        }
                        if (!res.found()) {
                            // 确证"Modrinth 上没有这个前置在当前环境下的版本"（候选都被闸门排除）→ 才走摘除
                            log.accept("   ✗ " + a.modId() + " 在 Modrinth 上找不到匹配 "
                                    + inst.loader() + "/" + inst.mcVersion()
                                    + " 的可用版本 → 按规则改为摘除依赖方 " + a.target());
                            res.rejected().forEach(r -> log.accept("       淘汰依据: " + r));
                            res.sameName().forEach(s -> log.accept("       同名候选（名字对不上，未采用）: " + s));
                            Map<String, String> miss = new LinkedHashMap<>();
                            miss.put("result", "NOT_FOUND");
                            if (!res.rejected().isEmpty()) {
                                miss.put("rejected", String.join(" | ", res.rejected()));
                            }
                            if (!res.sameName().isEmpty()) {
                                miss.put("sameNameCandidates", String.join(" | ", res.sameName()));
                            }
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；补装失败：Modrinth 无匹配版本",
                                    a.errorText(), miss);
                            ActionPlanner.Fallback fb = ActionPlanner.fallbackRemoval(stat, jarIndex,
                                    a.target(), "前置 " + a.modId()
                                            + " 补装失败（Modrinth 无可用版本），因此只能摘掉需要它的 "
                                            + a.target(), a.errorText(), cascadeLimit);
                            if (fb.actions().isEmpty()) {
                                failures.add("既装不了 " + a.modId() + "，也摘不掉 " + a.target()
                                        + (fb.blocked() == null ? "" : "（" + fb.blocked() + "）"));
                            } else {
                                queue.addAll(fb.actions());
                            }
                            break;
                        }

                        // 候选逐个试：下载 → 用 jar 内真实 modId 核对 → 对得上才装进 mods。
                        // Modrinth 的项目/版本 JSON 里没有 modId 字段，同名模组（modId 都叫 relics）
                        // 只能靠这一步兜住：装之前先问 jar"你到底是谁"。
                        ModrinthClient.Candidate chosen = null;
                        Path cacheFile = null;
                        List<String> mismatches = new ArrayList<>();
                        try {
                            for (ModrinthClient.Candidate cand : res.candidates()) {
                                cacheFile = cacheJar(toolRoot, inst, cand, nm, log);
                                Set<String> ids = ModJarIndex.modIdsOf(cacheFile);
                                if (declares(ids, a.modId())) {
                                    chosen = cand;
                                    break;
                                }
                                String what = ids.isEmpty() ? "jar 里读不到 mods.toml/fabric.mod.json"
                                        : "jar 声明的 modId 是 " + String.join(", ", ids);
                                log.accept("   ⚠️ " + cand.slug() + " 的 " + cand.fileName()
                                        + " 不是 \"" + a.modId() + "\"（" + what + "）→ 换下一个候选");
                                mismatches.add(cand.slug() + " " + cand.versionNumber() + "：" + what);
                                cacheFile = null;
                            }
                        } catch (yagen.waitmydawn.checker.net.NetworkManager.StopRequested stop) {
                            throw stop;
                        } catch (yagen.waitmydawn.checker.net.NetworkManager.RemoveOnlyRequested ro) {
                            ActionPlanner.Fallback fb = ActionPlanner.fallbackRemoval(stat, jarIndex,
                                    a.target(), "用户选择只做摘除，放弃补装 " + a.modId(),
                                    a.errorText(), cascadeLimit);
                            queue.addAll(fb.actions());
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；用户选择只做摘除，改为摘除依赖方",
                                    a.errorText(), Map.of("result", "SKIPPED_REMOVE_ONLY"));
                            break;
                        } catch (Exception net) {
                            networkFailure = true;
                            failures.add(a.modId() + " 的候选文件下载失败（网络）: " + net.getMessage());
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；下载失败（网络不可达，未做任何删除）",
                                    a.errorText(), Map.of("result", "DOWNLOAD_FAILED",
                                            "error", String.valueOf(net.getMessage())));
                            log.accept("   ✗ 下载失败（网络）：" + net.getMessage());
                            break;
                        }
                        if (chosen == null) {
                            // 有候选，但下载下来的 jar 里都不是这个 modId：说明撞名了。
                            // 工具不替用户猜是哪一个 → 不安装，也【不摘除依赖方】，留给人工确认。
                            log.accept("   ✗ 有候选，但下载后没有一个 jar 声明 modId=\"" + a.modId()
                                    + "\" → 不安装、也不摘除 " + a.target() + "（需人工确认同名模组）");
                            mismatches.forEach(m -> log.accept("       " + m));
                            ledger.add("INSTALL_DEP", a.modId(), null, a.category(),
                                    a.reason() + "；候选 jar 的 modId 都对不上，未安装也未摘除"
                                            + "（同名模组，需人工确认）",
                                    a.errorText(), Map.of("result", "MODID_MISMATCH",
                                            "tried", String.join(" | ", mismatches)));
                            failures.add("前置 " + a.modId() + " 找到同名候选但 jar 内 modId 对不上，"
                                    + "已停下等你确认（" + a.target() + " 保持原样）");
                            break;
                        }
                        Path installedFile;
                        try {
                            Files.createDirectories(inst.modsDir());
                            installedFile = inst.modsDir().resolve(chosen.fileName());
                            Files.copy(cacheFile, installedFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception fsErr) {
                            failures.add("补装 " + chosen.fileName() + " 落盘失败: " + fsErr.getMessage());
                            ledger.add("INSTALL_DEP", a.modId(), chosen.fileName(), a.category(),
                                    a.reason() + "；落盘失败（mods 目录不可写？）", a.errorText(),
                                    Map.of("result", "COPY_FAILED",
                                            "error", String.valueOf(fsErr.getMessage())));
                            log.accept("   ✗ 落盘失败：" + fsErr.getMessage());
                            break;
                        }
                        Map<String, String> data = new LinkedHashMap<>();
                        data.put("installedPath", installedFile.toString());
                        data.put("fileName", installedFile.getFileName().toString());
                        data.put("url", chosen.url());
                        data.put("projectSlug", chosen.slug());
                        data.put("versionNumber", chosen.versionNumber());
                        data.put("versionId", chosen.versionId());
                        data.put("size", String.valueOf(Files.size(installedFile)));
                        data.put("sha256", sha256(installedFile));
                        data.put("verifiedModIds", String.join(", ", ModJarIndex.modIdsOf(installedFile)));
                        ledger.add("INSTALL_DEP", a.modId(), installedFile.getFileName().toString(),
                                a.category(),
                                a.reason() + "；已从 Modrinth 补装 " + chosen.slug() + " "
                                        + chosen.versionNumber() + "（jar 内 modId 已核对）",
                                a.errorText(), data);
                        installed++;
                        log.accept("   ✓ 已补装 " + installedFile.getFileName() + "（" + chosen.slug()
                                + " " + chosen.versionNumber() + "，modId 已核对）");
                    }
                    case REMOVE_MOD -> {
                        Path moved = removeJar(toolRoot, inst, a.jarFile(), round, log);
                        if (moved == null) {
                            failures.add("摘除 " + a.modId() + " 失败：mods 目录里找不到其 jar");
                            ledger.add("REMOVE_MOD", a.modId(), a.jarFile(), a.category(),
                                    a.reason() + "；执行失败：找不到 jar", a.errorText(),
                                    Map.of("result", "FILE_NOT_FOUND"));
                            break;
                        }
                        Map<String, String> data = new LinkedHashMap<>();
                        data.put("movedTo", moved.toString());
                        data.put("sha256", sha256(moved));
                        data.put("size", String.valueOf(Files.size(moved)));
                        if (a.cascadeOf() != null) data.put("cascadeOf", a.cascadeOf());
                        ledger.add("REMOVE_MOD", a.modId(), a.jarFile(), a.category(), a.reason(),
                                a.errorText(), data);
                        tombs.add(a.modId(), a.jarFile(), a.reason(), round);
                        removed++;
                        // 用户要求：日志里必须写清"哪一类问题 + 删了谁 + 报错原文"
                        log.accept("   ✓ 已摘除 [" + a.category() + "] " + a.modId()
                                + "（" + a.jarFile() + "）→ bin/" + safe(inst.name())
                                + "/round-" + round + "/");
                        log.accept("     原因: " + a.reason());
                        a.errorText().forEach(t -> log.accept("     报错原文: " + t));
                    }
                }
            } catch (yagen.waitmydawn.checker.net.NetworkManager.StopRequested stop) {
                throw stop;      // 用户点了"停止检验并还原"，不能被下面的兜底 catch 吞掉
            } catch (Exception e) {
                failures.add(a.kind() + " " + a.modId() + " 执行异常: " + e.getMessage());
                log.accept("   ✗ " + a.kind() + " " + a.modId() + " 失败: " + e.getMessage());
            }
        }
        return new Result(upgraded, installed, removed, failures, networkFailure);
    }

    // ------------------------------------------------------------------ 文件操作

    /** 摘除=移动。目标目录 bin/<实例>/round-N/，同名冲突加时间戳后缀，绝不覆盖 */
    static Path removeJar(Path toolRoot, GameInstance inst, String jarFile, int round,
                          Consumer<String> log) throws IOException {
        if (jarFile == null) return null;
        Path src = inst.modsDir().resolve(jarFile);
        if (!Files.isRegularFile(src)) return null;
        Path destDir = toolRoot.resolve("bin").resolve(safe(inst.name())).resolve("round-" + round);
        Files.createDirectories(destDir);
        Path dest = destDir.resolve(jarFile);
        if (Files.exists(dest)) {
            dest = destDir.resolve(jarFile + ".dup-" + System.currentTimeMillis());
            log.accept("   ⚠️ bin 目录里已有同名文件，本次落盘为 " + dest.getFileName());
        }
        Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    /**
     * 补装第一步：下到 {@code state/<实例>/cache/}（已缓存则零网络，重复检验不必再下）。
     *
     * <p>刻意"先下到缓存、再复制进 mods"两步走：这样在写进 mods 之前还能开箱核对 jar 里的 modId，
     * 发现装错了就直接换下一个候选，mods 目录不会被污染。
     */
    static Path cacheJar(Path toolRoot, GameInstance inst, ModrinthClient.Candidate c,
                         yagen.waitmydawn.checker.net.NetworkManager nm, Consumer<String> log)
            throws IOException {
        Path cache = toolRoot.resolve("state").resolve(safe(inst.name())).resolve("cache")
                .resolve(c.fileName());
        if (!Files.isRegularFile(cache) || Files.size(cache) < 1024) {
            log.accept("   下载 " + c.fileName() + "（" + (c.size() / 1024)
                    + " KB）来自 Modrinth 项目 " + c.slug() + " " + c.versionNumber());
            if (nm != null) {
                // 官方地址 + 镜像文件入口 + 期望大小（用于总时长兜底）一起交给通道管理器
                yagen.waitmydawn.checker.net.Http.DownloadOutcome o =
                        nm.download(c.url(), c.mirrorUrl(), c.size(), cache);
                if (!o.ok()) {
                    throw new IOException("下载失败：" + o.detail());
                }
            } else {
                yagen.waitmydawn.checker.net.Http.DownloadOutcome o =
                        yagen.waitmydawn.checker.net.Http.download(c.url(), cache, log, c.size());
                if (!o.ok()) throw new IOException("下载失败：" + o.detail());
            }
        }
        return cache;
    }

    /**
     * jar 里是否真的声明了这个 modId（大小写不敏感）。
     *
     * <p>为什么必须有这一步：Modrinth 的接口只给 slug/title，modId 只写在 jar 内部的
     * {@code mods.toml} / {@code fabric.mod.json} 里。同名模组很常见（modId 都能叫 relics），
     * 不核对就可能把别的模组装进包里。
     */
    static boolean declares(Set<String> declaredIds, String modId) {
        for (String id : declaredIds) {
            if (id.equalsIgnoreCase(modId)) return true;
        }
        return false;
    }

    public static String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p);
             DigestInputStream din = new DigestInputStream(in, MessageDigest.getInstance("SHA-256"))) {
            din.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(din.getMessageDigest().digest());
        } catch (Exception e) {
            return "";
        }
    }

    static String safe(String name) {
        return (name == null || name.isBlank() ? "unknown" : name)
                .replaceAll("[\\\\/:*?\"<>|]", "_").strip();
    }

    private static String loaderVersionOf(GameInstance inst) {
        return inst.loaderVersion() == null ? "?" : inst.loaderVersion();
    }
}
