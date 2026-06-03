# Firestore TTL policies (infra-as-code).
#
# IMPL-AND-20 #9 — declare a TTL on the `idempotencyKeys` collection-group's
# `expiresAt` field so Firestore automatically reaps expired idempotency-key
# records. The records live at `users/{uid}/idempotencyKeys/{scope#key}` and
# `FirestoreIdempotencyStore` writes `expiresAt` as a Firestore Timestamp
# (`com.google.cloud.Timestamp`, the type a `ttl_config` acts on) set to
# now + the in-app TTL. The in-app read also re-checks `expiresAt` so an
# unreaped-but-expired key still behaves as absent; this policy bounds storage
# growth in the steady state.
#
# A `google_firestore_field` with a `ttl_config` block targets a single field
# across the named collection group. The project ID and the `(default)`
# database match the rest of the deployment (project `health-fitness-160`,
# Firestore native mode in `us-central1`).
#
# This file is the source of truth for the TTL policy. If Terraform is not yet
# being applied for this project (the bootstrap is shell-scripted today, see
# ../README.md), the equivalent one-time gcloud command is documented in
# ../README.md under "IMPL-AND-20: Firestore TTL on idempotencyKeys" so the
# policy is reproducible either way.

variable "project_id" {
  description = "GCP / Firebase project that owns the Firestore database."
  type        = string
  default     = "health-fitness-160"
}

# TTL on users/{uid}/idempotencyKeys/{doc}.expiresAt.
#
# `collection` is the collection-group id (the leaf collection name), so this
# applies to every `idempotencyKeys` subcollection regardless of the owning
# user. `field = "expiresAt"` plus the `ttl_config {}` block tells Firestore to
# delete a document once its `expiresAt` Timestamp passes.
resource "google_firestore_field" "idempotency_keys_ttl" {
  project    = var.project_id
  database   = "(default)"
  collection = "idempotencyKeys"
  field      = "expiresAt"

  ttl_config {}

  # Leave indexing untouched: we only want the TTL behaviour here, not to
  # alter the field's automatic single-field indexes.
  index_config {}
}
