# terraform

AWS infrastructure for CardFlow as code. **Written and tested, not deployed** (deploying costs money; see the README's "Deploying to AWS").

| Stack | What it creates | State |
|---|---|---|
| [`bootstrap/`](bootstrap/) | Terraform state bucket, $10/month budget alert, GitHub OIDC provider | local (it creates the bucket) |
| [`app/`](app/) | EC2 (Compose stack), security group, ECR repos, artifacts bucket, SSM parameters, instance role, GitHub deploy role | S3, native locking |

## Test it (offline, free, no AWS account needed)
```bash
make tf-test
```
Runs `terraform fmt -check`, `validate` and `test` for both stacks against a **mocked** AWS provider: no credentials, nothing is created. The tests check security properties (only the dashboard port open and only to one network, least-privilege IAM, OIDC trust limited to `main`, encrypted private buckets, immutable scanned images).

## Deploy it (costs money, ~$0.07/hour while running)
See the "Deploying to AWS" section of the top-level README and [ADR 0014](../../docs/adr/0014-ec2-compose-not-ecs.md) / [ADR 0015](../../docs/adr/0015-iam-and-oidc.md).
