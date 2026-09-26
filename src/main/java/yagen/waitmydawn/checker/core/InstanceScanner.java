package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 实例识别：扫描 游戏目录/versions/*，识别出可启动的整合包实例。
 *
 * 三条实测踩坑规则：
 * 1) 版本 JSON 不能盲取第一个：实例目录里还有 modrinth.index.json / patchouli_data.json /
 *    usercache.json 等干扰文件，中文名实例的第一个 json 就是干扰项。
 *    优先取 id == 目录名，其次取同时含 mainClass 与 libraries 的那个。
 * 2) mc / loader 从启动参数里读（--fml.mcVersion、--fml.neoForgeVersion、mainClass），
 *    不从目录名猜（目录名可能是中文）。
 * 3) mods 目录：版本隔离时在实例内，未隔离时在游戏根；优先实例内。
 */
public final class InstanceScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FML_MC = Pattern.compile("--fml\\.mcVersion[\"\\s,]+([0-9][0-9a-zA-Z._]*)");
    private static final Pattern FML_NEOFORGE = Pattern.compile("--fml\\.neoForgeVersion[\"\\s,]+([0-9][0-9.]*)");
    private static final Pattern FML_FORGE = Pattern.compile("--fml\\.forgeVersion[\"\\s,]+([0-9][0-9.]*)");
    private static final Pattern FABRIC_LOADER = Pattern.compile("fabric-loader-([0-9][0-9.]*)");

    private InstanceScanner() {
    }

    /** 扫描游戏目录下的全部实例（按名字排序，保证可复现） */
    public static List<GameInstance> scanAll(Path gameDir) throws IOException {
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) return List.of();
        List<GameInstance> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(versions)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                GameInstance inst = scanOne(gameDir, dir);
                if (inst != null) out.add(inst);
            }
        }
        // 有模组的实例排前面（整合包才是主要使用场景），同类按名字稳定排序
        out.sort((a, b) -> {
            int c = Boolean.compare(b.looksLikeModpack(), a.looksLikeModpack());
            return c != 0 ? c : a.name().compareTo(b.name());
        });
        return out;
    }

    /** 识别单个实例目录；不像实例时返回 null */
    public static GameInstance scanOne(Path gameDir, Path instDir) {
        Path versionJson = findVersionJson(instDir);
        if (versionJson == null) return null;
        String name = instDir.getFileName().toString();
        String raw;
        JsonNode json;
        try {
            raw = Files.readString(versionJson, StandardCharsets.UTF_8);
            json = MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
        String mainClass = json.path("mainClass").asText("");
        String mc = firstGroup(FML_MC, raw);
        if (mc == null || mc.isBlank()) mc = json.path("inheritsFrom").asText("");
        if (mc.isBlank()) mc = guessMcFromName(name);
        String loader = "vanilla";
        String loaderVersion = null;
        if (mainClass.contains("bootstraplauncher") || mainClass.contains("modlauncher")) {
            String neo = firstGroup(FML_NEOFORGE, raw);
            if (neo != null) {
                loader = "neoforge";
                loaderVersion = neo;
            } else {
                loader = "forge";
                loaderVersion = firstGroup(FML_FORGE, raw);
            }
        } else if (mainClass.contains("fabricmc")) {
            loader = "fabric";
            loaderVersion = firstGroup(FABRIC_LOADER, raw);
        } else if (mainClass.contains("quiltmc")) {
            loader = "quilt";
        }
        Path modsDir = Files.isDirectory(instDir.resolve("mods"))
                ? instDir.resolve("mods") : gameDir.resolve("mods");
        return new GameInstance(name, instDir, versionJson, mc, loader, loaderVersion,
                modsDir, countJars(modsDir), gameDir);
    }

    /** 版本 JSON 选择规则见类注释第 1 条 */
    public static Path findVersionJson(Path instDir) {
        String dirName = instDir.getFileName().toString();
        Path best = null;
        try (Stream<Path> files = Files.list(instDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode j;
                try {
                    j = MAPPER.readTree(Files.readString(f, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    continue;
                }
                if (dirName.equals(j.path("id").asText(""))) return f;
                if (j.hasNonNull("mainClass") && j.path("libraries").isArray() && best == null) best = f;
            }
        } catch (IOException ignored) {
            return null;
        }
        return best;
    }

    private static int countJars(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.toString().endsWith(".jar")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 目录名自带版本号时兜底，例如 1.21.1-NeoForge */
    private static String guessMcFromName(String name) {
        Matcher m = Pattern.compile("(1\\.[0-9]{1,2}(?:\\.[0-9]{1,2})?)").matcher(name);
        return m.find() ? m.group(1) : "unknown";
    }
}
