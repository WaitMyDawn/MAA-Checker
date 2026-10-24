package yagen.waitmydawn.checker.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NeoForgeVersionsTest {
    private static final String METADATA = """
            <metadata>
              <versioning>
                <versions>
                  <version>21.1.9</version>
                  <version>21.1.264</version>
                  <version>21.1.1000</version>
                  <version>21.1.270-beta</version>
                  <version>21.2.1</version>
                  <version>20.4.237</version>
                </versions>
              </versioning>
            </metadata>
            """;

    @Test
    void 只选择目标Minecraft的正式版且按数字比较版本() {
        assertEquals("21.1.1000", NeoForgeVersions.selectLatest(METADATA, "1.21.1", "21.1.247", null));
    }

    @Test
    void 没有满足下限的正式版时不回退到测试版或其他Minecraft版本() {
        assertNull(NeoForgeVersions.selectLatest(METADATA, "1.21.1", "21.1.1001", null));
        assertNull(NeoForgeVersions.selectLatest(METADATA, "1.20.5", null, null));
    }
}
