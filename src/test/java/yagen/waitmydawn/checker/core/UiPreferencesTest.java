package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiPreferencesTest {
    @TempDir Path temp;

    @Test
    void 外观设置全局保存并重新读取() throws Exception {
        Path tool = temp.resolve("tool");
        assertEquals(UiPreferences.defaults(), UiPreferences.load(tool));
        UiPreferences expected = new UiPreferences(true, false);
        UiPreferences.save(tool, expected);
        assertEquals(expected, UiPreferences.load(tool));
    }
}
