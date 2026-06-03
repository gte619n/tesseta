# Terraform + provider pinning for the (currently minimal) infra-as-code set.
#
# Only the Firestore TTL policy (firestore_ttl.tf, IMPL-AND-20 #9) is declared
# here today; the rest of the bootstrap is still shell-scripted (see
# ../README.md). The google provider is pinned to a version that supports
# `google_firestore_field` with `ttl_config`.

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.0.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = "us-central1"
}
