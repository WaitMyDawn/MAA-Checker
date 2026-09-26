package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * "这个 jar 声明了哪些模组"的**唯一**读取口：三载体一起看。
 *
 * <ol>
 *   <li>{@code META-INF/neoforge.mods.toml}（NeoForge 时代）与 {@code META-INF/mods.toml}（Forge 时代）——
 *       两种写法都交给 {@link ModToml}；</li>
 *   <li>{@code fabric.mod.json} 的 {@code id}/{@code version}；</li>
 *   <li>{@code quilt.mod.json} 的 {@code quilt_loader.id}/{@code quilt_loader.version}。</li>
 * </ol>
 *
 * <p>为什么必须三载体都读（2026-09-26 实测）：万象包里的 {@code panda-temple-V1-1.21+.jar} 用的是
 * toml **内联数组**写法，旧解析器读不出来；它同时带了 fabric/quilt 元数据，才"侥幸"被认出 modId。
 * 反过来，只读 fabric 的 jar（纯 Fabric 模组）在只读 toml 的旧代码里是完全不可见的。
 *
 * <p>本类只做"读声明"，不做任何合法性判断——版本号格式是否合法由加载器决定，
 * 我们只在**报错之后**用这里的声明版本反查肇事模组。
 */
public final class JarModReader {

    /** @param source 从哪读到的（toml / fabric / quilt），写进台账便于解释归因来源 */
    public record Mod(String modId, String version, String source) {
    }

    private static final Pattern JSON_STR = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private JarModReader() {
    }

    /** 读一个 jar 文件（读不了返回空表，绝不抛） */
    public static List<Mod> read(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return List.of();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            return read(zf);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 读一个已打开的 jar（不关闭传入的流） */
    public static List<Mod> read(ZipFile zf) {
        Map<String, Mod> out = new LinkedHashMap<>();     // modId → 第一条（toml 优先）
        String toml = readEntry(zf, "META-INF/neoforge.mods.toml");
        if (toml == null) toml = readEntry(zf, "META-INF/mods.toml");
        if (toml != null) {
            for (ModToml.ModDecl m : ModToml.mods(toml)) {
                out.putIfAbsent(m.modId(), new Mod(m.modId(), m.version(), "toml"));
            }
        }
        String fabric = readEntry(zf, "fabric.mod.json");
        if (fabric != null) {
            String id = jsonValue(fabric, "id");
            if (id != null) out.putIfAbsent(id, new Mod(id, orQ(jsonValue(fabric, "version")), "fabric"));
        }
        String quilt = readEntry(zf, "quilt.mod.json");
        if (quilt != null) {
            // quilt 的 id/version 在 quilt_loader 里面；外面 depends 里也有 "id"，
            // 所以必须先截到 quilt_loader 之后再取，否则会读到依赖项的 id
            String loader = afterKey(quilt, "quilt_loader");
            String id = jsonValue(loader, "id");
            if (id != null) out.putIfAbsent(id, new Mod(id, orQ(jsonValue(loader, "version")), "quilt"));
        }
        return List.copyOf(out.values());
    }

    private static String jsonValue(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile(String.format(JSON_STR.pattern(), key)).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 取 {@code "key"} 之后的部分（用于先定位 quilt_loader 再取里面的字段） */
    private static String afterKey(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:").matcher(json);
        return m.find() ? json.substring(m.end()) : json;
    }

    private static String orQ(String v) {
        return v == null ? "?" : v;
    }

    /** @return 条目文本；不存在或读失败返回 null */
    static String readEntry(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e1) {
            return null;
        }
    }

    /** 便于测试/日志：只取 modId */
    public static List<String> modIds(Path jar) {
        List<String> out = new ArrayList<>();
        for (Mod m : read(jar)) out.add(m.modId());
        return List.copyOf(out);
    }
}
