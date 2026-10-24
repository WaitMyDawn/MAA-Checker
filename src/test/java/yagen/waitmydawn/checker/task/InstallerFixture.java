package yagen.waitmydawn.checker.task;

import java.nio.file.*;
import java.util.jar.*;

/** 生成可控 installer 产物，覆盖隔离、错误输出和取消，不访问网络。 */
public final class InstallerFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        publishPid(root.resolve("fixture.pid"));
        String mode = Path.of(InstallerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
        if (mode.startsWith("hang")) { Thread.sleep(60_000); return; }
        if (mode.startsWith("exit")) System.exit(7);
        if (mode.startsWith("missing")) return;
        String relative = "test/loader/21.1.10/loader-21.1.10.jar";
        Path jar = root.resolve("libraries").resolve(relative);
        Files.createDirectories(jar.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture.txt"));
            output.write("fixture loader library".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path implicit = root.resolve("libraries/net/neoforged/neoforge/21.1.10");
        Files.createDirectories(implicit);
        Files.writeString(implicit.resolve("neoforge-21.1.10-client.jar"), "fixture neoforge client");
        Files.writeString(implicit.resolve("neoforge-21.1.10-universal.jar"), "fixture neoforge universal");
        if (mode.startsWith("escape")) relative = "../../outside.jar";
        String mc = mode.startsWith("wrong-mc") ? "1.20.4" : "1.21.1";
        String sha = mode.startsWith("wrong-hash") ? ",\"sha1\":\"0000000000000000000000000000000000000000\"" : "";
        Path version = Files.createDirectories(root.resolve("versions/neoforge-21.1.10")).resolve("neoforge-21.1.10.json");
        Files.writeString(version, "{\"id\":\"neoforge-21.1.10\",\"inheritsFrom\":\"" + mc + "\","
                + "\"mainClass\":\"yagen.waitmydawn.checker.task.ProcessFixture$bootstraplauncher\","
                + "\"arguments\":{\"jvm\":[\"--add-modules\",\"java.base\"],"
                + "\"game\":[\"--fml.neoForgeVersion\",\"21.1.10\",\"--fml.neoFormVersion\",\"20240808.144430\","
                + "\"--fml.mcVersion\",\"1.21.1\",\"--launchTarget\",\"forgeclient\"]},"
                + "\"libraries\":[{\"name\":\"test:loader:21.1.10\",\"downloads\":{\"artifact\":{\"path\":\"" + relative
                + "\",\"size\":" + Files.size(jar) + sha + "}}}]}");
        Files.writeString(root.resolve("launcher_profiles.json"), "{\"profiles\":{\"fixture\":{}}}");
        System.out.println("fixture installer finished");
    }

    /**
     * The test observes pid-file existence as the installer handshake. Publish a complete sibling
     * first so the visible target never exposes a partial numeric value.
     */
    private static void publishPid(Path target) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".publishing-" + ProcessHandle.current().pid());
        Files.writeString(temporary, Long.toString(ProcessHandle.current().pid()));
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
