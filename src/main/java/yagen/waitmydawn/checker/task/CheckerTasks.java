package yagen.waitmydawn.checker.task;

import yagen.waitmydawn.checker.core.CheckerEngine;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceScanner;
import yagen.waitmydawn.checker.core.PathSafety;

import java.nio.file.Path;
import java.util.List;

/** 领域入口适配，不提供尚未通过事务恢复验收的修复任务。 */
public final class CheckerTasks {
    private CheckerTasks() { }

    public static TaskService.Work<List<GameInstance>> scan(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        return context -> {
            context.phase(TaskSnapshot.Phase.SCANNING, "正在识别实例");
            List<GameInstance> instances = InstanceScanner.scanAll(absolute, context::checkpoint);
            context.checkpoint();
            return TaskResult.success(List.copyOf(instances), "识别到 " + instances.size() + " 个实例");
        };
    }

    public static TaskService.Work<Integer> check(GameInstance instance, Path toolRoot, CheckerEngine.Options options) {
        return context -> {
            int code = CheckerEngine.check(instance, toolRoot, options, context::log, context);
            TaskSnapshot.State state = switch (code) {
                case 0 -> TaskSnapshot.State.SUCCEEDED;
                case 1 -> TaskSnapshot.State.FAILED;
                default -> TaskSnapshot.State.NEEDS_ATTENTION;
            };
            String message = switch (state) {
                case SUCCEEDED -> "检验通过";
                case FAILED -> "检验未通过，请查看诊断报告";
                default -> "检验前置条件未满足，需要处理";
            };
            return new TaskResult<>(state, code, message, null);
        };
    }

    public static int exitCode(TaskResult<Integer> result) {
        return switch (result.state()) {
            case SUCCEEDED -> 0;
            case CANCELLED -> 130;
            case NEEDS_ATTENTION -> 2;
            default -> result.value() == null ? 2 : result.value();
        };
    }

    public static TaskService.Work<CheckerEngine.CheckReport> diagnose(GameInstance instance, Path toolRoot,
                                                                       CheckerEngine.Options options) {
        return context -> {
            context.phase(TaskSnapshot.Phase.PRECHECK, "正在重新读取实例元数据");
            GameInstance current = InstanceScanner.scanOne(instance.gameRoot(), instance.dir());
            if (current == null || !PathSafety.samePath(current.versionJson(), instance.versionJson())) {
                throw new java.io.IOException("实例版本文件已变化或无法识别，请重新扫描实例");
            }
            var report = CheckerEngine.checkDetailed(current, toolRoot, options, context::log, context);
            TaskSnapshot.State state = switch (report.exitCode()) {
                case 0 -> TaskSnapshot.State.SUCCEEDED;
                case 1 -> TaskSnapshot.State.FAILED;
                default -> TaskSnapshot.State.NEEDS_ATTENTION;
            };
            String message = switch (state) {
                case SUCCEEDED -> "本次启动检验通过";
                case FAILED -> "本次启动未通过，诊断报告已生成";
                default -> "启动条件未满足，请查看检查日志";
            };
            return new TaskResult<>(state, report, message, null);
        };
    }
}
