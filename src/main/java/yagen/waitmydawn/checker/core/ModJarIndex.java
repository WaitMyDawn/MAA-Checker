package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * mods 目录的元数据索引：**modId ↔ jar 文件 ↔ 声明版本**。
 *
 * <p>为什么要有它：
 * <ul>
 *   <li>崩溃日志只说 modId（{@code irons_spells_js}），而动作执行器要移动/替换的是<b>文件</b>，
 *       这层映射必须显式建出来，绝不能靠"modId + .jar"猜；</li>
 *   <li>有的故障（如 {@code Empty pre-release}）日志里**只有版本号、没有 modId**，
 *       只能靠"谁声明了这个版本"反查 —— 所以声明版本也要一起读出来。</li>
 * </ul>
 *
 * <p>只扫顶层 jar：嵌套 jar（JarJar 里的）不是独立文件，摘不掉也不该摘 —— 它的归属由外层 jar 决定。
 * 一个 jar 声明多个 modId 时全部指向该 jar。
 *
 * <p>读取交给 {@link JarModReader}（toml 两种写法 + fabric/quilt 元数据都认）——
 * 这里刻意不做第二套解析，避免"两处口径不一致"。
 */
public final class ModJarIndex {

    /**
     * 索引结果。{@link #jarOf()} 保持"声明里怎么写就怎么存"（与历史行为一致，老调用方零感知）；
     * 需要按 modId 查询时用 {@link #jarOf(String)} / {@link #versionOf(String)}（大小写不敏感）。
     */
    public static final class Meta {

        private final Map<String, String> jarOf;
        private final Map<String, String> jarOfLower = new LinkedHashMap<>();
        private final Map<String, String> versionOfLower = new LinkedHashMap<>();
        private final Map<String, List<String>> modIdsByVersion = new LinkedHashMap<>();

        Meta(Map<String, String> jarOf) {
            this.jarOf = jarOf;
            jarOf.forEach((k, v) -> jarOfLower.putIfAbsent(lower(k), v));
        }

        void put(String modId, String jarFile, String version) {
            boolean first = jarOf.putIfAbsent(modId, jarFile) == null;
            jarOfLower.putIfAbsent(lower(modId), jarFile);
            if (!first) return;                  // 同一个 modId 只记一次（否则"版本→modId"会重复计数）
            versionOfLower.putIfAbsent(lower(modId), version == null ? "?" : version);
            if (version != null && !version.isBlank()) {
                modIdsByVersion.computeIfAbsent(lower(version), k -> new ArrayList<>()).add(modId);
            }
        }

        /** modId → jar 文件名（键为声明原样，供既有解析路径使用） */
        public Map<String, String> jarOf() {
            return jarOf;
        }

        public String jarOf(String modId) {
            return modId == null ? null : jarOfLower.get(lower(modId));
        }

        public String versionOf(String modId) {
            return modId == null ? null : versionOfLower.get(lower(modId));
        }

        /** 精确等于该声明版本的 modId（忽略大小写与首尾空白；找不到返回空表） */
        public List<String> modIdsDeclaring(String version) {
            if (version == null) return List.of();
            return List.copyOf(modIdsByVersion.getOrDefault(lower(version), List.of()));
        }

        public Set<String> modIds() {
            return jarOfLower.keySet();
        }

        public boolean isEmpty() {
            return jarOf.isEmpty();
        }
    }

    private ModJarIndex() {
    }

    /** @return modId → jar 文件名（后者同名同目录，纯文件名便于日志与台账阅读） */
    public static Map<String, String> scan(Path modsDir) {
        return scanMeta(modsDir).jarOf();
    }

    /** 完整索引：modId → jar、modId → 声明版本、声明版本 → modId */
    public static Meta scanMeta(Path modsDir) {
        Meta meta = new Meta(new LinkedHashMap<>());
        if (modsDir == null || !Files.isDirectory(modsDir)) return meta;
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList();
        } catch (IOException e) {
            return meta;
        }
        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            for (JarModReader.Mod m : JarModReader.read(jar)) {
                meta.put(m.modId(), fileName, m.version());
            }
        }
        return meta;
    }

    /** 只有"modId → jar"时的降级入口（{@code --diagnose} 没有 mods 目录时的场景） */
    public static Meta ofJarIndex(Map<String, String> jarIndex) {
        return new Meta(new LinkedHashMap<>(jarIndex == null ? Map.of() : jarIndex));
    }

    /**
     * 读一个 jar 声明的全部 modId（补装后核对"这个 jar 到底是不是我们要的模组"用）。
     *
     * <p>Modrinth 的接口里<b>没有 modId 字段</b>，jar 里的元数据才是唯一 ground truth；
     * 读不到任何声明时返回空集 —— 调用方必须把"空集"当"无法确认"，不能当成通过。
     */
    public static Set<String> modIdsOf(Path jar) {
        Set<String> out = new LinkedHashSet<>();
        for (JarModReader.Mod m : JarModReader.read(jar)) out.add(m.modId());
        return out;
    }

    private static String lower(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }
}
