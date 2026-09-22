package dev.mobilecodex.app.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneControlGateTest {
    @Test public void processStartsDisarmed() {
        PhoneControlGate gate = new PhoneControlGate();
        assertFalse(gate.enabled()); assertThrows(IllegalStateException.class, gate::ticket);
    }
    @Test public void stopThenReenableDoesNotAuthorizeAnOldQueuedAction() {
        PhoneControlGate gate = new PhoneControlGate(); gate.enable(); long queued = gate.ticket();
        gate.check(queued); gate.stop();
        assertThrows(IllegalStateException.class, () -> gate.check(queued));
        gate.enable(); assertThrows(IllegalStateException.class, () -> gate.check(queued));
        gate.check(gate.ticket());
    }
}
