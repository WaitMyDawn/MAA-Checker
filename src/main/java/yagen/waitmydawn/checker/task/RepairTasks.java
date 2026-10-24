package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.action.*;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.net.*;
import yagen.waitmydawn.checker.transaction.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 复用领域规划器的审核式修复；每批文件变更后重新检验，新增动作必须再次审核。 */
public final class RepairTasks {
    private RepairTasks() { }

    public record Change(PlannedAction action, String filename, String hash, Path candidate, String source) { }
    public record Download(Path file, String source) { }
    public record Plan(GameInstance instance, RepairInputs inputs, List<Change> changes,
                       List<String> blocked, List<String> notes, int round, boolean disablePackMarker, LoaderPreparation.Prepared loader) {
        public Plan { changes = List.copyOf(changes); blocked = List.copyOf(blocked); notes = List.copyOf(notes); }
        public boolean executable() { return (!changes.isEmpty() || loader != null) && blocked.isEmpty(); }
        public int fileCount() { return changes.size() + (disablePackMarker ? 1 : 0) + (loader == null ? 0 : 1 + (int) loader.additions()); }
    }
    public record Execution(UUID sessionId, int changed, CheckerEngine.CheckReport verification) { }
    public record Recovery(UUID sessionId, List<TransactionSession.Outcome> outcomes) {
        public Recovery { outcomes = List.copyOf(outcomes); }
        public boolean complete() { return outcomes.stream().allMatch(o -> o.state() == ActionRecord.State.REVERTED); }
    }

    /** 测试可提供本地候选；生产实现仍使用既有 Modrinth 候选与下载通道。 */
    @FunctionalInterface
    public interface Candidates {
        List<Download> resolve(PlannedAction action, GameInstance instance, TaskContext context) throws Exception;

        default List<ModrinthClient.Candidate> versions(PlannedAction action, GameInstance instance,
                                                        TaskContext context) throws Exception {
            throw new IOException("当前候选来源不支持版本列表");
        }

        default List<Download> resolveSelected(PlannedAction action, GameInstance instance,
                                               ModrinthClient.Candidate candidate,
                                               TaskContext context) throws Exception {
            throw new IOException("当前候选来源不支持指定版本下载");
        }
    }
    public interface Gate {
        void authorize(PlannedAction action) throws Exception;
        default void preflight(PlannedAction action) throws Exception { }
        default ModrinthClient.Candidate chooseVersion(PlannedAction action,
                                                       List<ModrinthClient.Candidate> candidates)
                throws Exception {
            throw new IOException("当前运行入口没有版本选择界面；请改用最低版本或最新兼容版本策略");
        }
    }
    public interface Observer {
        default void session(UUID id) throws IOException { }
        default void committed(ActionRecord action) throws IOException { }
    }

    public static TaskService.Work<Plan> prepare(CheckerEngine.CheckReport report, Path toolRoot,
                                                CheckerEngine.Options options) {
        return context -> prepare(report, toolRoot, options, downloads(toolRoot, options)).run(context);
    }

    public static TaskService.Work<Plan> prepareWithLoader(CheckerEngine.CheckReport report, Path toolRoot,
                                                          CheckerEngine.Options options, LoaderPreparation.Factory factory) {
        return prepare(report, toolRoot, options, downloads(toolRoot, options), factory);
    }

    public static TaskService.Work<Plan> prepare(CheckerEngine.CheckReport report, Path toolRoot,
                                                CheckerEngine.Options options, Candidates candidates) {
        return prepare(report, toolRoot, options, candidates, LoaderPreparation.production(toolRoot, options));
    }

    public static TaskService.Work<Plan> prepare(CheckerEngine.CheckReport report, Path toolRoot,
                                                CheckerEngine.Options options, Candidates candidates, LoaderPreparation.Factory loaderFactory) {
        return prepare(report, toolRoot, options, candidates, loaderFactory, action -> { });
    }

    public static TaskService.Work<Plan> prepareGoverned(CheckerEngine.CheckReport report, Path toolRoot, CheckerEngine.Options options,
                                                        LoaderPreparation.Factory factory, Gate gate) {
        return prepare(report, toolRoot, options, downloads(toolRoot, options), factory, gate, null);
    }

    public static TaskService.Work<Plan> prepareGoverned(CheckerEngine.CheckReport report, Path toolRoot,
                                                        CheckerEngine.Options options,
                                                        LoaderPreparation.Factory factory, Gate gate,
                                                        VersionUpdateStrategy versionStrategy) {
        return prepare(report, toolRoot, options, downloads(toolRoot, options), factory, gate, versionStrategy);
    }

    public static TaskService.Work<Plan> prepare(CheckerEngine.CheckReport report, Path toolRoot, CheckerEngine.Options options,
                                                Candidates candidates, LoaderPreparation.Factory loaderFactory, Gate gate) {
        return prepare(report, toolRoot, options, candidates, loaderFactory, gate, null);
    }

    public static TaskService.Work<Plan> prepare(CheckerEngine.CheckReport report, Path toolRoot,
                                                CheckerEngine.Options options, Candidates candidates,
                                                LoaderPreparation.Factory loaderFactory, Gate gate,
                                                VersionUpdateStrategy versionStrategy) {
        return context -> {
            GameInstance instance = report.rounds().isEmpty() ? null : report.rounds().getFirst().inst();
            if (instance == null) throw new IOException("诊断结果没有关联实例");
            try (var lease = WorkflowLease.acquire(instance)) {
                return prepareLocked(report, toolRoot, options, candidates, loaderFactory, gate,
                        versionStrategy).run(context);
            }
        };
    }

    private static TaskService.Work<Plan> prepareLocked(CheckerEngine.CheckReport report, Path toolRoot,
                                                        CheckerEngine.Options options, Candidates candidates,
                                                        LoaderPreparation.Factory loaderFactory, Gate gate,
                                                        VersionUpdateStrategy versionStrategy) {
        return context -> {
            context.phase(TaskSnapshot.Phase.PLANNING, "正在根据诊断准备可审核的修复方案");
            if (report.exitCode() != 1 || report.rounds().size() != 1 || report.inputs() == null) {
                throw new IOException("需要一次完整、未通过且带输入快照的启动诊断");
            }
            GameInstance instance = report.rounds().getFirst().inst();
            InstanceStorage.requireIsolatedMods(instance);
            assertInputs(instance, report.inputs());
            Path state = toolRoot.resolve("state");
            requireCompatibleHistory(state, instance);
            var history = TransactionSession.history(state, instance);
            if (history.stream().flatMap(h -> h.actions().stream()).anyMatch(ActionRecord::unresolved)) {
                throw new IOException("存在中断或冲突记录，请先查看最近修复记录并恢复");
            }
            int roundNumber = history.stream().flatMap(h -> h.actions().stream()).mapToInt(ActionRecord::round).max().orElse(0) + 1;
            var round = report.rounds().getFirst();
            ActionPlan original = ActionPlanner.plan(instance.loader(), round.diag(), round.stat(), round.jarIndex(),
                    Tombstones.forPlanning(TransactionSession.blockedMods(state, instance)), options.cascadeLimit(),
                    versionStrategy);
            List<Change> changes = new ArrayList<>();
            List<String> blocked = new ArrayList<>(original.blocked());
            List<String> notes = new ArrayList<>(original.notes());
            Set<String> removed = new HashSet<>();
            LoaderPreparation.Prepared loader = null;
            for (PlannedAction action : original.actions()) {
                context.checkpoint();
                if (action.kind() == ActionKind.UPDATE_MOD) {
                    prepareUpdate(action, instance, candidates, gate, context, round, options,
                            changes, blocked, notes, removed);
                    continue;
                }
                gate.authorize(action);
                if (action.kind() == ActionKind.UPGRADE_LOADER) {
                    loader = loaderFactory.prepare(instance, action, context);
                    String chosenVersion = loader.version();
                    if (VersionRanges.compare(loader.version(), action.target()) < 0 || round.diag().issues().stream()
                            .filter(i -> i.kind() == Diagnosis.Kind.LOADER_TOO_OLD)
                            .anyMatch(i -> !VersionRanges.satisfies(chosenVersion, i.expectedRange()))) {
                        throw new IOException("准备的加载器不满足全部诊断版本约束");
                    }
                } else if (action.kind() == ActionKind.REMOVE_MOD) {
                    prepareRemoval(instance, action, changes, blocked, removed);
                } else {
                    context.phase(TaskSnapshot.Phase.DOWNLOADING, "正在下载并校验前置 " + action.modId() + "，尚未修改实例");
                    List<Download> files = candidates.resolve(action, instance, context);
                    if (files.isEmpty()) {
                        ActionPlanner.Fallback fallback = ActionPlanner.fallbackRemoval(round.stat(), round.jarIndex(), action.target(),
                                "前置 " + action.modId() + " 没有匹配版本，需要停用依赖方", action.errorText(), options.cascadeLimit());
                        notes.add("未找到 " + action.modId() + " 的匹配版本；替代停用动作必须确认后才能执行。");
                        if (fallback.blocked() != null) blocked.add(fallback.blocked());
                        for (var alternative : fallback.actions()) {
                            gate.authorize(alternative);
                            prepareRemoval(instance, alternative, changes, blocked, removed);
                        }
                        if (fallback.actions().isEmpty()) blocked.add("无法安全定位依赖方：" + action.target());
                    } else {
                        Download chosen = null;
                        for (Download download : files) {
                            Path candidate = download.file();
                            context.checkpoint();
                            RepairInputs.hash(candidate);
                            if (ModJarIndex.modIdsOf(candidate).stream().anyMatch(id -> id.equalsIgnoreCase(action.modId()))) {
                                chosen = download;
                                break;
                            }
                            context.log("候选模组身份不符，未安装：" + candidate.getFileName());
                        }
                        if (chosen == null) {
                            blocked.add("所有候选均未声明 modId=" + action.modId() + "，不会安装或改为停用依赖方。");
                        } else {
                            String filename = chosen.file().getFileName().toString();
                            safeJarName(filename);
                            if (Files.exists(instance.modsDir().resolve(filename), LinkOption.NOFOLLOW_LINKS)) {
                                blocked.add("已有同名文件 " + filename + "，不会覆盖，请人工核对。");
                            } else {
                                changes.add(new Change(action, filename, RepairInputs.hash(chosen.file()), chosen.file().toAbsolutePath().normalize(),
                                        chosen.source()));
                            }
                        }
                    }
                }
            }
            if (changes.stream().map(c -> c.filename().toLowerCase(Locale.ROOT)).distinct().count() != changes.size()) {
                blocked.add("多个动作指向同一文件，请人工核对后重新诊断。");
            }
            assertInputs(instance, report.inputs());
            context.checkpoint();
            boolean marker = report.inputs().hashes().containsKey("marker")
                    && changes.stream().anyMatch(c -> c.action().kind() == ActionKind.REMOVE_MOD);
            return TaskResult.success(new Plan(instance, report.inputs(), changes, blocked, notes, roundNumber, marker, loader),
                    blocked.isEmpty() ? "修复方案已准备，等待确认" : "方案存在限制，实例未修改");
        };
    }

    private static void prepareRemoval(GameInstance instance, PlannedAction action, List<Change> changes,
                                       List<String> blocked, Set<String> seen) throws IOException {
        safeJarName(action.jarFile());
        if (!seen.add(action.jarFile().toLowerCase(Locale.ROOT))) return;
        Path file = instance.modsDir().resolve(action.jarFile());
        String hash = RepairInputs.hash(file);
        Set<String> ids = ModJarIndex.modIdsOf(file);
        if (ids.size() != 1 || !ids.iterator().next().equalsIgnoreCase(action.modId())) {
            blocked.add("文件 " + action.jarFile() + " 的模组身份不唯一或与诊断不符，未加入停用计划。");
            return;
        }
        changes.add(new Change(action, action.jarFile(), hash, null, "原文件保留在事务恢复目录，可按会话恢复"));
    }

    private static void prepareUpdate(PlannedAction action, GameInstance instance,
                                      Candidates candidates, Gate gate, TaskContext context,
                                      CheckerEngine.Round round, CheckerEngine.Options options,
                                      List<Change> changes, List<String> blocked,
                                      List<String> notes, Set<String> removed) throws Exception {
        gate.preflight(action);
        context.phase(TaskSnapshot.Phase.PLANNING,
                "正在检索 " + action.modId() + " 同时满足全部版本区间的版本");
        String title = action.jarFile() == null ? null
                : JarModReader.displayNameOf(instance.modsDir().resolve(action.jarFile()), action.modId());
        List<ModrinthClient.Candidate> versions = candidates.versions(action, instance, context);
        if (versions.isEmpty()) {
            blocked.add("没有找到 " + action.modId() + " 同时满足 " + action.versionRanges()
                    + " 的版本；不会用不满足约束的最新版碰运气");
            return;
        }

        VersionUpdateStrategy strategy = action.versionStrategy() == null
                ? VersionUpdateStrategy.MINIMUM : action.versionStrategy();
        ModrinthClient.Candidate selected;
        if (strategy == VersionUpdateStrategy.CHOOSE) {
            context.log("找到 " + versions.size() + " 个兼容版本，等待用户选择：" + action.modId());
            selected = gate.chooseVersion(action, versions);
        } else {
            selected = selectVersion(versions, strategy);
        }
        if (selected == null) {
            blocked.add("未选择 " + action.modId() + " 的更新版本，实例未修改");
            return;
        }

        String label = selected.slug() + " " + selected.versionNumber();
        PlannedAction resolved = new PlannedAction(ActionKind.UPDATE_MOD, action.modId(),
                action.jarFile(), action.category(),
                action.reason() + "；已选择 " + label, action.errorText(), null, null,
                action.versionRange(), action.versionRanges(), strategy);
        if (strategy != VersionUpdateStrategy.CHOOSE) gate.authorize(resolved);

        context.phase(TaskSnapshot.Phase.DOWNLOADING,
                "正在下载并校验 " + action.modId() + " " + selected.versionNumber());
        List<Download> files = candidates.resolveSelected(action, instance, selected, context);
        if (files.isEmpty()) {
            blocked.add("所选版本 " + label + " 下载后无法确认模组身份，实例未修改");
            return;
        }

        Download chosen = null;
        String declaredVersion = null;
        for (Download download : files) {
            context.checkpoint();
            String candidateVersion = JarModReader.read(download.file()).stream()
                    .filter(mod -> mod.modId().equalsIgnoreCase(action.modId()))
                    .map(JarModReader.Mod::version).findFirst().orElse(null);
            if (candidateVersion == null) continue;
            if (action.versionRanges().stream().allMatch(range ->
                    VersionRanges.satisfies(candidateVersion, range))) {
                chosen = download;
                declaredVersion = candidateVersion;
                break;
            }
            context.log("候选 " + download.file().getFileName() + " 声明版本 " + candidateVersion
                    + " 未满足全部区间，未采用");
        }
        if (chosen == null) {
            blocked.add("所选 " + label + " 下载后仍未同时满足全部版本区间，实例未修改");
            return;
        }

        String filename = chosen.file().getFileName().toString();
        safeJarName(filename);
        if (filename.equalsIgnoreCase(action.jarFile())) {
            blocked.add("新版本文件名与当前文件相同（" + filename + "），无法安全替换，请人工处理");
            return;
        }
        if (Files.exists(instance.modsDir().resolve(filename), LinkOption.NOFOLLOW_LINKS)) {
            blocked.add("已有同名文件 " + filename + "，不会覆盖，请人工核对");
            return;
        }

        PlannedAction install = new PlannedAction(ActionKind.INSTALL_DEP, action.modId(), filename,
                action.category(),
                "版本区间不满足，按策略「" + strategy.title() + "」更新 " + action.modId()
                        + " 到 " + declaredVersion + "（来源 " + label + "）",
                action.errorText(), null, null, null, action.versionRanges(), strategy);
        changes.add(new Change(install, filename, RepairInputs.hash(chosen.file()),
                chosen.file().toAbsolutePath().normalize(), chosen.source()));

        PlannedAction remove = new PlannedAction(ActionKind.REMOVE_MOD, action.modId(),
                action.jarFile(), action.category(),
                "更新 " + action.modId() + " 到 " + declaredVersion + " 后移走旧版本 "
                        + action.jarFile(),
                action.errorText(), null, null, null, action.versionRanges(), strategy);
        prepareRemoval(instance, remove, changes, blocked, removed);
        notes.add("更新 " + action.modId() + " "
                + (title == null || title.isBlank() ? "" : title + " ") + "：" + label
                + "（jar 声明 " + declaredVersion + "，全部版本区间已复核）");
    }

    static ModrinthClient.Candidate selectVersion(List<ModrinthClient.Candidate> versions,
                                                  VersionUpdateStrategy strategy) {
        if (versions == null || versions.isEmpty() || strategy == VersionUpdateStrategy.CHOOSE) {
            return null;
        }
        if (strategy == VersionUpdateStrategy.LATEST) {
            return versions.stream()
                    .max(Comparator.comparing(ModrinthClient.Candidate::datePublished))
                    .orElse(null);
        }
        return versions.stream()
                .min(Comparator.comparing(ModrinthClient.Candidate::versionNumber,
                        VersionRanges::compare))
                .orElse(null);
    }

    public static TaskService.Work<Execution> execute(Plan plan, Path toolRoot, CheckerEngine.Options options) {
        return execute(plan, toolRoot, options, new Observer() { });
    }

    public static TaskService.Work<Execution> execute(Plan plan, Path toolRoot, CheckerEngine.Options options, Observer observer) {
        return context -> {
            try (var lease = WorkflowLease.acquire(plan.instance())) {
                return executeLocked(plan, toolRoot, options, observer).run(context);
            }
        };
    }

    private static TaskService.Work<Execution> executeLocked(Plan plan, Path toolRoot, CheckerEngine.Options options,
                                                             Observer observer) {
        return context -> {
            if (!plan.executable()) throw new IOException("方案为空或存在阻断项，不能执行");
            if (options.dryRun()) throw new IOException("预演参数不能用于执行文件动作");
            boolean markerRequired = plan.inputs().hashes().containsKey("marker")
                    && plan.changes().stream().anyMatch(c -> c.action().kind() == ActionKind.REMOVE_MOD);
            if (markerRequired != plan.disablePackMarker()) throw new IOException("整合包标记动作与已审核方案不一致");
            context.phase(TaskSnapshot.Phase.PRECHECK, "正在核对已确认方案与实例内容");
            assertInputs(plan.instance(), plan.inputs());
            requireCompatibleHistory(toolRoot.resolve("state"), plan.instance());
            if (plan.loader() != null) validateLoader(plan.instance(), plan.loader());
            for (Change change : plan.changes()) {
                context.checkpoint();
                safeJarName(change.filename());
                if (change.action().errorText().isEmpty()) throw new IOException("动作缺少诊断证据");
                Path source = change.candidate() == null ? plan.instance().modsDir().resolve(change.filename()) : change.candidate();
                if (!RepairInputs.hash(source).equals(change.hash())) throw new IOException("方案文件已变化，请重新准备");
                if (!ModJarIndex.modIdsOf(source).stream().anyMatch(id -> id.equalsIgnoreCase(change.action().modId()))) {
                    throw new IOException("文件身份在执行前发生变化");
                }
            }
            UUID sessionId;
            int changed = 0;
            try (TransactionSession session = TransactionSession.create(toolRoot.resolve("state"), plan.instance())) {
                sessionId = session.header().sessionId();
                observer.session(sessionId);
                context.log("修复会话：" + sessionId + "；已提交动作在取消后保留，可从最近修复记录恢复。");
                assertInputs(plan.instance(), plan.inputs());
                if (plan.loader() != null) {
                    validateLoader(plan.instance(), plan.loader());
                    for (var library : plan.loader().libraries()) {
                        context.checkpoint();
                        Path target = LibraryPaths.target(plan.instance(), library.relative());
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            if (!RepairInputs.hash(target).equals(library.hash())) throw new IOException("共享运行库在执行前发生冲突：" + library.relative());
                            continue;
                        }
                        context.phase(TaskSnapshot.Phase.APPLYING, "正在增量发布 NeoForge 共享运行库 " + library.relative());
                        context.atomicFileAction(() -> observer.committed(session.cacheLibrary(library.relative(), library.candidate(), library.hash(), plan.round(),
                                "NeoForge " + plan.loader().version() + " 的版本专属共享运行库", List.of("安装器 SHA-256：" + plan.loader().installerHash(), "库 SHA-256：" + library.hash()))));
                        changed++;
                    }
                    context.checkpoint();
                    context.phase(TaskSnapshot.Phase.APPLYING, "正在切换实例的 NeoForge 配置");
                    context.atomicFileAction(() -> observer.committed(session.replaceVersion(plan.loader().versionJson(), plan.inputs().hashes().get("version"), plan.round(),
                            "根据启动故障升级 NeoForge 至 " + plan.loader().version(), List.of("新配置 SHA-256：" + plan.loader().versionHash(),
                                    "安装器 SHA-256：" + plan.loader().installerHash(), "安装器日志：" + plan.loader().installerLog()))));
                    changed++;
                }
                if (plan.disablePackMarker()) {
                    context.phase(TaskSnapshot.Phase.APPLYING, "正在停用整合包自动补全标记，原文件将保留用于恢复");
                    context.atomicFileAction(() -> observer.committed(session.disablePackMarker(plan.inputs().hashes().get("marker"), plan.round(),
                            "防止 HMCL 自动补回本批停用的模组；整合包更新入口会暂时隐藏", List.of("已审核的停用方案包含："
                                    + String.join(", ", plan.changes().stream().filter(c -> c.action().kind() == ActionKind.REMOVE_MOD).map(Change::filename).toList())))));
                    changed++;
                }
                for (Change change : plan.changes()) {
                    context.checkpoint();
                    context.phase(TaskSnapshot.Phase.APPLYING, "正在执行 " + (changed + 1) + "/" + plan.fileCount() + "：" + change.filename());
                    // 单个文件动作跨越持久化意图与提交；取消仅在动作之间响应，防止中断 journal 写入。
                    List<String> evidence = new ArrayList<>(change.action().errorText());
                    evidence.add("文件来源或恢复方式：" + change.source());
                    evidence.add("SHA-256：" + change.hash());
                    context.atomicFileAction(() -> {
                        if (change.action().kind() == ActionKind.REMOVE_MOD) {
                            observer.committed(session.removeMod(change.action().modId(), change.filename(), change.hash(), plan.round(),
                                    change.action().reason(), evidence));
                        } else if (change.action().kind() == ActionKind.INSTALL_DEP) {
                            observer.committed(session.installMod(change.action().modId(), change.filename(), change.candidate(), change.hash(), plan.round(),
                                    change.action().reason(), evidence));
                        } else throw new IOException("未支持的修复动作");
                    });
                    changed++;
                    context.log("已提交 " + change.filename() + "；依据：" + change.action().reason());
                }
                context.checkpoint();
                GameInstance refreshed = InstanceScanner.scanOne(plan.instance().gameRoot(), plan.instance().dir());
                if (refreshed == null) throw new IOException("修复后无法重新识别实例，请查看会话记录");
                var verification = CheckerEngine.checkDetailed(refreshed, toolRoot, verificationOptions(options), context::log, context);
                TaskSnapshot.State state = verification.exitCode() == 0 ? TaskSnapshot.State.SUCCEEDED : TaskSnapshot.State.NEEDS_ATTENTION;
                return new TaskResult<>(state, new Execution(sessionId, changed, verification),
                        verification.exitCode() == 0 ? "本批修复后启动验证通过" : "本批文件动作已提交，但启动尚未通过；请查看新诊断或恢复", null);
            }
        };
    }

    public static TaskService.Work<List<TransactionSession.History>> history(GameInstance instance, Path toolRoot) {
        return context -> {
            context.phase(TaskSnapshot.Phase.PLANNING, "正在读取修复会话");
            return TaskResult.success(TransactionSession.history(toolRoot.resolve("state"), instance), "修复会话已读取");
        };
    }

    public static TaskService.Work<Recovery> restore(GameInstance instance, Path toolRoot, UUID sessionId) {
        return context -> {
            try (var lease = WorkflowLease.acquire(instance)) {
                return restoreLocked(instance, toolRoot, sessionId).run(context);
            }
        };
    }

    private static TaskService.Work<Recovery> restoreLocked(GameInstance instance, Path toolRoot, UUID sessionId) {
        return context -> {
            context.phase(TaskSnapshot.Phase.RESTORING, "正在按逆序恢复修复会话");
            if (RepairWorkflow.history(instance).stream().anyMatch(s -> s.restoreReceipts().contains(sessionId))) {
                throw new IOException("这是轮次快照的恢复凭据；请在轮次记录中预览并继续还原，不能独立撤销以免破坏轮次状态");
            }
            if (CheckerEngine.recentlyActive(instance)) throw new IOException("实例日志刚被写入，请关闭游戏并等待 10 秒后再恢复");
            context.checkpoint();
            try (var session = TransactionSession.resume(toolRoot.resolve("state"), instance, sessionId)) {
                List<TransactionSession.Outcome> outcomes = new ArrayList<>();
                while (true) {
                    context.checkpoint();
                    TransactionSession.Outcome[] next = new TransactionSession.Outcome[1];
                    context.atomicFileAction(() -> next[0] = session.restoreNext());
                    if (next[0] == null) break;
                    outcomes.add(next[0]);
                    if (next[0].state() == ActionRecord.State.CONFLICT) break;
                }
                Recovery result = new Recovery(sessionId, outcomes);
                return new TaskResult<>(result.complete() ? TaskSnapshot.State.SUCCEEDED : TaskSnapshot.State.NEEDS_ATTENTION,
                        result, result.complete() ? "会话已恢复，尚未重新启动检验" : "恢复遇到文件冲突，未覆盖用户修改", null);
            }
        };
    }

    private static Candidates downloads(Path toolRoot, CheckerEngine.Options options) {
        return new Candidates() {
            @Override
            public List<Download> resolve(PlannedAction action, GameInstance instance,
                                          TaskContext context) throws Exception {
                var manager = new NetworkManager(options.proxy(), null, true,
                        options.allowMirrorFile(), context::log);
                context.checkpoint();
                var resolution = ModrinthClient.resolve(action.modId(), instance.mcVersion(),
                        instance.loader(), action.versionRange(), manager, context::log);
                if (!resolution.found()) return List.of();
                Path cache = candidateCache(instance);
                for (var candidate : resolution.candidates()) {
                    context.checkpoint();
                    Download download = downloadCandidate(
                            action.modId(), candidate, manager, cache, context);
                    if (download != null) return List.of(download);
                }
                return List.of();
            }

            @Override
            public List<ModrinthClient.Candidate> versions(PlannedAction action, GameInstance instance,
                                                           TaskContext context) throws Exception {
                var manager = new NetworkManager(options.proxy(), null, true,
                        options.allowMirrorFile(), context::log);
                Path current = action.jarFile() == null ? null
                        : instance.modsDir().resolve(action.jarFile());
                String title = current == null ? null
                        : JarModReader.displayNameOf(current, action.modId());
                var resolution = ModrinthClient.resolveVersions(action.modId(), title,
                        instance.mcVersion(), instance.loader(), action.versionRanges(),
                        manager, context::log);
                resolution.rejected().forEach(reason -> context.log("   版本淘汰依据: " + reason));
                return resolution.candidates();
            }

            @Override
            public List<Download> resolveSelected(PlannedAction action, GameInstance instance,
                                                  ModrinthClient.Candidate candidate,
                                                  TaskContext context) throws Exception {
                var manager = new NetworkManager(options.proxy(), null, true,
                        options.allowMirrorFile(), context::log);
                Download download = downloadCandidate(action.modId(), candidate, manager,
                        candidateCache(instance), context);
                return download == null ? List.of() : List.of(download);
            }
        };
    }

    private static Path candidateCache(GameInstance instance) throws IOException {
        Path preparation = InstanceStorage.directory(
                InstanceStorage.root(instance, true), "preparation", true);
        return Files.createTempDirectory(preparation, "candidate-");
    }

    private static Download downloadCandidate(String expectedModId,
                                              ModrinthClient.Candidate candidate,
                                              NetworkManager manager, Path cache,
                                              TaskContext context) throws Exception {
        context.checkpoint();
        safeJarName(candidate.fileName());
        Path target = Files.createDirectories(cache.resolve(UUID.randomUUID().toString()))
                .resolve(candidate.fileName());
        var outcome = manager.download(candidate.url(), candidate.mirrorUrl(),
                candidate.size(), target);
        context.checkpoint();
        if (!outcome.ok()) {
            throw new IOException("模组下载失败：" + outcome.detail() + "；不会因此停用模组");
        }
        if (candidate.size() > 0 && Files.size(target) != candidate.size()) {
            throw new IOException("下载大小与候选元数据不一致");
        }
        if (ModJarIndex.modIdsOf(target).stream()
                .noneMatch(id -> id.equalsIgnoreCase(expectedModId))) {
            context.log("候选 " + candidate.fileName() + " 的 jar 内 modId 与 " + expectedModId
                    + " 不符，未采用");
            return null;
        }
        return new Download(target, "Modrinth " + candidate.slug() + " / "
                + candidate.versionNumber() + " / " + candidate.versionId() + " / "
                + candidate.url());
    }

    private static void assertInputs(GameInstance instance, RepairInputs expected) throws IOException {
        if (!RepairInputs.capture(instance).equals(expected)) throw new IOException("实例内容在诊断或方案准备后发生变化，请重新检查");
    }

    private static void validateLoader(GameInstance instance, LoaderPreparation.Prepared loader) throws IOException {
        if (!RepairInputs.hash(loader.versionJson()).equals(loader.versionHash())) throw new IOException("准备的加载器配置已变化，请重新准备");
        Set<String> seen = new HashSet<>();
        for (var lib : loader.libraries()) {
            if (!seen.add(lib.relative().toLowerCase(Locale.ROOT))) throw new IOException("实例运行库路径存在大小写冲突");
            if (!RepairInputs.hash(lib.candidate()).equals(lib.hash())) throw new IOException("准备的实例运行库内容已变化：" + lib.relative());
            Path target = LibraryPaths.target(instance, lib.relative());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !RepairInputs.hash(target).equals(lib.hash())) {
                throw new IOException("共享运行库目标已变化，不覆盖：" + lib.relative());
            }
            if (lib.alreadyPresent() && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("准备时复用的共享运行库已消失，请重新准备");
        }
    }

    private static void safeJarName(String name) throws IOException {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar") || name.startsWith(".")
                || name.contains("/") || name.contains("\\") || name.contains(":") || name.endsWith(" ")) {
            throw new IOException("候选必须是单个普通 JAR 文件名");
        }
    }

    private static void requireCompatibleHistory(Path state, GameInstance instance) throws IOException {
        Path legacy = state.resolve(RunJournal.safeName(instance.name())).resolve("tombstones.json");
        if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            RepairInputs.hash(legacy);
            var node = new ObjectMapper().readTree(legacy.toFile());
            if (!node.path("tombstones").isArray() || !node.path("tombstones").isEmpty()) {
                throw new IOException("存在旧版停用记录或记录格式异常，请先用原入口核对和恢复，不能与新事务混用");
            }
        }
    }

    private static CheckerEngine.Options verificationOptions(CheckerEngine.Options o) {
        return new CheckerEngine.Options(o.mx(), o.timeoutSeconds(), o.graceSeconds(), o.rounds(), o.cascadeLimit(), false,
                true, 1, 0, "abort", o.proxy(), null, o.allowDirectCdn(), o.allowMirrorFile(), null, o.notifier());
    }
}
