package yagen.waitmydawn.checker.desktop;

import yagen.waitmydawn.checker.task.RepairWorkflow;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 任务状态、工作流阶段与用户下一步的统一文案映射。
 *
 * <p>“操作完成”不等于“实例启动通过”：恢复完成、清理完成和记录读取完成都保持独立语义。
 * 失败或需要处理时必须给出可执行下一步，不能只显示内部异常或空泛的“无法执行”。
 */
final class UiStatus {
    enum Tone { NEUTRAL, ACTIVE, SUCCESS, WARNING }

    record Outcome(String title, Tone tone, String nextStep) {
        Outcome {
            title = Objects.requireNonNull(title);
            tone = Objects.requireNonNull(tone);
            nextStep = Objects.requireNonNull(nextStep);
        }
    }

    private UiStatus() { }

    static Outcome task(TaskSnapshot.State state, String operation, String message) {
        String op = Objects.toString(operation, "操作");
        String detail = Objects.toString(message, "");
        return switch (state) {
            case QUEUED -> new Outcome("等待" + op, Tone.NEUTRAL, "等待当前步骤开始；完成前不要重复启动同一实例。");
            case RUNNING -> new Outcome("正在" + op, Tone.ACTIVE, "等待当前步骤完成；必要时可停止任务。");
            case CANCELLING -> new Outcome("正在停止并清理", Tone.ACTIVE,
                    "等待进程和文件动作释放后再继续；不要重复启动同一实例。");
            case SUCCEEDED -> succeeded(op);
            case FAILED -> failure(op, detail);
            case NEEDS_ATTENTION -> needsAttention(op);
            case CANCELLED -> cancelled(op);
        };
    }

    static Outcome workflow(RepairWorkflow.Phase phase, boolean active) {
        return switch (phase) {
            case CHECKING -> new Outcome("检查中", Tone.ACTIVE, "等待启动与诊断完成；届时会显示证据和可执行下一步。");
            case PLANNING -> new Outcome("规划中", Tone.ACTIVE, "等待候选和事务方案准备完成；尚未修改实例。");
            case WAITING -> new Outcome("等待你的决定", Tone.WARNING,
                    "选择“允许此操作”或“拒绝并暂停”；等待期间不会允许其他写入进入同一实例。");
            case APPLYING -> new Outcome("执行与验证中", Tone.ACTIVE,
                    "等待文件动作和启动验证完成；不要关闭游戏或重复启动同一实例。");
            case VERIFIED -> new Outcome("检验通过", Tone.SUCCESS,
                    "查看本轮报告；可以开始新会话或打开轮次记录。");
            case NOT_PASSED -> new Outcome("验证未通过", Tone.WARNING,
                    "查看诊断依据后从当前轮继续，或打开轮次记录还原到轮前状态。");
            case PAUSED -> new Outcome("已暂停", Tone.WARNING,
                    "查看暂停原因；可从停止轮次继续、处理账本问题，或还原轮前状态。");
            case RESTORING -> active
                    ? new Outcome("正在恢复", Tone.ACTIVE, "等待当前恢复动作完成；不要同时启动游戏。")
                    : new Outcome("恢复中断", Tone.WARNING,
                            "在轮次记录中重新预览同一轮并继续恢复；恢复凭据会保留，不能单独撤销。");
            case RESTORED -> new Outcome("已还原，尚未重新检验", Tone.NEUTRAL,
                    "点击“从停止轮次继续”重新启动检验；已还原不代表启动通过。");
        };
    }

    static Outcome plan(boolean executable, int fileCount, List<String> blocked) {
        if (executable) {
            return new Outcome("修复方案等待确认", Tone.NEUTRAL,
                    "核对 " + fileCount + " 个文件动作、诊断证据和恢复方式后，再勾选确认并执行。");
        }
        if (blocked.isEmpty()) {
            return new Outcome("没有可安全执行的方案", Tone.WARNING,
                    "查看实时日志和完整报告；补充可复现证据或人工核对模组后重新检查。无动作不能当作修复成功。");
        }
        return new Outcome("修复方案需要处理", Tone.WARNING,
                "逐条查看阻断原因；调整实例或策略后重新检查并准备方案，必要时还原轮前状态。");
    }

    static Outcome error(String operation, String message) {
        String op = Objects.toString(operation, "操作");
        String detail = Objects.toString(message, "");
        if (isConflict(detail)) {
            return new Outcome("存在文件冲突", Tone.WARNING,
                    "不要覆盖外部修改；打开最近修复记录核对冲突文件，再选择保留、重试或恢复。");
        }
        return failure(op, detail);
    }

    private static Outcome succeeded(String operation) {
        if (operation.equals("检查")) {
            return new Outcome("本次启动检验通过", Tone.SUCCESS,
                    "查看保存的报告；如需进一步修复，从此结果准备方案。");
        }
        if (operation.equals("准备方案")) {
            return new Outcome("方案准备完成", Tone.NEUTRAL,
                    "核对文件动作、证据和恢复方式后再确认执行。");
        }
        if (operation.contains("恢复")) {
            return new Outcome("已恢复，尚未重新启动检验", Tone.NEUTRAL,
                    "查看恢复结果；需要确认启动状态时重新开始检验。已恢复不等于启动通过。");
        }
        if (operation.contains("修复")) {
            return new Outcome("修复后启动检验通过", Tone.SUCCESS,
                    "查看本轮报告；需要回退时打开轮次记录或最近修复记录。");
        }
        if (operation.contains("清理")) {
            return new Outcome("清理完成", Tone.NEUTRAL,
                    "准备缓存已清理；需要继续修复时重新准备方案。");
        }
        if (operation.contains("读取")) {
            return new Outcome(operation + "完成", Tone.NEUTRAL,
                    "选择轮次或会话查看证据，再决定继续或恢复。");
        }
        if (operation.contains("保存")) {
            return new Outcome("策略已保存", Tone.SUCCESS,
                    "后续选择该实例会自动加载当前保存值。");
        }
        return new Outcome(operation + "完成", Tone.NEUTRAL, "查看结果后再选择下一步。");
    }

    private static Outcome failure(String operation, String message) {
        if (isConflict(message)) {
            return new Outcome("存在文件冲突", Tone.WARNING,
                    "不要覆盖外部修改；打开最近修复记录核对冲突文件，再选择保留、重试或恢复。");
        }
        if (operation.contains("保存")) {
            return new Outcome("策略保存失败", Tone.WARNING,
                    "保留当前编辑值，检查磁盘权限后重试；原有保存文件不会被默认值覆盖。");
        }
        if (operation.contains("恢复")) {
            return new Outcome("恢复失败", Tone.WARNING,
                    "查看缺失文件或冲突；在轮次记录重新预览并继续，不能单独撤销恢复凭据。");
        }
        if (operation.contains("修复")) {
            return new Outcome("修复执行失败", Tone.WARNING,
                    "打开最近修复记录或轮次记录；按日志处理，必要时还原轮前状态。");
        }
        if (operation.equals("检查")) {
            if (message.contains("未通过") || message.contains("诊断报告已生成")) {
                return new Outcome("检查未通过", Tone.WARNING,
                        "查看失败轮次的证据与报告；需要修改时准备修复方案，不要直接认定是某个模组的问题。");
            }
            return new Outcome("检查执行失败", Tone.WARNING,
                    "查看实时日志与报告；确认实例路径、Java 和启动条件后重新检查。");
        }
        if (operation.contains("准备")) {
            return new Outcome("方案准备失败", Tone.WARNING,
                    "查看下载、安装器或候选校验错误；确认网络和实例后重新准备。");
        }
        if (operation.contains("读取")) {
            return new Outcome("记录读取失败", Tone.WARNING,
                    "修复损坏的轮次记录或事务账本后重新选择实例。");
        }
        if (operation.contains("清理")) {
            return new Outcome("清理失败", Tone.WARNING,
                    "重新预览准备缓存，确认没有活动任务后再清理。");
        }
        return new Outcome(operation + "失败", Tone.WARNING,
                "查看实时日志中的具体错误，处理后再重试；不要根据失败结果判断实例已修复。");
    }

    private static Outcome needsAttention(String operation) {
        if (operation.equals("检查")) {
            return new Outcome("检查未通过", Tone.WARNING,
                    "查看失败轮次的证据与报告；需要修改时准备修复方案，不要直接认定是某个模组的问题。");
        }
        if (operation.contains("修复")) {
            return new Outcome("修复需要人工处理", Tone.WARNING,
                    "查看被阻断的动作和证据；调整策略后继续，或还原轮前状态。");
        }
        if (operation.contains("恢复")) {
            return new Outcome("恢复尚未完成", Tone.WARNING,
                    "重新预览剩余差异并继续恢复；存在冲突时先处理冲突文件。");
        }
        return new Outcome(operation + "需要处理", Tone.WARNING,
                "查看报告与日志，处理提示中的条件后重新执行。");
    }

    private static Outcome cancelled(String operation) {
        if (operation.contains("恢复")) {
            return new Outcome("恢复已停止", Tone.WARNING,
                    "从轮次记录重新预览剩余差异并继续；已完成的文件动作不会重复执行。");
        }
        if (operation.contains("修复")) {
            return new Outcome("修复已停止", Tone.WARNING,
                    "打开最近修复记录；已提交动作会保留，可继续或按会话恢复。");
        }
        return new Outcome(operation + "已停止", Tone.WARNING,
                "本次操作没有完成，不能据此判断实例状态；检查日志后再重试。");
    }

    private static boolean isConflict(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toUpperCase(Locale.ROOT);
        return value.contains("冲突") || value.contains("外部修改") || value.contains("不会覆盖")
                || normalized.contains("CONFLICT");
    }
}
