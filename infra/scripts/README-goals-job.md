# Goals SUSTAINED re-evaluation — Cloud Run Job

The daily background pass for IMPL-12 Goals. Re-evaluates every SUSTAINED
Step across every user so windowed conditions (e.g., "resting HR &lt; 55 for
30 days") flip from undone to done when the window finally holds.

## Components

- **Cloud Run Job** `goals-sustained-reeval` — re-uses the backend
  service's Docker image. Activates Spring profile `job-sustained`,
  which loads `ReevaluateSustainedJob` (a `CommandLineRunner`).
- **Cloud Scheduler entry** `goals-sustained-reeval-daily` — fires
  daily at 03:00 America/New_York and POSTs to the Job's `:run`
  endpoint, authenticated as the runtime service account.
- **Runtime service account**
  `health-fitness-runtime@health-fitness-160.iam.gserviceaccount.com`
  (already has `roles/datastore.user` from `bootstrap-gcp.sh`).

## One-time bootstrap

Run the scripts in this order from the repo root:

```bash
bash infra/scripts/deploy-goals-sustained-job.sh
bash infra/scripts/bootstrap-goals-scheduler.sh
```

After this, each backend Cloud Build run updates the Job's image (see
the `update-goals-sustained-job` step in `backend/cloudbuild.yaml`).

## Ad-hoc execution

```bash
gcloud run jobs execute goals-sustained-reeval --region us-central1 --wait
```

## Reading logs

```bash
gcloud logging read \
  'resource.type=cloud_run_job AND resource.labels.job_name=goals-sustained-reeval' \
  --project=health-fitness-160 --limit=50 --format=json
```

Or open it in the console:
<https://console.cloud.google.com/run/jobs/details/us-central1/goals-sustained-reeval/logs?project=health-fitness-160>
