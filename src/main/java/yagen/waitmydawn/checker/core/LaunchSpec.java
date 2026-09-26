package yagen.waitmydawn.checker.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 组装好的启动方案（只描述"怎么启动"，不含执行）。
 *
 * <p>单独抽出来是为了可测与可解释：CLI 的 {@code --no-launch} 只打印 {@link #commandText()}，
 * 不产生任何进程；出问题时也能把这份命令直接贴给用户自证。
 *
 * @param command      完整命令行（第 0 个元素是 java.exe 绝对路径）
 * @param commandText  脱敏后的命令行文本（accessToken 换成 &lt;token&gt;），用于日志与台账
 * @param workDir      工作目录 = 实例目录
 * @param latestLog    游戏日志 logs/latest.log（本次新增内容靠"内容锚点"识别）
 * @param nativesDir   实例内已解压的 natives-windows-x86_64
 * @param stdoutLog    客户端 stdout/stderr 落盘位置（软件目录 logs/&lt;实例&gt;/&lt;轮次&gt;/）
 * @param runDir       本轮产物目录（软件目录 logs/&lt;实例&gt;/&lt;轮次&gt;/），诊断切片与报告都写这里
 * @param requiredJava 版本 JSON 里声明的 javaVersion.majorVersion
 * @param notes        构建期的提醒（缺 jar、占位符未求值等），必须如实展示给用户
 */
public record LaunchSpec(List<String> command, String commandText, Path workDir, Path latestLog,
                         Path nativesDir, Path stdoutLog, Path runDir, int requiredJava,
                         List<String> notes) {
}
