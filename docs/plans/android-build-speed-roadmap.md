# Android build-speed roadmap: the bigger guns

- Status: Proposed (options catalog — each item is its own decision + PR)
- Date: 2026-09-06
- Context branch: `android-build-speed`

## Where we are

The `android-build-speed` branch shipped the cheap, high-certainty wins:

- **Local**: the per-build `gcloud secrets versions access` exec is gone
  (`webOauthClientId` caches in `local.properties`; a `providers.exec` result
  is a configuration-cache *input*, so Gradle was re-running it — a network
  round-trip — on every build just to validate the fingerprint). Build JVMs
  are sized explicitly (Gradle 6g / Kotlin daemon 4g, ParallelGC) and
  configuration-cache-miss configuration runs in parallel. Measured on an
  M4 Pro with warm caches: no-op 439ms, one-file incremental 2s, clean-ish
  `assembleDebug` 39s.
- **GitHub Actions** (`.github/workflows/android-ci.yml`): the serial
  debug+tests+release job is split into parallel `test` and `release` jobs;
  `gradle/actions/setup-gradle` manages dependency + build caches (PRs
  read-only); superseded PR runs are cancelled; debug APK packaging is
  dropped in favor of `checkDebugDuplicateClasses` (the only packaging-tail
  task that can fail when compilation passes).
- **Cloud Build** (`android/cloudbuild.yaml`): the Gradle home (dependencies
  + local build cache + wrapper) persists across release builds via a
  tarball in `gs://health-fitness-160-android-releases/gradle-cache/`.

What remains below is the heavier machinery: each option has real
implementation or migration cost, and several depend on each other. They are
ordered roughly by recommended sequencing.

## 1. Custom Cloud Build builder image (already TODO'd in cloudbuild.yaml)

**What**: bake an image (JDK 21, Android SDK + build-tools, firebase-tools
CLI, `USER root`) once, push to this project's Artifact Registry, and use it
for the `build-release-apk` and `distribute-firebase` steps.

**Why it's faster**: removes the Docker Hub pull of `cimg/android` (~2 GB
image, cross-registry, rate-limit exposed), the `sudo chown -R /workspace`
workaround (which now also traverses the restored multi-GB Gradle cache),
and the per-run `curl -sL https://firebase.tools | bash` install. Same-region
Artifact Registry pulls are fast and never throttled.

**Cost/risk**: low risk, moderate setup. Needs a Dockerfile under `infra/` or
`android/ci/`, a (manual or scheduled) image-build trigger, and a bump
process when SDK/build-tools versions move. The image must track the
`compileSdk`/build-tools the project uses or Gradle will download them into
the cached Gradle home anyway (which dulls the win but breaks nothing).

## 2. Cloud Build private worker pool on a modern machine family

**What**: create a private pool (e.g. `c3d-standard-32`) and point the
android trigger at it. `options.machineType` on the default pool tops out at
`E2_HIGHCPU_32` — E2 is the *oldest, slowest-per-core* family GCP sells; C3D
(Genoa) is roughly 1.5–2× faster per core on compile-shaped work.

**Why it's faster**: the release build is CPU-bound (Kotlin compile, R8,
lint-vital). Faster cores shrink the long pole directly; the cache work in
this branch shrinks everything *except* the long pole.

**Cost/risk**: Terraform for the pool (fits the existing `infra/` layout),
per-minute pricing differences, and one gotcha: private pools run in a
producer VPC — the build needs egress to Docker Hub / Maven Central / Google
(default internet egress is on unless configured otherwise). Try this only if
release wall time still hurts after items 1 and the cache changes land.

## 3. True remote Gradle build cache (shared across laptops + both CI rails)

**What**: replace the two per-rail cache copies (setup-gradle's GitHub cache,
the Cloud Build GCS tarball) with one HTTP build cache every build reads and
CI writes. Options, in increasing capability/cost:

- **`gradle/build-cache-node`** Docker image on a small GCE VM or Cloud Run
  with a persistent volume; wire via `buildCache { remote(HttpBuildCache) }`
  in `settings.gradle.kts`, push=true only in CI.
- **Develocity** (hosted or self-run): remote cache plus build scans,
  per-task timing analytics, and (relevant to item 7) Test Distribution.

**Why it's faster**: a PR branch's first CI run currently compiles everything
its base branch already compiled (PRs are cache-read-only and keyed to
main's seed). A shared remote cache makes "modules you didn't touch" free
*everywhere* — including a fresh worktree on a laptop and the Cloud Build
release build, which today only reuses its own previous release's outputs.

**Cost/risk**: an always-on service with auth (the cache is effectively a
code-execution input — writes must be CI-only, reads can be anonymous or
token-gated), TLS, and disk-eviction settings. This obsoletes the GCS
tarball steps in `cloudbuild.yaml` when it lands. It is the correct endgame;
everything before it is the 80% version.

## 4. Toolchain upgrade: AGP 9.x + Gradle 9.7+ + Kotlin 2.4 + KSP2

**What**: from AGP 8.7.3 / Gradle 8.11 / Kotlin 2.0.21 / KSP1 to the current
line. This is the gateway upgrade — items 5 and 6 want it, and it carries
its own wins: KSP2 (faster Hilt/Room processing), Kotlin 2.x incremental
compilation improvements, AGP 9.3's R8 Configuration Analyzer task (iterate
on shrinker config without the full APK pipeline).

**Cost/risk**: the big one. AGP 9 has **built-in Kotlin support** — the
`org.jetbrains.kotlin.android` plugin applications in `build-logic`
convention plugins and module scripts need reworking (see JetBrains'
AGP 9 migration guide). Watch items: Hilt/KSP2 compatibility pinning,
`:wear`'s alpha Compose artifacts, Robolectric vs. new AGP unit-test
pipeline, and the debug-keystore/signing config assumptions. Budget a full
PR with CI soak, not a version-catalog bump.

## 5. Isolated Projects

**What**: `org.gradle.unsafe.isolated-projects=true` once on Gradle 9.7+ /
AGP 9.x. Projects configure in isolation, so configuration itself
parallelizes and Android Studio sync drops dramatically (Gradle reports up
to ~2.3× faster IDE syncs; their own build dogfoods it).

**Why it matters here**: 13 modules all going through `build-logic`
conventions is exactly the shape this feature rewards. Biggest effect is
felt in Studio sync and cold configuration, which the configuration cache
doesn't help with on a miss.

**Cost/risk**: incubating — recommended for local workflows, not yet for
production artifact builds. Convention plugins must not reach across project
boundaries (`rootProject.`, `allprojects`, cross-project `project(...)`
access at configuration time are violations). `app/build.gradle.kts` reading
`rootProject.layout` for `local.properties` will need the
`isolated.rootProject` API or a settings-level plumb. Adopt as
local/Studio-only first; CI can trail.

## 6. Configuration-cache persistence in GitHub Actions

**What**: setup-gradle's `cache-encryption-key` input (a repo secret) lets
the action cache `.gradle/configuration-cache` between runs (it's encrypted
because config-cache entries can embed secrets — ours embeds the OAuth
client ID via `buildConfigField`).

**Why it's faster**: shaves the whole configuration phase (dozens of seconds
on a 4-vCPU runner for 13 modules + build-logic) off every warm CI run.

**Cost/risk**: small. One secret, one workflow input. Entries invalidate on
any build-script/env change, so the hit rate is best on test-only and
source-only PRs — which is most of them. Cheap enough to do any time; listed
here rather than done because it needs a repo secret created out-of-band.

## 7. Unit-test sharding (when the Robolectric suites become the long pole)

**What**: after this branch, the `test` job's wall time is dominated by
actually running tests (Robolectric Compose suites are JVM-heavy). Options:

- **Matrix sharding**: split `testDebugUnitTest` across N runners by module
  group (e.g. `core-*` vs `feature-*` vs `app`+`wear`). Crude but zero new
  infrastructure; test-report upload needs per-shard artifact names.
- **Develocity Test Distribution**: remote test executors with automatic
  balancing — the polished version, but requires Develocity (item 3).
- **Larger runners**: `runs-on: ubuntu-latest-8-cores` (paid feature, needs
  org enablement) — no workflow surgery, linear-ish speedup for Gradle's
  parallel test execution.

**Cost/risk**: matrix sharding adds workflow complexity and hides
cross-module flakes behind shard boundaries (note: `SyncEnginePullTest`
already flakes under the full suite on android-ci; sharding may mask or
shift that). Wait for post-merge timing data before choosing.

## 8. Housekeeping / known follow-ups

- The Cloud Build cache tarball is a rolling object that only grows (Gradle
  caches are additive). If release builds slow down again, delete
  `gs://health-fitness-160-android-releases/gradle-cache/gradle-home.tgz`
  once to reset, or add a scheduled lifecycle rule / a size check in the
  save step.
- `gradle/actions/setup-gradle` does stale-entry cleanup for the GitHub rail
  automatically; no action needed there.
- If item 3 lands, remove the `restore-gradle-cache`/`save-gradle-cache`
  steps from `cloudbuild.yaml` (keep the *dependency* portion of the Gradle
  home tarball only if cold-start dependency downloads still dominate).

## Suggested sequencing

| Order | Item | Prereq | Effort | Expected win |
|---|---|---|---|---|
| 1 | Custom builder image (§1) | none | S | 1–2 min/release, kills flaky installs |
| 2 | Config-cache in GHA (§6) | repo secret | S | ~30–60s/warm CI run |
| 3 | Toolchain upgrade (§4) | none | L | KSP2 + compiler gains; unlocks §5 |
| 4 | Isolated Projects, local-first (§5) | §4 | M | Studio sync + cold config |
| 5 | Remote build cache (§3) | infra | M–L | unchanged modules free everywhere |
| 6 | Private pool C3D (§2) | Terraform | M | 1.5–2× on the CPU-bound release |
| 7 | Test sharding (§7) | timing data | M | caps the test job's floor |

Measure between steps — several of these attack the same seconds, and
post-change CI timings should decide whether the next one is still worth it.
