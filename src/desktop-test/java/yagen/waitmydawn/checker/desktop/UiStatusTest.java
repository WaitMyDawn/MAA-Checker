package yagen.waitmydawn.checker.desktop;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.checker.task.RepairWorkflow;
import yagen.waitmydawn.checker.task.TaskSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiStatusTest {

    @Test
    void 所有任务状态都给出标题与下一步() {
        for (TaskSnapshot.State state : TaskSnapshot.State.values()) {
            for (String operation : List.of("检查", "准备方案", "修复与验证", "恢复", "清理准备缓存",
                    "读取轮次记录", "保存修复策略", "多轮诊断修复")) {
                UiStatus.Outcome outcome = UiStatus.task(state, operation, "fixture");
                assertFalse(outcome.title().isBlank(), state + " / " + operation);
                assertFalse(outcome.nextStep().isBlank(), state + " / " + operation);
            }
        }
    }

    @Test
    void 所有持久化工作流阶段都有一致动作文案() {
        for (RepairWorkflow.Phase phase : RepairWorkflow.Phase.values()) {
            UiStatus.Outcome outcome = UiStatus.workflow(phase, false);
            assertFalse(outcome.title().isBlank(), phase.name());
            assertFalse(outcome.nextStep().isBlank(), phase.name());
        }
        assertTrue(UiStatus.workflow(RepairWorkflow.Phase.WAITING, false).nextStep().contains("允许"));
        assertTrue(UiStatus.workflow(RepairWorkflow.Phase.PAUSED, false).nextStep().contains("继续"));
        assertTrue(UiStatus.workflow(RepairWorkflow.Phase.RESTORING, false).title().contains("中断"));
        assertTrue(UiStatus.workflow(RepairWorkflow.Phase.RESTORING, false).nextStep().contains("不能单独撤销"));
        assertTrue(UiStatus.workflow(RepairWorkflow.Phase.RESTORED, false).nextStep().contains("从停止轮次继续"));
        assertFalse(UiStatus.workflow(RepairWorkflow.Phase.RESTORED, false).title().contains("启动通过"));
    }

    @Test
    void 停止和恢复不会显示为启动通过() {
        UiStatus.Outcome stopped = UiStatus.task(TaskSnapshot.State.CANCELLED, "检查", "stopped");
        UiStatus.Outcome restored = UiStatus.task(TaskSnapshot.State.SUCCEEDED, "恢复轮前快照", "restored");
        UiStatus.Outcome verified = UiStatus.task(TaskSnapshot.State.SUCCEEDED, "检查", "verified");

        assertFalse(stopped.title().contains("通过"));
        assertFalse(restored.title().contains("通过"));
        assertTrue(restored.title().contains("尚未重新启动检验"));
        assertTrue(verified.title().contains("通过"));
    }

    @Test
    void 冲突和空方案都有可执行下一步() {
        UiStatus.Outcome conflict = UiStatus.error("恢复", "当前文件被外部修改，存在冲突");
        UiStatus.Outcome empty = UiStatus.plan(false, 0, List.of());

        assertEquals("存在文件冲突", conflict.title());
        assertTrue(conflict.nextStep().contains("最近修复记录"));
        assertTrue(empty.title().contains("没有可安全执行"));
        assertTrue(empty.nextStep().contains("重新检查"));
        assertTrue(empty.nextStep().contains("不能当作修复成功"));
    }
}
