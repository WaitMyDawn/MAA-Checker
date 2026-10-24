package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.checker.core.GameInstance;
import yagen.waitmydawn.checker.core.InstanceStorage;
import yagen.waitmydawn.checker.transaction.InstanceIdentity;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 随实例目录移动的恢复资料谱系。
 *
 * <p>恢复资料仍以路径派生身份作为事务和快照的强绑定，不把任意同名目录视为同一实例。
 * 该记录只保存稳定谱系 UUID、当前身份和显式迁移过的旧身份，用于移动后的重新关联审计。
 */
public record RecoveryLineage(int schema, UUID recoveryId, String identityId, String directory,
                              String modsDirectory, String versionFile,
                              List<String> previousIdentityIds, long updatedAt) {
    private static final String FILE = "recovery-lineage-v1.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    public RecoveryLineage {
        previousIdentityIds = previousIdentityIds == null
                ? List.of() : List.copyOf(previousIdentityIds);
    }

    public static Optional<RecoveryLineage> read(GameInstance instance) throws IOException {
        Path file = path(instance, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("恢复资料谱系不是普通文件");
        }
        RecoveryLineage value = JSON.readValue(Files.readAllBytes(file), RecoveryLineage.class);
        if (value.schema() != 1 || value.recoveryId() == null
                || value.identityId() == null || value.identityId().isBlank()
                || !value.identityId().matches("[a-f0-9]{64}")
                || value.directory() == null || value.directory().isBlank()
                || value.modsDirectory() == null || value.modsDirectory().isBlank()
                || value.versionFile() == null || value.versionFile().isBlank()
                || value.previousIdentityIds().stream().anyMatch(id -> !id.matches("[a-f0-9]{64}"))) {
            throw new IOException("恢复资料谱系格式无效");
        }
        return Optional.of(value);
    }

    /**
     * 新实例或同一身份的后续写入使用该入口。若实例已经移动但尚未显式重新关联，
     * 这里拒绝静默改写谱系，避免把旧恢复点藏在新的路径身份下。
     */
    public static RecoveryLineage ensure(GameInstance instance, InstanceIdentity identity) throws IOException {
        Optional<RecoveryLineage> existing = read(instance);
        if (existing.isPresent()) {
            RecoveryLineage value = existing.get();
            if (identity.id().equals(value.identityId())) return value;
            throw new IOException("恢复资料仍绑定旧实例身份；请先在存储页显式预览并重新关联");
        }
        RecoveryLineage created = new RecoveryLineage(1, UUID.randomUUID(), identity.id(),
                identity.directory(), identity.modsDirectory(), identity.versionFile(),
                List.of(), System.currentTimeMillis());
        write(instance, created);
        return created;
    }

    /**
     * 重新关联保留原谱系 UUID，只把当前路径身份切换到目标实例，并记录旧身份供审计。
     */
    public static RecoveryLineage reassociate(GameInstance instance, InstanceIdentity source,
                                               InstanceIdentity target) throws IOException {
        Optional<RecoveryLineage> existing = read(instance);
        UUID recoveryId;
        LinkedHashSet<String> previous = new LinkedHashSet<>();
        if (existing.isPresent()) {
            RecoveryLineage value = existing.get();
            if (!Objects.equals(source.id(), value.identityId())
                    && !value.previousIdentityIds().contains(source.id())
                    && !Objects.equals(target.id(), value.identityId())) {
                throw new IOException("恢复资料谱系与待迁移的旧身份不一致");
            }
            recoveryId = value.recoveryId();
            previous.addAll(value.previousIdentityIds());
        } else {
            recoveryId = UUID.randomUUID();
        }
        previous.add(source.id());
        previous.remove(target.id());
        RecoveryLineage migrated = new RecoveryLineage(1, recoveryId, target.id(),
                target.directory(), target.modsDirectory(), target.versionFile(),
                new ArrayList<>(previous), System.currentTimeMillis());
        write(instance, migrated);
        return migrated;
    }

    private static Path path(GameInstance instance, boolean create) throws IOException {
        return InstanceStorage.root(instance, create).resolve(FILE);
    }

    private static void write(GameInstance instance, RecoveryLineage value) throws IOException {
        Path file = path(instance, true);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                throw new IOException("恢复资料谱系不是普通文件");
            }
        }
        Path temp = file.resolveSibling(FILE + ".tmp-" + UUID.randomUUID());
        try {
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
