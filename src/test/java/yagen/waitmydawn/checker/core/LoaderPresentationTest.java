package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderPresentationTest {
    @Test
    void 按加载器类型选择徽标并回退MC() {
        assertEquals("Neo", LoaderPresentation.of("neoforge").code());
        assertEquals("Fab", LoaderPresentation.of("Fabric").code());
        assertEquals("For", LoaderPresentation.of("forge").code());
        assertEquals("Qui", LoaderPresentation.of("quilt").code());
        assertTrue(LoaderPresentation.of("forge").iconSource().contains("MinecraftForge"));
        assertNull(LoaderPresentation.of("unknown").iconSource());
        assertEquals("MC", LoaderPresentation.of("unknown").code());
        assertEquals("MC", LoaderPresentation.of(null).code());
    }
}
