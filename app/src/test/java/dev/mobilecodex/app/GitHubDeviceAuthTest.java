package dev.mobilecodex.app;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class GitHubDeviceAuthTest {
    private static final class Clock implements java.util.function.LongSupplier {
        long value;
        @Override public long getAsLong() { return value; }
        void advance(long milliseconds) { value += milliseconds; }
    }

    private static final class FakeTransport implements GitHubDeviceAuth.Transport {
        final ArrayDeque<JSONObject> responses = new ArrayDeque<>();
        final List<String> endpoints = new ArrayList<>();
        final List<JSONObject> bodies = new ArrayList<>();
        @Override public JSONObject request(String endpoint, JSONObject body) throws Exception {
            endpoints.add(endpoint); bodies.add(new JSONObject(body.toString()));
            if (responses.isEmpty()) throw new IOException("unexpected request");
            return responses.removeFirst();
        }
    }

    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static JSONObject startReply() {
        return object("device_code", "private-device-code", "user_code", "ABCD-EFGH", "verification_uri", "https://github.com/login/device", "expires_in", 900, "interval", 5);
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final FakeTransport transport = new FakeTransport();
        final GitHubDeviceAuth auth = new GitHubDeviceAuth("Iv1.test_client", transport, clock);
        JSONObject start() throws Exception { transport.responses.add(startReply()); return auth.start(); }
        GitHubDeviceAuth.Result poll(JSONObject start) throws Exception { return auth.poll(start.getString("flowId")); }
        void ready() { clock.advance(5_000); }
    }

    @Test public void doesNotPollBeforeIntervalAndKeepsPrivateCodeOutOfPublicState() throws Exception {
        Fixture fixture = new Fixture(); JSONObject state = fixture.start();
        assertFalse(state.has("device_code")); assertFalse(state.toString().contains("private-device-code"));
        assertEquals("repo", fixture.transport.bodies.get(0).getString("scope"));
        assertThrows(IOException.class, () -> fixture.poll(state));
        assertEquals(1, fixture.transport.endpoints.size());
        fixture.ready(); fixture.transport.responses.add(object("error", "authorization_pending"));
        GitHubDeviceAuth.Result pending = fixture.poll(state);
        assertTrue(pending.pending.getBoolean("pending")); assertEquals(5, pending.pending.getLong("interval"));
        assertNull(pending.accessToken); assertThrows(IOException.class, () -> fixture.poll(state));
        assertEquals(2, fixture.transport.endpoints.size());
    }

    @Test public void slowDownAddsFiveSecondsBeforeAnotherRequest() throws Exception {
        Fixture fixture = new Fixture(); JSONObject state = fixture.start(); fixture.ready();
        fixture.transport.responses.add(object("error", "slow_down"));
        assertEquals(10, fixture.poll(state).pending.getLong("interval"));
        fixture.clock.advance(9_999); assertThrows(IOException.class, () -> fixture.poll(state));
        fixture.clock.advance(1); fixture.transport.responses.add(object("error", "authorization_pending"));
        assertEquals(10, fixture.poll(state).pending.getLong("interval"));
    }

    @Test public void returnsBearerRepoTokenAndOptionalExpiryOnly() throws Exception {
        Fixture fixture = new Fixture(); JSONObject state = fixture.start(); fixture.ready();
        fixture.transport.responses.add(object("access_token", "gho_token", "token_type", "Bearer", "scope", "gist, repo", "expires_in", 60, "refresh_token", "ghr_hidden"));
        GitHubDeviceAuth.Result result = fixture.poll(state);
        assertEquals("gho_token", result.accessToken); assertNull(result.pending); assertEquals(65_000, result.expiresAt);
        JSONObject exchange = fixture.transport.bodies.get(1);
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", exchange.getString("grant_type"));
        assertFalse(exchange.has("client_secret"));
        assertThrows(IOException.class, () -> fixture.poll(state));

        Fixture nonExpiring = new Fixture(); JSONObject nonExpiringState = nonExpiring.start(); nonExpiring.ready();
        nonExpiring.transport.responses.add(object("access_token", "gho_token", "token_type", "bearer", "scope", "repo"));
        assertEquals(0, nonExpiring.poll(nonExpiringState).expiresAt);
    }

    @Test public void cancelAndReplacementRejectStaleFlowWithoutPolling() throws Exception {
        Fixture fixture = new Fixture(); JSONObject first = fixture.start(); JSONObject second = fixture.start();
        fixture.ready(); assertThrows(IOException.class, () -> fixture.poll(first));
        assertEquals(2, fixture.transport.endpoints.size());
        fixture.auth.cancel(second.getString("flowId"));
        assertThrows(IOException.class, () -> fixture.poll(second));
        assertEquals(2, fixture.transport.endpoints.size());
    }

    @Test public void expiryAndDenialClearTheAttempt() throws Exception {
        Fixture expired = new Fixture(); expired.transport.responses.add(object("device_code", "private", "user_code", "ABCD-EFGH", "verification_uri", "https://github.com/login/device", "expires_in", 5, "interval", 1));
        JSONObject expiredState = expired.auth.start(); expired.clock.advance(5_000);
        assertThrows(IOException.class, () -> expired.poll(expiredState)); assertEquals(1, expired.transport.endpoints.size());

        Fixture denied = new Fixture(); JSONObject deniedState = denied.start(); denied.ready(); denied.transport.responses.add(object("error", "access_denied", "error_description", "do not expose this"));
        assertThrows(IOException.class, () -> denied.poll(deniedState)); assertThrows(IOException.class, () -> denied.poll(deniedState));
        assertEquals(2, denied.transport.endpoints.size());
    }

    @Test public void rejectsInvalidClientAndMaliciousVerificationUrlWithoutLeakingResponseText() throws Exception {
        FakeTransport invalidClientTransport = new FakeTransport();
        GitHubDeviceAuth invalidClient = new GitHubDeviceAuth("bad!", invalidClientTransport, () -> 0L);
        IOException clientError = assertThrows(IOException.class, invalidClient::start);
        assertFalse(clientError.getMessage().contains("bad!")); assertTrue(invalidClientTransport.endpoints.isEmpty());

        Fixture rejectedClient = new Fixture(); rejectedClient.transport.responses.add(object("error", "incorrect_client_credentials", "error_description", "private client failure"));
        IOException rejectedClientError = assertThrows(IOException.class, rejectedClient.auth::start);
        assertFalse(rejectedClientError.getMessage().contains("incorrect_client_credentials"));
        assertFalse(rejectedClientError.getMessage().contains("private client failure"));

        Fixture malicious = new Fixture(); malicious.transport.responses.add(object("device_code", "private", "user_code", "ABCD-EFGH", "verification_uri", "https://evil.example/login/device", "expires_in", 900, "interval", 5));
        IOException maliciousError = assertThrows(IOException.class, malicious.auth::start);
        assertFalse(maliciousError.getMessage().contains("evil.example"));

        Fixture unknown = new Fixture(); JSONObject state = unknown.start(); unknown.ready(); unknown.transport.responses.add(object("error", "unexpected_error", "error_description", "gho_sensitive"));
        IOException unknownError = assertThrows(IOException.class, () -> unknown.poll(state));
        assertFalse(unknownError.getMessage().contains("unexpected_error")); assertFalse(unknownError.getMessage().contains("gho_sensitive"));
    }

    @Test public void rejectsFractionalIntervalsAndControlCharactersInCredentials() throws Exception {
        Fixture fractional = new Fixture();
        fractional.transport.responses.add(object("device_code", "private", "user_code", "ABCD-EFGH", "verification_uri", "https://github.com/login/device", "expires_in", 900.5, "interval", 5));
        assertThrows(IOException.class, fractional.auth::start);

        Fixture fractionalInterval = new Fixture();
        fractionalInterval.transport.responses.add(object("device_code", "private", "user_code", "ABCD-EFGH", "verification_uri", "https://github.com/login/device", "expires_in", 900, "interval", 5.5));
        assertThrows(IOException.class, fractionalInterval.auth::start);

        Fixture controlDeviceCode = new Fixture();
        controlDeviceCode.transport.responses.add(object("device_code", "private\ncode", "user_code", "ABCD-EFGH", "verification_uri", "https://github.com/login/device", "expires_in", 900, "interval", 5));
        assertThrows(IOException.class, controlDeviceCode.auth::start);

        Fixture controlUserCode = new Fixture();
        controlUserCode.transport.responses.add(object("device_code", "private", "user_code", "ABCD\tEFGH", "verification_uri", "https://github.com/login/device", "expires_in", 900, "interval", 5));
        assertThrows(IOException.class, controlUserCode.auth::start);

        Fixture controlToken = new Fixture(); JSONObject state = controlToken.start(); controlToken.ready();
        controlToken.transport.responses.add(object("access_token", "gho_bad\nheader", "token_type", "bearer", "scope", "repo"));
        assertThrows(IOException.class, () -> controlToken.poll(state));
    }
}
