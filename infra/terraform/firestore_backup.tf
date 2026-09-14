# DATA-001 (audit 2026-09-13, CRITICAL) — Firestore durability for the
# `production` database: backup schedules, point-in-time recovery (PITR), and
# delete-protection. Plus GCS bucket versioning + noncurrent-version lifecycle.
#
# Verified live on 2026-09-13: prod had ZERO backup schedules, PITR disabled
# (1-hour version retention only), delete-protection disabled, and all app GCS
# buckets unversioned with no lifecycle. RPO was effectively unbounded for the
# sole health-data store.
#
# The `production` Firestore database was created out-of-band by
# infra/scripts/bootstrap-gcp.sh (well, `(default)` was; `production` was created
# during the prod cutover — see the copy scripts). To manage its durability
# fields in Terraform WITHOUT recreating it (recreating a Firestore DB is
# destructive and guarded), we IMPORT the existing resource. The import block
# below is declarative (Terraform >= 1.5) and safe: `terraform plan` will show
# only the PITR + delete-protection field updates, never a replace.
#
# BELT-AND-SUSPENDERS: the equivalent one-time gcloud commands are documented in
# APPLY.md so PITR + delete-protection can be turned on immediately even before
# the import is run (mirrors the firestore_ttl.tf / README convention). If you
# run the gcloud commands first, the subsequent `terraform plan` is a no-op for
# those two fields.

# ---------------------------------------------------------------------------
# Import + manage the existing `production` database.
# ---------------------------------------------------------------------------
import {
  to = google_firestore_database.production
  id = "projects/${var.project_id}/databases/${var.firestore_database}"
}

resource "google_firestore_database" "production" {
  project     = var.project_id
  name        = var.firestore_database
  location_id = var.region
  type        = "FIRESTORE_NATIVE"

  # DATA-001: turn on PITR (extends version retention from 1h to 7d) and
  # delete-protection (guards against a fat-fingered `databases delete`).
  point_in_time_recovery_enablement = "POINT_IN_TIME_RECOVERY_ENABLED"
  delete_protection_state           = "DELETE_PROTECTION_ENABLED"

  # Firestore databases are effectively permanent; never let `terraform destroy`
  # take the sole health-data store with it. ABANDON keeps the DB on destroy and
  # `prevent_destroy` refuses a destroy plan outright.
  deletion_policy = "ABANDON"

  lifecycle {
    prevent_destroy = true

    # Do not fight the bootstrap over immutable creation fields (location/type
    # are set at create time and cannot change); we only manage the durability
    # fields here.
    ignore_changes = [
      location_id,
      type,
    ]
  }
}

# ---------------------------------------------------------------------------
# Backup schedules on `production`.
# Provider caps `retention` at 14 weeks. A database may hold at most one daily
# and one weekly schedule.
# ---------------------------------------------------------------------------

# Daily backups, 7-day retention — the primary short-horizon restore source.
resource "google_firestore_backup_schedule" "production_daily" {
  project  = var.project_id
  database = google_firestore_database.production.name

  # 7 days = 604800s.
  retention = "604800s"

  daily_recurrence {}
}

# Weekly backups, 14-week retention (the provider/service maximum) — long-horizon
# safety net for a corruption/bad-write only noticed weeks later.
resource "google_firestore_backup_schedule" "production_weekly" {
  project  = var.project_id
  database = google_firestore_database.production.name

  # 14 weeks = 8467200s (provider maximum).
  retention = "8467200s"

  weekly_recurrence {
    day = "SUNDAY"
  }
}

# ---------------------------------------------------------------------------
# GCS bucket versioning + noncurrent-version lifecycle (DATA-001).
#
# App buckets are created by infra/scripts/bootstrap-gcp.sh / IMPL flows, not by
# Terraform. Rather than import every bucket (and risk fighting the bootstrap's
# public-read IAM, which is intentionally left alone per SEC-012 de-scope), we
# apply versioning + lifecycle via `google_storage_bucket` resources that IMPORT
# the existing buckets. Versioning protects against object-level fat-finger /
# overwrite; the lifecycle rule expires noncurrent versions after N days so
# versioning does not grow storage without bound.
#
# NOTE: these buckets are uniform-bucket-level-access + (for image buckets)
# allUsers:objectViewer. We deliberately DO NOT set IAM here — SEC-012 (bucket
# privacy) is de-scoped to its own spec (IMPL-SEC-01). Managing only
# versioning + lifecycle keeps this change surgical.
#
# If you prefer NOT to import buckets into Terraform yet, APPLY.md documents the
# equivalent `gcloud storage buckets update --versioning` + lifecycle-file
# commands; this Terraform is then the future source of truth.
# ---------------------------------------------------------------------------

locals {
  # App buckets enumerated from infra/scripts/bootstrap-gcp.sh + application.yml
  # references cited in the cost audit (§1). Bucket names are
  # ${project}-<suffix>. Exports bucket included (holds Firestore export copies).
  versioned_bucket_names = [
    "${var.project_id}-nutrition-photos",
    "${var.project_id}-food",
    "${var.project_id}-studio",
    "${var.project_id}-equipment",
    "${var.project_id}-gym-photos",
    "${var.project_id}-exercise-media",
    "${var.project_id}-android-releases",
    "${var.project_id}-exports",
    # NOTE: `${var.project_id}-firestore-exports` is intentionally NOT here — it
    # is managed in firestore_export.tf (DEC, 2026-09-13 interview: monthly cold
    # exports), which needs an age-based lifecycle a for_each member can't carry.
  ]
}

# Import each existing bucket so Terraform manages only its versioning +
# lifecycle. Bucket import id is just the bucket name.
import {
  for_each = toset(local.versioned_bucket_names)
  to       = google_storage_bucket.versioned[each.key]
  id       = each.value
}

resource "google_storage_bucket" "versioned" {
  for_each = toset(local.versioned_bucket_names)

  project  = var.project_id
  name     = each.value
  location = var.region

  # Match the bootstrap: uniform bucket-level access. (Set on create; harmless
  # to restate for an imported bucket that already has it.)
  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }

  # Expire noncurrent (overwritten/deleted) versions after N days so versioning
  # is a safety net, not unbounded storage growth.
  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      with_state                 = "ARCHIVED"
      days_since_noncurrent_time = var.gcs_noncurrent_retention_days
    }
  }

  lifecycle {
    prevent_destroy = true

    # Do not manage anything about these buckets except versioning + lifecycle.
    # IAM/public-read, location, and storage class are owned elsewhere
    # (bootstrap + SEC-012 spec); ignore them so plan is surgical.
    ignore_changes = [
      location,
      storage_class,
      labels,
      cors,
      website,
      logging,
      encryption,
    ]
  }
}
