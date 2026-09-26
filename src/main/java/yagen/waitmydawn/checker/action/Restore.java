package yagen.waitmydawn.checker.action;

import yagen.waitmydawn.checker.core.GameInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 还原：把某一轮的改动逐条反做回去（用户要求"可以还原到检验前的模组情况"）。
 *
 * <p>反做顺序 = 逆序执行：
 * <ul>
 *   <li>摘除的 → 从 bin/ 移回 mods/（校验 sha256，不一致就报出来而不是硬塞回去）；</li>
 *   <li>补装的 → 删掉装进去的那个文件（缓存留着，不算改动）；</li>
 *   <li>升加载器的 → 恢复原版本 JSON + 删掉 installer 新产生的版本目录 + 恢复 launcher_profiles.json；</li>
 *   <li>墓碑 → 撤掉本轮加的条目（否则"还原了却装不回来"，等于没还原）。</li>
 * </ul>
 */
public final class Restore {

    private Restore() {
    }

    /** @return 人读的还原步骤清单 */
    public static List<String> restoreRound(Path ledgerFile, GameInstance inst, int round,
                                            Tombstones tombs, Consumer<String> log) throws IOException {
        if (!Files.isRegularFile(ledgerFile)) throw new IOException("找不到台账: " + ledgerFile);
        Ledger ledger = Ledger.load(ledgerFile);
        List<String> steps = new ArrayList<>();
        List<Ledger.Entry> entries = new ArrayList<>(ledger.entries());
        for (int i = entries.size() - 1; i >= 0; i--) {      // 逆序反做
            Ledger.Entry e = entries.get(i);
            switch (e.kind()) {
                case "REMOVE_MOD" -> steps.add(restoreRemoved(inst, e, log));
                case "INSTALL_DEP" -> steps.add(restoreInstalled(inst, e, log));
                case "UPGRADE_LOADER" -> steps.add(restoreLoader(inst, e, log));
                default -> steps.add("跳过未知条目: " + e.kind() + " " + e.modId());
            }
        }
        tombs.removeFromRound(round);
        tombs.save();
        return steps;
    }

    private static String restoreRemoved(GameInstance inst, Ledger.Entry e, Consumer<String> log) {
        String movedTo = e.data().get("movedTo");
        if (movedTo == null) return "无法还原 " + e.modId() + "：台账缺少 movedTo";
        Path src = Path.of(movedTo);
        Path dest = inst.modsDir().resolve(e.jarFile());
        if (!Files.isRegularFile(src)) return "无法还原 " + e.modId() + "：bin 里已不存在 " + src;
        if (Files.exists(dest)) {
            return "跳过还原 " + e.modId() + "：mods 目录里已有同名文件（不覆盖）";
        }
        try {
            String expected = e.data().get("sha256");
            String actual = ActionExecutor.sha256(src);
            if (expected != null && !expected.isBlank() && !expected.equals(actual)) {
                return "⚠️ 还原 " + e.modId() + " 但哈希不一致（台账 " + expected.substring(0, 12)
                        + " / 实际 " + actual.substring(0, 12) + "），已放入但请人工确认";
            }
            Files.createDirectories(inst.modsDir());
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            log.accept("   ↩ 已还原 " + e.jarFile() + " → " + inst.modsDir());
            return "还原摘除: " + e.modId();
        } catch (Exception ex) {
            return "还原 " + e.modId() + " 失败: " + ex.getMessage();
        }
    }

    private static String restoreInstalled(GameInstance inst, Ledger.Entry e, Consumer<String> log) {
        String path = e.data().get("installedPath");
        if (path == null) return "跳过 " + e.modId() + "：该条没有实际安装文件（" + e.data() + "）";
        Path p = Path.of(path);
        // 安全检查：只删 mods 目录里的文件，别的一律不动
        if (!p.toAbsolutePath().normalize().startsWith(inst.modsDir().toAbsolutePath().normalize())) {
            return "跳过删除 " + p + "：不在实例 mods 目录内";
        }
        try {
            boolean deleted = Files.deleteIfExists(p);
            if (deleted) log.accept("   ↩ 已删除补装文件 " + p.getFileName());
            return (deleted ? "还原补装: " + e.modId() : "补装文件已不存在: " + e.modId());
        } catch (IOException ex) {
            return "删除补装文件失败 " + e.modId() + ": " + ex.getMessage();
        }
    }

    private static String restoreLoader(GameInstance inst, Ledger.Entry e, Consumer<String> log) {
        StringBuilder sb = new StringBuilder();
        try {
            String backup = e.data().get("jsonBackup");
            if (backup != null && Files.isRegularFile(Path.of(backup))) {
                Files.copy(Path.of(backup), inst.versionJson(), StandardCopyOption.REPLACE_EXISTING);
                log.accept("   ↩ 已恢复实例版本 JSON（" + e.data().get("from") + " ← "
                        + e.data().get("to") + "）");
                sb.append("还原加载器 ").append(e.data().get("from")).append(" ← ");
            } else {
                sb.append("无法还原加载器：找不到备份 ");
            }
            String producedDir = e.data().get("producedDir");
            if ("false".equals(e.data().get("producedDirExisted")) && producedDir != null) {
                Path dir = Path.of(producedDir);
                Path versionsRoot = inst.gameRoot().resolve("versions").toAbsolutePath().normalize();
                if (dir.toAbsolutePath().normalize().startsWith(versionsRoot)
                        && dir.getFileName().toString().startsWith("neoforge-")) {
                    deleteRecursively(dir);
                    log.accept("   ↩ 已删除 installer 产生的版本目录 " + dir.getFileName());
                }
            }
            // 沙盒模式（2026-09-22 起）：installer 产物在软件目录的 installer-work/ 下，
            // 还原时把它删掉即可；删之前必须先 rd 掉 junction（详见 LoaderPatch.cleanupSandbox）
            String sandbox = e.data().get("sandbox");
            if (sandbox != null && !sandbox.isBlank()) {
                Path sandboxDir = Path.of(sandbox);
                if (Files.exists(sandboxDir)) {
                    boolean cleaned = LoaderPatch.cleanupSandbox(sandboxDir,
                            sandboxDir.resolve("libraries"), log);
                    log.accept("   ↩ 已清理安装沙盒 " + sandboxDir.getFileName()
                            + (cleaned ? "" : "（有残留）"));
                }
            }
            String lp = e.data().get("launcherProfilesBackup");
            if (lp != null && !lp.isBlank() && Files.isRegularFile(Path.of(lp))) {
                Files.copy(Path.of(lp), inst.gameRoot().resolve("launcher_profiles.json"),
                        StandardCopyOption.REPLACE_EXISTING);
                log.accept("   ↩ 已恢复 launcher_profiles.json");
            }
        } catch (Exception ex) {
            sb.append("失败: ").append(ex.getMessage());
        }
        return sb.toString();
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
