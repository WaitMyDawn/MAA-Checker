package yagen.waitmydawn.checker.desktop.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreviewSessionTest {
    @Test
    void 停止后迟到的进度不能变成通过() {
        PreviewSession session = new PreviewSession();
        session.start();
        session.advance();
        session.cancel();
        for (int i = 0; i < 8; i++) session.advance();
        assertEquals(PreviewSession.State.CANCELLED, session.state());
        assertEquals(1, session.history().size());
        session.cancel();
        assertEquals(1, session.history().size());
    }

    @Test
    void 失败保留独立结果且允许重新执行() {
        PreviewSession session = new PreviewSession();
        session.start();
        session.fail();
        assertEquals(PreviewSession.State.FAILED, session.state());
        session.start();
        for (int i = 0; i < 5; i++) session.advance();
        assertEquals(PreviewSession.State.PASSED, session.state());
        assertEquals(2, session.history().size());
        assertThrows(UnsupportedOperationException.class, () -> session.history().clear());
    }

    @Test
    void 运行中禁止重复启动与重置() {
        PreviewSession session = new PreviewSession();
        session.start();
        assertThrows(IllegalStateException.class, session::start);
        assertThrows(IllegalStateException.class, session::reset);
        session.cancel();
        session.reset();
        assertEquals(PreviewSession.State.REVIEW, session.state());
        assertEquals(0, session.step());
    }
}
