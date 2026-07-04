# Dedicated, least-privilege CI/CD service account for the Cloud Build triggers.
#
# WHY: the triggers ran as the Compute Engine default SA, which had PROJECT-WIDE
# roles/secretmanager.secretAccessor — so any build step (or a malicious
# PR-triggered build) could read EVERY secret in the project (session signing
# key, OAuth secrets, KMS-adjacent material, Gemini key), not just the ones its
# pipeline needs. This SA gets secret access PER-SECRET instead.

resource "google_service_account" "ci" {
  project      = var.project_id
  account_id   = "tesseta-ci"
  display_name = "Tesseta CI/CD (Cloud Build)"
  description  = "Least-privilege deployer for the backend/web/android pipelines."
}

# Deploy + image push + app-distribution are capabilities (not secret access),
# so project scope is acceptable here — the point is that this SA has NO
# project-wide secret access.
resource "google_project_iam_member" "ci_run_admin" {
  project = var.project_id
  role    = "roles/run.admin"
  member  = "serviceAccount:${google_service_account.ci.email}"
}

resource "google_project_iam_member" "ci_artifact_writer" {
  project = var.project_id
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.ci.email}"
}

resource "google_project_iam_member" "ci_firebase_distro" {
  project = var.project_id
  role    = "roles/firebaseappdistro.admin"
  member  = "serviceAccount:${google_service_account.ci.email}"
}

# Log writer so builds can write to Cloud Logging (options: CLOUD_LOGGING_ONLY).
resource "google_project_iam_member" "ci_log_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.ci.email}"
}

# Standard build-runner role, required for the Cloud Build triggers to execute
# as this SA (infra/triggers/*.yaml point their serviceAccount here).
resource "google_project_iam_member" "ci_builds_builder" {
  project = var.project_id
  role    = "roles/cloudbuild.builds.builder"
  member  = "serviceAccount:${google_service_account.ci.email}"
}

# Act-as the runtime SA on Cloud Run deploys.
resource "google_service_account_iam_member" "ci_actas_runtime" {
  service_account_id = "projects/${var.project_id}/serviceAccounts/${var.runtime_service_account}@${var.project_id}.iam.gserviceaccount.com"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.ci.email}"
}

# THE FIX: per-secret accessor for exactly the secrets the three pipelines read
# (their union), instead of a project-wide grant.
locals {
  ci_secret_ids = toset(concat(var.backend_secrets, var.web_secrets, var.android_secrets))
}

resource "google_secret_manager_secret_iam_member" "ci_secret_access" {
  for_each  = local.ci_secret_ids
  project   = var.project_id
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.ci.email}"
}

output "ci_service_account_email" {
  description = "Point the Cloud Build triggers (infra/triggers/*.yaml) at this SA."
  value       = google_service_account.ci.email
}
