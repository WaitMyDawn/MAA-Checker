package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 静态依赖预检：不启动游戏，直接从 mods/*.jar 里读出依赖图。
 *
 * 数据来源：META-INF/neoforge.mods.toml（NeoForge）/ META-INF/mods.toml（旧 Forge）。
 * 关键点（实测踩过的坑）：
 * - 必须递归进嵌套 jar（META-INF/jarjar/*.jar、META-INF/jars/*.jar）：
 *   create 的 flywheel/ponder、quark 的 biolith 都在嵌套里，不扫就会误报"缺前置"。
 * - ${...} 占位符无法静态求值，标记为 placeholder 并单独上报，不静默当满足。
 * - 结论分两档：高置信（未安装的前置 / 加载器版本不足）用于决策；
 *   其余（minecraft 区间、其它模组版本区间）只进"需人工"清单。
 */
public final class StaticDepsScanner {

    /** 高置信问题：可用于自动决策 */
    public record Problem(String kind, String requester, String target, String range, String actual, String jar) {
        public String describe() {
            return switch (kind) {
                case "MISSING" -> String.format("缺前置: %s 需要 %s %s（未安装）", requester, target, range);
                case "LOADER_TOO_OLD" -> String.format("加载器版本不足: %s 需要 %s %s，当前 %s",
                        requester, target, range, actual);
                default -> String.format("%s: %s -> %s %s（当前 %s）", kind, requester, target, range, actual);
            };
        }
    }

    public record Report(int jarCount, int modCount, int nestedJarCount,
                         List<Problem> highConfidence, List<Problem> needsReview,
                         Map<String, List<String>> dependentsOf,
                         List<String> leaves, String mcVersion, String loader, String loaderVersion) {
    }

    private StaticDepsScanner() {
    }

    public static Report scan(GameInstance inst) throws IOException {
        Map<String, String> installed = new LinkedHashMap<>();
        List<StaticDepsScanner.Problem> high = new ArrayList<>();
        List<StaticDepsScanner.Problem> review = new ArrayList<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Set<String> jarModIds = new LinkedHashSet<>();
        int[] counters = new int[2];   // 0: jar 数, 1: 嵌套 jar 数

        if (inst.modsDir() != null && Files.isDirectory(inst.modsDir())) {
            for (Path jar : listJars(inst.modsDir())) {
                counters[0]++;
                scanJar(jar, installed, jarModIds, counters, 0);
            }
        }
        // 环境"模组"预置：minecraft 与加载器不在 mods/ 里
        installed.put("minecraft", inst.mcVersion());
        if (inst.loaderVersion() != null) installed.put(inst.loader(), inst.loaderVersion());

        // 第二遍：所有依赖声明（依赖也必须包含嵌套 jar 里声明的）
        List<ModToml.DepDecl> allDeps = new ArrayList<>();
        Map<String, String> jarOf = new LinkedHashMap<>();
        collectDeps(inst.modsDir(), allDeps, jarOf);
        for (ModToml.DepDecl d : allDeps) {
            if (!"required".equals(d.type())) continue;
            dependents.computeIfAbsent(d.target(), k -> new ArrayList<>()).add(d.requester());
            String actual = installed.get(d.target());
            String jar = jarOf.getOrDefault(d.requester(), "?");
            if (actual == null) {
                high.add(new Problem("MISSING", d.requester(), d.target(), d.versionRange(), "-", jar));
            } else if (!VersionRanges.satisfies(actual, d.versionRange())) {
                boolean isLoader = d.target().equals(inst.loader()) || "minecraft".equals(d.target());
                Problem p = isLoader && !"minecraft".equals(d.target())
                        ? new Problem("LOADER_TOO_OLD", d.requester(), d.target(), d.versionRange(), actual, jar)
                        : new Problem("VERSION_MISMATCH", d.requester(), d.target(), d.versionRange(), actual, jar);
                if ("LOADER_TOO_OLD".equals(p.kind()) && "neoforge".equals(d.target())) high.add(p);
                else review.add(p);
            } else if (d.placeholder()) {
                review.add(new Problem("PLACEHOLDER", d.requester(), d.target(), "?", actual, jar));
            }
        }
        List<String> leaves = jarModIds.stream().filter(id -> !dependents.containsKey(id)).sorted().toList();
        return new Report(counters[0], installed.size() - (inst.loaderVersion() != null ? 2 : 1),
                counters[1], high, review, dependents, leaves,
                inst.mcVersion(), inst.loader(), inst.loaderVersion());
    }

    /** 读一个 jar（含嵌套）：收集它声明的 modId 与依赖 */
    private static void scanJar(Path jar, Map<String, String> installed, Set<String> jarModIds,
                                int[] counters, int depth) {
        if (depth > 3) return;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            // 三载体一起读（toml 两种写法 + fabric/quilt 元数据）。
            // 实测代价：只读 toml 的表头写法时，用内联数组写 mods 的 jar（如 panda-temple-V1-1.21+.jar）
            // 会被当成"没装"，于是依赖它的模组被误判成"缺前置"。
            for (JarModReader.Mod m : JarModReader.read(zf)) {
                installed.putIfAbsent(m.modId(), m.version());
                jarModIds.add(m.modId());
            }
            for (ZipEntry e : zf.stream().toList()) {
                String n = e.getName();
                if (!n.endsWith(".jar")) continue;
                if (!(n.startsWith("META-INF/jarjar/") || n.startsWith("META-INF/jars/"))) continue;
                counters[1]++;
                Path tmp = Files.createTempFile("maa-checker-nested", ".jar");
                try (InputStream in = zf.getInputStream(e)) {
                    Files.write(tmp, in.readAllBytes());
                }
                scanJar(tmp, installed, jarModIds, counters, depth + 1);
                Files.deleteIfExists(tmp);
            }
        } catch (Exception ignored) {
            // 单jar解析失败不影响整体预检
        }
    }

    private static void collectDeps(Path modsDir, List<ModToml.DepDecl> out, Map<String, String> jarOf) {
        if (modsDir == null || !Files.isDirectory(modsDir)) return;
        try {
            for (Path jar : listJars(modsDir)) collectDepsFromJar(jar, out, jarOf, 0);
        } catch (IOException ignored) {
        }
    }

    private static void collectDepsFromJar(Path jar, List<ModToml.DepDecl> out,
                                           Map<String, String> jarOf, int depth) {
        if (depth > 3) return;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            String toml = readEntry(zf, "META-INF/neoforge.mods.toml");
            if (toml == null) toml = readEntry(zf, "META-INF/mods.toml");
            if (toml != null) {
                for (ModToml.DepDecl d : ModToml.deps(toml)) {
                    out.add(d);
                    jarOf.putIfAbsent(d.requester(), jar.getFileName().toString());
                }
            }
            for (ZipEntry e : zf.stream().toList()) {
                String n = e.getName();
                if (!n.endsWith(".jar")) continue;
                if (!(n.startsWith("META-INF/jarjar/") || n.startsWith("META-INF/jars/"))) continue;
                Path tmp = Files.createTempFile("maa-checker-nested", ".jar");
                try (InputStream in = zf.getInputStream(e)) {
                    Files.write(tmp, in.readAllBytes());
                }
                collectDepsFromJar(tmp, out, jarOf, depth + 1);
                Files.deleteIfExists(tmp);
            }
        } catch (Exception ignored) {
        }
    }

    private static String readEntry(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e1) {
            return null;
        }
    }

    private static List<Path> listJars(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
    }
}
