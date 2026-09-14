# D3 — Supply Chain & Licensing Audit (SUP)

Date: 2026-09-13 · Auditor: D3 · Repo: tesseta monorepo (worktree)

## BLUF

The headline "12 high pnpm vulns" in web/ is a non-event: every single high is a **dev-only** transitive (eslint/vitest tooling), unreachable in the shipped Next.js image, and CI already gates with `pnpm audit --prod`. The real supply-chain exposure is elsewhere: (1) the backend sits on **OSS-EOL Spring lines** (Boot 3.5 and Framework 6.2 both ended OSS support 2026-06-30) so the manual CVE-pin treadmill in `build.gradle.kts` is now the *only* patch source and structurally cannot patch Spring itself; (2) the **netty pin (4.1.137) was superseded on 2026-09-09** by 4.1.138.Final fixing ~23 CVEs — the Trivy deploy gate will start blocking backend deploys as soon as scanners catch up; (3) the Android at-rest encryption library **`android-database-sqlcipher` is officially deprecated** (successor `sqlcipher-android`) and blocks Google Play 16KB-page-size compliance; (4) the **exercise-thumbnails Cloud Function** is entirely outside every scan/gate and carries HIGH sharp/libvips and fast-uri vulns on an EOL Node 20 runtime; (5) **ODbL/BSD attribution obligations from ADR-0006 are only partially implemented** — web has one attribution string, Android has none, and the promised credits page does not exist; root LICENSE is literally "TBD". No SBOM or provenance attestation exists anywhere. No GPL/AGPL contamination found. No typosquats found.

---

## 1. Dependency inventory

| Component | Direct deps | Transitive (resolved) | Source of truth | Evidence |
|---|---|---|---|---|
| web/ | 6 runtime + 18 dev | 716 lockfile packages | `web/package.json`, `web/pnpm-lock.yaml` | [Certain] counted 716 entries in the `packages:` section of `pnpm-lock.yaml` this run |
| backend/ | ~21 catalog libs + 1 inline (`springdoc-openapi-starter-webmvc-ui:2.8.9` at `backend/build.gradle.kts:93`) + 6 transitive version overrides | **not resolvable without a build** (Boot BOM resolution; R3 forbids running gradle) | `backend/gradle/libs.versions.toml` | [Certain] for directs; transitives unquantified by design |
| android/ | ~70 catalog libraries across 68 modules (incl. 5 gradle-plugin artifacts) | **not resolvable without a build**; no lockfiles exist | `android/gradle/libs.versions.toml` | [Certain] for directs |
| functions/exercise-thumbnails/ | 3 runtime (`@google-cloud/functions-framework` ^3.4.0, `@google-cloud/storage` ^7.14.0, `sharp` ^0.33.5) | package-lock.json present | `functions/exercise-thumbnails/package.json` | [Certain] |
| website/ | 0 (static Firebase Hosting: `firebase.json` + `public/`) | — | `website/` listing | [Certain] |
| uat/ | Gradle project, separate catalog (not deployed to prod) | — | `uat/build.gradle.kts` | [Certain] present; not audited in depth |

Dependency automation: `.github/dependabot.yml` covers all four ecosystems (gradle×2, npm/web, github-actions), weekly, minor+patch grouped, majors as individual PRs. [Certain] This is a genuinely good setup; note it does **not** cover `functions/exercise-thumbnails` (npm directory `/web` only). CodeQL (`.github/workflows/codeql.yml`) is SAST, not SCA.

## 2. Web: the 12 high vulns — present vs. reachable

`pnpm audit` run 2026-09-13 in `web/`: **0 critical / 12 high / 4 moderate / 0 low** — matches baseline exactly. [Certain]

Key fact: **all 12 highs resolve through two devDependencies** (`@eslint/eslintrc`, `@vitest/coverage-v8`/`vitest`). The production image (`web/Dockerfile`) ships only `.next/standalone` + static assets from a builder stage — devDependencies never enter the runtime layer. CI already gates the meaningful surface: `.github/workflows/web-ci.yml:38-41` runs `pnpm audit --prod --audit-level=high` (passes ⇒ zero prod-reachable highs). [Certain]

### Vuln reachability table (web/)

| Advisory | Package | Sev | Path (from audit output) | Present | Reachable in prod runtime? |
|---|---|---|---|---|---|
| GHSA-3jxr-9vmj-r5cp | brace-expansion <1.1.16 | high | `@eslint/eslintrc>minimatch>` | yes | **No** — lint-time only |
| GHSA-3jxr-9vmj-r5cp | brace-expansion 2.x | high | `@vitest/coverage-v8>test-exclude>glob>minimatch>` | yes | **No** — test-coverage only |
| GHSA-3jxr-9vmj-r5cp | brace-expansion 3–5.0.6 | high | `@vitest/coverage-v8>test-exclude>minimatch>` | yes | **No** |
| GHSA-mh99-v99m-4gvg | brace-expansion (3 ranges) | high ×3 | same two roots | yes | **No** |
| GHSA-rgw5-rvv9-x895 | brace-expansion (3 ranges) | high ×3 | same two roots | yes | **No** |
| GHSA-52cp-r559-cp3m | js-yaml <4.3.0 | high | `@eslint/eslintrc>js-yaml` | yes | **No** — eslint config loading |
| GHSA-5p4m-2wfm-xmqj | js-yaml <4.3.1 | high | `@eslint/eslintrc>js-yaml` | yes | **No** |
| GHSA-2883-xcg3-v3hh | js-yaml <4.3.2 | high | `@eslint/eslintrc>js-yaml` | yes | **No** |
| GHSA-h67p-54hq-rp68 | js-yaml ≤4.1.1 | mod | `@eslint/eslintrc>js-yaml` | yes | **No** |
| GHSA-fxqj-rqcc-2cmp | postcss ≤8.5.22 | mod | `.>postcss` (pinned 8.5.18 via override) | yes | **No** — build-time CSS pipeline; input is repo-owned CSS, not attacker-controlled |
| GHSA-82fw-gwwq-j7x9 | vitest / @vitest/mocker | mod ×2 | `.>vitest` | yes | **No** — test runner |

Worst-case realistic impact of the entire list: a malicious file *in the repo* could DoS a CI lint/test job. [Certain on paths; Likely on impact framing] Also note the exploit-class is DoS-in-tooling, not RCE. Residual risk accepted is small; hygiene fix is cheap (see SUP-006).

The `pnpm.overrides` block in `web/package.json` (tar 7.5.19, sharp 0.35.4, sigstore, nanoid, picomatch, browserslist, postcss 8.5.18) plus the Dockerfile's `apk upgrade libssl3` and `rm -rf .../npm .../corepack` show the same pattern as backend: hand-pinning to satisfy the Trivy image gate. [Certain]

## 3. Backend: CVE-pin verification (fetched this run)

The override block at `backend/build.gradle.kts:12-37` pins: jackson-bom 2.21.4, netty 4.1.137.Final, tomcat 10.1.59, spring-framework 6.2.19, micrometer 1.15.12, httpcore5 5.4.3.

| Pin | Verified against | Verdict today (2026-09-13) |
|---|---|---|
| jackson-databind 2.21.4 (CVE-2026-54512, RCE via PolymorphicTypeValidator generic-type bypass) | github.com/advisories/GHSA-j3rv-43j4-c7qm (fetched this run): affected ≤2.21.3, patched **2.21.4**, CVSS 8.1, published 2026-06-16 | **[Certain] still sufficient.** (But see HYPOTHESES: CVE-2026-59889, moderate, 2026-07-21, patched-version unconfirmed.) |
| tomcat 10.1.59 | tomcat.apache.org/security-10.html (fetched this run): newest 10.1.x is **10.1.59** (2026-08-20); no fixes above it; confirms 10.1.58 vote failed and the 9-CVE batch (incl. CVE-2026-65182/-68569/-65927 "Important") landed in 10.1.59 | **[Certain] current.** |
| netty 4.1.137.Final | netty.io/news (fetched this run): **4.1.138.Final released 2026-09-09**, described as fixing **~23 CVEs** (HTTP request smuggling, HTTP/2 DoS, codec resource exhaustion; CVE ids not yet assigned at publication). Netty 4.1 line EOL **2027-07-01** | **[Certain] superseded 4 days ago** → SUP-002 |
| spring-framework 6.2.19 | endoflife.date/spring-framework (fetched this run): 6.2.19 (2026-06-08) is the **final OSS patch**; 6.2 OSS support ended **2026-06-30** | [Certain] pin = last patch that will ever exist on this line → SUP-001 |
| spring-boot 3.5.14 | endoflife.date/spring-boot (fetched this run): 3.5 OSS support ended **2026-06-30**; last patch 3.5.16 (2026-06-25); current lines 4.0 (OSS→2026-12-31) and 4.1 (OSS→2027-07-31) | [Certain] EOL line, and 2 patches behind even within it → SUP-001 |

micrometer/httpcore5 pins not independently re-verified this run (time-boxed) — see HYPOTHESES.

## 4. Maintenance / staleness of critical deps (fetched this run)

| Dep | Pinned | Latest (fetched) | Assessment |
|---|---|---|---|
| next-auth | 5.0.0-beta.32 (`web/package.json`) | **5.0.0-beta.32 is the latest beta; no stable v5 exists** (npm via search, published 2026-07-20; stable dist-tag is still 4.24.x) | [Certain] the entire web auth stack rides a perpetual beta — pinned exactly (good), but no semver/security-backport guarantees on betas → SUP-009 |
| android-database-sqlcipher | 4.5.4 (`android/gradle/libs.versions.toml:15`) | README (fetched): "**officially deprecated**", successor `sqlcipher-android`, migration required for 16KB-page-size Play compliance | [Certain] → SUP-003 |
| moshi | 1.15.1 | 1.15.2, 2024-12-05 (search.maven.org, fetched) | [Certain] dormant ~21 months; low risk (stable JSON lib) but on notice |
| retrofit | 2.11.0 | 3.0.0 on Maven Central (fetched; epoch 1747326599 ≈ 2025-05) | [Certain] 2.x is the legacy line; no known CVE pressure; plan an eventual 3.x move |
| googleid | 1.1.1 | 1.2.0 (dl.google.com group-index.xml, fetched) | [Certain] one minor behind; fine |
| wear compose-material3 | 1.0.0-alpha28; health-services-client 1.1.0-alpha04 | — | [Certain] alpha pins shipping in the wear APK; API churn risk only |
| functions Node engine | `"node": "20"` | endoflife.date/nodejs (fetched): Node 20 security support ended **2026-04-30**; supported lines are 22/24/26 | [Certain] EOL runtime → SUP-005 |

## 5. Lockfile integrity & reproducibility

- web: `pnpm-lock.yaml` committed; `packageManager: pnpm@10.32.1` pinned (corepack). [Certain] Weakness: `web/Dockerfile` falls back to `pnpm install --no-frozen-lockfile` if the lockfile is absent — a silent unpinned build path (should hard-fail instead). [Certain]
- functions: `package-lock.json` committed, but deps use `^` ranges and nothing gates them. [Certain]
- backend + android: **no Gradle dependency locking** — zero `*.lockfile` files, no `activateDependencyLocking`/`lockAllConfigurations` anywhere (grep across both trees this run). `backend/Dockerfile` re-resolves from Maven Central inside every image build, so deploy-time resolution can drift from CI-time resolution on any dynamic/BOM-managed version. [Certain] Mitigating: nearly everything is BOM- or catalog-pinned to exact versions, so practical drift is low. [Likely]

## 6. Provenance, SBOM, scan coverage

- **No SBOM generation anywhere**: no syft/cyclonedx/spdx steps in `backend/cloudbuild.yaml`, `web/cloudbuild.yaml`, `android/cloudbuild.yaml`, or CI workflows (grep this run). No binary-authorization/attestation steps either. [Certain]
- Trivy image scan (HIGH/CRITICAL, `--ignore-unfixed`, `--exit-code=1`) gates **both backend and web** deploys (`backend/cloudbuild.yaml` scan-image step; `web/cloudbuild.yaml` scan-image step) — note the shared-context brief said backend-only; that is out of date. [Certain]
- Coverage gaps: **android** release build (`android/cloudbuild.yaml`) has no dependency/vuln scan of any kind before Firebase App Distribution; **functions/exercise-thumbnails** has no cloudbuild file and no CI at all; scanning is **deploy-time only** (nothing scheduled), so vulnerabilities accumulate invisibly between backend merges — the documented "first backend merge eats accumulated Trivy breakage" failure mode. [Certain on config; Likely on failure mode]

## 7. Licensing

### Component × license table

| Component | License profile | Copyleft exposure | Evidence |
|---|---|---|---|
| Repo itself | **"TBD — license to be selected before public release."** | — | `LICENSE:1` [Certain] |
| web (716 pkgs, `pnpm licenses list --json` this run) | MIT 478, Apache-2.0 26, ISC 23, BSD-2/3 16, MPL-2.0 3, BlueOak 5, CC-BY-4.0 1 (caniuse-lite data), CC0 1, Python-2.0 1, 0BSD 1, MIT-0 1, **LGPL-3.0-or-later 1** (`@img/sharp-libvips-darwin-arm64`) | **None problematic.** LGPL libvips prebuilds are server-side (no distribution to end users ⇒ LGPL obligations don't trigger); MPL is file-level. No GPL/AGPL. [Certain for inventory; Likely for LGPL analysis] |
| backend | Apache-2.0 across the board (Spring, google-cloud-*, firebase-admin, Tink, Caffeine, springdoc) | None | catalog review [Likely — not resolved transitively] |
| android (ships as distributed binary — copyleft matters most here) | Apache-2.0 (androidx, Hilt, Square okhttp/retrofit/moshi, coil, turbine, mockk, mikepenz markdown-renderer), MIT (robolectric — test only), Google Play services under Android SDK ToS, **SQLCipher Community: BSD-style requiring copyright-notice reproduction in the shipped app** | **Attribution obligation currently unmet** (no licenses/notice screen — see SUP-004) | catalog + SQLCipher README (fetched: "SQLCipher code: BSD-style license (Zetetic LLC)") [Certain for SQLCipher; Likely for the rest] |
| Data: food catalog | USDA CC0 + **Open Food Facts ODbL** | ODbL attribution + share-alike | `docs/decisions/ADR-0006-open-food-facts-licensing.md` [Certain] |

### ADR-0006 conformance check

The ADR's engineering constraints are well designed and mostly implemented: `source` tagging and no-bulk-export/no-bundling are honored (Android resolves foods through the backend; `android/core-data/.../FoodRepository.kt:85` confirms the backend-fallback pattern; no OFF data bundled in the APK). [Certain]

**Non-conformances** (ADR §4 "Attribution"): the only attribution string in any client is `web/components/nutrition/AddFoodModal.tsx:592` ("Nutrition data from Open Food Facts (ODbL)"), conditional on `food.source === "OPEN_FOOD_FACTS"` (line 590). The Android app — the primary barcode-scan surface (ML Kit scanning per `android/gradle/libs.versions.toml` IMPL-13 comment) — contains **zero** OFF/ODbL attribution strings (repo-wide grep this run: only two code comments). The ADR-promised "project-level attribution/credits page [that] links the license" does not exist in web/, android/, or website/ (no NOTICE, THIRD_PARTY, credits, or licenses assets found repo-wide). [Certain]

### Typosquat / confusion scan

All direct dep names across web, functions, backend, android checked against canonical names (`next`, `react`, `@dnd-kit/core`, `react-markdown`, `@google-cloud/*`, `sharp`, Square/Google/androidx Maven coordinates, `net.zetetic`, `com.mikepenz`). No lookalikes, no unscoped shadows of scoped packages, no suspicious registries (only registry.npmjs.org in `pnpm-lock.yaml`; only mavenCentral + google in gradle repos). **Nothing found.** [Certain, quick-check depth]

---

## Findings

### SUP-001 — Backend runs on OSS-EOL Spring Boot 3.5 / Framework 6.2; CVE-pin treadmill has no future
- Severity: **critical** (critical proposal 1 of 2) · Confidence: [Certain]
- Evidence: `backend/gradle/libs.versions.toml:6` (springBoot = "3.5.14"); `backend/build.gradle.kts:12-37` (override block); endoflife.date/spring-boot fetched 2026-09-13 (3.5 OSS ended 2026-06-30, last patch 3.5.16; 4.0 OSS→2026-12, 4.1 OSS→2027-07); endoflife.date/spring-framework fetched 2026-09-13 (6.2 OSS ended 2026-06-30, 6.2.19 final).
- Impact: an internet-facing health-data API (regulated mode) whose framework will receive **no further OSS security patches**. The override mechanism can bump jackson/netty/tomcat, but the next Spring-Security/Framework/Boot CVE has no OSS fix on this line — the only options at that point will be emergency major-version migration or shipping known-vulnerable.
- Effort: 3–5 days (Boot 3.5→4.x: jakarta already done; main costs are config-property renames, spring-security API drift, springdoc major bump, re-baselining the Trivy gate). Autonomy: high (agent can do migration + test green; human reviews).
- Null option cost: rising probability-weighted exposure; first unpatched Spring CVE forces the same migration under incident pressure. Do-nothing is defensible only until year-end (Boot 4.0's OSS window gives 4.1 as the better target).
- Prompt: "In backend/, migrate Spring Boot 3.5.14 → 4.1.x (latest). Update `backend/gradle/libs.versions.toml` springBoot + springDependencyManagement, run the properties migrator, fix compile/config breaks, re-evaluate each `extra[...]` override in `backend/build.gradle.kts` (delete every pin the new BOM supersedes; keep only ones still ahead), bump springdoc to the Boot-4-compatible major, run unit + firestore-emulator suites, and verify the Trivy gate against the new image. Do not change application behavior."

### SUP-002 — Netty pin 4.1.137 superseded by 4.1.138.Final (~23 CVE fixes, 2026-09-09); Trivy gate will start blocking deploys
- Severity: high · Confidence: [Certain] on supersession; [Likely] on gate-blockage timing
- Evidence: `backend/build.gradle.kts:33` (`extra["netty.version"] = "4.1.137.Final"`); netty.io/news and netty.io/news/2026/09/09/4-1-138-Final.html fetched this run (23 CVEs: HTTP request smuggling, HTTP/2 DoS, codec exhaustion; 4.1 EOL 2027-07-01).
- Impact: known-vulnerable netty in prod images; and per the project's own history, the Trivy `--exit-code=1` gate will hard-block the next backend merge once these CVEs get IDs and enter vuln DBs — surprise deploy outage.
- Effort: 0.1 days. Autonomy: full. Null option cost: near-term blocked deploy + exposure window.
- Prompt: "In `backend/build.gradle.kts`, change `extra[\"netty.version\"]` from `4.1.137.Final` to `4.1.138.Final`, update the comment block to cite the 2026-09-09 release (23 CVE batch), build and run tests."

### SUP-003 — At-rest health-data encryption depends on an officially deprecated library (android-database-sqlcipher)
- Severity: high · Confidence: [Certain]
- Evidence: `android/gradle/libs.versions.toml:15` (`sqlcipher = "4.5.4"`), library block `net.zetetic:android-database-sqlcipher`; `android/core-data/build.gradle.kts:34`; github.com/sqlcipher/android-database-sqlcipher README fetched this run: "officially deprecated", successor `sqlcipher-android`, migration tied to Google Play 16KB-page-size compliance.
- Impact: the component that encrypts synced health data on-device gets no maintenance, no future SQLCipher-core security fixes, and no 16KB-page native libs — a hard blocker for future Play Store distribution (currently Firebase App Distribution only, so latent not live).
- Effort: 1–2 days (`net.zetetic:sqlcipher-android` + `androidx.sqlite` SupportFactory swap per Zetetic migration guide; instrumented DAO tests exist to verify). Autonomy: high; needs device/emulator test pass and an upgrade-in-place check of existing encrypted DBs.
- Null option cost: none immediate; becomes a release-blocking scramble the day Play distribution starts, and an unpatchable-crypto liability meanwhile.
- Prompt: "Migrate android/core-data from `net.zetetic:android-database-sqlcipher:4.5.4` to the maintained `net.zetetic:sqlcipher-android` (latest, Zetetic migration guide). Update the catalog alias, imports (net.sqlcipher.* → net.zetetic.database.*), and the SupportFactory wiring for the Keystore-derived passphrase; confirm an existing encrypted DB opens after upgrade (in-place format compatibility) via the instrumented DAO tests."

### SUP-004 — License obligations unmet: no OFF/ODbL attribution on Android, no credits/licenses page, SQLCipher BSD notice missing, root LICENSE "TBD"
- Severity: medium (legal/compliance, commercial-product ambition) · Confidence: [Certain]
- Evidence: ADR-0006 §4 (`docs/decisions/ADR-0006-open-food-facts-licensing.md`) requires attribution wherever OFF data surfaces + a credits page; only hit repo-wide is `web/components/nutrition/AddFoodModal.tsx:592`; zero attribution strings in android (grep this run); no NOTICE/THIRD_PARTY/credits/licenses files repo-wide (find this run); SQLCipher README (fetched) — BSD-style requires notice reproduction in distributed apps; `LICENSE:1` = "TBD — license to be selected before public release."
- Impact: ODbL attribution is a *condition* of the OFF data use the product depends on; SQLCipher BSD notice is a condition of shipping the APK. Cheap to fix, embarrassing (or license-terminating, for ODbL) to be called on.
- Effort: 1 day. Autonomy: full for the mechanical parts; license selection for the repo itself is founder-only.
- Null option cost: low probability of enforcement, nonzero reputational/legal downside; blocks any public launch checklist anyway.
- Prompt: "(1) Add 'Nutrition data from Open Food Facts (ODbL)' attribution to the Android food-detail/barcode-result surfaces where `source == OPEN_FOOD_FACTS` (mirror `web/components/nutrition/AddFoodModal.tsx:590-592`). (2) Add an open-source licenses screen in android feature-settings (Google's oss-licenses plugin or AboutLibraries), ensuring the SQLCipher BSD notice is included, and a /credits page on web linking the ODbL license per ADR-0006 §4. (3) Flag to the founder that root LICENSE is still 'TBD'."

### SUP-005 — exercise-thumbnails Cloud Function: outside all scanning, HIGH vulns in runtime deps, EOL Node 20
- Severity: medium · Confidence: [Certain]
- Evidence: `functions/exercise-thumbnails/package.json` (`"node": "20"`, `sharp ^0.33.5`, `@google-cloud/storage ^7.14.0`); `npm audit --package-lock-only` this run: 12 vulns (2 high: **sharp ≤0.35.4-rc.0** — libvips CVE-2026-33327/-33328/-35590/-35591 + libheif GHSAs, fix 0.35.4; **fast-uri** SSRF/host-confusion GHSA batch), plus qs/body-parser/uuid moderates; no cloudbuild file for functions/ (find this run); dependabot.yml has no entry for this directory; endoflife.date/nodejs fetched: Node 20 security support ended 2026-04-30.
- Impact: an image-parsing function (sharp decodes image bytes — the classic memory-unsafe attack surface) with unpatched libvips CVEs on an EOL runtime, invisible to every gate. Mitigant: input is GCS-triggered exercise media uploaded by admin flows, not arbitrary end-user files → exploitation requires an authenticated/privileged writer. [Likely]
- Effort: 0.5 days. Autonomy: full except the redeploy itself.
- Null option cost: low-likelihood compromise path; certain future forced migration when GCF retires nodejs20.
- Prompt: "In functions/exercise-thumbnails: bump engines.node to 22, sharp to ^0.35.4, @google-cloud/storage and functions-framework to latest majors; regenerate package-lock; `npm audit` to zero high; `node --check index.js`. Add a dependabot.yml npm entry for `/functions/exercise-thumbnails`. Note redeploy command for the operator (gcloud functions deploy, runtime nodejs22)."

### SUP-006 — Web's 12 high audit findings are all dev-only; close them as hygiene, not fire
- Severity: low · Confidence: [Certain]
- Evidence: audit paths table above (pnpm audit --json this run); `web-ci.yml:38-41` prod-scoped gate; Dockerfile standalone-output runtime.
- Impact: none at runtime; noise cost only (baseline "12 high" reads scarier than it is; masks real signal).
- Effort: 0.2 days. Autonomy: full.
- Null option cost: essentially zero; occasional CI DoS-by-weird-filename theoretically possible.
- Prompt: "In web/: bump devDeps to clear `pnpm audit`: eslint/@eslint/eslintrc line new enough for js-yaml ≥4.3.2 + brace-expansion ≥1.1.18, vitest/@vitest/coverage-v8 ≥4.1.11 (clears test-exclude chains), and raise the postcss override to ≥8.5.23. If a bump is blocked, add targeted `pnpm.overrides` for brace-expansion/js-yaml instead. `pnpm audit` should end 0 high; run tests + lint."

### SUP-007 — No SBOM/provenance; scan coverage is deploy-time-only and skips android + functions
- Severity: medium (regulated mode) · Confidence: [Certain]
- Evidence: grep of all three cloudbuild.yaml files + workflows this run (no sbom/syft/cyclonedx/attestation); Trivy steps exist in backend+web cloudbuild only; `android/cloudbuild.yaml` has none; deploy-time-only gating matches documented "stale deploy gate" incidents.
- Impact: no dependency inventory artifact for compliance (EU CRA / customer security questionnaires); vulnerabilities invisible between deploys; android/functions never scanned at all.
- Effort: 1 day. Autonomy: full.
- Null option cost: acceptable short-term for a solo founder; grows with any enterprise/regulatory conversation.
- Prompt: "Add (1) a `trivy image --format cyclonedx` SBOM step after each Trivy scan in backend/web cloudbuild.yaml, uploading to the artifacts bucket; (2) a weekly scheduled GitHub Actions workflow running `trivy image` against the `:latest` backend/web images plus `pnpm audit --prod` (web) and `npm audit` (functions) so CVE drift surfaces between deploys instead of at merge time; (3) an OSV-scanner step over `android/gradle/libs.versions.toml` in android-ci."

### SUP-008 — No Gradle dependency locking; web Dockerfile has an unpinned-install fallback
- Severity: low · Confidence: [Certain]
- Evidence: zero `*.lockfile`, no locking config in backend/android (grep/find this run); `backend/Dockerfile` resolves deps at image-build time; `web/Dockerfile` `else pnpm install --no-frozen-lockfile` branch.
- Impact: deploy-time resolution can differ from CI-tested resolution; a compromised/yanked-and-republished transitive could enter a prod image without a diff. Low practical drift given exact pins/BOMs. [Likely]
- Effort: 0.5 days. Autonomy: full.
- Null option cost: small; mostly a reproducibility/forensics gap.
- Prompt: "Enable Gradle dependency locking for backend (activateDependencyLocking on runtimeClasspath; commit gradle.lockfile; add `--write-locks` note to docs). In web/Dockerfile, delete the `--no-frozen-lockfile` fallback so a missing lockfile fails the build. Android locking is optional (68 modules — defer unless cheap)."

### SUP-009 — Auth stack on a perpetual beta (next-auth 5.0.0-beta.32); assorted dormant pins
- Severity: low · Confidence: [Certain] on versions; [Likely] on risk framing
- Evidence: `web/package.json` (next-auth 5.0.0-beta.32, exact-pinned); npm (via search, fetched this run): beta.32 published 2026-07-20 is the newest v5, stable tag still 4.24.x — v5 has been beta for years; moshi 1.15.2 last release 2024-12-05 (Maven Central, fetched); retrofit 2.x superseded by 3.0.0 (fetched); wear-compose-material3 alpha28 / health-services alpha04 (`android/gradle/libs.versions.toml`).
- Impact: betas carry no security-backport promise; if a next-auth CVE lands, the fix may arrive as beta.33 with breaking API drift. Exact pin + Auth.js's beta being the de-facto Next 15 path make this tolerable, not comfortable.
- Effort: 0 now (watch item); 0.5 day when v5 goes stable. Autonomy: full.
- Null option cost: near zero today.
- Prompt: "Watch item: when next-auth v5 stable ships, bump from 5.0.0-beta.32 promptly. Meanwhile keep the exact pin (no range) and ensure dependabot doesn't auto-bump betas into the grouped PR without review. Opportunistically bump moshi 1.15.1→1.15.2."

---

## HYPOTHESES (unverified this run)

- CVE-2026-59889 (jackson-databind @JsonView/@JsonUnwrapped bypass, moderate, 2026-07-21): patched version not confirmed; 2.21.4 (2026-06) *may* predate the fix. Verify GHSA-5gvw-p9qm-jgwh's patched range next backend touch.
- micrometer 1.15.12 and httpcore5 5.4.3 pins were not re-verified against advisories newer than their comments.
- Whether any of netty 4.1.138's 23 CVEs are reachable in this backend (netty arrives via gRPC clients — Firestore/GenAI — not as a server) — bump regardless; Trivy doesn't do reachability either.
- Android transitive closure (and thus any copyleft hiding in transitives, e.g. via mikepenz renderer) was not resolved — R3 forbade running gradle. A one-time `licensee`/AboutLibraries run would settle it.
- `com.mikepenz:multiplatform-markdown-renderer-m3` assumed Apache-2.0 (not fetched).
- functions deploy mechanism (assumed manual gcloud; no pipeline found) — confirm with operator.

```json
[
  {"id":"SUP-001","title":"Backend on OSS-EOL Spring Boot 3.5 / Framework 6.2; manual CVE-pin treadmill has no future","severity":"critical","confidence":"certain","component":"backend","evidence":["backend/gradle/libs.versions.toml:6","backend/build.gradle.kts:12-37","endoflife.date/spring-boot fetched 2026-09-13: 3.5 OSS ended 2026-06-30","endoflife.date/spring-framework fetched 2026-09-13: 6.2 OSS ended 2026-06-30, 6.2.19 final"],"effort_days":4,"autonomy":"high","null_option_cost":"first unpatched Spring CVE forces same migration under incident pressure"},
  {"id":"SUP-002","title":"netty pin 4.1.137.Final superseded by 4.1.138.Final (~23 CVEs, 2026-09-09); Trivy gate will block deploys","severity":"high","confidence":"certain","component":"backend","evidence":["backend/build.gradle.kts:33","netty.io/news/2026/09/09/4-1-138-Final.html fetched 2026-09-13"],"effort_days":0.1,"autonomy":"full","null_option_cost":"near-term blocked deploy + known-vuln exposure"},
  {"id":"SUP-003","title":"android-database-sqlcipher 4.5.4 officially deprecated; blocks 16KB Play compliance; encrypts health data","severity":"high","confidence":"certain","component":"android","evidence":["android/gradle/libs.versions.toml:15","android/core-data/build.gradle.kts:34","github.com/sqlcipher/android-database-sqlcipher README fetched 2026-09-13"],"effort_days":1.5,"autonomy":"high","null_option_cost":"latent until Play distribution; unmaintained crypto meanwhile"},
  {"id":"SUP-004","title":"License obligations unmet: no Android ODbL attribution, no credits/licenses page, SQLCipher BSD notice missing, LICENSE=TBD","severity":"medium","confidence":"certain","component":"android,web,repo","evidence":["docs/decisions/ADR-0006-open-food-facts-licensing.md §4","web/components/nutrition/AddFoodModal.tsx:592 (sole attribution)","repo-wide grep/find this run: zero android attribution, zero NOTICE/credits files","LICENSE:1"],"effort_days":1,"autonomy":"high","null_option_cost":"ODbL is a condition of the OFF data the product depends on"},
  {"id":"SUP-005","title":"exercise-thumbnails function unscanned: HIGH sharp/libvips + fast-uri vulns, EOL Node 20, no dependabot/CI/cloudbuild","severity":"medium","confidence":"certain","component":"functions","evidence":["functions/exercise-thumbnails/package.json","npm audit --package-lock-only this run: 12 vulns (2 high)","endoflife.date/nodejs fetched 2026-09-13: Node 20 EOL 2026-04-30","no cloudbuild/dependabot entry (find/grep this run)"],"effort_days":0.5,"autonomy":"high","null_option_cost":"low-likelihood compromise; forced migration at GCF nodejs20 retirement"},
  {"id":"SUP-006","title":"All 12 high web pnpm-audit vulns are dev-only (eslintrc/vitest chains) — unreachable in prod; hygiene bump","severity":"low","confidence":"certain","component":"web","evidence":["pnpm audit --json this run: every high path roots at @eslint/eslintrc or @vitest/coverage-v8","web/Dockerfile standalone runtime","(.github/workflows/web-ci.yml:38-41 pnpm audit --prod gate passes)"],"effort_days":0.2,"autonomy":"full","null_option_cost":"~zero; audit noise masks real signal"},
  {"id":"SUP-007","title":"No SBOM/provenance anywhere; scanning deploy-time-only; android + functions never scanned","severity":"medium","confidence":"certain","component":"ci/cd","evidence":["backend|web|android cloudbuild.yaml grep this run: Trivy in backend+web only, no SBOM/attestation steps anywhere","no scheduled scans in .github/workflows"],"effort_days":1,"autonomy":"full","null_option_cost":"acceptable short-term; grows with regulatory/enterprise exposure"},
  {"id":"SUP-008","title":"No Gradle dependency locking; web Dockerfile silently falls back to non-frozen install","severity":"low","confidence":"certain","component":"backend,android,web","evidence":["find: zero *.lockfile; grep: no locking config","backend/Dockerfile deploy-time resolution","web/Dockerfile --no-frozen-lockfile fallback"],"effort_days":0.5,"autonomy":"full","null_option_cost":"reproducibility/forensics gap only"},
  {"id":"SUP-009","title":"next-auth v5 still beta (beta.32 latest, 2026-07-20); moshi dormant; retrofit on legacy 2.x; wear libs on alpha","severity":"low","confidence":"certain","component":"web,android","evidence":["web/package.json next-auth 5.0.0-beta.32","npm search fetched this run: no stable v5","search.maven.org fetched: moshi 1.15.2 (2024-12-05), retrofit 3.0.0"],"effort_days":0,"autonomy":"full","null_option_cost":"near zero today; watch item"}
]
```
