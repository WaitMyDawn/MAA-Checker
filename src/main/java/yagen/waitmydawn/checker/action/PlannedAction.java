package yagen.waitmydawn.checker.action;

import java.util.List;

/**
 * 一条待执行的动作。字段刻意保留"证据"（{@link #errorText()}）：执行之后用户还要能看懂
 * "当初是因为哪一类问题的哪一段原文，才动了这个模组"。
 *
 * @param kind      动作类别
 * @param modId     动作对象（UPGRADE_LOADER=加载器名；INSTALL_DEP=要装的前置；REMOVE_MOD=要摘的模组）
 * @param jarFile   相关 jar 文件名（可能为 null，例如要装的前置还没下载）
 * @param category  触发动作为问题类别（{@link yagen.waitmydawn.checker.core.Diagnosis.Kind} 名字或说明）
 * @param reason    人读原因（"因为 X 缺少前置 Y"这类）
 * @param errorText 报错原文（按行），执行台账与大日志都会原样带上
 * @param target    动作参数：UPGRADE_LOADER=目标最低版本；INSTALL_DEP=依赖方 modId；REMOVE_MOD=级联根
 * @param cascadeOf 非空表示"这是级联摘除"，值为级联根 modId
 * @param versionRange 依赖声明的版本区间（只有 INSTALL_DEP 用得上），用于挑"满足区间"的开发者版本
 */
public record PlannedAction(ActionKind kind, String modId, String jarFile, String category,
                            String reason, List<String> errorText, String target,
                            String cascadeOf, String versionRange) {
}
