package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HMCL 整合包标记的停用/还原。
 *
 * <p>背景（2026-09-26 实测）：HMCL 每次【启动】都会按 {@code modpack.cfg} 里的 Modrinth 清单补全文件，
 * 把我们摘掉的模组原样下回来。控制它的开关就是"HMCL 认不认这个标记文件"。
 */
class ModpackMarkerTest {

    @TempDir
    Path tmp;

    @Test
    void 停用后改名且内容一字未改_还原能改回来(@TempDir Path tmp) throws Exception {
        GameInstance inst = instance(tmp);
        Path cfg = tmp.resolve(ModpackMarker.FILE);
        String content = "{\"type\":\"Modrinth\",\"name\":\"万象\",\"version\":\"1.0.0\"}";
        Files.writeString(cfg, content, StandardCharsets.UTF_8);

        ModpackMarker.Disabled d = ModpackMarker.disable(inst);

        assertNotNull(d);
        assertFalse(Files.exists(cfg), "原文件必须已经被改名");
        assertTrue(d.renamed().getFileName().toString().startsWith("modpack.cfg.maa-checker-bak"));
        assertEquals(content, Files.readString(d.renamed(), StandardCharsets.UTF_8), "内容不能改");
        assertNotNull(ModpackMarker.findDisabled(inst));
        assertNull(ModpackMarker.find(inst), "改名后 HMCL 就找不到它了");

        Path back = ModpackMarker.restore(inst);

        assertEquals(cfg, back);
        assertEquals(content, Files.readString(cfg, StandardCharsets.UTF_8));
        assertNull(ModpackMarker.findDisabled(inst));
        assertNull(ModpackMarker.restore(inst), "没有可还原的标记时应返回 null（幂等）");
    }

    @Test
    void 没有整合包标记时什么都不做(@TempDir Path tmp) throws Exception {
        GameInstance inst = instance(tmp);

        assertNull(ModpackMarker.disable(inst));
        assertNull(ModpackMarker.find(inst));
        assertNull(ModpackMarker.restore(inst));
        assertTrue(Files.list(tmp).findAny().isEmpty(), "不能凭空造文件");
    }

    @Test
    void 重复停用不覆盖上一次的备份(@TempDir Path tmp) throws Exception {
        GameInstance inst = instance(tmp);
        Files.writeString(tmp.resolve(ModpackMarker.FILE), "第一次", StandardCharsets.UTF_8);
        ModpackMarker.Disabled first = ModpackMarker.disable(inst);

        // 场景：HMCL 又把这个实例当成整合包（或用户手动放回了一个 modpack.cfg）
        Files.writeString(tmp.resolve(ModpackMarker.FILE), "第二次", StandardCharsets.UTF_8);
        ModpackMarker.Disabled second = ModpackMarker.disable(inst);

        assertNotNull(second);
        assertTrue(second.renamed().getFileName().toString()
                .matches("modpack\\.cfg\\.maa-checker-bak-\\d+"), second.renamed().getFileName().toString());
        assertEquals("第一次", Files.readString(first.renamed(), StandardCharsets.UTF_8),
                "上一次的备份不能被覆盖");
        assertEquals("第二次", Files.readString(second.renamed(), StandardCharsets.UTF_8));
    }

    private GameInstance instance(Path dir) {
        return new GameInstance("test", dir, dir.resolve("test.json"), "1.21.1",
                "neoforge", "21.1.231", dir.resolve("mods"), 0, dir);
    }
}
