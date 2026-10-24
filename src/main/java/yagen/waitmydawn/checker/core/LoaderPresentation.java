package yagen.waitmydawn.checker.core;

import java.util.Locale;

/** 实例卡片的加载器类型与可选远程图标来源；图标不随应用分发。 */
public record LoaderPresentation(String code, String family, String title, String iconSource) {
    public static LoaderPresentation of(String loader) {
        return switch (loader == null ? "" : loader.toLowerCase(Locale.ROOT)) {
            case "neoforge" -> new LoaderPresentation(
                    "Neo", "neoforge", "NeoForge",
                    "https://github.com/neoforged.png?size=128");
            case "fabric" -> new LoaderPresentation(
                    "Fab", "fabric", "Fabric",
                    "https://github.com/FabricMC.png?size=128");
            case "forge" -> new LoaderPresentation(
                    "For", "forge", "Forge",
                    "https://github.com/MinecraftForge.png?size=128");
            case "quilt" -> new LoaderPresentation(
                    "Qui", "quilt", "Quilt",
                    "https://github.com/QuiltMC.png?size=128");
            default -> new LoaderPresentation("MC", "minecraft", "Minecraft", null);
        };
    }
}
