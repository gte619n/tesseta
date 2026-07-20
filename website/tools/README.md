# tools — demo data + screenshot capture (dev only)

Everything here generates the product screenshots in `../public/assets/shots/`.
None of it is served (Firebase Hosting only publishes `../public`), and it never
touches production data — it runs the **web app against a local mock backend**
seeded with a single fictional persona ("Alex Rivera").

```
tools/
├── mock-backend.mjs   tiny HTTP mock of the Spring backend (dev-login + GET reads)
├── demo/*.json        per-domain demo responses (one persona, coherent across pages)
├── merge-demo.mjs     merges demo/*.json → demo-data.json (specialist files win;
│                      normalizes nutrition macro totals; nulls external image URLs)
├── demo-data.json     generated — what mock-backend.mjs serves
└── capture.mjs        Playwright: UAT dev sign-in, then screenshot each route
```

## Regenerate the screenshots

From the repo root, with the web app deps installed (`cd web && pnpm install`):

```bash
# 1. Build the mock dataset
node website/tools/merge-demo.mjs

# 2. Start the mock backend (port 9099)
node website/tools/mock-backend.mjs &

# 3. Start the web app pointed at the mock, with UAT dev sign-in enabled
cd web && UAT_AUTH_ENABLED=1 BACKEND_URL=http://localhost:9099 \
  AUTH_SECRET=dev-secret-0123456789 ADMIN_EMAILS=demo@tesseta.com \
  AUTH_GOOGLE_ID=x AUTH_GOOGLE_SECRET=x pnpm dev --port 3001 &

# 4. Capture into ../public/assets/shots
OUT=website/public/assets/shots node website/tools/capture.mjs
```

The mock logs any endpoint it doesn't recognize as `UNMATCHED <path>` — add that
key to the relevant `demo/*.json` and re-run `merge-demo.mjs`. To change the
persona's numbers, edit the `demo/*.json` files (they mirror the web app's
response types in `web/lib/types/`).
