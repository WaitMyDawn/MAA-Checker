package yagen.waitmydawn.checker.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "轮次用完还没修好"时的三种选择。
 *
 * <p>为什么要问而不是直接结束：轮次上限只是默认值（默认 4），不是天花板；但也不能因为问了
 * 就把无人值守的命令行/CI 挂死——所以没有 asker 时必须自动按"停止（保留改动）"处理。
 */
class RoundAskerTest {

    @TempDir
    Path tmp;

    @Test
    void 没有asker时按停止保留改动处理_绝不挂住无人值守流程() throws Exception {
        RunJournal journal = RunJournal.create(tmp.resolve("tool"), "测试包", null);

        RoundAsker.Decision d = CheckerEngine.askWhenRoundsExhausted(null, journal, inst(), 4, 4);

        assertEquals(RoundAsker.Choice.STOP_KEEP, d.choice());
    }

    @Test
    void 用户选择追加轮次时按追加轮数返回() throws Exception {
        RunJournal journal = RunJournal.create(tmp.resolve("tool"), "测试包", null);
        AtomicReference<RoundAsker.Ask> seen = new AtomicReference<>();
        RoundAsker asker = ask -> {
            seen.set(ask);
            return RoundAsker.Decision.extend(3);
        };

        RoundAsker.Decision d = CheckerEngine.askWhenRoundsExhausted(asker, journal, inst(), 4, 4);

        assertEquals(RoundAsker.Choice.EXTEND, d.choice());
        assertEquals(3, d.extraRounds());
        assertTrue(seen.get().canExtend(), "还没到总上限时应允许追加");
        assertEquals(4, seen.get().doneRounds());
        assertEquals(4, seen.get().maxRounds());
    }

    @Test
    void 到达总轮次上限时不再提供追加() throws Exception {
        RunJournal journal = RunJournal.create(tmp.resolve("tool"), "测试包", null);
        AtomicReference<RoundAsker.Ask> seen = new AtomicReference<>();
        RoundAsker asker = ask -> {
            seen.set(ask);
            return RoundAsker.Decision.stopKeep();
        };

        CheckerEngine.askWhenRoundsExhausted(asker, journal, inst(),
                CheckerEngine.MAX_TOTAL_FIX_ROUNDS, CheckerEngine.MAX_TOTAL_FIX_ROUNDS);

        assertFalse(seen.get().canExtend(), "到了总上限只能停止或还原");
    }

    @Test
    void 轮次上限夹在1到20之间() {
        CheckerEngine.Options o = CheckerEngine.Options.defaults();
        assertEquals(CheckerEngine.DEFAULT_FIX_ROUNDS, o.rounds());
        assertEquals(1, o.withRounds(0).rounds());
        assertEquals(CheckerEngine.MAX_TOTAL_FIX_ROUNDS, o.withRounds(999).rounds());
    }

    private GameInstance inst() {
        return new GameInstance("测试包", tmp.resolve("inst"), tmp.resolve("v.json"), "1.21.1",
                "neoforge", "21.1.231", tmp.resolve("mods"), 80, tmp);
    }
}
