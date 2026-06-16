'use strict';

// Gen2 Cloud Function: generate webp thumbnails for exercise media.
//
// Trigger: GCS `google.cloud.storage.object.v1.finalized` (CloudEvent) on the
// exercise-media bucket. For an object `exercises/{id}/{name}.{ext}` this writes
// a webp thumbnail (max edge 320px, aspect preserved) to
// `exercises/{id}/thumb/{name}.webp` in the SAME bucket, copying the source's
// long-lived immutable cache headers.
//
// See docs/decisions/ADR-0017-gcs-thumbnail-cloud-function.md and
// docs/specs/IMPL-20-exercise-admin-redesign.md.
//
// Loop guard (critical): any object whose path contains `/thumb/` is skipped, so
// the function never reprocesses its own output. Non-image objects are skipped
// defensively. No Firestore writes; overwriting an existing thumb is fine
// (idempotent).

const functions = require('@google-cloud/functions-framework');
const { Storage } = require('@google-cloud/storage');
const sharp = require('sharp');

const storage = new Storage();

// Output settings (ADR-0017): webp, max edge 320px, immutable cache.
const MAX_EDGE = 320;
const WEBP_QUALITY = 80;
const CACHE_CONTROL = 'public, max-age=31536000, immutable';
const THUMB_CONTENT_TYPE = 'image/webp';

// Only process objects under exercises/<id>/ that are direct frame images
// (not already inside a thumb/ subfolder).
const SOURCE_RE = /^exercises\/([^/]+)\/([^/]+)\.([^./]+)$/;

functions.cloudEvent('generateThumbnail', async (cloudEvent) => {
  const data = cloudEvent.data || {};
  const bucketName = data.bucket;
  const objectName = data.name;
  const contentType = data.contentType || '';

  if (!bucketName || !objectName) {
    console.warn('Missing bucket/name in event; skipping', {
      bucket: bucketName,
      name: objectName,
    });
    return;
  }

  // Loop guard: never reprocess our own output.
  if (objectName.includes('/thumb/')) {
    console.log(`Skipping thumb output: ${objectName}`);
    return;
  }

  // Defensive: only handle image content types.
  if (!contentType.startsWith('image/')) {
    console.log(`Skipping non-image (${contentType || 'unknown'}): ${objectName}`);
    return;
  }

  const match = SOURCE_RE.exec(objectName);
  if (!match) {
    console.log(`Path not an exercise frame image, skipping: ${objectName}`);
    return;
  }

  const [, exerciseId, name] = match;
  const thumbName = `exercises/${exerciseId}/thumb/${name}.webp`;

  const bucket = storage.bucket(bucketName);
  const srcFile = bucket.file(objectName);
  const thumbFile = bucket.file(thumbName);

  console.log(`Thumbnailing ${objectName} -> ${thumbName}`);

  const [srcBuffer] = await srcFile.download();

  const thumbBuffer = await sharp(srcBuffer)
    .rotate() // honor EXIF orientation before resizing
    .resize({
      width: MAX_EDGE,
      height: MAX_EDGE,
      fit: 'inside', // preserve aspect, bound the longest edge to MAX_EDGE
      withoutEnlargement: true,
    })
    .webp({ quality: WEBP_QUALITY })
    .toBuffer();

  await thumbFile.save(thumbBuffer, {
    contentType: THUMB_CONTENT_TYPE,
    metadata: {
      cacheControl: CACHE_CONTROL,
      contentType: THUMB_CONTENT_TYPE,
    },
    resumable: false,
  });

  console.log(`Wrote thumbnail ${thumbName} (${thumbBuffer.length} bytes)`);
});
