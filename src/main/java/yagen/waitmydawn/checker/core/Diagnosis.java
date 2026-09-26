package yagen.waitmydawn.checker.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次启动失败（或可疑）的诊断结论。
 *
 * <p>刻意区分"有依赖证据"与"只有崩溃证据"：前者可以级联（缺前置的模组必然崩，摘它不冤），
 * 后者只能摘报错的那一个自己——因为我们面对的是别人装好的一堆 jar 文件，
 * 除了 JAR 里声明的依赖边，拿不到任何"MAA 构筑期"的依赖闭包。
 */
public record Diagnosis(List<Issue> issues, List<String> evidenceLines, Path crashReport,
                        boolean crashReportIsFresh, String note, List<String> attributions) {

    /** 兼容构造：没有"归因过程"记录时（老调用方、纯静态场景） */
    public Diagnosis(List<Issue> issues, List<String> evidenceLines, Path crashReport,
                     boolean crashReportIsFresh, String note) {
        this(issues, evidenceLines, crashReport, crashReportIsFresh, note, List.of());
    }

    /** 故障类型：决定后续动作（A 升级 / B 补装 / C 摘除），不做语义猜测 */
    public enum Kind {
        /** 缺必需前置：Modrinth 上补齐即可（动作 B） */
        MISSING_REQUIRED,
        /** 加载器版本太低（动作 A：升级 loader） */
        LOADER_TOO_OLD,
        /** 其它模组版本区间不满足：静态阶段不下结论，交人工（动作 C 或忽略） */
        MOD_VERSION_MISMATCH,
        /** 模组自身加载失败（构造期抛异常） */
        MOD_LOAD_FAILURE,
        /** Mixin 应用失败：几乎都是冲突，摘报错的那一个自己 */
        MIXIN_FAILURE,
        /** 重复模组/重复类 */
        DUPLICATE_MOD,
        /** 以上之外的崩溃（空指针、原生库、内存等） */
        CRASH_OTHER,
        /**
         * 模组声明的版本号过不了加载器的格式审查（如 {@code Empty pre-release}）。
         *
         * <p>日志里往往<b>只有版本号、没有 modId</b>，靠"谁声明了这个版本"反查（L4）。
         */
        BAD_VERSION_FORMAT,
        /** 从 {@code Caused by} 链里的 {@code from mod X} 指认出来的肇事模组（L2，中置信） */
        CAUSED_BY_ATTRIBUTED,
        /** 从堆栈帧/类名反查出来的肇事模组（L3，最低置信，每轮最多采纳一个） */
        STACK_ATTRIBUTED
    }

    /**
     * @param modId         故障指向的模组（缺前置时=缺失的那个；其余=报错的那个）
     * @param requestedBy   谁需要它（只有缺前置类问题有）
     * @param expectedRange 要求的版本区间（原文，不做归一化，避免二次失真）
     * @param actualVersion 当前实际版本，缺失为 {@code [MISSING]}
     * @param jarFile       相关 jar 文件名（缺前置时 = 需要它的那个模组的 jar），供动作执行器精确定位文件
     * @param detail        原始证据行（截断），用于人复核
     */
    public record Issue(Kind kind, String modId, String requestedBy, String expectedRange,
                        String actualVersion, String jarFile, String detail) {

        /** 是否属于"可以级联"的依赖型故障 */
        public boolean cascadable() {
            return kind == Kind.MISSING_REQUIRED;
        }

        /** 建议动作：只描述类别与目标，真正执行在动作执行器里（并写台账） */
        public String suggestion() {
            return switch (kind) {
                case MISSING_REQUIRED -> "动作B 补装前置 " + modId + "（" + requestedBy + " 需要它）";
                case LOADER_TOO_OLD -> "动作A 升级 " + modId + " 到 " + expectedRange;
                case MOD_VERSION_MISMATCH -> "动作C 摘除 " + requestedBy + "（或人工换版本）";
                case BAD_VERSION_FORMAT -> "动作C 摘除 " + modId + "（它声明的版本号 " + actualVersion
                        + " 过不了加载器的格式审查）";
                case CAUSED_BY_ATTRIBUTED, STACK_ATTRIBUTED ->
                        "动作C 摘除 " + modId + "（由崩溃链/堆栈反查得到，非依赖型故障，只摘它自己）";
                case MIXIN_FAILURE, DUPLICATE_MOD, MOD_LOAD_FAILURE, CRASH_OTHER ->
                        "动作C 摘除 " + (modId == null ? "崩溃报告指认的模组" : modId)
                                + "（非依赖型故障，只能摘报错者自身）";
            };
        }

        public String describe() {
            String culprit = requestedBy == null || requestedBy.equals(modId)
                    ? modId : requestedBy + " → " + modId;
            String jar = jarFile == null ? "" : "（" + jarFile + "）";
            return switch (kind) {
                case MISSING_REQUIRED -> "缺前置: " + culprit + " 需要 " + expectedRange
                        + "（未安装）" + jar;
                case LOADER_TOO_OLD -> "加载器版本不足: " + culprit + " 需要 " + expectedRange
                        + "，当前 " + actualVersion + jar;
                case MOD_VERSION_MISMATCH -> "版本区间不满足: " + culprit + " 需要 " + expectedRange
                        + "，当前 " + actualVersion + jar;
                case BAD_VERSION_FORMAT -> "版本号格式非法: " + culprit + " 声明 " + actualVersion + jar;
                case CAUSED_BY_ATTRIBUTED -> "崩溃链归因: " + culprit + "（Caused by 链里的 from mod）" + jar;
                case STACK_ATTRIBUTED -> "堆栈归因: " + culprit + jar;
                default -> kind + ": " + culprit + jar + " " + detail;
            };
        }
    }

    public boolean hasIssues() {
        return issues != null && !issues.isEmpty();
    }
}
