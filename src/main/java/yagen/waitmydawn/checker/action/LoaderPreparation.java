package yagen.waitmydawn.checker.action;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.net.*;
import yagen.waitmydawn.checker.task.*;
import yagen.waitmydawn.checker.transaction.LibraryPaths;
import yagen.waitmydawn.checker.transaction.WorkflowLease;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** installer 仅对独立准备目录运行，产物审核通过前不发布到真实游戏目录。 */
public final class LoaderPreparation {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Library(String relative, Path candidate, String hash, boolean alreadyPresent) { }
    public record Prepared(String version, Path versionJson, String versionHash, List<Library> libraries,
                           String installerHash, Path installerLog) {
        public Prepared { libraries = List.copyOf(libraries); }
        public long additions() { return libraries.stream().filter(lib -> !lib.alreadyPresent()).count(); }
    }
    @FunctionalInterface
    public interface Factory { Prepared prepare(GameInstance instance, PlannedAction action, TaskContext context) throws Exception; }
    private LoaderPreparation() { }

    public static Factory production(Path toolRoot, CheckerEngine.Options options) {
        return (instance, action, context) -> {
            try (var lease = WorkflowLease.acquire(instance)) {
                return prepareProduction(toolRoot, options, instance, action, context);
            }
        };
    }

    private static Prepared prepareProduction(Path toolRoot, CheckerEngine.Options options, GameInstance instance,
                                              PlannedAction action, TaskContext context) throws Exception {
        LoaderPatch.validateLayout(JSON.readTree(instance.versionJson().toFile()));
        context.phase(TaskSnapshot.Phase.DOWNLOADING, "正在查询 NeoForge 正式版与校验安装器");
        var manager = new NetworkManager(options.proxy(), null, true, options.allowMirrorFile(), context::log);
        Http.setProxy(manager.proxy());
        String target = NeoForgeVersions.latestSatisfying(instance.mcVersion(), action.target(), context::log);
        if (target == null) throw new IOException("找不到满足要求的 NeoForge 正式版");
        String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + target + "/neoforge-" + target + "-installer.jar";
        String expected = Http.getString(url + ".sha256", context::log).strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[a-f0-9]{64}")) throw new IOException("官方安装器 SHA-256 格式无效，停止准备");
        Path cache = InstanceStorage.directory(InstanceStorage.root(instance, true), "preparation", true);
        Path download = Files.createTempDirectory(cache, "installer-").resolve("neoforge-" + target + "-installer.jar");
        var outcome = manager.download(url, null, 0, download);
        context.checkpoint();
        if (!outcome.ok() || !RepairInputs.hash(download).equals(expected)) throw new IOException("安装器下载失败或官方摘要不符，未运行安装器");
        var javaHome = JavaLocator.find(InstanceLauncher.requiredJavaOf(instance), toolRoot);
        if (javaHome == null) throw new IOException("没有可运行安装器的 Java");
        return prepare(instance, target, download, javaHome, cache, manager.proxy(), 900, context);
    }

    /** 调用方必须先验证安装器来源与摘要；独立工作目录不构成操作系统沙箱。 */
    public static Prepared prepare(GameInstance instance, String version, Path installer, JavaLocator.JavaHome javaHome,
                                    Path cache, String proxy, int timeoutSeconds, TaskContext context) throws Exception {
        try (var lease = WorkflowLease.acquire(instance)) {
            return prepareLocked(instance, version, installer, javaHome, cache, proxy, timeoutSeconds, context);
        }
    }

    private static Prepared prepareLocked(GameInstance instance, String version, Path installer,
                                           JavaLocator.JavaHome javaHome, Path cache, String proxy,
                                           int timeoutSeconds, TaskContext context) throws Exception {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !instance.mcVersion().startsWith("1.")) throw new IOException("无效加载器版本");
        String[] mc = instance.mcVersion().split("\\.");
        if (mc.length != 3 || !version.startsWith(mc[1] + "." + mc[2] + ".")) throw new IOException("加载器版本不匹配 Minecraft");
        JsonNode original = JSON.readTree(instance.versionJson().toFile());
        LoaderPatch.validateLayout(original);
        String installerHash = RepairInputs.hash(installer);
        Files.createDirectories(cache);
        Path work = Files.createTempDirectory(cache, "prepare-").toRealPath();
        Path game = Files.createDirectory(work.resolve("game"));
            context.phase(TaskSnapshot.Phase.PREPARING_LOADER, "正在复制已有库到独立目录，实例尚未修改");
        Path shared = original.has("maaCheckerRuntime") ? InstanceStorage.libraries(instance, original.path("maaCheckerRuntime").asText(), false) : LibraryPaths.root(instance);
        Set<String> seed = referencedLibraries(original);
        if (Files.isDirectory(shared)) {
            for (Path file : jars(shared, context)) {
                context.checkpoint();
                String relative = shared.relativize(file).toString().replace('\\', '/');
                LibraryPaths.relative(relative);
                if (!seed.contains(relative)) continue;
                Path target = game.resolve("libraries").resolve(relative);
                Files.createDirectories(target.getParent());
                copy(file, target, RepairInputs.hash(file));
            }
        }
        Files.writeString(game.resolve("launcher_profiles.json"), "{\"profiles\":{}}");
        Path vanilla = Files.createDirectories(game.resolve("versions").resolve(instance.mcVersion()));
        String jarName = original.path("jar").asText(instance.versionJson().getFileName().toString().replaceFirst("\\.json$", ""));
        if (!jarName.matches("[^/\\\\:]+") || jarName.startsWith(".")) throw new IOException("无效客户端 JAR 名称");
        Path client = instance.dir().resolve(jarName + ".jar");
        if (Files.isRegularFile(client, LinkOption.NOFOLLOW_LINKS)) {
            copy(client, vanilla.resolve(instance.mcVersion() + ".jar"), RepairInputs.hash(client));
        }
        Path originalVanilla = instance.gameRoot().resolve("versions").resolve(instance.mcVersion()).resolve(instance.mcVersion() + ".json");
        if (Files.isRegularFile(originalVanilla, LinkOption.NOFOLLOW_LINKS)) {
            copy(originalVanilla, vanilla.resolve(instance.mcVersion() + ".json"), RepairInputs.hash(originalVanilla));
        }
        Path output = work.resolve("installer.log");
        context.log("安装器准备目录：" + game + "；日志：" + output);
        List<String> command = new ArrayList<>(List.of(javaHome.javaExe().toString(), "-Djava.net.preferIPv4Stack=true"));
        if (proxy != null) {
            URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            if (uri.getUserInfo() != null) throw new IOException("安装器暂不支持携带认证信息的代理");
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            command.addAll(List.of("-Dhttp.proxyHost=" + uri.getHost(), "-Dhttp.proxyPort=" + port,
                    "-Dhttps.proxyHost=" + uri.getHost(), "-Dhttps.proxyPort=" + port));
        }
        command.addAll(List.of("-jar", installer.toAbsolutePath().toString(), "--install-client", game.toString()));
        try (ProcessLog log = new ProcessLog(output); ProcessScope processes = new ProcessScope()) {
            Process process = processes.start(new ProcessBuilder(command).directory(game.toFile()).redirectErrorStream(true));
            log.attach(process);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            long nextMessage = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                context.checkpoint();
                if (System.nanoTime() >= deadline) throw new IOException("安装器准备超时，已停止；实例未修改，见 " + output);
                if (System.nanoTime() >= nextMessage) {
                    context.log("安装器仍在准备依赖，实例未修改；详情见 " + output);
                    nextMessage = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                }
            }
            if (process.exitValue() != 0) throw new IOException("安装器退出码 " + process.exitValue() + "，实例未修改，见 " + output);
        }
        context.checkpoint();
        if (!RepairInputs.hash(installer).equals(installerHash)) throw new IOException("安装器内容在运行期间发生变化");
        Path produced = game.resolve("versions/neoforge-" + version + "/neoforge-" + version + ".json");
        RepairInputs.hash(produced);
        JsonNode nf = JSON.readTree(produced.toFile());
        if (!nf.path("id").asText().equals("neoforge-" + version) || !nf.path("inheritsFrom").asText().equals(instance.mcVersion())) {
            throw new IOException("安装器产物的加载器或 Minecraft 身份不符");
        }
        for (JsonNode lib : nf.path("libraries")) {
            if (!InstanceLauncher.libraryApplies(lib)) continue;
            String relative = lib.path("downloads").path("artifact").path("path").asText();
            if (relative.isBlank()) {
                String[] coordinate = lib.path("name").asText().split(":");
                if (coordinate.length < 3 || coordinate.length > 4) throw new IOException("库缺少可解析的 Maven 坐标");
                relative = coordinate[0].replace('.', '/') + "/" + coordinate[1] + "/" + coordinate[2] + "/"
                        + coordinate[1] + "-" + coordinate[2] + (coordinate.length == 4 ? "-" + coordinate[3] : "") + ".jar";
                ((ObjectNode) lib).withObject("downloads").withObject("artifact").put("path", relative);
            }
            LibraryPaths.relative(relative);
            Path artifact = game.resolve("libraries").resolve(relative);
            RepairInputs.hash(artifact);
            long expectedSize = lib.path("downloads").path("artifact").path("size").asLong(-1);
            if (expectedSize >= 0 && Files.size(artifact) != expectedSize) throw new IOException("库大小与产物声明不符：" + relative);
            String sha1 = lib.path("downloads").path("artifact").path("sha1").asText();
            if (!sha1.isBlank() && !LoaderPatch.sha1(artifact).equalsIgnoreCase(sha1)) throw new IOException("库摘要与产物声明不符：" + relative);
        }
        Path candidate = work.resolve("candidate.json");
        var merged = LoaderPatch.compose(original, nf, version);
        JSON.writerWithDefaultPrettyPrinter().writeValue(candidate.toFile(), merged);
        List<Library> libraries = new ArrayList<>();
        Path preparedLibraries = game.resolve("libraries");
        if (!Files.isDirectory(preparedLibraries, LinkOption.NOFOLLOW_LINKS)) throw new IOException("安装器未产出依赖库");
        Set<String> required = referencedLibraries(merged);
        for (Path file : jars(preparedLibraries, context)) {
            context.checkpoint();
            String relative = preparedLibraries.relativize(file).toString().replace('\\', '/');
            LibraryPaths.relative(relative);
            if (!required.remove(relative)) continue;
            String hash = RepairInputs.hash(file);
            libraries.add(preparedLibrary(instance, relative, file, hash));
        }
        Set<String> selected = new LinkedHashSet<>();
        libraries.forEach(library -> selected.add(library.relative()));
        Path sharedLibraries = LibraryPaths.root(instance);
        for (String relative : LoaderRuntimeLayout.requiredFiles(merged)) {
            if (!selected.add(relative)) continue;
            Path source = preparedLibraries.resolve(relative);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) source = sharedLibraries.resolve(relative);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("加载器运行时缺少隐式产物：" + relative);
            }
            String hash = RepairInputs.hash(source);
            libraries.add(preparedLibrary(instance, relative, source, hash));
        }
        if (!required.isEmpty()) throw new IOException("准备目录缺少合并配置所需的库：" + String.join(", ", required));
        return new Prepared(version, candidate, RepairInputs.hash(candidate), libraries, installerHash, output);
    }

    private static Library preparedLibrary(GameInstance instance, String relative, Path source, String hash)
            throws IOException {
        Path target = LibraryPaths.target(instance, relative);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return new Library(relative, source, hash, false);
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !RepairInputs.hash(target).equals(hash)) {
            throw new IOException("共享库已有不同内容的同版本文件，拒绝覆盖：" + relative);
        }
        return new Library(relative, source, hash, true);
    }

    private static Set<String> referencedLibraries(JsonNode version) throws IOException {
        Set<String> paths = new TreeSet<>();
        for (JsonNode library : version.path("libraries")) {
            if (!InstanceLauncher.libraryApplies(library)) continue;
            String path = library.path("downloads").path("artifact").path("path").asText();
            if (!path.isBlank()) { LibraryPaths.relative(path); paths.add(path); }
        }
        return paths;
    }

    private static void copy(Path source, Path target, String expected) throws IOException {
        Files.copy(source, target);
        if (!RepairInputs.hash(target).equals(expected) || !RepairInputs.hash(source).equals(expected)) throw new IOException("输入复制期间发生变化");
    }

    private static List<Path> jars(Path root, TaskContext context) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                context.checkpoint();
                String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!PathSafety.isDirectory(directory) || name.startsWith(".env")
                        || name.equals("secrets") || name.equals("credentials")) throw new IOException("库目录包含链接或受保护路径");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                context.checkpoint();
                if (!PathSafety.isRegularFile(file)) throw new IOException("库文件包含链接");
                if (file.toString().endsWith(".jar")) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }
}
