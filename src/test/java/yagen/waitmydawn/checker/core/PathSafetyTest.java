package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSafetyTest {
    @TempDir Path temp;

    @Test
    void 普通目录和文件不会因真实路径展开而误判为链接() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("state"));
        Path file = Files.writeString(directory.resolve("policy.json"), "{}");

        assertTrue(PathSafety.isDirectory(directory));
        assertTrue(PathSafety.isRegularFile(file));
        assertFalse(PathSafety.containsLink(file));
    }

    @Test
    void 规范路径比较兼容短路径与真实路径() throws Exception {
        Path full = temp.toRealPath();

        assertTrue(PathSafety.samePath(temp, full));
        assertEquals(PathSafety.key(full), PathSafety.key(temp));
        assertTrue(PathSafety.canonical(temp.resolve("not-created")).startsWith(
                PathSafety.canonical(temp)));
    }
}
