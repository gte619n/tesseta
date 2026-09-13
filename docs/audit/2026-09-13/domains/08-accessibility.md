# D8 — Accessibility Audit (A11Y)

Date: 2026-09-13 · Auditor: D8 · Scope: web (Next.js 15 / Tailwind v4), android (Compose M3), wear · Method: static code analysis only (R3); contrast computed mathematically from token hex values.

## Target conformance vs actual

**Declared target: none.** [Certain] No accessibility statement, conformance claim, or WCAG target exists anywhere in the repo — a grep for `accessibility statement|VPAT|WCAG|a11y` across `web/`, `docs/`, `android/` hits only test harness files (`web/test/rtl-harness.test.tsx`, `web/components/ui/ModalBackdrop.test.tsx`), a placeholder comment in `web/e2e/smoke.spec.ts:5` ("Richer auth/a11y specs land in Phase 5"), and design docs. **No VPAT/procurement pressure detected** — solo-founder, personal-use product, no institutional-sales evidence — so VPAT/Section-508 work is skipped entirely; it is not a real requirement here.

**Actual state (measured against WCAG 2.2 AA as the reference bar, per [W3C Understanding SC 1.4.3](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html), fetched 2026-09-13: 4.5:1 normal text, 3:1 large text ≈ ≥24px or ≥18.5px bold):** better than typical for an unaudited solo codebase — the modal primitive is genuinely well-built, every sampled page has `<main>` + `<h1>`, images carry `alt`, Android text is 100% `sp` — but the shared color palette fails AA for its two "muted" text tiers on both platforms, Android has essentially zero explicit semantics (`0` `semantics{}`/`Role.` usages in UI code across 93 `.clickable`s), and charts on Android are invisible to TalkBack.

## BLUF

The single highest-leverage fix is the palette: `tertiary`/`quaternary`/`muted` text tokens fail AA contrast and are used **529 + 13 times on web and 230 + 25 times on Android** — one token change in two files fixes all of it. The second is Android interaction semantics, best fixed in the exact core-ui settings primitives the team just standardized on (SettingsNavRow/ToggleRow/SegmentedChoice), before more screens inherit them. Web is in decent shape structurally (good modal primitive, landmarks, headings, alt coverage, focus-visible styling); its real gaps are the non-responsive dashboard (reflow) and unassociated form labels. For a personal-use product, do-nothing is defensible short-term on everything except the two primitive-level items — those get strictly more expensive with every screen shipped.

---

## Contrast table (computed from `web/app/globals.css:9-45` and `android/core-ui/.../theme/Colors.kt:12-40` — palettes are hex-identical)

| Foreground | Background | Ratio | AA normal (4.5:1) | AA large (3:1) | Usage count |
|---|---|---|---|---|---|
| primary `#1F2419` | canvas `#F0EBE0` | 13.34:1 | PASS | PASS | — |
| primary `#1F2419` | surface `#FFFFFF` | 15.85:1 | PASS | PASS | — |
| inverse `#F0EBE0` | primary `#1F2419` | 13.34:1 | PASS | PASS | — |
| secondary `#6B6856` | surface `#FFFFFF` | 5.62:1 | PASS | PASS | — |
| secondary `#6B6856` | canvas `#F0EBE0` | 4.72:1 | PASS | PASS | — |
| **tertiary `#8A8770`** | surface `#FFFFFF` | **3.63:1** | **FAIL** | PASS | web `text-tertiary` ×529; android `textTertiary` ×230 |
| **tertiary `#8A8770`** | canvas `#F0EBE0` | **3.06:1** | **FAIL** | PASS | (same tokens) |
| **quaternary `#B5B09C`** | surface `#FFFFFF` | **2.18:1** | **FAIL** | **FAIL** | web `text-quaternary`/`text-muted` ×13; android `textQuaternary` ×25 |
| **quaternary `#B5B09C`** | canvas `#F0EBE0` | **1.83:1** | **FAIL** | **FAIL** | (same tokens) |
| accent `#5C7A2E` | surface `#FFFFFF` | 4.91:1 | PASS | PASS | — |
| **accent `#5C7A2E`** | canvas `#F0EBE0` | **4.13:1** | **FAIL** | PASS | link/accent text on canvas pages |
| accent-dim `#3D5A1E` | accent-bg `#E8EBD8` | 6.46:1 | PASS | PASS | — |
| **accent `#5C7A2E`** | accent-bg `#E8EBD8` | **4.05:1** | **FAIL** | PASS | badges/chips |
| **good-alt `#8A9F5C`** | surface `#FFFFFF` | **2.92:1** | **FAIL** | **FAIL** | secondary "good" indicator |
| **warn `#A06A1F`** | warn-bg `#FBE9DA` | **3.88:1** | **FAIL** | PASS | warning badges |
| warn `#A06A1F` | surface `#FFFFFF` | 4.59:1 | PASS | PASS | — |
| alert `#A8473A` | alert-bg `#F7E1DC` | 4.62:1 | PASS | PASS | — |
| alert `#A8473A` | surface `#FFFFFF` | 5.79:1 | PASS | PASS | — |
| neutral `#3B6B8E` | surface `#FFFFFF` | 5.71:1 | PASS | PASS | — |
| border-strong `#DDD3BB` | surface `#FFFFFF` | 1.49:1 | n/a (SC 1.4.11 non-text, 3:1) | **FAIL** as control boundary | segmented-control borders |

Aggravating factor: the failing tokens are overwhelmingly applied at **small sizes** where the large-text exemption cannot rescue them — web has 356 occurrences of `text-[10px]`/`text-[11px]` (e.g. `web/app/me/profile/page.tsx:142` `<h2 className="m-0 caps-mono text-[10px] tracking-[0.08em] text-tertiary">`), and Toast descriptions render at `text-[11px] ... text-tertiary` (`web/components/ui/Toast.tsx:121`).

---

## Findings

### A11Y-001 — Muted text tiers (tertiary/quaternary/muted, good-alt, warn-on-warnBg) fail WCAG AA contrast on both platforms
- **Severity:** High · **Confidence:** [Certain] (ratios computed from source hex; that is the measurement)
- **Evidence:** `web/app/globals.css:21-22` (`--color-tertiary: #8A8770; --color-quaternary: #B5B09C;`), `:32` (`--color-warn: #A06A1F`), `:37` (`--color-muted: #B5B09C`); `android/core-ui/src/main/java/com/gte619n/healthfitness/ui/theme/Colors.kt:23-24,33-35,40` (identical hexes). Counts: `text-tertiary` ×529 and `text-quaternary|text-muted` ×13 in `web/components`+`web/app`; `textTertiary` ×230 and `textQuaternary` ×25 in android UI modules. Ratios in table above (worst: quaternary on canvas 1.83:1).
- **Impact:** Section labels, subtitles, timestamps, toast descriptions, and settings descriptions (e.g. `SettingsComponents.kt:87,119` use `textTertiary` for subtitles) are low-vision-hostile app-wide. This is the largest single AA gap in the product.
- **Effort:** 0.5–1 day. Darken tertiary to ~`#767358` (≥4.6:1 on canvas) and quaternary to ~`#8A8770`-or-darker where it carries information (keep the current values for genuinely decorative uses via a new `decorative` token). Two files fix both platforms; visual QA is the real cost.
- **Autonomy:** High — mechanical token change; needs founder eyeball for brand feel.
- **Null option cost:** Low today (founder has good vision, personal use). But every new screen bakes in more usages; the count grew to 759 combined already. Cheap-now, annoying-later — and it's a rebrand-level diff if done after multi-user launch. Do-nothing is *acceptable* short-term only if A11Y-009's primitive fixes land, since those are the true retrofit traps.
- **Prompt:** "In tesseta, darken `--color-tertiary`/`--color-quaternary`/`--color-muted` in `web/app/globals.css` and the matching `textTertiary`/`textQuaternary`/`muted` in `android/core-ui/.../theme/Colors.kt` so tertiary ≥4.5:1 on `#F0EBE0` and quaternary ≥3:1; also fix `good-alt` on white and `warn` on `warn-bg`. Keep hue; verify with a contrast calculator; screenshot dashboard + settings before/after."

### A11Y-002 — Android: zero explicit semantics; 93 clickables with no role; settings primitives bake the gap into every future screen
- **Severity:** High (for TalkBack users; the product logs meds and workouts — planned multi-user) · **Confidence:** [Certain] for the code facts; [Likely] for the exact TalkBack experience (verify per HYPOTHESES)
- **Evidence:** grep across android UI modules: `.clickable(` ×93 in feature-nutrition/feature-workouts/app with **0** `role =` arguments; the only two `semantics`/`Role.` hits in the whole android tree are comments (`core-data/.../AdherenceRepository.kt:94`, `feature-body-composition/.../EditableNumberCell.kt:11`). In the just-standardized primitives `android/core-ui/src/main/java/com/gte619n/healthfitness/ui/components/SettingsComponents.kt`: `SettingsNavRow` (line 70) uses bare `.clickable(onClick = onClick)` (line 79) with a literal chevron `Text("›", …)` (line 90) that TalkBack will read aloud; `SettingsToggleRow` (line 100) puts the `Switch` (line 123) in a separate node from its label with no row-level `Modifier.toggleable(role = Role.Switch)` — the touch/focus target is only the switch and the label is a disconnected text node; `SegmentedChoice` (line 158) is clickable `Text`s with **no selected-state semantics** (no `selectable`/`Role.RadioButton`), so TalkBack cannot announce which unit/sex option is active — selection is conveyed by color alone (and selected `capsSm` inverse-on-accent is 4.13:1, small-text FAIL).
- **Impact:** Screen-reader users get anonymous "double-tap to activate" targets, unlabeled toggles, unreadable segmented controls. Because settings screens now compose exclusively from these primitives (per settings-ux refactor), every new settings surface inherits the gap.
- **Effort:** 1 day for the three primitives (+ `InlineLoading` live-region); 2–3 days to sweep the 93 raw `.clickable`s (most just need `role = Role.Button` and `onClickLabel`).
- **Autonomy:** High — mechanical Compose changes with well-documented patterns.
- **Null option cost:** This is the flagship "do it in the primitives now" item. Fixing 4 composables today fixes all current and future settings screens; retrofitting after 20 more screens ship means auditing each screen. Do-nothing cost compounds fastest of any finding here.
- **Prompt:** "In `android/core-ui/.../components/SettingsComponents.kt`: (1) `SettingsNavRow` → `.clickable(role = Role.Button, onClick = onClick)` and mark the `›` chevron `Modifier.clearAndSetSemantics {}`; (2) `SettingsToggleRow` → move interaction to the Row via `Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)`, pass `onCheckedChange = null` to the Switch, merge descendants; (3) `SegmentedChoice` → wrap options in `Modifier.selectable(selected = isSelected, role = Role.RadioButton)` inside a `selectableGroup()`. Then sweep `.clickable(` call sites in feature modules adding `role`/`onClickLabel`."

### A11Y-003 — Android Canvas charts have no text alternative (invisible to TalkBack)
- **Severity:** Medium · **Confidence:** [Certain]
- **Evidence:** `android/feature-body-composition/.../overview/WeightTrendChart.kt:126` (`Canvas(` with no `semantics`/`contentDescription` anywhere in file), `android/app/.../mobile/dashboard/WeightChart.kt:56` (`Canvas(modifier = Modifier.fillMaxWidth().height(140.dp))`, same), plus chart drawing in `dashboard/Components.kt` and `dashboard/TodayCard.kt`. Zero `contentDescription`/`semantics` in any of the four files' chart code.
- **Impact:** Weight trend and dashboard charts are simply absent for TalkBack users — no name, no value, no summary.
- **Effort:** 0.5 day. Minimal fix: `Modifier.semantics { contentDescription = "Weight trend, last 30 days: 82.1 to 80.4 kg, down 1.7" }` built from data already in scope. Not per-point interactivity — just a sentence.
- **Autonomy:** High.
- **Null option cost:** Low now; linear growth (each new chart repeats the omission). Contrast with web, which already shows the correct pattern — `web/components/dashboard/WeightChart.tsx:73-74` has `role="img"` + `aria-label={chartLabel}`; copy that convention.
- **Prompt:** "Add `Modifier.semantics { contentDescription = <one-sentence data summary> }` to the Canvas charts in `WeightTrendChart.kt`, `app/.../dashboard/WeightChart.kt`, `Components.kt`, `TodayCard.kt`, mirroring the aria-label pattern in `web/components/dashboard/WeightChart.tsx:73`."

### A11Y-004 — Web dashboard has zero responsive breakpoints: fails reflow (SC 1.4.10) at 200% zoom / narrow viewports
- **Severity:** Medium · **Confidence:** [Certain] for the static facts; [Likely] for exact breakage mode (verify per HYPOTHESES)
- **Evidence:** `web/app/page.tsx:43` `grid w-[1200px] max-w-full grid-cols-[220px_1fr] overflow-hidden` and `:50` `grid grid-cols-5 gap-2.5`; grep for `md:|sm:|lg:` returns **0** in `app/page.tsx`, `components/dashboard/Sidebar.tsx`, and `app/me/nutrition/page.tsx`. `overflow-hidden` on both the shell (`:43`) and `<main>` (`:47`) clips rather than scrolls.
- **Impact:** At 400% zoom (≈320px effective width, the SC 1.4.10 test), a 5-column stat grid plus a fixed 220px sidebar column cannot reflow; `max-w-full` shrinks the grid but the columns compress to unusable slivers and `overflow-hidden` hides the rest. Also plain mobile-browser usability, which matters more commercially than the SC.
- **Effort:** 1–2 days for the dashboard shell (collapse sidebar, `grid-cols-2 md:grid-cols-5`); the `me/*` pages are single-column `max-w` containers and mostly fine.
- **Autonomy:** Medium — layout decisions need taste.
- **Null option cost:** Genuinely low today: this is a desktop companion to the Android app for a single user. Reasonable to defer until web is a first-class surface — it is a page-level fix, not a primitive-level trap.
- **Prompt:** "Make `web/app/page.tsx` dashboard responsive: stat `<section>` `grid-cols-2 md:grid-cols-5`, shell `grid-cols-1 md:grid-cols-[220px_1fr]` with sidebar collapsing, remove/relax the two `overflow-hidden`s so 320px-wide rendering scrolls vertically only. Test at 400% browser zoom."

### A11Y-005 — Web form labels not programmatically associated in the nutrition target form (and only 12 `htmlFor` app-wide)
- **Severity:** Medium-Low · **Confidence:** [Certain]
- **Evidence:** `web/components/nutrition/TargetForm.tsx:160-163`: `<label className="mb-1 block text-[11px] font-medium text-secondary">{label} ({unit})</label>` is a *sibling* of the `<input>` (line 163) with no `htmlFor`/`id` — same pattern for the calories input at line 129-135. Repo-wide there are only 12 `htmlFor` usages, all in gym/admin equipment forms (`components/gym/EquipmentSpecsForm.tsx:35-169`, `components/gym/CategorySelector.tsx:42,62`). Counter-example done right: `web/components/profile/BodyDetailsForm.tsx:43,58` wraps inputs inside `<label>` (implicit association — fine).
- **Impact:** Screen readers announce the macro inputs as unlabeled number fields (placeholder "e.g. 180" is not a name); clicking a label doesn't focus its field.
- **Effort:** 0.25 day for TargetForm; 0.5 day to sweep `AddFoodModal.tsx` / `EditEntryModal.tsx` / `AdjustWithAi.tsx` for the same pattern.
- **Autonomy:** High.
- **Null option cost:** Low, but this is the kind of thing worth a lint rule (`jsx-a11y/label-has-associated-control`) so it stops recurring — the rule costs an hour once.
- **Prompt:** "In `web/components/nutrition/TargetForm.tsx` associate each `<label>` with its input via `htmlFor`/`id` (key off the macro `key`), same for the calories field; audit AddFoodModal/EditEntryModal/AdjustWithAi for sibling-label-without-htmlFor; enable `eslint-plugin-jsx-a11y` label rule."

### A11Y-006 — Toasts: error toasts use polite `role="status"` and auto-dismiss in 4s
- **Severity:** Low · **Confidence:** [Certain] for code; [Likely] for announcement reliability
- **Evidence:** `web/components/ui/Toast.tsx:108` — every toast renders `role="status"` regardless of kind; `:57-59` — default `duration ?? 4000` then removal. Errors (`toast.error`) therefore get polite, interruptible announcement and vanish in 4s (dismiss button `aria-label="Dismiss"` at `:130` is good). Android side: `core-ui/.../snackbar/SnackbarController.kt` uses Material snackbar (platform-handled, fine).
- **Impact:** Screen-reader users can miss failure feedback entirely (announcement queued behind other speech, gone before focus reaches it).
- **Effort:** 0.25 day: `role={kind === "error" ? "alert" : "status"}` and default error duration to sticky or ≥8s.
- **Autonomy:** High.
- **Null option cost:** Near-zero today; trivial fix in one primitive — cheap insurance.
- **Prompt:** "In `web/components/ui/Toast.tsx`, render `role=\"alert\"` for error toasts and lengthen/stick error duration; keep `role=\"status\"` for success/info."

### A11Y-007 — No reduced-motion handling on either platform
- **Severity:** Low · **Confidence:** [Certain] (absence verified)
- **Evidence:** web: `motion-reduce|prefers-reduced-motion` → **0** hits vs 103 `animate-*`/`transition` usages (e.g. skeleton `animate-pulse` at `web/app/page.tsx:195,213`). Android: `LocalAccessibilityManager|areAnimationsEnabled|ANIMATOR_DURATION` → **0** hits; ~12 files use Compose `animate*` APIs (notably `WorkoutSessionScreen.kt` ×5), which do **not** respect the system animator-duration-scale setting.
- **Impact:** Vestibular-disorder users can't suppress motion. Current animations are mild (pulses, transitions), so real-world harm is small.
- **Effort:** 0.25 day web (add `motion-reduce:animate-none` where `animate-` appears, or a global `@media (prefers-reduced-motion)` rule in `globals.css` — one place). Android: 0.5 day for a `rememberReducedMotion()` helper in core-ui.
- **Autonomy:** High.
- **Null option cost:** Near-zero; the global-CSS variant is a 10-line one-time fix, so do-nothing saves almost nothing.
- **Prompt:** "Add a `@media (prefers-reduced-motion: reduce)` block to `web/app/globals.css` disabling animations/transitions; optionally add a Compose reduced-motion helper reading `Settings.Global.ANIMATOR_DURATION_SCALE` in core-ui."

### A11Y-008 — Undersized visual touch targets: 36dp IconButtons in WorkoutSessionScreen
- **Severity:** Low · **Confidence:** [Likely] mitigated — M3 `IconButton` expands the *touch* target to 48dp via minimum-interactive-size enforcement unless disabled, and grep for `LocalMinimumInteractive|minimumInteractiveComponentSize` → 0 hits (not disabled). Visual target is still 36dp.
- **Evidence:** `android/feature-workouts/.../session/WorkoutSessionScreen.kt:1615` and `:1832`: `IconButton(onClick = onUndo, modifier = Modifier.size(36.dp))`. These are the undo buttons in the active-workout logging flow — used mid-set, sweaty hands, worst-case motor conditions.
- **Impact:** Minor if enforcement holds (touch area 48dp, visual 36dp); worth one manual check because these two are the highest-stakes touch targets in the app.
- **Effort:** 0.1 day.
- **Autonomy:** High.
- **Null option cost:** Zero if HYPOTHESIS H4 confirms enforcement; otherwise trivial fix.
- **Prompt:** "Verify the two `Modifier.size(36.dp)` IconButtons in WorkoutSessionScreen (lines ~1615, ~1832) still receive the 48dp minimum touch target (M3 enforcement); if the modifier constrains touch bounds, bump to 48.dp with 36.dp icon."

### A11Y-009 — Web landmark gaps: sidebar nav is not a `<nav>`; some decorative SVG charts hidden with no adjacent text alternative
- **Severity:** Low · **Confidence:** [Certain] for the sidebar; [Likely] for the chart-alternative adequacy (needs per-chart judgment)
- **Evidence:** `web/components/dashboard/Sidebar.tsx` maps `navItems` (line 53) with no `<nav>` element (repo `<nav>` grep hits only goals tabs `app/me/goals/page.tsx:68`, `app/me/nutrition/history/page.tsx`, and three admin sub-navs). Chart alternatives: `WeightChart.tsx:73-74` is the good pattern (`role="img"` + `aria-label`); `Sparkline.tsx` `aria-hidden` (fine — adjacent number in StatCard); but `BloodPanel.tsx:47` hides its chart (`aria-hidden="true"`) — acceptable only if the surrounding markup exposes the same values as text, which it partially does (marker rows) — spot-verify.
- **Impact:** Screen-reader users lose the "navigation" landmark shortcut on every dashboard visit; minor.
- **Effort:** 0.25 day (wrap sidebar list in `<nav aria-label="Primary">`; audit the 14 SVG-bearing components against the WeightChart pattern — 3 of 14 currently carry any `role`/`aria`).
- **Autonomy:** High.
- **Null option cost:** Near-zero; trivial.
- **Prompt:** "Wrap the nav-item list in `web/components/dashboard/Sidebar.tsx` in `<nav aria-label=\"Primary\">`; audit the 14 components containing `<svg>` so each is either `aria-hidden` with equivalent adjacent text or `role=\"img\"`+`aria-label` like WeightChart."

---

## What is already good (credit where due, and why no criticals)

- **ModalBackdrop is a model primitive** [Certain]: `web/components/ui/ModalBackdrop.tsx:124-128` (`role="dialog"`, `aria-modal="true"`, `aria-labelledby`/`aria-label`), Escape close (`:70`), a real focus trap (`:75-94`), initial focus (`:65-66`), and focus restore (`:100`) — with a unit test. Every modal inherits this.
- **Semantic page skeletons** [Certain]: all 5 sampled pages have `<main>` + a single `<h1>` (`app/page.tsx:47,263`; `app/me/nutrition/page.tsx:262,275`; `app/me/goals/page.tsx:33,44`; `app/me/profile/page.tsx:126,136`; `app/me/workouts/preferences/page.tsx:27,37`) with `<h2>` sections and no observed level-skipping.
- **Only 1 div-with-onClick in the entire web tree** (`components/admin/ExerciseGridView.tsx`) — buttons are buttons.
- **Image alt coverage is effectively 100%**: all 41 `<img>`/`<Image>` usages carry `alt` (many deliberately `alt=""` for decorative duplicates — correct usage); decorative icon font `<i>` elements carry `aria-hidden` (`Sidebar.tsx:74,94`).
- **74 `aria-label`s** across web; 427 `focus:`/`focus-visible` usages and only 3 `outline-none` without a focus replacement.
- **Android type is 100% scalable**: 0 `fontSize = N.dp` violations, 135 `.sp` usages — dynamic type works.
- **Android decorative icons pattern is mostly right**: the ~62 `contentDescription = null` icons sampled sit next to visible text (e.g. `GymDetailScreen.kt:141` `Icon(Icons.Filled.Add, contentDescription = null)` inside a button whose `Text("  Add equipment")` provides the name); a 6-line-window grep found **0** icon-only `IconButton`s with a null description.

This is why there are no critical findings (R5): stakes are real but bounded — a single sighted user today, no legal exposure, and the worst gaps (contrast, Compose semantics) are token/primitive-level fixes, not architectural.

## Wear (one paragraph, per brief)

[Certain] The wear module is 5 Kotlin files and its UI surface is two static `Text` composables (`wear/src/main/java/com/gte619n/healthfitness/wear/MainActivity.kt:41-42`) plus a sign-in screen — there are zero `Icon`/`Image` calls and nothing interactive beyond platform defaults. Wear OS accessibility is dominated by platform behavior (rotary input, screen-reader defaults on Wear Material components), and at this UI density there is nothing actionable to audit. N/A by platform reality; revisit only if the watch app grows real interaction.

## The leverage argument: 5 cheapest-per-value fixes vs expensive retrofits

**Do these five now (all primitive/token-level; ~2.5 days total, fix every current and future screen):**
1. **Palette tokens** (A11Y-001) — 2 files, both platforms, 800+ usages fixed at once.
2. **SettingsComponents.kt semantics** (A11Y-002 part 1) — 3 composables; the settings-ux refactor made these the substrate of all settings UI, so this is the highest retrofit-avoidance per line changed.
3. **Toast error role** (A11Y-006) — 1 line in 1 primitive.
4. **Sidebar `<nav>`** (A11Y-009) — 1 element.
5. **Global reduced-motion CSS** (A11Y-007 web half) — 10 lines in `globals.css`.

**Gets expensive later (retrofit-shaped):** the 93 raw `.clickable` sweeps (grows with every screen), chart text alternatives (each new chart), and dashboard reflow (whole-layout rework once more cards land). **Safe to defer indefinitely at current scale:** reflow (A11Y-004) and per-chart Android semantics beyond the two weight charts — do-nothing is genuinely correct there until web/multi-user matters, and they are page-local, not primitive-contagious.

## HYPOTHESES (require a running app / screen reader — exact manual tests)

- **H1 — TalkBack traversal order in WorkoutSessionScreen** [Guessing — 1900-line screen with overlays, banners, matrix pickers]. Test: enable TalkBack, open an active session, swipe-right through the whole screen; verify order is header → current exercise → set rows → rest overlay, that the rest-timer overlay (singleton-flow-driven, per rest-timer history) announces its countdown updates via a live region rather than never/every-tick, and that `ResumeSessionBanner`/`ParkedSessionBanner` are reachable and announce their action.
- **H2 — Toast announcement actually fires** [Likely it does — `role="status"` on a newly inserted node is announced by NVDA/VoiceOver in most engine versions, but insertion-with-role is the flakiest live-region pattern]. Test: with VoiceOver + Safari and NVDA + Chrome, trigger `toast.success` and `toast.error` from the nutrition target save; confirm both are spoken without focus moving. If silent, mount a persistent `aria-live` container and inject text into it.
- **H3 — Focus trap vs. portalled/nested content** [Likely fine]. Test: open a modal containing a select and a date input, Tab from the last control (must wrap to first), Shift+Tab from first (must wrap to last), press Escape mid-text-selection (must close and restore focus to the trigger button).
- **H4 — 36dp IconButton effective touch target** [Likely 48dp]. Test: enable "Pointer location" in Android developer options, tap 6dp outside the visual undo button in an active workout; if the tap doesn't register, A11Y-008 upgrades to a real fix.
- **H5 — SettingsToggleRow TalkBack reading** [Likely broken as described]. Test: TalkBack on Settings; verify whether swiping reaches the label and switch as separate nodes and whether the switch announces its label or just "switch, on". Confirms A11Y-002's severity.
- **H6 — 200%/400% browser zoom on dashboard** [Likely clipped]. Test: Chrome, 1280px window, zoom 400%; verify no horizontal scrolling is required to read stat cards and nothing is clipped by the two `overflow-hidden` wrappers (`app/page.tsx:43,47`).
- **H7 — BloodPanel hidden chart has equivalent text** [Likely partial]. Test: VoiceOver over the dashboard blood panel; confirm every marker value drawn in the `aria-hidden` SVG is also announced from the visible marker rows.

```json
[
  {"id":"A11Y-001","title":"Muted text tokens (tertiary/quaternary/muted, good-alt, warn-on-warnBg) fail WCAG AA contrast on web and Android","severity":"high","confidence":"certain","effort_days":1,"autonomy":"high","files":["web/app/globals.css:21","web/app/globals.css:22","android/core-ui/src/main/java/com/gte619n/healthfitness/ui/theme/Colors.kt:23"],"counts":{"web_text_tertiary":529,"web_quaternary_muted":13,"android_textTertiary":230,"android_textQuaternary":25},"worst_ratio":"1.83:1 (quaternary on canvas)","null_option_cost":"low now; palette-wide retrofit after more UI ships"},
  {"id":"A11Y-002","title":"Android: zero explicit semantics; 93 role-less clickables; settings primitives (NavRow/ToggleRow/SegmentedChoice) propagate the gap","severity":"high","confidence":"certain","effort_days":3,"autonomy":"high","files":["android/core-ui/src/main/java/com/gte619n/healthfitness/ui/components/SettingsComponents.kt:79","android/core-ui/src/main/java/com/gte619n/healthfitness/ui/components/SettingsComponents.kt:123","android/core-ui/src/main/java/com/gte619n/healthfitness/ui/components/SettingsComponents.kt:158"],"counts":{"clickable_no_role":93,"semantics_usages_in_ui":0},"null_option_cost":"compounds fastest — fix 3 primitives now or audit every screen later"},
  {"id":"A11Y-003","title":"Android Canvas charts have no text alternative (invisible to TalkBack)","severity":"medium","confidence":"certain","effort_days":0.5,"autonomy":"high","files":["android/feature-body-composition/src/main/java/com/gte619n/healthfitness/feature/bodycomposition/overview/WeightTrendChart.kt:126","android/app/src/main/java/com/gte619n/healthfitness/mobile/dashboard/WeightChart.kt:56"],"null_option_cost":"low; grows per chart; web WeightChart.tsx:73 shows correct pattern"},
  {"id":"A11Y-004","title":"Web dashboard has zero responsive breakpoints; fails reflow (SC 1.4.10) at 200-400% zoom","severity":"medium","confidence":"certain","effort_days":2,"autonomy":"medium","files":["web/app/page.tsx:43","web/app/page.tsx:50"],"counts":{"responsive_prefixes_in_dashboard":0},"null_option_cost":"genuinely low today (desktop companion, single user); defer is defensible"},
  {"id":"A11Y-005","title":"Nutrition target form labels not associated (sibling label, no htmlFor); only 12 htmlFor app-wide","severity":"medium-low","confidence":"certain","effort_days":0.5,"autonomy":"high","files":["web/components/nutrition/TargetForm.tsx:160","web/components/nutrition/TargetForm.tsx:129"],"null_option_cost":"low; add jsx-a11y lint rule to stop recurrence"},
  {"id":"A11Y-006","title":"Error toasts use polite role=status and auto-dismiss in 4s","severity":"low","confidence":"certain","effort_days":0.25,"autonomy":"high","files":["web/components/ui/Toast.tsx:108","web/components/ui/Toast.tsx:57"],"null_option_cost":"near-zero; one-line primitive fix"},
  {"id":"A11Y-007","title":"No reduced-motion handling on either platform (0 prefers-reduced-motion vs 103 web animations; Compose ignores animator scale)","severity":"low","confidence":"certain","effort_days":0.75,"autonomy":"high","files":["web/app/globals.css","android/feature-workouts/src/main/java/com/gte619n/healthfitness/feature/workouts/session/WorkoutSessionScreen.kt"],"null_option_cost":"near-zero; 10-line global CSS fix available"},
  {"id":"A11Y-008","title":"36dp IconButton undo targets in active-workout screen (visual below 48dp; touch likely expanded by M3)","severity":"low","confidence":"likely","effort_days":0.1,"autonomy":"high","files":["android/feature-workouts/src/main/java/com/gte619n/healthfitness/feature/workouts/session/WorkoutSessionScreen.kt:1615","android/feature-workouts/src/main/java/com/gte619n/healthfitness/feature/workouts/session/WorkoutSessionScreen.kt:1832"],"null_option_cost":"zero if H4 confirms enforcement"},
  {"id":"A11Y-009","title":"Sidebar nav lacks <nav> landmark; 11 of 14 SVG components carry no role/aria (some legitimately decorative)","severity":"low","confidence":"certain","effort_days":0.25,"autonomy":"high","files":["web/components/dashboard/Sidebar.tsx:53","web/components/dashboard/BloodPanel.tsx:47"],"counts":{"svg_components":14,"with_role_or_aria":3},"null_option_cost":"near-zero"}
]
```
