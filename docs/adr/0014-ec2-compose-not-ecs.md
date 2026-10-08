# 14. AWS target: one EC2 instance running Docker Compose, written as Terraform but not deployed

- **Status:** Accepted
- **Date:** 2026-10-08

## Context
PLAN.md calls for deploying CardFlow to AWS with Terraform and CI/CD, at hobby-project cost. The stack is 7 long-running containers plus Postgres and Kafka. Measured locally they fit in about 3.8 GB of RAM. The owner's constraint changed during the phase: the AWS account lost its free-plan credits, and **the project must cost $0**. So the infrastructure is written, reviewed and tested as if it would be deployed, but nothing is applied.

## Decision
- **One `t4g.large` (2 vCPU, 8 GB, ARM/Graviton)** running the existing Compose stack, with an AWS override file. ARM matches the images we already build on Apple silicon. `t4g.medium` (4 GB) would leave no headroom over the 3.8 GB we measured.
- **Kafka and Postgres stay in containers** on that instance. No MSK, no RDS.
- **Default VPC, public subnet:** no NAT gateway, no load balancer. Outbound HTTPS only. Inbound: the dashboard port from one network (validated to /24 or narrower) and nothing else, **no SSH**. Shell access and deploys go through SSM.
- **Cost guards in the code:** `cpu_credits = "standard"` (T4g's default "unlimited" can bill for extra CPU), ECR lifecycle keeps 5 images, release files expire after 30 days, a $10 budget alert exists before anything else (bootstrap stack), container logs are size-capped.
- **Not deployed.** The Terraform is checked by `terraform validate`, 14 `terraform test` runs against a **mocked** AWS provider, tflint, and a Trivy misconfiguration scan, all in CI with no AWS credentials. The deploy workflow is complete but every job is skipped unless `DEPLOY_ENABLED` is set.

## Cost if it were deployed (us-east-1, on-demand, October 2026)
| Item | Running 24/7 | Stopped between demos |
|---|---|---|
| t4g.large | $0.0672/h ≈ $49/month | $0 while stopped |
| 30 GB gp3 | $2.40/month | $2.40 (disk persists) |
| Public IPv4 | $0.005/h ≈ $3.60/month | $0 while stopped |
| ECR, S3, SSM (standard), budget | < $1 | < $1 |
| **Total** | **≈ $56/month** | **≈ $3–4/month + $0.07 per running hour** |

## Alternatives considered
Monthly figures are approximate us-east-1 list prices, for scale only.

| Option | Why not (here) | When it would be right |
|---|---|---|
| **ECS on Fargate** | ~3 vCPU / 8 GB of tasks ≈ $90+/month, plus a load balancer (~$16) to reach it | Several instances, autoscaling, no servers to patch |
| **EKS** | $73/month for the control plane alone, before any nodes | Many services and teams, Kubernetes skills already in house |
| **MSK** (managed Kafka) | Smallest provisioned cluster (2–3 small brokers) ≈ $70–100/month before storage | Production event streams needing multi-AZ durability |
| **RDS** (managed Postgres) | ~$12–15/month for the smallest instance, per database server | Backups, failover and patching you don't want to own |
| **NAT gateway + private subnet** | ~$33/month plus data charges | Instances that must have no public IP |
| **Lightsail / a VPS** | Cheaper, but teaches less of the AWS IAM/VPC model that interviews ask about | Pure cost minimisation |

## Consequences
- A single instance is a **single point of failure**: no multi-AZ, and Postgres/Kafka data lives on one EBS volume. That's acceptable for a demo and would be the first thing to change for real money (RDS + MSK, or at least EBS snapshots).
- The instance has a public IP (needed without NAT). It's protected by the security group, IMDSv2 and having no SSH, but it's still more exposed than a private subnet.
- Trivy flags the outbound rule and the use of S3-managed encryption keys; both are accepted, scoped exceptions in `infra/terraform/.trivyignore.yaml` with the cost reason.
- "Not deployed" means some things are unproven: AMI boot, IMDS hop limit with containers, real Bedrock calls, deploy timing. The tests prove the configuration's *properties*, not that AWS accepts every value. Deploying is `terraform apply` + four repository variables when the cost is acceptable.
