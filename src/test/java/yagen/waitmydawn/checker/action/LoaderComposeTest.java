package yagen.waitmydawn.checker.action;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoaderComposeTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode original() throws Exception {
        return json.readTree("""
                {"id":"my-instance","jar":"my-client","mainClass":"old.Main","inheritsFrom":"1.21.1",
                 "arguments":{"jvm":["-Xmx3G","-p","old-module-path","--add-opens","old/open"],
                              "game":["--username","fixture","--fml.neoForgeVersion","21.1.1"]},
                 "libraries":[{"name":"old:loader:1"},{"name":"common:library:1"}],
                 "patches":[{"id":"minecraft","version":"1.21.1"},
                            {"id":"neoforge","version":"21.1.1","priority":30000,"libraries":[{"name":"old:loader:1"}]}]}
                """);
    }

    @Test
    void 差量合并同时更新补丁与平铺字段并保留实例身份() throws Exception {
        JsonNode before = original();
        JsonNode snapshot = before.deepCopy();
        JsonNode produced = json.readTree("""
                {"mainClass":"new.Main","inheritsFrom":"1.21.1",
                 "arguments":{"jvm":["-p","new-module-path"],"game":["--fml.neoForgeVersion","21.1.10"]},
                 "libraries":[{"name":"new:loader:10"},{"name":"common:library:1"}]}
                """);
        var after = LoaderPatch.compose(before, produced, "21.1.10");
        assertEquals(snapshot, before);
        assertEquals("my-instance", after.path("id").asText());
        assertEquals("my-client", after.path("jar").asText());
        assertEquals("new.Main", after.path("mainClass").asText());
        assertEquals("21.1.10", after.path("patches").get(1).path("version").asText());
        assertEquals("1.21.1", after.path("patches").get(0).path("version").asText());
        assertEquals(2, after.path("libraries").size());
        assertFalse(after.path("arguments").toString().contains("old-module-path"));
        assertFalse(after.path("arguments").toString().contains("21.1.1\""));
        assertTrue(after.path("arguments").toString().contains("fixture"));
        assertTrue(after.path("arguments").toString().contains("-Xmx3G"));
        assertTrue(after.path("arguments").path("jvm").toString().contains("-Dfile.encoding=UTF-8"));
        assertTrue(after.path("patches").get(1).path("arguments").path("jvm").toString().contains("-Dfile.encoding=UTF-8"));
    }

    @Test
    void 不完整产物或不明确的补丁结构拒绝合并() throws Exception {
        assertThrows(java.io.IOException.class, () -> LoaderPatch.compose(original(), json.readTree("{}"), "21.1.10"));
        assertThrows(java.io.IOException.class, () -> LoaderPatch.compose(original(), json.readTree("{\"mainClass\":\"new.Main\",\"libraries\":[]}"), "21.1.10"));
        var duplicate = original();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("patches")).add(duplicate.path("patches").get(1).deepCopy());
        assertThrows(java.io.IOException.class, () -> LoaderPatch.validateLayout(duplicate));
    }
}
