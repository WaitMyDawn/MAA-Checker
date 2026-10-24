package yagen.waitmydawn.checker.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.checker.action.LoaderPreparation;
import yagen.waitmydawn.checker.core.*;
import java.nio.file.*;
import java.util.List;
import java.util.jar.*;

public final class LoaderFixture {
    private LoaderFixture() { }

    public static GameInstance game(Path root) throws Exception {
        GameInstance base = RepairTasksTest.fixture(root, "loader-repairable");
        try (var jar = new JarOutputStream(Files.newOutputStream(base.dir().resolve("fixture.jar")))) {
            for (Class<?> type : List.of(ProcessFixture.class, ProcessFixture.bootstraplauncher.class)) addClass(jar, type);
        }
        var mapper = new ObjectMapper();
        ObjectNode json = (ObjectNode) mapper.readTree(base.versionJson().toFile());
        json.put("mainClass", ProcessFixture.bootstraplauncher.class.getName());
        ((ArrayNode) json.path("arguments").path("game")).add("--fml.neoForgeVersion").add("21.1.1");
        ObjectNode library = mapper.createObjectNode().put("name", "test:loader:21.1.1");
        library.withObject("downloads").withObject("artifact").put("path", "test/loader/21.1.1/loader-21.1.1.jar");
        ((ArrayNode) json.path("libraries")).add(library);
        ArrayNode patches = json.putArray("patches");
        patches.addObject().put("id", "game").put("version", "1.21.1");
        patches.addObject().put("id", "neoforge").put("version", "21.1.1").putArray("libraries").add(library.deepCopy());
        mapper.writerWithDefaultPrettyPrinter().writeValue(base.versionJson().toFile(), json);
        RepairTasksTest.mod(root.resolve("libraries/test/loader/21.1.1/loader-21.1.1.jar"), "fixture_library");
        Path client = root.resolve("libraries/net/minecraft/client/1.21.1-20240808.144430");
        Files.createDirectories(client);
        for (String suffix : List.of("extra", "slim", "srg")) {
            Files.writeString(client.resolve("client-1.21.1-20240808.144430-" + suffix + ".jar"),
                    "fixture client " + suffix);
        }
        Files.writeString(root.resolve("launcher_profiles.json"), "{\"profiles\":{\"untouched\":{}}}");
        return InstanceScanner.scanOne(root, base.dir());
    }

    public static Path installer(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, InstallerFixture.class.getName());
        try (var jar = new JarOutputStream(Files.newOutputStream(file), manifest)) { addClass(jar, InstallerFixture.class); }
        return file;
    }

    private static void addClass(JarOutputStream jar, Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(resource));
        try (var input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new java.io.IOException("Missing fixture class");
            input.transferTo(jar);
        }
        jar.closeEntry();
    }

    public static JavaLocator.JavaHome javaHome() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new JavaLocator.JavaHome(Path.of(System.getProperty("java.home"), "bin", executable), 21, "fixture Java");
    }

    public static LoaderPreparation.Factory factory(Path installer, Path cache) {
        return (instance, action, context) -> LoaderPreparation.prepare(instance, "21.1.10", installer, javaHome(), cache, null, 10, context);
    }
}
