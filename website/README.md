# website — the public marketing site

The public-facing site for **Tesseta**. It articulates the product's value
proposition — a personal health record that resolves activity, blood markers,
body composition, medications, nutrition, and training into one trustworthy view
across phone, watch, and web.

This is intentionally a **static site with no build step**: hand-written HTML +
CSS plus the brand SVGs. It is separate from `web/` (the authenticated Next.js
app that runs on Cloud Run at `app.tesseta.com`). Keeping it static is what makes
it essentially free to host.

```
website/
├── public/
│   ├── index.html          the single marketing page
│   ├── styles.css          brand tokens mirrored from docs/logo/LOGO-SPEC.md
│   └── assets/             logo/wordmark/favicon/OG image (SVG)
├── firebase.json           Firebase Hosting config (public dir, cache headers)
├── .firebaserc             pins the health-fitness-160 Firebase project
└── README.md               this file
```

Brand assets are copied from [`docs/logo/`](../docs/logo); the palette and type
match `docs/logo/LOGO-SPEC.md` and `web/app/globals.css` exactly. Content is
grounded in [`docs/reference/feature-catalog.md`](../docs/reference/feature-catalog.md)
and [`docs/requirements/`](../docs/requirements). To refresh a logo, re-copy the
SVG from `docs/logo/` rather than hand-editing.

## Why Firebase Hosting (lowest cost, existing infrastructure)

We already run Firebase in the `health-fitness-160` GCP project (Android builds
distribute through **Firebase App Distribution**), so Hosting adds no new
account, project, or vendor.

| Option | Fit | Cost |
|---|---|---|
| **Firebase Hosting** ✅ | Static files, free managed SSL, global CDN, one-command deploy, custom domains | **$0** in practice — a text/SVG site is far under the free tier (10 GB stored, 360 MB/day egress) |
| Cloud Run (like `web/`) | Overkill — needs a container + server for static content; bills per request and per idle-ish CPU | Small but non-zero, plus a Dockerfile to maintain |
| GCS bucket website | Cheap storage, but **no HTTPS on a custom domain** without an HTTPS load balancer (~\$18/mo) | LB makes it the *most* expensive option |

Firebase Hosting is the cheapest option that still gives HTTPS on a custom
domain, and it reuses infrastructure we already operate.

### Domain

`app.tesseta.com` and `api.tesseta.com` are Cloud Run domain mappings. The apex
`tesseta.com` and `www.tesseta.com` are currently unmapped — this site takes
them.

## Deploy

Prereq: the Firebase CLI (`npm i -g firebase-tools`) and access to the
`health-fitness-160` project.

```bash
cd website
firebase login                       # one time
firebase deploy --only hosting       # ships public/ to Firebase Hosting
```

Preview locally before shipping:

```bash
firebase emulators:start --only hosting   # or: firebase hosting:channel:deploy preview
# no-tooling fallback:
python3 -m http.server -d public 8000
```

### Connect the custom domain (one time)

In the [Firebase Hosting console](https://console.firebase.google.com/project/health-fitness-160/hosting/sites),
**Add custom domain** → `tesseta.com` (and `www.tesseta.com`), then add the DNS
records Firebase prints at the registrar. SSL certs are provisioned
automatically. This is the same out-of-band domain step the Cloud Run subdomains
use; it isn't scripted in this repo.

### Optional: automate on merge

To deploy on every push to `main` that touches `website/**`, mirror the existing
Cloud Build triggers in [`infra/triggers/`](../infra/triggers): add a
`website/cloudbuild.yaml` running `firebase deploy --only hosting` with a
`FIREBASE_TOKEN` (or the `tesseta-ci` service account granted
`firebasehosting.admin`) and an `includedFiles: [website/**]` filter. Until then,
deploy is the one manual command above.
