package yagen.waitmydawn.checker.core;

/** 模组版本区间不满足时的版本选择策略。 */
public enum VersionUpdateStrategy {
    MINIMUM("更新到满足要求的最低版本"),
    LATEST("更新到最新的兼容版本"),
    CHOOSE("每次自选兼容版本");

    private final String title;

    VersionUpdateStrategy(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
