'use strict';

// One-off backfill: generate webp thumbnails for exercise-media objects that
// predate the Cloud Function trigger (IMPL-20 / ADR-0017). Applies the SAME
// transform as index.js, writing exercises/{id}/thumb/{name}.webp. Idempotent:
// existing thumbs are skipped. Originals are never modified.
//
// Auth: Application Default Credentials (gcloud auth application-default login).
//
//   node backfill.js                 # default bucket, generate missing thumbs
//   BUCKET=other-bucket node backfill.js
//   FORCE=1 node backfill.js         # rewrite thumbs even if they exist
//   CONCURRENCY=12 node backfill.js

const { Storage } = require('@google-cloud/storage');
const sharp = require('sharp');

// Mirror index.js exactly.
const MAX_EDGE = 320;
const WEBP_QUALITY = 80;
const CACHE_CONTROL = 'public, max-age=31536000, immutable';
const THUMB_CONTENT_TYPE = 'image/webp';
const SOURCE_RE = /^exercises\/([^/]+)\/([^/]+)\.([^./]+)$/;

const BUCKET = process.env.BUCKET || 'health-fitness-160-exercise-media';
const CONCURRENCY = Number(process.env.CONCURRENCY || 8);
const FORCE = process.env.FORCE === '1';

const storage = new Storage();

async function makeThumb(bucket, objectName) {
  const match = SOURCE_RE.exec(objectName);
  if (!match) return { status: 'skip-path' };
  if (objectName.includes('/thumb/')) return { status: 'skip-thumb' };

  const [, exerciseId, name] = match;
  const thumbName = `exercises/${exerciseId}/thumb/${name}.webp`;
  const thumbFile = bucket.file(thumbName);

  if (!FORCE) {
    const [exists] = await thumbFile.exists();
    if (exists) return { status: 'exists' };
  }

  const [srcBuffer] = await bucket.file(objectName).download();
  const thumbBuffer = await sharp(srcBuffer)
    .rotate()
    .resize({ width: MAX_EDGE, height: MAX_EDGE, fit: 'inside', withoutEnlargement: true })
    .webp({ quality: WEBP_QUALITY })
    .toBuffer();

  await thumbFile.save(thumbBuffer, {
    contentType: THUMB_CONTENT_TYPE,
    metadata: { cacheControl: CACHE_CONTROL, contentType: THUMB_CONTENT_TYPE },
    resumable: false,
  });
  return { status: 'wrote', bytes: thumbBuffer.length };
}

async function main() {
  const bucket = storage.bucket(BUCKET);
  console.log(`Listing gs://${BUCKET}/exercises/ ...`);
  const [files] = await bucket.getFiles({ prefix: 'exercises/' });
  const sources = files
    .map((f) => f.name)
    .filter((n) => !n.includes('/thumb/') && SOURCE_RE.test(n));
  console.log(`${files.length} objects, ${sources.length} source images to consider (FORCE=${FORCE}).`);

  const counts = { wrote: 0, exists: 0, 'skip-path': 0, error: 0 };
  let i = 0;
  let done = 0;

  async function worker() {
    while (i < sources.length) {
      const objectName = sources[i++];
      try {
        const res = await makeThumb(bucket, objectName);
        counts[res.status] = (counts[res.status] || 0) + 1;
      } catch (err) {
        counts.error++;
        console.error(`ERROR ${objectName}: ${err.message}`);
      }
      done++;
      if (done % 50 === 0 || done === sources.length) {
        console.log(`  ${done}/${sources.length} (wrote=${counts.wrote} exists=${counts.exists} error=${counts.error})`);
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, sources.length) }, worker));
  console.log('Done:', counts);
  if (counts.error > 0) process.exitCode = 1;
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
