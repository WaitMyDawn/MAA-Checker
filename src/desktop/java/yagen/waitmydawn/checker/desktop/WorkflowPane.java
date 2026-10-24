package yagen.waitmydawn.checker.desktop;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import yagen.waitmydawn.checker.action.LoaderPreparation;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.net.ModrinthClient;
import yagen.waitmydawn.checker.task.*;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;
import yagen.waitmydawn.checker.transaction.RestorePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import static yagen.waitmydawn.checker.desktop.Ui.*;

/** 分类授权和轮次时间线的展示适配；工作线程仅发布不可变会话快照。 */
final class WorkflowPane {
    interface Host {
        <T> void run(TaskService.Work<T> work, Consumer<T> display, String operation, Consumer<Throwable> failure);
        boolean busy();
    }
    private record Pending(RepairWorkflow.Decision decision, CompletableFuture<Boolean> answer) { }
    private record History(List<RepairWorkflow.State> sessions) { }
    private enum LoadState { IDLE, LOADING, READY, ERROR }
    record InitialData(InstanceIdentity identity, RepairPolicy policy, boolean saved,
                       List<RepairWorkflow.State> sessions) { }
    @FunctionalInterface
    interface InitialLoader {
        InitialData load(GameInstance instance) throws Exception;
    }
    private final Host host;
    private final Path output;
    private final Supplier<CheckerEngine.Options> options;
    private final LoaderPreparation.Factory loader;
    private final VBox root = new VBox(16);
    private final VBox decision = new VBox(10);
    private final VBox timeline = new VBox(12);
    private final VBox roundList = new VBox(12);
    private final Map<UUID, RepairWorkflow.Round> renderedRounds = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Boolean>> expandedSections = new HashMap<>();
    private final Label loadStatus = text("", "muted");
    private final Label policyStatus = text("", "muted");
    private final Label phaseStatus = text("尚未选择实例", "badge");
    private final Label nextStep = text("下一步：选择实例后加载修复策略与最近会话。", "muted");
    private final Label timelineCaption = text("", "caption");
    private final Label summary = text("按问题分类决定自动化程度；每轮开始前保存可恢复快照。", "muted");
    private final EnumMap<RepairPolicy.Category, ComboBox<RepairPolicy.Mode>> modes = new EnumMap<>(RepairPolicy.Category.class);
    private final ComboBox<VersionUpdateStrategy> versionStrategy =
            new ComboBox<>(FXCollections.observableArrayList(VersionUpdateStrategy.values()));
    private final Spinner<Integer> limit = new Spinner<>(1, 20, 10);
    private final Button start = button("检查并修复", "primary", () -> execute(null));
    private final Button resume = button("从停止轮次继续", "secondary", () -> execute(this.state));
    private final Button history = button("轮次记录", "secondary", this::history);
    private final Button clean = button("清理准备缓存", "secondary", this::clean);
    private final Button savePolicy = button("保存全局策略", "secondary", this::savePolicy);
    private final AtomicReference<RepairWorkflow.State> latest = new AtomicReference<>();
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicLong selectionRequest = new AtomicLong();
    private final ExecutorService initialTasks = Executors.newVirtualThreadPerTaskExecutor();
    private final javafx.beans.property.BooleanProperty running = new javafx.beans.property.SimpleBooleanProperty();
    private final javafx.beans.property.BooleanProperty startAvailable = new javafx.beans.property.SimpleBooleanProperty();
    private final InitialLoader initialLoader;
    private GameInstance instance;
    private RepairWorkflow.State state;
    private Map<UUID, RepairWorkflow.State> knownSessions = Map.of();
    private UUID requestedSession;
    private RepairPolicy savedPolicy;
    private LoadState loadState = LoadState.IDLE;
    private String loadError;
    private String saveError;
    private boolean policyDirty;
    private boolean applyingPolicy;
    private boolean closed;
    private Pending displayed;
    private boolean wasBusy;

    WorkflowPane(Host host, Path output, Supplier<CheckerEngine.Options> options, LoaderPreparation.Factory loader) {
        this(host, output, options, loader, instance -> loadInitial(output, instance));
    }

    WorkflowPane(Host host, Path output, Supplier<CheckerEngine.Options> options, LoaderPreparation.Factory loader,
                 InitialLoader initialLoader) {
        this.host = host; this.output = output; this.options = options; this.loader = loader;
        this.initialLoader = Objects.requireNonNull(initialLoader);
        start.setId("start-workflow"); resume.setId("resume-workflow"); history.setId("workflow-history");
        start.setVisible(false);
        start.setManaged(false);
        clean.setId("clean-preparation"); savePolicy.setId("save-policy");
        limit.setId("workflow-limit");
        versionStrategy.setId("policy-version-strategy");
        versionStrategy.setValue(VersionUpdateStrategy.MINIMUM);
        versionStrategy.setConverter(new StringConverter<>() {
            @Override public String toString(VersionUpdateStrategy value) {
                return value == null ? "" : value.title();
            }
            @Override public VersionUpdateStrategy fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        versionStrategy.setMinWidth(250);
        loadStatus.setId("workflow-load-state"); policyStatus.setId("workflow-policy-state");
        phaseStatus.setId("workflow-phase-status"); nextStep.setId("workflow-next-step");
        root.setId("workflow-panel");
        roundList.setId("workflow-rounds");
        VBox settings = new VBox(12);
        for (RepairPolicy.Category category : RepairPolicy.Category.values()) {
            ComboBox<RepairPolicy.Mode> choice = new ComboBox<>(FXCollections.observableArrayList(RepairPolicy.Mode.values()));
            choice.setId("policy-" + category.name().toLowerCase(Locale.ROOT));
            choice.setConverter(new StringConverter<>() {
                @Override public String toString(RepairPolicy.Mode value) { return value == null ? "" : switch (value) { case AUTO -> "自动执行"; case ASK -> "每次询问"; case STOP -> "遇到即停止"; }; }
                @Override public RepairPolicy.Mode fromString(String text) { throw new UnsupportedOperationException(); }
            });
            choice.setValue(GlobalRepairPolicy.defaults().modes().get(category));
            choice.valueProperty().addListener((observable, oldValue, newValue) -> policyEdited());
            choice.setMinWidth(150);
            modes.put(category, choice);
            Label label = text(category.title(), "body-text"); label.setMinWidth(200);
            settings.getChildren().add(new HBox(16, label, spacer(), choice));
        }
        limit.valueProperty().addListener((observable, oldValue, newValue) -> policyEdited());
        versionStrategy.valueProperty().addListener((observable, oldValue, newValue) -> policyEdited());
        settings.getChildren().addAll(
                new HBox(16, text("模组版本不足时", "body-text"), spacer(), versionStrategy),
                new HBox(16, text("每次连续修复轮次上限", "body-text"), spacer(), limit),
                policyStatus, savePolicy);
        TitledPane policy = new TitledPane("修复策略 · 分类授权与版本更新", settings); policy.setExpanded(false);
        root.getChildren().addAll(card(new HBox(12, text("可控的自动修复", "section-title"), spacer(), phaseStatus),
                summary, nextStep,
                loadStatus, new FlowPane(12, 10, resume, history, clean)), policy, decision, timeline);
        decision.setId("workflow-decision");
        controls();
    }

    private static InitialData loadInitial(Path output, GameInstance instance) throws Exception {
        GlobalRepairPolicy.Snapshot policy = GlobalRepairPolicy.load(output);
        return new InitialData(InstanceIdentity.of(instance), policy.policy(), policy.saved(),
                RepairWorkflow.history(instance));
    }

    VBox root() { return root; }
    javafx.beans.property.BooleanProperty startAvailableProperty() { return startAvailable; }
    void startFromPrimary() {
        if (!start.isDisabled()) execute(null);
    }
    void openSession(UUID workflowId) {
        if (workflowId == null || instance == null || closed) return;
        if (loadState == LoadState.READY) {
            RepairWorkflow.State selected = knownSessions.get(workflowId);
            if (selected == null) {
                summary.setText("该历史记录已不在当前实例台账中，请刷新历史。");
                return;
            }
            display(selected);
            requestedSession = null;
        } else {
            requestedSession = workflowId;
        }
    }
    void select(GameInstance selected) {
        if (closed) return;
        Pending old = pending.getAndSet(null); if (old != null) old.answer().cancel(false);
        long request = selectionRequest.incrementAndGet();
        instance = selected; state = null; latest.set(null); displayed = null;
        knownSessions = Map.of();
        requestedSession = null;
        savedPolicy = null; policyDirty = false; loadError = null; saveError = null;
        loadState = selected == null ? LoadState.IDLE : LoadState.LOADING;
        decision.getChildren().clear(); timeline.getChildren().clear();
        applyPolicy(GlobalRepairPolicy.defaults());
        summary.setText(selected == null ? "请选择实例后再加载修复策略。"
                : "正在加载全局策略与最近轮次记录…");
        setWorkflowOutcome(selected == null
                ? new UiStatus.Outcome("尚未选择实例", UiStatus.Tone.NEUTRAL, "选择实例后加载修复策略与最近会话。")
                : new UiStatus.Outcome("策略加载中", UiStatus.Tone.ACTIVE, "等待读取完成；完成前修复和还原操作保持禁用。"));
        updateLoadState();
        updatePolicyState();
        controls();
        if (selected == null) return;
        CompletableFuture.supplyAsync(() -> {
            try {
                return initialLoader.load(selected);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, initialTasks).whenComplete((data, error) ->
                Platform.runLater(() -> applyInitial(request, selected, data, error)));
    }

    private void applyInitial(long request, GameInstance selected, InitialData data, Throwable error) {
        if (closed || request != selectionRequest.get() || instance != selected) return;
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            loadState = LoadState.ERROR;
            loadError = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
            state = null;
            timeline.getChildren().clear();
            summary.setText("实例初始化失败；在修复状态明确前不会开始任务。");
            setWorkflowOutcome(new UiStatus.Outcome("实例初始化失败", UiStatus.Tone.WARNING,
                    "修复或移走损坏的策略/轮次记录后重新选择实例；不要删除仍被事务引用的恢复资料。"));
            timeline.getChildren().add(card(text("实例初始化失败", "section-title"),
                    text(loadError + "\n请修复或移走损坏的策略/轮次记录后重新选择实例。", "body-text")));
            updateLoadState();
            updatePolicyState();
            controls();
            return;
        }
        try {
            if (!data.identity().equals(InstanceIdentity.of(selected))) {
                throw new IOException("实例身份在加载期间发生变化");
            }
        } catch (Exception e) {
            applyInitial(request, selected, null, e);
            return;
        }
        savedPolicy = data.saved() ? data.policy() : null;
        policyDirty = false;
        loadState = LoadState.READY;
        loadError = null;
        saveError = null;
        applyPolicy(data.policy());
        knownSessions = data.sessions().stream().collect(java.util.stream.Collectors.toMap(
                RepairWorkflow.State::id, value -> value, (left, right) -> left, LinkedHashMap::new));
        RepairWorkflow.State requested = requestedSession == null ? null : knownSessions.get(requestedSession);
        RepairWorkflow.State unfinished = requested != null ? requested : data.sessions().stream()
                .filter(session -> session.phase() != RepairWorkflow.Phase.VERIFIED
                        && session.phase() != RepairWorkflow.Phase.RESTORED)
                .findFirst().orElse(null);
        if (unfinished != null) {
            display(unfinished);
            summary.setText("实例已就绪；已载入最近未完成会话：" + unfinished.message());
        } else {
            state = null;
            timeline.getChildren().clear();
            summary.setText(data.saved()
                    ? "实例已就绪；已加载全局保存策略。"
                    : "实例已就绪；尚无全局保存策略，将使用全部自动、10 轮、最低兼容版本默认值。");
            setWorkflowOutcome(new UiStatus.Outcome("实例已就绪", UiStatus.Tone.NEUTRAL,
                    "核对当前策略后开始诊断与修复，或打开轮次记录查看历史。"));
        }
        if (requestedSession != null && requested == null) {
            summary.setText("实例已就绪；请求的历史会话不存在或已损坏，请刷新历史记录。");
        }
        requestedSession = null;
        updateLoadState();
        updatePolicyState();
        controls();
    }
    private RepairPolicy policy() {
        EnumMap<RepairPolicy.Category, RepairPolicy.Mode> result = new EnumMap<>(RepairPolicy.Category.class);
        modes.forEach((category, choice) -> result.put(category, choice.getValue()));
        return new RepairPolicy(result, limit.getValue(), versionStrategy.getValue());
    }
    private void applyPolicy(RepairPolicy value) {
        applyingPolicy = true;
        try {
            value.modes().forEach((category, mode) -> modes.get(category).setValue(mode));
            versionStrategy.setValue(value.versionStrategy());
            limit.getValueFactory().setValue(value.maxRounds());
            policyDirty = false;
        } finally {
            applyingPolicy = false;
        }
    }
    private void policyEdited() {
        if (applyingPolicy || loadState != LoadState.READY) return;
        policyDirty = true;
        saveError = null;
        updateLoadState();
        updatePolicyState();
    }
    private void savePolicy() {
        if (instance == null || loadState != LoadState.READY || host.busy()) return;
        GameInstance selected = instance;
        long request = selectionRequest.get();
        RepairPolicy requested = policy();
        saveError = null;
        updatePolicyState();
        host.run(GlobalRepairPolicy.save(output, requested), saved -> {
            if (instance != selected || selectionRequest.get() != request) return;
            savedPolicy = saved;
            policyDirty = false;
            saveError = null;
            updateLoadState();
            updatePolicyState();
            summary.setText("全局修复策略已保存；所有实例都会使用这份策略。");
        }, "保存修复策略", error -> {
            if (instance != selected || selectionRequest.get() != request) return;
            saveError = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            summary.setText("全局策略保存失败；原有全局策略保持不变。");
            setWorkflowOutcome(UiStatus.error("保存修复策略", saveError));
            updateLoadState();
            updatePolicyState();
        });
    }
    private void updateLoadState() {
        loadStatus.getStyleClass().removeAll("muted", "badge", "badge-warning");
        switch (loadState) {
            case IDLE -> {
                loadStatus.setText("IDLE · 尚未选择实例");
                loadStatus.getStyleClass().add("muted");
            }
            case LOADING -> {
                loadStatus.setText("LOADING · 正在读取全局策略与最近会话");
                loadStatus.getStyleClass().add("badge-warning");
            }
            case READY -> {
                loadStatus.setText((savedPolicy == null
                        ? "READY · 全局默认策略（全部自动 / 10 轮 / 最低兼容版）"
                        : "READY · 已加载全局策略与最近会话")
                        + (policyDirty ? " · 有未保存修改" : ""));
                loadStatus.getStyleClass().add(policyDirty ? "badge-warning" : "badge");
            }
            case ERROR -> {
                loadStatus.setText("ERROR · " + Objects.toString(loadError, "实例初始化失败"));
                loadStatus.getStyleClass().add("badge-warning");
            }
        }
    }
    private void setWorkflowOutcome(UiStatus.Outcome outcome) {
        phaseStatus.setText(outcome.title());
        phaseStatus.getStyleClass().removeAll("badge", "badge-warning");
        phaseStatus.getStyleClass().add(outcome.tone() == UiStatus.Tone.WARNING ? "badge-warning" : "badge");
        nextStep.setText("下一步：" + outcome.nextStep());
    }
    private void showWorkflowFailure(String operation, Throwable error) {
        String detail = Objects.toString(error == null ? null : error.getMessage(), "操作失败");
        setWorkflowOutcome(UiStatus.error(operation, detail));
        summary.setText(detail);
    }
    private void updatePolicyState() {
        policyStatus.getStyleClass().removeAll("muted", "badge-warning");
        if (loadState == LoadState.ERROR) {
            policyStatus.setText("策略状态未知；必须先解决实例初始化错误。");
            policyStatus.getStyleClass().add("badge-warning");
        } else if (loadState != LoadState.READY) {
            policyStatus.setText("策略加载完成后才能编辑、保存或开始修复。");
            policyStatus.getStyleClass().add("muted");
        } else if (saveError != null) {
            policyStatus.setText("保存失败：" + saveError + "；原保存文件保持不变，本次仍使用当前显示值。");
            policyStatus.getStyleClass().add("badge-warning");
        } else if (savedPolicy == null) {
            policyStatus.setText(policyDirty
                    ? "有未保存修改；本次会话使用当前显示值，保存后才会成为全局默认策略。"
                    : "尚无全局保存策略；当前使用全部自动、10 轮、最低兼容版本默认值。");
            policyStatus.getStyleClass().add(policyDirty ? "badge-warning" : "muted");
        } else if (policyDirty) {
            policyStatus.setText("有未保存修改；本次会话仍使用当前显示值，保存后才会替换全局默认策略。");
            policyStatus.getStyleClass().add("badge-warning");
        } else {
            policyStatus.setText("已加载全局策略；本次会话直接使用当前显示值。");
            policyStatus.getStyleClass().add("muted");
        }
    }
    private void execute(RepairWorkflow.State previous) {
        if (instance == null || host.busy()) return;
        if (loadState != LoadState.READY) {
            summary.setText("全局策略与最近会话尚未加载完成，不能开始修复。");
            return;
        }
        if (previous == null && state != null && state.phase() != RepairWorkflow.Phase.VERIFIED) {
            summary.setText("此实例有当前修复会话，请先继续该会话或恢复轮前状态。"); return;
        }
        GameInstance selected = instance;
        long request = selectionRequest.get();
        RepairPolicy sessionPolicy = policy();
        pending.set(null); displayed = null; decision.getChildren().clear();
        summary.setText("本次会话将使用当前显示策略" + (policyDirty ? "（包含未保存修改）" : "") + "。");
        RepairWorkflow.Decider decider = new RepairWorkflow.Decider() {
            @Override public CompletionStage<Boolean> decide(RepairWorkflow.Decision decisionRequest) {
                var answer = new CompletableFuture<Boolean>();
                pending.set(new Pending(decisionRequest, answer));
                return answer;
            }

            @Override public CompletionStage<ModrinthClient.Candidate> chooseVersion(
                    RepairWorkflow.Decision decisionRequest,
                    List<ModrinthClient.Candidate> candidates) {
                CompletableFuture<ModrinthClient.Candidate> answer = new CompletableFuture<>();
                Platform.runLater(() -> answer.complete(showVersionChoice(decisionRequest, candidates)));
                return answer;
            }
        };
        host.run(RepairWorkflow.start(selected, output, options.get(), sessionPolicy, previous, loader, decider,
                latest::set), update -> {
            if (instance == selected && selectionRequest.get() == request) display(update);
        }, "多轮诊断修复", error -> {
            if (instance == selected && selectionRequest.get() == request) {
                showWorkflowFailure("多轮诊断修复", error);
            }
        });
    }

    private ModrinthClient.Candidate showVersionChoice(
            RepairWorkflow.Decision request, List<ModrinthClient.Candidate> candidates) {
        Dialog<ModrinthClient.Candidate> dialog = new Dialog<>();
        dialog.setTitle("选择模组更新版本");
        dialog.setHeaderText("为 " + request.action().modId() + " 选择同时满足全部要求的版本");
        if (root.getScene() != null && root.getScene().getWindow() != null) {
            dialog.initOwner(root.getScene().getWindow());
        }
        ButtonType update = new ButtonType("使用此版本", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(update, ButtonType.CANCEL);
        dialog.getDialogPane().setId("version-choice-dialog");

        ComboBox<ModrinthClient.Candidate> versions =
                new ComboBox<>(FXCollections.observableArrayList(candidates));
        versions.setId("version-choice");
        versions.setMaxWidth(Double.MAX_VALUE);
        versions.setConverter(new StringConverter<>() {
            @Override public String toString(ModrinthClient.Candidate value) {
                if (value == null) return "";
                String date = value.datePublished() == null ? ""
                        : value.datePublished().length() >= 10
                        ? " · " + value.datePublished().substring(0, 10) : "";
                return value.versionNumber() + " · " + value.fileName() + date;
            }
            @Override public ModrinthClient.Candidate fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        versions.setValue(candidates.getFirst());

        TextArea evidence = new TextArea(String.join("\n", request.action().errorText()));
        evidence.setEditable(false);
        evidence.setWrapText(true);
        evidence.setPrefRowCount(4);
        VBox content = new VBox(12,
                text("项目：" + candidates.getFirst().slug() + " · " + candidates.size()
                        + " 个兼容版本", "body-text"),
                text("必须满足：" + String.join("；", request.action().versionRanges()), "caption"),
                versions,
                new TitledPane("查看诊断依据", evidence));
        content.setPrefWidth(620);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == update ? versions.getValue() : null);
        return dialog.showAndWait().orElse(null);
    }
    private void history() {
        if (instance == null || loadState != LoadState.READY || host.busy()) return;
        GameInstance selected = instance;
        long request = selectionRequest.get();
        host.run(context -> TaskResult.success(new History(RepairWorkflow.history(selected)), "轮次记录已载入"), result -> {
            if (instance != selected || selectionRequest.get() != request) return;
            if (result.sessions().isEmpty()) { summary.setText("该实例暂无轮次会话；可开始诊断与修复。"); return; }
            ComboBox<RepairWorkflow.State> sessions = new ComboBox<>(FXCollections.observableArrayList(result.sessions()));
            sessions.setMaxWidth(Double.MAX_VALUE);
            sessions.setConverter(new StringConverter<>() {
                @Override public String toString(RepairWorkflow.State s) { return s == null ? "" : java.time.Instant.ofEpochMilli(s.createdAt()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " · " + name(s.phase()) + " · " + s.rounds().size() + " 个轮次记录"; }
                @Override public RepairWorkflow.State fromString(String text) { throw new UnsupportedOperationException(); }
            });
            sessions.valueProperty().addListener((o, a, b) -> {
                if (b != null && instance == selected && selectionRequest.get() == request) {
                    timeline.getChildren().clear();
                    display(b);
                    timeline.getChildren().addFirst(sessions);
                }
            });
            sessions.setValue(result.sessions().getFirst());
        }, "读取轮次记录", error -> {
            if (instance == selected && selectionRequest.get() == request) {
                showWorkflowFailure("读取轮次记录", error);
            }
        });
    }
    private void clean() {
        if (instance == null || loadState != LoadState.READY || host.busy()) return;
        GameInstance selected = instance;
        long request = selectionRequest.get();
        host.run(PreparationCleanup.preview(selected), preview -> {
            if (instance != selected || selectionRequest.get() != request) return;
            Button confirm = button("确认清理准备文件", "secondary", () -> host.run(PreparationCleanup.clear(selected, preview), count -> {
                if (instance != selected || selectionRequest.get() != request) return;
                summary.setText("已清理 " + count + " 个准备文件；轮次快照、事务和独立运行库保留。");
                if (state != null) display(state); else timeline.getChildren().clear();
            }, "清理准备缓存", error -> {
                if (instance == selected && selectionRequest.get() == request) {
                    showWorkflowFailure("清理准备缓存", error);
                }
            }));
            confirm.disableProperty().bind(running);
            timeline.getChildren().addFirst(card(text("清理预览", "section-title"),
                    text(preview.files().size() + " 个文件 · " + String.format("%.1f MiB", preview.bytes() / 1048576.0)
                            + "\n仅清除下载候选和安装器准备产物；原有审核方案需重新准备。恢复快照、事务备份和实例运行库保留。", "body-text"), confirm));
        }, "统计准备缓存", error -> {
            if (instance == selected && selectionRequest.get() == request) {
                showWorkflowFailure("统计准备缓存", error);
            }
        });
    }
    void refresh() {
        RepairWorkflow.State update = latest.getAndSet(null);
        if (update != null && !update.equals(state)) display(update);
        Pending request = pending.get();
        if (request != displayed) {
            displayed = request; decision.getChildren().clear();
            if (request != null && !request.answer().isDone() && host.busy()) {
                var action = request.decision().action();
                Button allow = button("允许此操作", "primary", () -> answer(request, true)); allow.setId("workflow-allow");
                Button reject = button("拒绝并暂停", "secondary", () -> answer(request, false)); reject.setId("workflow-decline");
                TextArea evidence = new TextArea(String.join("\n", action.errorText())); evidence.setEditable(false); evidence.setWrapText(true); evidence.setPrefRowCount(3);
                TitledPane details = new TitledPane("查看诊断依据", evidence); details.setExpanded(false);
                VBox prompt = card(text("需要你的决定 · " + request.decision().category().title(), "section-title"),
                        text(action.reason(), "body-text"), text("对象：" + action.modId() + " · " + (action.jarFile() == null ? "候选尚未下载" : action.jarFile()), "caption"),
                        details, new FlowPane(12, 8, allow, reject));
                prompt.getStyleClass().add("decision-card"); decision.getChildren().add(prompt);
            }
        }
        if (!host.busy()) { decision.getChildren().clear(); pending.set(null); displayed = null; }
        if (wasBusy && !host.busy() && state != null) display(state);
        wasBusy = host.busy();
        controls();
    }
    private void answer(Pending request, boolean allow) {
        decision.getChildren().clear(); summary.setText(allow ? "已允许，正在准备候选与提交方案…" : "已拒绝，正在保存暂停状态…");
        request.answer().complete(allow);
    }
    private void display(RepairWorkflow.State value) {
        try {
            if (instance == null || !value.instance().equals(InstanceIdentity.of(instance))) {
                summary.setText("收到其他实例的轮次状态，已忽略。");
                return;
            }
        } catch (IOException e) {
            summary.setText("实例身份校验失败：" + e.getMessage());
            return;
        }
        boolean same = state != null && state.id().equals(value.id());
        state = value;
        summary.setText(value.message());
        setWorkflowOutcome(UiStatus.workflow(value.phase(), host.busy()));
        timelineCaption.setText("轮次时间线 · " + value.rounds().size() + " 个记录 · 初态、授权、提交和验证分开记录");
        renderRounds(value.rounds());
        if (!same || !timeline.getChildren().contains(roundList)) timeline.getChildren().setAll(timelineCaption, roundList);
        if (!host.busy()) {
            timeline.getChildren().removeIf(node -> node != timelineCaption && node != roundList && !(node instanceof ComboBox<?>));
        }
        controls();
    }

    private void renderRounds(List<RepairWorkflow.Round> rounds) {
        double scroll = outerScrollPosition();
        boolean structureChanged = roundList.getChildren().size() != rounds.size();
        if (!structureChanged) {
            List<UUID> renderedIds = new ArrayList<>(renderedRounds.keySet());
            for (int i = 0; i < rounds.size(); i++) {
                if (!rounds.get(i).id().equals(renderedIds.get(i))) {
                    structureChanged = true;
                    break;
                }
            }
        }
        if (structureChanged) {
            roundList.getChildren().clear();
            renderedRounds.clear();
            for (RepairWorkflow.Round round : rounds) {
                roundList.getChildren().add(roundCard(state, round));
                renderedRounds.put(round.id(), round);
            }
        } else {
            for (int i = 0; i < rounds.size(); i++) {
                RepairWorkflow.Round round = rounds.get(i);
                if (!Objects.equals(renderedRounds.get(round.id()), round)) {
                    roundList.getChildren().set(i, roundCard(state, round));
                    renderedRounds.put(round.id(), round);
                }
            }
        }
        if (scroll >= 0) Platform.runLater(() -> setOuterScroll(scroll));
    }

    private double outerScrollPosition() {
        Node node = roundList;
        while (node != null) {
            if (node.getParent() instanceof ScrollPane scroll) return scroll.getVvalue();
            node = node.getParent();
        }
        return -1;
    }

    private void setOuterScroll(double value) {
        Node node = roundList;
        while (node != null) {
            if (node.getParent() instanceof ScrollPane scroll) {
                scroll.setVvalue(value);
                return;
            }
            node = node.getParent();
        }
    }
    private VBox roundCard(RepairWorkflow.State session, RepairWorkflow.Round round) {
        var snapshot = round.before();
        UiStatus.Outcome roundStatus = UiStatus.workflow(round.phase(), false);
        VBox card = card(new HBox(12, text(String.format("%02d", round.number()), "round-number"),
                text("第 " + round.number() + " 轮", "section-title"), spacer(),
                text(roundStatus.title(), roundStatus.tone() == UiStatus.Tone.WARNING ? "badge-warning" : "badge")),
                text(round.message(), "body-text"),
                new FlowPane(12, 8, text("Minecraft " + snapshot.minecraft(), "step-next"),
                        text(snapshot.loader() + " " + Objects.toString(snapshot.loaderVersion(), ""), "step-next"), text(snapshot.mods().size() + " 个模组", "step-next")));
        card.getChildren().add(stageSummary(round));
        VBox mods = new VBox(6, snapshot.mods().stream()
                .map(m -> text(m.filename() + " · " + m.versions(), "caption"))
                .toArray(Node[]::new));
        TitledPane inventory = rememberExpansion(round.id(), "inventory",
                new TitledPane("轮前模组清单与声明版本", mods));
        card.getChildren().add(inventory);
        if (!round.events().isEmpty()) {
            FlowPane changes = new FlowPane(10, 8);
            List<yagen.waitmydawn.checker.transaction.ActionRecord> committed = round.events().stream().map(RepairWorkflow.Event::committed).filter(Objects::nonNull).toList();
            for (var action : committed.stream().filter(a -> a.kind() != yagen.waitmydawn.checker.transaction.ActionRecord.Kind.INSTANCE_LIBRARY).limit(3).toList()) {
                String label = switch (action.kind()) { case REMOVE_MOD -> "− 停用 "; case INSTALL_MOD -> "+ 补装 "; case REPLACE_VERSION -> "↗ 版本调整 "; default -> "✓ 配置调整 "; };
                changes.getChildren().add(text(label + action.filename(), "tag"));
            }
            if (committed.isEmpty()) changes.getChildren().add(text("本轮尚未提交文件操作", "caption"));
            else changes.getChildren().add(text("共 " + committed.size() + " 项提交", "caption"));
            card.getChildren().add(changes);
            VBox events = new VBox(8, round.events().stream()
                    .map(event -> new VBox(4,
                            new HBox(10, text(event.status(), event.committed() == null ? "caption" : "badge"),
                                    text(event.title(), "body-text")),
                            text(event.detail(), "muted")))
                    .toArray(Node[]::new));
            TitledPane detail = rememberExpansion(round.id(), "events",
                    new TitledPane("授权与操作台账 · " + round.events().size() + " 条记录", events));
            card.getChildren().add(detail);
        }
        Button restore = button("还原到此轮开始前", "secondary", () -> preview(session, round));
        restore.setId("restore-round-" + round.number());
        boolean archived = round.phase() == RepairWorkflow.Phase.RESTORED
                || session.restoreTarget() != null && !session.rounds().get(session.restoreTarget()).id().equals(round.id());
        restore.disableProperty().bind(running.or(new javafx.beans.property.SimpleBooleanProperty(archived)));
        card.getChildren().add(restore); return card;
    }

    private FlowPane stageSummary(RepairWorkflow.Round round) {
        String authorization = "按策略";
        for (int i = round.events().size() - 1; i >= 0; i--) {
            String status = round.events().get(i).status();
            if (status.contains("授权") || status.contains("允许") || status.contains("拒绝")
                    || status.contains("停止") || status.contains("决定")) {
                authorization = status;
                break;
            }
        }
        List<yagen.waitmydawn.checker.transaction.ActionRecord> committed = round.events().stream()
                .map(RepairWorkflow.Event::committed).filter(Objects::nonNull)
                .filter(a -> a.kind() != yagen.waitmydawn.checker.transaction.ActionRecord.Kind.INSTANCE_LIBRARY)
                .toList();
        String preparation = committed.stream().anyMatch(a -> a.kind() == yagen.waitmydawn.checker.transaction.ActionRecord.Kind.INSTALL_MOD)
                ? "候选已就绪"
                : committed.stream().anyMatch(a -> a.kind() == yagen.waitmydawn.checker.transaction.ActionRecord.Kind.REPLACE_VERSION)
                ? "版本配置已准备" : "无下载请求";
        String submission = committed.isEmpty() ? "无文件提交" : committed.size() + " 项已提交";
        String verification = round.report() != null && !round.report().isBlank()
                ? "报告已生成" : UiStatus.workflow(round.phase(), false).title();
        FlowPane stages = new FlowPane(8, 8,
                text("授权 · " + authorization, "stage-pill"),
                text("准备 · " + preparation, "stage-pill"),
                text("提交 · " + submission, "stage-pill"),
                text("验证 · " + verification, "stage-pill"));
        stages.setId("round-stages-" + round.number());
        return stages;
    }

    private TitledPane rememberExpansion(UUID round, String key, TitledPane pane) {
        Map<String, Boolean> values = expandedSections.computeIfAbsent(round, ignored -> new HashMap<>());
        if (values.containsKey(key)) pane.setExpanded(values.get(key));
        else pane.setExpanded(false);
        pane.expandedProperty().addListener((observable, oldValue, expanded) -> values.put(key, expanded));
        pane.setId("round-" + round + "-" + key);
        return pane;
    }

    private void preview(RepairWorkflow.State session, RepairWorkflow.Round round) {
        if (instance == null || loadState != LoadState.READY || host.busy()) return;
        GameInstance selected = instance;
        long request = selectionRequest.get();
        host.run(RepairWorkflow.preview(selected, output.resolve("state"), session, round.id()), preview -> {
            if (instance != selected || selectionRequest.get() != request) return;
            Node files = restoreItems(preview, selected, round);
            Button confirm = button("确认还原这 " + preview.files().size() + " 项", "danger", () -> host.run(
                    RepairWorkflow.restore(selected, output, preview, latest::set), value -> {
                        if (instance == selected && selectionRequest.get() == request) display(value);
                    }, "恢复轮前快照", error -> {
                        if (instance == selected && selectionRequest.get() == request) {
                            showWorkflowFailure("恢复轮前快照", error);
                        }
                    })); confirm.setId("confirm-round-restore");
            confirm.disableProperty().bind(running);
            TextArea technical = new TextArea("计划摘要：" + preview.plan().digest() + "\n"
                    + preview.plan().steps().stream()
                    .map(step -> step.key() + " · " + Objects.toString(step.fromHash(), "不存在")
                            + " → " + Objects.toString(step.toHash(), "不存在"))
                    .collect(java.util.stream.Collectors.joining("\n")));
            technical.setEditable(false);
            technical.setWrapText(true);
            technical.setPrefRowCount(5);
            TitledPane technicalDetails = new TitledPane("技术详情 · 内部键与 SHA-256", technical);
            technicalDetails.setExpanded(false);
            VBox restoreCard = card(text("还原预览 · 第 " + round.number() + " 轮前", "section-title"),
                    text("将按「模组 → 版本配置 → HMCL 标记」恢复（" + preview.plan().summary()
                            + "），以下差异包含暂停期间的手动变更。世界和存档不在快照内；当前内容会通过事务保留。",
                            "body-text"), files, technicalDetails, confirm);
            restoreCard.setId("restore-preview-card");
            timeline.getChildren().removeIf(node -> "restore-preview-card".equals(node.getId()));
            timeline.getChildren().addFirst(restoreCard);
        }, "预览轮次还原", error -> {
            if (instance == selected && selectionRequest.get() == request) {
                showWorkflowFailure("预览轮次还原", error);
            }
        });
    }

    private Node restoreItems(RepairWorkflow.RestorePreview preview, GameInstance selected,
                              RepairWorkflow.Round round) {
        Map<String, String> targetMods = new TreeMap<>();
        round.before().mods().forEach(mod -> targetMods.put(mod.filename(),
                String.join(", ", mod.versions().values())));
        VBox rows = new VBox(10);
        rows.setId("restore-preview-items");
        int index = 0;
        for (RestorePlan.Step step : preview.plan().steps()) {
            String filename = restoreFilename(step.key(), preview);
            String action = step.fromHash() == null ? "新增" : step.toHash() == null ? "移除" : "替换";
            String current;
            String target;
            if (step.phase() == RestorePlan.Phase.MOD) {
                current = preview.currentModVersions().getOrDefault(filename, "未安装");
                target = targetMods.getOrDefault(filename, "未安装");
            } else if (step.phase() == RestorePlan.Phase.VERSION) {
                current = selected.mcVersion() + " / " + selected.loader() + " "
                        + Objects.toString(selected.loaderVersion(), "-");
                target = round.before().minecraft() + " / " + round.before().loader() + " "
                        + Objects.toString(round.before().loaderVersion(), "-");
            } else {
                current = step.fromHash() == null ? "不存在" : "当前整合包补全标记";
                target = step.toHash() == null ? "不存在" : "轮前整合包补全标记";
            }
            VBox row = new VBox(5,
                    new HBox(10, text(action, "tag"), text(filename, "body-text")),
                    text("当前：" + current, "muted"),
                    text("目标：" + target, "muted"));
            row.getStyleClass().add("restore-row");
            row.setId("restore-item-" + index++);
            rows.getChildren().add(row);
        }
        return rows;
    }

    private static String restoreFilename(String key, RepairWorkflow.RestorePreview preview) {
        if (key.equals("marker")) return "modpack.cfg";
        if (key.equals("version")) return Path.of(preview.current().versionFile()).getFileName().toString();
        if (key.startsWith("mod/")) return key.substring(4);
        return key;
    }

    void controls() {
        boolean busy = host.busy();
        boolean ready = loadState == LoadState.READY;
        running.set(busy);
        start.setDisable(busy || !ready || instance == null
                || state != null && state.phase() != RepairWorkflow.Phase.VERIFIED);
        startAvailable.set(!start.isDisabled());
        resume.setDisable(busy || !ready || state == null
                || state.phase() == RepairWorkflow.Phase.VERIFIED || state.phase() == RepairWorkflow.Phase.RESTORING);
        history.setDisable(busy || !ready || instance == null);
        modes.values().forEach(box -> box.setDisable(busy || !ready));
        limit.setDisable(busy || !ready);
        clean.setDisable(busy || !ready || instance == null);
        savePolicy.setDisable(busy || !ready || instance == null);
    }
    void close() {
        closed = true;
        selectionRequest.incrementAndGet();
        Pending old = pending.getAndSet(null);
        if (old != null) old.answer().cancel(false);
        initialTasks.shutdownNow();
    }
    private static String name(RepairWorkflow.Phase phase) {
        return UiStatus.workflow(phase, false).title();
    }
}
