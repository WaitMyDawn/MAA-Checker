package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {
    @Test
    void 桌面用户数据可由系统属性显式指定() {
        String previous = System.getProperty("maachecker.userData");
        try {
            Path expected = Path.of("build", "maa-user-data").toAbsolutePath().normalize();
            System.setProperty("maachecker.userData", expected.toString());
            assertEquals(expected, AppPaths.desktopDataRoot());
        } finally {
            if (previous == null) System.clearProperty("maachecker.userData");
            else System.setProperty("maachecker.userData", previous);
        }
    }
}
