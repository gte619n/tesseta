# DATA-001 addendum (operator decision, 2026-09-13 interview): long-term COLD
# exports of the `production` Firestore database to GCS, on top of the managed
# backup schedules in firestore_backup.tf.
#
# WHY, on top of backups: managed backup schedules cap at 14 weeks (weekly) /
# 7 days (daily). A monthly GCS export gives a portable, longer-horizon,
# storage-cheap copy (survives a project/database-level loss the managed backups
# don't, and is restorable into any database via `gcloud firestore import`).
#
# Mechanism: a Cloud Scheduler job POSTs to the Firestore export REST API once a
# month, authenticated as a dedicated service account, writing to the
# `-firestore-exports` bucket under a `scheduled/` prefix. Old exports expire via
# an age-based lifecycle so cold retention is bounded, not unbounded.
#
# NOT APPLIED this session (GCP ADC unavailable — see APPLY.md). Cloud Scheduler
# must be available in the project's region; verify on first `terraform apply`.

# Retention for scheduled cold exports (days). 365 keeps a year of monthly
# snapshots; each is storage-class-cheap. Declared here to keep this change
# cohesive; move to variables.tf if you prefer.
variable "firestore_export_retention_days" {
  type        = number
  default     = 365
  description = "Days to retain scheduled Firestore GCS exports before lifecycle deletion."
}

# ---------------------------------------------------------------------------
# The exports bucket — managed HERE (pulled out of firestore_backup.tf's
# `versioned` for_each) so it can carry an age-based lifecycle for the exports.
# ---------------------------------------------------------------------------
import {
  to = google_storage_bucket.firestore_exports
  id = "${var.project_id}-firestore-exports"
}

resource "google_storage_bucket" "firestore_exports" {
  project  = var.project_id
  name     = "${var.project_id}-firestore-exports"
  location = var.region

  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }

  # Bound cold-export storage: delete scheduled export objects older than N days.
  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = var.firestore_export_retention_days
    }
  }

  lifecycle {
    prevent_destroy = true
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

# ---------------------------------------------------------------------------
# Dedicated service account for the scheduled export (least privilege).
# ---------------------------------------------------------------------------
resource "google_service_account" "firestore_export" {
  project      = var.project_id
  account_id   = "firestore-export"
  display_name = "Firestore scheduled cold export (DATA-001)"
}

# Needs the managed import/export admin role to trigger exportDocuments.
resource "google_project_iam_member" "firestore_export_admin" {
  project = var.project_id
  role    = "roles/datastore.importExportAdmin"
  member  = "serviceAccount:${google_service_account.firestore_export.email}"
}

# ...and write access to the exports bucket.
resource "google_storage_bucket_iam_member" "firestore_export_writer" {
  bucket = google_storage_bucket.firestore_exports.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.firestore_export.email}"
}

# ---------------------------------------------------------------------------
# Monthly export job: 04:00 UTC on the 1st of each month.
# ---------------------------------------------------------------------------
resource "google_cloud_scheduler_job" "firestore_monthly_export" {
  name             = "firestore-monthly-export"
  project          = var.project_id
  region           = var.region
  schedule         = "0 4 1 * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "320s"

  http_target {
    http_method = "POST"
    uri         = "https://firestore.googleapis.com/v1/projects/${var.project_id}/databases/${var.firestore_database}:exportDocuments"

    headers = {
      "Content-Type" = "application/json"
    }

    # Export the whole database to a timestamped path is handled by Firestore;
    # we pin the bucket + prefix. (Firestore writes a timestamped subfolder.)
    body = base64encode(jsonencode({
      outputUriPrefix = "gs://${google_storage_bucket.firestore_exports.name}/scheduled"
    }))

    oauth_token {
      service_account_email = google_service_account.firestore_export.email
      scope                 = "https://www.googleapis.com/auth/datastore"
    }
  }

  depends_on = [
    google_project_iam_member.firestore_export_admin,
    google_storage_bucket_iam_member.firestore_export_writer,
  ]
}
