package yagen.waitmydawn.checker.core;

import java.nio.file.Path;

/**
 * 识别出的一个整合包实例（可直接启动的那个游戏目录）。
 *
 * @param name          实例名（versions 下的目录名，可能是中文）
 * @param dir           实例目录（版本隔离时它同时是 gameDir）
 * @param versionJson   真正的版本 JSON（不是 modrinth.index.json 之类的干扰文件）
 * @param mcVersion     Minecraft 版本
 * @param loader        neoforge / forge / fabric / quilt / vanilla
 * @param loaderVersion 加载器版本（vanilla 为 null）
 * @param modsDir       实际生效的 mods 目录（实例内优先，未隔离回退游戏根）
 * @param modCount      mods 目录里的 jar 数
 * @param gameRoot      游戏根目录（.minecraft）：libraries/ 与 assets/ 都在它下面，
 *                      启动命令的 ${library_directory}/${assets_root} 需要它
 */
public record GameInstance(String name, Path dir, Path versionJson, String mcVersion,
                           String loader, String loaderVersion, Path modsDir, int modCount,
                           Path gameRoot) {

    /** 人读摘要，CLI 与 UI 共用 */
    public String describe() {
        // 0 模组的条目是"原版/纯加载器版本"（例如 installer 装出来的 neoforge-21.1.250），
        // 标出来并排在后面，免得用户在一堆列表里挑错
        String suffix = modCount == 0 ? "（无模组：原版或纯加载器版本）" : "";
        return String.format("%-22s mc=%-9s loader=%-9s %-9s mods=%-4d %s%s",
                name, mcVersion, loader, loaderVersion == null ? "-" : loaderVersion, modCount,
                dir, suffix);
    }

    /** 是否像"整合包实例"（有模组）——GUI 默认列表把它排前面 */
    public boolean looksLikeModpack() {
        return modCount > 0;
    }
}
