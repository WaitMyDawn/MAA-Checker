package yagen.waitmydawn.checker.desktop.preview;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 仅在内存中演示任务状态；不持有实例路径或文件变更能力。 */
public final class PreviewSession {
    public enum State { REVIEW, RUNNING, PASSED, FAILED, CANCELLED }
    public record History(LocalDateTime time, State state, String detail) { }

    private State state = State.REVIEW;
    private int step;
    private final List<History> history = new ArrayList<>();
    private final List<String> events = new ArrayList<>(List.of("[演示] 已完成诊断，等待审核 2 项修复动作。"));

    public State state() { return state; }
    public int step() { return step; }
    public List<History> history() { return List.copyOf(history); }
    public List<String> events() { return List.copyOf(events); }
    public boolean running() { return state == State.RUNNING; }

    public void start() {
        if (running()) throw new IllegalStateException("演示任务已在运行");
        state = State.RUNNING;
        step = 0;
        events.clear();
        events.add("[演示] 开始修复流程；不会下载、启动游戏或修改文件。");
    }

    public void advance() {
        if (!running()) return;
        step++;
        events.add(switch (step) {
            case 1 -> "[演示] 已建立恢复点，准备调整加载器。";
            case 2 -> "[演示] 已校验前置模组身份，准备补齐依赖。";
            case 3 -> "[演示] 进入启动验证，等待后期启动标记。";
            case 4 -> "[演示] 已观察到启动标记，进入稳定性观察。";
                default -> "[演示] 验证结束。未运行 Minecraft。";
        });
            if (step >= 5) finish(State.PASSED, "演示验证通过 · 文件改动 0 项");
    }

    public void cancel() {
        if (running()) finish(State.CANCELLED, "演示已停止 · 没有文件需要还原");
    }

    public void fail() {
        if (running()) finish(State.FAILED, "模拟下载失败 · 可以重新演示");
    }

    public void reset() {
        if (running()) throw new IllegalStateException("请先停止任务");
        state = State.REVIEW;
        step = 0;
        events.clear();
        events.add("[演示] 已重置方案预览；文件改动 0 项。");
    }

    private void finish(State result, String detail) {
        state = result;
        events.add("[演示] " + detail);
        history.add(new History(LocalDateTime.now(), result, detail));
    }
}
