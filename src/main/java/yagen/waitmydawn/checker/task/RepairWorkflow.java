package yagen.waitmydawn.checker.task;

import yagen.waitmydawn.checker.action.*;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.net.*;
import yagen.waitmydawn.checker.transaction.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 可持久化的多轮修复协调器；引擎、授权决策和展示层均不依赖具体桌面框架。 */
public final class RepairWorkflow {
    public enum Phase { CHECKING, PLANNING, WAITING, APPLYING, VERIFIED, NOT_PASSED, PAUSED, RESTORING, RESTORED }
    public record Event(String title, String status, String detail, ActionRecord committed) { }
    public record Decision(UUID id, PlannedAction action, RepairPolicy.Category category,
                           List<ModrinthClient.Candidate> versionCandidates) {
        public Decision {
            versionCandidates = versionCandidates == null ? List.of() : List.copyOf(versionCandidates);
        }

        public Decision(UUID id, PlannedAction action, RepairPolicy.Category category) {
            this(id, action, category, List.of());
        }
    }
    public record Round(UUID id, int number, RoundSnapshot before, Phase phase, String message,
                        List<Event> events, List<UUID> transactions, String report) {
        public Round { events = List.copyOf(events); transactions = List.copyOf(transactions); }
    }
    public record State(int schema, UUID id, InstanceIdentity instance, long createdAt, RepairPolicy policy,
                        int current, Phase phase, String message, List<Round> rounds, Decision decision,
                        UUID restoreReceipt, Integer restoreTarget, List<UUID> restoreReceipts) {
        public State { rounds = List.copyOf(rounds); restoreReceipts = restoreReceipts == null ? List.of() : List.copyOf(restoreReceipts); }
        public Round round() { return rounds.get(current); }
    }
    /**
     * 轮次恢复预览。
     *
     * @param plan 语义化恢复计划；预览与执行通过 {@link RestorePlan#digest()} 核对同一计划
     */
    public record RestorePreview(UUID workflow, UUID round, RepairInputs current, RestorePlan plan,
                                 Map<String, String> currentModVersions) {
        public RestorePreview {
            Objects.requireNonNull(plan, "plan");
            currentModVersions = Map.copyOf(currentModVersions);
        }

        /** 按执行顺序返回恢复目标键，保持既有预览调用方的读取方式。 */
        public List<String> files() {
            return plan.keys();
        }

        public String digest() {
            return plan.digest();
        }
    }
    @FunctionalInterface
    public interface Decider {
        CompletionStage<Boolean> decide(Decision decision);

        default CompletionStage<ModrinthClient.Candidate> chooseVersion(
                Decision decision, List<ModrinthClient.Candidate> candidates) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("当前运行入口没有版本选择界面"));
        }
    }
    private RepairWorkflow() { }

    public static TaskService.Work<State> start(GameInstance instance, Path output, CheckerEngine.Options options,
                                               RepairPolicy policy, State previous, LoaderPreparation.Factory loader,
                                               Decider decider, Consumer<State> observer) {
        return run(instance, output, options, policy, previous, null, loader, decider, observer);
    }

    public static TaskService.Work<State> run(GameInstance instance, Path output, CheckerEngine.Options options,
                                             RepairPolicy policy, State previous, RepairTasks.Candidates candidates,
                                             LoaderPreparation.Factory loader, Decider decider, Consumer<State> observer) {
        return context -> {
            InstanceStorage.requireIsolatedMods(instance);
            try (var lease = WorkflowLease.acquire(instance)) {
                return new Runner(instance, output, options, policy, previous, candidates, loader, decider, observer, context).run();
            }
        };
    }

    public static List<State> history(GameInstance instance) throws IOException {
        Path directory = InstanceStorage.directory(InstanceStorage.root(instance, false), "workflows", false);
        if (!Files.exists(directory)) return List.of();
        List<State> states = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                // 分支回收会先把工作流改名；该残留由存储清理继续处理，不属于可读历史。
                if (name.contains(".tmp-") || name.equals("policy.json") || name.endsWith(".json.reclaimed")) continue;
                states.add(readHistoryEntry(instance, path));
            }
        }
        return states.stream().sorted(Comparator.comparingLong(State::createdAt).reversed()).toList();
    }

    /**
     * 读取并校验一条持久化会话，供索引逐条隔离损坏记录。
     * 调用方仍需以返回的实例状态和磁盘台账作为事实来源，索引不得缓存可变业务结果。
     */
    public static State readHistoryEntry(GameInstance instance, Path path) throws IOException {
        Path directory = InstanceStorage.directory(InstanceStorage.root(instance, false), "workflows", false);
        if (!PathSafety.samePath(path.getParent(), directory)) {
            throw new IOException("修复会话文件不在实例台账目录");
        }
        String name = path.getFileName().toString();
        if (!name.endsWith(".json")) throw new IOException("未知修复会话文件");
        State state = RoundSnapshot.readJournal(instance, name, State.class);
        validate(instance, state);
        if (!name.equals(state.id() + ".json")) throw new IOException("修复会话文件身份不符");
        return state;
    }

    /** 区分“没有保存策略”和“已经读取到保存值”，避免读取失败时静默落回默认策略。 */
    public record PolicySnapshot(RepairPolicy policy, boolean saved) {
        public PolicySnapshot {
            Objects.requireNonNull(policy, "policy");
        }
    }

    public static PolicySnapshot policySnapshot(GameInstance instance) throws IOException {
        Path directory = InstanceStorage.directory(InstanceStorage.root(instance, false), "workflows", false);
        Path file = directory.resolve("policy.json");
        if (!Files.exists(file)) return new PolicySnapshot(RepairPolicy.defaults(), false);
        return new PolicySnapshot(RoundSnapshot.readJournal(instance, "policy.json", RepairPolicy.class), true);
    }

    public static RepairPolicy policy(GameInstance instance) throws IOException {
        return policySnapshot(instance).policy();
    }

    public static TaskService.Work<RepairPolicy> savePolicy(GameInstance instance, RepairPolicy policy) {
        return context -> {
            try (var lease = WorkflowLease.acquire(instance)) {
                context.atomicFileAction(() -> RoundSnapshot.writeJournal(instance, "policy.json", policy));
                return TaskResult.success(policy, "此实例的分类修复策略已保存");
            }
        };
    }

    private static void validate(GameInstance instance, State state) throws IOException {
        if (state.schema() != 1 || state.id() == null || !InstanceIdentity.of(instance).equals(state.instance())
                || state.current() < 0 || state.current() >= state.rounds().size() || state.policy() == null) throw new IOException("修复会话身份或格式不匹配");
        Set<UUID> rounds = new HashSet<>();
        for (Round round : state.rounds()) {
            if (round.id() == null || !rounds.add(round.id()) || round.number() < 1 || round.before() == null
                    || !round.before().inputs().versionFile().equals(state.instance().versionFile())
                    || !round.before().inputs().modsDirectory().equals(state.instance().modsDirectory())) throw new IOException("轮次快照绑定异常");
        }
    }

    private static void requireLatest(GameInstance instance, State state) throws IOException {
        if (history(instance).stream().anyMatch(other -> other.createdAt() > state.createdAt() && other.phase() != Phase.RESTORED)) {
            throw new IOException("请先在轮次记录处理或还原更新的修复会话，再继续此历史会话");
        }
    }

    /**
     * 预览轮次还原。
     *
     * @param stateRoot 应用 state 根目录；对账需要读取其中的事务账本
     */
    public static TaskService.Work<RestorePreview> preview(GameInstance instance, Path stateRoot,
                                                           State state, UUID round) {
        return context -> {
            try (var lease = WorkflowLease.acquire(instance)) {
                State disk = load(instance, state.id());
                requireLatest(instance, disk);
                if (!disk.equals(state)) throw new IOException("会话已变化，请刷新记录");
                disk = reconcileForUpdate(instance, stateRoot, disk, null);
                Round target = disk.rounds().stream().filter(r -> r.id().equals(round)).findFirst().orElseThrow();
                if (disk.restoreTarget() != null && !disk.rounds().get(disk.restoreTarget()).id().equals(round)) throw new IOException("请先完成上次选定轮次的还原");
                if (target.phase() == Phase.RESTORED) throw new IOException("此轮已归档还原，请选择当前分支的轮次");
                RepairInputs current = RepairInputs.capture(instance);
                RestorePlan plan = RestorePlan.between(disk.id(), round, current, target.before().inputs());
                for (String key : plan.keys()) target.before().candidate(instance, key);
                validateVersionLibraries(instance, target.before(), plan);
                return TaskResult.success(new RestorePreview(disk.id(), round, current, plan,
                                modVersions(instance.modsDir())),
                        "已核对快照；" + plan.summary() + "，按模组 → 版本配置 → HMCL 标记执行（计划 "
                                + plan.shortDigest() + "）；包含暂停期间的手动变更");
            }
        };
    }

    private static Map<String, String> modVersions(Path mods) throws IOException {
        if (!Files.isDirectory(mods)) return Map.of();
        Map<String, List<String>> byFile = new java.util.TreeMap<>();
        ModJarIndex.Meta meta = ModJarIndex.scanMeta(mods);
        meta.jarOf().forEach((modId, filename) -> byFile
                .computeIfAbsent(filename, ignored -> new ArrayList<>())
                .add(modId + " " + Objects.toString(meta.versionOf(modId), "?")));
        Map<String, String> result = new java.util.TreeMap<>();
        byFile.forEach((file, versions) -> result.put(file, String.join(", ", versions)));
        return result;
    }

    public static TaskService.Work<State> restore(GameInstance instance, Path output, RestorePreview preview, Consumer<State> observer) {
        return context -> {
            InstanceStorage.requireIsolatedMods(instance);
            try (var lease = WorkflowLease.acquire(instance)) {
            State disk = load(instance, preview.workflow());
            requireLatest(instance, disk);
            if (!RepairInputs.capture(instance).equals(preview.current())) throw new IOException("恢复预览后实例已变化，请重新预览");
            waitUntilQuiet(instance, context);
            disk = reconcileForUpdate(instance, output.resolve("state"), disk, observer);
            int index = -1;
            for (int n = 0; n < disk.rounds().size(); n++) if (disk.rounds().get(n).id().equals(preview.round())) index = n;
            if (index < 0) throw new IOException("恢复轮次不存在");
            if (disk.restoreTarget() != null && disk.restoreTarget() != index) throw new IOException("请先完成上次选定轮次的还原");
            if (disk.rounds().get(index).phase() == Phase.RESTORED) throw new IOException("不能重复还原已归档分支");
            RoundSnapshot snapshot = disk.rounds().get(index).before();
            RestorePlan plan = RestorePlan.between(disk.id(), preview.round(), preview.current(), snapshot.inputs());
            if (!plan.digest().equals(preview.plan().digest()) || !plan.keys().equals(preview.files())) {
                throw new IOException("恢复计划已变化，请重新预览");
            }
            for (String key : plan.keys()) snapshot.candidate(instance, key);
            validateVersionLibraries(instance, snapshot, plan);
            recoverPending(instance, output, context);
            if (!RepairInputs.capture(instance).equals(preview.current())) throw new IOException("已恢复未提交动作，请重新预览恢复清单");
            final int target = index;
            UUID receipt;
            context.log("恢复计划 " + plan.shortDigest() + "：" + plan.summary()
                    + "；执行顺序 模组 → 版本配置 → HMCL 标记");
            try (var transaction = TransactionSession.create(output.resolve("state"), instance)) {
                receipt = transaction.header().sessionId();
                List<UUID> receipts = new ArrayList<>(disk.restoreReceipts()); receipts.add(receipt);
                State restoring = new State(1, disk.id(), disk.instance(), disk.createdAt(), disk.policy(), disk.current(),
                        Phase.RESTORING, "正在恢复第 " + disk.rounds().get(target).number() + " 轮前", disk.rounds(), null, receipt, target, receipts);
                persist(instance, restoring, observer);
                    Map<String, String> current = preview.current().hashes();
                    for (RestorePlan.Step step : plan.steps()) {
                        context.checkpoint();
                        Path candidate = snapshot.candidate(instance, step.key());
                        String reason = "恢复会话 " + disk.id() + " 第 " + disk.rounds().get(target).number() + " 轮前快照";
                        if (step.phase() == RestorePlan.Phase.VERSION) {
                            validateVersionLibraries(instance, snapshot, plan);
                        }
                        context.phase(TaskSnapshot.Phase.RESTORING, reason + "（计划 " + plan.shortDigest()
                                + "）：" + step.key());
                        context.atomicFileAction(() -> {
                            switch (step.phase()) {
                                case VERSION -> transaction.replaceVersion(candidate, current.get(step.key()), 1,
                                        reason, List.of(reason));
                                case MARKER -> transaction.restoreMarker(candidate, current.get(step.key()), 1,
                                        reason);
                                case MOD -> transaction.restoreMod(step.key().substring(4), candidate,
                                        current.get(step.key()), 1, reason);
                            }
                        });
                    }
            }
            return finishRestore(instance, output, load(instance, disk.id()), context, observer);
            }
        };
    }

    /**
     * 校验目标版本配置引用的实例私有运行库。
     *
     * <p>目标配置声明 {@code maaCheckerRuntime} 时，库文件缺失会让启动器静默跳过 classpath 条目；
     * 因此必须在切换版本配置之前停止恢复。共享 libraries 不属于轮次快照范围，不做阻断。
     */
    private static void validateVersionLibraries(GameInstance instance, RoundSnapshot snapshot,
                                                 RestorePlan plan) throws IOException {
        if (plan.steps().stream().noneMatch(step -> step.phase() == RestorePlan.Phase.VERSION)) return;
        Path target = snapshot.candidate(instance, "version");
        if (target == null) throw new IOException("恢复计划缺少目标版本配置");
        InstanceLauncher.LibraryCheck check = InstanceLauncher.checkPrivateLibraries(instance, target, 5);
        if (check.managed() && !check.complete()) {
            throw new IOException("目标版本配置引用的 " + check.missing() + " 个实例运行库文件缺失（示例："
                    + String.join("、", check.examples())
                    + "）；未切换版本配置，请保留或重新准备该运行库后再恢复");
        }
    }

    private static TaskResult<State> finishRestore(GameInstance instance, Path output, State state, TaskContext context, Consumer<State> observer) throws IOException {
        Round target = state.rounds().get(state.restoreTarget());
        if (!RepairInputs.capture(instance).equals(target.before().inputs())) {
            throw new IOException("快照尚未完整恢复。请在轮次记录中重新预览同一轮并继续恢复；已有恢复凭据会保留");
        }
        context.log("恢复阶段：文件已恢复并通过目标指纹校验");
        for (int n = state.restoreTarget(); n < state.rounds().size(); n++) {
            if (state.rounds().get(n).phase() == Phase.RESTORED) continue;
            for (UUID tx : state.rounds().get(n).transactions()) {
                context.checkpoint();
                try (var transaction = TransactionSession.resume(output.resolve("state"), instance, tx)) {
                    context.atomicFileAction(() -> transaction.acknowledgeSnapshot(state.restoreReceipt(), target.before().inputs()));
                }
            }
        }
        context.log("恢复阶段：原动作墓碑已确认（事务动作标记为 REVERTED）");
        List<Round> rounds = new ArrayList<>(state.rounds());
        for (int n = state.restoreTarget(); n < rounds.size(); n++) {
            Round r = rounds.get(n);
            rounds.set(n, new Round(r.id(), r.number(), r.before(), Phase.RESTORED, "已恢复至第 " + target.number() + " 轮前，历史记录保留", r.events(), r.transactions(), r.report()));
        }
        State restored = new State(1, state.id(), state.instance(), state.createdAt(), state.policy(), state.restoreTarget(), Phase.RESTORED,
                "已恢复第 " + target.number() + " 轮前状态，尚未重新启动检验", rounds, null, null, null, state.restoreReceipts());
        persist(instance, restored, observer);
        context.log("恢复阶段：轮次终态已落盘");
        return TaskResult.success(restored, restored.message());
    }

    public static State load(GameInstance instance, UUID id) throws IOException {
        State state = RoundSnapshot.readJournal(instance, id + ".json", State.class);
        validate(instance, state);
        return state;
    }

    private static void persist(GameInstance instance, State state, Consumer<State> observer) throws IOException {
        RecoveryLineage.ensure(instance, state.instance());
        RoundSnapshot.writeJournal(instance, state.id() + ".json", state);
        if (observer != null) observer.accept(state);
    }

    /**
     * 以事务账本为准对账，并把补齐的事件持久化。
     *
     * <p>调用方必须持有实例工作流租约。缺少账本、未完成动作或损坏关联作为错误上报，
     * 不重置会话；补偿只追加事件，不执行任何文件动作。
     */
    private static State reconcileForUpdate(GameInstance instance, Path stateRoot, State state,
                                            Consumer<State> observer) throws IOException {
        Set<UUID> elsewhere = new HashSet<>();
        for (State other : history(instance)) {
            if (!other.id().equals(state.id())) elsewhere.addAll(WorkflowReconciler.associations(other));
        }
        WorkflowReconciler.Result result = WorkflowReconciler.reconcile(instance, stateRoot, state, elsewhere);
        if (!result.issues().isEmpty()) {
            throw new IOException("会话账本需要处理：" + String.join("；", result.issues()));
        }
        if (result.changed()) {
            persist(instance, result.state(), observer);
        }
        return result.state();
    }

    private static void recoverPending(GameInstance instance, Path output, TaskContext context) throws IOException {
        for (var history : TransactionSession.history(output.resolve("state"), instance)) {
            if (history.actions().stream().noneMatch(ActionRecord::unresolved)) continue;
            try (var session = TransactionSession.resume(output.resolve("state"), instance, history.header().sessionId())) {
                context.atomicFileAction(() -> {
                    if (session.recoverPending().stream().anyMatch(o -> o.state() == ActionRecord.State.CONFLICT)) throw new IOException("未提交动作存在冲突，请先从最近修复记录处理");
                });
            }
        }
    }

    private static void waitUntilQuiet(GameInstance instance, TaskContext context) throws InterruptedException, IOException {
        context.phase(TaskSnapshot.Phase.PRECHECK, "正在确认实例日志已停止写入，请先关闭该实例");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (CheckerEngine.recentlyActive(instance)) {
            context.checkpoint();
            if (System.nanoTime() > deadline) throw new IOException("该实例日志持续写入，请关闭游戏再继续");
            Thread.sleep(100);
        }
    }

    static String unusablePlanReason(RepairTasks.Plan plan) {
        String reason = plan.blocked().isEmpty()
                ? "诊断未生成可安全执行的文件动作，且没有具体阻断原因；请查看报告证据并人工确认，不能把无动作当作修复成功"
                : "无法安全执行：" + String.join("；", plan.blocked());
        return reason + "。可调整策略后从本轮继续，或还原轮前状态";
    }

    private static final class Paused extends Exception { Paused(String message) { super(message); } }

    private static final class Runner {
        private final GameInstance instance;
        private final Path output;
        private final CheckerEngine.Options options;
        private final RepairPolicy policy;
        private final RepairTasks.Candidates candidates;
        private final LoaderPreparation.Factory loader;
        private final Decider decider;
        private final Consumer<State> observer;
        private final TaskContext context;
        private State state;

        Runner(GameInstance i, Path out, CheckerEngine.Options o, RepairPolicy p, State previous, RepairTasks.Candidates c,
               LoaderPreparation.Factory l, Decider d, Consumer<State> observer, TaskContext context) {
            instance = i; output = out; options = o; policy = p; state = previous; candidates = c;
            loader = l == null ? LoaderPreparation.production(out, o) : l; decider = d; this.observer = observer; this.context = context;
        }

        TaskResult<State> run() throws Exception {
            InstanceStorage.requireIsolatedMods(instance);
            if (state == null) {
                List<State> existing = history(instance);
                if (!existing.isEmpty() && existing.getFirst().phase() != Phase.VERIFIED && existing.getFirst().phase() != Phase.RESTORED) state = existing.getFirst();
            }
            if (state != null) {
                State disk = load(instance, state.id());
                if (!disk.equals(state)) throw new IOException("修复会话已变化，请重新读取记录");
                state = disk;
                requireLatest(instance, state);
                if (state.phase() == Phase.VERIFIED) throw new IOException("此会话已经通过检验，请创建新会话");
                if (state.restoreReceipt() != null) throw new IOException("存在未完成的快照恢复；请在轮次记录重新预览同一轮并继续还原");
            }
            try {
                waitUntilQuiet(instance, context);
                recoverPending(instance, output, context);
                Path stateRoot = output.resolve("state");
                if (state == null) {
                    Set<UUID> known = new HashSet<>();
                    for (State other : history(instance)) known.addAll(WorkflowReconciler.associations(other));
                    List<String> orphan = WorkflowReconciler.unassociated(instance, stateRoot, known);
                    if (!orphan.isEmpty()) {
                        throw new IOException("存在未关联的事务账本，需处理：" + String.join("；", orphan));
                    }
                } else {
                    state = reconcileForUpdate(instance, stateRoot, state, observer);
                }
                GameInstance fresh = fresh();
                if (state == null) {
                    Round initial = round(1, RoundSnapshot.capture(fresh));
                    state = new State(1, UUID.randomUUID(), InstanceIdentity.of(instance), System.currentTimeMillis(), policy, 0,
                            Phase.CHECKING, "第 1 轮初态已保存", List.of(initial), null, null, null, List.of());
                } else {
                    state = new State(1, state.id(), state.instance(), state.createdAt(), policy, state.current(), state.phase(), state.message(), state.rounds(), null, null, null, state.restoreReceipts());
                    if (state.phase() == Phase.RESTORED) append(state.round().number(), RoundSnapshot.capture(fresh));
                    else event("从停止轮次继续", "重新诊断", "保留该轮原始快照；当前与轮前快照差异：" + RoundSnapshot.differences(RepairInputs.capture(fresh), state.round().before().inputs()).size() + " 项", null);
                }
                publish(Phase.CHECKING, "正在检查第 " + state.round().number() + " 轮");
                var report = CheckerTasks.diagnose(fresh, output, options).run(context).value();
                for (int attempt = 0; attempt < policy.maxRounds(); attempt++) {
                    context.checkpoint();
                    context.round(state.round().number());
                    if (report == null || report.runDirectory() == null) {
                        throw new IOException("诊断未返回可用报告路径；请查看实时日志后重新检查");
                    }
                    report(report.runDirectory());
                    if (report.exitCode() != 1) {
                        publish(report != null && report.exitCode() == 0 ? Phase.VERIFIED : Phase.PAUSED,
                                report != null && report.exitCode() == 0 ? "启动检验通过" : "启动前置条件未满足，已暂停");
                        return result();
                    }
                    publish(Phase.PLANNING, "正在规划第 " + state.round().number() + " 轮；下载前按分类授权");
                    var preparation = candidates == null
                            ? RepairTasks.prepareGoverned(report, output, options, loader,
                            governance(), policy.versionStrategy())
                            : RepairTasks.prepare(report, output, options, candidates, loader,
                            governance(), policy.versionStrategy());
                    var plan = preparation.run(context).value();
                    if (plan == null) throw new Paused("修复规划未返回方案；请查看实时日志和完整报告后重试");
                    if (!plan.executable()) throw new Paused(unusablePlanReason(plan));
                    plan = new RepairTasks.Plan(plan.instance(), plan.inputs(), plan.changes(), plan.blocked(), plan.notes(), state.round().number(), plan.disablePackMarker(), plan.loader());
                    publish(Phase.APPLYING, "正在提交已授权动作并启动验证");
                    var executed = RepairTasks.execute(plan, output, options, new RepairTasks.Observer() {
                        @Override public void session(UUID id) throws IOException { transaction(id); }
                        @Override public void committed(ActionRecord action) throws IOException { event(action.filename(), "已提交", action.reason(), action); }
                    }).run(context).value();
                    report = executed.verification();
                    report(report.runDirectory());
                    if (report.exitCode() == 0) {
                    publish(Phase.VERIFIED, "第 " + state.round().number() + " 轮修复后启动检验通过");
                        return result();
                    }
                    publish(Phase.NOT_PASSED, "本轮修复已提交，验证未通过");
                    append(state.round().number() + 1, RoundSnapshot.capture(fresh()));
                    if (attempt + 1 == policy.maxRounds()) throw new Paused("达到本次连续修复轮次上限；可调整策略后从当前轮继续");
                }
                return result();
            } catch (Paused e) {
                publish(Phase.PAUSED, e.getMessage());
                return result();
            } catch (Exception e) {
                boolean interrupted = Thread.interrupted();
                try { if (state != null) publish(Phase.PAUSED, e instanceof CancellationException || e instanceof InterruptedException ? "任务已中断，可从此轮继续或恢复轮前状态" : "任务暂停：" + e.getMessage()); }
                catch (IOException journal) { e.addSuppressed(journal); }
                finally { if (interrupted || e instanceof InterruptedException) Thread.currentThread().interrupt(); }
                throw e;
            }
        }

        private GameInstance fresh() throws IOException {
            GameInstance current = InstanceScanner.scanOne(instance.gameRoot(), instance.dir());
            if (current == null || !PathSafety.samePath(current.versionJson(), instance.versionJson())) {
                throw new IOException("无法识别原实例版本文件，请重新扫描");
            }
            return current;
        }

        private RepairTasks.Gate governance() {
            return new RepairTasks.Gate() {
                @Override public void authorize(PlannedAction action) throws Exception {
                    Runner.this.authorize(action);
                }

                @Override public void preflight(PlannedAction action) throws Exception {
                    Runner.this.preflight(action);
                }

                @Override public ModrinthClient.Candidate chooseVersion(
                        PlannedAction action, List<ModrinthClient.Candidate> candidates)
                        throws Exception {
                    return Runner.this.chooseVersion(action, candidates);
                }
            };
        }

        private RepairPolicy.Mode mode(PlannedAction action) {
            return policy.modes().get(policy.category(action));
        }

        private void preflight(PlannedAction action) throws Exception {
            RepairPolicy.Category category = policy.category(action);
            if (mode(action) == RepairPolicy.Mode.STOP) {
                throw new Paused(category.title() + " 已设为停止；尚未执行本批修复");
            }
        }

        private void authorize(PlannedAction action) throws Exception {
            RepairPolicy.Category category = policy.category(action);
            RepairPolicy.Mode mode = mode(action);
            event(category.title() + " · " + action.modId(), mode == RepairPolicy.Mode.AUTO ? "自动授权" : mode == RepairPolicy.Mode.STOP ? "策略停止" : "等待决定", action.reason(), null);
            if (mode == RepairPolicy.Mode.STOP) throw new Paused(category.title() + " 已设为停止；尚未执行本批修复");
            if (mode == RepairPolicy.Mode.AUTO) return;
            Decision decision = new Decision(UUID.randomUUID(), action, category);
            Round current = state.round();
            String waiting = "等待授权：" + action.reason();
            replace(new Round(current.id(), current.number(), current.before(), Phase.WAITING, waiting, current.events(), current.transactions(), current.report()),
                    Phase.WAITING, waiting, decision);
            context.phase(TaskSnapshot.Phase.PLANNING, state.message());
            if (decider == null) throw new Paused("当前运行入口没有交互决策器，已暂停");
            boolean approved = Boolean.TRUE.equals(decider.decide(decision).toCompletableFuture().get());
            context.checkpoint();
            event(category.title() + " · " + action.modId(), approved ? "已允许" : "已拒绝", action.reason(), null);
            if (!approved) throw new Paused("已拒绝 " + action.modId() + " 的操作；可手动处理后从本轮继续");
            publish(Phase.PLANNING, "授权已记录，正在准备候选");
        }

        private ModrinthClient.Candidate chooseVersion(
                PlannedAction action, List<ModrinthClient.Candidate> candidates) throws Exception {
            RepairPolicy.Category category = policy.category(action);
            Decision decision = new Decision(UUID.randomUUID(), action, category, candidates);
            Round current = state.round();
            String waiting = "等待选择版本：" + action.modId() + " · " + candidates.size()
                    + " 个兼容版本";
            replace(new Round(current.id(), current.number(), current.before(), Phase.WAITING,
                            waiting, current.events(), current.transactions(), current.report()),
                    Phase.WAITING, waiting, decision);
            context.phase(TaskSnapshot.Phase.PLANNING, state.message());
            if (decider == null) throw new Paused("当前运行入口没有交互式版本选择器，已暂停");
            ModrinthClient.Candidate selected = decider.chooseVersion(decision, candidates)
                    .toCompletableFuture().get();
            context.checkpoint();
            if (selected == null || !candidates.contains(selected)) {
                throw new Paused("已取消 " + action.modId() + " 的版本选择；实例未修改");
            }
            event(category.title() + " · " + action.modId(), "已选择版本",
                    selected.slug() + " " + selected.versionNumber()
                            + "（" + selected.fileName() + "）", null);
            publish(Phase.PLANNING, "版本已选择，正在下载与校验");
            return selected;
        }

        private TaskResult<State> result() {
            return new TaskResult<>(state.phase() == Phase.VERIFIED ? TaskSnapshot.State.SUCCEEDED : TaskSnapshot.State.NEEDS_ATTENTION, state, state.message(), null);
        }
        private Round round(int number, RoundSnapshot snapshot) { return new Round(UUID.randomUUID(), number, snapshot, Phase.CHECKING, "轮前状态已保存", List.of(), List.of(), null); }
        private void append(int number, RoundSnapshot snapshot) throws IOException {
            List<Round> rounds = new ArrayList<>(state.rounds());
            rounds.add(round(number, snapshot));
            state = new State(1, state.id(), state.instance(), state.createdAt(), policy, rounds.size() - 1, Phase.CHECKING, "轮前状态已保存", rounds, null, null, null, state.restoreReceipts());
            persist(instance, state, observer);
        }
        private void publish(Phase phase, String message) throws IOException {
            Round r = state.round();
            replace(new Round(r.id(), r.number(), r.before(), phase, message, r.events(), r.transactions(), r.report()), phase, message, null);
        }
        private void event(String title, String status, String detail, ActionRecord committed) throws IOException {
            Round r = state.round();
            List<Event> events = new ArrayList<>(r.events());
            events.add(new Event(title, status, detail, committed));
            replace(new Round(r.id(), r.number(), r.before(), r.phase(), r.message(), events, r.transactions(), r.report()), state.phase(), state.message(), state.decision());
        }
        private void transaction(UUID id) throws IOException {
            Round r = state.round();
            List<UUID> ids = new ArrayList<>(r.transactions()); ids.add(id);
            replace(new Round(r.id(), r.number(), r.before(), r.phase(), r.message(), r.events(), ids, r.report()), state.phase(), state.message(), null);
        }
        private void report(Path report) throws IOException {
            Round r = state.round();
            replace(new Round(r.id(), r.number(), r.before(), r.phase(), r.message(), r.events(), r.transactions(), report.toString()), state.phase(), state.message(), state.decision());
        }
        private void replace(Round round, Phase phase, String message, Decision decision) throws IOException {
            List<Round> rounds = new ArrayList<>(state.rounds()); rounds.set(state.current(), round);
            state = new State(1, state.id(), state.instance(), state.createdAt(), policy, state.current(), phase, message, rounds, decision, null, null, state.restoreReceipts());
            persist(instance, state, observer);
        }
    }
}
