# Android App Functions — Gemini Voice Meal Logging

**Date:** 2026-06-22
**Status:** Researched / planned — **blocked on Google's private preview** (see Limitations §6). The Android-side implementation (Phase 1) is buildable and locally verifiable today; Gemini actually invoking the function (Phase 2) is gated behind an Early Access Program.
**Owner:** evan.ruff
**Related:** ADR-0011 (describe-a-meal saved-meal catalog), IMPL-13 (multi-tenant nutrition), `feature-nutrition`, `core-data/nutrition`

---

## 1. Goal

Let a user say something to Gemini on their Android phone — e.g. *"I just ate some string cheese"* — and have it flow into their Tesseta nutrition log, reusing the existing server-side describe-meal pipeline (free text → itemized meal + macros + generated image).

The intelligence already exists in the backend (`POST /api/me/nutrition/{date}/describe-meal-async`, Gemini-backed extraction). The missing piece is a **phone-side trigger** that the system assistant can reach. **Android App Functions** is the only Google-sanctioned mechanism that connects the system Gemini assistant to a third-party app's actions.

## 2. Why App Functions (and what it is)

App Functions (`androidx.appfunctions`) is an Android 16 platform API + Jetpack library. An app declares annotated functions; the OS indexes them into a schema; authorized agents (Gemini, proactive system features) discover and invoke them **on-device**. Google's own framing: *"every Android app becomes an on-device MCP server."* It is the mobile equivalent of an MCP tool, but native to Android and reachable by the system assistant — which a standalone MCP server is **not** (the consumer Gemini app does not let users plug in arbitrary MCP servers).

### Alternatives considered
| Path | Hands-free wake word? | Available now? | Notes |
|---|---|---|---|
| **In-app voice button** → `describe-meal` | No (open app, tap, speak) | ✅ Yes | Lowest effort; recommended as the interim daily-utility option |
| **MCP server** over the REST API | No (desktop/CLI agents only) | ✅ Yes | Useful for Claude/Gemini-CLI/custom agents; does **not** reach the phone assistant |
| **App Functions** (this doc) | Maybe, eventually | ❌ Preview-gated | Only sanctioned route to the system Gemini assistant |

## 3. How it fits Tesseta

The Android app is well-positioned:

- **Stack:** Kotlin + Jetpack Compose + Hilt + Retrofit/OkHttp/Moshi — App Functions' DI story is literally Hilt.
- **Backend brain already shipped:** `NutritionRepository.describeMealAsync(date, description, meal)` → `POST /api/me/nutrition/{date}/describe-meal-async` returns a fast `202` ANALYZING placeholder and resolves server-side. Ideal for a voice command (fast spoken confirmation, no wait on AI image generation).
- **Auth is free:** repository calls pass through `AuthInterceptor`, which injects the bearer token automatically. A signed-in user authenticates transparently; a guard on `IdTokenCache` produces a clean spoken error when signed out.
- **Non-destructive:** logging a meal needs no multi-step confirmation per Google's guidance.

### Gap to close before it compiles
- **`compileSdk` 35 → 36** (App Functions requires it). `minSdk` stays 29 — the library no-ops below Android 16.

## 4. Implementation plan

### Phase 1 — Build & locally verify (doable today, no Gemini access needed)

1. **Version catalog** (`android/gradle/libs.versions.toml`): add `appfunctions`, `appfunctions-service`, `appfunctions-compiler` (currently `1.0.0-alpha09` — **alpha, expect API churn**). KSP is already on the project.
2. **`compileSdk = 36`** in `android/app/build.gradle.kts` (and any module setting it explicitly). Budget a build-fix pass for new lint/deprecation warnings.
3. **Home the function in `:feature-nutrition`** (already depends on the nutrition data layer): add the three deps + `ksp(appfunctions-compiler)`. In the **app module**, add once: `ksp { arg("appfunctions:aggregateAppFunctions", "true") }`.
4. **New `NutritionFunctions.kt`** — a thin shim over `describeMealAsync`:

   ```kotlin
   class NutritionFunctions @Inject constructor(
       private val repo: NutritionRepository,
       private val idTokenCache: IdTokenCache,
       private val clock: Clock,
   ) {
       @AppFunctionSerializable(isDescribedByKDoc = true)
       data class LoggedMeal(
           /** The meal as logged, e.g. "String Cheese". */
           val name: String,
           /** Which meal it was filed under: breakfast, lunch, dinner, or snack. */
           val mealType: String,
       )

       /**
        * Logs food the user describes in natural language onto today's nutrition log.
        * Use when the user says they ate or drank something, e.g. "I had a string cheese".
        *
        * @param description Free text of what the user ate, e.g. "some string cheese".
        */
       @AppFunction(isDescribedByKDoc = true)
       suspend fun logMeal(
           appFunctionContext: AppFunctionContext,
           description: String,
       ): LoggedMeal = withContext(Dispatchers.IO) {
           if (idTokenCache.read().idToken.isNullOrBlank()) {
               throw AppFunctionInvalidArgumentException(
                   "Not signed in to Tesseta — open the app and sign in first.")
           }
           val today = clock.todayLocalIso()
           val mealType = mealTypeForTime(clock.now()) // breakfast/lunch/dinner/snack
           val entry = repo.describeMealAsync(today, description, mealType)
           LoggedMeal(name = entry.name, mealType = mealType)
       }
   }
   ```

   - `mealTypeForTime`: small local helper (e.g. <11:00 breakfast, <16:00 lunch, <21:00 dinner, else snack) — matches the backend's existing time-of-day reasoning for recent meals.
   - The KDoc **is** the tool description the agent reads; wording materially affects trigger quality.

5. **Register the factory** in `app/.../mobile/HealthFitnessApp.kt`. It already implements `ImageLoaderFactory, Configuration.Provider`; add `AppFunctionConfiguration.Provider`:

   ```kotlin
   override val appFunctionConfiguration: AppFunctionConfiguration
       get() = AppFunctionConfiguration.Builder()
           .addEnclosingClassFactory(NutritionFunctions::class.java) { nutritionFunctions }
           .build()
   ```

6. **Verify locally** (Android 16 device/emulator, no Gemini):
   ```bash
   adb shell cmd app_function list-app-functions | grep -A 10 com.gte619n.healthfitness
   adb shell cmd app_function execute --package com.gte619n.healthfitness --function <id> ...
   ```
   Confirms the function is indexed and a synthetic invocation creates an ANALYZING entry that resolves in-app — proving the full path except the Gemini front-end.

### Phase 2 — Gemini enablement (gated)
- Submit the **EAP registration** (https://forms.gle/GN5ybjQFhzHRCguM7). Until admitted, Gemini will not call the function even though it is indexed.
- Tune the KDoc; use Google's **AppFunctions agent skill** (codegen + KDoc optimization + adb test commands).
- Confirm whether hands-free wake-word invocation is actually supported on the test device or only in-app Gemini (see §6).

### Suggested commit slicing
1. Build plumbing (catalog + `compileSdk 36` + KSP arg) — verify everything still builds.
2. `NutritionFunctions` + Application provider.
3. adb verification notes.

### Out of scope for v1
A richer-confirmation variant (returns macros), and read functions (`whatDidIEatToday` over `getDay`). Start with one write function per Google's "narrow access" guidance.

## 5. Application ID / packages (reference)
- Application ID: `com.gte619n.healthfitness`
- App module namespace: `com.gte619n.healthfitness.mobile`
- Current: `compileSdk 35`, `targetSdk 35`, `minSdk 29`

## 6. Current limitations (as of 2026-06-22)

These are the reasons this is **planned, not shippable today**. Re-validate before starting — this is a fast-moving, alpha-stage area.

1. **Gemini invocation is private preview / trusted-testers only.** As of May 2026, only Google/OEM default apps (Calendar, Notes, Tasks, Samsung Gallery) are wired to Gemini via App Functions. Third-party access is via an Early Access Program with **no guarantee of admission**. You can build and `adb`-test the function now, but Gemini will not call it until admitted.
2. **No general-availability date.** Google has committed only to "share more details later this year," with broader reach slated for **Android 17**. Timeline is uncertain.
3. **Hands-free wake-word UX is unconfirmed.** Every documented example shows a user already interacting with Gemini ("ask Gemini to…"). The experience is described as "multimodal, voice or text," but the specific *"Hey Gemini" with the phone on the table, hands-free* flow this idea is built around is **not demonstrated and not promised**. It may require opening/engaging the Gemini app first.
4. **Device floor is Android 16+.** Marquee examples cite Galaxy S26 / One UI 8.5. Older devices simply will not see the function. Our `minSdk 29` users get no regression (library no-ops), but only a small slice of devices can use the feature.
5. **`compileSdk 36` is required** — a build-config change that touches all modules and may surface new lint/deprecation issues.
6. **The library is alpha** (`1.0.0-alpha09` at time of writing). API surface is explicitly "subject to change." Pin the version and expect churn.
7. **Server-side processing caveat.** Agents may process the query on Google servers with advanced LLMs before invoking the function. Only expose non-sensitive, natural-language-friendly actions (meal logging qualifies; avoid exposing sensitive reads in v1).

## 7. Recommendation

Build **Phase 1 now** so the function is indexed and adb-verifiable, **register for the EAP**, and bump `compileSdk` to 36 on the next build touch so the rest drops in the moment access lands. For immediate daily utility independent of Google's timeline, ship the **in-app voice button** (Android speech-to-text → `describe-meal`) as a parallel, un-gated path.

## 8. References (researched 2026-06-22)

- [Overview of AppFunctions — Android Developers](https://developer.android.com/ai/appfunctions)
- [Add the AppFunctions API to your app — Android Developers](https://developer.android.com/ai/appfunctions/add-appfunctions)
- [appfunctions Jetpack release notes](https://developer.android.com/jetpack/androidx/releases/appfunctions)
- [Google details 'AppFunctions' that let Gemini use Android apps — 9to5Google, 2026-02-25](https://9to5google.com/2026/02/25/android-appfunctions-gemini/)
- [The Future of Android Apps with AppFunctions — Shreyas Patil](https://blog.shreyaspatil.dev/the-future-of-android-apps-with-appfunctions/)
- [Android AppFunctions: Every Android App Is Now an MCP Server — byteiota](https://byteiota.com/android-appfunctions-on-device-mcp-server/)
- EAP registration form: https://forms.gle/GN5ybjQFhzHRCguM7
