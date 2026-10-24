package yagen.waitmydawn.checker.net;

import yagen.waitmydawn.checker.core.LoaderPresentation;
import yagen.waitmydawn.checker.core.ProxyConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 加载器图标缓存。图标由项目控制的外部地址提供，只有通过大小和图片签名检查后才会落盘。
 */
public final class LoaderIconCache {
    public static final int MAX_IMAGE_BYTES = 512 * 1024;
    private static final int TIMEOUT_SECONDS = 8;
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private final Path cacheRoot;
    private final HttpClient client;

    public LoaderIconCache(Path toolRoot) {
        cacheRoot = toolRoot.toAbsolutePath().normalize()
                .resolve("cache").resolve("loader-icons-v1");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = Http.proxy();
        if (proxy == null || proxy.isBlank()) proxy = ProxyConfig.loadConfigured(toolRoot);
        if (proxy == null || proxy.isBlank()) proxy = ProxyConfig.passiveSystemProxy();
        if (proxy != null && !proxy.isBlank()) {
            URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            builder.proxy(java.net.ProxySelector.of(new InetSocketAddress(
                    uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8080)));
        }
        client = builder.build();
    }

    /**
     * 优先返回本地缓存；缓存不存在时下载并原子写入。无图标来源时返回空值，调用方显示缩写。
     */
    public Optional<byte[]> load(LoaderPresentation presentation)
            throws IOException, InterruptedException {
        Optional<byte[]> cached = read(presentation);
        if (cached.isPresent() || presentation.iconSource() == null) return cached;
        byte[] image = download(presentation);
        store(presentation, image);
        return Optional.of(image);
    }

    Optional<byte[]> read(LoaderPresentation presentation) throws IOException {
        Path file = cacheFile(presentation);
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.size(file) > MAX_IMAGE_BYTES) {
            return Optional.empty();
        }
        byte[] bytes = Files.readAllBytes(file);
        return supportedImage(bytes) ? Optional.of(bytes) : Optional.empty();
    }

    void store(LoaderPresentation presentation, byte[] bytes) throws IOException {
        if (!supportedImage(bytes) || bytes.length > MAX_IMAGE_BYTES) {
            throw new IOException("加载器图标格式无效或超过大小上限");
        }
        Path file = cacheFile(presentation);
        if (file == null) return;
        Files.createDirectories(cacheRoot);
        if (Files.isSymbolicLink(cacheRoot) || !Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("加载器图标缓存目录无效");
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path cacheFile(LoaderPresentation presentation) {
        return presentation.iconSource() == null
                ? null : cacheRoot.resolve(presentation.family() + ".img");
    }

    public void invalidate(LoaderPresentation presentation) {
        Path file = cacheFile(presentation);
        if (file == null || Files.isSymbolicLink(file)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    static boolean supportedImage(byte[] bytes) {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_IMAGE_BYTES) return false;
        if (startsWith(bytes, PNG_SIGNATURE)) return true;
        return (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
    }

    private byte[] download(LoaderPresentation presentation) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(presentation.iconSource()))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", "MAA-Checker/1.0.0")
                .header("Accept", "image/png,image/jpeg,image/*;q=0.8")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("加载器图标下载失败：HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
            if (!supportedImage(bytes)) {
                throw new IOException("加载器图标不是受支持的 PNG/JPEG");
            }
            return bytes;
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }
}
