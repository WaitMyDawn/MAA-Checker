package yagen.waitmydawn.checker.net;

import yagen.waitmydawn.checker.core.VersionRanges;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoForge 版本选择：从官方 Maven 元数据里挑"满足最低要求的最新正式版"。
 *
 * <p>版本号前缀规则：NeoForge 的版本 = MC 主次版本 + 自增量。MC {@code 1.21.1} → {@code 21.1.x}。
 * 只认正式版（滤掉 {@code -beta}），并且用"数字序列比较"而不是字符串比较
 * （字符串比较会把 21.1.99 判成大于 21.1.231，这是本模块早期的真实坑）。
 */
public final class NeoForgeVersions {

    private static final String METADATA =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private NeoForgeVersions() {
    }

    /**
     * @param minVersion 运行期崩溃日志给出的最低要求（例如 {@code [21.1.247,)} 里的 21.1.247）
     * @return 目标版本；取不到返回 null（调用方据此中止并提示用户自己升级）
     */
    public static String latestSatisfying(String mcVersion, String minVersion, Consumer<String> log)
            throws IOException {
        String prefix = mcPrefix(mcVersion);
        if (prefix == null) return null;
        String xml = Http.getString(METADATA, log);
        List<String> candidates = new ArrayList<>();
        Matcher m = VERSION_TAG.matcher(xml);
        while (m.find()) {
            String v = m.group(1).trim();
            if (!v.startsWith(prefix)) continue;
            if (v.contains("-")) continue;                       // beta/preview 不选
            if (minVersion != null && !minVersion.isBlank()
                    && VersionRanges.compare(v, minVersion) < 0) continue;
            candidates.add(v);
        }
        if (candidates.isEmpty()) {
            if (log != null) log.accept("   ↳ " + prefix + "x 里没有满足 >= " + minVersion + " 的正式版");
            return null;
        }
        String best = candidates.get(0);
        for (String v : candidates) {
            if (VersionRanges.compare(v, best) > 0) best = v;
        }
        if (log != null) {
            log.accept("   ↳ 可选 " + candidates.size() + " 个正式版，选最新：" + best);
        }
        return best;
    }

    /** MC 1.21.1 → "21.1."；MC 1.20.4 → "20.4."；拆不出主次版本返回 null */
    static String mcPrefix(String mcVersion) {
        if (mcVersion == null || !mcVersion.startsWith("1.")) return null;
        String[] p = mcVersion.split("\\.");
        if (p.length < 3) return null;
        return p[1] + "." + p[2] + ".";
    }
}
