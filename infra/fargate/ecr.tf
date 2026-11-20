# Private registry for the app's container image.

resource "aws_ecr_repository" "app" {
  name                 = var.app_name
  image_tag_mutability = "IMMUTABLE" # a tag always means the same image
  force_delete         = true        # lets teardown delete the repo even when it holds images

  image_scanning_configuration {
    scan_on_push = true # free basic scan for OS-package CVEs
  }
}

# Keep only the last 5 images so storage cost stays near zero.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}
