package yagen.waitmydawn.checker.desktop;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.task.RecoveryIntegrity;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static yagen.waitmydawn.checker.desktop.Ui.*;

/** 恢复资料完整性、实例移动候选和显式重新关联的工作线程适配。 */
final class RecoveryIntegrityPane {
    private final Path toolRoot;
    private final Supplier<GameInstance> instance;
    private final Consumer<RecoveryIntegrity.Report> reportSink;
    private final Runnable changed;
    private final TaskService tasks = new TaskService();
    private final VBox root = new VBox(12);
    private final Label status = text("选择实例后检查恢复资料完整性。", "muted");
    private final VBox content = new VBox(12);
    private final Button refresh = button("重新检查完整性", "secondary", this::refresh);
    private final List<Button> dynamicActions = new ArrayList<>();
    private TaskService.Handle<?> active;
    private boolean closed;

    RecoveryIntegrityPane(Path toolRoot, Supplier<GameInstance> instance,
                          Consumer<RecoveryIntegrity.Report> reportSink, Runnable changed) {
        this.toolRoot = toolRoot.toAbsolutePath().normalize();
        this.instance = Objects.requireNonNull(instance);
        this.reportSink = Objects.requireNonNull(reportSink);
        this.changed = Objects.requireNonNull(changed);
        root.setId("recovery-integrity");
        status.setId("recovery-status");
        content.setId("recovery-content");
        refresh.setId("refresh-recovery");
        root.getChildren().addAll(
                new HBox(12, new VBox(5, text("恢复资料完整性", "section-title"),
                        text("实例身份仍绑定原路径；移动后必须显式重新关联。", "muted")),
                        spacer(), refresh),
                status, content);
    }

    Node root() {
        return root;
    }

    void clear() {
        if (closed || busy()) return;
        status.setText("选择实例后检查恢复资料完整性。");
        content.getChildren().clear();
        refresh.setDisable(true);
    }

    void setAvailable(boolean available) {
        root.setDisable(!available);
    }

    void refresh() {
        GameInstance selected = instance.get();
        if (closed || busy()) return;
        if (selected == null) {
            clear();
            return;
        }
        status.setText("正在核对工作流身份、事务账本与轮前对象…");
        setBusy(true);
        content.getChildren().setAll(text("正在后台检查恢复资料；不会修改实例。", "muted"));
        var current = tasks.submit("recovery integrity", context -> TaskResult.success(
                RecoveryIntegrity.inspect(toolRoot, selected, context::checkpoint),
                "恢复资料完整性检查完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("完整性检查失败：" + failure(error, result));
                content.getChildren().setAll(card(text("无法完成完整性检查", "section-title"),
                        text(failure(error, result)
                                + "\n不会自动猜测或改写恢复资料；保留原文件后重试。", "body-text")));
                return;
            }
            render(selected, result.value());
            reportSink.accept(result.value());
        }));
    }

    private void render(GameInstance selected, RecoveryIntegrity.Report report) {
        content.getChildren().clear();
        status.setText(report.needsReassociation()
                ? "发现 " + report.candidates().size() + " 个旧身份候选；必须显式确认后才能迁移"
                : report.hasErrors() ? "发现需要人工处理的恢复资料问题"
                : "当前实例恢复资料检查通过");
        FlowPane summary = new FlowPane(10, 8,
                text("当前工作流 " + report.currentWorkflows(), "tag"),
                text("旧身份工作流 " + report.movedWorkflows(), "tag"),
                text("损坏工作流 " + report.corruptWorkflows(),
                        report.corruptWorkflows() > 0 ? "badge-warning" : "tag"),
                text("当前事务 " + report.currentTransactions(), "tag"),
                text("缺失快照对象 " + report.missingObjects(),
                        report.missingObjects() > 0 ? "badge-warning" : "tag"));
        content.getChildren().add(card(text("完整性摘要", "section-title"), summary));

        int candidateIndex = 0;
        for (RecoveryIntegrity.Candidate candidate : report.candidates()) {
            content.getChildren().add(candidateCard(selected, candidate, candidateIndex++));
        }
        for (RecoveryIntegrity.Finding finding : report.findings()) {
            content.getChildren().add(findingCard(finding));
        }
        VBox scope = new VBox(6);
        report.snapshotScope().forEach(value -> scope.getChildren().add(text(value, "body-text")));
        TitledPane snapshotScope = new TitledPane("轮前快照范围", scope);
        snapshotScope.setExpanded(false);
        content.getChildren().add(snapshotScope);
    }

    private VBox candidateCard(GameInstance selected, RecoveryIntegrity.Candidate candidate,
                               int index) {
        Label state = text(candidate.executable() ? "可预览" : "受阻断",
                candidate.executable() ? "badge-warning" : "badge");
        VBox details = new VBox(8,
                new HBox(12, text("旧身份 " + shortId(candidate.source().id()), "section-title"),
                        spacer(), state),
                text(candidate.source().directory() + "\n→ " + selected.dir().toString(), "caption"),
                text(candidate.reason(), "body-text"),
                new FlowPane(10, 8,
                        text(candidate.workflows().size() + " 个工作流", "tag"),
                        text(candidate.transactions().size() + " 个事务", "tag"),
                        text(candidate.rounds() + " 轮快照", "tag")));
        if (!candidate.warnings().isEmpty()) {
            details.getChildren().add(text(String.join("\n", candidate.warnings()), "muted"));
        }
        Button preview = button("预览重新关联", "secondary",
                () -> preview(selected, candidate));
        preview.setId("preview-reassociation-" + index);
        preview.setDisable(!candidate.executable());
        dynamicActions.add(preview);
        details.getChildren().add(preview);
        details.setId("recovery-candidate-" + index);
        return card(details);
    }

    private VBox findingCard(RecoveryIntegrity.Finding finding) {
        String style = switch (finding.severity()) {
            case ERROR -> "badge-warning";
            case WARNING -> "badge-warning";
            case INFO -> "tag";
        };
        return card(new HBox(12, text(finding.title(), "section-title"), spacer(),
                        text(finding.severity().name(), style)),
                text(finding.detail(), "body-text"),
                text("下一步：" + finding.nextStep(), "muted"));
    }

    private void preview(GameInstance selected, RecoveryIntegrity.Candidate candidate) {
        if (closed || busy()) return;
        status.setText("正在生成恢复资料重新关联预览…");
        setBusy(true);
        var current = tasks.submit("preview recovery reassociation", context -> TaskResult.success(
                RecoveryIntegrity.preview(toolRoot, selected, candidate.source().id(),
                        context::checkpoint), "重新关联预览完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("重新关联预览失败：" + failure(error, result));
                return;
            }
            RecoveryIntegrity.Plan plan = result.value();
            if (!plan.executable()) {
                status.setText(plan.reason());
                refresh();
                return;
            }
            status.setText("重新关联预览已生成：旧事务只复制，原目录保留；快照内容对象不删除。");
            Button confirm = button("确认重新关联恢复资料", "danger",
                    () -> reassociate(selected, plan));
            confirm.setId("confirm-reassociation");
            dynamicActions.add(confirm);
            VBox preview = card(text("重新关联预览", "section-title"),
                    text(plan.source().directory() + "\n→ " + plan.target().directory(), "caption"),
                    new FlowPane(10, 8,
                            text(plan.workflows().size() + " 个工作流", "tag"),
                            text(plan.transactions().size() + " 个事务", "tag"),
                            text(plan.rounds() + " 轮", "tag")),
                    text("执行时会重新计算完整性摘要；旧工作流和事务在成功写入前保持不变。", "body-text"),
                    confirm);
            preview.setId("reassociation-preview");
            content.getChildren().add(0, preview);
        }));
    }

    private void reassociate(GameInstance selected, RecoveryIntegrity.Plan plan) {
        if (closed || busy()) return;
        status.setText("正在重新关联恢复资料…");
        setBusy(true);
        var current = tasks.submit("reassociate recovery data", context -> TaskResult.success(
                RecoveryIntegrity.reassociate(toolRoot, selected, plan, context::checkpoint),
                "恢复资料重新关联完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("重新关联失败：" + failure(error, result)
                        + "；旧身份资料仍保留，请重新检查后继续");
                refresh();
                return;
            }
            String suffix = result.value().warnings().isEmpty()
                    ? "" : "；" + String.join("；", result.value().warnings());
            status.setText(result.value().message() + suffix);
            changed.run();
            refresh();
        }));
    }

    private void setBusy(boolean busy) {
        refresh.setDisable(busy);
        dynamicActions.forEach(button -> button.setDisable(busy));
    }

    private boolean busy() {
        return active != null && !active.snapshot().state().terminal();
    }

    private static String failure(Throwable error, TaskResult<?> result) {
        return Objects.toString(error == null || error.getMessage() == null
                ? result == null ? null : result.message() : error.getMessage(), "未知错误");
    }

    private static String shortId(String value) {
        return value == null || value.length() < 8 ? Objects.toString(value, "-") : value.substring(0, 8);
    }

    CompletionStage<Void> shutdown() {
        closed = true;
        return tasks.shutdown();
    }
}
