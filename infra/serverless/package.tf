# The Lambda package goes through a private S3 bucket.
#
# Why: direct zip uploads are capped at 50 MB, and Spring Boot + Spring AI + the AWS SDK is already ~45 MB.
# Through S3 the limit is 250 MB unzipped. The bucket holds one object and costs a fraction of a cent.

resource "aws_s3_bucket" "artifacts" {
  bucket_prefix = "${var.app_name}-artifacts-"
  force_destroy = true # lets teardown delete it even though it holds the package
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_object" "package" {
  bucket = aws_s3_bucket.artifacts.id
  key    = "lambda/depguard-lambda.zip"
  source = var.package_path
  etag   = filemd5(var.package_path) # re-upload only when the zip changes
}
