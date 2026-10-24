package yagen.waitmydawn.checker.task;

import java.util.Objects;

public record TaskResult<T>(TaskSnapshot.State state, T value, String message, Throwable cause) {
    public TaskResult {
        Objects.requireNonNull(state);
        Objects.requireNonNull(message);
        if (!state.terminal()) throw new IllegalArgumentException("任务结果必须是终态");
    }

    public static <T> TaskResult<T> success(T value, String message) {
        return new TaskResult<>(TaskSnapshot.State.SUCCEEDED, value, message, null);
    }

    public static <T> TaskResult<T> failure(String message, Throwable cause) {
        return new TaskResult<>(TaskSnapshot.State.FAILED, null, message, cause);
    }
}
