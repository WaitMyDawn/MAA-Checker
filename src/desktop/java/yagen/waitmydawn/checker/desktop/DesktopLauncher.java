package yagen.waitmydawn.checker.desktop;

/** 独立入口避免 Java 启动器将类路径运行误判为缺少 JavaFX 模块。 */
public final class DesktopLauncher {
    private DesktopLauncher() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--package-smoke")) {
            int memoryMb = yagen.waitmydawn.checker.core.MemorySize.physicalMb();
            if (memoryMb < yagen.waitmydawn.checker.core.MemorySize.MIN_MB) {
                throw new IllegalStateException("物理内存读取失败");
            }
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            javafx.application.Platform.startup(() -> {
                try {
                    for (int size : new int[]{32, 64, 256}) {
                        var icon = CheckerDesktop.class.getResource("app-icon-" + size + ".png");
                        if (icon == null) {
                            throw new IllegalStateException("缺少应用图标：" + size);
                        }
                        new javafx.scene.image.Image(icon.toExternalForm(), false);
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    ready.countDown();
                }
            });
            if (!ready.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit 启动超时");
            }
            javafx.application.Platform.exit();
            if (failure.get() != null) {
                throw new IllegalStateException("JavaFX 资源验证失败", failure.get());
            }
            return;
        }
        javafx.application.Application.launch(CheckerDesktop.class, args);
    }
}
