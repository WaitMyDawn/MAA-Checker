package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * NeoForge 运行时中不写进版本 JSON {@code libraries}、但启动器会按约定路径读取的隐式产物。
 *
 * <p>NeoForge 的 universal/client jar 和 NeoForm 的 client-extra/slim/srg jar 不属于普通
 * Maven 依赖条目。只复制显式库会让 FML 把 {@code minecraft}、{@code neoforge} 识别为
 * {@code [MISSING]}，因此共享或旧私有运行库布局都必须把这组文件作为一等依赖跟踪。
 */
public final class LoaderRuntimeLayout {
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+\\-]+");

    private LoaderRuntimeLayout() { }

    public static List<String> requiredFiles(JsonNode version) throws IOException {
        String loader = null;
        String minecraft = null;
        for (JsonNode patch : version.path("patches")) {
            if (patch.path("id").asText().equals("neoforge")) loader = patch.path("version").asText();
            if (patch.path("id").asText().equals("game")) minecraft = patch.path("version").asText();
        }
        if (loader == null || loader.isBlank()) return List.of();
        if (minecraft == null || minecraft.isBlank()) {
            throw new IOException("NeoForge 配置缺少 Minecraft 基准版本");
        }
        String neoForm = argumentValue(version.path("arguments").path("game"), "--fml.neoFormVersion");
        if (neoForm == null) throw new IOException("NeoForge 配置缺少 --fml.neoFormVersion，无法定位原版运行时");
        versionSegment(loader, "NeoForge");
        versionSegment(minecraft, "Minecraft");
        versionSegment(neoForm, "NeoForm");
        String client = minecraft + "-" + neoForm;
        versionSegment(client, "client");

        List<String> files = new ArrayList<>();
        files.add("net/neoforged/neoforge/" + loader + "/neoforge-" + loader + "-client.jar");
        files.add("net/neoforged/neoforge/" + loader + "/neoforge-" + loader + "-universal.jar");
        files.add("net/minecraft/client/" + client + "/client-" + client + "-extra.jar");
        files.add("net/minecraft/client/" + client + "/client-" + client + "-slim.jar");
        files.add("net/minecraft/client/" + client + "/client-" + client + "-srg.jar");
        return List.copyOf(files);
    }

    private static String argumentValue(JsonNode arguments, String flag) {
        String result = null;
        for (JsonNode argument : arguments) {
            if (!argument.isTextual()) continue;
            if (result != null) return argument.asText();
            if (argument.asText().equals(flag)) result = flag;
        }
        return null;
    }

    private static void versionSegment(String value, String label) throws IOException {
        if (!VERSION.matcher(value).matches() || value.contains("..")) {
            throw new IOException(label + " 版本包含不安全字符：" + value);
        }
    }
}
