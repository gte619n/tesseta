// IMPL-20: derive a GCS thumbnail URL from a full exercise-media image URL by
// path convention (see ADR-0017). A thumbnail Cloud Function writes a webp
// derivative alongside each uploaded/generated image:
//
//   exercises/{id}/{name}.{ext}  ->  exercises/{id}/thumb/{name}.webp
//
// The thumbnail is NOT persisted on the document — the web derives the URL and
// falls back to the full image on miss (the thumb may not exist yet, e.g. for
// images uploaded before the function deployed, or external reference images).
//
// This is a pure string transform; callers pair it with an <img onError>
// fallback to the original full URL.

// Match a path segment of the form `exercises/{id}/{name}.{ext}` where {name}
// has no further slashes (i.e. it is the leaf file, not already under /thumb/).
// Group 1 = directory prefix up to and including `exercises/{id}/`,
// group 2 = bare file name, group 3 = extension.
const EXERCISE_IMAGE_PATH = /^(.*\/exercises\/[^/]+\/)([^/]+)\.[^/.]+$/;

/**
 * Returns the path-derived thumbnail URL for an exercise-media image, or the
 * original URL unchanged when it doesn't fit the convention.
 *
 * Edge cases (all return the input untouched):
 *  - `null` / `undefined` / empty → returned as-is (callers pass nullable
 *    frame URLs directly).
 *  - A URL already under `…/thumb/…` → already a thumbnail, no double-nesting.
 *  - Any URL that doesn't match `…/exercises/{id}/{file}.{ext}` (external
 *    reference images, malformed URLs) → returned unchanged so the caller still
 *    renders the original.
 */
export function thumbUrl<T extends string | null | undefined>(originalUrl: T): T {
  if (!originalUrl) return originalUrl;
  // Already a thumbnail — don't re-nest under another /thumb/ segment.
  if (originalUrl.includes("/thumb/")) return originalUrl;
  const m = EXERCISE_IMAGE_PATH.exec(originalUrl);
  if (!m) return originalUrl;
  const [, prefix, name] = m;
  return `${prefix}thumb/${name}.webp` as T;
}
