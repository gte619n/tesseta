# ADR-0006: Seed the food catalog from USDA (CC0) + Open Food Facts (ODbL)

- Status: Accepted
- Date: 2026-05-30

## Context

[IMPL-13](../specs/IMPL-13-nutrition-tracking.md) introduces a **shared,
server-owned food catalog** so that a food the system has seen once never has
to be re-derived by Gemini. The catalog needs two kinds of data:

- **Generic / whole foods** ("chicken breast", "brown rice, cooked") for
  manual logging and as the components a meal photo decomposes into.
- **Packaged / barcoded products** so a barcode scan or a nutrition-label
  photo resolves to a real product with accurate macros.

Building this by hand, or by paying Gemini for every first lookup, defeats the
stated goal of cutting AI calls. We want to **bulk-seed** the catalog from free
data. The research surveyed the realistic sources:

| Source | Covers | License | Bulk dump | Verdict |
|---|---|---|---|---|
| **USDA FoodData Central** (Foundation, SR Legacy, FNDDS, Branded) | whole/generic foods (excellent), US branded (ok) | **CC0 / public domain** | yes (CSV/JSON) | **seed — generics** |
| **Open Food Facts** | 3M+ barcoded packaged products, 150+ countries | **ODbL** (attribution + share-alike) | yes (JSONL/Parquet/Mongo, daily) | **seed — packaged** |
| FatSecret / Nutritionix / Edamam / CalorieNinjas | branded + generic | proprietary, no redistribution | no | rejected |

The proprietary APIs are rent-not-own: their terms forbid extracting data into
our own database, and most are paid at any real volume. They are out.

That leaves USDA and Open Food Facts. USDA is the cleanest license possible
(CC0 — no obligations) but is thin on the long tail of barcoded retail
products. Open Food Facts is the best free barcode database in existence, but
it is **ODbL**, a copyleft/share-alike license, which carries obligations we
must design around rather than discover later.

### What ODbL actually requires

ODbL share-alike attaches when you **publicly distribute the database or a
derived database** — not when you use it internally to answer queries. Two
practical readings for our architecture:

- A backend that queries its own Open Food Facts–seeded store and returns
  **individual product results** to our own app clients is normal API reuse:
  it requires **attribution**, not publication of our whole database.
- If we ever **ship the combined database itself** — e.g. bundle it into the
  Android APK for offline lookup, or expose a bulk export endpoint — the
  derived database becomes a "Derivative Database" and share-alike requires we
  release it under ODbL. At that point Open Food Facts rows and our
  proprietary / USDA-mixed rows are legally entangled unless we kept them
  separable.

## Decision

**Seed the shared food catalog from both USDA FoodData Central (generic foods)
and Open Food Facts (packaged/barcoded products), and engineer for ODbL
isolation from day one.**

1. **USDA, generics.** Bulk-import Foundation Foods + SR Legacy (optionally
   FNDDS for mixed dishes) from the CSV/JSON download. These rows are CC0 — no
   obligations. Tag every row `source: USDA`.

2. **Open Food Facts, packaged.** Bulk-import from the JSONL/Parquet daily
   dump, filtered to products with a populated `nutriments` block. Key each by
   barcode (GTIN/EAN/UPC). Tag every row `source: OPEN_FOOD_FACTS`. Refresh
   with the daily delta files; periodic full re-import to catch deletions.

3. **Segregation is mandatory.** Every catalog document carries a `source`
   enum (`USDA | OPEN_FOOD_FACTS | USER | GEMINI_PHOTO | GEMINI_LABEL`) and,
   for imported rows, a `sourceRef` (USDA `fdcId`, OFF barcode). This makes
   Open Food Facts–derived rows **queryable and extractable as a set**, so we
   can honor or excise them if distribution requirements ever change.

4. **Attribution.** Wherever Open Food Facts data is surfaced (a product
   detail, a barcode result sourced from OFF), the UI shows
   *"Nutrition data from Open Food Facts (ODbL)."* A project-level
   attribution/credits page links the license.

5. **No database redistribution in v1.** We serve **individual lookups** to
   our own clients only. We do **not** bundle the catalog into the Android app,
   and we do **not** expose a bulk/export endpoint. The Android client always
   resolves foods through the backend. If offline catalog or any bulk export is
   ever wanted, that is a **new decision**: either ship only the CC0 (USDA)
   subset, or publish the OFF-derived subset as ODbL open data. The `source`
   tagging from (3) is what keeps that option open.

6. **Barcode lookup order (runtime).** local catalog → Open Food Facts API
   (`GET https://world.openfoodfacts.org/api/v2/product/{barcode}.json`, no key
   required, polite User-Agent, cache the result back into the catalog) →
   nutrition-label photo via Gemini as the last resort. Gemini only ever sees
   the genuine long tail.

This ADR is **about data licensing only**. It introduces **no new AI model**:
meal-photo itemization and label OCR run on the project-standard
`gemini-3.5-flash` (extraction), and studio images on
`gemini-3.1-flash-image-preview`, per the root `CLAUDE.md` rule and unlike the
model exception in [ADR-0005](ADR-0005-goals-chat-gemini-pro.md).

## Consequences

Positive:

- The large majority of lookups (generic foods + common packaged products)
  resolve from our own store with **zero Gemini cost** — the explicit goal.
- USDA gives a legally unencumbered spine; Open Food Facts gives barcode
  breadth no free CC0 source matches.
- `source` tagging keeps Open Food Facts rows isolated, so a future
  distribution need is a clean filter, not a data-untangling project.

Negative / obligations we accept:

- We must carry and render Open Food Facts attribution.
- We forgo (for now) bundling the catalog for offline use; the Android app
  needs connectivity for food lookup. Acceptable — every other module already
  reads through the backend.
- Open Food Facts data quality is crowd-sourced and uneven; we filter on
  populated nutriments and let the validation/trust model (IMPL-13) raise
  confidence over time.

## Revisit when

- We want **offline** food lookup or any **bulk export** — that triggers the
  share-alike question and must choose: ship USDA-only, or publish the OFF
  subset as open data.
- USDA Branded Foods coverage becomes good enough to replace Open Food Facts
  for our barcode needs (it is CC0) — at which point we could drop the ODbL
  dependency entirely.
- A future flash revision proves too weak for meal/label extraction and we
  need a Pro model — that is a **separate** model ADR, not this one.
