# Staging environment (opt-in via var.enable_staging).
#
# The persistent piece Terraform owns is a separate Firestore DATABASE, so
# staging data never touches production. The staging Cloud Run services are
# deployed by the pipeline (a staging cloudbuild / branch) targeting the
# `-staging` service names with FIRESTORE_DATABASE_ID=staging — Cloud Run service
# lifecycle is intentionally NOT Terraform-managed to avoid fighting the deploy
# pipeline over the image tag. See ../README.md for the promote-on-green flow.

resource "google_firestore_database" "staging" {
  count = var.enable_staging ? 1 : 0

  project     = var.project_id
  name        = "staging"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"

  # Firestore databases can't be casually deleted; keep the default ABANDON and
  # a Terraform guard so `terraform destroy` can't take staging data with it.
  deletion_policy = "ABANDON"

  lifecycle {
    prevent_destroy = true
  }
}

output "staging_firestore_database" {
  description = "Staging Firestore database id (set FIRESTORE_DATABASE_ID to this on staging Cloud Run)."
  value       = var.enable_staging ? google_firestore_database.staging[0].name : null
}
