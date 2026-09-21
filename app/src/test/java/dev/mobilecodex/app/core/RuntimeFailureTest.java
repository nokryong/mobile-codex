package dev.mobilecodex.app.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class RuntimeFailureTest {
    @Test public void onlyKnownStderrPatternsProduceSafeLabels() {
        assertEquals("", RuntimeFailure.classify("oauth token=definitely-not-a-real-token"));
        assertEquals("네이티브 종속성 오류", RuntimeFailure.classify("CANNOT LINK EXECUTABLE: cannot locate symbol"));
        assertEquals("런타임 내부 오류", RuntimeFailure.classify("thread 'main' panicked at runtime"));
        assertEquals("메모리 부족", RuntimeFailure.classify("memory allocation failed"));
        assertEquals("설정 형식 오류", RuntimeFailure.classify("failed to parse config.toml"));
    }
    @Test public void tailRetainsOnlyTheLastBoundedBytes() {
        RuntimeFailure.Tail tail = new RuntimeFailure.Tail();
        tail.append(new byte[RuntimeFailure.MAX_TAIL_BYTES + 32], RuntimeFailure.MAX_TAIL_BYTES + 32);
        assertEquals(RuntimeFailure.MAX_TAIL_BYTES, tail.bufferedBytesForTest());
        assertEquals("", tail.diagnosis());
    }
}
