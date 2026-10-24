package yagen.waitmydawn.checker.desktop;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class CheckerDesktop extends Application {
    static final double DEFAULT_WIDTH = 1120;
    static final double DEFAULT_HEIGHT = 720;
    private DesktopShell shell;

    @Override
    public void start(Stage stage) {
        shell = new DesktopShell(stage);
        var bounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(DEFAULT_WIDTH, bounds.getWidth() - 40);
        double height = Math.min(DEFAULT_HEIGHT, bounds.getHeight() - 40);
        Scene scene = new Scene(shell.root(), width, height);
        scene.getStylesheets().add(CheckerDesktop.class.getResource("desktop.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("MAA Checker · Minecraft 实例诊断");
        for (int size : new int[]{32, 64, 256}) {
            var icon = CheckerDesktop.class.getResource("app-icon-" + size + ".png");
            if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));
        }
        stage.setMinWidth(900);
        stage.setMinHeight(680);
        stage.setOnCloseRequest(e -> {
            if (!shell.requestClose()) e.consume();
        });
        stage.show();
        stage.centerOnScreen();
    }

    @Override
    public void stop() {
        if (shell != null) shell.close();
    }
}
