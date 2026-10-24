package yagen.waitmydawn.checker.task;

import java.time.Instant;
import java.util.UUID;

/** 跨界面共享的不可变状态；终态仅在资源清理结束后发布。 */
public record TaskSnapshot(UUID id, String subject, State state, Phase phase,
                           String message, int round, Instant createdAt, Instant updatedAt) {
    public enum State {
        QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, NEEDS_ATTENTION, CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == NEEDS_ATTENTION || this == CANCELLED;
        }
    }

    public enum Phase { QUEUED, SCANNING, PRECHECK, PREPARING_LAUNCH, LAUNCHING, OBSERVING, DIAGNOSING, REPORTING, PLANNING, DOWNLOADING, PREPARING_LOADER, APPLYING, RESTORING, CLEANUP, FINISHED }
}
