# PRD: AWS S3 Object Storage Provider for Apache CloudStack

## 1. Overview

A new CloudStack object storage provider plugin that uses AWS S3 as the backing store. Unlike the existing MinIO provider (which manages a self-hosted MinIO instance), this provider delegates bucket storage to a real AWS S3 region and provides tenant isolation through STS AssumeRole with scoped session policies.

The provider ships with a companion service — the **S3 middleware** — that provides S3-compatible access to tenants' buckets without ever exposing the upstream AWS IAM credentials. The S3 middleware is a single Go binary that exposes STS and S3 proxy endpoints on separate ports, manages all AWS interaction (bucket CRUD, STS AssumeRole, session policy construction), and maintains its own Postgres database of credentials and bucket-to-account mappings. The CloudStack plugin communicates with the middleware via an admin API, following the same pattern as the MinIO plugin communicates with MinIO.

## 2. Goals

- Allow CloudStack operators to offer S3-compatible object storage backed by AWS S3.
- Provide per-tenant bucket isolation using a single upstream IAM identity (no per-user IAM users).
- Support two client access modes: direct-to-S3 via STS credential vending, and full S3 reverse proxy for clients that cannot consume STS tokens.
- Integrate into CloudStack's existing object storage UI and API (create/list/delete buckets, set quota, encryption, versioning, etc.).
- Target AWS region: `sa-east-1` (parameterized, configurable per object store).

## 3. Non-Goals

- Replacing the existing MinIO or Ceph providers.
- Supporting non-AWS S3-compatible stores through this provider (use MinIO provider for that).
- Multi-region replication or cross-region bucket management.
- Per-object ACL management through the CloudStack UI (ACLs are set by clients via S3 API).

## 4. Architecture

### 4.1 Components

```
     CloudStack Management Server
     ┌──────────────────────────────────┐
     │  BucketApiServiceImpl             │
     │       │                           │
     │  AWSS3ObjectStoreDriverImpl       │
     │   (thin wrapper, calls           │
     │    middleware admin API)          │
     └──────────┬───────────────────────┘
                │ Admin API (HTTP)
                ▼
     ┌──────────────────────────────────────────────┐
     │             S3 Middleware                      │
     │         (single Go binary)                    │
     │                                               │
     │  ┌─────────────┐  ┌──────────┐  ┌─────────┐ │
     │  │ Admin API    │  │ STS      │  │ S3      │ │
     │  │ :8090        │  │ :8085    │  │ :9000   │ │
     │  │ (user/bucket │  │ (cred    │  │ (reverse│ │
     │  │  management) │  │  vending)│  │  proxy) │ │
     │  └──────────────┘  └──────────┘  └─────────┘ │
     │              │                                │
     │  ┌───────────┴───────────────┐                │
     │  │ Postgres (credentials,    │                │
     │  │  bucket-account mappings) │                │
     │  └───────────────────────────┘                │
     └──────────────────┬───────────────────────────┘
                        │ AWS SDK (service account)
                        ▼
                  ┌──────────┐
                  │  AWS S3  │ (sa-east-1)
                  │  AWS STS │
                  └──────────┘
                        ▲
           ┌────────────┼────────────────┐
           │                             │
  ┌────────┴─────────┐        ┌─────────┴────────┐
  │ Client (direct   │        │ Client (legacy    │
  │ to S3 with       │        │ software, uses    │
  │ STS temp creds)  │        │ S3 proxy as       │
  └──────────────────┘        │ endpoint)         │
                              └──────────────────┘
```

### 4.2 Single Upstream IAM Identity

One IAM user (the service account) and one IAM role for the entire provider:

- **Service account** (IAM user): Long-lived credentials held by the S3 middleware. Used directly for bucket admin operations (create, delete, configure) and to call `STS:AssumeRole`. The CloudStack management server does not hold AWS credentials — all AWS interaction goes through the middleware.
- **IAM role**: Used exclusively for STS AssumeRole. The role has broad S3 permissions (full `s3:*`); the actual per-tenant scoping is done entirely by the session policy attached to each AssumeRole call. The role's trust policy allows only the service account to assume it.
- **Session policies**: Built per-account at AssumeRole time. Each session policy scopes access to only the buckets owned by that CloudStack account and denies all bucket-admin operations (see section 5.6).

No per-user IAM users or long-lived AWS keys are created in the upstream account. The role exists solely as a vehicle for STS session policy scoping.

### 4.3 Credential Model

| Credential Type | Held By | Scope | Lifetime |
|---|---|---|---|
| AWS IAM access/secret key | S3 middleware | Service account | Permanent (rotated by operator) |
| CloudStack-issued access/secret key | Per CloudStack account | Middleware authentication | Permanent (stored in middleware Postgres) |
| STS temporary credentials | Per client session | Scoped to account's buckets | 1 hour (renewable) |

CloudStack-issued credentials are synthetic: they authenticate requests to the middleware's STS and S3 endpoints. They have no meaning to AWS. The middleware verifies them via SigV4 signature verification and then calls `STS:AssumeRole` with a session policy scoped to that account's buckets. The CloudStack plugin also stores copies of these credentials in the `account_details` table for display in the UI.

### 4.4 Account-Prefixed Bucket Naming

Bucket names **must start with the CloudStack account name**. For example, if the account is `johndoe`, valid bucket names include `johndoe-docs`, `johndoe-photos-2024`, etc. The middleware enforces this on bucket creation.

This convention enables the session policy to use a wildcard ARN (`arn:aws:s3:::johndoe*`) instead of listing each bucket individually, keeping the policy at a constant size regardless of how many buckets an account has. This eliminates the AWS STS 2048-byte packed policy size limit.

Bucket names visible to CloudStack users are identical to the actual S3 bucket names — no additional prefixing or mangling beyond the account name prefix chosen by the user.

Since S3 bucket names are globally unique, the provider must handle name collisions:
- On `createBucket`, call S3 `HeadBucket` first.
- If the name is taken (by another AWS account), return an error to the CloudStack user asking them to choose a different name.
- If the name exists in the same AWS account but is not tracked by CloudStack, return an error (orphan bucket).

### 4.5 Access Modes

#### Mode 1: STS Proxy (Direct to S3)

Based on `~/sts-poc`.

The client configures an AWS CLI profile that redirects only the STS service to the proxy. The SDK handles AssumeRole transparently — the client never calls it explicitly.

**AWS CLI configuration (`~/.aws/config` and `~/.aws/credentials`):**

```ini
# ~/.aws/credentials
[cloudstack-source]
aws_access_key_id = <CloudStack-issued access key>
aws_secret_access_key = <CloudStack-issued secret key>

# ~/.aws/config
[profile cloudstack-s3]
role_arn = <IAM role ARN from provider config>
source_profile = cloudstack-source
region = sa-east-1
services = cloudstack-svc

[services cloudstack-svc]
sts =
  endpoint_url = http://<sts-proxy-host>:8085
```

**Or equivalently via environment variables:**

```bash
export AWS_ACCESS_KEY_ID=<CloudStack-issued access key>
export AWS_SECRET_ACCESS_KEY=<CloudStack-issued secret key>
export AWS_ROLE_ARN=<IAM role ARN from provider config>
export AWS_REGION=sa-east-1
export AWS_ENDPOINT_URL_STS=http://<sts-proxy-host>:8085
```

**Flow:**
- The SDK calls `AssumeRole` against the STS proxy using the CloudStack-issued credentials.
- Proxy verifies the SigV4 signature, then calls real `STS:AssumeRole` with the service account's credentials and a session policy scoped to the authenticated account's buckets.
- Proxy returns temporary AWS credentials in standard STS XML format.
- The SDK caches the temporary credentials and uses them directly against real S3 (proxy not in data path).

Best for: clients using standard AWS SDKs, high-throughput workloads, large file transfers.

#### Mode 2: S3 Proxy (Reverse Proxy)

Based on `~/s3-proxy-poc`.

- Client configures AWS CLI/SDK with CloudStack-issued credentials and an `endpoint_url` pointing to the S3 proxy.
- All S3 operations go through the proxy.
- Proxy verifies SigV4 signature, obtains scoped STS credentials (cached), re-signs the request, and forwards to real S3.
- Response is returned to the client.

Best for: legacy software that cannot configure STS, environments where direct S3 access is not possible (network restrictions), additional request-level policy enforcement.

Both modes can run simultaneously. Operators choose which to deploy based on their environment.

## 5. CloudStack Plugin Design

### 5.0 Modularity Constraint

The plugin must not modify any existing CloudStack core files. All code lives in the new plugin module (`plugins/storage/object/s3/`) and is enabled via Maven profile or compiler flag, the same way the MinIO, Ceph, and Simulator object storage plugins are included. This ensures:

- The plugin can be cleanly merged into the upstream Apache CloudStack repository as a self-contained module.
- Existing CloudStack builds are unaffected unless the operator explicitly enables the plugin.
- The plugin relies only on public interfaces (`ObjectStoreProvider`, `ObjectStoreDriver`, `ObjectStoreLifeCycle`, `BaseObjectStoreDriverImpl`) and the existing `account_details` / `object_store_details` database tables — no schema migrations, no core class modifications.

If a feature cannot be implemented without touching core, it must be flagged and discussed before proceeding. The goal is a zero-diff on all files outside `plugins/storage/object/s3/`.

### 5.1 Plugin Structure

```
plugins/storage/object/s3/
  pom.xml
  src/main/java/org/apache/cloudstack/storage/datastore/
    provider/AWSS3ObjectStoreProviderImpl.java
    driver/AWSS3ObjectStoreDriverImpl.java
    lifecycle/AWSS3ObjectStoreLifeCycleImpl.java
  src/main/resources/META-INF/cloudstack/
    storage-object-s3/
      spring-storage-object-s3-context.xml
      module.properties
```

### 5.2 Provider Registration

- Provider name: `"AWS-S3"`
- Registered via Spring component scan.
- `configure()` creates driver and lifecycle instances, registers driver with `ObjectStoreProviderManager`.

### 5.3 Object Store Configuration (addObjectStoragePool)

Parameters stored in `object_store_details`:

| Key | Description | Example |
|---|---|---|
| `adminurl` | S3 middleware admin API URL | `http://s3mw:8090` |
| `apikey` | Admin API key for middleware authentication | `sk-...` |
| `region` | AWS region (informational, for display) | `sa-east-1` |
| `sts-endpoint` | STS endpoint URL (for user display/client config) | `https://sts.example.com:8085` |
| `s3-endpoint` | S3 proxy endpoint URL (for user display/client config) | `https://s3proxy.example.com:9000` |

The `url` field on the object store itself will hold the S3 middleware admin API URL. AWS credentials are no longer stored in CloudStack — they are configured in the middleware only.

### 5.4 Driver Implementation (AWSS3ObjectStoreDriverImpl)

Extends `BaseObjectStoreDriverImpl`. The driver is a thin wrapper that delegates all operations to the S3 middleware via its admin API, following the same pattern as the MinIO driver delegates to MinIO. No AWS SDK calls are made from the Java side.

#### createUser(accountId, storeId)
- Call middleware admin API: `POST /admin/users` with account UUID and account name.
- The account name is used as the bucket name prefix for session policy scoping (see section 5.6).
- Middleware generates the synthetic access/secret key pair and stores them in its Postgres database.
- Driver receives the credentials in the response and stores them in `account_details` table (keys `s3-accesskey`, `s3-secretkey`) for display in the CloudStack UI.

#### createBucket(bucket, objectLock)
- Call middleware admin API: `POST /admin/buckets` with bucket name, account identifier, objectLock flag.
- Middleware handles all AWS interaction internally:
  1. `HeadBucket` collision check.
  2. `CreateBucket` with `LocationConstraint`.
  3. Post-creation fixups (`DeletePublicAccessBlock`, `PutBucketOwnershipControls`).
  4. Records the bucket-to-account mapping in its Postgres database.
- Driver stamps the account's credentials and bucket URL onto the BucketVO.

#### deleteBucket(bucket, storeId)
- Call middleware admin API: `DELETE /admin/buckets/{name}`.
- Middleware calls `S3:DeleteBucket` and removes the mapping from its database.
- Middleware surfaces `BucketNotEmpty` errors to the driver.

#### setBucketEncryption / deleteBucketEncryption
- Call middleware admin API to set/delete SSE-S3 encryption on the bucket.

#### setBucketVersioning / deleteBucketVersioning
- Call middleware admin API to enable/suspend versioning.

#### setBucketPolicy / deleteBucketPolicy
- Call middleware admin API with `public` or `private` preset.

#### setBucketQuota(bucket, storeId, size)
- S3 does not natively support bucket quotas.
- Store the quota value in CloudStack's database for tracking.
- Enforcement is advisory only.

#### getAllBucketsUsage(storeId)
- Call middleware admin API which queries CloudWatch `BucketSizeBytes` metric.
- Note: this metric is reported daily with ~48 hour delay, so usage data is advisory, not real-time.

### 5.5 Credential Storage

Credentials exist in two places:

**S3 middleware Postgres database (source of truth):**
- Synthetic access/secret key pairs, per account.
- Bucket-to-account mappings.
- The middleware uses these to verify SigV4 signatures and build session policies.

**CloudStack `account_details` table (copies for UI display):**

| Key | Value |
|---|---|
| `s3-accesskey` | Access key (copy from middleware) |
| `s3-secretkey` | Secret key (copy from middleware) |

This follows the same pattern as the MinIO provider's `minio-accesskey` / `minio-secretkey`. The middleware is the authoritative store; CloudStack holds copies so the UI can display connection instructions to users.

### 5.6 Session Policy Construction

Bucket lifecycle and settings are managed exclusively through CloudStack. The session policy must deny all bucket-level administrative operations so users cannot bypass CloudStack by calling S3 directly.

When the S3 middleware needs to build a session policy for a given account, it uses the account name as a wildcard prefix:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": [
        "arn:aws:s3:::<accountname>*",
        "arn:aws:s3:::<accountname>*/*"
      ]
    },
    {
      "Effect": "Deny",
      "Action": [
        "s3:CreateBucket",
        "s3:DeleteBucket",
        "s3:PutBucketVersioning",
        "s3:PutBucketPolicy",
        "s3:DeleteBucketPolicy",
        "s3:PutBucketAcl",
        "s3:PutBucketOwnershipControls",
        "s3:PutBucketTagging",
        "s3:DeleteBucketTagging",
        "s3:ListAllMyBuckets",
        "s3:PutEncryptionConfiguration",
        "s3:DeleteEncryptionConfiguration",
        "s3:PutPublicAccessBlock",
        "s3:DeletePublicAccessBlock",
        "s3:PutObjectLockConfiguration"
      ],
      "Resource": "arn:aws:s3:::*"
    }
  ]
}
```

**Design rationale:**
- The Allow statement uses `s3:*` with a wildcard resource scoped to the account name prefix (`arn:aws:s3:::accountname*`). This keeps the policy at a constant size regardless of bucket count, eliminating the AWS STS 2048-byte packed policy size limit. The account-prefixed bucket naming convention (see section 4.4) ensures isolation — each account can only access buckets whose names start with their own account name.
- The Deny statement explicitly blocks all bucket-level administrative operations and `ListAllMyBuckets`. AWS always honors explicit Deny over Allow, so even though the Allow grants `s3:*`, bucket-admin operations are still blocked.
- `ListAllMyBuckets` is denied because it cannot be scoped to specific buckets in AWS and would expose all bucket names in the account. Bucket listing is a CloudStack-level feature served from the CloudStack database, not from S3.
- Bucket creation/deletion, versioning, encryption, policies, ACLs, public access, and object lock configuration are all reserved for the middleware's admin API, which uses the service account credentials directly (not STS).

The middleware passes this policy as the `Policy` parameter to `STS:AssumeRole`.

AWS enforces the intersection of this session policy with the role's permission policy, ensuring a user can never access buckets outside their own set or perform bucket admin operations.

## 6. S3 Middleware

Single Go binary that consolidates the STS proxy, S3 proxy, and admin API into one self-contained service. Based on `~/sts-poc` and `~/s3-proxy-poc`.

### 6.1 Configuration

| Variable | Description | Default |
|---|---|---|
| `DATABASE_URL` | Postgres connection string | (required) |
| `AWS_ACCESS_KEY_ID` | Service account access key | (required) |
| `AWS_SECRET_ACCESS_KEY` | Service account secret key | (required) |
| `AWS_ROLE_ARN` | IAM role ARN for STS AssumeRole | (required) |
| `AWS_REGION` | Target S3 region | `sa-east-1` |
| `ADMIN_API_KEY` | API key for admin API authentication | (required) |
| `ADMIN_ALLOWED_IPS` | Comma-separated IP allowlist for admin API | `127.0.0.1` |
| `ADMIN_LISTEN` | Admin API listen address | `:8090` |
| `STS_LISTEN` | STS endpoint listen address | `:8085` |
| `S3_LISTEN` | S3 proxy endpoint listen address | `:9000` |

### 6.2 Postgres Schema

The middleware owns its database with (at minimum) these tables:

- **`accounts`**: account identifier (CloudStack UUID), account name (bucket prefix), access key, secret key.
- **`buckets`**: bucket name, account FK, created timestamp.

The middleware is the source of truth for credentials and bucket mappings. CloudStack holds copies in its own database for UI display only.

### 6.3 Admin API (port 8090)

Internal API called by the CloudStack plugin driver. Not exposed to end users. Secured by two mechanisms:

1. **API key authentication**: Every request must include an `Authorization: Bearer <ADMIN_API_KEY>` header. The key is configured via the `ADMIN_API_KEY` environment variable on the middleware and stored in `object_store_details` (key `apikey`) on the CloudStack side.
2. **IP allowlist**: The middleware only accepts admin API connections from IPs listed in `ADMIN_ALLOWED_IPS` (comma-separated, default `127.0.0.1`). Requests from other IPs are rejected with 403.

| Method | Path | Description |
|---|---|---|
| `POST` | `/admin/users` | Create account with generated credentials (requires `id` and `name`) |
| `GET` | `/admin/users/{id}` | Get account credentials |
| `DELETE` | `/admin/users/{id}` | Delete account |
| `POST` | `/admin/buckets` | Create bucket (S3 + Postgres) |
| `DELETE` | `/admin/buckets/{name}` | Delete bucket (S3 + Postgres) |
| `PUT` | `/admin/buckets/{name}/encryption` | Set/delete SSE-S3 encryption |
| `PUT` | `/admin/buckets/{name}/versioning` | Enable/suspend versioning |
| `PUT` | `/admin/buckets/{name}/policy` | Set/delete bucket policy |
| `GET` | `/admin/buckets/usage` | Get bucket sizes via CloudWatch |
| `GET` | `/admin/health` | Health check (DB + AWS connectivity) |

Bucket creation via the admin API handles all AWS interaction internally:
1. `HeadBucket` collision check.
2. `CreateBucket` with `LocationConstraint`.
3. Post-creation fixups (`DeletePublicAccessBlock`, `PutBucketOwnershipControls`).
4. Insert bucket-account mapping into Postgres.

### 6.4 STS Endpoint (port 8085)

Handles `Action=AssumeRole` requests signed with CloudStack-issued credentials.

**Flow:**
1. Verify SigV4 against credentials in Postgres.
2. Look up account's buckets from Postgres.
3. Build session policy scoped to those buckets.
4. Call `STS:AssumeRole` with session policy.
5. Return temporary credentials in STS XML format.

Clients configure their AWS SDK to redirect STS to this endpoint (see section 4.5 Mode 1).

### 6.5 S3 Proxy Endpoint (port 9000)

Proxies all S3 API operations to `https://s3.<region>.amazonaws.com`.

**Flow:**
1. Verify SigV4 against credentials in Postgres.
2. Obtain scoped STS credentials (cached per account, refreshed 5 min before expiry).
3. Decode aws-chunked transfer encoding if present.
4. Re-sign request with scoped credentials.
5. Forward to S3, return response.

Bucket admin operations (CreateBucket, DeleteBucket) are rejected — these go through the admin API only.

### 6.6 Horizontal Scalability

Multiple middleware instances can run behind a load balancer. All instances share the same Postgres database. STS credential caches are per-instance (in-memory) and populated on demand. No inter-instance coordination is required.

### 6.7 Local Development

For local development, run Postgres in a container alongside the middleware:

```bash
podman run -d --name s3mw-postgres -p 5432:5432 \
  -e POSTGRES_DB=s3middleware -e POSTGRES_USER=s3mw -e POSTGRES_PASSWORD=s3mw \
  postgres:17
```

## 7. Public Object Access

When objects are configured with public access (via ACL or bucket policy), anonymous downloads bypass the proxy entirely and go to the upstream S3 URL:

- Path-style: `https://s3.sa-east-1.amazonaws.com/<bucket>/<key>`
- Virtual-hosted: `https://<bucket>.s3.sa-east-1.amazonaws.com/<key>`

The CloudStack UI should display this URL for public objects. The proxy cannot serve anonymous requests (it requires SigV4).

## 8. Bucket Name Collision Handling

Since S3 bucket names are globally unique:

1. `createBucket("my-bucket")` → middleware calls `S3:HeadBucket("my-bucket")`.
2. If 404 (not found) → proceed with `S3:CreateBucket`.
3. If 200 or 403 (exists, owned by someone else) → return error: `"Bucket name 'my-bucket' is already taken in AWS S3. Please choose a different name."`.
4. If the bucket exists and is owned by the same AWS account but not tracked in the middleware's database → return error: `"Bucket 'my-bucket' exists in the upstream store but is not managed by CloudStack."`.

## 9. Implementation Phases

### Phase 1: CloudStack Plugin (MVP) — DONE
- Implemented `AWSS3ObjectStoreProviderImpl`, `AWSS3ObjectStoreDriverImpl`, `AWSS3ObjectStoreLifeCycleImpl`.
- Bucket CRUD via CloudStack API and UI.
- Credential generation and storage in `account_details`.
- Bucket creation with post-creation fixups.
- Region parameterization.
- Note: The current driver calls AWS directly. Phase 2 will refactor it to call the middleware admin API instead.

### Phase 2: S3 Middleware
- Build the unified S3 middleware (single Go binary) with Postgres backing store.
- Implement admin API for user/bucket management.
- Port `~/sts-poc` STS endpoint to use Postgres for credential/bucket lookups.
- Port `~/s3-proxy-poc` S3 proxy endpoint to use Postgres for credential/bucket lookups.
- Preserve all S3 compatibility fixups (aws-chunked, SSE-C, post-creation fixups, header casing).
- Reject bucket admin operations (CreateBucket/DeleteBucket) at S3 proxy level.
- Refactor CloudStack driver to call middleware admin API instead of AWS directly.

### Phase 3: UI Integration
- Display middleware connection instructions per account (STS endpoint, S3 endpoint, credentials).
- Show public object URLs pointing to upstream S3.
- Bucket management through existing CloudStack Object Storage UI.

## 10. AWS Prerequisites

The operator must set up the following:

1. **A dedicated AWS account** for the object storage provider. This isolates CloudStack-managed buckets from other infrastructure.
2. **An IAM user** (service account) in that account with programmatic access and S3/STS permissions.
3. **S3 access** in the target region.

The service account credentials are configured in the S3 middleware. The CloudStack management server does not hold AWS credentials.

### Dev environment (current setup)

- **Account:** `211125662649` (dedicated account for this project)
- **IAM user:** `s3-cloudstack-provider-dev`
- **IAM role ARN:** `arn:aws:iam::211125662649:role/cloudstack-s3-dev`
- **Credentials:** `~/.env.s3-cloudstack-provider-dev`
- **Region:** `sa-east-1`

## 11. Configuration Summary

### CloudStack object store configuration (`object_store_details`)

| Parameter | Key | Description |
|---|---|---|
| Admin URL | `adminurl` | S3 middleware admin API URL |
| Region | `region` | AWS region (informational, for display) |
| STS Endpoint | `sts-endpoint` | STS endpoint URL (for user display/client config) |
| S3 Endpoint | `s3-endpoint` | S3 proxy endpoint URL (for user display/client config) |

### S3 middleware configuration (environment variables)

| Parameter | Description |
|---|---|
| `DATABASE_URL` | Postgres connection string |
| `AWS_ACCESS_KEY_ID` | Service account access key |
| `AWS_SECRET_ACCESS_KEY` | Service account secret key |
| `AWS_ROLE_ARN` | IAM role for STS AssumeRole |
| `AWS_REGION` | Target S3 region |

## 12. S3 Middleware Compatibility Baseline

The S3 proxy endpoint (based on `~/s3-proxy-poc`) has been extensively tuned against two S3 compatibility test suites. Any changes to the S3 proxy logic in the middleware must preserve these results — regressions are not acceptable.

### Current test results (from s3-proxy-poc)

**Ceph s3-tests** (453 object-operation tests, excluding `@pytest.mark.fails_on_aws` and bucket-admin tests):
- 281 passed (62%)
- 0 proxy bugs remaining
- Only 4 tests behind direct-to-AWS (no proxy)
- Failures are AWS behavioral differences (59), not-applicable Ceph-specific tests (37), and architectural limitations (shared assumed role model)

**MinIO Mint** (3 suites):
- aws-sdk-go-v2: 21/21 passed
- versioning: 12/12 passed
- minio-go: 54/73 passed (failures are AWS behavioral differences or architectural limitations)

### Testing policy

When modifying S3 middleware proxy code:
1. Run the full Ceph s3-tests suite and verify pass count does not decrease from the baseline in `~/s3-proxy-poc/README.md`.
2. Run the MinIO Mint suites and verify no regressions.
3. Follow the exact test setup and configuration documented in `~/s3-proxy-poc/README.md` (including the `s3tests-harness-fix.patch`).
4. Preserve all S3 proxy fixups (aws-chunked decoding, SSE-C pass-through, CreateBucket LocationConstraint injection, post-creation ACL/ownership fixups, bypass-governance retry, header casing). These were hand-tuned to pass the test suites — do not simplify, refactor, or remove them without re-running the full suites.

## 13. Testing Strategy

### CloudStack Plugin
- Unit tests for driver methods (mock middleware admin API calls).
- Integration tests with middleware running against real S3.
- Manual testing through CloudStack UI: create/delete buckets, verify in S3 console.

### S3 Middleware
- Unit tests for admin API, STS endpoint, S3 proxy logic.
- Integration tests against real S3 using the dev account credentials.
- S3 compatibility suites (Ceph s3-tests, MinIO Mint) — see section 12.

## 14. Backlog

- **Hide encryption toggle in UI for AWS-S3 provider.** AWS S3 enforces SSE-S3 (AES-256) on all buckets and objects by default since January 2023 — there is no way to disable it. The CloudStack encryption flag is misleading for this provider. Research whether the UI can conditionally hide or disable the encryption toggle based on the provider type, or whether the plugin should simply report encryption as always enabled.
