#!/usr/bin/env python3
"""Rewrite a git commit log into plain-English release notes for beta testers.

Used by android/cloudbuild.yaml during the Firebase App Distribution step. Reads
the commit log on stdin-substitute env vars and prints tester-facing notes to
stdout. Exits non-zero on any failure so the build can fall back to the raw
git-commit notes — note generation must never block a release.

Env:
  GEMINI_API_KEY  required; the Generative Language API key (Secret Manager:
                  gemini_api_key) — the same key the backend uses.
  COMMITS         required; the commit log to summarize (one entry per commit,
                  subject + body).
  GEMINI_MODEL    optional; defaults to gemini-3.5-flash (project standard for
                  text work, per CLAUDE.md).

This calls the public Generative Language API (generativelanguage.googleapis.com)
with the key as a query param — the same endpoint the backend's google-genai SDK
uses — so no extra dependencies beyond the Python stdlib are needed.
"""

import json
import os
import sys
import urllib.error
import urllib.request

PROMPT = """\
You are writing the release notes shown to non-technical beta testers of the \
Tesseta health & fitness mobile app, in their Firebase App Distribution email. \
Rewrite the following git commit messages into a short, friendly, plain-English \
summary of what changed in this build.

Rules:
- Optionally open with a single one-sentence overview, then a bulleted list.
- Describe user-visible impact in everyday language, not code or internals.
- Group related commits into one bullet.
- Omit purely internal work (refactors, tests, CI, dependency bumps, version \
chores, formatting) unless it affects what a tester sees or does.
- No commit hashes, no conventional-commit prefixes (feat:, fix:, chore:, etc.), \
no markdown headings, no emoji.
- Keep it to at most 12 bullets. Use "- " for bullets.
- If nothing user-facing changed, output exactly: \
Minor under-the-hood improvements and fixes.

Commits:
"""


def main() -> int:
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    commits = os.environ.get("COMMITS", "").strip()
    model = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash").strip()
    if not api_key or not commits:
        return 1

    payload = json.dumps(
        {
            "contents": [
                {"role": "user", "parts": [{"text": PROMPT + commits}]}
            ],
            # gemini-3.5-flash is a thinking model; thoughtsTokenCount counts
            # against maxOutputTokens, so keep ample headroom (~800 thinking
            # tokens observed) to avoid truncating the notes mid-sentence.
            "generationConfig": {"temperature": 0.4, "maxOutputTokens": 8192},
        }
    ).encode("utf-8")

    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        + model
        + ":generateContent?key="
        + api_key
    )
    req = urllib.request.Request(
        url, data=payload, headers={"Content-Type": "application/json"}
    )

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.load(resp)
        text = data["candidates"][0]["content"]["parts"][0]["text"].strip()
    except (urllib.error.URLError, KeyError, IndexError, ValueError, OSError):
        return 1

    if not text:
        return 1

    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
