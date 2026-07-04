# Remote Terraform state in a versioned, locked GCS bucket — replaces the local
# terraform.tfstate the prior single-file setup implied (unshared, unlocked, a
# data-loss risk). The bucket is created once out-of-band before `terraform
# init` (it can't be Terraform-managed here without a chicken-and-egg); see
# ../README.md for the create + migrate runbook.
terraform {
  backend "gcs" {
    bucket = "health-fitness-160-tf-state"
    prefix = "infra"
  }
}
