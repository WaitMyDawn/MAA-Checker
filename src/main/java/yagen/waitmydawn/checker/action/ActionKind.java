package yagen.waitmydawn.checker.action;

/** 三类动作，与用户确认的动作分级一一对应（A 升级 / B 补装 / C 摘除） */
public enum ActionKind {
    /** A：就地升级加载器版本（改实例版本 JSON，带备份与还原） */
    UPGRADE_LOADER,
    /** B：从 Modrinth 补装缺失的前置（装完记录来源，还原=删掉这个文件） */
    INSTALL_DEP,
    /** C：摘除模组（移动到 bin/，不是真删；记录类别与报错原文） */
    REMOVE_MOD
}
