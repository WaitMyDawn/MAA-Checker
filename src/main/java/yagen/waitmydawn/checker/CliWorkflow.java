package yagen.waitmydawn.checker;

import yagen.waitmydawn.checker.action.LoaderPreparation;
import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.task.GlobalRepairPolicy;
import yagen.waitmydawn.checker.task.RepairPolicy;
import yagen.waitmydawn.checker.task.RepairWorkflow;
import yagen.waitmydawn.checker.task.TaskResult;
import yagen.waitmydawn.checker.task.TaskService;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * CLI 与新版事务工作流之间的适配层。
 *
 * <p>策略来源、非交互 ASK 拒绝、任务退出码和轮次还原顺序集中在这里，避免命令行入口
 * 再次复制一套与桌面端不同的文件修改语义。
 */
final class CliWorkflow {
    enum PolicyChoice { SAVED, AUTO, ASK, STOP }

    record ResolvedPolicy(RepairPolicy policy, boolean saved, String description) {
    }

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FINDINGS = 1;
    static final int EXIT_NEEDS_ATTENTION = 2;
    static final int EXIT_CANCELLED = 130;

    private CliWorkflow() {
    }

    static PolicyChoice policyChoice(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "saved" -> PolicyChoice.SAVED;
            case "auto" -> PolicyChoice.AUTO;
            case "ask" -> PolicyChoice.ASK;
            case "stop" -> PolicyChoice.STOP;
            default -> throw new IllegalArgumentException(
                    "--policy 只允许 saved、auto、ask 或 stop：" + value);
        };
    }

    /**
     * 解析显式或保存策略。交互终端允许 ASK；非交互进程遇到 ASK 必须在开始修复前失败，
     * 不能先写工作流或文件动作再停在问题中间。
     */
    static ResolvedPolicy resolvePolicy(GameInstance instance, Path toolRoot, PolicyChoice choice,
                                        int rounds, boolean roundsOverride, boolean interactive)
            throws IOException {
        RepairPolicy policy;
        boolean saved = false;
        String description;
        int defaultRounds = GlobalRepairPolicy.defaults().maxRounds();
        switch (choice) {
            case SAVED -> {
                GlobalRepairPolicy.Snapshot snapshot = GlobalRepairPolicy.load(toolRoot);
                policy = snapshot.policy();
                saved = snapshot.saved();
                description = snapshot.saved() ? "已保存的全局策略"
                        : "全局默认策略（全部自动 / 10 轮 / 最低兼容版）";
            }
            case AUTO -> {
                policy = new RepairPolicy(allModes(RepairPolicy.Mode.AUTO), defaultRounds);
                description = "命令行显式 AUTO";
            }
            case ASK -> {
                policy = new RepairPolicy(defaultAskModes(), defaultRounds);
                description = "命令行显式 ASK";
            }
            case STOP -> {
                policy = new RepairPolicy(allModes(RepairPolicy.Mode.STOP), defaultRounds);
                description = "命令行显式 STOP";
            }
            default -> throw new IllegalStateException(choice.name());
        }
        if (roundsOverride) {
            policy = new RepairPolicy(policy.modes(), rounds, policy.versionStrategy());
        }
        if (!interactive && policy.modes().values().stream()
                .anyMatch(mode -> mode == RepairPolicy.Mode.ASK)) {
            throw new IOException("非交互 CLI 不能处理 ASK 授权；请使用 --policy auto、"
                    + "--policy stop，或先在桌面端保存不含 ASK 的策略");
        }
        return new ResolvedPolicy(policy, saved, description);
    }

    static TaskResult<RepairWorkflow.State> fix(GameInstance instance, Path toolRoot,
                                                 CheckerEngine.Options options,
                                                 RepairPolicy policy, RepairWorkflow.Decider decider,
                                                 Consumer<String> sink) throws Exception {
        try (TaskService tasks = new TaskService()) {
            Thread shutdown = new Thread(tasks::close, "maa-cli-fix-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                var handle = tasks.submit(instance.name(), RepairWorkflow.run(instance, toolRoot,
                        options, policy, null, null,
                        LoaderPreparation.production(toolRoot, options), decider,
                        state -> sink.accept("[" + state.phase() + "] " + state.message())), event -> {
                    if (event.kind() == yagen.waitmydawn.checker.task.TaskEvent.Kind.LOG) {
                        sink.accept(event.text());
                    }
                });
                return handle.completion().toCompletableFuture().get();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    static TaskResult<RepairWorkflow.RestorePreview> previewRestore(GameInstance instance,
                                                                    Path toolRoot, UUID workflow,
                                                                    int roundNumber) throws Exception {
        RepairWorkflow.State state = selectHistory(instance, workflow);
        RepairWorkflow.Round round = state.rounds().stream()
                .filter(value -> value.number() == roundNumber)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IOException("工作流 " + state.id()
                        + " 中不存在第 " + roundNumber + " 轮"));
        try (TaskService tasks = new TaskService()) {
            return tasks.submit("preview restore", RepairWorkflow.preview(instance,
                            toolRoot.resolve("state"), state, round.id()), null)
                    .completion().toCompletableFuture().get();
        }
    }

    static TaskResult<RepairWorkflow.State> restore(GameInstance instance, Path toolRoot,
                                                     RepairWorkflow.RestorePreview preview,
                                                     Consumer<String> sink) throws Exception {
        try (TaskService tasks = new TaskService()) {
            Thread shutdown = new Thread(tasks::close, "maa-cli-restore-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                return tasks.submit("restore workflow", RepairWorkflow.restore(instance, toolRoot,
                                preview, state -> sink.accept("[" + state.phase() + "] "
                                        + state.message())), event -> {
                            if (event.kind() == yagen.waitmydawn.checker.task.TaskEvent.Kind.LOG) {
                                sink.accept(event.text());
                            }
                        })
                        .completion().toCompletableFuture().get();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    static RepairWorkflow.State selectHistory(GameInstance instance, UUID workflow) throws IOException {
        List<RepairWorkflow.State> history = RepairWorkflow.history(instance);
        if (history.isEmpty()) throw new IOException("该实例没有新版事务工作流记录");
        if (workflow == null) return history.getFirst();
        return history.stream().filter(state -> state.id().equals(workflow)).findFirst()
                .orElseThrow(() -> new IOException("找不到工作流：" + workflow));
    }

    static RepairWorkflow.Decider decider(boolean dialog, Consumer<String> sink) {
        return decision -> {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            String prompt = decision.category().title() + "\n\n" + decision.action().reason()
                    + "\n\n允许执行这次动作吗？";
            if (dialog) {
                if (GraphicsEnvironment.isHeadless()) {
                    answer.completeExceptionally(new IOException("当前环境没有图形界面，无法使用 --dialog 处理 ASK"));
                } else {
                    SwingUtilities.invokeLater(() -> answer.complete(
                            JOptionPane.showConfirmDialog(null, prompt, "MAA-Checker 授权",
                                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                                    == JOptionPane.YES_OPTION));
                }
                return answer;
            }
            Console console = System.console();
            if (console == null) {
                answer.completeExceptionally(new IOException(
                        "非交互 CLI 无法处理 ASK 授权；请使用 --policy auto/stop"));
                return answer;
            }
            sink.accept(prompt);
            String line = console.readLine("输入 y 允许，其他输入拒绝：");
            answer.complete(line != null && line.trim().equalsIgnoreCase("y"));
            return answer;
        };
    }

    static int exitCode(TaskResult<?> result) {
        return switch (result.state()) {
            case SUCCEEDED -> EXIT_SUCCESS;
            case CANCELLED -> EXIT_CANCELLED;
            case FAILED -> EXIT_NEEDS_ATTENTION;
            case NEEDS_ATTENTION -> EXIT_NEEDS_ATTENTION;
            case QUEUED, RUNNING, CANCELLING -> EXIT_NEEDS_ATTENTION;
        };
    }

    private static Map<RepairPolicy.Category, RepairPolicy.Mode> allModes(RepairPolicy.Mode mode) {
        EnumMap<RepairPolicy.Category, RepairPolicy.Mode> modes =
                new EnumMap<>(RepairPolicy.Category.class);
        for (RepairPolicy.Category category : RepairPolicy.Category.values()) modes.put(category, mode);
        return modes;
    }

    private static Map<RepairPolicy.Category, RepairPolicy.Mode> defaultAskModes() {
        EnumMap<RepairPolicy.Category, RepairPolicy.Mode> modes =
                new EnumMap<>(RepairPolicy.Category.class);
        for (RepairPolicy.Category category : RepairPolicy.Category.values()) {
            modes.put(category, category == RepairPolicy.Category.OTHER
                    ? RepairPolicy.Mode.STOP : RepairPolicy.Mode.ASK);
        }
        return modes;
    }
}
