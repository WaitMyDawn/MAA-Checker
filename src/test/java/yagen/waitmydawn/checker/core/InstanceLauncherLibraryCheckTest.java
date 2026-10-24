package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恢复前实例运行库检查：目标版本配置引用尚未发布的库时，恢复必须能在切换配置之前停下。
 */
class InstanceLauncherLibraryCheckTest {

    @TempDir Path temp;

    @Test
    void 缺少实例运行库时报告缺失数量与示例() throws Exception {
        GameInstance instance = instance();
        Path version = writeVersion(UUID.randomUUID().toString(), "tenant/loader.jar", "tenant/extra.jar");

        InstanceLauncher.LibraryCheck check = InstanceLauncher.checkPrivateLibraries(instance, version, 1);

        assertTrue(check.managed());
        assertFalse(check.complete());
        assertEquals(2, check.missing());
        assertEquals(List.of("tenant/loader.jar"), check.examples(), "示例数量受 limit 限制");
    }

    @Test
    void 实例运行库齐全时通过() throws Exception {
        GameInstance instance = instance();
        String runtime = UUID.randomUUID().toString();
        Path version = writeVersion(runtime, "tenant/loader.jar");
        Path library = InstanceStorage.libraries(instance, runtime, true).resolve("tenant/loader.jar");
        Files.createDirectories(library.getParent());
        Files.writeString(library, "library");

        InstanceLauncher.LibraryCheck check = InstanceLauncher.checkPrivateLibraries(instance, version, 5);

        assertTrue(check.managed());
        assertTrue(check.complete());
        assertEquals(0, check.missing());
    }

    @Test
    void NeoForge隐式运行时产物也必须检查() throws Exception {
        GameInstance instance = instance();
        String runtime = UUID.randomUUID().toString();
        Path version = temp.resolve("game/versions/fixture/fixture.json");
        Files.writeString(version, """
                {
                  "id":"fixture",
                  "maaCheckerRuntime":"%s",
                  "patches":[{"id":"game","version":"1.21.1"},{"id":"neoforge","version":"21.1.264"}],
                  "arguments":{"game":["--fml.neoFormVersion","20240808.144430"]},
                  "libraries":[]
                }
                """.formatted(runtime), StandardCharsets.UTF_8);

        InstanceLauncher.LibraryCheck check = InstanceLauncher.checkPrivateLibraries(instance, version, 2);

        assertFalse(check.complete());
        assertEquals(5, check.missing());
        assertTrue(check.examples().getFirst().contains("neoforge-21.1.264-client.jar"));
    }

    @Test
    void 未声明实例运行库时使用共享库且不阻断恢复() throws Exception {
        GameInstance instance = instance();
        Path version = writeVersion(null, "tenant/shared.jar");

        InstanceLauncher.LibraryCheck check = InstanceLauncher.checkPrivateLibraries(instance, version, 5);

        assertFalse(check.managed());
        assertTrue(check.complete());
        assertEquals(instance.gameRoot().resolve("libraries"), check.root());
    }

    @Test
    void 非法实例运行库标识被拒绝() throws Exception {
        GameInstance instance = instance();
        Path version = writeVersion("not-a-uuid", "tenant/loader.jar");

        assertThrows(IOException.class,
                () -> InstanceLauncher.checkPrivateLibraries(instance, version, 5));
    }

    private GameInstance instance() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("game/versions/fixture"));
        Files.createDirectories(directory.resolve("mods"));
        Files.writeString(directory.resolve("fixture.json"),
                "{\"id\":\"fixture\",\"mainClass\":\"fixture\",\"libraries\":[]}",
                StandardCharsets.UTF_8);
        return InstanceScanner.scanOne(temp.resolve("game"), directory);
    }

    private Path writeVersion(String runtime, String... libraryPaths) throws Exception {
        StringBuilder json = new StringBuilder("{\"id\":\"fixture\",\"mainClass\":\"fixture\"");
        if (runtime != null) json.append(",\"maaCheckerRuntime\":\"").append(runtime).append('"');
        json.append(",\"libraries\":[");
        for (int i = 0; i < libraryPaths.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"downloads\":{\"artifact\":{\"path\":\"")
                    .append(libraryPaths[i]).append("\"}}}");
        }
        json.append("]}");
        Path version = temp.resolve("game/versions/fixture/fixture.json");
        Files.writeString(version, json.toString(), StandardCharsets.UTF_8);
        return version;
    }
}
