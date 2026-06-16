#!/usr/bin/env python3
"""Enrich the Tesseta exercise CSV with reference-library URLs.

For every exercise in the catalog CSV we try to find a matching public
exercise-library page and append its URL. This gives future development a bit
of grounded reference material (form cues, muscle maps, demo media) per
exercise without us having to author it.

How we find matches
-------------------
The candidate sites use opaque numeric IDs in their URLs (e.g.
``/exercises/1339/burpee``), so a URL can't be constructed from a name alone.
Instead we download each site's public XML sitemap, which lists every exercise
page with a human-readable slug, and fuzzy-match our exercise names against
those slugs. No API keys are required.

Sources are *routed by movement type* (see source_order) -- jefit is a
hypertrophy DB, rb100 is functional/HYROX, free-exercise-db owns mobility/
stretch/foam-roll (SMR), and yoga-api owns named asanas. fedb is everyone's
final fallback. First reasonable match in the routed order wins.
  - jefit.com         sitemap, ~1,300 exercises (strength/cardio)
  - rb100.fitness     sitemap, ~170 (carries, sleds, med-ball, plyo)
  - free-exercise-db  open JSON, 873 (incl. 123 stretching + SMR); 2 images each
  - yoga-api          open JSON, 43 asanas; 1 image each

(fitwill.app and exrx.net were evaluated but sit behind a Cloudflare
JavaScript challenge, so they aren't scriptable.)

Adds columns: referenceUrl, referenceSource, referenceName, referenceScore,
referenceMatch, referenceImage1, referenceImage2.

Usage
-----
    python3 infra/scripts/enrich_exercises_with_references.py \
        --input exercises.csv --output exercises.csv

    # tune the match threshold, force re-download of sources, or HTTP-verify
    python3 .../enrich_exercises_with_references.py --min-score 0.6 --refresh --verify
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
import urllib.parse
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
)

CACHE_DIR = Path("/tmp/tesseta-exercise-refs")

# Sitemaps we know are publicly fetchable, and how to pull exercise URLs out of
# them. Each source yields (display_name, url) pairs. Order = match priority.
JEFIT_SITEMAP = "https://www.jefit.com/sitemap.xml"
JEFIT_RE = re.compile(r"https://www\.jefit\.com/exercises/\d+/[^<\s]+")

RB100_SITEMAP = "https://rb100.fitness/exercise-sitemap.xml"
RB100_RE = re.compile(r"https://rb100\.fitness/exercise/[^<\s/]+/?")

# free-exercise-db: open JSON dataset (873 exercises, no key, no Cloudflare).
# It is the *right* source for mobility/stretch/SMR -- it has a 123-entry
# stretching category including foam-roll ("...-SMR") entries that jefit lacks.
# Each entry exposes a stable id and exactly two demo images.
FEDB_JSON = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json"
FEDB_BASE = "https://yuhonas.github.io/free-exercise-db/exercises/"

# yoga-api: 43 asanas with english/sanskrit names and one image each. Covers
# the named-pose cluster (Pigeon, Corpse, Butterfly, Forward Bend, ...) that no
# strength DB indexes.
YOGA_JSON = "https://raw.githubusercontent.com/LunaticPrakash/yoga-api/master/yoga-api.json"
# Words to neutralize ONLY when matching against the yoga pool, so
# "Butterfly Stretch" can reach "Butterfly Pose". Harmless elsewhere because
# this set is applied per-source.
YOGA_DROP = frozenset({"pose", "stretch", "reclining", "supine", "supta"})

# Grammatical filler only. We deliberately KEEP equipment/qualifier words
# (barbell, dumbbell, cable, machine, kettlebell, ...) as content tokens: they
# are highly discriminative. Dropping them was a mistake -- it let
# "Quadruped Kick-Back" collide with "Barbell Back Squat" on the shared word
# "back". Synonyms we *do* fold together live in SYNONYMS below.
STOPWORDS = {
    "the", "a", "an", "with", "and", "to", "of", "on", "in", "for", "or",
    "your", "my", "into", "from", "at",
}

# Light synonym folding so trivially-different vocab still lines up.
SYNONYMS = {
    "pushup": "push", "pushups": "push", "push-up": "push", "pushdown": "push",
    "pullup": "pull", "pullups": "pull", "pulldown": "pull", "pull-down": "pull",
    "situp": "sit", "situps": "sit", "scissors": "scissor",
    "ups": "up", "raises": "raise", "curls": "curl", "rows": "row",
    "squats": "squat", "lunges": "lunge", "presses": "press", "crunches": "crunch",
    "extensions": "extension", "stretches": "stretch", "circles": "circle",
    "hugs": "hug", "reaches": "reach", "slams": "slam", "twisters": "twister",
    # plurals folded to the muscle term free-exercise-db uses for its SMR
    # (foam-roll) entries. Plural-only on purpose: singular "quad"/"hamstring"
    # are left alone so existing "Quad Stretch" matches don't break.
    "quads": "quadriceps", "hamstrings": "hamstring", "calf": "calves",
}

# Qualifier tokens that describe *how/where/how-many* a movement is done but
# rarely change which library exercise it maps to. The simplified second pass
# strips these from BOTH our name and the candidate slug so e.g.
# "Single-Arm Dumbbell Overhead Press" can reach "dumbbell overhead press" and
# "Standing Arm Circles Backwards" can reach "arm circles". Deliberately NOT
# included: "back", "split", "goblet", "sumo", "arm", "leg" -- those genuinely
# distinguish movements.
QUALIFIERS = {
    # laterality / count (the "1-arm" style lead-in)
    "single", "one", "two", "alternating", "alternate", "dual", "double",
    "bilateral", "unilateral",
    # load / style prefixes the libraries usually omit
    "bodyweight", "weighted", "isometric", "isometrics", "dynamic", "static",
    "plate", "loaded", "suspended", "suspension", "anchored", "tempo", "slow",
    "fast", "mini", "partial", "elevated",
    # posture / setup
    "tall", "half", "kneeling", "bench", "prone", "supine", "seated", "standing",
    "quadruped", "supported", "lever", "short", "long",
    # vertical qualifiers
    "low", "high",
    # direction
    "clockwise", "counterclockwise", "counter", "forwards", "forward",
    "backwards", "backward", "left", "right",
}


def _norm_tokens(name: str) -> list[str]:
    """Lowercase, strip punctuation, drop filler, fold synonyms."""
    name = name.lower()
    name = name.replace("&", " and ")
    # "Foam Roll <muscle>" is "<muscle>-SMR" in free-exercise-db.
    name = name.replace("foam rolling", " smr ").replace("foam roll", " smr ")
    name = re.sub(r"\(.*?\)", " ", name)          # drop parentheticals
    name = re.sub(r"[^a-z0-9]+", " ", name)
    tokens = []
    for t in name.split():
        if not t or t in STOPWORDS:
            continue
        tokens.append(SYNONYMS.get(t, t))
    return tokens or [t for t in re.sub(r"[^a-z0-9]+", " ", name).split() if t]


def _strip_qualifiers(tokens: list[str]) -> list[str]:
    return [t for t in tokens if t not in QUALIFIERS]


def _cand_core(name: str) -> list[str]:
    """Qualifier-stripped tokens for a *candidate* slug.

    We do NOT drop a trailing ``with/to`` clause here: the candidate's full
    identity is the target, so "plank with alternating knee drive" must stay
    distinct from a plain "plank" -- otherwise it would match every plank.
    """
    return _strip_qualifiers(_norm_tokens(name)) or _norm_tokens(name)


def _core_tokens(name: str) -> list[str]:
    """Simplified tokens for *our* over-qualified catalog name.

    Drops a trailing ``... with X`` / ``... to X`` clause (so "High Plank With
    Scap Push-Up" -> "plank", "Dumbbell Hang Clean To Front Squat" -> "hang
    clean") AND qualifier words. Falls back gracefully if that empties out.
    """
    head = re.split(r"\b(?:with|to)\b", name.lower(), maxsplit=1)[0]
    core = _strip_qualifiers(_norm_tokens(head))
    if core:
        return core
    full = _strip_qualifiers(_norm_tokens(name))
    return full or _norm_tokens(name)


def _score(a_tokens: list[str], b_tokens: list[str]) -> float:
    """Precision-first token-overlap score in [0, 1].

    Pure *content-token* overlap -- no character-level similarity, which used
    to reward incidental shared words ("Pigeon Pose" vs "Hero Pose" both have
    "pose"). We combine Jaccard with a guard that the candidate must cover at
    least half of *our* tokens, so a short generic slug ("plank") can't swallow
    a long descriptive name ("Isometric Bear Crawl With ... Pull-Through").
    """
    if not a_tokens or not b_tokens:
        return 0.0
    sa, sb = set(a_tokens), set(b_tokens)
    shared = sa & sb
    if not shared:
        return 0.0
    jaccard = len(shared) / len(sa | sb)
    our_coverage = len(shared) / len(sa)   # how much of our name is explained
    if our_coverage < 0.5:
        return 0.0
    # Reward jaccard, nudged by how completely our own name is covered.
    return 0.7 * jaccard + 0.3 * our_coverage


@dataclass
class Candidate:
    name: str
    url: str
    tokens: list[str]            # full normalized tokens (primary pass)
    core: list[str]              # qualifier-stripped tokens (simplified pass)
    images: tuple[str, ...] = ()  # reference image URLs (free-exercise-db / yoga)


def _http_get(url: str) -> str:
    req = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", errors="replace")


def _cached_sitemap(url: str, cache_name: str, refresh: bool) -> str:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file = CACHE_DIR / cache_name
    if cache_file.exists() and not refresh:
        return cache_file.read_text(encoding="utf-8")
    text = _http_get(url)
    cache_file.write_text(text, encoding="utf-8")
    return text


def _slug_to_name(url: str) -> str:
    slug = url.rstrip("/").rsplit("/", 1)[-1]
    return slug.replace("-", " ")


def load_jefit(refresh: bool) -> list[Candidate]:
    xml = _cached_sitemap(JEFIT_SITEMAP, "jefit_sitemap.xml", refresh)
    urls = sorted(set(JEFIT_RE.findall(xml)))
    out = []
    for u in urls:
        name = _slug_to_name(u)
        out.append(Candidate(name=name, url=u,
                             tokens=_norm_tokens(name), core=_cand_core(name)))
    return out


def load_rb100(refresh: bool) -> list[Candidate]:
    xml = _cached_sitemap(RB100_SITEMAP, "rb100_sitemap.xml", refresh)
    urls = sorted(set(RB100_RE.findall(xml)))
    out = []
    for u in urls:
        name = _slug_to_name(u)
        out.append(Candidate(name=name, url=u,
                             tokens=_norm_tokens(name), core=_cand_core(name)))
    return out


def load_fedb(refresh: bool) -> list[Candidate]:
    data = json.loads(_cached_sitemap(FEDB_JSON, "fedb.json", refresh))
    out = []
    for e in data:
        name = e.get("name", "").strip()
        eid = e.get("id", "").strip()
        if not name or not eid:
            continue
        url = f"{FEDB_BASE}{eid}.json"
        images = tuple(f"{FEDB_BASE}{img}" for img in e.get("images", []))
        out.append(Candidate(name=name, url=url, tokens=_norm_tokens(name),
                             core=_cand_core(name), images=images))
    return out


def _wiki_url(title: str) -> str:
    return "https://en.wikipedia.org/wiki/" + urllib.parse.quote(
        title.strip().replace(" ", "_"))


def load_yoga(refresh: bool) -> list[Candidate]:
    """yoga-api identifies the asana; Wikipedia (keyed on the Sanskrit name) is
    the reference page. yoga-api itself has no images or per-pose URLs, so we
    resolve each pose to a Wikipedia article (Sanskrit name first, English name
    second) and KEEP ONLY poses whose article actually exists -- dropping the
    rest avoids dead links and incidentally prefers the canonical pose variant.
    The url->resolution map is cached so normal runs don't re-hit Wikipedia."""
    data = json.loads(_cached_sitemap(YOGA_JSON, "yoga.json", refresh))
    cache_file = CACHE_DIR / "yoga_wiki.json"
    resolved: dict[str, str] = {}
    if cache_file.exists() and not refresh:
        resolved = json.loads(cache_file.read_text(encoding="utf-8"))
    else:
        for e in data:
            name = (e.get("english_name") or "").strip()
            if not name:
                continue
            for title in (e.get("sanskrit_name"), name):
                if title and url_ok(u := _wiki_url(title)):
                    resolved[name] = u
                    break
        cache_file.write_text(json.dumps(resolved), encoding="utf-8")

    out = []
    for e in data:
        name = (e.get("english_name") or "").strip()
        url = resolved.get(name)
        if not name or not url:
            continue
        out.append(Candidate(name=name, url=url, tokens=_norm_tokens(name),
                             core=_cand_core(name)))
    return out


def best_match(tokens: list[str], candidates: list[Candidate],
               use_core: bool = False,
               drop: frozenset = frozenset()) -> tuple[Candidate | None, float]:
    """Best candidate for ``tokens``. ``drop`` neutralizes the given words on
    both sides (used so the yoga pool ignores generic "pose"/"stretch")."""
    q = [t for t in tokens if t not in drop] if drop else tokens
    best, best_key = None, None
    for c in candidates:
        ctoks = c.core if use_core else c.tokens
        if drop:
            ctoks = [t for t in ctoks if t not in drop]
        s = _score(q, ctoks)
        if s <= 0.0:
            continue
        # On score ties prefer the simplest candidate (shortest name) -- that's
        # usually the canonical base exercise ("plank" over "bench plank"),
        # then break remaining ties alphabetically for determinism.
        key = (s, -len(c.tokens), c.name)
        if best_key is None or key > best_key:
            best, best_key = c, key
    return best, (best_key[0] if best_key else 0.0)


def url_ok(url: str) -> bool:
    try:
        req = Request(url, headers={"User-Agent": USER_AGENT}, method="HEAD")
        with urlopen(req, timeout=15) as resp:
            return 200 <= resp.status < 400
    except (HTTPError, URLError, ValueError):
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", default="exercises.csv", type=Path)
    ap.add_argument("--output", default="exercises.csv", type=Path)
    ap.add_argument("--min-score", type=float, default=0.62,
                    help="minimum match score to accept a URL (0-1)")
    ap.add_argument("--refresh", action="store_true",
                    help="re-download all sources instead of using the cache")
    ap.add_argument("--verify", action="store_true",
                    help="HTTP-HEAD each matched URL to confirm it resolves (slow, polite delay)")
    args = ap.parse_args()

    if not args.input.exists():
        print(f"error: input CSV not found: {args.input}", file=sys.stderr)
        return 1

    with args.input.open(newline="") as fh:
        reader = csv.DictReader(fh)
        rows = list(reader)
        fieldnames = list(reader.fieldnames or [])

    print(f"loaded {len(rows)} exercises from {args.input}")

    # Candidate pools. jefit/rb100 are sitemap-scraped; fedb/yoga are open JSON.
    print("downloading sources ...")
    pools = {
        "jefit": load_jefit(args.refresh),
        "rb100": load_rb100(args.refresh),
        "fedb": load_fedb(args.refresh),
        "yoga": load_yoga(args.refresh),
    }
    drops = {"yoga": YOGA_DROP}
    for label, cands in pools.items():
        print(f"  {label}: {len(cands)} candidate exercises")

    # Route each exercise to the sources that actually cover its movement type.
    # jefit is hypertrophy-first, rb100 functional, fedb owns mobility/stretch/
    # SMR, yoga owns named asanas. fedb is everyone's final fallback.
    YOGA_RE = re.compile(r"\bpose\b|matsyendra|savasana|shavasana|asana", re.I)

    def source_order(row) -> list[str]:
        mp = (row.get("movementPattern") or "").upper()
        name = row.get("name", "")
        if YOGA_RE.search(name):
            return ["yoga", "fedb", "jefit"]
        if mp in ("MOBILITY", "STRETCH"):
            return ["fedb", "jefit", "yoga"]
        if mp == "CARRY":
            return ["rb100", "fedb", "jefit"]
        return ["jefit", "rb100", "fedb"]

    new_cols = ["referenceUrl", "referenceSource", "referenceName",
                "referenceScore", "referenceMatch",
                "referenceImage1", "referenceImage2"]
    for col in new_cols:
        if col not in fieldnames:
            fieldnames.append(col)

    def find(tokens, use_core, order):
        """First source (in routed order) with a good-enough match."""
        for label in order:
            cand, score = best_match(tokens, pools[label], use_core=use_core,
                                     drop=drops.get(label, frozenset()))
            if cand and score >= args.min_score:
                return label, cand, score
        return None

    matched = 0
    per_source: dict[str, int] = {}
    per_kind: dict[str, int] = {}
    for row in rows:
        for col in new_cols:
            row[col] = ""   # reset so re-runs don't keep stale matches
        name = row.get("name", "")
        order = source_order(row)
        # Pass 1: full name. Pass 2: qualifier-stripped "core" name (handles
        # "alternating", laterality, posture, direction, with/to clauses).
        chosen, kind = find(_norm_tokens(name), False, order), "name"
        if not chosen:
            chosen, kind = find(_core_tokens(name), True, order), "simplified"
        if chosen and args.verify and not url_ok(chosen[1].url):
            chosen = None
            time.sleep(1.0)
        if chosen:
            label, cand, score = chosen
            row["referenceUrl"] = cand.url
            row["referenceSource"] = label
            row["referenceName"] = cand.name
            row["referenceScore"] = f"{score:.2f}"
            row["referenceMatch"] = kind
            row["referenceImage1"] = cand.images[0] if len(cand.images) > 0 else ""
            row["referenceImage2"] = cand.images[1] if len(cand.images) > 1 else ""
            matched += 1
            per_source[label] = per_source.get(label, 0) + 1
            per_kind[kind] = per_kind.get(kind, 0) + 1
            if args.verify:
                time.sleep(0.5)  # be polite when hitting pages

    with args.output.open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nmatched {matched}/{len(rows)} exercises "
          f"({100*matched/len(rows):.0f}%)")
    for label, n in per_source.items():
        print(f"  via {label}: {n}")
    for kind, n in per_kind.items():
        print(f"  {kind} match: {n}")
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
