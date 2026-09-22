package dev.mobilecodex.app.core;

import static dev.mobilecodex.app.core.Texts.t;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Recognizes only a small allow-list of runtime diagnostics; raw stderr never leaves memory. */
public final class RuntimeFailure {
    private RuntimeFailure() {}
    public static final int MAX_TAIL_BYTES = 1024;

    public static String classify(String stderrTail) {
        String value = stderrTail == null ? "" : stderrTail.toLowerCase(Locale.ROOT);
        if (value.contains("cannot link executable") || value.contains("cannot locate symbol")) return t("네이티브 종속성 오류");
        if (value.contains("panicked at") || value.contains("thread 'main' panicked")) return t("런타임 내부 오류");
        if (value.contains("out of memory") || value.contains("outofmemory") || value.contains("memory allocation")) return t("메모리 부족");
        if (value.contains("config parse") || value.contains("failed to parse config") || value.contains("toml parse")) return t("설정 형식 오류");
        return "";
    }

    /** Bounded, in-memory-only stderr tail. It is classified but never logged, persisted, or exposed. */
    public static final class Tail {
        private byte[] value = new byte[0];
        public synchronized void append(byte[] bytes, int count) {
            if (count <= 0) return;
            int take = Math.min(count, MAX_TAIL_BYTES);
            int offset = count - take;
            int retained = Math.min(value.length, MAX_TAIL_BYTES - take);
            byte[] next = new byte[retained + take];
            if (retained > 0) System.arraycopy(value, value.length - retained, next, 0, retained);
            System.arraycopy(bytes, offset, next, retained, take);
            value = next;
        }
        public synchronized String diagnosis() { return classify(new String(value, StandardCharsets.UTF_8)); }
        int bufferedBytesForTest() { return value.length; }
    }
}
