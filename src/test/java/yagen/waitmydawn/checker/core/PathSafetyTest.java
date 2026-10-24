package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
