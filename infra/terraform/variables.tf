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

# The production runtime database is the NAMED `production` database, NOT
# `(default)`. `(default)` still exists as a populated dev/second copy (see
# COST-006 / DATA H1) but no production infra-as-code should target it.
variable "firestore_database" {
  description = "Firestore database that holds production traffic (named DB, not '(default)')."
  type        = string
  default     = "production"
}

# --- OBS-001 / DATA-001 notification + alerting inputs ---

variable "notification_email" {
  description = "Operator email that receives Cloud Monitoring alerts + budget notifications."
  type        = string
  default     = "evan.ruff@oxos.com"
}

variable "backend_uptime_host" {
  description = "Public host for the backend uptime check (probes /actuator/health over HTTPS)."
  type        = string
  default     = "api.tesseta.com"
}

variable "web_uptime_host" {
  description = "Public host for the web frontend uptime check."
  type        = string
  default     = "app.tesseta.com"
}

variable "backend_run_service" {
  description = "Cloud Run service name for the backend (used in the 5xx error-rate alert filter)."
  type        = string
  default     = "health-fitness-backend"
}

# --- OBS-010 / COST-003 billing budget inputs ---
# No default: the operator MUST supply the billing account id, and applying the
# budget requires roles/billing.costsManager (or budgets.editor) on that
# account for the applying identity. Budgets ALERT on spend; they do NOT stop
# it — pair with the SEC-001 rate limiter (out of scope here).

variable "billing_account_id" {
  description = <<-EOT
    Billing account id that funds project health-fitness-160, e.g.
    "0008AE-458280-6056E2". Required to create the budget alert; leave the
    monitoring.tf budget resource un-applied (comment it or target-exclude it)
    until this is supplied. Applying identity needs roles/billing.costsManager
    on the billing account.
  EOT
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  description = "Monthly budget amount in whole USD for the billing budget alert."
  type        = number
  default     = 150
}

variable "gcs_noncurrent_retention_days" {
  description = "Days to retain noncurrent GCS object versions before expiry (DATA-001 versioning safety net)."
  type        = number
  default     = 30
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
    "platform-rsa-private-key",
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
