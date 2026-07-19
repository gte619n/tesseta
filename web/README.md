# health-fitness-web

Next.js 15 App Router, TypeScript strict, Tailwind v4, pnpm. Deploys to
Cloud Run as `health-fitness-web` in `us-central1`.

## Develop
```bash
cp .env.example .env.local        # set BACKEND_URL (e.g. http://localhost:8080)
pnpm install
pnpm dev
```

Open <http://localhost:3000>. (`bash infra/scripts/dev.sh` from the repo root
writes `.env.local` for you and starts the backend too.)

`lib/api.ts` reads `process.env.BACKEND_URL` — that exact name. Setting
`BACKEND_BASE_URL` (an older name) leaves the app non-functional
(`apiFetch` throws "BACKEND_URL is not configured").

## Other scripts
- `pnpm typecheck`
- `pnpm lint`
- `pnpm test` (vitest)
- `pnpm build` (production build)
- `pnpm start` (run the production build)

## Deploy
Pushed via Cloud Build (`cloudbuild.yaml`). The backend Cloud Run URL is
passed in as `_BACKEND_URL` and surfaces inside the container as
`BACKEND_URL` (which `lib/api.ts` and `auth.ts` read).
