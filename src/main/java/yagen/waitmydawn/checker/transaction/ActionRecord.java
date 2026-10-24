package yagen.waitmydawn.checker.transaction;

import java.util.List;
import java.util.UUID;

/** 机器台账保留动作身份、前后内容指纹和诊断证据；文件位置由受限目标角色推导。 */
public record ActionRecord(int schemaVersion, UUID id, UUID sessionId, String instanceId,
                           int sequence, int round, Kind kind, String modId, String filename,
                           String beforeHash, String afterHash, State state,
                           String reason, List<String> evidence, long createdAt, String note) {
    public enum Kind { INSTALL_MOD, REMOVE_MOD, REPLACE_VERSION, DISABLE_PACK_MARKER, CACHE_LIBRARY, INSTANCE_LIBRARY, RESTORE_ADD, RESTORE_REPLACE, RESTORE_REMOVE, RESTORE_MARKER_ADD, RESTORE_MARKER_REPLACE }
    public enum State { PREPARED, APPLIED, REVERTING, REVERTED, CONFLICT }

    public ActionRecord {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    ActionRecord state(State state, String note) {
        return new ActionRecord(schemaVersion, id, sessionId, instanceId, sequence, round, kind, modId, filename,
                beforeHash, afterHash, state, reason, evidence, createdAt, note);
    }

    public boolean unresolved() { return state == State.PREPARED || state == State.REVERTING || state == State.CONFLICT; }
}
