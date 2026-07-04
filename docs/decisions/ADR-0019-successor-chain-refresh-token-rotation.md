# ADR-0019: Successor-chain refresh-token rotation

- Status: Accepted
- Date: 2026-07-04

## Context

[ADR-0010](ADR-0010-backend-session-tokens-for-native-clients.md) gave native
clients a backend-issued session: a short-lived HS256 access token plus an
opaque, single-use refresh token that **rotates on every use**. Rotation with
reuse-detection is the standard defense against a stolen refresh token — if a
retired token is presented again, the server assumes theft and burns the whole
token family.

The problem is that a mobile client legitimately re-presents a retired token all
the time. When a `/api/auth/refresh` **response is lost in flight** (tunnel,
backgrounded process, dropped connection), the server has already rotated the
token, but the client never received the successor — so it retries with the only
token it still holds: the old one. To a naive reuse-detector this is
indistinguishable from theft.

The first mitigation (`app.session.reuse-grace`, 30 s) honoured a replay only
within a short time window after rotation. It fixed the common case but not the
one users actually hit: the phone goes offline, and the retry lands **minutes or
hours later** — well past 30 s — so the family burns and the user is forced back
to interactive Google sign-in. Reports of "I keep having to log in again" traced
directly to this. Widening the window only moves the cliff; any time-based
window is the wrong axis for an offline-first client.

## Decision

Replace the time window with a **successor chain**. Each token, when rotated,
stores a pointer (`replacedBy`) to the freshly-minted successor it was rotated
into. A logout or theft-burn revokes a token **without** a successor pointer.

On refresh of a **revoked** token, walk the chain from the presented token to its
tip:

- If the tip is **live and unexpired**, the client simply never received the
  successor — a benign lost-response retry. Advance the tip by one rotation, hand
  the client that new pair, and **re-point** the presented token at the new
  successor so repeated, arbitrarily-delayed retries of the same stale token keep
  succeeding in O(1).
- If the tip is **dead** — revoked by logout/theft-burn (no successor), expired,
  or the chain dangles — it is theft: `revokeAllForUser` and 401.

A benign replay is therefore honoured **however long the phone was offline**;
there is no window to tune. The concurrent-rotation race is subsumed: a refresh
that loses the atomic `tryMarkRotated` CAS re-resolves the chain and advances the
tip, so exactly one live chain survives.

Implementation: `SessionTokenService.refresh` → `advanceChain` / `liveTip`;
`RefreshTokenStore` gains `replacedBy` on `StoredRefreshToken`, a
`successorId` argument on `tryMarkRotated`, and `repoint`. `app.session.reuse-grace`
is removed.

## Consequences

**Availability over strict single-use detection.** This is a deliberate trade-off
inherent to offline-first refresh: a benign lost-response retry and a genuine
concurrent theft both manifest as "an old token replayed while a newer one
exists," and the only thing that ever distinguished them was time — which we just
removed. So concurrent use of a stolen token is no longer detected by the chain
walk. For a single-user personal health app, being logged out constantly was the
real, frequent harm; token theft is a rare, higher-bar event.

**Theft is still bounded.** Explicit logout (`markRevoked`) and a burn
(`revokeAllForUser`) sever the chain (no successor pointer), so a logged-out or
burned token can never be re-animated. Sessions remain capped by the 60-day
sliding refresh TTL.

**Follow-up.** Stricter detection without sacrificing offline resilience would
require binding the refresh token to a device identity (a replay from an unknown
device is theft regardless of timing). Out of scope here; noted for later.

Covered by `SessionTokenServiceTest` (benign replay honoured regardless of delay,
repeated replays each succeed, logout severs the chain and burns, expiry rejected)
and `RefreshTokenRotationConcurrencyTest` (one winner under a concurrent race).
