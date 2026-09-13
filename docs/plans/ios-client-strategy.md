# iOS Client Strategy (iPhone + iPad)

> Status: **decision doc — no build committed** · Created 2026-09-13 · Source:
> audit run [`docs/audit/2026-09-13/`](../audit/2026-09-13/INDEX.md) (D6
> cross-platform, D16 product gaps, D12 SOTA) + operator discussion.

## The situation

The backend needs nothing: all clients speak REST + backend session tokens
(ADR-0010), and the third-party surface proves the API is client-agnostic
[Certain]. The problem is entirely client-side economics: there is **zero
shareable client code** — all business logic lives in Kotlin/Compose modules
with no KMP structure — and the audit already measured logic divergence at 2.5
client surfaces (three "today" implementations XPLAT-001, two `compositeTotal`s
XPLAT-004, drifted `LB_PER_KG` XPLAT-008). A third hand-written client
multiplies the contract-drift bug class (ARCH-002) that has shipped bugs twice.

## Hard prerequisites for ANY native iOS work

Gate the build decision on these, in order — they are audit Top-20 items with
their own prompts in `findings.json`:

1. **ARCH-002 contract fixtures** — backend↔client DTO/collection contract
   tests. Non-negotiable before a third DTO set exists.
2. **XPLAT-002 version negotiation** — minimum-client-version handshake.
   Two continuously-deployed-against clients is survivable; three is not.
3. **XPLAT-001 day-key fix** — one canonical "today" (`?date=` +
   `X-Timezone`) before a third client copies the bug.
4. **IMPL-PWA-01 shipped and measured** — the demand signal. If no iOS user
   materializes on the PWA, this whole document stays shelved.

## Options

### Step 0 — PWA (decided: do it)

See [`IMPL-PWA-01-web-pwa.md`](IMPL-PWA-01-web-pwa.md). ~3–4 days, gives
iPhone/iPad the daily loop, measures demand. This is the only iOS-facing work
authorized today.

### Option A — Kotlin Multiplatform + Compose Multiplatform (strategic path)

The 2026 landscape makes this materially more viable than when ADR-0001 chose
native-per-platform: CMP for iOS stable since 1.8.0 (May 2025, JetBrains);
Room 2.8.x / DataStore / ViewModel ship KMP support (Android Developers KMP
docs, fetched 2026-09-13 in the audit run). The existing
`core-domain`/`core-data` module split is exactly the shape KMP wants.

**Case for:** one implementation of sync engine, outbox, nutrition math,
domain models across Android + iOS — directly attacks systemic pattern B
(contracts-as-convention) instead of tripling it. Wear and phone keep working
throughout; iOS becomes a thin shell.

**Case against (lead with it):** Compose-rendered iOS UI is stable, not
native-feeling — HIG conformance is manual; the deprecated SQLCipher artifact
(SUP-003) has no KMP path, so the encrypted-mirror story must be re-solved
(SQLDelight + SQLCipher drivers, or Room KMP + platform encryption — decision
needed regardless per SUP-003); and it sits behind the MIG-001 toolchain ride
(Kotlin 2.0→2.4, AGP 9.x, Room 2.6→2.8, BOM refresh). A solo operator carries
the KMP build complexity forever.

**Phased plan (execute only when triggered):**

| Phase | Work | Effort | Gate to next |
|---|---|---|---|
| A0 | MIG-001 toolchain: Kotlin 2.4.x, AGP 9.x, Room 2.8.x, Compose BOM current — on Android alone, shipped and stable | 4–6 d | android-ci green 2 weeks, no regressions |
| A1 | `core-domain` → KMP (pure Kotlin: models, units, macros math). No behavior change; Android + Wear consume unchanged | 3–4 d | all unit tests pass from common source set |
| A2 | Encryption decision + `core-data` storage swap off `net.zetetic` (closes SUP-003 for Android too) | 3–5 d | migration test on a real device DB |
| A3 | `core-data` sync/outbox → KMP (Room KMP, Ktor or shared OkHttp expect/actual) | 8–12 d | emulator sync suite green on both targets |
| A4 | iOS shell: CMP app, Sign in with Google on iOS → `/api/auth/exchange`, dashboard + nutrition read-only | 8–10 d | TestFlight build on a real device |
| A5 | Feature waves: nutrition capture (camera via expect/actual), meds, workouts read, then parity by demand | ongoing | per-wave |

Total to a useful iOS beta: ~6–8 solo-weeks spread across a quarter, of which
A0–A2 are worth doing for Android alone (that's the hedge: phases A0–A2 are
no-regret).

### Option B — Native SwiftUI, online-only MVP

Best iOS/iPad feel, cleanest HIG story, no KMP build complexity. But it is a
permanent third implementation of every feature and contract, maintained by
one person — the audit's cross-platform findings are the argument against.
Choose only if, after an A4-scale spike, Compose-on-iOS feel is genuinely
unacceptable. Effort to the same beta scope: ~8–10 solo-weeks, then a
permanent ~1.5× feature tax forever.

### Rejected — Flutter / React Native rewrite

Platform migrations are presumptively rejected (audit standing rule); nothing
here overcomes the presumption — it discards the entire Kotlin investment and
the offline-first engine for no capability gain.

## Decision & triggers

**Today:** ship IMPL-PWA-01; execute prerequisites 1–3 from the audit plan;
optionally start A0 (no-regret toolchain work, also demanded by MIG-001).

**Trigger to open Option A:** a real iOS-owning user is active on the PWA
weekly for a month, or user #2 is iOS-only and the PWA loop proves
insufficient (specifically: photo capture or reminders friction).

**Trigger to prefer Option B instead:** A4 spike verdict "Compose feel
unacceptable," or KMP toolchain friction exceeds ~2 days/month.

**Do-nothing carrying cost:** zero until an iOS user exists; from that day,
the PWA is the mitigation and its gaps (no push, no offline) define the
pressure. Revisit at the next audit run regardless.
