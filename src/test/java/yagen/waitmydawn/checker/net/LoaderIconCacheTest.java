package yagen.waitmydawn.checker.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.core.LoaderPresentation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LoaderIconCacheTest {
    @TempDir Path temp;

    @Test
    void 有效图标写入后可从本地缓存读取() throws Exception {
        LoaderIconCache cache = new LoaderIconCache(temp);
        LoaderPresentation presentation = LoaderPresentation.of("neoforge");
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl8sAAAAASUVORK5CYII=");

        cache.store(presentation, image);

        assertArrayEquals(image, cache.read(presentation).orElseThrow());
        assertEquals(temp.resolve("cache/loader-icons-v1/neoforge.img"),
                cache.cacheFile(presentation));
        assertTrue(Files.isRegularFile(cache.cacheFile(presentation)));
    }

    @Test
    void 拒绝伪装成图片的响应且不创建缓存() {
        LoaderIconCache cache = new LoaderIconCache(temp);
        LoaderPresentation presentation = LoaderPresentation.of("fabric");

        assertThrows(Exception.class, () ->
                cache.store(presentation, "<html>blocked</html>".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(Files.exists(cache.cacheFile(presentation)));
        assertFalse(LoaderIconCache.supportedImage(new byte[]{(byte) 0x89, 0x50}));
    }

    @Test
    void Minecraft回退不发起外部图标请求() throws Exception {
        LoaderIconCache cache = new LoaderIconCache(temp);
        LoaderPresentation presentation = LoaderPresentation.of("unknown");

        assertTrue(cache.load(presentation).isEmpty());
        assertNull(cache.cacheFile(presentation));
    }
}
