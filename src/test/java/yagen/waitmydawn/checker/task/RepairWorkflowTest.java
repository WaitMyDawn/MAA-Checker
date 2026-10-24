package yagen.waitmydawn.checker.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.checker.action.*;
import yagen.waitmydawn.checker.core.*;
import yagen.waitmydawn.checker.transaction.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RepairWorkflowTest {
    @TempDir Path temp;
    private Path output() { return temp.resolve("tool"); }
    private CheckerEngine.Options options() {
        var o = CheckerEngine.Options.defaults();
        return new CheckerEngine.Options("1G", 10, 0, 1, o.cascadeLimit(), false, true, 1, 0, "abort", null, null, false, false, null, o.notifier());
    }
    private <T> TaskResult<T> run(TaskService.Work<T> work) throws Exception {
        try (var tasks = new TaskService()) { return tasks.submit("fixture", work, null).completion().toCompletableFuture().get(120, TimeUnit.SECONDS); }
    }
    private RepairPolicy policy(RepairPolicy.Category category, RepairPolicy.Mode mode) { return new RepairPolicy(Map.of(category, mode), 4); }
    private TaskService.Work<RepairWorkflow.State> work(GameInstance i, RepairPolicy p, RepairWorkflow.State previous,
                                                       RepairTasks.Candidates candidates, RepairWorkflow.Decider decide) {
        return RepairWorkflow.run(i, output(), options(), p, previous, candidates, (inst, action, ctx) -> { throw new AssertionError("unexpected installer"); }, decide, s -> { });
    }
    private void quiet(GameInstance i) throws Exception {
        if (Files.exists(i.dir().resolve("logs/latest.log"))) Files.setLastModifiedTime(i.dir().resolve("logs/latest.log"), java.nio.file.attribute.FileTime.fromMillis(0));
    }

    @Test void 缺依赖拒绝发生在下载前且原轮可继续() throws Exception {
        var instance = RepairTasksTest.fixture(temp.resolve("game"), "installable");
        Path client = RepairTasksTest.mod(instance.modsDir().resolve("client.jar"), "client");
        var initial = RepairInputs.capture(instance);
        AtomicInteger downloads = new AtomicInteger();
        Path candidate = RepairTasksTest.mod(temp.resolve("candidate/dependency.jar"), "dependency");
        RepairTasks.Candidates resolver = (a, i, c) -> { downloads.incrementAndGet(); return List.of(new RepairTasks.Download(candidate, "fixture")); };
        var paused = run(work(instance, RepairPolicy.defaults(), null, resolver, d -> CompletableFuture.completedFuture(false)));
        assertEquals(TaskSnapshot.State.NEEDS_ATTENTION, paused.state(), paused.message());
        assertEquals(0, downloads.get()); assertEquals(initial, RepairInputs.capture(instance)); assertTrue(Files.exists(client));
        var disk = RepairWorkflow.load(instance, paused.value().id()); assertEquals(paused.value(), disk);
        quiet(instance);
        var resumed = run(work(instance, policy(RepairPolicy.Category.DEPENDENCY, RepairPolicy.Mode.AUTO), disk, resolver, d -> { fail("自动不应询问"); return null; }));
        assertEquals(TaskSnapshot.State.SUCCEEDED, resumed.state(), resumed.message());
        assertEquals(1, downloads.get()); assertEquals(1, resumed.value().rounds().size());
        assertEquals(initial, resumed.value().round().before().inputs());
        assertNotNull(resumed.value().round().report(), "启动通过时也必须关联报告目录");
        assertTrue(Files.isDirectory(Path.of(resumed.value().round().report())));
        assertTrue(resumed.value().round().events().stream().anyMatch(e -> e.status().equals("已拒绝")));
        assertTrue(resumed.value().round().events().stream().anyMatch(e -> e.committed() != null));
    }

    @Test void 空方案且无阻断原因时给出可执行下一步() throws Exception {
        var instance = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var empty = new RepairTasks.Plan(instance, RepairInputs.capture(instance), List.of(), List.of(),
                List.of(), 1, false, null);

        String reason = RepairWorkflow.unusablePlanReason(empty);

        assertTrue(reason.contains("没有具体阻断原因"), reason);
        assertTrue(reason.contains("不能把无动作当作修复成功"), reason);
        assertTrue(reason.contains("从本轮继续") && reason.contains("还原轮前状态"), reason);
    }

    @Test void 策略组合保存后重新读取保持且不影响其他实例() throws Exception {
        var first = RepairTasksTest.fixture(temp.resolve("one"), "repairable");
        var second = RepairTasksTest.fixture(temp.resolve("two"), "repairable");
        RepairPolicy expected = new RepairPolicy(Map.of(
                RepairPolicy.Category.DEPENDENCY, RepairPolicy.Mode.AUTO,
                RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.STOP,
                RepairPolicy.Category.OTHER, RepairPolicy.Mode.ASK), 7);

        assertEquals(TaskSnapshot.State.SUCCEEDED, run(RepairWorkflow.savePolicy(first, expected)).state());
        RepairWorkflow.PolicySnapshot reopened = RepairWorkflow.policySnapshot(first);
        assertTrue(reopened.saved());
        assertEquals(expected, reopened.policy());

        RepairWorkflow.PolicySnapshot untouched = RepairWorkflow.policySnapshot(second);
        assertFalse(untouched.saved());
        assertEquals(RepairPolicy.defaults(), untouched.policy());
        assertFalse(Files.exists(InstanceStorage.root(second, false).resolve("workflows/policy.json")));
    }

    @Test void 停止策略不下载且无候选的级联停用必须另行授权() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "installable");
        RepairTasksTest.mod(i.modsDir().resolve("client.jar"), "client");
        var initial = RepairInputs.capture(i);
        var stopped = run(work(i, policy(RepairPolicy.Category.DEPENDENCY, RepairPolicy.Mode.STOP), null,
                (a, in, c) -> { fail("停止不能下载"); return List.of(); }, d -> { fail("停止不能询问"); return null; }));
        assertEquals(TaskSnapshot.State.NEEDS_ATTENTION, stopped.state());
        quiet(i);
        AtomicReference<RepairPolicy.Category> requested = new AtomicReference<>();
        var fallback = run(work(i, policy(RepairPolicy.Category.DEPENDENCY, RepairPolicy.Mode.AUTO), stopped.value(),
                (a, in, c) -> List.of(), d -> { requested.set(d.category()); return CompletableFuture.completedFuture(false); }));
        assertEquals(TaskSnapshot.State.NEEDS_ATTENTION, fallback.state(), fallback.message());
        assertEquals(RepairPolicy.Category.CASCADE, requested.get()); assertEquals(initial, RepairInputs.capture(i));
    }

    @Test void 手工修改后从停止轮继续并可完整还原初态及标记() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        Path broken = RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        Files.writeString(i.dir().resolve("modpack.cfg"), "original marker");
        var initial = RepairInputs.capture(i);
        var paused = run(work(i, RepairPolicy.defaults(), null, (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(false))).value();
        Files.delete(broken); RepairTasksTest.mod(i.modsDir().resolve("manual.jar"), "manual");
        Files.writeString(i.dir().resolve("modpack.cfg"), "manual marker");
        quiet(i);
        var resumed = run(work(i, RepairPolicy.defaults(), paused, (a, in, c) -> List.of(), d -> { fail("已手动修复不应询问"); return null; }));
        assertEquals(TaskSnapshot.State.SUCCEEDED, resumed.state(), resumed.message()); assertEquals(1, resumed.value().rounds().size());
        var preview = run(RepairWorkflow.preview(i, output().resolve("state"), resumed.value(),
                paused.round().id())).value();
        assertEquals(3, preview.files().size()); quiet(i);
        var restored = run(RepairWorkflow.restore(i, output(), preview, s -> { }));
        assertEquals(TaskSnapshot.State.SUCCEEDED, restored.state(), restored.message());
        assertEquals(initial, RepairInputs.capture(i));
        var continued = run(work(i, policy(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.AUTO), restored.value(), (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(true)));
        assertEquals(TaskSnapshot.State.SUCCEEDED, continued.state(), continued.message());
        assertEquals(2, continued.value().rounds().size()); assertEquals(1, continued.value().round().number());
    }

    @Test void 两轮初态真实独立并可恢复到第二轮前() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "two-rounds");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        RepairTasksTest.mod(i.modsDir().resolve("second.jar"), "second");
        var result = run(work(i, policy(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.AUTO), null, (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(false)));
        assertEquals(TaskSnapshot.State.SUCCEEDED, result.state(), result.message());
        assertEquals(2, result.value().rounds().size());
        assertEquals(2, result.value().rounds().getFirst().before().mods().size()); assertEquals(1, result.value().round().before().mods().size());
        quiet(i);
        var preview = run(RepairWorkflow.preview(i, output().resolve("state"), result.value(),
                result.value().round().id())).value();
        var restore = run(RepairWorkflow.restore(i, output(), preview, s -> { }));
        assertEquals(TaskSnapshot.State.SUCCEEDED, restore.state(), restore.message());
        assertTrue(Files.exists(i.modsDir().resolve("second.jar"))); assertFalse(Files.exists(i.modsDir().resolve("broken.jar")));
        assertEquals(Set.of("broken"), TransactionSession.blockedMods(output().resolve("state"), i));
    }

    @Test void 等待决策时取消保留可重开的暂停状态() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        var before = RepairInputs.capture(i);
        CountDownLatch waiting = new CountDownLatch(1);
        try (var tasks = new TaskService()) {
            var handle = tasks.submit("fixture", work(i, RepairPolicy.defaults(), null, (a, in, c) -> List.of(), d -> { waiting.countDown(); return new CompletableFuture<>(); }), null);
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); handle.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
        }
        var disk = RepairWorkflow.history(i).getFirst();
        assertEquals(RepairWorkflow.Phase.PAUSED, disk.phase()); assertNull(disk.decision()); assertEquals(before, RepairInputs.capture(i));
        try (var lease = WorkflowLease.acquire(i)) { assertNotNull(lease); }
    }

    @Test void 等待决策期间其他服务不能获得同实例租约() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        CountDownLatch waiting = new CountDownLatch(1);
        try (var owner = new TaskService(); var contender = new TaskService()) {
            var handle = owner.submit("fixture", work(i, RepairPolicy.defaults(), null,
                    (a, in, c) -> List.of(), d -> {
                        waiting.countDown();
                        return new CompletableFuture<>();
                    }), null);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));

            var blocked = contender.submit("contender", context -> {
                try (var ignored = WorkflowLease.acquire(i)) {
                    throw new IOException("竞争任务意外取得实例租约");
                }
            }, null);
            TaskResult<Object> blockedResult = blocked.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(TaskSnapshot.State.FAILED, blockedResult.state(), blockedResult.message());
            assertTrue(blockedResult.message().contains("已有活动修改操作"), blockedResult.message());

            handle.cancel();
            assertEquals(TaskSnapshot.State.CANCELLED,
                    handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
        }
        try (var lease = WorkflowLease.acquire(i)) { assertNotNull(lease); }
    }

    @Test void 恢复预览后外部改动必须拒绝且不能影响其他实例() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var other = RepairTasksTest.fixture(temp.resolve("other"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        RepairTasksTest.mod(other.modsDir().resolve("broken.jar"), "broken");
        var otherBefore = RepairInputs.capture(other);
        var result = run(work(i, policy(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.AUTO), null, (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(false)));
        var preview = run(RepairWorkflow.preview(i, output().resolve("state"), result.value(),
                result.value().round().id())).value();
        RepairTasksTest.mod(i.modsDir().resolve("external.jar"), "external");
        var current = RepairInputs.capture(i);
        var restore = run(RepairWorkflow.restore(i, output(), preview, s -> { }));
        assertEquals(TaskSnapshot.State.FAILED, restore.state()); assertEquals(current, RepairInputs.capture(i)); assertEquals(otherBefore, RepairInputs.capture(other));
        assertFalse(Files.exists(i.modsDir().resolve(".maa-checker-work"))); assertFalse(Files.exists(i.gameRoot().resolve(".maa-checker-libraries.lock")));
    }

    @Test void 共享模组实例不可自动修复且快照对象去重() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        var first = RoundSnapshot.capture(i); var second = RoundSnapshot.capture(i); assertEquals(first, second);
        try (var paths = Files.list(i.dir().resolve(".maa-checker/objects"))) { assertEquals(2, paths.count()); }
        Path shared = Files.createDirectories(i.gameRoot().resolve("mods"));
        var wrong = new GameInstance(i.name(), i.dir(), i.versionJson(), i.mcVersion(), i.loader(), i.loaderVersion(), shared, 0, i.gameRoot());
        assertEquals(TaskSnapshot.State.FAILED, run(work(wrong, RepairPolicy.defaults(), null, (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(true))).state());
    }

    @Test void 快照恢复中断后按实际差异继续且凭据不可单独撤销() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        Files.writeString(i.dir().resolve("modpack.cfg"), "original marker");
        var initial = RepairInputs.capture(i);
        var fixed = run(work(i, policy(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.AUTO), null, (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(false))).value();
        quiet(i);
        var preview = run(RepairWorkflow.preview(i, output().resolve("state"), fixed,
                fixed.round().id())).value();
        AtomicReference<TaskService.Handle<RepairWorkflow.State>> owner = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        try (var tasks = new TaskService()) {
            var handle = tasks.submit("fixture", context -> { ready.await(); return RepairWorkflow.restore(i, output(), preview, s -> { }).run(context); }, event -> {
                if (event.kind() == TaskEvent.Kind.STATE && event.text().endsWith("：mod/broken.jar")) owner.get().cancel();
            });
            owner.set(handle); ready.countDown();
            assertEquals(TaskSnapshot.State.CANCELLED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
        }
        assertFalse(Files.exists(i.dir().resolve("modpack.cfg")),
                "恢复尚未完成时不得提前重新启用 HMCL 标记");
        assertFalse(Files.exists(i.modsDir().resolve("broken.jar")));
        var interrupted = RepairWorkflow.load(i, fixed.id()); assertEquals(RepairWorkflow.Phase.RESTORING, interrupted.phase());
        assertNotNull(interrupted.restoreReceipt());
        assertEquals(TaskSnapshot.State.FAILED, run(RepairTasks.restore(i, output(), interrupted.restoreReceipt())).state());
        var remaining = run(RepairWorkflow.preview(i, output().resolve("state"), interrupted,
                fixed.round().id())).value();
        assertEquals(List.of("mod/broken.jar", "marker"), remaining.files(),
                "剩余计划必须先补模组、最后恢复标记");
        var completed = run(RepairWorkflow.restore(i, output(), remaining, s -> { }));
        assertEquals(TaskSnapshot.State.SUCCEEDED, completed.state(), completed.message()); assertEquals(initial, RepairInputs.capture(i));
        assertEquals("original marker", Files.readString(i.dir().resolve("modpack.cfg")));
        assertEquals(2, completed.value().restoreReceipts().size());
    }

    @Test void 目标版本引用缺失实例运行库时预览拒绝且不改动实例() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        var current = RepairInputs.capture(i);
        String runtime = UUID.randomUUID().toString();
        String json = "{\"id\":\"fixture\",\"mainClass\":\"fixture\",\"maaCheckerRuntime\":\"" + runtime
                + "\",\"libraries\":[{\"downloads\":{\"artifact\":{\"path\":\"tenant/loader.jar\"}}}]}";
        Path stagedVersion = temp.resolve("target-version.json");
        Files.writeString(stagedVersion, json);
        String hash = RepairInputs.hash(stagedVersion);
        Path objects = InstanceStorage.root(i, true).resolve("objects");
        Files.createDirectories(objects);
        Files.copy(stagedVersion, objects.resolve(hash + ".json"));

        RepairInputs target = new RepairInputs(current.versionFile(), current.modsDirectory(),
                Map.of("version", hash));
        RoundSnapshot snapshot = new RoundSnapshot(target, i.mcVersion(), i.loader(), i.loaderVersion(), List.of());
        UUID workflow = UUID.randomUUID();
        UUID round = UUID.randomUUID();
        var state = new RepairWorkflow.State(1, workflow, InstanceIdentity.of(i), System.currentTimeMillis(),
                RepairPolicy.defaults(), 0, RepairWorkflow.Phase.PAUSED, "restore library guard",
                List.of(new RepairWorkflow.Round(round, 1, snapshot, RepairWorkflow.Phase.PAUSED,
                        "fixture", List.of(), List.of(), null)),
                null, null, null, List.of());
        RoundSnapshot.writeJournal(i, workflow + ".json", state);

        var preview = run(RepairWorkflow.preview(i, output().resolve("state"), state, round));

        assertEquals(TaskSnapshot.State.FAILED, preview.state(), preview.message());
        assertTrue(preview.message().contains("实例运行库"), preview.message());
        assertEquals(current, RepairInputs.capture(i), "预览失败不得改动实例");
    }

    @Test void 预览后轮次快照变化必须重新预览() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        var fixed = run(work(i, policy(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.AUTO), null,
                (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(false))).value();
        quiet(i);
        var preview = run(RepairWorkflow.preview(i, output().resolve("state"), fixed,
                fixed.round().id())).value();

        var disk = RepairWorkflow.load(i, fixed.id());
        var round = disk.round();
        var before = round.before();
        var hashes = new TreeMap<>(before.inputs().hashes());
        hashes.put("mod/extra.jar", "0".repeat(64));
        var changedInputs = new RepairInputs(before.inputs().versionFile(),
                before.inputs().modsDirectory(), hashes);
        var changedSnapshot = new RoundSnapshot(changedInputs, before.minecraft(), before.loader(),
                before.loaderVersion(), before.mods());
        var changedRound = new RepairWorkflow.Round(round.id(), round.number(), changedSnapshot,
                round.phase(), round.message(), round.events(), round.transactions(), round.report());
        var changed = new RepairWorkflow.State(disk.schema(), disk.id(), disk.instance(), disk.createdAt(),
                disk.policy(), disk.current(), disk.phase(), disk.message(), List.of(changedRound),
                disk.decision(), disk.restoreReceipt(), disk.restoreTarget(), disk.restoreReceipts());
        RoundSnapshot.writeJournal(i, disk.id() + ".json", changed);

        var result = run(RepairWorkflow.restore(i, output(), preview, s -> { }));
        assertEquals(TaskSnapshot.State.FAILED, result.state(), result.message());
        assertTrue(result.message().contains("恢复计划已变化"), result.message());
    }

    @Test void 已提交但未写入时间线的动作在重开时补齐且幂等() throws Exception {
        var i = RepairTasksTest.fixture(temp.resolve("game"), "repairable");
        RepairTasksTest.mod(i.modsDir().resolve("broken.jar"), "broken");
        var fixed = run(work(i, policy(RepairPolicy.Category.MOD_FAILURE, RepairPolicy.Mode.AUTO), null,
                (a, in, c) -> List.of(), d -> CompletableFuture.completedFuture(false))).value();
        quiet(i);

        var disk = RepairWorkflow.load(i, fixed.id());
        var round = disk.round();
        assertEquals(1, round.events().stream().filter(e -> e.committed() != null).count());
        var strippedRound = new RepairWorkflow.Round(round.id(), round.number(), round.before(),
                round.phase(), round.message(),
                round.events().stream().filter(e -> e.committed() == null).toList(),
                round.transactions(), round.report());
        var stripped = new RepairWorkflow.State(disk.schema(), disk.id(), disk.instance(), disk.createdAt(),
                disk.policy(), disk.current(), disk.phase(), disk.message(), List.of(strippedRound),
                disk.decision(), disk.restoreReceipt(), disk.restoreTarget(), disk.restoreReceipts());
        RoundSnapshot.writeJournal(i, disk.id() + ".json", stripped);
        var instanceBeforeReconcile = RepairInputs.capture(i);

        run(RepairWorkflow.preview(i, output().resolve("state"), stripped, round.id())).value();
        assertEquals(instanceBeforeReconcile, RepairInputs.capture(i),
                "对账只能补齐业务事件，不能重复执行文件动作");
        var reconciled = RepairWorkflow.load(i, fixed.id());
        var restoredEvents = reconciled.round().events().stream().filter(e -> e.committed() != null).toList();
        assertEquals(1, restoredEvents.size());
        assertEquals("已提交", restoredEvents.getFirst().status());

        run(RepairWorkflow.preview(i, output().resolve("state"), reconciled, round.id())).value();
        assertEquals(1, RepairWorkflow.load(i, fixed.id()).round().events().stream()
                .filter(e -> e.committed() != null).count(), "重复对账不得追加重复事件");
    }
}
