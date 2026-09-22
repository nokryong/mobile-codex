package dev.mobilecodex.app.core;

import static dev.mobilecodex.app.core.Texts.t;
/** Revocable, process-local consent. Old queued work cannot run after stop/re-enable. */
public final class PhoneControlGate {
    private boolean enabled;
    private long generation;
    public synchronized void enable() { generation++; enabled = true; }
    public synchronized void stop() { enabled = false; generation++; }
    public synchronized boolean enabled() { return enabled; }
    public synchronized long ticket() {
        if (!enabled) throw new IllegalStateException(t("휴대폰 제어가 꺼져 있습니다. 설정 → 도구에서 직접 켜 주세요."));
        return generation;
    }
    public synchronized void check(long ticket) {
        if (!enabled || ticket != generation) throw new IllegalStateException(t("휴대폰 제어가 중지되었습니다. 이전 요청은 취소됩니다."));
    }
}
