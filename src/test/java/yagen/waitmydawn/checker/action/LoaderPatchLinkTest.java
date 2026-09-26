package yagen.waitmydawn.checker.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 沙盒用到的 Windows 链接操作（junction / 硬链接 / 清理安全）。
 *
 * <p>这些是"装加载器不留垃圾"的关键机制，也是最危险的一处（递归删除会穿透 junction 删到真实
 * libraries），所以必须有回归测试守着。用例都建立在 Windows 上真实执行，不支持时自动跳过。
 */
class LoaderPatchLinkTest {

    @Test
    void junction_能建能读_删除时不动目标内容(@TempDir Path tmp) throws Exception {
        // 模拟真实布局：沙盒在 tmp/sandbox，junction 在沙盒里，目标是沙盒外的真实 libraries
        Path real = tmp.resolve("real-libraries");
        Files.createDirectories(real);
        Files.writeString(real.resolve("keep.txt"), "must-survive");
        Path sandbox = tmp.resolve("sandbox");
        Path junction = sandbox.resolve("libraries");
        Files.createDirectories(sandbox);

        assumeTrue(LoaderPatch.linkDirectory(junction, real, null),
                "本机不支持 mklink /J，跳过（功能会降级为 installer 自行下载）");

        assertTrue(Files.isRegularFile(junction.resolve("keep.txt")), "junction 应能读到目标内容");
        // 注意：这里刻意不断言 isDirectoryLink —— 实测 JDK 无法可靠识别 Windows junction
        // （isSymbolicLink=false、NOFOLLOW 的 isDirectory=true），所以清理逻辑改为
        // "按已知路径执行 rd"，不依赖探测。下面的断言才是真正重要的安全性。

        boolean cleaned = LoaderPatch.cleanupSandbox(sandbox, junction, null);

        assertTrue(cleaned, "沙盒应被清干净");
        assertFalse(Files.exists(sandbox), "沙盒目录应消失");
        assertTrue(Files.isRegularFile(real.resolve("keep.txt")),
                "★ 删除 junction 绝不能动到真实 libraries 的内容");
    }

    @Test
    void 硬链接同卷可用_目标不存在时优雅失败(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("instance.jar");
        Files.writeString(target, "abc");
        Path link = tmp.resolve("sandbox-versions").resolve("1.21.1.jar");
        Files.createDirectories(link.getParent());

        if (LoaderPatch.linkFile(link, target)) {
            assertTrue(Files.isRegularFile(link));
            assertTrue(LoaderPatch.sha1(link).equals(LoaderPatch.sha1(target)),
                    "硬链接内容必须与目标一致（installer 会校验 sha1）");
        }
        // 目标不存在时必须返回 false（调用方据此降级为复制/下载）
        assertFalse(LoaderPatch.linkFile(tmp.resolve("nope.jar"), tmp.resolve("missing.jar")));
    }

    @Test
    void 普通目录不能被误判为目录链接(@TempDir Path tmp) throws Exception {
        Path plain = tmp.resolve("plain");
        Files.createDirectories(plain);
        assertFalse(LoaderPatch.isDirectoryLink(plain));
    }
}
