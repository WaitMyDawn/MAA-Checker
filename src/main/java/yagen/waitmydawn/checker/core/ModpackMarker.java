package yagen.waitmydawn.checker.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * HMCL 的「整合包标记」`modpack.cfg`：把它停用/还原。
 *
 * <p><b>为什么需要这个</b>（2026-09-26 实测，万象包）：HMCL 把导入的整合包记在实例目录下的
 * {@code modpack.cfg} 里，内嵌一份完整的 Modrinth manifest（`type=Modrinth`，194 条 `files[]`，
 * 每条带 `path` + `hashes` + `downloads`）。**每次点【启动】**都会跑
 * {@code org.jackhuang.hmcl.modpack.modrinth.ModrinthCompletionTask}（"下载整合包相关文件"），
 * 逐条校验 manifest 里的文件、**缺谁下谁**——于是我们刚摘掉的模组，用户一启动就被原样补回来
 * （HMCL 日志实测：12:15:38 启动 → 12:15:45 `Task finished: 下载整合包相关文件`，
 * 而下过的 13 个 jar 正好是我们摘掉的那 13 个）。
 *
 * <p>三种解法里选了最干净的一种（用户 2026-09-26 确认）：**把标记文件改名**，
 * HMCL 就不再把它当整合包、也就不会再跑补全任务。代价是 HMCL 里【更新整合包】的入口消失——
 * 想恢复就改名回来（{@code --restore} 会自动做）。
 *
 * <p>注意：HMCL 的补全**只增不删**（实测日志里只有下载、没有删除），所以我们**补装**的模组
 * 不会被它动；只有**摘除**会被撤销，这正是要停用标记的原因。
 */
public final class ModpackMarker {

    public static final String FILE = "modpack.cfg";
    /** 停用后的后缀：内容一字未改，只是换个名字让 HMCL 认不出来 */
    public static final String DISABLED_SUFFIX = ".maa-checker-bak";

    /** @param original 原来的 modpack.cfg；@param renamed 改名后的文件 */
    public record Disabled(Path original, Path renamed) {
    }

    private ModpackMarker() {
    }

    /** 实例里的 modpack.cfg（HMCL 整合包标记）；不存在返回 null */
    public static Path find(GameInstance inst) {
        if (inst == null || inst.dir() == null) return null;
        Path cfg = inst.dir().resolve(FILE);
        return Files.isRegularFile(cfg) ? cfg : null;
    }

    /** 已被本工具停用的标记（{@code modpack.cfg.maa-checker-bak*}，取最新一个）；没有返回 null */
    public static Path findDisabled(GameInstance inst) {
        if (inst == null || inst.dir() == null || !Files.isDirectory(inst.dir())) return null;
        try (Stream<Path> s = Files.list(inst.dir())) {
            List<Path> hits = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(FILE + DISABLED_SUFFIX))
                    .sorted(java.util.Comparator.comparing(
                            (Path p) -> {
                                try {
                                    return Files.getLastModifiedTime(p).toMillis();
                                } catch (IOException e) {
                                    return 0L;
                                }
                            }).reversed())
                    .toList();
            return hits.isEmpty() ? null : hits.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 停用：{@code modpack.cfg} → {@code modpack.cfg.maa-checker-bak}。
     *
     * @return 改了哪个文件；实例里没有 modpack.cfg 时返回 null（幂等：已经停用过也不会重复改名）
     */
    public static Disabled disable(GameInstance inst) throws IOException {
        Path cfg = find(inst);
        if (cfg == null) return null;
        Path dst = inst.dir().resolve(FILE + DISABLED_SUFFIX);
        if (Files.exists(dst)) {
            // 已经停用过：加时间戳，绝不覆盖上一次的备份
            dst = inst.dir().resolve(FILE + DISABLED_SUFFIX + "-" + System.currentTimeMillis());
        }
        Files.move(cfg, dst);
        return new Disabled(cfg, dst);
    }

    /**
     * 还原：把最近的 {@code modpack.cfg.maa-checker-bak*} 改回 {@code modpack.cfg}。
     *
     * @return 改回来的路径；没有可还原的标记（或 modpack.cfg 已存在）时返回 null
     */
    public static Path restore(GameInstance inst) throws IOException {
        Path back = findDisabled(inst);
        if (back == null) return null;
        Path cfg = inst.dir().resolve(FILE);
        if (Files.exists(cfg)) return null;          // 已经有了就不动（用户自己放回来过）
        Files.move(back, cfg);
        return cfg;
    }
}
