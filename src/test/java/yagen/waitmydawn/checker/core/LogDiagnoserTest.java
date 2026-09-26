package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.action.ActionKind;
import yagen.waitmydawn.checker.action.ActionPlan;
import yagen.waitmydawn.checker.action.ActionPlanner;
import yagen.waitmydawn.checker.action.PlannedAction;
import yagen.waitmydawn.checker.action.Tombstones;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断器单测：用的是 1.21.1 NeoForge 实测日志里<b>原样抄下来</b>的片段，
 * 不是编造的样例；改解析逻辑时这些用例就是回归网。
 */
class LogDiagnoserTest {

    /** 实测片段（铁魔法冒险之旅，NeoForge 21.1.231 跑不起来的那次） */
    private static final String REAL_BLOCK = """
            [22:04:53.465] [main/ERROR] [net.neoforged.fml.loading.ModSorter/LOADING]: Missing or unsupported mandatory dependencies:
            \tMod ID: 'neoforge', Requested by: 'supplementaries', Expected range: '[21.1.247,]', Actual version: '21.1.231'
            \tMod ID: 'neoforge', Requested by: 'jei', Expected range: '[21.1.238,)', Actual version: '21.1.231'
            \tMod ID: 'kubejs', Requested by: 'irons_spells_js', Expected range: '[2101.7.2-build.315,)', Actual version: '[MISSING]'
            \tMod ID: 'ars_nouveau', Requested by: 'ars_n_spells', Expected range: '[5.13,6.0)', Actual version: '[MISSING]'
            """;

    @Test
    void 依赖明细块解析出四元组并按类型分档(@TempDir Path tmp) throws Exception {
        Path fresh = write(tmp, REAL_BLOCK);
        GameInstance inst = instance(tmp, "neoforge", "21.1.231");
        Map<String, String> jarIndex = Map.of(
                "ars_n_spells", "ars_n_spells-1.0.jar",
                "irons_spells_js", "irons_spells_js-1.0.jar");

        Diagnosis d = LogDiagnoser.diagnose(inst, fresh, List.of(), jarIndex, 0L);

        assertEquals(4, d.issues().size(), "4 条依赖问题一条都不能丢");
        long loaderIssues = d.issues().stream()
                .filter(i -> i.kind() == Diagnosis.Kind.LOADER_TOO_OLD).count();
        long missing = d.issues().stream()
                .filter(i -> i.kind() == Diagnosis.Kind.MISSING_REQUIRED).count();
        assertEquals(2, loaderIssues, "两条 neoforge 版本不足 → 动作 A");
        assertEquals(2, missing, "两条缺前置 → 动作 B");

        Diagnosis.Issue kubejs = d.issues().stream()
                .filter(i -> "kubejs".equals(i.modId())).findFirst().orElseThrow();
        assertEquals("irons_spells_js", kubejs.requestedBy());
        assertEquals("irons_spells_js-1.0.jar", kubejs.jarFile(), "缺前置时给出'需要它的模组'的 jar");
        assertTrue(kubejs.suggestion().startsWith("动作B"), "缺前置应建议补装");
        assertTrue(kubejs.cascadable(), "缺前置是依赖型故障，可级联");

        Diagnosis.Issue neo = d.issues().stream()
                .filter(i -> i.kind() == Diagnosis.Kind.LOADER_TOO_OLD).findFirst().orElseThrow();
        assertTrue(neo.suggestion().startsWith("动作A"), "加载器版本不足应建议升级 loader");
        assertFalse(neo.cascadable(), "加载器问题不是'摘模组'能解决的");
    }

    @Test
    void 模组加载失败块能抓到FailureMessage与jar(@TempDir Path tmp) throws Exception {
        String text = """
                net.neoforged.fml.ModLoadingException: Mod loading failures have occurred
                Mod loading issue for: examplemod
                \tMod file: /mods/examplemod-2.0.jar
                \tFailure message: NoClassDefFoundError: com/example/MissingThing
                """;
        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"),
                write(tmp, text), List.of(), Map.of(), 0L);

        Diagnosis.Issue i = d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.MOD_LOAD_FAILURE).findFirst().orElseThrow();
        assertEquals("examplemod", i.modId());
        assertEquals("examplemod-2.0.jar", i.jarFile(), "应把日志里的路径收敛成文件名");
        assertTrue(i.detail().contains("MissingThing"), "失败原因要原样保留，不能只留个类型");
        assertFalse(i.cascadable(), "非依赖型故障不能级联");
    }

    @Test
    void Mixin失败只指认配置不自作主张判定元凶(@TempDir Path tmp) throws Exception {
        String text = """
                java.lang.RuntimeException: Mixin transformation of net.minecraft.world.level.Level failed
                Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError: Mixin level_tweaks.mixins.json:LevelMixin from mod somepack failed
                """;
        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"),
                write(tmp, text), List.of(), Map.of(), 0L);
        assertTrue(d.issues().stream().anyMatch(x -> x.kind() == Diagnosis.Kind.MIXIN_FAILURE),
                "Mixin 失败必须被识别为非依赖型故障");
        assertEquals(1, d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.MIXIN_FAILURE).count(), "同一条日志不应重复计数");
    }

    @Test
    void Mixin的WARN不能当成失败(@TempDir Path tmp) throws Exception {
        // 实测原文（alexscaves 正常启动时打的 WARN，早期版本把它当成了 MIXIN_FAILURE，还把 modId 解析成 "mod"）
        String text = """
                [169月2026 12:14:50.858] [pool-23-thread-1/WARN] [mixin/]: Mixin alexscaves.mixins.json:SwampHutPieceMixin from mod alexscaves has multiple constructors, <init>(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceType;IIIIIILnet/minecraft/core/Direction;)V was selected
                [169月2026 12:14:42.183] [main/WARN] [mixin/]: Error loading class: org/embeddedt/embeddium/impl/render/chunk/compile/pipeline/FluidRenderer (java.lang.ClassNotFoundException: org.embeddedt.embeddium.impl.render.chunk.compile.pipeline.FluidRenderer)
                """;
        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"),
                write(tmp, text), List.of(), Map.of(), 0L);
        assertFalse(d.issues().stream().anyMatch(x -> x.kind() == Diagnosis.Kind.MIXIN_FAILURE),
                "正常启动期会有大量 Mixin WARN，一条都不能算故障：" + d.issues());
    }

    @Test
    void ERROR级的Mixin失败能定位到modid(@TempDir Path tmp) throws Exception {
        String text = """
                [main/ERROR] [mixin/]: Mixin apply for mod badmod failed badmod.mixins.json:FooMixin from mod badmod -> org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException
                """;
        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"),
                write(tmp, text), List.of(), Map.of("badmod", "badmod.jar"), 0L);
        Diagnosis.Issue i = d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.MIXIN_FAILURE).findFirst().orElseThrow();
        assertEquals("badmod", i.modId());
        assertEquals("badmod.jar", i.jarFile());
    }

    @Test
    void 没有故障时不应凭空产出问题(@TempDir Path tmp) throws Exception {
        String text = "[22:00:00] [main/INFO] [minecraft/Minecraft]: Setting user: Steve\n";
        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"),
                write(tmp, text), List.of(), Map.of(), 0L);
        assertFalse(d.hasIssues(), "正常日志不能解析出故障");
    }

    @Test
    void 客户端stdout里的FML故障块必须能定位到模组(@TempDir Path tmp) throws Exception {
        // 实测原文（铁魔法冒险之旅：进主界面 1 秒后 iceandfire 在 FMLClientSetupEvent 里抛 CME）。
        // 关键点：这段明细只出现在客户端 stdout，latest.log 里没有 → 必须把 stdout 也一起解析。
        String latest = "[169月2026 12:17:53.000] [main/ERROR] [fml/]: Mod loading failures have occurred\n";
        Path fresh = write(tmp, latest);
        String stdout = """
                [12:17:53] [Render thread/ERROR] [fml/]: Mod loading failures have occurred
                -- Mod loading issue for: iceandfire --
                	Mod file: /D:/Minecraft/minecraft/.minecraft/versions/铁魔法冒险之旅/mods/iceandfire-2.1-beta.1.jar
                	Failure message: Ice And Fire Community Edition (iceandfire) encountered an error while dispatching the net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event
                		java.util.ConcurrentModificationException: null
                	Mod version: 2.1-beta.1
                """;
        Path outLog = tmp.resolve("client-stdout.log");
        Files.writeString(outLog, stdout, StandardCharsets.UTF_8);

        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.250"), fresh,
                java.util.List.of(outLog), Map.of("iceandfire", "iceandfire-2.1-beta.1.jar"), 0L);

        Diagnosis.Issue i = d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.MOD_LOAD_FAILURE).findFirst().orElseThrow();
        assertEquals("iceandfire", i.modId());
        assertEquals("iceandfire-2.1-beta.1.jar", i.jarFile());
        assertTrue(i.detail().contains("ConcurrentModificationException")
                        || i.detail().contains("FMLClientSetupEvent"),
                "失败原因要保留原文：" + i.detail());
        assertFalse(i.cascadable(), "非依赖型故障只能摘它自己");
    }

    private static Path write(Path dir, String text) throws Exception {
        Path f = dir.resolve("fresh-latest.log");
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return f;
    }

    // ==================================================================================
    // 归因渠道（L2/L3/L4）：日志里没有 FML 故障块、拿不到 modId 时怎么反查
    // 用例全部来自 2026-09-26 万象包的真实日志（panda-temple / preloading_tricks）
    // ==================================================================================

    @Test
    void Empty_pre_release要摘声明非法版本号的模组_而不是报信者(@TempDir Path tmp) throws Exception {
        // 造两个真 jar：① 声明了非法版本号的 panda-temple ② 只是"报信者"的 preloading_tricks
        Path mods = tmp.resolve("mods");
        Files.createDirectories(mods);
        writeJar(mods.resolve("panda-temple-V1-1.21+.jar"), Map.of(
                "META-INF/neoforge.mods.toml", """
                        modLoader = 'javafml'
                        loaderVersion = '[1,)'
                        mods = [
                        	{ modId = 'mr_panda_temple', version = '1-V1-1.21+', displayName = 'Panda Temple' },
                        ]
                        """,
                "fabric.mod.json", "{\"id\":\"mr_panda_temple\",\"version\":\"1-V1-1.21+\"}"));
        writeJar(mods.resolve("preloading-tricks-1.0.jar"), Map.of(
                "META-INF/mods.toml", "[[mods]]\nmodId = \"preloading_tricks\"\nversion = \"1.0\"\n"));

        // latest.log 切片：FML 的模组清单行（实测原文格式，制表符缩进）
        Path fresh = write(tmp, """
                [269月2026 10:46:58.221] [main/INFO] [net.neoforged.fml.loading.moddiscovery.ModDiscoverer/SCAN]: Found mod file "panda-temple-V1-1.21+.jar" [locator: {mods folder locator}]
                \t\tPanda Temple 1-V1-1.21+ (mr_panda_temple)
                \t\tPreloading Tricks 1.0 (preloading_tricks)
                """);
        // 客户端 stdout：真实报错——顶层是"报信者"，Caused by 才是根因
        Path stdout = tmp.resolve("client-stdout.log");
        Files.writeString(stdout, """
                net.lenni0451.reflect.exceptions.MethodInvocationException: Could not invoke method 'onSetupMods(java.util.List)' in class 'settingdust.preloading_tricks.neoforge.modlauncher.PreloadingTricksCallbacksInvoker'
                	at TRANSFORMER/preloading_tricks@1.0/settingdust.preloading_tricks.neoforge.modlauncher.PreloadingTricksCallbacksInvoker.onSetupMods(PreloadingTricksCallbacksInvoker.java:42)
                Caused by: java.lang.IllegalArgumentException: 1-V1-1.21+: Empty pre-release
                	at TRANSFORMER/neoforge@21.1.231/net.neoforged.fml.loading.Version.parse(Version.java:1)
                """, StandardCharsets.UTF_8);

        GameInstance inst = instance(tmp, "neoforge", "21.1.231");
        ModJarIndex.Meta meta = ModJarIndex.scanMeta(mods);
        Diagnosis d = LogDiagnoser.diagnose(inst, fresh, List.of(stdout), meta, 0L);

        List<Diagnosis.Issue> bad = d.issues().stream()
                .filter(i -> i.kind() == Diagnosis.Kind.BAD_VERSION_FORMAT).toList();
        assertEquals(1, bad.size(), "应产出一条版本号格式非法：" + d.issues());
        assertEquals("mr_panda_temple", bad.get(0).modId(), "肇事者是声明了 1-V1-1.21+ 的那个模组");
        assertEquals("panda-temple-V1-1.21+.jar", bad.get(0).jarFile());
        assertTrue(bad.get(0).actualVersion().contains("1-V1-1.21+"));
        assertFalse(bad.get(0).cascadable(), "版本号格式非法只摘它自己，不级联");

        assertFalse(d.issues().stream().anyMatch(i -> i.kind() == Diagnosis.Kind.STACK_ATTRIBUTED),
                "已由版本指纹定位时，绝不能再把报信者 preloading_tricks 摘掉：" + d.issues());
        assertTrue(d.attributions().stream().anyMatch(a -> a.contains("preloading_tricks")),
                "报信者要如实写进归因过程（解释为什么没怀疑它）：" + d.attributions());

        // 规划器层面的最终结果：摘 panda-temple，不动 preloading_tricks
        ActionPlan plan = ActionPlanner.plan("neoforge", d, StaticDepsScanner.scan(inst),
                meta.jarOf(), Tombstones.load(tmp.resolve("state")), 40);
        List<String> removed = plan.actions().stream()
                .filter(a -> a.kind() == ActionKind.REMOVE_MOD)
                .map(a -> a.jarFile() == null ? a.modId() : a.jarFile()).toList();
        assertTrue(removed.contains("panda-temple-V1-1.21+.jar"), "应当摘掉非法版本号那个 jar：" + removed);
        assertFalse(removed.stream().anyMatch(x -> x.contains("preloading")), "报信者不能被摘：" + removed);
    }

    @Test
    void Caused_by链里的from_mod能被指认出来_L2(@TempDir Path tmp) throws Exception {
        Path mods = tmp.resolve("mods");
        Files.createDirectories(mods);
        writeJar(mods.resolve("badmod-1.0.jar"), Map.of(
                "META-INF/mods.toml", "[[mods]]\nmodId = \"badmod\"\nversion = \"1.0\"\n"));
        Path fresh = write(tmp, """
                java.lang.RuntimeException: 崩了
                Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError: Mixin badmod.mixins.json:Foo from mod badmod failed
                """);

        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"), fresh, List.of(),
                ModJarIndex.scanMeta(mods), 0L);

        Diagnosis.Issue i = d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.CAUSED_BY_ATTRIBUTED).findFirst().orElseThrow();
        assertEquals("badmod", i.modId());
        assertEquals("badmod-1.0.jar", i.jarFile());
        assertFalse(i.cascadable(), "归因渠道产出的结论同样只摘自己");
    }

    @Test
    void 堆栈帧能反查出模组_但框架帧必须被过滤_L3(@TempDir Path tmp) throws Exception {
        Path mods = tmp.resolve("mods");
        Files.createDirectories(mods);
        writeJar(mods.resolve("legendary_monsters-1.0.jar"), Map.of(
                "META-INF/mods.toml", "[[mods]]\nmodId = \"legendary_monsters\"\nversion = \"1.0\"\n"));
        // 实测原文（铁魔法冒险之旅的崩溃报告）
        Path fresh = write(tmp, """
                	at TRANSFORMER/minecraft@1.21.1/net.minecraft.client.renderer.item.ItemProperties.register(ItemProperties.java:61)
                	at TRANSFORMER/legendary_monsters@1.21.1/net.miauczel.legendary_monsters.item.ModItemProperties.soulGreatSword(ModItemProperties.java:1)
                """);

        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"), fresh, List.of(),
                ModJarIndex.scanMeta(mods), 0L);

        Diagnosis.Issue i = d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.STACK_ATTRIBUTED).findFirst().orElseThrow();
        assertEquals("legendary_monsters", i.modId(), "minecraft 这类框架帧不能被当成模组");
    }

    @Test
    void LAYER_SERVICE形式的栈帧也能反查_模块名点号要换成下划线(@TempDir Path tmp) throws Exception {
        Path mods = tmp.resolve("mods");
        Files.createDirectories(mods);
        writeJar(mods.resolve("preloading-tricks-3.7.2.jar"), Map.of(
                "META-INF/mods.toml",
                "[[mods]]\nmodId = \"preloading_tricks\"\nversion = \"3.7.2\"\n"));
        // 实测原文（万象包：preloading_tricks 在 ModDiscoverer 阶段抛异常的那次）
        Path fresh = write(tmp, """
                	at LAYER SERVICE/preloading.tricks@3.7.2/settingdust.preloading_tricks.util.ListBackedModManager.removeIf(ListBackedModManager.java:42)
                	at MC-BOOTSTRAP/Reflect@1.6.3/net.lenni0451.reflect.Methods.invoke(Methods.java:86)
                """);

        Diagnosis d = LogDiagnoser.diagnose(instance(tmp, "neoforge", "21.1.231"), fresh, List.of(),
                ModJarIndex.scanMeta(mods), 0L);

        Diagnosis.Issue i = d.issues().stream()
                .filter(x -> x.kind() == Diagnosis.Kind.STACK_ATTRIBUTED).findFirst().orElseThrow();
        assertEquals("preloading_tricks", i.modId(), "模块名 preloading.tricks → modId preloading_tricks");
    }

    @Test
    void 崩溃链命中时堆栈候选只记录不摘_L2压制L3(@TempDir Path tmp) throws Exception {
        // 实测教训（万象包第 3 轮）：同一次崩溃里，崩溃链指向真凶 tensura_iron_spells，
        // 堆栈帧指向"报信者" aces_spell_utils；两条都摘 → 后者进墓碑 → 下一轮又连带摘掉
        // 两个依赖它的模组，一次低置信误判摘了 3 个。
        Path mods = tmp.resolve("mods");
        Files.createDirectories(mods);
        writeJar(mods.resolve("real_culprit-1.0.jar"), Map.of(
                "META-INF/mods.toml", "[[mods]]\nmodId = \"real_culprit\"\nversion = \"1.0\"\n"));
        writeJar(mods.resolve("bystander-1.0.jar"), Map.of(
                "META-INF/mods.toml", "[[mods]]\nmodId = \"bystander\"\nversion = \"1.0\"\n"));
        Path fresh = write(tmp, """
                java.lang.RuntimeException: 崩了
                Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError: Mixin x.mixins.json:Foo from mod real_culprit failed
                	at TRANSFORMER/bystander@1.0/com.example.Bystander.onInit(Bystander.java:1)
                """);

        GameInstance inst = instance(tmp, "neoforge", "21.1.231");
        ModJarIndex.Meta meta = ModJarIndex.scanMeta(mods);
        Diagnosis d = LogDiagnoser.diagnose(inst, fresh, List.of(), meta, 0L);

        assertEquals("real_culprit", d.issues().stream()
                        .filter(i -> i.kind() == Diagnosis.Kind.CAUSED_BY_ATTRIBUTED)
                        .findFirst().orElseThrow().modId(),
                "崩溃链（中置信）应当胜出");
        assertFalse(d.issues().stream().anyMatch(i -> i.kind() == Diagnosis.Kind.STACK_ATTRIBUTED),
                "已经由崩溃链定位到肇事模组时，堆栈候选不能变成动作：" + d.issues());
        assertTrue(d.attributions().stream().anyMatch(a -> a.contains("bystander")),
                "但要如实记进归因过程：" + d.attributions());

        ActionPlan plan = ActionPlanner.plan("neoforge", d, StaticDepsScanner.scan(inst),
                meta.jarOf(), Tombstones.load(tmp.resolve("state")), 40);
        List<String> removed = plan.actions().stream()
                .filter(a -> a.kind() == ActionKind.REMOVE_MOD).map(PlannedAction::modId).toList();
        assertEquals(List.of("real_culprit"), removed, "每轮只摘最可信的那一个：" + removed);
    }

    private static void writeJar(Path jar, Map<String, String> entries) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static GameInstance instance(Path dir, String loader, String loaderVersion) {
        return new GameInstance("test", dir, dir.resolve("test.json"), "1.21.1",
                loader, loaderVersion, dir.resolve("mods"), 0, dir);
    }
}
