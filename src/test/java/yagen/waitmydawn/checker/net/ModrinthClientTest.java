package yagen.waitmydawn.checker.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * modId → Modrinth 项目的解析规则（纯函数 + 注入假 JSON，不联网）。
 *
 * <p>两个真实误判都固化在这里：
 * <ul>
 *   <li>{@code ars_nouveau}：下划线在 Modrinth 搜索文本索引里返回 0 条，只走搜索的版本会把它
 *       判成"不存在"，进而误摘依赖它的 {@code ars_n_spells}；所以必须先按 slug 直查。</li>
 *   <li>{@code relics}：{@code /v2/project/relics} 是 200，但那是<b>另一个</b>项目（loaders=[datapack]），
 *       真正的 Relics 在 {@code relics-mod} 下。旧实现"第一个 200 就返回"，于是把
 *       "这个项目没有 neoforge 版本"当成了"Modrinth 上没有这个前置"，误摘了依赖方。</li>
 * </ul>
 */
class ModrinthClientTest {

    @Test
    void slug候选要覆盖下划线的常见写法_但不做后缀猜测() {
        assertEquals(List.of("ars_nouveau", "ars-nouveau", "arsnouveau", "ars nouveau"),
                ModrinthClient.slugCandidates("ars_nouveau"));
        assertEquals(List.of("jei"), ModrinthClient.slugCandidates("jei"));
        // 刻意不做"relics → relics-mod"这种后缀猜测：作者习惯各不相同，猜了反而容易装错包
        assertFalse(ModrinthClient.slugCandidates("relics").contains("relics-mod"));
    }

    @Test
    void 搜索词必须把下划线换成连字符否则搜不到() {
        assertTrue(ModrinthClient.queryVariants("ars_nouveau").contains("ars-nouveau"),
                "实测 query=ars_nouveau 是 0 条，必须换成 ars-nouveau");
        assertEquals(List.of("jei"), ModrinthClient.queryVariants("jei"),
                "没有下划线的正常 modId 不该被改写");
    }

    @Test
    void slug撞名时不能被第一个200挡住_要回退到搜索找到真项目() throws Exception {
        Map<String, String> api = new LinkedHashMap<>();
        // /v2/project/relics 是 200，但那是另一个项目：project_type=mod，loaders=[datapack]
        api.put("/project/relics", """
                {"id":"UnVJifuG","slug":"relics","title":"Relics","project_type":"mod","loaders":["datapack"]}
                """);
        api.put("/search?", """
                {"hits":[{"project_id":"OCJRPujW","slug":"relics-mod","title":"Relics"}]}
                """);
        api.put("/project/relics-mod", """
                {"id":"OCJRPujW","slug":"relics-mod","title":"Relics","project_type":"mod",
                 "loaders":["forge","neoforge"]}
                """);
        api.put("/project/relics-mod/version", """
                [{"id":"v3","version_number":"0.12.3","date_published":"2026-01-01T00:00:00Z",
                  "files":[{"primary":true,"filename":"relics-1.21.1-0.12.3.jar",
                            "url":"https://cdn.example/relics-0.12.3.jar","size":2048}]},
                 {"id":"v8","version_number":"0.12.8","date_published":"2026-05-01T00:00:00Z",
                  "files":[{"primary":true,"filename":"relics-1.21.1-0.12.8.jar",
                            "url":"https://cdn.example/relics-0.12.8.jar","size":2048}]}]
                """);

        ModrinthClient.Resolution res = ModrinthClient.resolve("relics", "1.21.1", "neoforge",
                "[0.12.3,)", fake(api), null);

        assertEquals(1, res.candidates().size(), "撞名的 datapack 项目不能被当成答案");
        ModrinthClient.Candidate c = res.candidates().get(0);
        assertEquals("relics-mod", c.slug());
        assertEquals("0.12.8", c.versionNumber(), "区间 [0.12.3,) 下应选最新的满足版本");
        assertTrue(res.rejected().stream().anyMatch(r -> r.contains("relics") && r.contains("datapack")),
                "被排除的同名项目要留下依据：实际 " + res.rejected());
    }

    @Test
    void loader必须严格一致_neoforge不接受只标forge的项目() throws Exception {
        Map<String, String> api = new LinkedHashMap<>();
        api.put("/project/foo", """
                {"id":"aaa","slug":"foo","title":"Foo","project_type":"mod","loaders":["forge"]}
                """);
        api.put("/search?", """
                {"hits":[{"project_id":"aaa","slug":"foo","title":"Foo"}]}
                """);

        ModrinthClient.Resolution res = ModrinthClient.resolve("foo", "1.21.1", "neoforge",
                "*", fake(api), null);

        assertFalse(res.found());
        assertTrue(res.rejected().stream().anyMatch(r -> r.contains("不含 neoforge")),
                "只标 forge 的项目在 neoforge 目标下必须被排除：实际 " + res.rejected());
    }

    @Test
    void datapack项目不算mod() throws Exception {
        Map<String, String> api = new LinkedHashMap<>();
        api.put("/project/bar", """
                {"id":"bbb","slug":"bar","title":"Bar","project_type":"datapack","loaders":["datapack"]}
                """);

        ModrinthClient.Resolution res = ModrinthClient.resolve("bar", "1.21.1", "neoforge",
                "*", fake(api), null);

        assertFalse(res.found());
        assertTrue(res.rejected().stream().anyMatch(r -> r.contains("不是 mod")),
                "datapack 不该被当成 mod 依赖装上：" + res.rejected());
    }

    @Test
    void 项目存在但目标环境没版本时_要继续试下一个候选而不是直接判死() throws Exception {
        Map<String, String> api = new LinkedHashMap<>();
        // 直查到的 foo 支持 neoforge，但 1.21.1 下没有任何版本
        api.put("/project/foo", """
                {"id":"aaa","slug":"foo","title":"Foo","project_type":"mod","loaders":["neoforge"]}
                """);
        api.put("/project/foo/version", "[]");
        // 搜索到同一个模组的另一个项目（标题与 modId 相同）
        api.put("/search?", """
                {"hits":[{"project_id":"ccc","slug":"foo-mod","title":"Foo"}]}
                """);
        api.put("/project/foo-mod", """
                {"id":"ccc","slug":"foo-mod","title":"Foo","project_type":"mod","loaders":["neoforge"]}
                """);
        api.put("/project/foo-mod/version", """
                [{"id":"v1","version_number":"1.0.0","date_published":"2026-02-01T00:00:00Z",
                  "files":[{"primary":true,"filename":"foo-mod-1.0.0.jar",
                            "url":"https://cdn.example/foo.jar","size":4096}]}]
                """);

        ModrinthClient.Resolution res = ModrinthClient.resolve("foo", "1.21.1", "neoforge",
                "*", fake(api), null);

        assertEquals(1, res.candidates().size());
        assertEquals("foo-mod", res.candidates().get(0).slug());
    }

    @Test
    void 同名候选名字对不上时只登记不采用_交给人工确认() throws Exception {
        Map<String, String> api = new LinkedHashMap<>();
        api.put("/search?", """
                {"hits":[{"project_id":"d1","slug":"relics-rpg","title":"Relics (RPG Series)"},
                         {"project_id":"d2","slug":"more-relics","title":"More Relics"}]}
                """);

        ModrinthClient.Resolution res = ModrinthClient.resolve("relics", "1.21.1", "neoforge",
                "*", fake(api), null);

        assertFalse(res.found());
        assertEquals(2, res.sameName().size(), "同名候选要如实登记：不猜是哪一个");
    }

    /**
     * 按 URL 关键字匹配的假取数器；没配到的一律当 404（null），与真实 API 行为一致。
     *
     * <p>匹配时<b>长关键字优先</b>：{@code /project/foo/version?...} 同时包含 {@code /project/foo}，
     * 若不按长度排序就会把版本接口错认成项目接口。
     */
    private static ModrinthClient.JsonGet fake(Map<String, String> byUrlPart) {
        List<Map.Entry<String, String>> rules = new ArrayList<>(byUrlPart.entrySet());
        rules.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        return url -> {
            for (Map.Entry<String, String> e : rules) {
                if (url.contains(e.getKey())) return e.getValue();
            }
            return null;
        };
    }
}
