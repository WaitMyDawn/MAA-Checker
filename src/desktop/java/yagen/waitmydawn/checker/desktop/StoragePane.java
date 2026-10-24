package yagen.waitmydawn.checker.desktop;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.task.PreparationCleanup;
import yagen.waitmydawn.checker.task.RecoveryIntegrity;
import yagen.waitmydawn.checker.task.StorageInventory;
import yagen.waitmydawn.checker.task.StorageReclaim;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskService;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static yagen.waitmydawn.checker.desktop.Ui.*;

/**
 * 存储占用与引用边界。所有扫描、摘要计算和删除都在任务线程完成，JavaFX 线程只负责
 * 展示预览结果和提交已确认的计划。
 */
final class StoragePane {
    private record ReclaimOverview(StorageReclaim.Plan orphans, List<StorageReclaim.Plan> branches) {
    }

    private final Path toolRoot;
    private final Supplier<GameInstance> instance;
    private final Runnable recoveryChanged;
    private final TaskService tasks = new TaskService();
    private final BorderPane root = new BorderPane();
    private final Label status = text("选择实例后可统计存储占用。", "muted");
    private final Label reclaimStatus = text("等待恢复资料完整性检查。", "muted");
    private final VBox overview = new VBox(12);
    private final VBox categories = new VBox(12);
    private final VBox reclaim = new VBox(12);
    private final Button refresh = button("刷新存储统计", "secondary", this::refresh);
    private final Button clean = button("清理下载缓存", "secondary", this::previewPreparation);
    private final Button orphans = button("查找可清理旧文件", "secondary", this::previewOrphans);
    private final Button openData = button("打开数据目录", "secondary", this::openDataDirectory);
    private final List<Button> dynamicActions = new ArrayList<>();
    private final RecoveryIntegrityPane integrity;
    private TaskService.Handle<?> active;
    private StorageInventory.Snapshot last;
    private StorageReclaim.Plan orphanPreview;
    private int reclaimGeneration;
    private boolean closed;

    StoragePane(Path toolRoot, Supplier<GameInstance> instance) {
        this(toolRoot, instance, () -> { });
    }

    StoragePane(Path toolRoot, Supplier<GameInstance> instance, Runnable recoveryChanged) {
        this.toolRoot = toolRoot.toAbsolutePath().normalize();
        this.instance = instance;
        this.recoveryChanged = Objects.requireNonNull(recoveryChanged);
        this.integrity = new RecoveryIntegrityPane(this.toolRoot, instance,
                this::recoveryReport, this.recoveryChanged);
        root.setId("storage-page");
        status.setId("storage-status");
        reclaimStatus.setId("storage-reclaim-status");
        overview.setId("storage-overview");
        categories.setId("storage-categories");
        reclaim.setId("storage-reclaim");
        refresh.setId("refresh-storage");
        clean.setId("clean-storage");
        orphans.setId("preview-orphans");
        openData.setId("open-storage-directory");
        clean.setDisable(true);
        orphans.setDisable(true);

        TitledPane advanced = new TitledPane("高级：详细数据分类", categories);
        advanced.setId("storage-advanced");
        advanced.setExpanded(false);
        VBox content = new VBox(18,
                new VBox(8, text("存储", "caption"), text("清理缓存与旧记录", "page-title"),
                        text("这里只管理 MAA-Checker 自己生成的数据，不会删除世界、存档或正常模组。", "muted")),
                new HBox(12, status, spacer(), openData, clean, orphans, refresh),
                overview,
                integrity.root(),
                reclaimStatus,
                reclaim,
                advanced);
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
        GameInstance selected = instance.get();
        if (closed || busy()) return;
        if (selected == null) {
            status.setText("请先在实例页选择一个实例；全局索引本身不需要清理。");
            categories.getChildren().setAll(card(text("尚未选择实例", "section-title"),
                    text("存储分类以选定实例为边界，避免把其他实例的恢复数据混在一起。", "muted")));
            overview.getChildren().clear();
            reclaim.getChildren().clear();
            clean.setDisable(true);
            orphans.setDisable(true);
            integrity.clear();
            reclaimStatus.setText("等待恢复资料完整性检查。");
            return;
        }
        status.setText("正在后台统计存储占用…");
        reclaimStatus.setText("等待存储统计与完整性复核。");
        reclaim.getChildren().clear();
        setBusy(true);
        categories.getChildren().setAll(text("正在读取元数据与持久化台账…", "muted"));
        var current = tasks.submit(selected.name(), context ->
                TaskResult.success(StorageInventory.inspect(toolRoot, selected), "存储统计完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("存储统计失败：" + failure(error, result));
                return;
            }
            render(result.value());
            integrity.refresh();
        }));
    }

    private void recoveryReport(RecoveryIntegrity.Report report) {
        GameInstance selected = instance.get();
        if (closed || selected == null || !selected.equals(report.instance())) return;
        if (!report.healthy()) {
            String reason = report.needsReassociation()
                    ? "先完成恢复资料重新关联，再复核和处理存储回收。"
                    : "恢复资料仍有损坏或缺失；修复原记录后再复核存储回收。";
            reclaim.getChildren().setAll(card(text("回收暂不可用", "section-title"),
                    text(reason, "badge-warning")));
            reclaimStatus.setText("回收暂不可用：先处理恢复资料问题。");
            orphans.setDisable(true);
            return;
        }
        loadReclaim(selected);
    }

    private void render(StorageInventory.Snapshot snapshot) {
        last = snapshot;
        status.setText("实例 " + snapshot.instance().name() + " · 本工具数据占用 "
                + bytes(snapshot.diskBytes()));
        clean.setDisable(snapshot.cleanableBytes() == 0);
        overview.getChildren().setAll(
                card(text("当前占用", "section-title"),
                        new FlowPane(10, 8,
                                text("总计 " + bytes(snapshot.diskBytes()), "badge"),
                                text("可直接清理 " + bytes(snapshot.cleanableBytes()), "badge"),
                                text("需要预览确认 " + bytes(snapshot.candidateBytes()), "badge-warning"),
                                text("受恢复记录保护 " + bytes(snapshot.protectedBytes()), "tag")),
                        text("“可直接清理”通常只是下载或安装临时文件。"
                                + "“需要预览确认”会先列出文件，不会直接删除。", "body-text")),
                card(text("推荐怎么清理", "section-title"),
                        text("1. 先点「清理下载缓存」，删除下载和安装临时文件。\n"
                                + "2. 再点「查找可清理旧文件」，确认没有恢复价值后删除。\n"
                                + "3. 历史记录确认不再还原后，可从下方删除旧轮次。", "body-text"),
                        text("不会清理：世界与存档、账号、资源文件、正常模组、共享 libraries、"
                                + "仍被轮次或恢复凭据引用的数据。", "safe-callout")),
                card(text("数据在哪里", "section-title"),
                        text("应用数据：" + toolRoot + "\n"
                                + "外观/策略/历史索引：" + toolRoot.resolve("state") + "\n"
                                + "加载器图标缓存：" + toolRoot.resolve("cache/loader-icons-v1") + "\n"
                                + "日志与报告：" + toolRoot.resolve("logs") + "\n"
                                + "实例轮次和恢复对象："
                                + snapshot.instance().dir().resolve(".maa-checker"), "caption")));

        categories.getChildren().clear();
        categories.getChildren().add(text(
                "以下为技术分类明细，仅供排查。普通清理无需展开。", "muted"));
        for (StorageInventory.Category category : snapshot.categories()) {
            if (category.files() == 0) continue;
            Label state = text(stateName(category.state()),
                    category.state() == StorageInventory.State.UNKNOWN
                            || category.state() == StorageInventory.State.CANDIDATE
                            ? "badge-warning" : "badge");
            VBox card = card(new HBox(12, text(category.title(), "section-title"), spacer(), state),
                    text(category.detail(), "body-text"),
                    new FlowPane(10, 8,
                            text("物理 " + bytes(category.diskBytes()), "tag"),
                            text("逻辑 " + bytes(category.logicalBytes()), "tag"),
                            text(category.files() + " 个文件", "tag")));
            if (category.cleanableBytes() > 0) card.getChildren().add(text(
                    "可立即清理 " + bytes(category.cleanableBytes()), "safe-callout"));
            if (category.candidateBytes() > 0) card.getChildren().add(text(
                    "可由引用复核回收 " + bytes(category.candidateBytes()), "muted"));
            if (category.unknownBytes() > 0) card.getChildren().add(text(
                    "未知占用，保留且禁止清理 " + bytes(category.unknownBytes()), "badge-warning"));
            card.setId("storage-" + category.id());
            categories.getChildren().add(card);
        }
        if (!snapshot.issues().isEmpty()) {
            TitledPane issues = new TitledPane("统计提示", text(String.join("\n", snapshot.issues()), "muted"));
            issues.setExpanded(false);
            categories.getChildren().add(issues);
        }
    }

    private void loadReclaim(GameInstance selected) {
        if (closed || busy() || !selected.equals(instance.get())) return;
        dynamicActions.clear();
        reclaimStatus.setText("正在复核恢复对象、事务备份和历史分支引用…");
        reclaim.getChildren().setAll(text("正在复核恢复对象、事务备份和历史分支引用…", "muted"));
        setBusy(true);
        var current = tasks.submit("reclaim overview", context -> TaskResult.success(
                new ReclaimOverview(
                        StorageReclaim.previewOrphans(toolRoot, selected, context::checkpoint),
                        StorageReclaim.previewBranches(toolRoot, selected, context::checkpoint)),
                "回收引用复核完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                reclaimStatus.setText("引用复核失败。");
                reclaim.getChildren().setAll(card(text("回收引用复核失败", "section-title"),
                        text(failure(error, result), "muted")));
                orphans.setDisable(true);
                return;
            }
            renderReclaim(selected, result.value());
        }));
    }

    private void renderReclaim(GameInstance selected, ReclaimOverview overview) {
        orphanPreview = null;
        reclaimStatus.setText("引用复核完成 · 第 " + (++reclaimGeneration) + " 次。");
        reclaim.getChildren().clear();
        reclaim.getChildren().add(new VBox(6,
                text("旧文件与旧轮次", "section-title"),
                text("先查看清单，再决定是否删除。仍被恢复记录使用的数据不会出现在可删除清单中。",
                        "muted")));

        StorageReclaim.Plan orphanPlan = overview.orphans();
        boolean residual = orphanPlan.files().stream()
                .anyMatch(item -> item.label().equals("上次清理残留"));
        VBox orphanCard = card(
                new HBox(12, text("无引用的旧文件", "section-title"), spacer(),
                        text(orphanPlan.files().isEmpty() ? "无需清理" : "可回收",
                                orphanPlan.files().isEmpty() ? "badge" : "badge-warning")),
                text(orphanPlan.reason(), "body-text"),
                new FlowPane(10, 8,
                        text(orphanPlan.files().size() + " 个文件", "tag"),
                        text(bytes(orphanPlan.bytes()), "tag"),
                        residual ? text("包含上次中断残留", "badge-warning") : text("无中断残留", "tag")));
        orphans.setDisable(orphanPlan.files().isEmpty());
        orphanCard.setId("storage-orphans");
        reclaim.getChildren().add(orphanCard);

        reclaim.getChildren().add(new VBox(6,
                text("旧轮次记录", "section-title"),
                text("只显示可以放弃的旧记录。删除后对应还原点会消失；正在使用或共享的记录保持保护状态。", "muted")));
        if (overview.branches().isEmpty()) {
            reclaim.getChildren().add(text("当前实例没有历史分支。", "muted"));
            return;
        }
        for (int index = 0; index < overview.branches().size(); index++) {
            StorageReclaim.Plan branch = overview.branches().get(index);
            VBox lost = new VBox(6);
            branch.lostPoints().forEach(point -> lost.getChildren().add(text(point, "body-text")));
            TitledPane points = new TitledPane("将失去的还原点（" + branch.lostPoints().size() + "）", lost);
            points.setExpanded(false);
            Button preview = button("预览回收", "secondary", () -> previewBranch(selected, branch));
            preview.setId("preview-branch-" + index);
            preview.setDisable(!branch.executable());
            dynamicActions.add(preview);
            VBox branchCard = card(
                    new HBox(12, text("历史分支 " + shortId(branch.branch()), "section-title"), spacer(),
                            text(branch.executable() ? "可回收" : "受保护",
                                    branch.executable() ? "badge-warning" : "badge")),
                    text(branch.reason(), "body-text"),
                    new FlowPane(10, 8,
                            text(branch.rounds() + " 轮", "tag"),
                            text(branch.files().size() + " 个文件", "tag"),
                            text(bytes(branch.bytes()), "tag")),
                    points, preview);
            branchCard.setId("storage-branch-" + index);
            reclaim.getChildren().add(branchCard);
        }
    }

    private void previewPreparation() {
        GameInstance selected = instance.get();
        if (closed || busy() || selected == null || last == null || last.cleanableBytes() == 0) return;
        status.setText("正在核对准备缓存…");
        setBusy(true);
        var current = tasks.submit("preview preparation cleanup", context ->
                PreparationCleanup.preview(selected).run(context), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("准备缓存预览失败：" + failure(error, result));
                return;
            }
            var preview = result.value();
            Button confirm = button("确认清理 " + preview.files().size() + " 个文件", "danger",
                    () -> clearPreparation(selected, preview));
            confirm.setId("confirm-clean-storage");
            dynamicActions.add(confirm);
            VBox previewCard = card(text("准备缓存清理预览", "section-title"),
                    text(preview.files().size() + " 个文件 · " + bytes(preview.bytes())
                            + "\n只删除准备目录；快照、事务、运行库和报告不在此清单。", "body-text"),
                    confirm);
            previewCard.setId("storage-clean-preview");
            categories.getChildren().add(0, previewCard);
        }));
    }

    private void clearPreparation(GameInstance selected, PreparationCleanup.Preview preview) {
        if (closed || busy()) return;
        status.setText("正在清理准备缓存…");
        setBusy(true);
        var current = tasks.submit("clear preparation cleanup", context ->
                PreparationCleanup.clear(selected, preview).run(context), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("准备缓存清理失败：" + failure(error, result));
                return;
            }
            status.setText(result.message());
            refresh();
        }));
    }

    private void previewOrphans() {
        GameInstance selected = instance.get();
        if (closed || busy() || selected == null) return;
        status.setText("正在生成孤儿数据回收预览…");
        setBusy(true);
        var current = tasks.submit("preview orphan reclaim", context ->
                TaskResult.success(StorageReclaim.previewOrphans(
                        toolRoot, selected, context::checkpoint), "孤儿回收预览完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("孤儿数据预览失败：" + failure(error, result));
                return;
            }
            StorageReclaim.Plan plan = result.value();
            if (!plan.executable()) {
                status.setText(plan.reason());
                loadReclaim(selected);
                return;
            }
            orphanPreview = plan;
            status.setText("孤儿数据预览已生成：删除前请确认文件数与体积。");
            Button confirm = button("确认清理 " + plan.files().size() + " 个文件", "danger",
                    () -> cleanOrphans(selected));
            confirm.setId("confirm-clean-orphans");
            dynamicActions.add(confirm);
            VBox preview = card(text("孤儿数据清理预览", "section-title"),
                    text(plan.reason() + "\n" + plan.files().size() + " 个文件 · " + bytes(plan.bytes())
                            + "\n删除前将重新核对路径、时间戳和 SHA-256。", "body-text"),
                    confirm);
            preview.setId("storage-orphan-preview");
            reclaim.getChildren().add(0, preview);
        }));
    }

    private void cleanOrphans(GameInstance selected) {
        if (closed || busy() || orphanPreview == null) return;
        StorageReclaim.Plan preview = orphanPreview;
        orphanPreview = null;
        status.setText("正在清理孤儿数据…");
        setBusy(true);
        var current = tasks.submit("clean orphan reclaim", context ->
                TaskResult.success(StorageReclaim.cleanOrphans(toolRoot, selected, preview,
                        context::checkpoint), "孤儿数据清理完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("孤儿数据清理失败：" + failure(error, result) + "；请重新预览后处理残留");
                loadReclaim(selected);
                return;
            }
            status.setText(result.value().message());
            refresh();
        }));
    }

    private void previewBranch(GameInstance selected, StorageReclaim.Plan summary) {
        if (closed || busy() || summary.branch() == null) return;
        status.setText("正在核对历史分支回收清单…");
        setBusy(true);
        var current = tasks.submit("preview branch reclaim", context -> TaskResult.success(
                StorageReclaim.previewBranch(toolRoot, selected, summary.branch(), context::checkpoint),
                "历史分支回收预览完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("历史分支预览失败：" + failure(error, result));
                return;
            }
            StorageReclaim.Plan plan = result.value();
            if (!plan.executable()) {
                status.setText(plan.reason());
                loadReclaim(selected);
                return;
            }
            status.setText("历史分支回收预览已生成：删除将永久失去 "
                    + plan.lostPoints().size() + " 个还原点。");
            Button confirm = button("确认删除分支并失去还原点", "danger",
                    () -> deleteBranch(selected, plan));
            confirm.setId("confirm-delete-branch");
            dynamicActions.add(confirm);
            VBox lost = new VBox(6);
            plan.lostPoints().forEach(point -> lost.getChildren().add(text(point, "body-text")));
            VBox preview = card(text("历史分支回收预览", "section-title"),
                    text(plan.reason() + "\n" + plan.files().size() + " 个文件 · " + bytes(plan.bytes()), "body-text"),
                    new TitledPane("删除后失去的还原点（" + plan.lostPoints().size() + "）", lost),
                    confirm);
            preview.setId("storage-branch-preview");
            reclaim.getChildren().add(0, preview);
        }));
    }

    private void deleteBranch(GameInstance selected, StorageReclaim.Plan preview) {
        if (closed || busy()) return;
        status.setText("正在删除历史分支并回收关联事务…");
        setBusy(true);
        var current = tasks.submit("delete branch reclaim", context ->
                TaskResult.success(StorageReclaim.deleteBranch(toolRoot, selected, preview,
                        context::checkpoint), "历史分支回收完成"), null);
        active = current;
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            setBusy(false);
            if (!selected.equals(instance.get())) return;
            if (error != null || result.state() != TaskSnapshot.State.SUCCEEDED) {
                status.setText("历史分支回收失败：" + failure(error, result)
                        + "；已保留可继续处理的回收残留，请重新预览");
                loadReclaim(selected);
                return;
            }
            status.setText(result.value().message());
            refresh();
        }));
    }

    private void setBusy(boolean busy) {
        refresh.setDisable(busy);
        openData.setDisable(busy);
        clean.setDisable(busy || last == null || last.cleanableBytes() == 0);
        if (busy) orphans.setDisable(true);
        dynamicActions.forEach(button -> button.setDisable(busy));
        integrity.setAvailable(!busy);
    }

    private boolean busy() {
        return active != null && !active.snapshot().state().terminal();
    }

    private static String failure(Throwable error, TaskResult<?> result) {
        return Objects.toString(error == null || error.getMessage() == null
                ? result == null ? null : result.message()
                : error.getMessage(), "未知错误");
    }

    private static String shortId(java.util.UUID id) {
        String value = id.toString();
        return value.substring(0, 8);
    }

    private static String stateName(StorageInventory.State state) {
        return switch (state) {
            case CLEANABLE -> "可立即清理";
            case CANDIDATE -> "等待引用复核";
            case PROTECTED -> "受引用保护";
            case UNKNOWN -> "未知，禁止清理";
        };
    }

    private void openDataDirectory() {
        try {
            java.nio.file.Files.createDirectories(toolRoot);
            if (!java.awt.Desktop.isDesktopSupported()
                    || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                throw new java.io.IOException("系统不支持打开文件夹");
            }
            java.awt.Desktop.getDesktop().open(toolRoot.toFile());
        } catch (Exception e) {
            status.setText("无法打开数据目录：" + e.getMessage());
        }
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GiB", value / 1073741824.0);
    }

    CompletionStage<Void> shutdown() {
        closed = true;
        return CompletableFuture.allOf(tasks.shutdown().toCompletableFuture(),
                integrity.shutdown().toCompletableFuture());
    }
}
