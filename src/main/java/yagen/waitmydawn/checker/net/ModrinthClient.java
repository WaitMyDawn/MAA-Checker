package yagen.waitmydawn.checker.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.VersionRanges;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Modrinth 查询：给"缺失的依赖 modId + 目标 mc/loader + 版本区间"找出可下载的 jar。
 *
 * <p>解析路径是<b>候选链 + 闸门</b>，不是"第一个 200 就返回"——因为 modId 不等于 Modrinth 项目名是常态，
 * 而且 slug 会撞名。实测（2026-09-25）：
 * <pre>
 *   GET /v2/project/relics      → 200，但那是<b>另一个</b>项目（project_type=mod，loaders=[datapack]）
 *   GET /v2/project/relics-mod  → 真正的 Relics（loaders=[forge,neoforge]，1.21.1 有 0.12.3~0.12.8）
 * </pre>
 * 旧实现在第一步就 return 了（只看 {@code project_type == mod}），于是"这个 slug 存在、但没有 neoforge 版本"
 * 被当成"Modrinth 上没有这个前置"，直接摘掉了需要它的模组。现在改成三步：
 * <ol>
 *   <li><b>slug 直查候选</b>（下划线在搜索文本索引里不通，必须直查）→ 每个候选都要过闸门：
 *       必须是真 mod（datapack/resourcepack 不算）、loader 必须与目标<b>严格一致</b>
 *       （neoforge 与 forge 互斥，不做互相通融）、当前 mc+loader 下必须选得出可用的 .jar；</li>
 *   <li><b>搜索回退</b>：命中按"slug 与 modId 相同 → 标题与 modId 相同 → 唯一命中"排优先级，
 *       得到的候选同样要过闸门；名字对不上的同名候选只登记、不采用（工具不替用户猜是哪一个）；</li>
 *   <li>返回<b>全部</b>候选（按可信度排序），由执行器逐个下载、用 <b>jar 内真实 modId</b> 校验后再装。
 *       Modrinth 的接口里没有 modId 字段，jar 里的 mods.toml / fabric.mod.json 才是唯一 ground truth，
 *       所以"两个模组的 modId 都叫 relics"这种重名场景也只能在这里兜住。</li>
 * </ol>
 *
 * <p>刻意<b>不做</b> slug 后缀猜测（{@code relics → relics-mod}）：每个作者的习惯都不一样，这类猜测又随机又不可靠，
 * 猜错还会装错包。靠"搜索 + 闸门 + jar 校验"已经能拿到确定答案。
 */
public final class ModrinthClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://api.modrinth.com/v2";
    /** MCIM 的 API 镜像前缀：把 api.modrinth.com 换成 mod.mcimirror.top/modrinth（实测 0.065s vs 0.69s） */
    public static final String MIRROR_API_BASE = "https://mod.mcimirror.top/modrinth";
    /** 镜像的文件入口前缀（与官方 CDN 同路径形状；实测当前会 302 回官方，留着以便镜像以后支持文件） */
    public static final String MIRROR_FILE_BASE = "https://mod.mcimirror.top/data";
    /** 搜索一次性取多少条命中（同名模组看前几名就够了，不必翻页） */
    private static final int SEARCH_LIMIT = 8;

    /**
     * @param projectId Modrinth 项目 ID（形如 {@code umyGl7zF}），用于拼镜像文件入口地址
     * @param versionId 版本 ID（形如 {@code THIGFPwf}）
     * @param url       官方 CDN 地址（镜像 API 返回的也是这个——镜像不改写地址，只加速"找地址"）
     */
    public record Candidate(String slug, String title, String projectId, String versionId,
                            String versionNumber, String fileName, String url, long size,
                            String projectType) {

        /** 镜像的文件入口；项目/版本 ID 缺失时返回 null */
        public String mirrorUrl() {
            if (projectId == null || versionId == null || fileName == null) return null;
            return MIRROR_FILE_BASE + "/" + projectId + "/versions/" + versionId + "/" + fileName;
        }
    }

    /**
     * 解析结论。
     *
     * @param candidates 可下载候选（按可信度排序，全部已过闸门）。执行器要逐个下载、校验 jar 内 modId
     * @param rejected   被闸门排除的项目及原因（进台账：说清"为什么补不上"，而不是一句"找不到"）
     * @param sameName   搜到的同名候选（名字与 modId 对不上，未采用）——留给人工确认
     */
    public record Resolution(List<Candidate> candidates, List<String> rejected, List<String> sameName) {

        public boolean found() {
            return !candidates.isEmpty();
        }
    }

    /** 取数抽象：生产走 Http / 通道管理器，单测注入假 JSON（不引任何第三方库） */
    interface JsonGet {

        /** @return 响应体；404 返回 null */
        String get(String url) throws IOException;
    }

    private ModrinthClient() {
    }

    public static Resolution resolve(String modId, String mcVersion, String loader,
                                     String requiredRange, Consumer<String> log) throws IOException {
        return resolve(modId, mcVersion, loader, requiredRange, httpGet(log), log);
    }

    /** @param nm 下载通道管理器（可为 null：直接用 Java 网络栈）。走它才能享受 直连↔代理 切换 */
    public static Resolution resolve(String modId, String mcVersion, String loader,
                                     String requiredRange, NetworkManager nm, Consumer<String> log)
            throws IOException {
        return resolve(modId, mcVersion, loader, requiredRange,
                nm == null ? httpGet(log) : channelGet(nm, log), log);
    }

    /** 兼容入口：只要第一个候选（{@code --test-proxy} 等旧调用点用） */
    public static Candidate findDependency(String modId, String mcVersion, String loader,
                                           String requiredRange, Consumer<String> log)
            throws IOException {
        Resolution r = resolve(modId, mcVersion, loader, requiredRange, log);
        return r.found() ? r.candidates().get(0) : null;
    }

    /** 兼容入口：只要第一个候选 */
    public static Candidate findDependency(String modId, String mcVersion, String loader,
                                           String requiredRange, NetworkManager nm,
                                           Consumer<String> log) throws IOException {
        Resolution r = resolve(modId, mcVersion, loader, requiredRange, nm, log);
        return r.found() ? r.candidates().get(0) : null;
    }

    /** 走通道管理器取 JSON：404 视为"没有这个项目"，其它网络错误交给通道管理器换路 */
    private static JsonGet channelGet(NetworkManager nm, Consumer<String> log) {
        return url -> {
            try {
                return nm.getJson(url);
            } catch (IOException e) {
                if (e instanceof Http.HttpStatusException h && h.status() == 404) return null;
                throw e;
            }
        };
    }

    private static JsonGet httpGet(Consumer<String> log) {
        return url -> Http.getStringOrNull(url, log);
    }

    // ------------------------------------------------------------------ 解析主体

    /** 候选依据：某条"为什么认为这个 slug 就是我们要的项目"的理由 + 优先级（越小越可信） */
    private record Ref(String slug, int priority, String how) {
    }

    /**
     * 解析主体（取数器可注入，便于离线单测）。
     *
     * <p>永远返回"候选 + 淘汰原因"，把"确证找不到"和"有候选但都不可用"分开表达——上层据此决定是
     * 改走摘除（确证没有），还是停下来等人工确认（不确定是哪一个）。
     */
    static Resolution resolve(String modId, String mcVersion, String loader, String requiredRange,
                              JsonGet get, Consumer<String> log) throws IOException {
        String want = normalize(modId);
        Map<String, JsonNode> projects = new LinkedHashMap<>();     // slug → 项目 JSON（同一项目只查一次）
        List<Ref> refs = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> sameName = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();

        // ① 按 slug 候选直查：Modrinth 的搜索是文本索引，下划线会被当作分隔符
        //    （实测 query=ars_nouveau 返回 0 条，而 /v2/project/ars-nouveau 是 200）。
        //    但直查命中 ≠ 找对了项目：同名 slug 会撞到别的模组，所以命中后必须过闸门，
        //    绝不"第一个 200 就返回"。
        for (String cand : slugCandidates(modId)) {
            JsonNode p = project(cand, get, projects, log);
            if (p == null) continue;
            String slug = p.path("slug").asText(cand);
            if (!slug.equalsIgnoreCase(modId) && log != null) {
                log.accept("   ↳ modId \"" + modId + "\" → Modrinth 项目 \"" + slug + "\"");
            }
            refs.add(new Ref(slug, normalize(slug).equals(want) ? 0 : 1, "slug 直查 \"" + cand + "\""));
        }

        // ② 搜索回退：把"直查撞名 / 直查不到、真项目在别的 slug 下"的情况补回来
        JsonNode hits = search(modId, mcVersion, loader, get, log);
        if (hits != null) {
            for (JsonNode h : hits) {
                String slug = h.path("slug").asText("");
                if (slug.isBlank()) continue;
                if (normalize(slug).equals(want)) {
                    refs.add(new Ref(slug, 0, "搜索命中：slug 与 modId 相同"));
                } else if (normalize(h.path("title").asText()).equals(want)) {
                    refs.add(new Ref(slug, 2, "搜索命中：标题与 modId 相同"));
                } else if (hits.size() == 1) {
                    refs.add(new Ref(slug, 3, "搜索唯一命中"));
                } else {
                    sameName.add("slug=" + slug + "（" + h.path("title").asText() + "）");
                }
            }
        }

        // ③ 逐个候选过闸门 + 选版本（不满足就换下一个，不在这里下"找不到"的结论）
        for (Ref ref : ordered(refs)) {
            JsonNode p = project(ref.slug(), get, projects, log);
            if (p == null) continue;
            String slug = p.path("slug").asText(ref.slug());
            String gate = gate(p, loader);
            if (gate != null) {
                String why = "项目 \"" + slug + "\"（" + ref.how() + "）被排除：" + gate;
                rejected.add(why);
                if (log != null) log.accept("   ⚠️ " + why);
                continue;
            }
            Candidate c = pickVersion(p, slug, mcVersion, loader, requiredRange, get, log);
            if (c == null) {
                rejected.add("项目 \"" + slug + "\"（" + ref.how() + "）在 " + loader + "/" + mcVersion
                        + " 下选不出可用 jar");
                continue;
            }
            candidates.add(c);
        }

        if (log != null) logConclusion(modId, mcVersion, loader, candidates, rejected, sameName, log);
        return new Resolution(List.copyOf(candidates), List.copyOf(rejected), List.copyOf(sameName));
    }

    private static void logConclusion(String modId, String mcVersion, String loader,
                                      List<Candidate> candidates, List<String> rejected,
                                      List<String> sameName, Consumer<String> log) {
        if (!candidates.isEmpty()) {
            log.accept("   ↳ 候选 " + candidates.size() + " 个："
                    + candidates.stream().map(c -> c.slug() + " " + c.versionNumber())
                    .collect(Collectors.joining(" / "))
                    + "（装之前会下载并核对 jar 内真实 modId）");
            return;
        }
        if (rejected.isEmpty() && sameName.isEmpty()) {
            log.accept("   ↳ 结论：Modrinth 上没有与 \"" + modId + "\" 对应的 " + loader + "/"
                    + mcVersion + " 模组（slug 候选与搜索都没有结果）");
            return;
        }
        rejected.forEach(r -> log.accept("   ↳ " + r));
        sameName.forEach(s -> log.accept("   ↳ 同名候选（名字对不上，不采用）：" + s));
        log.accept("   ↳ 结论：有候选但都不可用 → 判为'补不到'");
    }

    /** 同一项目只保留最可信的那条依据；再按优先级稳定排序（相同优先级保持发现顺序） */
    private static List<Ref> ordered(List<Ref> refs) {
        Map<String, Ref> best = new LinkedHashMap<>();
        for (Ref r : refs) {
            Ref cur = best.get(r.slug());
            if (cur == null || r.priority() < cur.priority()) best.put(r.slug(), r);
        }
        List<Ref> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingInt(Ref::priority));
        return out;
    }

    /**
     * 闸门：只放行"真 mod + loader 严格一致"的项目。
     *
     * @return 不放行时的原因；放行返回 null
     */
    static String gate(JsonNode project, String loader) {
        String type = project.path("project_type").asText("mod");
        if (!"mod".equals(type)) return "project_type=" + type + "（不是 mod）";
        Set<String> loaders = new LinkedHashSet<>();
        for (JsonNode l : project.path("loaders")) loaders.add(l.asText().toLowerCase(Locale.ROOT));
        if (loaders.isEmpty()) return "没有声明 loaders";
        // neoforge 与 forge 互斥：不做"neoforge 也能当 forge 使"的通融，否则会把 Forge 版装进 NeoForge 包
        if (!loaders.contains(loader.toLowerCase(Locale.ROOT))) {
            return "loaders=" + loaders + "（不含 " + loader + "）";
        }
        return null;
    }

    /** 版本挑选：优先满足依赖声明的版本区间，都不满足才退到最新版（并把风险写进日志） */
    private static Candidate pickVersion(JsonNode project, String slug, String mcVersion,
                                         String loader, String requiredRange, JsonGet get,
                                         Consumer<String> log) throws IOException {
        String versionsUrl = API + "/project/" + Http.enc(slug) + "/version?loaders="
                + Http.enc("[\"" + loader + "\"]") + "&game_versions=" + Http.enc("[\"" + mcVersion + "\"]");
        String versionsBody = get.get(versionsUrl);
        if (versionsBody == null) {
            if (log != null) log.accept("   ↳ 版本接口没有返回内容（项目 " + slug + "）");
            return null;
        }
        JsonNode versions;
        try {
            versions = MAPPER.readTree(versionsBody);
        } catch (Exception e) {
            if (log != null) log.accept("   ↳ 版本接口返回不是 JSON: " + e.getMessage());
            return null;
        }
        if (!versions.isArray() || versions.isEmpty()) {
            if (log != null) {
                log.accept("   ↳ 项目 " + slug + " 存在，但没有 " + loader + "/" + mcVersion
                        + " 的版本 → 换下一个候选");
            }
            return null;
        }
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode v : versions) {
            if (primaryFile(v) != null) usable.add(v);
        }
        if (usable.isEmpty()) {
            if (log != null) log.accept("   ↳ " + slug + " 的版本里没有可用的 .jar → 换下一个候选");
            return null;
        }
        // 优先满足依赖声明的区间；都不满足才退到最新版，并把风险写进日志
        List<JsonNode> fitting = usable.stream()
                .filter(v -> VersionRanges.satisfies(v.path("version_number").asText(), requiredRange))
                .toList();
        boolean exact = !fitting.isEmpty();
        List<JsonNode> pool = exact ? fitting : usable;
        JsonNode chosen = pool.stream()
                .max(Comparator.comparing(v -> v.path("date_published").asText()))
                .orElseThrow();
        if (!exact && log != null) {
            log.accept("   ⚠️ " + slug + " 没有满足区间 " + requiredRange + " 的版本，退用最新版 "
                    + chosen.path("version_number").asText() + "（可能仍不满足依赖，会在下一轮暴露）");
        }
        JsonNode file = primaryFile(chosen);
        return new Candidate(slug, project.path("title").asText(),
                project.path("id").asText(),                 // 镜像文件入口要用项目 ID
                chosen.path("id").asText(),
                chosen.path("version_number").asText(), file.path("filename").asText(),
                file.path("url").asText(), file.path("size").asLong(), "mod");
    }

    /** 按 slug/ID 查项目；404 返回 null（连"查过但没有"也缓存，避免同一个 slug 重复打接口） */
    private static JsonNode project(String slugOrId, JsonGet get, Map<String, JsonNode> cache,
                                    Consumer<String> log) throws IOException {
        if (cache.containsKey(slugOrId)) return cache.get(slugOrId);
        JsonNode p = parse(get.get(API + "/project/" + Http.enc(slugOrId)), log, "项目 " + slugOrId);
        cache.put(slugOrId, p);
        if (p != null) {
            String canonical = p.path("slug").asText("");
            if (!canonical.isBlank()) cache.putIfAbsent(canonical, p);
        }
        return p;
    }

    private static JsonNode search(String modId, String mcVersion, String loader, JsonGet get,
                                   Consumer<String> log) throws IOException {
        String facets = "[[" + quote("project_type:mod") + "],["
                + quote("categories:" + loader) + "],[" + quote("versions:" + mcVersion) + "]]";
        List<String> variants = queryVariants(modId);
        for (int i = 0; i < variants.size(); i++) {
            String q = variants.get(i);
            String url = API + "/search?limit=" + SEARCH_LIMIT + "&index=relevance&query="
                    + Http.enc(q) + "&facets=" + Http.enc(facets);
            JsonNode root = parse(get.get(url), log, "搜索 " + q);
            JsonNode hits = root == null ? null : root.path("hits");
            if (hits != null && hits.isArray() && !hits.isEmpty()) {
                // 只有在"前一个查询词真的没搜到"时才说"改用"——第一个词就命中不必多说一句
                if (i > 0 && log != null) {
                    log.accept("   ↳ 用 \"" + variants.get(0) + "\" 搜不到，改用 \"" + q + "\" 搜到 "
                            + hits.size() + " 条");
                }
                return hits;
            }
        }
        if (log != null) {
            log.accept("   ↳ Modrinth 搜索无结果（已试 " + variants + "）");
        }
        return null;
    }

    /** 响应体 → JSON；空/坏 JSON 返回 null（拿到 HTML/劫持页时绝不能当数据用） */
    private static JsonNode parse(String body, Consumer<String> log, String what) {
        if (body == null) return null;
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            if (log != null) log.accept("   ↳ " + what + " 的响应不是 JSON，忽略");
            return null;
        }
    }

    /** slug 候选：原样 → 下划线换连字符 → 去掉下划线 → 下划线换空格（不做后缀猜测） */
    static List<String> slugCandidates(String modId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(modId);
        out.add(modId.replace('_', '-'));
        out.add(modId.replace("_", ""));
        out.add(modId.replace('_', ' '));
        out.removeIf(s -> s.isBlank());
        return List.copyOf(out);
    }

    /** 搜索词变体：下划线在 Modrinth 文本索引里不通，必须换掉 */
    static List<String> queryVariants(String modId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (!modId.contains("_")) {
            out.add(modId);
        } else {
            out.add(modId.replace('_', '-'));
            out.add(modId.replace('_', ' '));
            out.add(modId.replace("_", ""));
        }
        return List.copyOf(out);
    }

    private static JsonNode primaryFile(JsonNode version) {
        for (JsonNode f : version.path("files")) {
            if (f.path("primary").asBoolean(false) && f.path("filename").asText().endsWith(".jar")) {
                return f;
            }
        }
        for (JsonNode f : version.path("files")) {
            if (f.path("filename").asText().endsWith(".jar")) return f;
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
