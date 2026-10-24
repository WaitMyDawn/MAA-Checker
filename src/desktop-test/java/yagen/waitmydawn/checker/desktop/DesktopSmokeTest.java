package yagen.waitmydawn.checker.desktop;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import yagen.waitmydawn.checker.task.TaskResult;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实 JavaFX 图形工具包上验证导航、取消、失败和主题，截图写入构建目录。 */
class DesktopSmokeTest {
    private static final long FX_STARTUP_TIMEOUT_SECONDS = 30;
    private static final long FX_CALL_TIMEOUT_SECONDS = 30;
    private static final long FX_POLL_TIMEOUT_SECONDS = 10;
    private static final long UI_STATUS_TIMEOUT_SECONDS = 30;
    private static final long SCAN_STATUS_TIMEOUT_SECONDS = 30;

    private DesktopShell shell;
    private Stage stage;
    private CheckPane isolatedCheck;

    @Test
    void 桌面主流程与响应布局可运行(@TempDir Path fixture) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(FX_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "JavaFX 启动超时");
        Platform.setImplicitExit(false);
        try {
            fx(() -> {
                stage = new Stage();
                shell = new DesktopShell(stage, fixture.resolve("tool"));
                Scene scene = new Scene(shell.root(), 1440, 940);
                scene.getStylesheets().add(CheckerDesktop.class.getResource("desktop.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
            });
            fx(() -> {
                assertNull(shell.root().lookup("#repair-preview"), "正式流程不应包含演示入口");
                assertNull(shell.root().lookup("#start-demo"), "正式流程不应包含演示任务");
                snapshot("01-instances-light");
                fire("nav-历史记录");
                assertFalse(shell.root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l
                        && l.getText().equals("演示失败")));
            });
            awaitLabel(shell.root(), "history-status", "尚无可展示的会话");
            fx(() -> {
                assertNotNull(shell.root().lookup("#global-history"));
                snapshot("02-history-boundary");
                assertEquals(1120, CheckerDesktop.DEFAULT_WIDTH);
                assertEquals(720, CheckerDesktop.DEFAULT_HEIGHT);
                fire("nav-设置");
                assertNull(shell.root().lookupAll(".label").stream()
                        .filter(node -> node instanceof Label label
                                && label.getText().contains("真实能力范围"))
                        .findFirst().orElse(null));
                assertNull(shell.root().lookupAll(".label").stream()
                        .filter(node -> node instanceof Label label
                                && !label.getStyleClass().contains("notice")
                                && label.getText().contains("当前阶段"))
                        .findFirst().orElse(null));
                assertTrue(shell.root().lookupAll(".label").stream()
                        .anyMatch(node -> node instanceof Label label
                                && label.getText().contains("MIT License")
                                && label.getText().contains("Copyright (c) 2026 Yagen")));
                ((RadioButton) shell.root().lookup("#theme-dark")).fire();
                snapshot("02b-settings-about");
                assertTrue(shell.root().getStyleClass().contains("dark"));
                fire("nav-当前任务");
                assertNotNull(shell.root().lookup("#start-check"));
                assertNotNull(shell.root().lookup("#start-repair"));
                snapshot("03-task-dark");
                stage.setWidth(920);
                stage.setHeight(700);
            });
            fx(() -> snapshot("04-task-compact"));
            Path version = Files.createDirectories(fixture.resolve("versions/ReadonlySample"));
            Path metadata = version.resolve("ReadonlySample.json");
            String json = "{\"id\":\"ReadonlySample\",\"inheritsFrom\":\"1.21.1\",\"mainClass\":\"net.minecraft.client.main.Main\",\"libraries\":[]}";
            Files.writeString(metadata, json);
            fx(() -> shell.scanDirectory(fixture));
            long scanDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(SCAN_STATUS_TIMEOUT_SECONDS);
            boolean scanned = false;
            while (System.nanoTime() < scanDeadline) {
                FutureTask<Boolean> result = new FutureTask<>(() -> {
                    shell.root().applyCss();
                    return shell.root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().contains("识别到 1 个实例"));
                });
                Platform.runLater(result);
                if (result.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { scanned = true; break; }
                Thread.sleep(50);
            }
            assertTrue(scanned, "后台只读扫描未完成");
            assertEquals(json, Files.readString(metadata));
            try (var files = Files.walk(version)) {
                assertEquals(1, files.filter(Files::isRegularFile).count(), "扫描不应修改实例目录");
            }
            assertTrue(Files.isRegularFile(fixture.resolve("tool/state/history-index-v1.json")),
                    "扫描应更新工具自己的历史发现索引");
            fx(() -> {
                fire("nav-实例");
                TextField search = (TextField) shell.root().lookup("#instance-search");
                search.setText("不存在");
                assertFalse(shell.root().lookupAll(".label").stream()
                        .anyMatch(n -> n instanceof Label l && l.getText().equals("ReadonlySample")));
                search.setText("Readonly");
                assertTrue(shell.root().lookupAll(".label").stream()
                        .anyMatch(n -> n instanceof Label l && l.getText().equals("ReadonlySample")));
                FlowPane tags = (FlowPane) shell.root().lookup("#instance-tags-0");
                assertNotNull(tags);
                assertEquals(10_000, tags.getPrefWrapLength());
                StackPane badge = (StackPane) shell.root().lookup("#loader-badge-0");
                assertNotNull(badge);
                assertEquals("MC", ((Label) badge.getChildren().getFirst()).getText());
                Button select = (Button) shell.root().lookup("#inspect-instance-0");
                assertEquals("选择", select.getText());
                assertEquals(76, select.getMinWidth());
                snapshot("05-readonly-scan");
            });
            CountDownLatch scanStarted = new CountDownLatch(1);
            fx(() -> shell.scanDirectory(fixture, context -> {
                scanStarted.countDown();
                Thread.sleep(60_000);
                return TaskResult.success(java.util.List.of(), "不应完成");
            }));
            assertTrue(scanStarted.await(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            fx(() -> fire("cancel-scan"));
            awaitScanMessage("已停止扫描");
            fx(() -> shell.scanDirectory(fixture, context -> {
                throw new java.io.IOException("fixture read denied");
            }));
            awaitScanMessage("fixture read denied");
            fx(() -> {
                assertTrue(((Label) shell.root().lookup("#scan-status")).getText().contains("失败"));
                snapshot("06-scan-failure");
            });
            verifyRealChecks(fixture.resolve("real-checks"));
            verifyRepairWorkflow(fixture.resolve("repair-game"));
            verifyLoaderWorkflow(fixture.resolve("loader-game"));
            verifyGovernedWorkflow(fixture.resolve("governed-game"));
            verifyPolicyLoading(fixture.resolve("policy-game"));
            verifyGlobalHistory(fixture.resolve("history-game"));
            verifyMovedRecovery(fixture.resolve("moved-recovery-game"));
            verifyConflictState(fixture.resolve("conflict-game"));
        } finally {
            fx(() -> {
                if (shell != null) shell.close();
                if (isolatedCheck != null) isolatedCheck.shutdown();
                if (stage != null) stage.close();
            });
            Platform.exit();
        }
    }

    private void verifyGovernedWorkflow(Path fixture) throws Exception {
        var instance = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture, "two-rounds");
        Path broken = yagen.waitmydawn.checker.task.RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        Path second = yagen.waitmydawn.checker.task.RepairTasksTest.mod(instance.modsDir().resolve("second.jar"), "second");
        fx(() -> {
            shell.selectInstance(instance);
            shell.root().applyCss(); shell.root().layout();
            ((Spinner<Integer>) shell.root().lookup("#check-grace")).getValueFactory().setValue(0);
            assertTrue(((Button) shell.root().lookup("#start-repair")).isDisabled());
        });
        awaitLabel(shell.root(), "workflow-load-state", "READY");
        fx(() -> {
            assertTrue(((Label) shell.root().lookup("#workflow-policy-state")).getText().contains("尚无全局保存策略"));
            @SuppressWarnings("unchecked")
            ComboBox<yagen.waitmydawn.checker.task.RepairPolicy.Mode> modFailure =
                    (ComboBox<yagen.waitmydawn.checker.task.RepairPolicy.Mode>)
                            shell.root().lookup("#policy-mod_failure");
            modFailure.setValue(yagen.waitmydawn.checker.task.RepairPolicy.Mode.ASK);
            snapshot("18a-workflow-ready");
            fire("start-repair");
        });
        awaitButton("workflow-decline");
        awaitLabel(shell.root(), "workflow-phase-status", "等待你的决定");
        fx(() -> { scrollWorkflow(0.38); snapshot("19-workflow-decision"); fire("workflow-decline"); });
        awaitCheckMessage("修复需要人工处理");
        fx(() -> assertTrue(((Label) shell.root().lookup("#workflow-phase-status"))
                .getText().contains("已暂停")));
        assertTrue(Files.exists(broken)); assertTrue(Files.exists(second));
        fx(() -> {
            javafx.scene.layout.VBox rounds = (javafx.scene.layout.VBox) shell.root().lookup("#workflow-rounds");
            javafx.scene.control.TitledPane inventory =
                    (javafx.scene.control.TitledPane) rounds.getChildren().getFirst().lookup(".titled-pane");
            assertNotNull(inventory);
            inventory.setExpanded(true);
            snapshot("20-workflow-paused");
            fire("resume-workflow");
        });
        awaitButton("workflow-allow");
        fx(() -> fire("workflow-allow"));
        awaitButton("workflow-allow");
        fx(() -> { scrollWorkflow(0.38); snapshot("21-workflow-round-two"); fire("workflow-allow"); });
        awaitCheckMessage("修复后启动检验通过");
        fx(() -> assertTrue(((Label) shell.root().lookup("#workflow-phase-status"))
                .getText().contains("检验通过")));
        assertFalse(Files.exists(broken)); assertFalse(Files.exists(second));
        fx(() -> {
            assertTrue(shell.root().lookupAll(".stage-pill").size() >= 4,
                    "轮次卡片应分别展示授权、准备、提交与验证状态");
            javafx.scene.layout.VBox rounds = (javafx.scene.layout.VBox) shell.root().lookup("#workflow-rounds");
            javafx.scene.control.TitledPane inventory =
                    (javafx.scene.control.TitledPane) rounds.getChildren().getFirst().lookup(".titled-pane");
            assertNotNull(inventory);
            assertTrue(inventory.isExpanded(), "轮次更新后应保留用户展开状态");
            assertTrue(rounds.lookupAll(".list-view").isEmpty(), "轮次区域不应再创建嵌套滚动列表");
            scrollWorkflow(0.45);
            snapshot("22-workflow-verified");
            fire("workflow-history");
        });
        awaitCheckMessage("读取轮次记录完成");
        fx(() -> {
            shell.root().applyCss(); shell.root().layout();
            javafx.scene.layout.VBox rounds = (javafx.scene.layout.VBox) shell.root().lookup("#workflow-rounds");
            assertEquals(2, rounds.getChildren().size());
            rounds.getChildren().get(1).requestFocus();
            shell.root().layout();
        });
        awaitButton("restore-round-2");
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
        fx(() -> fire("restore-round-2"));
        awaitButton("confirm-round-restore");
        fx(() -> {
            assertNotNull(shell.root().lookup("#restore-preview-items"));
            assertTrue(shell.root().lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().startsWith("当前：")));
            assertTrue(shell.root().lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().startsWith("目标：")));
            assertFalse(shell.root().lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().startsWith("mod/")));
            scrollWorkflow(0.35);
            snapshot("23-workflow-restore-preview");
            fire("confirm-round-restore");
        });
        awaitCheckMessage("已恢复，尚未重新启动检验");
        fx(() -> {
            assertTrue(((Label) shell.root().lookup("#workflow-phase-status")).getText().contains("已还原"));
            assertTrue(((Label) shell.root().lookup("#workflow-next-step")).getText().contains("重新启动检验"));
        });
        assertTrue(Files.exists(second)); assertFalse(Files.exists(broken));
        fx(() -> { scrollWorkflow(0.4); snapshot("24-workflow-restored"); });
    }

    private void verifyPolicyLoading(Path fixture) throws Exception {
        var first = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture.resolve("first"), "repairable");
        var second = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture.resolve("second"), "repairable");
        var resumed = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture.resolve("resumed"), "repairable");
        var failed = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture.resolve("failed"), "repairable");
        var firstPolicy = new yagen.waitmydawn.checker.task.RepairPolicy(java.util.Map.of(
                yagen.waitmydawn.checker.task.RepairPolicy.Category.DEPENDENCY,
                yagen.waitmydawn.checker.task.RepairPolicy.Mode.STOP), 2);
        var secondPolicy = new yagen.waitmydawn.checker.task.RepairPolicy(java.util.Map.of(
                yagen.waitmydawn.checker.task.RepairPolicy.Category.DEPENDENCY,
                yagen.waitmydawn.checker.task.RepairPolicy.Mode.AUTO), 6);
        var firstData = new WorkflowPane.InitialData(
                yagen.waitmydawn.checker.transaction.InstanceIdentity.of(first), firstPolicy, true, java.util.List.of());
        var secondData = new WorkflowPane.InitialData(
                yagen.waitmydawn.checker.transaction.InstanceIdentity.of(second), secondPolicy, true, java.util.List.of());
        var pausedState = new yagen.waitmydawn.checker.task.RepairWorkflow.State(1,
                java.util.UUID.randomUUID(), yagen.waitmydawn.checker.transaction.InstanceIdentity.of(resumed),
                System.currentTimeMillis(), yagen.waitmydawn.checker.task.RepairPolicy.defaults(), 0,
                yagen.waitmydawn.checker.task.RepairWorkflow.Phase.PAUSED, "最近未完成会话",
                java.util.List.of(new yagen.waitmydawn.checker.task.RepairWorkflow.Round(
                        java.util.UUID.randomUUID(), 1,
                        yagen.waitmydawn.checker.transaction.RoundSnapshot.capture(resumed),
                        yagen.waitmydawn.checker.task.RepairWorkflow.Phase.PAUSED, "等待处理",
                        java.util.List.of(), java.util.List.of(), null)),
                null, null, null, java.util.List.of());
        var resumedData = new WorkflowPane.InitialData(
                yagen.waitmydawn.checker.transaction.InstanceIdentity.of(resumed),
                yagen.waitmydawn.checker.task.RepairPolicy.defaults(), false, java.util.List.of(pausedState));
        var delayed = new CompletableFuture<WorkflowPane.InitialData>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        WorkflowPane.InitialLoader loader = selected -> {
            if (selected == first) {
                firstStarted.countDown();
                return delayed.get();
            }
            if (selected == second) return secondData;
            if (selected == resumed) return resumedData;
            throw new java.io.IOException("fixture policy corrupt");
        };
        WorkflowPane.Host host = new WorkflowPane.Host() {
            @Override public <T> void run(yagen.waitmydawn.checker.task.TaskService.Work<T> work,
                                          java.util.function.Consumer<T> display, String operation,
                                          java.util.function.Consumer<Throwable> failure) {
                if (operation.equals("保存修复策略")) failure.accept(new java.io.IOException("fixture save denied"));
            }
            @Override public boolean busy() { return false; }
        };
        WorkflowPane[] pane = new WorkflowPane[1];
        Stage[] policyStage = new Stage[1];
        try {
            fx(() -> {
                pane[0] = new WorkflowPane(host, fixture.resolve("tool"),
                        yagen.waitmydawn.checker.core.CheckerEngine.Options::defaults, null, loader);
                policyStage[0] = new Stage();
                policyStage[0].setScene(new Scene(pane[0].root(), 1200, 900));
                policyStage[0].show();
                pane[0].select(first);
                assertFalse(pane[0].startAvailableProperty().get());
            });
            assertTrue(firstStarted.await(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            awaitLabel(pane[0].root(), "workflow-load-state", "LOADING");

            fx(() -> pane[0].select(second));
            awaitLabel(pane[0].root(), "workflow-load-state", "READY");
            fx(() -> {
                WorkflowPane current = pane[0];
                assertEquals(yagen.waitmydawn.checker.task.RepairPolicy.Mode.AUTO, dependencyMode(current));
                assertEquals(6, ((Spinner<Integer>) current.root().lookup("#workflow-limit")).getValue());
                assertTrue(current.startAvailableProperty().get());
            });

            delayed.complete(firstData);
            Thread.sleep(200);
            fx(() -> {
                assertEquals(yagen.waitmydawn.checker.task.RepairPolicy.Mode.AUTO, dependencyMode(pane[0]));
                assertTrue(((Label) pane[0].root().lookup("#workflow-load-state")).getText().contains("READY"));
            });

            fx(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<yagen.waitmydawn.checker.task.RepairPolicy.Mode> dependency =
                        (ComboBox<yagen.waitmydawn.checker.task.RepairPolicy.Mode>)
                                pane[0].root().lookup("#policy-dependency");
                dependency.setValue(yagen.waitmydawn.checker.task.RepairPolicy.Mode.STOP);
            });
            awaitLabel(pane[0].root(), "workflow-policy-state", "未保存修改");
            fx(() -> ((Button) pane[0].root().lookup("#save-policy")).fire());
            awaitLabel(pane[0].root(), "workflow-policy-state", "保存失败");
            fx(() -> {
                assertEquals(yagen.waitmydawn.checker.task.RepairPolicy.Mode.STOP, dependencyMode(pane[0]));
                assertTrue(((Label) pane[0].root().lookup("#workflow-next-step")).getText().contains("检查磁盘权限"));
            });

            fx(() -> pane[0].select(resumed));
            awaitLabel(pane[0].root(), "workflow-load-state", "READY");
            fx(() -> {
                assertFalse(pane[0].startAvailableProperty().get());
                assertFalse(((Button) pane[0].root().lookup("#resume-workflow")).isDisabled());
                assertTrue(((Label) pane[0].root().lookup("#workflow-phase-status")).getText().contains("已暂停"));
            });

            fx(() -> pane[0].select(failed));
            awaitLabel(pane[0].root(), "workflow-load-state", "ERROR");
            fx(() -> {
                assertFalse(pane[0].startAvailableProperty().get());
                assertTrue(((Label) pane[0].root().lookup("#workflow-phase-status")).getText().contains("实例初始化失败"));
                assertFalse(((Label) pane[0].root().lookup("#workflow-next-step")).getText().equals("下一步："));
            });
        } finally {
            fx(() -> {
                if (pane[0] != null) pane[0].close();
                if (policyStage[0] != null) policyStage[0].close();
            });
        }
    }

    private void verifyGlobalHistory(Path fixture) throws Exception {
        var instance = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture, "repairable");
        var workflow = java.util.UUID.randomUUID();
        var round = new yagen.waitmydawn.checker.task.RepairWorkflow.Round(
                java.util.UUID.randomUUID(), 1,
                yagen.waitmydawn.checker.transaction.RoundSnapshot.capture(instance),
                yagen.waitmydawn.checker.task.RepairWorkflow.Phase.PAUSED, "等待用户继续",
                java.util.List.of(), java.util.List.of(), "");
        var state = new yagen.waitmydawn.checker.task.RepairWorkflow.State(1, workflow,
                yagen.waitmydawn.checker.transaction.InstanceIdentity.of(instance),
                System.currentTimeMillis(), yagen.waitmydawn.checker.task.RepairPolicy.defaults(),
                0, yagen.waitmydawn.checker.task.RepairWorkflow.Phase.PAUSED, "等待用户继续",
                java.util.List.of(round), null, null, null, java.util.List.of());
        yagen.waitmydawn.checker.transaction.RoundSnapshot.writeJournal(instance, workflow + ".json", state);

        fx(() -> shell.scanDirectory(fixture));
        awaitScanMessage("识别到 1 个实例");
        fx(() -> fire("nav-历史记录"));
        awaitLabel(shell.root(), "history-status", "共 1 条记录");
        fx(() -> {
            snapshot("25-global-history");
            fire("history-open-0");
        });
        awaitLabel(shell.root(), "workflow-load-state", "READY");
        awaitLabel(shell.root(), "workflow-phase-status", "已暂停");
        fx(() -> {
            assertTrue(shell.root().lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().contains("等待用户继续")));
            snapshot("26-history-session-opened");
        });
        var removableWorkflow = java.util.UUID.randomUUID();
        var removableRound = new yagen.waitmydawn.checker.task.RepairWorkflow.Round(
                java.util.UUID.randomUUID(), 1, round.before(),
                yagen.waitmydawn.checker.task.RepairWorkflow.Phase.VERIFIED, "已完成，可以回收",
                java.util.List.of(), java.util.List.of(), null);
        var removableState = new yagen.waitmydawn.checker.task.RepairWorkflow.State(1, removableWorkflow,
                yagen.waitmydawn.checker.transaction.InstanceIdentity.of(instance),
                System.currentTimeMillis() + 1, yagen.waitmydawn.checker.task.RepairPolicy.defaults(),
                0, yagen.waitmydawn.checker.task.RepairWorkflow.Phase.VERIFIED, "已完成，可以回收",
                java.util.List.of(removableRound), null, null, null, java.util.List.of());
        yagen.waitmydawn.checker.transaction.RoundSnapshot.writeJournal(
                instance, removableWorkflow + ".json", removableState);

        Path preparation = Files.createDirectories(instance.dir().resolve(
                ".maa-checker/preparation/prepare-storage-fixture"));
        Path artifact = Files.writeString(preparation.resolve("artifact.jar"), "generated");
        Path orphan = instance.dir().resolve(".maa-checker/objects/" + "d".repeat(64) + ".jar");
        Files.createDirectories(orphan.getParent());
        Files.writeString(orphan, "orphan");
        fx(() -> fire("nav-存储"));
        awaitLabel(shell.root(), "storage-status", "实例 fixture");
        awaitButton("preview-orphans");
        awaitLabel(shell.root(), "storage-reclaim-status", "引用复核完成");
        String reclaimBeforeCleanup = labelText("storage-reclaim-status");
        fx(() -> {
            assertNotNull(shell.root().lookup("#storage-snapshots"));
            assertNotNull(shell.root().lookup("#storage-workflows"));
            assertNotNull(shell.root().lookup("#storage-orphans"));
            assertFalse(((Button) shell.root().lookup("#clean-storage")).isDisabled());
            snapshot("28-storage-inventory");
            fire("preview-orphans");
        });
        awaitButton("confirm-clean-orphans");
        fx(() -> {
            scrollStorage();
            snapshot("29-storage-orphan-preview");
        });
        fx(() -> fire("confirm-clean-orphans"));
        long orphanDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        while (Files.exists(orphan) && System.nanoTime() < orphanDeadline) Thread.sleep(30);
        assertFalse(Files.exists(orphan));
        awaitLabelChanged(shell.root(), "storage-reclaim-status", reclaimBeforeCleanup);
        String branchButton = awaitBranchButton();
        fx(() -> {
            scrollStorage();
            snapshot("30-storage-branches");
            fire(branchButton);
        });
        awaitButton("confirm-delete-branch");
        fx(() -> {
            scrollStorage();
            snapshot("31-storage-branch-preview");
        });
        fx(() -> fire("confirm-delete-branch"));
        long branchDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        Path workflowFile = instance.dir().resolve(".maa-checker/workflows/" + removableWorkflow + ".json");
        while (Files.exists(workflowFile) && System.nanoTime() < branchDeadline) Thread.sleep(30);
        assertFalse(Files.exists(workflowFile));
        awaitButton("clean-storage");
        fx(() -> {
            scrollStorage();
            snapshot("32-storage-branch-reclaimed");
            fire("clean-storage");
        });
        awaitButton("confirm-clean-storage");
        fx(() -> fire("confirm-clean-storage"));
        long cleanupDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        while (Files.exists(artifact) && System.nanoTime() < cleanupDeadline) Thread.sleep(30);
        assertFalse(Files.exists(artifact));
    }

    private void verifyConflictState(Path fixture) throws Exception {
        var instance = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture, "repairable");
        Path broken = yagen.waitmydawn.checker.task.RepairTasksTest.mod(
                instance.modsDir().resolve("broken.jar"), "broken");
        fx(() -> {
            shell.selectInstance(instance);
            ((Spinner<Integer>) shell.root().lookup("#check-grace")).getValueFactory().setValue(0);
            fire("start-check");
        });
        awaitCheckMessage("检查未通过");
        fx(() -> fire("prepare-repair"));
        awaitCheckMessage("修复方案等待确认");
        fx(() -> {
            ((javafx.scene.control.CheckBox) shell.root().lookup("#approve-repair")).fire();
            fire("execute-repair");
        });
        awaitCheckMessage("修复后启动检验通过");

        yagen.waitmydawn.checker.task.RepairTasksTest.mod(broken, "user_replacement");
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"),
                java.nio.file.attribute.FileTime.fromMillis(0));
        fx(() -> fire("repair-history"));
        awaitCheckMessage("读取记录完成");
        fx(() -> fire("restore-session-0"));
        awaitCheckMessage("恢复尚未完成");
        fx(() -> {
            assertTrue(shell.root().lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().contains("恢复遇到冲突")));
            snapshot("27-conflict-state");
        });
    }

    private void verifyMovedRecovery(Path fixture) throws Exception {
        var original = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture, "repairable");
        yagen.waitmydawn.checker.task.RepairTasksTest.mod(
                original.modsDir().resolve("moved.jar"), "moved");
        var snapshot = yagen.waitmydawn.checker.transaction.RoundSnapshot.capture(original);
        var workflow = java.util.UUID.randomUUID();
        var round = new yagen.waitmydawn.checker.task.RepairWorkflow.Round(
                java.util.UUID.randomUUID(), 1, snapshot,
                yagen.waitmydawn.checker.task.RepairWorkflow.Phase.PAUSED,
                "实例移动后的恢复资料", java.util.List.of(), java.util.List.of(), "");
        var state = new yagen.waitmydawn.checker.task.RepairWorkflow.State(1, workflow,
                yagen.waitmydawn.checker.transaction.InstanceIdentity.of(original),
                System.currentTimeMillis(), yagen.waitmydawn.checker.task.RepairPolicy.defaults(),
                0, yagen.waitmydawn.checker.task.RepairWorkflow.Phase.PAUSED,
                "实例移动后的恢复资料", java.util.List.of(round),
                null, null, null, java.util.List.of());
        yagen.waitmydawn.checker.transaction.RoundSnapshot.writeJournal(
                original, workflow + ".json", state);

        fx(() -> shell.scanDirectory(fixture));
        awaitScanMessage("识别到 1 个实例");
        Path movedPath = original.dir().resolveSibling("moved-instance");
        Files.move(original.dir(), movedPath);
        var moved = yagen.waitmydawn.checker.core.InstanceScanner.scanOne(fixture, movedPath);
        assertNotNull(moved);
        fx(() -> shell.selectInstance(moved));
        awaitLabel(shell.root(), "workflow-load-state", "ERROR");
        fx(() -> fire("nav-存储"));
        awaitLabel(shell.root(), "recovery-status", "旧身份候选");
        awaitButton("preview-reassociation-0");
        fx(() -> {
            assertNotNull(shell.root().lookup("#recovery-integrity"));
            assertNotNull(shell.root().lookup("#recovery-candidate-0"));
            snapshot("33-recovery-candidate");
            fire("preview-reassociation-0");
        });
        awaitButton("confirm-reassociation");
        fx(() -> {
            assertNotNull(shell.root().lookup("#reassociation-preview"));
            snapshot("34-recovery-preview");
            fire("confirm-reassociation");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        while (true) {
            try {
                var reopened = yagen.waitmydawn.checker.task.RepairWorkflow.history(moved);
                if (reopened.size() == 1
                        && reopened.getFirst().instance().id()
                        .equals(yagen.waitmydawn.checker.transaction.InstanceIdentity.of(moved).id())) {
                    break;
                }
            } catch (Exception ignored) {
            }
            assertTrue(System.nanoTime() < deadline, "显式重新关联未完成");
            Thread.sleep(50);
        }
        awaitLabel(shell.root(), "recovery-status", "检查通过");
        fx(() -> fire("nav-当前任务"));
        awaitLabel(shell.root(), "workflow-load-state", "READY");
        fx(() -> snapshot("35-recovery-reassociated"));
    }

    @SuppressWarnings("unchecked")
    private static yagen.waitmydawn.checker.task.RepairPolicy.Mode dependencyMode(WorkflowPane pane) {
        return ((ComboBox<yagen.waitmydawn.checker.task.RepairPolicy.Mode>)
                pane.root().lookup("#policy-dependency")).getValue();
    }

    private void scrollWorkflow(double position) {
        shell.root().lookupAll(".page-scroll").forEach(node -> { if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.isVisible()) scroll.setVvalue(position); });
        shell.root().applyCss(); shell.root().layout();
    }

    private void scrollStorage() {
        shell.root().lookupAll(".page-scroll").forEach(node -> {
            if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.isVisible()) {
                scroll.setVvalue(1.0);
            }
        });
        shell.root().applyCss();
        shell.root().layout();
    }

    private void awaitButton(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            FutureTask<Boolean> ready = new FutureTask<>(() -> {
                shell.root().applyCss(); shell.root().layout();
                var node = shell.root().lookup("#" + id);
                return node instanceof Button button && !button.isDisabled();
            });
            Platform.runLater(ready);
            if (ready.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
            Thread.sleep(50);
        }
        FutureTask<String> diagnostic = new FutureTask<>(() -> {
            var phase = shell.root().lookup("#check-phase");
            return phase instanceof Label label ? label.getText() : "";
        });
        Platform.runLater(diagnostic);
        fail("操作未就绪：" + id + "；当前状态："
                + diagnostic.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private String awaitBranchButton() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        Node stable = null;
        int stableChecks = 0;
        while (System.nanoTime() < deadline) {
            FutureTask<Node> ready = new FutureTask<>(() -> {
                shell.root().applyCss();
                shell.root().layout();
                for (int index = 0; index < 20; index++) {
                    String id = "preview-branch-" + index;
                    if (shell.root().lookup("#" + id) instanceof Button button && !button.isDisabled()) {
                        return button;
                    }
                }
                return null;
            });
            Platform.runLater(ready);
            Node found = ready.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (found != null && found == stable) stableChecks++;
            else {
                stable = found;
                stableChecks = found == null ? 0 : 1;
            }
            if (found != null && stableChecks >= 3) return found.getId();
            Thread.sleep(100);
        }
        fail("没有可预览的历史分支回收按钮");
        return null;
    }

    private void verifyLoaderWorkflow(Path fixture) throws Exception {
        var instance = yagen.waitmydawn.checker.task.LoaderFixture.game(fixture);
        Path installer = yagen.waitmydawn.checker.task.LoaderFixture.installer(fixture.resolveSibling("loader-installer.jar"));
        String original = yagen.waitmydawn.checker.core.RepairInputs.hash(instance.versionJson());
        fx(() -> {
            shell.close();
            shell = new DesktopShell(stage, fixture.resolveSibling("loader-tool"),
                    yagen.waitmydawn.checker.task.LoaderFixture.factory(installer, fixture.resolveSibling("loader-preparation")));
            stage.getScene().setRoot(shell.root());
            shell.selectInstance(instance);
            shell.root().applyCss();
            shell.root().layout();
            @SuppressWarnings("unchecked")
            Spinner<Integer> grace = (Spinner<Integer>) shell.root().lookup("#check-grace");
            grace.getValueFactory().setValue(0);
            fire("start-check");
        });
        awaitCheckMessage("检查未通过");
        fx(() -> fire("prepare-repair"));
        awaitCheckMessage("修复方案等待确认");
        assertEquals(original, yagen.waitmydawn.checker.core.RepairInputs.hash(instance.versionJson()));
        fx(() -> {
            assertTrue(shell.root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().equals("升级 NeoForge")));
            snapshot("15-loader-review");
            ((javafx.scene.control.CheckBox) shell.root().lookup("#approve-repair")).fire();
            fire("execute-repair");
        });
        awaitCheckMessage("修复后启动检验通过");
        fx(() -> snapshot("16-loader-verified"));
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
        fx(() -> fire("repair-history"));
        awaitCheckMessage("读取记录完成");
        fx(() -> {
            snapshot("17-loader-history");
            fire("restore-session-0");
        });
        awaitCheckMessage("已恢复，尚未重新启动检验");
        assertEquals(original, yagen.waitmydawn.checker.core.RepairInputs.hash(instance.versionJson()));
        assertTrue(Files.exists(fixture.resolve("libraries/test/loader/21.1.10/loader-21.1.10.jar")),
                "版本专属共享库在还原后作为缓存保留");
        fx(() -> snapshot("18-loader-restored"));
    }

    private void verifyRepairWorkflow(Path fixture) throws Exception {
        var instance = yagen.waitmydawn.checker.task.RepairTasksTest.fixture(fixture, "repairable");
        Path broken = yagen.waitmydawn.checker.task.RepairTasksTest.mod(instance.modsDir().resolve("broken.jar"), "broken");
        String originalHash = yagen.waitmydawn.checker.core.RepairInputs.hash(broken);
        Files.writeString(instance.dir().resolve("modpack.cfg"), "fixture pack marker");
        fx(() -> {
            shell.selectInstance(instance);
            fire("start-check");
        });
        awaitCheckMessage("检查未通过");
        fx(() -> fire("prepare-repair"));
        awaitCheckMessage("修复方案等待确认");
        assertTrue(Files.exists(broken), "方案审核前不得停用模组");
        fx(() -> {
            assertTrue(((Button) shell.root().lookup("#execute-repair")).isDisabled());
            snapshot("11-real-repair-plan");
            ((javafx.scene.control.CheckBox) shell.root().lookup("#approve-repair")).fire();
            fire("execute-repair");
        });
        awaitCheckMessage("修复后启动检验通过");
        assertFalse(Files.exists(broken));
        assertFalse(Files.exists(instance.dir().resolve("modpack.cfg")));
        fx(() -> snapshot("12-real-repair-verified"));
        Files.setLastModifiedTime(instance.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
        fx(() -> fire("repair-history"));
        awaitCheckMessage("读取记录完成");
        fx(() -> {
            snapshot("13-real-repair-history");
            fire("restore-session-0");
        });
        awaitCheckMessage("已恢复，尚未重新启动检验");
        assertEquals(originalHash, yagen.waitmydawn.checker.core.RepairInputs.hash(broken));
        assertEquals("fixture pack marker", Files.readString(instance.dir().resolve("modpack.cfg")));
        fx(() -> snapshot("14-real-repair-restored"));
    }

    private void verifyRealChecks(Path fixture) throws Exception {
        Path instance = createLaunchFixture(fixture, "marker-live");
        fx(() -> shell.scanDirectory(fixture));
        awaitScanMessage("识别到 1 个实例");
        fx(() -> {
            fire("inspect-instance-0");
            @SuppressWarnings("unchecked")
            Spinner<Integer> grace = (Spinner<Integer>) shell.root().lookup("#check-grace");
            grace.getValueFactory().setValue(0);
        });
        awaitLabel(shell.root(), "instance-java", "Java");
        awaitLabel(shell.root(), "instance-last-result", "最近结果");
        awaitLabel(shell.root(), "instance-unfinished", "未完成会话");
        fx(() -> {
            assertEquals(5, ((javafx.scene.layout.FlowPane) shell.root().lookup("#task-steps"))
                    .getChildren().size());
            assertEquals("3072", ((TextField) shell.root().lookup("#check-memory")).getText());
            assertEquals(3072.0,
                    ((javafx.scene.control.Slider) shell.root().lookup("#check-memory-slider")).getValue());
            assertNotNull(shell.root().lookup("#start-repair"));
            Button onlyCheck = (Button) shell.root().lookup("#start-check");
            onlyCheck.requestFocus();
            assertTrue(onlyCheck.isFocusTraversable(), "主操作应参与键盘焦点遍历");
            fire("start-check");
            assertTrue(((Button) shell.root().lookup("#start-check")).isDisabled());
        });
        awaitCheckMessage("本次启动检验通过");
        fx(() -> {
            assertTrue(shell.root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().equals("本次启动检验通过")));
            TextField reportLocation = (TextField) shell.root().lookup("#report-location");
            assertNotNull(reportLocation);
            assertFalse(reportLocation.getText().isBlank());
            stage.setWidth(1440);
            stage.setHeight(1000);
            snapshot("09-real-check-pass");
        });
        assertTrue(Files.readString(instance.resolve("fixture.json")).contains("marker-live"));
        try (var files = Files.list(instance.resolve("mods"))) { assertEquals(0, files.count()); }

        Path failedRoot = fixture.resolveSibling("failed-check");
        Path failedInstance = createLaunchFixture(failedRoot, "diagnostic-live");
        fx(() -> {
            shell.selectInstance(new yagen.waitmydawn.checker.core.GameInstance("fixture", failedInstance,
                    failedInstance.resolve("fixture.json"), "1.21.1", "vanilla", null,
                    failedInstance.resolve("mods"), 0, failedRoot));
            fire("start-check");
        });
        awaitCheckMessage("检查未通过");
        fx(() -> {
            assertTrue(shell.root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().contains("缺前置:") && l.getText().contains("relics")));
            snapshot("10-real-check-diagnosis");
        });

        CheckPane[] pane = new CheckPane[1];
        var inst = yagen.waitmydawn.checker.core.InstanceScanner.scanOne(fixture, instance);
        fx(() -> {
            pane[0] = new CheckPane(fixture.resolve("reports"));
            isolatedCheck = pane[0];
            pane[0].select(inst);
            stage.getScene().setRoot(pane[0].root());
            pane[0].start(context -> { throw new java.io.IOException("fixture diagnostic failure"); });
        });
        awaitLabel(pane[0].root(), "check-status", "检查执行失败");
        fx(() -> {
            assertTrue(((Label) pane[0].root().lookup("#check-phase")).getText().contains("fixture diagnostic failure"));
            assertNotNull(pane[0].root().lookup("#outcome-guidance"));
        });
        CountDownLatch started = new CountDownLatch(1);
        Process[] process = new Process[1];
        fx(() -> pane[0].start(context -> {
            process[0] = context.processes().start(new ProcessBuilder(yagen.waitmydawn.checker.task.ProcessFixture.command("sleep", fixture.resolve("pid"))));
            context.log("正在等待测试进程");
            started.countDown();
            Thread.sleep(60_000);
            return TaskResult.success(null, "不应到达");
        }));
        assertTrue(started.await(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        fx(() -> ((Button) pane[0].root().lookup("#cancel-check")).fire());
        awaitLabel(pane[0].root(), "check-status", "检查已停止");
        fx(() -> {
            VBox guidance = (VBox) pane[0].root().lookup("#outcome-guidance");
            assertNotNull(guidance);
            assertTrue(guidance.lookupAll(".label").stream()
                    .anyMatch(node -> node instanceof Label label && label.getText().contains("下一步")));
        });
        assertFalse(process[0].isAlive());
        fx(() -> {
            assertFalse(((Button) pane[0].root().lookup("#start-check")).isDisabled());
            stage.getScene().setRoot(shell.root());
            pane[0].shutdown();
        });
    }

    private Path createLaunchFixture(Path root, String mode) throws Exception {
        Path instance = Files.createDirectories(root.resolve("versions/fixture"));
        Files.createDirectories(instance.resolve("mods"));
        Files.createDirectories(instance.resolve("logs"));
        Files.createDirectories(instance.resolve("natives-windows-x86_64"));
        String classFile = yagen.waitmydawn.checker.task.ProcessFixture.class.getName().replace('.', '/') + ".class";
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(instance.resolve("fixture.jar")));
             var input = getClass().getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(input);
            jar.putNextEntry(new java.util.jar.JarEntry(classFile));
            input.transferTo(jar);
            jar.closeEntry();
        }
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(instance.resolve("fixture.json").toFile(),
                java.util.Map.of("id", "fixture", "inheritsFrom", "1.21.1", "mainClass", yagen.waitmydawn.checker.task.ProcessFixture.class.getName(),
                        "javaVersion", java.util.Map.of("majorVersion", 21), "libraries", java.util.List.of(),
                        "arguments", java.util.Map.of("jvm", java.util.List.of("-cp", "${classpath}"), "game", java.util.List.of(mode, "${game_directory}/logs/latest.log"))));
        return instance;
    }

    private void awaitCheckMessage(String expected) throws Exception { awaitLabel(shell.root(), "check-status", expected); }

    private void awaitLabel(javafx.scene.Parent root, String id, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            FutureTask<Boolean> matches = new FutureTask<>(() -> {
                root.applyCss();
                Label label = (Label) root.lookup("#" + id);
                return label != null && label.getText().contains(expected);
            });
            Platform.runLater(matches);
            if (matches.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
            Thread.sleep(50);
        }
        fail("状态未更新：" + expected);
    }

    private String labelText(String id) throws Exception {
        FutureTask<String> current = new FutureTask<>(() -> {
            shell.root().applyCss();
            Node node = shell.root().lookup("#" + id);
            return node instanceof Label label ? label.getText() : null;
        });
        Platform.runLater(current);
        return current.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void awaitLabelChanged(javafx.scene.Parent root, String id, String previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_STATUS_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            FutureTask<String> current = new FutureTask<>(() -> {
                root.applyCss();
                Node node = root.lookup("#" + id);
                return node instanceof Label label ? label.getText() : null;
            });
            Platform.runLater(current);
            String value = current.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (value != null && !value.equals(previous)) return;
            Thread.sleep(50);
        }
        fail("状态未刷新：" + id);
    }

    private void awaitScanMessage(String expected) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(SCAN_STATUS_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            FutureTask<Boolean> matches = new FutureTask<>(() -> {
                shell.root().applyCss();
                Label label = (Label) shell.root().lookup("#scan-status");
                return label != null && label.getText().contains(expected);
            });
            Platform.runLater(matches);
            if (matches.get(FX_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
            Thread.sleep(30);
        }
        fail("扫描状态未更新：" + expected);
    }

    private void fire(String id) {
        shell.root().applyCss();
        shell.root().layout();
        Button button = (Button) shell.root().lookup("#" + id);
        assertNotNull(button, id);
        assertFalse(button.isDisabled(), id);
        button.fire();
        shell.root().applyCss();
        shell.root().layout();
    }

    private void fx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(FX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void snapshot(String name) {
        try {
            shell.root().applyCss();
            shell.root().layout();
            WritableImage image = shell.root().snapshot(null, null);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) {
                for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
            Path dir = Path.of("target", "desktop-validation");
            Files.createDirectories(dir);
            assertTrue(ImageIO.write(png, "png", dir.resolve(name + ".png").toFile()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
