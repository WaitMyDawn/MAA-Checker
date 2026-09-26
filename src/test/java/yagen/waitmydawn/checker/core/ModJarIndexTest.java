package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "这个 jar 到底是谁"的读取规则。
 *
 * <p>补装前必须核对 jar 内声明的 modId：Modrinth 的项目/版本 JSON 里都没有 modId 字段，
 * 同名模组（modId 都叫 relics）只能靠这一步兜住。
 */
class ModJarIndexTest {

    @TempDir
    Path tmp;

    @Test
    void 读得到neoforge的mods_toml里的modId() throws Exception {
        Path jar = tmp.resolve("relics-1.21.1-0.12.8.jar");
        writeJar(jar, "META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="All Rights Reserved"

                [[mods]]
                modId="relics"
                version="0.12.8"
                displayName="Relics"
                """);

        Set<String> ids = ModJarIndex.modIdsOf(jar);
        assertTrue(ids.contains("relics"), "实际读到：" + ids);
    }

    @Test
    void 读得到fabric的mod_json里的id() throws Exception {
        Path jar = tmp.resolve("other-1.0.0.jar");
        writeJar(jar, "fabric.mod.json", """
                {"schemaVersion":1,"id":"other_mod","version":"1.0.0"}
                """);

        assertTrue(ModJarIndex.modIdsOf(jar).contains("other_mod"));
    }

    @Test
    void 读不到任何声明时返回空集_调用方必须当成无法确认() throws Exception {
        Path jar = tmp.resolve("plain.jar");
        writeJar(jar, "README.txt", "just a jar");

        assertEquals(Set.of(), ModJarIndex.modIdsOf(jar));
    }

    private static void writeJar(Path jar, String entryName, String content) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
