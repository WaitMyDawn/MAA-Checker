package yagen.waitmydawn.checker.task;

/** sequence 在同一任务内严格递增，便于界面丢弃迟到事件。 */
public record TaskEvent(long sequence, Kind kind, TaskSnapshot snapshot, String text) {
    public enum Kind { STATE, LOG }
}
