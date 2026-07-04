# Shared inputs for the infra-as-code set.

variable "project_id" {
  description = "GCP / Firebase project that owns the deployment."
  type        = string
  default     = "health-fitness-160"
}

variable "region" {
  description = "Cloud Run + Artifact Registry region."
  type        = string
  default     = "us-central1"
}

variable "runtime_service_account" {
  description = "Runtime SA the Cloud Run services run as (already exists)."
  type        = string
  default     = "health-fitness-runtime"
}

variable "enable_staging" {
  description = <<-EOT
    Create staging resources. Gated OFF by default because it provisions a
    staging Firestore database, which is effectively permanent (Firestore
    deletion is heavily guarded). Flip to true and apply only when you want a
    staging environment.
  EOT
  type        = bool
  default     = false
}

# Per-secret access is granted to the dedicated CI service account instead of a
# project-wide roles/secretmanager.secretAccessor (which let ANY build read
# EVERY secret). Grouped by which pipeline needs them so the grants are minimal.
variable "backend_secrets" {
  description = "Secret Manager secret ids the backend deploy reads."
  type        = list(string)
  default = [
    "oauth-allowed-audiences",
    "oauth-web-client-id",
    "oauth-web-client-secret",
    "google-health-webhook-secret",
    "gemini_api_key",
    "session-signing-key",
  ]
}

variable "web_secrets" {
  description = "Secret Manager secret ids the web deploy reads."
  type        = list(string)
  default = [
    "oauth-web-client-id",
    "oauth-web-client-secret",
    "authjs-secret",
  ]
}

variable "android_secrets" {
  description = "Secret Manager secret ids the android build reads."
  type        = list(string)
  default = [
    "android-release-keystore",
    "android-release-keystore-password",
    "android-release-key-password",
    "oauth-web-client-id",
    "firebase-android-app-id",
    "gemini_api_key",
  ]
}
