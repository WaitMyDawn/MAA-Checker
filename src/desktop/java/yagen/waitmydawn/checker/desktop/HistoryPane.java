package yagen.waitmydawn.checker.desktop;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskSnapshot;
import yagen.waitmydawn.checker.task.WorkflowHistoryIndex;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static yagen.waitmydawn.checker.desktop.Ui.*;

/** 真实历史发现页；记录正文始终来自实例工作流台账。 */
final class HistoryPane {
    @FunctionalInterface
    interface Loader {
        WorkflowHistoryIndex.Snapshot load() throws Exception;
    }

    private final Supplier<Path> gameDirectory;
    private final Consumer<WorkflowHistoryIndex.Entry> open;
    private final Loader loader;
    private final TaskService tasks = new TaskService();
    private final BorderPane root = new BorderPane();
    private final Label status = text("正在读取历史记录…", "muted");
    private final VBox records = new VBox(12);
    private final Button refresh = button("刷新历史", "secondary", this::refresh);
    private TaskService.Handle<WorkflowHistoryIndex.Snapshot> active;
    private boolean closed;

    HistoryPane(Path toolRoot, Supplier<Path> gameDirectory,
                Consumer<WorkflowHistoryIndex.Entry> open) {
        this(toolRoot, gameDirectory, open,
                () -> WorkflowHistoryIndex.load(toolRoot, gameDirectory.get()));
    }

    HistoryPane(Path toolRoot, Supplier<Path> gameDirectory,
                Consumer<WorkflowHistoryIndex.Entry> open, Loader loader) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory);
        this.open = Objects.requireNonNull(open);
        this.loader = Objects.requireNonNull(loader);
        status.setId("history-status");
        records.setId("history-list");
        refresh.setId("refresh-history");
        root.setId("global-history");

        VBox content = new VBox(18,
                new VBox(8, text("本地记录", "caption"), text("实例与会话历史", "page-title"),
                        text("索引只负责发现实例；结果、轮次和可还原动作始终从当前持久化台账读取。", "muted")),
                new HBox(12, status, spacer(), refresh),
                records);
        content.getStyleClass().add("page-body");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        root.setCenter(scroll);
    }

    Node root() {
        return root;
    }

    void refresh() {
        if (closed || (active != null && !active.snapshot().state().terminal())) return;
        status.setText("正在读取历史记录…");
        refresh.setDisable(true);
        records.getChildren().setAll(text("正在扫描已知实例和会话台账…", "muted"));
        var current = tasks.submit(gameDirectory.get() == null ? "历史索引"
                        : gameDirectory.get().toString(),
                context -> {
                    context.phase(TaskSnapshot.Phase.SCANNING, "正在读取实例索引与工作流台账");
                    return TaskResult.success(loader.load(), "历史已读取");
                }, null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            refresh.setDisable(false);
            if (error != null) {
                status.setText("历史读取失败：" + error.getMessage());
                records.getChildren().setAll(card(text("无法读取历史", "badge-warning"),
                        text(Objects.toString(error.getMessage(), error.getClass().getSimpleName()), "body-text")));
                return;
            }
            if (result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText(result.message());
                return;
            }
            render(result.value());
        }));
    }

    private void render(WorkflowHistoryIndex.Snapshot snapshot) {
        status.setText(snapshot.entries().isEmpty()
                ? "尚无可展示的会话"
                : "共 " + snapshot.entries().size() + " 条记录");
        records.getChildren().clear();
        if (!snapshot.problems().isEmpty()) {
            records.getChildren().add(card(text("索引提示", "section-title"),
                    text(String.join("\n", snapshot.problems()), "body-text")));
        }
        if (snapshot.entries().isEmpty()) {
            records.getChildren().add(card(text("还没有会话记录", "section-title"),
                    text("完成一次检查并修复后，轮次与会话会出现在这里。仅检查报告仍可在实例任务页查看。", "muted")));
            return;
        }
        int index = 0;
        for (WorkflowHistoryIndex.Entry entry : snapshot.entries()) {
            records.getChildren().add(entryCard(entry, index++));
        }
    }

    private VBox entryCard(WorkflowHistoryIndex.Entry entry, int index) {
        Label state = text(statusName(entry), entry.unfinished() || entry.status() != WorkflowHistoryIndex.Status.AVAILABLE
                ? "badge-warning" : "badge");
        String time = entry.createdAt() <= 0 ? "时间未知" : Instant.ofEpochMilli(entry.createdAt())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        VBox info = new VBox(8,
                new HBox(12, text(entry.instanceName(), "section-title"), spacer(), state),
                text(time + " · " + Objects.toString(entry.message(), ""), "body-text"),
                text(entry.instanceDirectory().toString(), "caption"));
        if (entry.status() == WorkflowHistoryIndex.Status.AVAILABLE) {
            info.getChildren().add(text("轮次 " + entry.rounds() + " · " + entry.operationSummary(), "muted"));
            if (entry.workflowId() != null) {
                info.getChildren().add(text("会话 " + entry.workflowId()
                        + (entry.report().isBlank() ? "" : "\n报告 " + entry.report()), "caption"));
            }
        } else {
            info.getChildren().add(text(Objects.toString(entry.problem(), "记录不可用"), "badge-warning"));
        }

        Button openButton = button(entry.unfinished() ? "继续并查看会话" : "打开会话、还原与详情",
                entry.unfinished() ? "primary" : "secondary", () -> open.accept(entry));
        openButton.setId("history-open-" + index);
        openButton.setDisable(!entry.canOpen());
        VBox card = card(info, openButton);
        card.setId("history-entry-" + index);
        return card;
    }

    private static String statusName(WorkflowHistoryIndex.Entry entry) {
        return switch (entry.status()) {
            case AVAILABLE -> entry.phase() == null ? "记录有效"
                    : UiStatus.workflow(entry.phase(), entry.unfinished()).title();
            case MOVED -> "实例已移动";
            case UNAVAILABLE -> "路径不可用";
            case CORRUPT -> "记录损坏";
        };
    }

    CompletionStage<Void> shutdown() {
        closed = true;
        return tasks.shutdown();
    }
}
