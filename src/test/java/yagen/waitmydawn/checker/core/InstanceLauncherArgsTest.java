package yagen.waitmydawn.checker.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数渲染单测：这部分错了会直接把用户实例启动失败，必须有回归网 */
class InstanceLauncherArgsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void 字符串参数替换占位符_未知占位符留空而不是原样塞给游戏() throws Exception {
        Map<String, String> vars = Map.of("auth_player_name", "Steve", "resolution_width", "854");
        List<String> out = InstanceLauncher.renderArgs(
                json("[\"--username\",\"${auth_player_name}\",\"--width\",\"${resolution_width}\",\"${nope}\"]"),
                vars, new ArrayList<>(), "game");
        assertEquals(List.of("--username", "Steve", "--width", "854"), out,
                "未知占位符替换成空串，并且空参数要被丢掉（不能给游戏传 \"${nope}\"）");
    }

    @Test
    void features规则按启动方能力取舍_demo与quickPlay不启用宽度启用() throws Exception {
        String gameArgs = """
                ["--username","${auth_player_name}",
                 {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":["--demo"]},
                 {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],
                  "value":["--width","${resolution_width}","--height","${resolution_height}"]},
                 {"rules":[{"action":"allow","features":{"has_quick_plays_support":true}}],
                  "value":["--quickPlayPath","${quickPlayPath}"]}]
                """;
        List<String> out = InstanceLauncher.renderArgs(json(gameArgs),
                Map.of("auth_player_name", "Steve", "resolution_width", "854", "resolution_height", "480"),
                new ArrayList<>(), "game");
        assertFalse(out.contains("--demo"), "没有正版账号/演示模式，--demo 不能出现");
        assertFalse(out.stream().anyMatch(a -> a.startsWith("--quickPlay")), "不支持 quickPlay");
        assertTrue(out.contains("--width") && out.contains("480"), "分辨率由我们自己指定");
        assertEquals(List.of("--username", "Steve", "--width", "854", "--height", "480"), out);
    }

    @Test
    void os规则只保留当前平台的参数() throws Exception {
        String jvm = """
                [{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                 {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":["-Xss1M"]},
                 "-Djava.library.path=${natives_directory}"]
                """;
        List<String> win = InstanceLauncher.renderArgs(json(jvm),
                Map.of("natives_directory", "N"), new ArrayList<>(), "jvm",
                "windows", new HashMap<>());
        assertFalse(win.contains("-XstartOnFirstThread"), "macOS 专用参数不能进 Windows 命令行");
        assertTrue(win.contains("-Xss1M"));
        assertEquals("-Djava.library.path=N", win.get(win.size() - 1));

        List<String> mac = InstanceLauncher.renderArgs(json(jvm),
                Map.of("natives_directory", "N"), new ArrayList<>(), "jvm",
                "osx", new HashMap<>());
        assertTrue(mac.contains("-XstartOnFirstThread"));
        assertFalse(mac.contains("-Xss1M"));
    }

    @Test
    void 命令行脱敏不泄漏令牌() {
        String text = InstanceLauncher.desensitize(List.of("java", "-cp", "a.jar", "Main",
                "--username", "Steve", "--accessToken", "0", "--clientId", "abc"));
        assertTrue(text.contains("--accessToken <token>"), text);
        assertTrue(text.contains("--clientId <token>"), text);
        assertTrue(text.contains("--username Steve"), "用户名是公开信息，不必脱敏");
    }

    @Test
    void 日志锚点看文件头_轮转重建时不会切出空串() {
        // 旧日志：头 200 字符 + 一段旧内容
        String oldText = "[11:00:00] [main/INFO] 旧日志头" + "H".repeat(180) + "旧日志正文".repeat(20);
        long offset = oldText.length();
        InstanceLauncher.Anchor anchor =
                new InstanceLauncher.Anchor(offset, oldText.substring(0, 200));

        // 1) 同一个文件继续追加 → 只取偏移之后
        assertEquals("\n本轮新增行\n",
                InstanceLauncher.sliceAfterAnchor(oldText + "\n本轮新增行\n", anchor));

        // 2) 更新到了真实案例：新日志长度与旧日志完全相同、末尾行也一样，只有开头时间戳不同。
        //    旧实现（长度+末尾指纹）会切出空串，诊断直接变"没问题"；看头部就不会。
        String rotated = "[11:01:33] [main/INFO] 新日志头" + "H".repeat(180) + "新日志正文".repeat(20);
        assertEquals(rotated.length(), (int) offset, "构造用例：长度确实相同");
        assertEquals(rotated, InstanceLauncher.sliceAfterAnchor(rotated, anchor));

        // 3) 文件比锚点短（轮转后刚开写）→ 整份都是新的
        assertEquals("整份日志", InstanceLauncher.sliceAfterAnchor("整份日志", anchor));

        // 4) 启动前没有日志 → 整份都算新的
        assertEquals("首次启动日志", InstanceLauncher.sliceAfterAnchor("首次启动日志",
                new InstanceLauncher.Anchor(0L, null)));
    }
}
