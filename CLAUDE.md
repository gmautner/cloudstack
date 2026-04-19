# CloudStack Development

## Git Conventions

Two-branch workflow:

- **`feature/aws-s3-object-storage-provider`** — PR-clean branch. Only commits that belong in an upstream pull request go here. No local configs, no dev tooling, no credentials.
- **`dev/local-config`** — Personal branch, always rebased on top of the feature branch. Contains local dev environment configs (CLAUDE.md, db.properties, mise.toml, etc.) that should not go upstream.

Rules:
- When committing, decide whether the change is upstream-worthy or local-only and commit to the appropriate branch.
- `dev/local-config` must always contain all commits from the feature branch plus its own local-config commits on top. After new commits on the feature branch, rebase: `git checkout dev/local-config && git rebase feature/aws-s3-object-storage-provider`.
- When working, check out `dev/local-config` so local configs are active. Switch to the feature branch only for committing upstream-worthy changes.
- CLAUDE.md is a local-config file (this file lives on `dev/local-config` only for new changes).

## AWS S3 Object Storage Provider

- AWS credentials for the dev environment are in `~/.env.s3-cloudstack-provider-dev`. Source this file before running AWS CLI commands for this project. Always include `AWS_SESSION_TOKEN=""` to avoid using a stale token from other sessions.
- Dedicated AWS account `211125662649`, IAM user `s3-cloudstack-provider-dev`.
- IAM role for STS AssumeRole: `arn:aws:iam::211125662649:role/cloudstack-s3-dev`
- PRD: `docs/PRD-aws-s3-object-storage-provider.md`
- Feature branch: `feature/aws-s3-object-storage-provider` on fork `gmautner/cloudstack`.
