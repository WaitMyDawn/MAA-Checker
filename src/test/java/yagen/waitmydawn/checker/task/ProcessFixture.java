package yagen.waitmydawn.checker.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 生命周期测试专用 Java 子进程，不加载游戏或访问网络。 */
public final class ProcessFixture {
    /** 名称保留启动器识别标记，使测试版本沿用真实 NeoForge 识别路径。 */
    public static final class bootstraplauncher {
        public static void main(String[] args) throws Exception { ProcessFixture.main(args); }
    }
    public static List<String> command(String mode, Path signal) {
        String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", exe).toString(), "-Xmx32m", "-cp",
                System.getProperty("java.class.path"), ProcessFixture.class.getName(), mode, signal.toString());
    }

    public static void main(String[] args) throws Exception {
        Path signal = Path.of(args[1]);
        switch (args[0]) {
            case "loader-repairable" -> {
                Path version = signal.getParent().getParent().resolve("fixture.json");
                String json = Files.readString(version).replaceAll("\\s+", "");
                publish(signal, json.contains("\"version\":\"21.1.10\"") ? "Sound engine started\n"
                        : "Missing or unsupported mandatory dependencies:\nMod ID: 'neoforge', Requested by: 'client', Expected range: '[21.1.10,)', Actual version: '21.1.1'\n");
                Thread.sleep(60_000);
            }
            case "exit" -> System.exit(7);
            case "marker-exit" -> {
                publish(signal, "Sound engine started\n");
                System.exit(0);
            }
            case "marker-live" -> {
                System.out.println("fixture stdout captured");
                publish(signal, "Sound engine started\n");
                Thread.sleep(60_000);
            }
            case "diagnostic-live" -> {
                publish(signal, "Missing or unsupported mandatory dependencies:\n"
                        + "Mod ID: 'relics', Requested by: 'reliquified', Expected range: '[0.12.3,)', Actual version: '[MISSING]'\n");
                Thread.sleep(60_000);
            }
            case "repairable", "installable", "two-rounds" -> {
                Path mods = signal.getParent().getParent().resolve("mods");
                String log;
                if ((args[0].equals("repairable") || args[0].equals("two-rounds")) && Files.exists(mods.resolve("broken.jar"))) {
                    log = "Mod loading failures have occurred\nMod loading issue for: broken\n"
                            + "Mod file: /mods/broken.jar\nFailure message: fixture initialization failure\n";
                } else if (args[0].equals("two-rounds") && Files.exists(mods.resolve("second.jar"))) {
                    log = "Mod loading failures have occurred\nMod loading issue for: second\n"
                            + "Mod file: /mods/second.jar\nFailure message: fixture second failure\n";
                } else if (args[0].equals("installable") && !Files.exists(mods.resolve("dependency.jar"))) {
                    log = "Missing or unsupported mandatory dependencies:\n"
                            + "Mod ID: 'dependency', Requested by: 'client', Expected range: '[1,)', Actual version: '[MISSING]'\n";
                } else log = "Sound engine started\n";
                publish(signal, log);
                Thread.sleep(60_000);
            }
            case "tree" -> {
                Process child = new ProcessBuilder(command("sleep", signal)).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                try { child.waitFor(); }
                finally { if (child.isAlive()) child.destroyForcibly(); }
            }
            case "tree-release" -> {
                new ProcessBuilder(command("sleep", signal)).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                while (!Files.exists(signal.resolveSibling("release"))) Thread.sleep(10);
            }
            default -> {
                publish(signal, Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60_000);
            }
        }
    }

    /**
     * Publishes a fixture signal so that observing the target path implies its complete content.
     *
     * <p>Tests use {@code Files.exists(signal)} as the readiness condition. Writing directly to the
     * target exposes a window in which the file exists but is still empty or partially written,
     * which can yield intermittent empty reads. A sibling temporary file renamed into place keeps
     * the target invisible until its content is complete.
     */
    private static void publish(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".publishing-" + ProcessHandle.current().pid());
        Files.writeString(temporary, content);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
