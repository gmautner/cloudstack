# CloudStack Development

## AWS S3 Object Storage Provider

- AWS credentials for the dev environment are in `~/.env.s3-cloudstack-provider-dev`. Source this file before running AWS CLI commands for this project. Always include `AWS_SESSION_TOKEN=""` to avoid using a stale token from other sessions.
- Dedicated AWS account `211125662649`, IAM user `s3-cloudstack-provider-dev`.
- IAM role for STS AssumeRole: `arn:aws:iam::211125662649:role/cloudstack-s3-dev`
- PRD: `docs/PRD-aws-s3-object-storage-provider.md`
- Feature branch: `feature/aws-s3-object-storage-provider` on fork `gmautner/cloudstack`.
