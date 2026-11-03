# Mobile Codex diagnostic relay

This is an anonymous, explicitly consented diagnostic inbox for the fixed public
repository `nokryong/mobile-codex`. It does not fix the underlying initialize
timeout. No automatic upload runs on an error. The APK contains no server key.

## Deployment gate

**Not production-ready until the configured production endpoint passes live E2E.**
The app uses the verified production endpoint
`https://mobile-codex-diagnostic-relay.vercel.app`. Keep live reporting checks as
a release gate whenever server configuration or this contract changes.

Deploy this directory as a dedicated Vercel project using Node 22. The `api/`
files use the documented Web Request/Response function interface, without extra
runtime dependencies. Preserve standard TLS, platform deployment protection and
existing APK signer/update identity. Never put credentials into a build command,
APK, GitHub Actions output, source file, API response, or public issue.

The deployment requires these **server-only, sensitive production variables**:

* `REPORT_GITHUB_TOKEN`: a nokryong fine-grained token limited to this
  repository, **Issues read/write** and default **Metadata read**. No Contents,
  Actions, Administration or organization permission is needed. New creation,
  storage or access expansion requires separate action-time approval.
* `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`: a dedicated durable Redis
  database, with read/write access only to that database. Free plan, no automatic
  paid upgrade. A new integration/credential needs approval before provisioning.

Vercel Marketplace's `KV_REST_API_URL` and `KV_REST_API_TOKEN` are also supported.
A complete legacy pair takes precedence; otherwise the complete Marketplace REST
pair is used. `KV_URL`, `REDIS_URL` and read-only tokens are not used.

An inaccessible deployment or absent configuration fails with 503. It must never
fall back to in-memory state. `/api/health` verifies configuration and Redis
reachability; **it does not prove GitHub write permission**. Live E2E is required.
Set the APK endpoint only to the verified production `https://*.vercel.app` alias.

## Contract and privacy

1. Preview: native code freezes exactly 27 scalar fields from `DiagnosticReport`.
   No messages, exceptions, raw logs, files, account data, paths, model name,
   device model/serial, Android ID, installation ID or environment is read into it.
   Times are relative, rounded to 100 ms and capped at ten minutes. The report ID
   is a random per-error retry receipt, never an installation/device identifier.
   Schema 2 keeps those field names and uses `null` for uncollected elapsed time,
   process-alive state and transport counters. A measured zero stays `0` (and a
   measured dead process stays `false`). Timing/exit fields retain their existing
   `-1` unavailable sentinel. Schema 1 remains accepted with its original ranges
   and grouping keys; schema 2 groups separately. No operation/process identity
   used for matching measurements is serialized.
   Startup measurements are frozen with the failed launch exception and accepted
   only by the same engine action or notification. Successful startup, later
   failures and replacement processes cannot supply those measurements. A later
   disconnect reports only its current process state, without startup counters.
   **Rollout order:** the relay must support schema 2 before an APK containing
   this collector is released. The currently deployed schema-1-only relay will
   reject schema 2; do not fall back to fabricated zeros. This PR does not deploy
   the relay or release an APK.
2. Explicit Send: `POST /api/challenge`, JSON `{ "reportId": "<UUID-v4>" }`.
   Returns a random challenge, 16-bit SHA-256 work requirement, five-minute expiry.
3. `POST /api/reports`: the exact diagnostic fields plus `challengeId` and integer
   `proof`. `sha256(challengeId + ':' + proof)` must start with `0000`.
   Success returns a validated public issue URL and `grouped` flag. No app secret
   is used as anonymous client authentication. No CORS authorization is enabled.

Both requests require JSON and are limited to 4096 streamed bytes. Only flat
allow-listed scalar fields are accepted; duplicate keys, escaping, arbitrary text,
unknown keys, nested data and out-of-range values are rejected. The repository,
title format and body template are fixed on the server. Values remain untrusted
device observations, not verified facts or proof of a root cause.

The relay reads Vercel's overwritten `x-forwarded-for` header, normalizes network
prefixes (IPv4 /24, IPv6 /48) and HMACs them with a random daily Redis salt. Raw
addresses are not stored by this code or posted to GitHub. Salts expire in 48
hours, prefix buckets in at most 2 hours. The host still processes ordinary HTTP
connection metadata under its own retention policies. Do not redeploy this
adapter behind a proxy that forwards caller-controlled IP headers.

Fixed limits: 30 requests/network prefix/hour, 3 submissions/prefix/hour, 10 new
issues/day, 30 GitHub writes/day, at most 3 additional distinct samples per error
group/day, and 10,000 total requests/day. These are safety circuit breakers:
anonymous attackers can still exhaust them and temporarily deny reporting.
Platform abuse protection remains enabled; increasing limits needs a deliberate
review. Challenge issuance and global caps bound creation costs; proof of work
adds friction without an APK secret or permanent user fingerprint.

Redis contains counters, short-lived challenge/lock/receipt hashes and public
issue links, not report bodies. Challenges expire in 5 minutes; locks in 120
seconds; report receipts and sample markers in 7 days; group mapping in 30 days.
Public GitHub diagnostics remain public under repository retention and are not
removed by local cancellation. The app's latest report exists only in memory.

## Idempotency, failure and recovery

Report IDs are atomically bound to immutable payload hashes. Groups use durable
leases. A pending marker is saved **before** each GitHub POST. An unknown POST
result is looked up by marker before any follow-up; it is never blindly replayed.
Original issue bodies are never overwritten. Repeated samples return the real
existing issue link; distinct samples add bounded comments. Groups are separate
from issue #15 until evidence connects their cause.

When the remote write was uncertain and no marker can be found, the report stays
`report_pending` and retries only reconcile. A maintainer must inspect the fixed
repository for the generated `mcdiag-…` marker before manually recovering that
Redis entry. Never clear the pending state merely because a request timed out.
The scan is bounded to the newest 500 issues/comments; older unresolved states
need manual reconciliation. This favors preventing duplicate public posts.

If GitHub or Redis is unavailable, report submission fails without touching
engine/account/chat state. Cancellation interrupts network work and proof search;
an already-published report cannot be retracted by cancelling. Retry preserves
the preview's report ID. No raw upstream response or exception is shown/logged.

## Verification and rollout

Run `node --test tests/diagnostic-relay.test.cjs tests/reports-ui.test.cjs` from the
repo root, Android unit/lint/build checks, and `node tools/verify_reports_browser.cjs`.
Production Redis TTL/atomic behavior, production GitHub authentication and actual
Android transport still need live verification; in-memory test stores are mocks.

After approved provisioning: deploy backend, check health, run the single
synthetic E2E with `tools/verify_diagnostic_relay.mjs`, verify the actual issue and
repeat receipt returns the same link, close that clearly marked test issue, then
set the real APK endpoint, bump app version/code once, and run exact-head CI.
Publish the signed APK only after those gates. Update issue #15 only after the
server and release are live. This feature must not be described as fixing its
unknown cause. Roll back by redeploying the previous server source; preserve
Redis receipts to avoid replay. Never downgrade APK signatures or reset app data.

The live-check workflow also runs when a same-repository PR is marked ready for
review, allowing the single synthetic report check before merging/releasing.

References: [Vercel Functions](https://vercel.com/docs/functions/quickstart),
[trusted headers](https://vercel.com/docs/headers/request-headers),
[Redis REST](https://upstash.com/docs/redis/features/restapi).
