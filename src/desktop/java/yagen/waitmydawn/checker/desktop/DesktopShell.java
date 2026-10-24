package yagen.waitmydawn.checker.desktop;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import yagen.waitmydawn.checker.core.AppConfig;
import yagen.waitmydawn.checker.core.AppPaths;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceLauncher;
import yagen.waitmydawn.checker.core.LoaderPresentation;
import yagen.waitmydawn.checker.core.UiPreferences;
import yagen.waitmydawn.checker.task.GlobalRepairPolicy;
import yagen.waitmydawn.checker.task.CheckerTasks;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskSnapshot;
import yagen.waitmydawn.checker.task.WorkflowHistoryIndex;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static yagen.waitmydawn.checker.desktop.Ui.*;

/** JavaFX 主界面：实例发现、真实检查与修复任务、真实历史入口和外观设置。 */
public final class DesktopShell implements AutoCloseable {
    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final VBox sidebar = new VBox(12);
    private final StackPane viewport = new StackPane();
    private final TaskService scanTasks = new TaskService();
    private final CheckPane check;
    private final HistoryPane history;
    private final StoragePane storage;
    private final LoaderIconService loaderIcons;
    private final Label notice = text("所有检查与修复都在本机执行；任务页面会显示当前阶段和下一步。", "notice");
    private final Map<String, Button> navigation = new LinkedHashMap<>();
    private final Path output;

    private String page = "实例";
    private boolean dark;
    private boolean motion = true;
    private boolean closed;
    private TaskService.Handle<List<GameInstance>> scan;
    private List<GameInstance> found = List.of();
    private Path gameDirectory;
    private String scanMessage = "尚未选择目录。扫描只读取实例元数据，不会启动游戏或修改文件。";
    private FadeTransition transition;

    public DesktopShell(Stage stage) {
        this(stage, AppPaths.desktopDataRoot());
    }

    DesktopShell(Stage stage, Path output) {
        this(stage, output, null);
    }

    DesktopShell(Stage stage, Path output,
                 yagen.waitmydawn.checker.action.LoaderPreparation.Factory loaderFactory) {
        this.stage = stage;
        this.output = output.toAbsolutePath().normalize();
        loaderIcons = new LoaderIconService(this.output);
        UiPreferences preferences = UiPreferences.defaults();
        try {
            preferences = UiPreferences.load(this.output);
        } catch (Exception e) {
            notice.setText("外观设置读取失败，已使用默认值：" + e.getMessage());
        }
        dark = preferences.dark();
        motion = preferences.motion();
        check = new CheckPane(output, loaderFactory);
        String configured = AppConfig.get(this.output, AppConfig.KEY_GAME_DIR);
        if (configured != null && !configured.isBlank()) {
            try {
                gameDirectory = Path.of(configured).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                gameDirectory = null;
            }
        }
        history = new HistoryPane(this.output, () -> gameDirectory, this::openHistory);
        storage = new StoragePane(this.output, check::selectedInstance, check::reloadWorkflow);
        root.getStyleClass().add("app");
        if (dark) root.getStyleClass().add("dark");
        root.setLeft(sidebar);
        root.setCenter(new BorderPane(viewport, notice, null, null, null));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(184);
        sidebar.getChildren().addAll(text("MAA CHECKER", "brand"), text("Minecraft 实例检验", "muted"));
        sidebar.getChildren().add(new Separator());
        nav("实例", "box");
        nav("当前任务", "task");
        nav("历史记录", "history");
        nav("存储", "storage");
        sidebar.getChildren().add(spacer());
        nav("设置", "settings");
        sidebar.getChildren().add(text("MAA CHECKER\n启动检查 · 日志诊断", "caption"));
        root.widthProperty().addListener((observable, oldValue, width) -> {
            boolean compact = width.doubleValue() < 1080;
            sidebar.setPrefWidth(compact ? 152 : 184);
            root.getStyleClass().remove("compact");
            if (compact) root.getStyleClass().add("compact");
        });
        show("实例");
    }

    public BorderPane root() {
        return root;
    }

    private void nav(String name, String icon) {
        Button button = button(name, "nav", () -> show(name));
        button.setId("nav-" + name);
        button.setGraphic(icon(icon));
        button.setGraphicTextGap(12);
        button.setMaxWidth(Double.MAX_VALUE);
        navigation.put(name, button);
        sidebar.getChildren().add(button);
    }

    private void show(String target) {
        page = target;
        navigation.forEach((name, button) -> {
            button.getStyleClass().remove("selected");
            if (name.equals(page)) button.getStyleClass().add("selected");
        });
        Node content = switch (page) {
            case "实例" -> instancesPage();
            case "历史记录" -> {
                history.refresh();
                yield history.root();
            }
            case "存储" -> {
                storage.refresh();
                yield storage.root();
            }
            case "设置" -> settingsPage();
            default -> check.root();
        };
        if (transition != null) transition.stop();
        viewport.getChildren().setAll(content);
        if (motion && !check.busy()) {
            transition = new FadeTransition(Duration.millis(140), content);
            transition.setFromValue(.94);
            transition.setToValue(1);
            transition.play();
        }
    }

    private VBox heading(String eyebrow, String title, String subtitle) {
        return new VBox(8, text(eyebrow, "caption"), text(title, "page-title"), text(subtitle, "muted"));
    }

    private ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
    }

    private VBox body(Node... children) {
        VBox body = new VBox(18, children);
        body.getStyleClass().add("page-body");
        return body;
    }

    private Node instancesPage() {
        TextField search = new TextField();
        search.setId("instance-search");
        search.setPromptText("按实例名称或路径筛选本地实例");
        VBox list = new VBox(12);
        Label scanStatus = text(scanMessage, "muted");
        scanStatus.setId("scan-status");

        Runnable render = () -> {
            list.getChildren().clear();
            String query = search.getText() == null ? "" : search.getText().strip().toLowerCase();
            for (GameInstance instance : found) {
                String haystack = (instance.name() + "\n" + instance.dir()).toLowerCase();
                if (!query.isEmpty() && !haystack.contains(query)) continue;
                FlowPane tags = new FlowPane(8, 8,
                        text("Minecraft " + instance.mcVersion(), "tag"),
                        text(instance.loader() + " " + instance.loaderVersion(), "tag"),
                        text(instance.modCount() + " 个模组", "tag"),
                        text("Java " + InstanceLauncher.requiredJavaOf(instance) + "+", "tag"));
                tags.setPrefWrapLength(10_000);
                tags.setId("instance-tags-" + found.indexOf(instance));
                Button select = button("选择", "primary", () -> selectInstance(instance));
                select.setId("inspect-instance-" + found.indexOf(instance));
                select.setMinWidth(76);
                select.setPrefWidth(76);
                select.setMaxWidth(76);
                select.setDisable(check.busy() || scanning());
                VBox detail = new VBox(10, text(instance.name(), "section-title"), tags,
                        text(instance.dir().toString(), "caption"));
                HBox.setHgrow(detail, Priority.ALWAYS);
                StackPane loader = loaderBadge(instance);
                loader.setId("loader-badge-" + found.indexOf(instance));
                HBox row = new HBox(18, loader, detail, spacer(), select);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("summary");
                list.getChildren().add(row);
            }
            if (list.getChildren().isEmpty()) {
                list.getChildren().add(text(found.isEmpty()
                        ? "选择 .minecraft 目录后会在这里显示可检验实例。"
                        : "没有符合当前筛选条件的实例。", "muted"));
            }
        };
        search.textProperty().addListener((observable, oldValue, value) -> render.run());
        render.run();

        Button choose = button("选择 .minecraft 目录", "secondary", this::chooseDirectory);
        choose.setId("choose-directory");
        choose.setDisable(scanning());
        VBox real = card(text("本地实例", "section-title"), scanStatus, search, choose, list);
        if (scanning()) {
            Button cancelScan = button(
                    scan.snapshot().state() == TaskSnapshot.State.CANCELLING ? "正在停止…" : "取消扫描",
                    "secondary",
                    () -> {
                        scan.cancel();
                        scanMessage = "正在停止扫描，等待当前读取结束。";
                        show(page);
                    });
            cancelScan.setId("cancel-scan");
            cancelScan.setDisable(scan.snapshot().state() == TaskSnapshot.State.CANCELLING);
            real.getChildren().add(3, cancelScan);
        }
        return scroll(body(heading("工作空间", "你的 Minecraft 实例",
                "选择游戏目录并打开实例。"), real));
    }

    private StackPane loaderBadge(GameInstance instance) {
        LoaderPresentation presentation = LoaderPresentation.of(instance.loader());
        Label fallback = text(presentation.code(), "loader-fallback");
        StackPane badge = new StackPane(fallback);
        badge.getStyleClass().addAll("instance-tile", "loader-" + presentation.family());
        Tooltip.install(badge, new Tooltip(presentation.title() + " 实例"));
        badge.setMinSize(58, 58);
        badge.setPrefSize(58, 58);
        badge.setMaxSize(58, 58);
        loaderIcons.bind(badge, presentation);
        return badge;
    }

    void selectInstance(GameInstance instance) {
        if (closed || check.busy() || scanning()) return;
        check.select(instance);
        show("当前任务");
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("只读扫描：选择含 versions 的游戏目录");
        File selected = chooser.showDialog(stage);
        if (selected != null) scanDirectory(selected.toPath());
    }

    void scanDirectory(Path directory) {
        scanDirectory(directory, CheckerTasks.scan(directory));
    }

    void scanDirectory(Path directory, TaskService.Work<List<GameInstance>> work) {
        if (closed || scanning()) return;
        Path normalized = directory.toAbsolutePath().normalize();
        found = List.of();
        scanMessage = "正在扫描 " + normalized + " …";
        scan = scanTasks.submit(normalized.toString(), context -> {
            TaskResult<List<GameInstance>> result = work.run(context);
            if (result.state() == TaskSnapshot.State.SUCCEEDED) {
                AppConfig.set(output, AppConfig.KEY_GAME_DIR, normalized.toString());
                try {
                    WorkflowHistoryIndex.remember(output, result.value());
                } catch (Exception e) {
                    context.log("历史索引更新失败：" + e.getMessage());
                }
            }
            return result;
        }, null);
        TaskService.Handle<List<GameInstance>> current = scan;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || scan != current) return;
            if (error != null) {
                scanMessage = "扫描失败：" + error.getMessage();
            } else if (result.state() == TaskSnapshot.State.CANCELLED) {
                scanMessage = "已停止扫描。文件未修改。";
            } else if (result.state() != TaskSnapshot.State.SUCCEEDED) {
                scanMessage = result.message() + "。请检查目录后重试。";
            } else {
                found = result.value();
                gameDirectory = normalized;
                scanMessage = found.isEmpty()
                        ? "没有识别到实例。请选择含 versions 子目录的 .minecraft 目录。"
                        : "识别到 " + found.size() + " 个实例；可按名称或路径筛选。";
            }
            if ("实例".equals(page)) show(page);
        }));
        show("实例");
    }

    private boolean scanning() {
        return scan != null && !scan.snapshot().state().terminal();
    }

    private void openHistory(WorkflowHistoryIndex.Entry entry) {
        if (!entry.canOpen()) return;
        if (check.busy() || scanning()) {
            notice.setText("当前任务仍在运行；停止或等待完成后再打开历史会话。");
            return;
        }
        notice.setText("所有检查与修复都在本机执行；任务页面会显示当前阶段和下一步。");
        selectInstance(entry.instance());
        check.openWorkflowSession(entry.workflowId());
    }

    private Node settingsPage() {
        ToggleGroup group = new ToggleGroup();
        RadioButton light = new RadioButton("浅色");
        RadioButton darkButton = new RadioButton("深色");
        light.setToggleGroup(group);
        darkButton.setToggleGroup(group);
        (dark ? darkButton : light).setSelected(true);
        group.selectedToggleProperty().addListener((observable, oldValue, selected) -> {
            dark = selected == darkButton;
            root.getStyleClass().remove("dark");
            if (dark) root.getStyleClass().add("dark");
            savePreferences();
        });
        darkButton.setId("theme-dark");
        light.setId("theme-light");

        CheckBox animations = new CheckBox("启用轻量过渡动画");
        animations.setSelected(motion);
        animations.setOnAction(event -> {
            motion = animations.isSelected();
            savePreferences();
        });
        Button resetAppearance = button("恢复默认外观", "secondary", () -> {
            dark = false;
            motion = true;
            light.setSelected(true);
            animations.setSelected(true);
            root.getStyleClass().remove("dark");
            savePreferences();
        });
        Button openData = button("打开数据目录", "secondary", this::openDataDirectory);
        openData.setId("open-data-directory");
        Button manageStorage = button("管理存储", "secondary", () -> show("存储"));
        manageStorage.setId("settings-storage");
        Button resetPolicy = button("恢复全局默认策略", "secondary", this::resetGlobalPolicy);
        resetPolicy.setId("reset-global-policy");
        return scroll(body(heading("偏好设置", "外观设置",
                        "外观设置即时生效。"),
                card(text("外观", "section-title"), new HBox(24, light, darkButton), animations,
                        resetAppearance),
                card(text("数据与维护", "section-title"),
                        text("应用数据：" + output + "\n"
                                + "外观：state\\ui-settings-v1.json\n"
                                + "全局策略：state\\repair-policy-v1.json\n"
                                + "历史索引：state\\history-index-v1.json\n"
                                + "事务备份：state\\transactions-v1\\\n"
                                + "加载器图标缓存：cache\\loader-icons-v1\\\n"
                                + "检查日志：logs\\\n"
                                + "实例轮次与恢复对象：<实例>\\.maa-checker\\", "body-text"),
                        new FlowPane(10, 8, openData, manageStorage, resetPolicy)),
                card(text("关于 MAA Checker", "section-title"),
                        text("技术栈：Java 21 · JavaFX 21 · Maven\n"
                                + "MIT License\nCopyright (c) 2026 Yagen", "muted"))));
    }

    private void savePreferences() {
        UiPreferences value = new UiPreferences(dark, motion);
        Thread.ofVirtual().name("maa-ui-preferences").start(() -> {
            try {
                UiPreferences.save(output, value);
            } catch (Exception e) {
                Platform.runLater(() -> notice.setText("外观设置保存失败：" + e.getMessage()));
            }
        });
    }

    private void resetGlobalPolicy() {
        Thread.ofVirtual().name("maa-reset-policy").start(() -> {
            try (TaskService tasks = new TaskService()) {
                var result = tasks.submit("reset global policy",
                        GlobalRepairPolicy.save(output, GlobalRepairPolicy.defaults()), null)
                        .completion().toCompletableFuture().join();
                if (result.state() != TaskSnapshot.State.SUCCEEDED) {
                    throw new java.io.IOException(result.message());
                }
                Platform.runLater(() -> {
                    notice.setText("全局修复策略已恢复：全部自动，10 轮，最低兼容版本。");
                    check.reloadWorkflow();
                });
            } catch (Exception e) {
                Platform.runLater(() -> notice.setText("恢复全局策略失败：" + e.getMessage()));
            }
        });
    }

    private void openDataDirectory() {
        try {
            java.nio.file.Files.createDirectories(output);
            if (!java.awt.Desktop.isDesktopSupported()
                    || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                throw new java.io.IOException("系统不支持打开文件夹");
            }
            java.awt.Desktop.getDesktop().open(output.toFile());
        } catch (Exception e) {
            notice.setText("无法打开数据目录：" + output + "（" + e.getMessage() + "）");
        }
    }

    public boolean requestClose() {
        if (closed) return true;
        boolean waitForScan = scanning();
        boolean waitForCheck = check.busy();
        if (waitForScan || waitForCheck) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "关闭窗口将停止当前任务，并等待所属游戏进程和文件资源释放。",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.initOwner(stage);
            confirm.setHeaderText("停止当前任务并退出？");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return false;
        }
        close();
        if (waitForScan || waitForCheck) {
            root.setDisable(true);
            java.util.concurrent.CompletableFuture.allOf(
                    scanTasks.shutdown().toCompletableFuture(),
                    check.shutdown().toCompletableFuture(),
                    history.shutdown().toCompletableFuture(),
                    storage.shutdown().toCompletableFuture(),
                    loaderIcons.shutdown().toCompletableFuture()
            ).whenComplete((ignored, error) -> Platform.runLater(stage::close));
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        closed = true;
        if (transition != null) transition.stop();
        scanTasks.shutdown();
        check.shutdown();
        history.shutdown();
        storage.shutdown();
        loaderIcons.close();
    }
}
