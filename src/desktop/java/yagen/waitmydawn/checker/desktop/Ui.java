package yagen.waitmydawn.checker.desktop;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** 共享组件工厂；图标路径采用 Lucide 的 24 单位坐标系。 */
final class Ui {
    private Ui() { }

    static Label text(String value, String style) {
        Label label = new Label(value);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        if (style.equals("badge") || style.equals("badge-warning") || style.equals("tag")) {
            label.setMaxWidth(Region.USE_PREF_SIZE);
        }
        return label;
    }

    static Button button(String value, String style, Runnable action) {
        Button button = new Button(value);
        button.getStyleClass().add(style);
        button.setOnAction(e -> action.run());
        button.setMinHeight(40);
        return button;
    }

    static VBox card(Node... content) {
        VBox box = new VBox(14, content);
        box.getStyleClass().add("card");
        return box;
    }

    static Region spacer() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        VBox.setVgrow(space, Priority.ALWAYS);
        return space;
    }

    static Node icon(String kind) {
        SVGPath path = new SVGPath();
        path.setContent(switch (kind) {
            case "box" -> "M21 8l-9 5-9-5 M12 13v9 M3 7l9-5 9 5v10l-9 5-9-5Z";
            case "task" -> "M9 5H5v16h14V5h-4 M9 3h6v4H9Z M8 12h8 M8 16h6";
            case "history" -> "M3 11a9 9 0 1 1 2.5 7 M3 4v7h7 M12 7v5l3 2";
            case "storage" -> "M4 6h16v12H4Z M7 3v3 M17 3v3 M7 18v3 M17 18v3 M8 10h8 M8 14h5";
            case "settings" -> "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 2h6l1 4 4 1 2 5-3 3v5l-5 2-3-3-5 1-3-5 2-4-1-5 5-1Z";
            case "up" -> "M12 20V4 M5 11l7-7 7 7";
            case "down" -> "M12 3v12 M6 9l6 6 6-6 M4 16v5h16v-5";
            case "shield" -> "M12 2l9 4v6c0 5-5 9-9 10-4-1-9-5-9-10V6Z M8 12l3 3 5-6";
            default -> "M5 12l4 4L20 5";
        });
        path.getStyleClass().add("icon");
        Region holder = new javafx.scene.layout.StackPane(path);
        holder.setMinSize(24, 24);
        holder.setPrefSize(24, 24);
        holder.setMaxSize(24, 24);
        holder.setMouseTransparent(true);
        return holder;
    }
}
