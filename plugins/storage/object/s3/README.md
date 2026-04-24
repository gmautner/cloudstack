<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# AWS S3 Object Storage Plugin

## Plugin Purpose

This plugin implements the Object Storage DataStore for AWS S3. It allows CloudStack operators to offer S3-compatible object storage backed by a real AWS S3 region, with per-tenant bucket isolation using STS AssumeRole with scoped session policies.

Unlike the MinIO provider (which manages a self-hosted instance), this provider delegates bucket storage to AWS S3 and ships with a companion service — the **S3 middleware** — that handles all AWS interaction on behalf of CloudStack.

## Architecture

```
CloudStack Management Server
  AWSS3ObjectStoreDriverImpl
        | Admin API (HTTP)
        v
  S3 Middleware (single Go binary)
    :8090  Admin API    (CloudStack driver calls this)
    :8085  STS endpoint (tenant credential vending)
    :9000  S3 proxy     (reverse proxy to real S3)
        |
        v
    Postgres (credentials, bucket-account mappings)
        |
        v
     AWS S3 / STS (service account credentials)
```

The CloudStack management server does **not** hold AWS credentials. All AWS interaction goes through the S3 middleware.

Two client access modes are supported simultaneously:

| Mode | Description | Data Path |
|------|-------------|-----------|
| **STS Direct** | Client obtains temporary AWS credentials via the STS proxy, then talks directly to S3 | Client -> STS proxy -> Client -> S3 |
| **S3 Proxy** | All S3 operations go through the reverse proxy | Client -> S3 proxy -> S3 |

## Prerequisites

### 1. AWS Account

Create or designate a dedicated AWS account for CloudStack-managed object storage. Using a dedicated account isolates CloudStack buckets from other infrastructure.

### 2. IAM User (Service Account)

Create an IAM user with programmatic access in the dedicated account. This user is the service account whose credentials are configured in the S3 middleware.

**Required permissions** — attach the following inline policy:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "S3FullAccess",
            "Effect": "Allow",
            "Action": "s3:*",
            "Resource": "*"
        },
        {
            "Sid": "STSAssumeRole",
            "Effect": "Allow",
            "Action": "sts:AssumeRole",
            "Resource": "arn:aws:iam::<ACCOUNT_ID>:role/<ROLE_NAME>"
        }
    ]
}
```

Generate an access key pair for this user and save the credentials securely.

### 3. IAM Role (for STS AssumeRole)

Create an IAM role in the same account. This role is assumed by the middleware to issue scoped temporary credentials to tenants.

**Trust policy** — allow only the service account to assume the role:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "AWS": "arn:aws:iam::<ACCOUNT_ID>:user/<SERVICE_ACCOUNT_USER>"
            },
            "Action": "sts:AssumeRole"
        }
    ]
}
```

**Permission policy** — the role needs broad S3 permissions. Actual per-tenant scoping is done via session policies at AssumeRole time, not by the role's own policy:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": "s3:*",
            "Resource": "*"
        }
    ]
}
```

Note the role ARN (e.g., `arn:aws:iam::<ACCOUNT_ID>:role/<ROLE_NAME>`) — it is needed for the middleware configuration.

### 4. S3 Middleware

The S3 middleware is a single Go binary available at [github.com/gmautner/s3-middleware](https://github.com/gmautner/s3-middleware). It requires a Postgres database.

#### Postgres Database

Run Postgres locally or use an existing instance:

```bash
podman run -d --name s3mw-postgres -p 5432:5432 \
  -e POSTGRES_DB=s3middleware -e POSTGRES_USER=s3mw -e POSTGRES_PASSWORD=s3mw \
  postgres:17
```

The middleware auto-creates its schema (tables `accounts` and `buckets`) on startup.

#### Building and Running the Middleware

```bash
git clone https://github.com/gmautner/s3-middleware.git
cd s3-middleware
go build -o s3-middleware .
```

Run with environment variables:

```bash
export DATABASE_URL="postgres://s3mw:s3mw@localhost:5432/s3middleware?sslmode=disable"
export AWS_ACCESS_KEY_ID="<service account access key>"
export AWS_SECRET_ACCESS_KEY="<service account secret key>"
export AWS_ROLE_ARN="arn:aws:iam::<ACCOUNT_ID>:role/<ROLE_NAME>"
export AWS_REGION="sa-east-1"          # target S3 region
export ADMIN_API_KEY="<choose a secret key>"
export ADMIN_ALLOWED_IPS="127.0.0.1"   # comma-separated, add ::1 for macOS localhost
export STS_LISTEN=":8085"              # STS endpoint listen address
export S3_LISTEN=":9000"               # S3 proxy listen address
export ADMIN_LISTEN=":8090"            # admin API listen address

./s3-middleware
```

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DATABASE_URL` | Yes | — | Postgres connection string |
| `AWS_ACCESS_KEY_ID` | Yes | — | Service account access key |
| `AWS_SECRET_ACCESS_KEY` | Yes | — | Service account secret key |
| `AWS_ROLE_ARN` | Yes | — | IAM role ARN for STS AssumeRole |
| `ADMIN_API_KEY` | Yes | — | Bearer token for admin API authentication |
| `AWS_REGION` | No | `sa-east-1` | Target S3 region |
| `ADMIN_LISTEN` | No | `:8090` | Admin API listen address |
| `STS_LISTEN` | No | `:8085` | STS endpoint listen address |
| `S3_LISTEN` | No | `:9000` | S3 proxy endpoint listen address |
| `ADMIN_ALLOWED_IPS` | No | `127.0.0.1` | Comma-separated IP allowlist for admin API |

Verify the middleware is running:

```bash
curl -H "Authorization: Bearer <ADMIN_API_KEY>" http://localhost:8090/admin/health
# {"status":"healthy"}
```

## CloudStack Configuration

### Adding the Object Store

A new `AWS-S3` Object Store can be added by the CloudStack admin user via **UI -> Infrastructure -> Object Storage -> Add Object Storage**.

Select `AWS-S3` as the provider and fill in the following fields:

| Field | Maps to Detail Key | Required | Description | Example |
|-------|-------------------|----------|-------------|---------|
| Name | — | Yes | Display name for the object store | `aws-s3-prod` |
| URL | — | Yes | Display URL (shown in admin details) | `https://s3.sa-east-1.amazonaws.com` |
| Middleware Admin URL | `adminurl` | Yes | S3 middleware admin API endpoint | `http://s3-middleware:8090` |
| Middleware Admin API Key | `apikey` | Yes | Bearer token matching `ADMIN_API_KEY` | `sk-...` |
| AWS Region | `region` | Yes | Target AWS region | `sa-east-1` |
| STS Endpoint URL | `sts-endpoint` | No | STS proxy URL (shown to users) | `https://sts.example.com:8085` |
| S3 Proxy Endpoint URL | `s3-endpoint` | No | S3 proxy URL (shown to users) | `https://s3proxy.example.com:9090` |
| IAM Role ARN | `role-arn` | No | IAM role ARN (shown to users for STS config) | `arn:aws:iam::123456789012:role/cs-s3` |

The lifecycle `initialize()` method validates connectivity by calling the middleware's `/admin/health` endpoint.

### Object Store Details

Once added, the configuration parameters are stored in the `object_store_details` table:

```
Details MAP
+++++++++++++++++++++++++++++++++++++++++++
| Key          | Value                     |
|--------------|---------------------------|
| adminurl     | Middleware admin API URL   |
| apikey       | Middleware admin API key   |
| region       | AWS region                |
| sts-endpoint | STS proxy URL for users   |
| s3-endpoint  | S3 proxy URL for users    |
| role-arn     | IAM role ARN for users    |
+++++++++++++++++++++++++++++++++++++++++++
```

## Account and Bucket Management

### Account Credential Provisioning

When a CloudStack account creates its first bucket, the driver calls the middleware to generate a synthetic credential pair for that account. These credentials authenticate requests to the middleware's STS and S3 endpoints — they have no meaning to AWS.

| Storage Location | Key | Description |
|------------------|-----|-------------|
| Middleware Postgres | `accounts.access_key`, `accounts.secret_key` | Source of truth |
| CloudStack `account_details` | `s3-accesskey`, `s3-secretkey` | Copy for UI display |

### Bucket Creation

When a bucket is created through CloudStack, the driver calls the middleware admin API, which:

1. Calls `S3:HeadBucket` to check for name collisions (S3 bucket names are globally unique).
2. Calls `S3:CreateBucket` with the configured region.
3. Applies post-creation fixups (`DeletePublicAccessBlock`, `PutBucketOwnershipControls`).
4. Records the bucket-to-account mapping in Postgres.

The bucket URL displayed to users points to the upstream S3 endpoint: `https://s3.<region>.amazonaws.com/<bucket-name>`.

### Bucket Quota

AWS S3 does not natively support bucket quotas. Quota values are stored in CloudStack's database for tracking purposes only — enforcement is advisory.

### Bucket Encryption

AWS S3 enforces SSE-S3 (AES-256) encryption on all objects by default since January 2023. The encryption toggle in the CloudStack UI sets the explicit configuration but encryption is always active.

## Client Access

Users see connection instructions in the **Connection** tab when viewing a bucket in the CloudStack UI. Two modes are available:

### Mode 1: STS Direct (Recommended)

The client AWS SDK redirects only the STS service to the middleware. AssumeRole is handled transparently — the client never calls it explicitly. After obtaining temporary credentials, all S3 traffic goes directly to AWS S3.

```ini
# ~/.aws/credentials
[cloudstack-source]
aws_access_key_id = <CloudStack-issued access key>
aws_secret_access_key = <CloudStack-issued secret key>

# ~/.aws/config
[profile cloudstack-s3]
role_arn = <IAM role ARN>
source_profile = cloudstack-source
region = <region>
services = cloudstack-svc

[services cloudstack-svc]
sts =
  endpoint_url = http://<sts-proxy-host>:8085
```

Usage:

```bash
aws s3 ls s3://my-bucket --profile cloudstack-s3
aws s3 cp file.txt s3://my-bucket/ --profile cloudstack-s3
```

Best for: standard AWS SDK clients, high-throughput workloads, large file transfers.

### Mode 2: S3 Proxy

All S3 operations go through the reverse proxy. The client uses CloudStack-issued credentials with the proxy endpoint.

```bash
export AWS_ACCESS_KEY_ID=<CloudStack-issued access key>
export AWS_SECRET_ACCESS_KEY=<CloudStack-issued secret key>

aws s3 ls s3://my-bucket --endpoint-url http://<s3-proxy-host>:9000
```

Best for: legacy software that cannot configure STS, environments with restricted network access to S3.

### Public Object Access

When objects have public access (via bucket policy), anonymous downloads bypass the proxy and go directly to the upstream S3 URL:

- `https://s3.<region>.amazonaws.com/<bucket>/<key>`
- `https://<bucket>.s3.<region>.amazonaws.com/<key>`

## Bucket Naming Convention

Bucket names **must start with the CloudStack account name**. For example, if the account is `johndoe`, valid bucket names include `johndoe-docs`, `johndoe-photos-2024`, etc. The middleware enforces this on bucket creation.

This convention enables constant-size STS session policies — the policy uses a wildcard ARN (`arn:aws:s3:::johndoe*`) instead of listing each bucket individually, eliminating the AWS 2048-byte packed policy size limit regardless of how many buckets an account has.

## Tenant Isolation

Isolation is enforced through STS session policies. When the middleware issues temporary credentials (via STS or the S3 proxy), it builds a per-account policy that:

- **Allows** all S3 data operations (`s3:*`) on buckets matching the account name prefix (`arn:aws:s3:::accountname*`).
- **Denies** all bucket-level administrative operations (`CreateBucket`, `DeleteBucket`, `PutBucketVersioning`, `PutBucketPolicy`, etc.) and `ListAllMyBuckets`.

The Allow uses `s3:*` because the explicit Deny takes precedence for bucket-admin operations (AWS always honors explicit Deny over Allow). Bucket lifecycle is managed exclusively through CloudStack. Users cannot bypass CloudStack by calling S3 directly.

## Object Store Browser

The CloudStack Object Store Browser (MinIO-based, uses the MinIO JS client in the browser) is **not** supported for the AWS-S3 provider. The browser tab is hidden for AWS-S3 buckets. Users should use the AWS CLI or any S3-compatible client with the connection instructions shown in the Connection tab.

## Known Issues

1. **Bucket Quota** — AWS S3 does not support bucket-level quotas. The quota value in CloudStack is advisory only.
2. **Bucket Usage** — Usage data is obtained from CloudWatch `BucketSizeBytes` metrics, which are reported daily with approximately 48 hours of delay. Usage values are not real-time.
3. **Encryption Toggle** — AWS S3 enforces SSE-S3 on all buckets by default. The encryption toggle in the UI is misleading for this provider (encryption is always active).
4. **Object Store Browser** — Not available for this provider due to CORS and authentication model differences with the MinIO JS client.
5. **User Cleanup** — CloudStack does not currently have a `deleteUser` API for Object Stores, so when a CloudStack account is deleted, the corresponding middleware account is not automatically cleaned up.
