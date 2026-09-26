package yagen.waitmydawn.checker.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 NeoForge/Forge 的 mods.toml（手写解析器，只覆盖加载器实际用到的子集）。
 *
 * <p><b>两种写法都必须认</b>——实测踩过：只认第一种会整个模组读不到：
 * <pre>
 *   ① 表头写法（大多数模组）
 *      [[mods]]
 *      modId = "x"
 *      version = "1.0.0"
 *
 *   ② 内联数组写法（Modrinth 打包生成的模组长这样，例如万象包里的 panda-temple-V1-1.21+.jar）
 *      mods = [
 *          { modId = 'mr_panda_temple', version = '1-V1-1.21+', displayName = 'Panda Temple', ... },
 *      ]
 * </pre>
 *
 * <p>为什么必须支持 ②（2026-09-26 实测）：内联数组里**所有键都在同一行**，旧实现用
 * {@code (?m)^\s*key\s*=\s*"..."} 匹配（要求键在行首）→ {@code mods()} 返回空。后果有两个：
 * ① 这个模组进不了静态预检的"已安装"表，别的模组依赖它时会被**误判成"缺前置"**；
 * ② 拿不到它的"声明版本"，L4（`Empty pre-release` 归因）就没法反查肇事模组。
 *
 * <p>为什么不用现成 TOML 库：这里只需两种结构 + 少量键，工具要求体积小、无额外依赖。
 * 取值也刻意只认引号包围的字符串（`"x"` 或 `'x'`），数字/布尔不在我们的需求里。
 */
public final class ModToml {

    public record ModDecl(String modId, String version) {
    }

    /** @param requester 发起依赖的一方（= dependencies.&lt;requester&gt; 里的名字） */
    public record DepDecl(String requester, String target, String type, String versionRange,
                          String ordering, boolean placeholder) {
    }

    /**
     * 表头块：从 {@code [[header]]} 到"下一个 {@code [[} / 下一个顶层数组赋值 / 文件末尾"。
     *
     * <p>后面那一截"顶层数组赋值"是必要的：内联数组写法（{@code mods = [...]}）没有 {@code [[mods]]}，
     * 但它可能出现在某个 {@code [[dependencies.x]]} 之后，若不截断就会把它的键误当成依赖的键。
     */
    private static final Pattern HEADER_BLOCK = Pattern.compile(
            "(?sm)\\[\\[(mods|dependencies\\.[^]]+)]]\\s*(.*?)"
                    + "(?=\\s*\\[\\[|^\\s*[A-Za-z_][A-Za-z0-9_.]*\\s*=\\s*\\[|\\z)");

    /** 顶层数组赋值：{@code name = [} */
    private static final Pattern ARRAY_ASSIGN = Pattern.compile(
            "(?m)^\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*\\[");

    private ModToml() {
    }

    /** 读出 jar 声明的模组本体（表头写法 + 内联数组写法都认） */
    public static List<ModDecl> mods(String toml) {
        List<ModDecl> out = new ArrayList<>();
        for (Block b : blocks(toml)) {
            if (!"mods".equals(b.header())) continue;
            addMod(out, b.body());
        }
        for (String entry : inlineTableBodies(toml, "mods")) addMod(out, entry);
        return dedupe(out);
    }

    /** 读出依赖声明（只认 {@code [[dependencies.<requester>]]} 表头写法，这是加载器的标准写法） */
    public static List<DepDecl> deps(String toml) {
        List<DepDecl> out = new ArrayList<>();
        for (Block b : blocks(toml)) {
            if (!b.header().startsWith("dependencies.")) continue;
            String requester = b.header().substring("dependencies.".length());
            String target = strValue(b.body(), "modId");
            if (target == null) continue;
            String rawRange = orDefault(strValue(b.body(), "versionRange"), "*");
            boolean placeholder = rawRange.contains("${");
            out.add(new DepDecl(requester, target, orDefault(strValue(b.body(), "type"), "optional"),
                    placeholder ? "*" : rawRange, strValue(b.body(), "ordering"), placeholder));
        }
        return out;
    }

    private static void addMod(List<ModDecl> out, String body) {
        String id = strValue(body, "modId");
        if (id == null || id.isBlank()) return;
        out.add(new ModDecl(id, orDefault(strValue(body, "version"), "?")));
    }

    /** 同一个 modId 只留一条（内联数组可能同时被表头解析路径看到） */
    private static List<ModDecl> dedupe(List<ModDecl> in) {
        Map<String, ModDecl> map = new LinkedHashMap<>();
        for (ModDecl m : in) map.putIfAbsent(m.modId(), m);
        return List.copyOf(map.values());
    }

    private record Block(String header, String body) {
    }

    private static List<Block> blocks(String toml) {
        List<Block> out = new ArrayList<>();
        if (toml == null) return out;
        Matcher m = HEADER_BLOCK.matcher(toml);
        while (m.find()) out.add(new Block(m.group(1), m.group(2)));
        return out;
    }

    // ------------------------------------------------------------------ 内联数组

    /**
     * 取 {@code name = [ {...}, {...} ]} 里每个 {@code {...}} 的**内部文本**。
     *
     * <p>手写扫描而不是正则：要正确跳过引号里的 {@code [ ] { } }（比如 description 里带方括号），
     * 正则做不到稳。
     */
    static List<String> inlineTableBodies(String toml, String name) {
        List<String> out = new ArrayList<>();
        if (toml == null) return out;
        Matcher m = ARRAY_ASSIGN.matcher(toml);
        while (m.find()) {
            if (!name.equals(m.group(1))) continue;
            int open = m.end() - 1;                       // 指向 '['
            int close = matchBracket(toml, open);
            if (close < 0) continue;
            out.addAll(topLevelObjects(toml.substring(open + 1, close)));
        }
        return out;
    }

    /** 从 {@code [} 找到配对的 {@code ]}（跳过引号里的括号）；找不到返回 -1 */
    private static int matchBracket(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipString(s, i);
                if (i < 0) return -1;
                continue;
            }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 数组体里每个顶层 {@code { ... }} 的内部文本 */
    private static List<String> topLevelObjects(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' || c == '\'') {
                int q = skipString(body, i);
                if (q < 0) break;
                i = q;
                continue;
            }
            if (c == '{') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(body.substring(start, i));
                    start = -1;
                }
            }
        }
        return out;
    }

    /** @return 引号（含转义）结束的下标；未闭合返回 -1 */
    private static int skipString(String s, int quoteIdx) {
        char quote = s.charAt(quoteIdx);
        for (int i = quoteIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && quote == '"') {
                i++;
                continue;
            }
            if (c == quote) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 键值读取

    /**
     * 取 {@code name = "值"} / {@code name = '值'} 的值；找不到返回 null。
     *
     * <p>不用"行首正则"的理由见类注释：内联数组里所有键在同一行。这里做成小扫描器：
     * 跳过字符串内容（避免把 description 里的 {@code x = 'y'} 当键）、只认引号值。
     */
    static String strValue(String body, String name) {
        if (body == null) return null;
        int i = 0;
        int n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (c == '"' || c == '\'') {
                int q = skipString(body, i);
                if (q < 0) return null;
                i = q + 1;
                continue;
            }
            if (!isIdentStart(c)) {
                i++;
                continue;
            }
            int s = i;
            while (i < n && isIdentPart(body.charAt(i))) i++;
            String key = body.substring(s, i);
            int j = i;
            while (j < n && Character.isWhitespace(body.charAt(j))) j++;
            if (j >= n || body.charAt(j) != '=') continue;
            j++;
            while (j < n && Character.isWhitespace(body.charAt(j))) j++;
            if (j >= n || (body.charAt(j) != '"' && body.charAt(j) != '\'')) continue;
            int q = skipString(body, j);
            if (q < 0) return null;
            if (name.equals(key)) return body.substring(j + 1, q);
            i = q + 1;                                    // 跳过这个值，别把值里的内容当键
        }
        return null;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static String orDefault(String v, String d) {
        return v == null ? d : v;
    }
}
