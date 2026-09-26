import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * MAA-Checker PoC：**静态依赖预检**。
 *
 * 目的：证明"面对一堆现成 jar 文件也能拿到依赖图"——不需要启动游戏、不需要崩溃报告。
 * 数据来源：每个 mod jar 里的 META-INF/neoforge.mods.toml（NeoForge）/ META-INF/mods.toml（旧 Forge）。
 *   [[mods]]                      → modId / version（谁是什么版本）
 *   [[dependencies.<modid>]]      → modId / type(required|optional|incompatible) / versionRange（谁要求谁）
 *
 * 输出：① 已装模组清单；② **未满足的 required 依赖**（缺失 / 版本不在区间）；③ 反向依赖（影响面）；④ 叶子候选（没人依赖 → 优先摘）。
 *
 * 用法：java StaticDepsProbe.java "D:/Minecraft/minecraft/.minecraft/versions/铁魔法冒险之旅"
 */
public class StaticDepsProbe {

    record Dep(String requester, String target, String type, String range) {}
    record Mod(String id, String version, String jar) {}

    public static void main(String[] args) throws Exception {
        Path inst = Paths.get(args[0]);
        Path mods = inst.resolve("mods");
        // 载具版本（loader 自身）从版本 JSON 里取，用于校验 "requires neoforge >= x"
        String loader = "neoforge";
        String loaderVersion = detectLoaderVersion(inst);
        String mcVersion = detectMcVersion(inst);

        List<Mod> installed = new ArrayList<>();
        List<Dep> deps = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Path jar : listJars(mods)) {
            String toml = readEntry(jar, "META-INF/neoforge.mods.toml");
            if (toml == null) toml = readEntry(jar, "META-INF/mods.toml");
            if (toml == null) { unreadable.add(jar.getFileName().toString()); continue; }
            List<Mod> modsInJar = parseMods(toml, jar.getFileName().toString());
            installed.addAll(modsInJar);
            for (Mod m : modsInJar) deps.addAll(parseDeps(toml, m.id()));
        }

        Map<String, Mod> byId = new LinkedHashMap<>();
        for (Mod m : installed) byId.put(m.id(), m);
        if (loaderVersion != null) byId.put(loader, new Mod(loader, loaderVersion, "<loader>"));
        // 环境"模组"要预置：minecraft / loader 不在 mods/ 里，不预置就会产生一堆"缺 minecraft"的误报
        if (mcVersion != null) byId.put("minecraft", new Mod("minecraft", mcVersion, "<game>"));

        System.out.println("=== 静态预检: " + inst.getFileName() + " ===");
        System.out.println("扫描 jar: " + listJars(mods).size() + " 个；解析到 mod: " + installed.size()
                + " 个；含 mods.toml 的非模组 jar: " + unreadable.size());
        System.out.println("环境: minecraft " + mcVersion + " / " + loader + " " + loaderVersion);

        // 未满足的 required 依赖
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> dependentsOf = new LinkedHashMap<>();   // target -> requesters（影响面）
        for (Dep d : deps) {
            if (!"required".equals(d.type())) continue;
            dependentsOf.computeIfAbsent(d.target(), k -> new ArrayList<>()).add(d.requester());
            Mod have = byId.get(d.target());
            if (have == null) {
                problems.add(String.format("缺前置: %s 需要 %s %s（未安装）", d.requester(), d.target(), d.range()));
            } else if (!satisfies(have.version(), d.range())) {
                problems.add(String.format("版本不满足: %s 需要 %s %s，当前 %s",
                        d.requester(), d.target(), d.range(), have.version()));
            }
        }
        System.out.println("\n--- 未满足的必需依赖（" + problems.size() + " 条，全部无需启动游戏即可得出）---");
        problems.forEach(p -> System.out.println("  " + p));

        // 影响面：谁依赖它（摘除它会有连带损失）
        System.out.println("\n--- 影响面（被依赖最多的模组 Top 8）---");
        dependentsOf.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(8)
                .forEach(e -> System.out.println("  " + e.getKey() + " ← " + e.getValue().size() + " 个依赖者 "
                        + e.getValue()));

        // 叶子候选：没人依赖它 → 摘它不连累别人（优先摘除对象）
        List<String> leaves = installed.stream().map(Mod::id)
                .filter(id -> !dependentsOf.containsKey(id))
                .sorted().toList();
        System.out.println("\n--- 叶子候选（没人依赖，摘除不连累他人）共 " + leaves.size() + " 个 ---");
        System.out.println("  " + leaves);
    }

    static String detectLoaderVersion(Path inst) {
        try {
            for (Path p : Files.list(inst).filter(x -> x.toString().endsWith(".json")).toList()) {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("--fml\\.neoForgeVersion[\"\\s,]+([0-9][0-9.]*)").matcher(s);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 从版本 JSON 的启动参数里取 mc 版本（--fml.mcVersion 或 --version） */
    static String detectMcVersion(Path inst) {
        try {
            for (Path p : Files.list(inst).filter(x -> x.toString().endsWith(".json")).toList()) {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("--fml\\.mcVersion[\"\\s,]+([0-9][0-9a-zA-Z._]*)").matcher(s);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) { }
        return null;
    }

    static List<Path> listJars(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
    }

    /** 从 jar 里读指定条目为文本（不存在返回 null） */
    static String readEntry(Path jar, String entry) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** 解析 [[mods]] 块：modId + version（一个 jar 可能声明多个 mod） */
    static List<Mod> parseMods(String toml, String jarName) {
        List<Mod> out = new ArrayList<>();
        Matcher block = Pattern.compile("(?s)\\[\\[mods]](.*?)(?=\\[\\[|\\z)").matcher(toml);
        while (block.find()) {
            String b = block.group(1);
            String id = first(b, "modId");
            String ver = first(b, "version");
            if (id != null) out.add(new Mod(id, ver == null ? "?" : ver, jarName));
        }
        return out;
    }

    /** 解析 [[dependencies.<owner>]] 块 */
    static List<Dep> parseDeps(String toml, String owner) {
        List<Dep> out = new ArrayList<>();
        // 注意：一个 mod 会有多个 [[dependencies.<owner>]] 块（jei 就同时声明 neoforge 与 minecraft），
        // 必须逐块精确切分——用"下一个 [[ 之前"的非贪婪匹配，别再叠否定前瞻。
        Matcher block = Pattern.compile("(?s)\\[\\[dependencies\\.[^]]+]]\\s*(.*?)(?=\\s*\\[\\[|\\z)").matcher(toml);
        while (block.find()) {
            String b = block.group(1);
            String id = first(b, "modId");
            String type = first(b, "type");
            String range = first(b, "versionRange");
            String ordering = first(b, "ordering");
            if (id == null) continue;
            // ${file.jarVersion} / ${minecraft_version_range} 之类的占位符：PoC 不静默误判，标记为需人工
            String resolved = range == null ? "*" : range;
            if (resolved.contains("${")) resolved = "*";
            out.add(new Dep(owner, id, type == null ? "optional" : type, resolved));
        }
        return out;
    }

    static String first(String block, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]*)\"").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 版本区间判定（PoC 级实现，产品要换成带单测的比较器）：
     * 支持 [a,b) / [a,) / (,b] / [a] / * ；版本按"非字母数字切分出的数字序列"比较。
     */
    static boolean satisfies(String version, String range) {
        if (range == null || range.isBlank() || "*".equals(range)) return true;
        // NeoForge 的区间可能用逗号或裸 "," 分隔，且可能有多个区间（这里只处理单个区间，够 PoC 用）
        Matcher m = Pattern.compile("([\\[(])\\s*([^,\\]\\)]*)\\s*,\\s*([^\\[\\]\\)]*)\\s*[\\])]").matcher(range);
        if (m.find()) {
            String low = m.group(2).trim(), high = m.group(3).trim();
            boolean incLow = "[".equals(m.group(1)), incHigh = range.trim().endsWith("]");
            if (!low.isEmpty()) {
                int c = compare(version, low);
                if (c < 0 || (c == 0 && !incLow)) return false;
            }
            if (!high.isEmpty()) {
                int c = compare(version, high);
                if (c > 0 || (c == 0 && !incHigh)) return false;
            }
            return true;
        }
        Matcher single = Pattern.compile("^\\[([^,\\[\\]]+)]$").matcher(range.trim());
        if (single.find()) return compare(version, single.group(1).trim()) == 0;
        return true; // 解析不了就放行（PoC 保守策略）
    }

    static int compare(String a, String b) {
        List<Integer> x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int xi = i < x.size() ? x.get(i) : 0;
            int yi = i < y.size() ? y.get(i) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    static List<Integer> nums(String v) {
        List<Integer> out = new ArrayList<>();
        for (String p : v.split("[^0-9]+")) if (!p.isEmpty()) out.add(Integer.parseInt(p));
        return out;
    }
}
