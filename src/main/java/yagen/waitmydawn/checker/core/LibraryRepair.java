package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.net.Http;
import yagen.waitmydawn.checker.net.NetworkManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 补齐缺失的库（libraries）。
 *
 * <p>为什么需要它：库文件是启动器的"共享地基"，任何一次误删（例如递归删除穿透了目录链接）
 * 都会让**所有**实例一起起不来，报错往往是"找不到主类"这种看不懂的样子。
 * 版本 JSON 里每个库都带 `downloads.artifact.{path,url,sha1,size}`，所以补库是纯机械活：
 * 缺哪个下哪个、下完校验 sha1，不需要重装游戏也不需要重新下载整合包。
 *
 * <p>只增不改不删：已存在且校验通过的文件一律跳过。
 */
public final class LibraryRepair {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @param missing 缺失数；@param fixed 补成功数；@param failed 失败清单 */
    public record Report(int total, int missing, int fixed, List<String> failed) {
    }

    private LibraryRepair() {
    }

    public static Report repair(GameInstance inst, NetworkManager nm, Consumer<String> log)
            throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(inst.versionJson(), StandardCharsets.UTF_8));
        Path libRoot = inst.gameRoot().resolve("libraries");
        List<JsonNode> downloads = collectDownloads(root);
        int missing = 0;
        int fixed = 0;
        List<String> failed = new ArrayList<>();
        int idx = 0;
        for (JsonNode d : downloads) {
            idx++;
            String rel = d.path("path").asText("");
            String url = d.path("url").asText("");
            String sha1 = d.path("sha1").asText("");
            if (rel.isBlank() || url.isBlank()) continue;
            Path target = libRoot.resolve(rel);
            if (Files.isRegularFile(target)) {
                if (sha1.isBlank() || sha1.equalsIgnoreCase(sha1(target))) continue;
                log.accept("   · 校验值不符，重下：" + rel);
            }
            missing++;
            log.accept("   ⇣ [" + idx + "/" + downloads.size() + "] 补 " + rel);
            Http.DownloadOutcome o = nm != null
                    ? nm.download(url, null, d.path("size").asLong(), target)
                    : Http.download(url, target, log, d.path("size").asLong());
            if (!o.ok()) {
                failed.add(rel + "（" + o.detail() + "）");
                continue;
            }
            if (!sha1.isBlank() && !sha1.equalsIgnoreCase(sha1(target))) {
                failed.add(rel + "（sha1 校验失败）");
                continue;
            }
            fixed++;
        }
        return new Report(downloads.size(), missing, fixed, List.copyOf(failed));
    }

    /** 收集版本 JSON 里所有可下载的库文件（含 patches 里的，按 path 去重） */
    static List<JsonNode> collectDownloads(JsonNode root) {
        Map<String, JsonNode> byPath = new LinkedHashMap<>();
        List<JsonNode> containers = new ArrayList<>();
        containers.add(root);
        for (JsonNode patch : root.path("patches")) containers.add(patch);
        for (JsonNode c : containers) {
            for (JsonNode lib : c.path("libraries")) {
                JsonNode artifact = lib.path("downloads").path("artifact");
                if (artifact.hasNonNull("path")) byPath.putIfAbsent(artifact.path("path").asText(), artifact);
                // natives 也要：启动器要靠它们解压出原生库（只取 windows 的，免得把 Linux/macOS 的也拖下来）
                JsonNode classifiers = lib.path("downloads").path("classifiers");
                if (classifiers.isObject()) {
                    for (var it = classifiers.fields(); it.hasNext(); ) {
                        var en = it.next();
                        if (en.getKey().contains("windows") && en.getValue().hasNonNull("path")) {
                            byPath.putIfAbsent(en.getValue().path("path").asText(), en.getValue());
                        }
                    }
                }
            }
        }
        return new ArrayList<>(byPath.values());
    }

    static String sha1(Path p) {
        try (var in = Files.newInputStream(p);
             var din = new java.security.DigestInputStream(in, MessageDigest.getInstance("SHA-1"))) {
            din.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(din.getMessageDigest().digest());
        } catch (Exception e) {
            return "";
        }
    }
}
