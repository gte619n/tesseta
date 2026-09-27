# `maestro/` — cross-platform mobile E2E flows (IMPL-E2E-01)

Maestro flows that drive the **iOS and Android** apps through synthetic user
journeys. The *same* YAML runs on both platforms because it targets the shared
accessibility-id vocabulary (see
[`docs/plans/IMPL-E2E-01-synthetic-user-testing.md`](../docs/plans/IMPL-E2E-01-synthetic-user-testing.md)).
Web journeys use Playwright (`web/e2e/journeys/`), not Maestro.

## Layout

```
maestro/
  config.yaml              workspace config (flow glob + tags)
  flows/
    _shared/sign-in.yaml   reusable "launch + land signed-in" sub-flow
    auth/…                 J1 sign-in → first-sync gate → dashboard  (tag: smoke)
    medications/…          J3 meds tab → add                          (tag: full)
```

## Run

```bash
# via the orchestrator (sets MAESTRO_APP_ID per platform, boots devices):
scripts/e2e/run-local.sh --platform android          # or ios
scripts/e2e/run-local.sh --subset                    # only smoke-tagged flows

# or directly (device must be booted + app installed):
export MAESTRO_APP_ID=com.gte619n.healthfitness
maestro test maestro/                                 # all flows
maestro test --include-tags=smoke maestro/            # subset
maestro test maestro/flows/auth/sign-in-first-sync.yaml
```

## Conventions

- `appId: ${MAESTRO_APP_ID}` — set per-platform by the runner (iOS bundle id vs
  Android package), so one flow is cross-platform.
- Tag flows `smoke` (per-PR spine) or `full` (nightly).
- Target elements by shared **id** (`assertVisible: { id: "meds-list" }`); target
  tab-bar / nav items by visible **title** (`tapOn: "Medications"`).
- Reusable steps go in `flows/_shared/` and are pulled via `runFlow:`.
- Every journey should start from `_shared/sign-in.yaml` (dev-login → seeded,
  deterministic state).
