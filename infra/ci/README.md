# Running the evals in GitHub Actions

The CI workflow ([.github/workflows/ci.yml](../../.github/workflows/ci.yml)) always runs the free test suite.
Its `evals` job is switched off until GitHub can get AWS credentials. To switch it on:

1. Create the IAM role that GitHub may assume (OIDC: short-lived credentials, no stored AWS keys):
   ```bash
   terraform -chdir=infra/ci init
   terraform -chdir=infra/ci apply
   # If the account already has a GitHub OIDC provider: add -var create_oidc_provider=false
   ```
2. In GitHub: **Settings → Secrets and variables → Actions → Variables → New repository variable**,
   name `AWS_EVAL_ROLE_ARN`, value = the `role_arn` output. (It's an identifier, not a secret.)
3. Run it: **Actions → CI → Run workflow**, tick **run_evals**. Each run costs about $1 of Bedrock usage;
   the scorecards are attached to the run as the `eval-scorecards` artifact.

What the role can do: invoke the three Bedrock models the evals use (Claude Haiku 4.5, Amazon Nova Pro,
Titan Text Embeddings v2), only from workflows on this repository's `main` branch. Nothing else.

To remove it: `terraform -chdir=infra/ci destroy`, and delete the repository variable.
The Terraform state stays local and gitignored, like `infra/serverless/`.
