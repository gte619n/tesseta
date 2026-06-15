#!/usr/bin/env python3
"""Push reference-library data from the enriched CSV into Firestore.

Reads the CSV produced by ``enrich_exercises_with_references.py`` and writes a
nested ``reference`` map onto each matched exercise document, keyed by
``exerciseId`` (the Firestore document id).

    reference: { url, source, name, score, match, images: [img1, img2] }

Safety
------
- Uses the Firestore REST ``PATCH`` with ``updateMask.fieldPaths=reference`` so
  ONLY the ``reference`` field is written -- every other field on the document
  is left untouched.
- The backend reads exercises with a manual field mapper (unknown fields are
  ignored) and saves with ``SetOptions.merge()``, so this extra field neither
  breaks deserialization nor gets wiped by later backend writes.
- Only rows that actually matched (non-empty referenceUrl) are written.

Defaults to the ``production`` database (where the live 352-exercise catalog
lives). Auth uses your active gcloud credentials.

Usage
-----
    # preview without writing
    python3 infra/scripts/push_exercise_references_to_firestore.py --dry-run

    # write a single doc first to sanity-check, then the rest
    python3 .../push_exercise_references_to_firestore.py --limit 1
    python3 .../push_exercise_references_to_firestore.py
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import urllib.parse
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

PROJECT = "health-fitness-160"


def access_token() -> str:
    return subprocess.run(
        ["gcloud", "auth", "print-access-token"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()


def reference_value(row: dict) -> dict:
    """Build a Firestore-typed mapValue for the reference data on a row."""
    fields = {
        "url": {"stringValue": row["referenceUrl"]},
        "source": {"stringValue": row["referenceSource"]},
        "name": {"stringValue": row["referenceName"]},
        "match": {"stringValue": row["referenceMatch"]},
    }
    if row.get("referenceScore"):
        fields["score"] = {"doubleValue": float(row["referenceScore"])}
    images = [row.get("referenceImage1"), row.get("referenceImage2")]
    images = [i for i in images if i]
    if images:
        fields["images"] = {
            "arrayValue": {"values": [{"stringValue": i} for i in images]}
        }
    return {"mapValue": {"fields": fields}}


def patch_doc(token: str, database: str, exercise_id: str, body: dict) -> None:
    base = (f"https://firestore.googleapis.com/v1/projects/{PROJECT}"
            f"/databases/{database}/documents/exercises/{urllib.parse.quote(exercise_id)}")
    url = base + "?updateMask.fieldPaths=reference"
    req = Request(url, data=json.dumps(body).encode("utf-8"), method="PATCH",
                  headers={"Authorization": f"Bearer {token}",
                           "Content-Type": "application/json"})
    with urlopen(req, timeout=30) as resp:
        if not (200 <= resp.status < 300):
            raise RuntimeError(f"HTTP {resp.status}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", default="exercises.csv", type=Path)
    ap.add_argument("--database", default="production",
                    help="Firestore database id (default: production)")
    ap.add_argument("--dry-run", action="store_true",
                    help="show what would be written without calling Firestore")
    ap.add_argument("--limit", type=int, default=0,
                    help="only write the first N matched docs (0 = all)")
    args = ap.parse_args()

    if not args.input.exists():
        print(f"error: input CSV not found: {args.input}", file=sys.stderr)
        return 1

    with args.input.open(newline="") as fh:
        rows = list(csv.DictReader(fh))

    todo = [r for r in rows if r.get("referenceUrl") and r.get("exerciseId")]
    if args.limit:
        todo = todo[:args.limit]
    print(f"{len(rows)} rows, {len(todo)} with references to write "
          f"-> database '{args.database}'{' (DRY RUN)' if args.dry_run else ''}\n")

    token = None if args.dry_run else access_token()
    ok = fail = 0
    for r in todo:
        body = {"fields": {"reference": reference_value(r)}}
        if args.dry_run:
            print(f"  would PATCH {r['exerciseId']}  ({r['name']} -> "
                  f"{r['referenceSource']}:{r['referenceName']})")
            continue
        try:
            patch_doc(token, args.database, r["exerciseId"], body)
            ok += 1
            if ok % 25 == 0:
                print(f"  ... {ok} written")
        except (HTTPError, URLError, RuntimeError, ValueError) as e:
            fail += 1
            code = getattr(e, "code", "")
            print(f"  FAIL {r['exerciseId']} ({r['name']}): {code} {e}",
                  file=sys.stderr)

    if not args.dry_run:
        print(f"\ndone: {ok} written, {fail} failed")
    return 1 if fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
