package yagen.waitmydawn.checker.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次启动检验的执行结果。
 *
 * @param verdict       结论（四态见 {@link LaunchVerdict}）
 * @param evidence      结论依据：命中的标记行 / 进程退出码
 * @param elapsedMs     从进程创建到得出结论的耗时
 * @param exitCode      进程退出码，仍在运行时为 {@link Integer#MIN_VALUE}
 * @param killed        是否由本工具结束（PASS 与 TIMEOUT 都会主动结束）
 * @param runDir        本轮产物目录（软件目录 logs/&lt;实例&gt;/&lt;轮次&gt;/）
 * @param stdoutLog     客户端 stdout 落盘路径
 * @param freshLog      本次运行新增的日志切片（从内容锚点截取，诊断只看它，避免读到上一轮残留）
 * @param timeline      进度时间线（"12.3s: Setting user:"），给用户看"它到底走到哪一步"
 */
public record LaunchResult(LaunchVerdict verdict, String evidence, long elapsedMs, int exitCode,
                           boolean killed, Path runDir, Path stdoutLog, Path freshLog,
                           List<String> timeline) {
}
