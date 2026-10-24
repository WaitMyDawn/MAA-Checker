package yagen.waitmydawn.checker.desktop;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import yagen.waitmydawn.checker.core.LoaderPresentation;
import yagen.waitmydawn.checker.net.LoaderIconCache;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 在后台读取或下载加载器图标；失败时保留卡片内的文字缩写。 */
final class LoaderIconService implements AutoCloseable {
    private final LoaderIconCache cache;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<Optional<byte[]>>> pending =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    LoaderIconService(Path toolRoot) {
        cache = new LoaderIconCache(toolRoot);
    }

    void bind(StackPane target, LoaderPresentation presentation) {
        if (closed || presentation.iconSource() == null) return;
        CompletableFuture<Optional<byte[]>> request = pending.computeIfAbsent(
                presentation.family(),
                key -> CompletableFuture.supplyAsync(() -> read(presentation), executor));
        request.thenAccept(image -> Platform.runLater(() -> apply(target, presentation, image)));
    }

    private Optional<byte[]> read(LoaderPresentation presentation) {
        try {
            return cache.load(presentation);
        } catch (Exception | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private void apply(StackPane target, LoaderPresentation presentation,
                       Optional<byte[]> content) {
        if (closed || content.isEmpty()) return;
        Image image = new Image(new ByteArrayInputStream(content.get()),
                44, 44, true, true);
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            cache.invalidate(presentation);
            pending.remove(presentation.family());
            return;
        }
        ImageView view = new ImageView(image);
        view.setFitWidth(44);
        view.setFitHeight(44);
        view.setPreserveRatio(true);
        view.setSmooth(false);
        view.setAccessibleText(presentation.title());
        target.getChildren().setAll(view);
    }

    CompletionStage<Void> shutdown() {
        closed = true;
        pending.clear();
        executor.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            pending.clear();
            executor.shutdownNow();
        }
    }
}
