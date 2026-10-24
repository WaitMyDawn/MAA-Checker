package yagen.waitmydawn.checker.task;

import yagen.waitmydawn.checker.action.*;
import yagen.waitmydawn.checker.core.VersionUpdateStrategy;
import java.util.*;

/** 自动化授权按问题指纹分类，不因下载失败改变授权类别。 */
public record RepairPolicy(Map<Category, Mode> modes, int maxRounds,
                           VersionUpdateStrategy versionStrategy) {
    public RepairPolicy(Map<Category, Mode> modes, int maxRounds) {
        this(modes, maxRounds, VersionUpdateStrategy.MINIMUM);
    }

    public enum Mode { AUTO, ASK, STOP }
    public enum Category {
        LOADER("A · 加载器版本不足"), DEPENDENCY("B · 缺少必需前置"),
        VERSION_MISMATCH("版本区间不满足"), MOD_FAILURE("C · 模组加载失败"),
        MIXIN("Mixin 冲突"), DUPLICATE("重复模组"), BAD_VERSION("非法版本号"),
        ATTRIBUTION("崩溃链或堆栈归因"), CASCADE("依赖方及级联停用"), OTHER("其他不明确故障");
        private final String title;
        Category(String title) { this.title = title; }
        public String title() { return title; }
    }
    public RepairPolicy {
        if (versionStrategy == null) versionStrategy = VersionUpdateStrategy.MINIMUM;
        Objects.requireNonNull(modes, "分类策略不能为空");
        EnumMap<Category, Mode> copy = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            Mode fallback = c == Category.OTHER ? Mode.STOP
                    : c == Category.VERSION_MISMATCH ? Mode.AUTO : Mode.ASK;
            copy.put(c, Objects.requireNonNull(modes.getOrDefault(c, fallback), "分类模式不能为空"));
        }
        modes = Collections.unmodifiableMap(copy);
        if (maxRounds < 1 || maxRounds > 20) throw new IllegalArgumentException("轮次上限必须为 1–20");
    }
    public static RepairPolicy defaults() { return new RepairPolicy(Map.of(), 4); }
    public Category category(PlannedAction action) {
        if (action.kind() == ActionKind.UPGRADE_LOADER) return Category.LOADER;
        if (action.kind() == ActionKind.INSTALL_DEP) return Category.DEPENDENCY;
        if (action.kind() == ActionKind.UPDATE_MOD) return Category.VERSION_MISMATCH;
        String fingerprint = Objects.toString(action.category(), "");
        if (action.cascadeOf() != null || fingerprint.contains("MISSING_REQUIRED")) return Category.CASCADE;
        if (fingerprint.contains("MIXIN")) return Category.MIXIN;
        if (fingerprint.contains("DUPLICATE")) return Category.DUPLICATE;
        if (fingerprint.contains("BAD_VERSION")) return Category.BAD_VERSION;
        if (fingerprint.contains("VERSION_MISMATCH")) return Category.VERSION_MISMATCH;
        if (fingerprint.contains("ATTRIBUTED")) return Category.ATTRIBUTION;
        if (fingerprint.contains("MOD_LOAD_FAILURE")) return Category.MOD_FAILURE;
        return Category.OTHER;
    }
}
