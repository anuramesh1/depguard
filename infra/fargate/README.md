# Fargate alternative (not used by the scripts)

The project deploys **serverless** (`infra/serverless`, via `scripts/deploy.sh`). This folder is the
container-based alternative: ECS Fargate (ARM64) behind an Application Load Balancer, about $1.40/day while up.
See the README section "Design alternatives" for when to choose it.

To deploy this stack by hand (needs Docker running):

```bash
cd infra/fargate
MY_CIDR="$(curl -fsS https://checkip.amazonaws.com)/32"
terraform init
terraform apply -var "allowed_cidr=$MY_CIDR" -target=aws_ecr_repository.app -target=aws_ecr_lifecycle_policy.app

REPO=$(aws ecr describe-repositories --repository-names depguard --query 'repositories[0].repositoryUri' --output text)
TAG=$(date +%Y%m%d-%H%M%S)
docker build --platform linux/arm64 -t "$REPO:$TAG" ../..
aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}"
docker push "$REPO:$TAG"

terraform apply -var "allowed_cidr=$MY_CIDR" -var "image_tag=$TAG"
terraform output url

# when done:
terraform destroy -var "allowed_cidr=$MY_CIDR"
```
