package yagen.waitmydawn.checker.desktop;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.Duration;
import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceLauncher;
import yagen.waitmydawn.checker.core.JavaLocator;
import yagen.waitmydawn.checker.core.LaunchVerdict;
import yagen.waitmydawn.checker.core.MemorySize;
import yagen.waitmydawn.checker.task.*;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.List;
import yagen.waitmydawn.checker.transaction.ActionRecord;
import yagen.waitmydawn.checker.transaction.TransactionSession;

import static yagen.waitmydawn.checker.desktop.Ui.*;

/** 真实检查的展示适配器；领域工作在 TaskService 执行，日志按帧批量刷新。 */
final class CheckPane {
    private static final int LOG_LIMIT = 64_000;
    private final TaskService tasks = new TaskService();
    private final BorderPane root = new BorderPane();
    private final Label title = text("选择一个实例开始检查", "page-title");
    private final Label status = text("等待开始", "badge");
    private final Label phase = text("实际启动 Minecraft，分析本次日志并生成诊断报告。", "muted");
    private final Label scope = text("选择实例后显示读取到的实例信息；检查或修复需要你明确启动任务。", "safe-callout");
    private final ProgressBar progress = new ProgressBar(-1);
    private final TextArea logs = new TextArea();
    private final VBox findings = new VBox(12);
    private final FlowPane steps = new FlowPane(12, 8);
    private final TextField memoryInput =
            new TextField(String.valueOf(MemorySize.snap(MemorySize.DEFAULT_MB)));
    private final Slider memorySlider = new Slider(MemorySize.MIN_MB, MemorySize.maximumMb(),
            MemorySize.snap(MemorySize.DEFAULT_MB));
    private final Label memoryHint = text("", "muted");
    private final PauseTransition memoryConfirmDelay = new PauseTransition(Duration.millis(280));
    private final Spinner<Integer> timeout = new Spinner<>(30, 1800, 180, 30);
    private final Spinner<Integer> grace = new Spinner<>(0, 120, 15, 5);
    private final Label javaFact = text("Java 待读取", "tag");
    private final Label lastFact = text("最近结果 待读取", "tag");
    private final Label unfinishedFact = text("未完成会话 待读取", "tag");
    private final Button start = button("仅检查", "secondary", this::start);
    private final Button repair;
    private final Button cancel = button("停止检查", "danger", this::cancel);
    private final Button history = button("打开文件事务台账", "secondary", this::loadHistory);
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.millis(100), e -> refresh()));
    private TaskService.Handle<?> active;
    private String operation = "检查";
    private GameInstance instance;
    private boolean closed;
    private int omitted;
    private final Path output;
    private final yagen.waitmydawn.checker.action.LoaderPreparation.Factory loaderFactory;
    private final WorkflowPane workflow;
    private final ExecutorService detailTasks = Executors.newVirtualThreadPerTaskExecutor();
    private long detailRequest;
    private boolean syncingMemory;
    private int confirmedMemory = MemorySize.snap(MemorySize.DEFAULT_MB);

    CheckPane(Path output) {
        this(output, null);
    }

    CheckPane(Path output, yagen.waitmydawn.checker.action.LoaderPreparation.Factory loaderFactory) {
        this.output = output.toAbsolutePath().normalize();
        this.loaderFactory = loaderFactory;
        workflow = new WorkflowPane(new WorkflowPane.Host() {
            @Override public <T> void run(TaskService.Work<T> work, Consumer<T> display, String operation,
                                          Consumer<Throwable> failure) {
                runWork(work, display, operation, failure);
            }
            @Override public boolean busy() { return CheckPane.this.busy(); }
        }, this.output, this::options, loaderFactory);
        repair = button("检查并修复", "primary",
                () -> { if (confirmCurrentMemory()) workflow.startFromPrimary(); });
        status.setId("check-status");
        phase.setId("check-phase");
        logs.setId("check-log");
        start.setId("start-check");
        repair.setId("start-repair");
        cancel.setId("cancel-check");
        history.setId("repair-history");
        javaFact.setId("instance-java");
        lastFact.setId("instance-last-result");
        unfinishedFact.setId("instance-unfinished");
        findings.setId("check-findings");
        steps.setId("task-steps");
        grace.setId("check-grace");
        configureMemoryControls();
        HBox options = new HBox(16, new VBox(6, text("最大内存 / MB", "caption"),
                        memoryInput, memorySlider, memoryHint),
                new VBox(6, text("启动超时 / 秒", "caption"), timeout),
                new VBox(6, text("成功信号后观察 / 秒", "caption"), grace));
        TitledPane advanced = new TitledPane("启动选项", options);
        advanced.setExpanded(false);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        logs.setEditable(false);
        logs.setPrefRowCount(10);
        logs.getStyleClass().add("log-view");
        TitledPane detail = new TitledPane("实时检查日志 · 窗口显示最近内容，完整日志随报告保存", logs);
        detail.setExpanded(true);
        FlowPane instanceFacts = new FlowPane(8, 8, javaFact, lastFact, unfinishedFact);
        instanceFacts.setId("instance-facts");
        VBox advancedLedger = new VBox(10,
                text("文件事务台账与单批审核不属于普通检查/修复入口；仅在排查具体文件动作时使用。", "muted"),
                history);
        TitledPane advancedTools = new TitledPane("高级：文件台账与审核记录", advancedLedger);
        advancedTools.setExpanded(false);
        VBox content = new VBox(18, text("实例 / 启动检查", "caption"), title, status,
                scope, instanceFacts, steps, card(phase, progress), advanced, workflow.root(),
                advancedTools, findings, detail);
        content.getStyleClass().add("page-body");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        HBox footer = new HBox(12, spacer(), cancel, start, repair);
        footer.getStyleClass().add("action-bar");
        root.setCenter(scroll);
        root.setBottom(footer);
        refresh.setCycleCount(Timeline.INDEFINITE);
        updateControls();
        updateSteps(null);
        repair.disableProperty().bind(workflow.startAvailableProperty().not());
    }

    BorderPane root() { return root; }

    void select(GameInstance selected) {
        if (busy()) throw new IllegalStateException("检查结束前不能切换实例");
        instance = selected;
        workflow.select(selected);
        active = null;
        operation = "检查";
        updateInstanceSummary();
        setStatus(TaskSnapshot.State.QUEUED);
        phase.setText("请先关闭正在运行的该实例，再开始检查。");
        logs.clear();
        findings.getChildren().clear();
        updateSteps(null);
        updateControls();
    }

    void openWorkflowSession(java.util.UUID workflowId) {
        workflow.openSession(workflowId);
    }

    void reloadWorkflow() {
        if (instance != null && !busy()) workflow.select(instance);
    }

    GameInstance selectedInstance() {
        return instance;
    }

    private void updateInstanceSummary() {
        GameInstance selected = instance;
        title.setText(selected.name());
        scope.setText("Minecraft " + selected.mcVersion() + " · " + selected.loader() + " "
                + selected.loaderVersion() + " · " + selected.modCount() + " 个模组\n"
                + selected.dir() + "\n仅检查不修改文件；检查并修复按分类策略授权，并在轮次记录中保留操作记录。");
        javaFact.setText("Java 待读取");
        lastFact.setText("最近结果 待读取");
        unfinishedFact.setText("未完成会话 待读取");
        long request = ++detailRequest;
        CompletableFuture.supplyAsync(() -> inspectInstance(selected), detailTasks)
                .whenComplete((details, error) -> Platform.runLater(() -> {
                    if (closed || instance != selected || request != detailRequest) return;
                    if (error != null) {
                        javaFact.setText("Java 读取失败");
                        lastFact.setText("最近结果 读取失败");
                        unfinishedFact.setText("未完成会话 读取失败");
                        return;
                    }
                    javaFact.setText(details.java());
                    lastFact.setText(details.lastResult());
                    unfinishedFact.setText(details.unfinished());
                }));
    }

    private record InstanceDetails(String java, String lastResult, String unfinished) {
    }

    private InstanceDetails inspectInstance(GameInstance selected) {
        int required = InstanceLauncher.requiredJavaOf(selected);
        String javaLabel;
        try {
            JavaLocator.JavaHome home = JavaLocator.find(required, output);
            javaLabel = home == null ? "Java " + required + "+ 未找到"
                    : "Java " + home.major() + "（要求 " + required + "+）";
        } catch (RuntimeException e) {
            javaLabel = "Java 读取失败";
        }

        String lastResult = "最近结果 暂无会话";
        String unfinished = "未完成会话 无";
        try {
            List<RepairWorkflow.State> sessions = RepairWorkflow.history(selected);
            if (!sessions.isEmpty()) {
                RepairWorkflow.State newest = sessions.stream()
                        .max(java.util.Comparator.comparingLong(RepairWorkflow.State::createdAt))
                        .orElseThrow();
                String time = java.time.Instant.ofEpochMilli(newest.createdAt())
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                lastResult = "最近结果 " + time + " · " + UiStatus.workflow(newest.phase(), false).title();
                RepairWorkflow.State open = sessions.stream()
                        .filter(s -> s.phase() != RepairWorkflow.Phase.VERIFIED
                                && s.phase() != RepairWorkflow.Phase.RESTORED)
                        .max(java.util.Comparator.comparingLong(RepairWorkflow.State::createdAt))
                        .orElse(null);
                if (open != null) {
                    unfinished = "未完成会话 " + UiStatus.workflow(open.phase(), false).title()
                            + " · 第 " + open.rounds().size() + " 轮";
                }
            }
        } catch (Exception e) {
            lastResult = "最近结果 读取失败";
            unfinished = "未完成会话 读取失败";
        }
        return new InstanceDetails(javaLabel, lastResult, unfinished);
    }

    boolean busy() { return active != null && !active.snapshot().state().terminal(); }

    private void start() {
        if (instance == null || busy() || closed) return;
        if (!confirmCurrentMemory()) return;
        start(CheckerTasks.diagnose(instance, output, options()));
    }

    private CheckerEngine.Options options() {
        CheckerEngine.Options defaults = CheckerEngine.Options.defaults();
        return new CheckerEngine.Options(confirmedMemory + "M", timeout.getValue(), grace.getValue(),
                defaults.rounds(), defaults.cascadeLimit(), false, false, 1, 0,
                "abort", null, null, false, false, null, defaults.notifier());
    }

    private void configureMemoryControls() {
        memoryInput.setId("check-memory");
        memorySlider.setId("check-memory-slider");
        memorySlider.setBlockIncrement(MemorySize.STEP_MB);
        memorySlider.setMajorTickUnit(4096);
        memorySlider.setMinorTickCount(0);
        memorySlider.setShowTickMarks(false);
        memorySlider.setShowTickLabels(false);
        memorySlider.getStyleClass().add("memory-slider");
        memoryInput.setPrefColumnCount(7);
        memoryInput.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,6}") ? change : null));
        memoryHint.setText("默认 3072 MB · 步长 4 MB · 本机上限 "
                + MemorySize.maximumMb() + " MB");
        memorySlider.valueProperty().addListener((observable, oldValue, value) -> {
            if (syncingMemory) return;
            int snapped = MemorySize.snap((int) Math.round(value.doubleValue()));
            applyMemoryValue(snapped);
            memoryConfirmDelay.playFromStart();
        });
        memoryInput.setOnAction(event -> confirmCurrentMemory());
        memoryInput.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) confirmCurrentMemory();
        });
        memoryConfirmDelay.setOnFinished(event -> confirmCurrentMemory());
    }

    private boolean confirmCurrentMemory() {
        int requested;
        try {
            requested = Integer.parseInt(memoryInput.getText().strip());
        } catch (NumberFormatException e) {
            memoryHint.setText("请输入有效的 MB 数值");
            applyMemoryValue(confirmedMemory);
            return false;
        }
        int snapped = MemorySize.snap(requested);
        if (MemorySize.needsConfirmation(snapped) && snapped != confirmedMemory
                && !confirmHighMemory(snapped)) {
            applyMemoryValue(confirmedMemory);
            return false;
        }
        confirmedMemory = snapped;
        applyMemoryValue(snapped);
        memoryHint.setText(MemorySize.needsConfirmation(snapped)
                ? "已确认高内存设置：" + snapped + " MB"
                : "当前 " + snapped + " MB；超过本机物理内存一半时会再次确认");
        return true;
    }

    private boolean confirmHighMemory(int mb) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "将最大内存设置为 " + mb + " MB，超过本机物理内存的一半（"
                        + MemorySize.physicalMb() / 2 + " MB）。\n继续可能影响系统和其他程序。",
                ButtonType.CANCEL, ButtonType.OK);
        alert.setHeaderText("确认使用较高内存？");
        if (root.getScene() != null && root.getScene().getWindow() != null) {
            alert.initOwner(root.getScene().getWindow());
        }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void applyMemoryValue(int mb) {
        syncingMemory = true;
        try {
            memoryInput.setText(String.valueOf(mb));
            memorySlider.setValue(mb);
        } finally {
            syncingMemory = false;
        }
    }

    void start(TaskService.Work<CheckerEngine.CheckReport> work) {
        runWork(work, this::display, "检查");
    }

    private <T> void runWork(TaskService.Work<T> work, Consumer<T> display, String operation) {
        runWork(work, display, operation, error -> { });
    }

    private <T> void runWork(TaskService.Work<T> work, Consumer<T> display, String operation,
                             Consumer<Throwable> failure) {
        if (instance == null || busy() || closed) return;
        this.operation = operation;
        synchronized (pending) { pending.clear(); omitted = 0; }
        logs.clear();
        findings.getChildren().clear();
        setStatus(UiStatus.task(TaskSnapshot.State.RUNNING, operation, ""));
        var current = tasks.submit(instance.name(), work, event -> {
            if (event.kind() != TaskEvent.Kind.LOG) return;
            String line = event.text();
            if (line.length() > 4000) line = line.substring(0, 4000) + " …";
            synchronized (pending) {
                if (pending.size() == 400) { pending.removeFirst(); omitted++; }
                pending.addLast(line);
            }
        });
        active = current;
        updateControls();
        refresh.playFromStart();
        current.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || active != current) return;
            refresh();
            refresh.stop();
            if (error != null) {
                UiStatus.Outcome outcome = UiStatus.error(operation, error.getMessage());
                setStatus(outcome);
                phase.setText(Objects.toString(error.getMessage(), "操作执行失败"));
                showOutcome(outcome, phase.getText());
                failure.accept(error);
            } else {
                UiStatus.Outcome outcome = UiStatus.task(result.state(), operation, result.message());
                setStatus(outcome);
                phase.setText(result.message());
                if (result.value() != null) display.accept(result.value());
                if (result.state() == TaskSnapshot.State.FAILED) {
                    Throwable failureCause = result.cause() != null
                            ? result.cause() : new java.io.IOException(result.message());
                    failure.accept(failureCause);
                }
                if (result.state() == TaskSnapshot.State.FAILED
                        || result.state() == TaskSnapshot.State.NEEDS_ATTENTION
                        || result.state() == TaskSnapshot.State.CANCELLED) {
                    showOutcome(outcome, result.message());
                }
            }
            updateControls();
        }));
    }

    private void cancel() {
        if (!busy()) return;
        active.cancel();
        refresh();
        updateControls();
    }

    private void refresh() {
        StringBuilder batch = new StringBuilder();
        synchronized (pending) {
            if (omitted > 0) { batch.append("[窗口省略 ").append(omitted).append(" 行，完整内容见运行日志]\n"); omitted = 0; }
            while (!pending.isEmpty()) batch.append(pending.removeFirst()).append('\n');
        }
        if (!batch.isEmpty()) {
            String combined = logs.getText() + batch;
            if (combined.length() > LOG_LIMIT) combined = "[仅展示最近日志]\n" + combined.substring(combined.length() - LOG_LIMIT);
            logs.setText(combined);
            logs.positionCaret(logs.getLength());
        }
        if (active != null) {
            TaskSnapshot snapshot = active.snapshot();
            setStatus(snapshot.state());
            phase.setText(snapshot.message());
            updateSteps(snapshot.phase());
        }
        workflow.refresh();
    }

    private void display(CheckerEngine.CheckReport report) {
        if (!report.rounds().isEmpty()) {
            instance = report.rounds().getLast().inst();
            updateInstanceSummary();
        }
        Path runDirectory = report.runDirectory();
        TextField directory = new TextField(runDirectory == null ? "报告路径未关联" : runDirectory.toString());
        directory.setId("report-location");
        directory.setEditable(false);
        Button copy = button("复制报告目录", "secondary", () -> {
            if (runDirectory == null) {
                phase.setText("报告路径未关联；请查看实时日志并重新检查");
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(runDirectory.toString());
            boolean copied = Clipboard.getSystemClipboard().setContent(content);
            phase.setText(copied ? "报告目录已复制" : "复制失败，可直接选中下方路径复制");
        });
        TitledPane reportFiles = new TitledPane("报告与完整日志 · 查看保存位置", new VBox(12, directory, copy));
        reportFiles.setId("report-files");
        reportFiles.setExpanded(false);
        if (runDirectory == null) {
            findings.getChildren().add(card(text("报告路径缺失", "badge-warning"),
                    text("本次结果无法关联到报告文件。请保留实时日志并重新检查；不能据此认定实例通过。", "body-text")));
        }
        if (report.rounds().isEmpty()) {
            findings.getChildren().add(card(text("尚未完成启动诊断", "section-title"),
                    text("请在检查日志中查看启动条件未满足的原因；此时不能认定模组存在故障。", "body-text")));
        }
        for (var round : report.rounds()) {
            VBox result = card(text(round.launch().verdict() == LaunchVerdict.PASS ? "本次启动检验通过" : "本次启动未通过", "section-title"),
                    text(round.launch().evidence(), "body-text"),
                    text("耗时 " + String.format("%.1f", round.launch().elapsedMs() / 1000.0) + " 秒 · 本次检查未额外修改模组", "caption"));
            result.getChildren().add(text("静态预检：" + round.stat().highConfidence().size() + " 条明确问题，"
                    + round.stat().needsReview().size() + " 条待复核；运行诊断：" + round.diag().issues().size() + " 条问题", "muted"));
            if (round.launch().verdict() == LaunchVerdict.PASS) result.getChildren().add(text("单次通过不代表所有游戏场景或后续运行均稳定。", "muted"));
            if (round.diag().issues().isEmpty() && round.launch().verdict() != LaunchVerdict.PASS) {
                result.getChildren().add(text("当前日志没有足够的结构化故障证据，请检查完整报告。", "badge-warning"));
            }
            for (var issue : round.diag().issues()) {
                TextArea evidence = new TextArea(issue.detail() == null ? "无额外证据" : issue.detail());
                evidence.setEditable(false);
                evidence.setWrapText(true);
                evidence.setPrefRowCount(3);
                TitledPane details = new TitledPane("查看诊断依据 · " + issue.kind(), evidence);
                details.setExpanded(false);
                String advice = switch (issue.kind()) {
                    case MISSING_REQUIRED -> "建议补齐必需前置 " + issue.modId();
                    case LOADER_TOO_OLD -> "建议更新加载器，满足 " + issue.expectedRange() + " 的要求";
                    case MOD_VERSION_MISMATCH -> "建议核对相关模组的版本兼容性";
                    case ENVIRONMENT -> "这是 Java、原生库、图形或资源环境问题，请先修复环境，不能据此摘除模组";
                    case CAUSED_BY_ATTRIBUTED, STACK_ATTRIBUTED -> "这是崩溃归因候选，需要结合完整证据复核";
                    default -> "建议核对相关模组及兼容性，查看诊断依据后再决定如何处理";
                };
                result.getChildren().add(card(text(issue.describe(), "body-text"), text(advice + "（尚未执行修改）", "muted"), details));
            }
            TextArea analysis = new TextArea("静态预检：\n"
                    + String.join("\n", round.stat().highConfidence().stream().map(p -> p.describe()).toList())
                    + "\n待复核：\n" + String.join("\n", round.stat().needsReview().stream().map(p -> p.describe()).toList())
                    + "\n归因过程：\n" + String.join("\n", round.diag().attributions())
                    + "\n证据：\n" + String.join("\n", round.diag().evidenceLines()) + "\n" + round.diag().note());
            analysis.setEditable(false);
            analysis.setWrapText(true);
            analysis.setPrefRowCount(6);
            TitledPane analysisDetails = new TitledPane("完整诊断详情与预检依据", analysis);
            analysisDetails.setExpanded(false);
            result.getChildren().add(analysisDetails);
            findings.getChildren().add(result);
        }
        findings.getChildren().add(reportFiles);
        if (report.exitCode() == 1 && report.rounds().size() == 1) {
            Button prepare = button("准备本次审核方案", "secondary", () -> runWork(
                    prepare(report), this::displayPlan, "准备方案"));
            prepare.setId("prepare-repair");
            TitledPane advancedRepair = new TitledPane("高级：仅执行本次审核方案",
                    new VBox(10,
                            text("普通修复请使用底部“检查并修复”。此入口只处理当前报告中的一批动作，"
                                    + "准备阶段可能联网下载候选或在独立目录运行安装器；产物校验后才会列出文件方案。", "muted"),
                            prepare));
            advancedRepair.setId("advanced-single-batch");
            advancedRepair.setExpanded(false);
            findings.getChildren().add(advancedRepair);
        }
    }

    private TaskService.Work<RepairTasks.Plan> prepare(CheckerEngine.CheckReport report) {
        var options = options();
        return loaderFactory == null ? RepairTasks.prepare(report, output, options)
                : RepairTasks.prepareWithLoader(report, output, options, loaderFactory);
    }

    private void displayPlan(RepairTasks.Plan plan) {
        UiStatus.Outcome outcome = UiStatus.plan(plan.executable(), plan.fileCount(), plan.blocked());
        setStatus(outcome);
        findings.getChildren().add(card(text(plan.executable() ? "修复方案 · 等待确认" : "修复方案 · 需要处理", "section-title"),
                text("共 " + plan.fileCount() + " 个文件动作 · 当前实例尚未修改\n每批执行后重新启动检验；新的动作需要重新审核。", "body-text")));
        if (plan.loader() != null) {
            TextArea evidence = new TextArea("新配置 SHA-256：" + plan.loader().versionHash() + "\n安装器 SHA-256：" + plan.loader().installerHash()
                    + "\n安装器日志：" + plan.loader().installerLog() + "\n" + String.join("\n", plan.loader().libraries().stream()
                    .map(lib -> (lib.alreadyPresent() ? "复用 " : "新增 ") + lib.relative() + " · " + lib.hash()).toList()));
            evidence.setEditable(false);
            evidence.setWrapText(true);
            evidence.setPrefRowCount(5);
            TitledPane details = new TitledPane("查看库文件清单与校验依据", evidence);
            details.setExpanded(false);
            findings.getChildren().add(card(text("升级 NeoForge", "section-title"),
                    text(plan.instance().loaderVersion() + " → " + plan.loader().version(), "accent-text"),
                    text("准备 " + plan.loader().additions() + " 个实例独立运行库，并切换版本配置。当前由本工具验证启动；外部启动器兼容性尚未验收。", "body-text"),
                    text("恢复会还原实例配置；新增库作为共享缓存保留，不会删除，以免影响其他实例。", "safe-callout"), details));
        }
        if (plan.disablePackMarker()) {
            findings.getChildren().add(card(text("停用 HMCL 整合包自动补全", "section-title"), text("modpack.cfg", "accent-text"),
                    text("防止启动器补回本批停用的模组。HMCL 的整合包更新入口会暂时隐藏；原标记完整保留，可随本会话恢复。", "body-text")));
        }
        for (var change : plan.changes()) {
            boolean install = change.candidate() != null;
            TextArea evidence = new TextArea(String.join("\n", change.action().errorText()) + "\n" + change.source() + "\nSHA-256 " + change.hash());
            evidence.setEditable(false);
            evidence.setWrapText(true);
            evidence.setPrefRowCount(4);
            TitledPane details = new TitledPane("诊断依据、文件身份与恢复方式", evidence);
            details.setExpanded(false);
            findings.getChildren().add(card(text((install ? "补装 " : "停用 ") + change.action().modId(), "section-title"),
                    text(change.filename(), "accent-text"), text(change.action().reason(), "body-text"), details));
        }
        for (String blocked : plan.blocked()) findings.getChildren().add(card(text("本批方案不可执行", "badge-warning"), text(blocked, "body-text")));
        for (String note : plan.notes()) findings.getChildren().add(text(note, "muted"));
        if (plan.executable()) {
            CheckBox approval = new CheckBox("我已查看以上文件变更、诊断依据及恢复方式，确认执行本批方案");
            approval.setWrapText(true);
            approval.setId("approve-repair");
            CheckBox uncertain = new CheckBox("我已核对崩溃归因候选，理解停用可能影响整合包功能");
            uncertain.setWrapText(true);
            boolean attribution = plan.changes().stream().anyMatch(c -> c.action().category().contains("ATTRIBUTED"));
            uncertain.setVisible(attribution);
            uncertain.setManaged(attribution);
            uncertain.setSelected(!attribution);
            Button execute = button("执行已确认方案并验证", "primary", () -> runWork(
                    RepairTasks.execute(plan, output, options()), this::displayExecution, "修复与验证"));
            execute.setId("execute-repair");
            execute.disableProperty().bind(approval.selectedProperty().not().or(uncertain.selectedProperty().not()));
            findings.getChildren().add(card(approval, uncertain, execute));
        }
        if (!plan.executable()) {
            showOutcome(outcome, "当前实例尚未修改。");
        }
    }

    private void displayExecution(RepairTasks.Execution execution) {
        if (!execution.verification().rounds().isEmpty()) {
            instance = execution.verification().rounds().getLast().inst();
            updateInstanceSummary();
        }
        findings.getChildren().add(card(text("已提交 " + execution.changed() + " 个文件动作", "section-title"),
                text("会话 " + execution.sessionId() + "\n如需撤销，请打开最近修复记录。", "muted")));
        display(execution.verification());
    }

    private void displayHistory(List<TransactionSession.History> sessions) {
        findings.getChildren().add(card(text("修复记录 · " + instance.name(), "section-title"),
                text("恢复按会话和动作逆序进行，文件有外部修改时停止并报告冲突。原 CLI / Swing 的旧记录暂不在此展示。", "muted")));
        boolean newerPending = false;
        int index = 0;
        for (var session : sessions) {
            String time = java.time.Instant.ofEpochMilli(session.header().createdAt()).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            VBox item = card(text(time + " · " + session.actions().size() + " 个文件动作", "section-title"),
                    text("会话 " + session.header().sessionId(), "caption"));
            boolean pendingActions = session.actions().stream().anyMatch(a -> a.state() != ActionRecord.State.REVERTED);
            for (var action : session.actions()) {
                if (action.kind() == ActionRecord.Kind.CACHE_LIBRARY || action.kind() == ActionRecord.Kind.INSTANCE_LIBRARY) continue;
                String kind = switch (action.kind()) {
                    case INSTALL_MOD -> "补装"; case REMOVE_MOD -> "停用"; case REPLACE_VERSION -> "调整版本"; case DISABLE_PACK_MARKER -> "停用自动补全标记"; case CACHE_LIBRARY -> "共享库缓存";
                    case INSTANCE_LIBRARY -> "实例运行库"; case RESTORE_ADD, RESTORE_REPLACE, RESTORE_REMOVE -> "快照恢复模组";
                    case RESTORE_MARKER_ADD, RESTORE_MARKER_REPLACE -> "快照恢复整合包标记";
                };
                String state = switch (action.state()) {
                    case PREPARED -> "提交中断，需恢复"; case APPLIED -> "已提交"; case REVERTING -> "恢复中断";
                    case REVERTED -> "已恢复"; case CONFLICT -> "存在文件冲突";
                };
                String filename = action.kind() == ActionRecord.Kind.CACHE_LIBRARY ? action.filename().substring(65) : action.filename();
                item.getChildren().add(text(kind + " " + filename + " · " + state + "\n" + action.reason() + "\n" + action.note(), "body-text"));
                TextArea evidence = new TextArea(String.join("\n", action.evidence()));
                evidence.setEditable(false);
                evidence.setWrapText(true);
                evidence.setPrefRowCount(3);
                TitledPane details = new TitledPane("查看操作依据与文件指纹", evidence);
                details.setExpanded(false);
                item.getChildren().add(details);
            }
            List<ActionRecord> libraries = session.actions().stream().filter(a -> a.kind() == ActionRecord.Kind.CACHE_LIBRARY || a.kind() == ActionRecord.Kind.INSTANCE_LIBRARY).toList();
            if (!libraries.isEmpty()) item.getChildren().add(libraryHistory(libraries));
            Button restore = button("按以上记录恢复此会话", "secondary", () -> runWork(
                    RepairTasks.restore(instance, output, session.header().sessionId()), this::displayRecovery, "恢复"));
            restore.setId("restore-session-" + index++);
            restore.setDisable(newerPending || !pendingActions);
            item.getChildren().add(restore);
            if (newerPending && pendingActions) item.getChildren().add(text("请先恢复更新的会话。", "caption"));
            findings.getChildren().add(item);
            newerPending |= pendingActions;
        }
        if (sessions.isEmpty()) findings.getChildren().add(text("该实例还没有新事务修复记录。", "muted"));
    }

    private TitledPane libraryHistory(List<ActionRecord> actions) {
        ListView<ActionRecord> list = new ListView<>(javafx.collections.FXCollections.observableArrayList(actions));
        list.setPrefHeight(180);
        list.setFixedCellSize(34);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(ActionRecord action, boolean empty) {
                super.updateItem(action, empty);
                setText(empty || action == null ? null : libraryName(action));
            }
        });
        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(4);
        list.getSelectionModel().selectedItemProperty().addListener((o, previous, action) -> {
            if (action != null) detail.setText(libraryName(action) + "\n" + action.reason() + "\n" + action.note()
                    + "\n" + String.join("\n", action.evidence()));
        });
        list.getSelectionModel().selectFirst();
        TitledPane pane = new TitledPane("运行库记录 · " + actions.size() + " 个文件 · 实例库随事务恢复，旧共享缓存保留", new VBox(10, list, detail));
        pane.setExpanded(false);
        return pane;
    }

    private static String libraryName(ActionRecord action) {
        return action.filename().substring(action.kind() == ActionRecord.Kind.CACHE_LIBRARY ? 65 : 37);
    }

    private void loadHistory() {
        if (instance != null) runWork(RepairTasks.history(instance, output), this::displayHistory, "读取记录");
    }

    private void displayRecovery(RepairTasks.Recovery recovery) {
        findings.getChildren().add(card(text(recovery.complete() ? "会话已恢复" : "恢复遇到冲突", "section-title"),
                text("会话 " + recovery.sessionId() + " · 恢复不等于启动检验通过，请重新检查。", "muted")));
        recovery.outcomes().forEach(outcome -> findings.getChildren().add(text(outcome.state() + " · " + outcome.message(), "body-text")));
    }

    private void updateControls() {
        boolean busy = busy();
        workflow.controls();
        start.setDisable(closed || busy || instance == null);
        history.setDisable(closed || busy || instance == null);
        cancel.setDisable(!busy || active.cancellationRequested());
        cancel.setText(busy && active.cancellationRequested() ? "正在停止…" : "停止任务");
        memoryInput.setDisable(busy);
        memorySlider.setDisable(busy);
        timeout.setDisable(busy);
        grace.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
    }

    private void updateSteps(TaskSnapshot.Phase phase) {
        String[] labels = {"预检", "启动", "诊断", "决策 / 修复", "验证"};
        int activeStep = phase == null ? 0 : switch (phase) {
            case QUEUED, SCANNING, PRECHECK -> 0;
            case PREPARING_LAUNCH, LAUNCHING, OBSERVING -> 1;
            case DIAGNOSING -> 2;
            case PLANNING, DOWNLOADING, PREPARING_LOADER, APPLYING, RESTORING -> 3;
            case REPORTING, FINISHED -> 4;
            case CLEANUP -> -1;
        };
        steps.getChildren().clear();
        for (int i = 0; i < labels.length; i++) {
            boolean passed = active != null && active.snapshot().state() == TaskSnapshot.State.SUCCEEDED
                    && i <= activeStep;
            steps.getChildren().add(text((i + 1) + "  " + labels[i], passed ? "step-done" : i == activeStep ? "step-current" : "step-next"));
        }
    }

    private void setStatus(TaskSnapshot.State state) {
        setStatus(UiStatus.task(state, operation, ""));
    }

    private void setStatus(UiStatus.Outcome outcome) {
        status.setText(outcome.title());
        status.getStyleClass().removeAll("badge", "badge-warning");
        status.getStyleClass().add(outcome.tone() == UiStatus.Tone.WARNING ? "badge-warning" : "badge");
    }

    private void showOutcome(UiStatus.Outcome outcome, String detail) {
        VBox guidance = card(text(outcome.title(), "section-title"),
                text(Objects.toString(detail, ""), "body-text"),
                text("下一步：" + outcome.nextStep(), "muted"));
        guidance.setId("outcome-guidance");
        findings.getChildren().add(guidance);
    }

    CompletionStage<Void> shutdown() {
        closed = true;
        detailRequest++;
        detailTasks.shutdownNow();
        refresh.stop();
        workflow.close();
        return tasks.shutdown();
    }
}
