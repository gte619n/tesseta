# OBS-001 (audit 2026-09-13, CRITICAL) — production alerting.
#
# Verified live on 2026-09-13: the project had ZERO alert policies, ZERO uptime
# checks, ZERO notification channels, ZERO log-based metrics. Every failure was
# discovered by the operator noticing app misbehavior (root cause of the four
# cited silent incidents: weeks of failed deploys, dead FCM, Cloud Tasks 401
# loop, webhook-as-probe drops).
#
# This file adds: 1 email notification channel; a backend 5xx error-rate alert;
# an uptime check on the public /actuator/health endpoint + an alert on it; a
# log-based metric + alert for Cloud Build deploy failures; a log-based
# metric + alert for Cloud Run Job execution failures; and (operator-gated) a
# billing budget alert (OBS-010 / COST-003).
#
# All resource arg names verified 2026-09-13 against the hashicorp/google
# provider docs (github website/docs/r/*.markdown) for the >= 5.0.0 pin in
# versions.tf.
#
# We deliberately DO NOT manage Cloud Run services here (they are deploy-managed;
# see infra/terraform/README.md follow-ups).

# ---------------------------------------------------------------------------
# Notification channel (email → operator).
# ---------------------------------------------------------------------------
resource "google_monitoring_notification_channel" "email" {
  project      = var.project_id
  display_name = "Operator email (audit remediation OBS-001)"
  type         = "email"

  labels = {
    email_address = var.notification_email
  }
}

# ---------------------------------------------------------------------------
# (a) Backend Cloud Run 5xx error-rate alert.
# Uses the Cloud Run built-in request_count metric filtered to 5xx responses —
# zero app code needed. Alerts when 5xx responses exceed the threshold rate.
# ---------------------------------------------------------------------------
resource "google_monitoring_alert_policy" "backend_5xx" {
  project      = var.project_id
  display_name = "Backend Cloud Run 5xx elevated (${var.backend_run_service})"
  combiner     = "OR"

  conditions {
    display_name = "5xx response rate > 0.05/s over 5m"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type = \"cloud_run_revision\"",
        "resource.label.\"service_name\" = \"${var.backend_run_service}\"",
        "metric.type = \"run.googleapis.com/request_count\"",
        "metric.label.\"response_code_class\" = \"5xx\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0.05
      duration        = "300s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_RATE"
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    subject = "Backend 5xx elevated"
    content = "The backend Cloud Run service ${var.backend_run_service} is returning 5xx responses above the threshold. Check the latest revision / recent deploy and Cloud Logging (severity>=ERROR)."
  }
}

# ---------------------------------------------------------------------------
# (b) Uptime check on the public backend health endpoint + alert.
# /actuator/health is public per the audit (application.yml exposes health,info).
# ---------------------------------------------------------------------------
resource "google_monitoring_uptime_check_config" "backend_health" {
  project      = var.project_id
  display_name = "Backend /actuator/health"
  timeout      = "10s"
  period       = "300s"

  http_check {
    path           = "/actuator/health"
    port           = 443
    use_ssl        = true
    validate_ssl   = true
    request_method = "GET"
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project_id
      host       = var.backend_uptime_host
    }
  }
}

resource "google_monitoring_alert_policy" "backend_health_uptime" {
  project      = var.project_id
  display_name = "Backend health check failing"
  combiner     = "OR"

  conditions {
    display_name = "Uptime check failed"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type = \"uptime_url\"",
        "metric.type = \"monitoring.googleapis.com/uptime_check/check_passed\"",
        "metric.label.\"check_id\" = \"${google_monitoring_uptime_check_config.backend_health.uptime_check_id}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 1
      duration        = "300s"

      aggregations {
        alignment_period     = "1200s"
        per_series_aligner   = "ALIGN_NEXT_OLDER"
        cross_series_reducer = "REDUCE_COUNT_FALSE"
        group_by_fields      = ["resource.label.\"host\""]
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    subject = "Backend health check failing"
    content = "The uptime check against https://${var.backend_uptime_host}/actuator/health is failing from one or more regions. The backend may be down or the latest revision unhealthy."
  }
}

# ---------------------------------------------------------------------------
# (c) Cloud Build deploy-failure alert (the silent-deploy pain, OBS-004).
# A log-based COUNTER metric on Cloud Build audit logs where the build finished
# with status FAILURE / INTERNAL_ERROR / TIMEOUT, plus a >0 alert. This is the
# cleanest fully-Terraform approach (no Pub/Sub notifier plumbing needed).
# ---------------------------------------------------------------------------
resource "google_logging_metric" "cloud_build_failures" {
  project = var.project_id
  name    = "cloud_build_failures"
  filter = join(" AND ", [
    "resource.type = \"build\"",
    "(jsonPayload.status = \"FAILURE\" OR jsonPayload.status = \"INTERNAL_ERROR\" OR jsonPayload.status = \"TIMEOUT\")",
  ])

  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
  }
}

resource "google_monitoring_alert_policy" "cloud_build_failures" {
  project      = var.project_id
  display_name = "Cloud Build deploy failure"
  combiner     = "OR"

  conditions {
    display_name = "Any Cloud Build FAILURE"

    condition_threshold {
      filter          = "resource.type = \"build\" AND metric.type = \"logging.googleapis.com/user/${google_logging_metric.cloud_build_failures.name}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_DELTA"
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    subject = "Cloud Build deploy failed"
    content = "A Cloud Build finished with FAILURE/INTERNAL_ERROR/TIMEOUT. Historically deploy failures went unnoticed for weeks (path-filtered checks go neutral and hide a red main). Check the build log and the deploy-*-on-main triggers."
  }
}

# ---------------------------------------------------------------------------
# (d) Cloud Run Job execution-failure alert (OBS-001 item; the gh-refresh /
# withings-refresh / goals-sustained / gh-health-check jobs). Log-based counter
# on ERROR-severity logs from cloud_run_job resources + a >0 alert.
# ---------------------------------------------------------------------------
resource "google_logging_metric" "cloud_run_job_errors" {
  project = var.project_id
  name    = "cloud_run_job_errors"
  filter = join(" AND ", [
    "resource.type = \"cloud_run_job\"",
    "severity >= ERROR",
  ])

  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
  }
}

resource "google_monitoring_alert_policy" "cloud_run_job_errors" {
  project      = var.project_id
  display_name = "Cloud Run Job errors"
  combiner     = "OR"

  conditions {
    display_name = "Cloud Run Job severity>=ERROR"

    condition_threshold {
      filter          = "resource.type = \"cloud_run_job\" AND metric.type = \"logging.googleapis.com/user/${google_logging_metric.cloud_run_job_errors.name}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_DELTA"
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email.id]

  documentation {
    subject = "Cloud Run Job error"
    content = "A scheduled Cloud Run Job (gh-refresh / withings-refresh / goals-sustained-reeval / gh-health-check) logged an ERROR. Note the '|| true' job-image update steps in backend/cloudbuild.yaml can leave a job pinned to a stale image (OBS-004)."
  }
}

# ---------------------------------------------------------------------------
# (d/OBS-010/COST-003) Billing budget alert.
# Operator-gated: needs var.billing_account_id (no default) AND
# roles/billing.costsManager on the billing account for the applying identity.
# When billing_account_id is empty this resource is NOT created (count = 0), so
# the rest of monitoring.tf applies without billing-account permissions.
#
# Budgets ALERT on spend; they do NOT stop it. Pair with the SEC-001 rate
# limiter (out of scope for infra).
# ---------------------------------------------------------------------------
data "google_project" "this" {
  project_id = var.project_id
}

resource "google_billing_budget" "monthly" {
  count = var.billing_account_id == "" ? 0 : 1

  billing_account = var.billing_account_id
  display_name    = "health-fitness-160 monthly budget (audit OBS-010)"

  budget_filter {
    projects = ["projects/${data.google_project.this.number}"]
  }

  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.monthly_budget_usd)
    }
  }

  threshold_rules {
    threshold_percent = 0.5
    spend_basis       = "CURRENT_SPEND"
  }
  threshold_rules {
    threshold_percent = 0.9
    spend_basis       = "CURRENT_SPEND"
  }
  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "CURRENT_SPEND"
  }
  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "FORECASTED_SPEND"
  }

  all_updates_rule {
    monitoring_notification_channels = [google_monitoring_notification_channel.email.id]
    disable_default_iam_recipients   = false
  }
}
