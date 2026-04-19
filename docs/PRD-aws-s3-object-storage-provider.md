# PRD: AWS S3 Object Storage Provider for Apache CloudStack

## 1. Overview

A new CloudStack object storage provider plugin that uses AWS S3 as the backing store. Unlike the existing MinIO provider (which manages a self-hosted MinIO instance), this provider delegates bucket storage to a real AWS S3 region and provides tenant isolation through STS AssumeRole with scoped session policies.

The provider ships with two companion proxy services that give CloudStack users S3-compatible access to their buckets without ever exposing the upstream AWS IAM credentials.

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
                │  AWSS3ObjectStoreDriverImpl        │
                │   (creates/deletes buckets in S3) │
                │   (manages credentials in DB)     │
                └──────────┬───────────────────────┘
                           │ AWS SDK (service account)
                           ▼
                     ┌──────────┐
                     │  AWS S3  │ (sa-east-1)
                     │  AWS STS │
                     │  AWS IAM │
                     └──────────┘
                           ▲
              ┌────────────┼────────────────┐
              │                             │
     ┌────────┴─────────┐        ┌─────────┴────────┐
     │   STS Proxy       │        │   S3 Proxy        │
     │ (credential       │        │ (reverse proxy    │
     │  vending only)    │        │  in data path)    │
     └────────┬─────────┘        └─────────┬────────┘
              │                             │
              │  STS tokens                 │  endpoint-url
              ▼                             ▼
     ┌────────────────┐          ┌────────────────┐
     │ Client (direct │          │ Client (legacy  │
     │ to S3 with     │          │ software, uses  │
     │ temp creds)    │          │ proxy as S3     │
     └────────────────┘          │ endpoint)       │
                                 └────────────────┘
```

### 4.2 Single Upstream IAM Identity

One IAM role + one IAM user (the service account) for the entire provider:

- **Service account**: Long-lived IAM credentials held by the management server and both proxies. Used to call `STS:AssumeRole` and to perform bucket admin operations (create, delete, configure).
- **IAM role**: Defines the maximum permission boundary. Trust policy allows the service account to assume it. Role policy grants `s3:*` on a configurable bucket name pattern and `s3:ListAllMyBuckets`.
- **Session policies**: Built per-user at AssumeRole time. Each session policy scopes access to only the buckets owned by that CloudStack account.

No per-user IAM users or long-lived AWS keys are created in the upstream account.

### 4.3 Credential Model

| Credential Type | Held By | Scope | Lifetime |
|---|---|---|---|
| AWS IAM access/secret key | Management server, proxies | Service account | Permanent (rotated by operator) |
| CloudStack-issued access/secret key | Per CloudStack account | Proxy authentication | Permanent (stored in DB) |
| STS temporary credentials | Per client session | Scoped to account's buckets | 1 hour (renewable) |

CloudStack-issued credentials are synthetic: they authenticate requests to the STS proxy or S3 proxy. They have no meaning to AWS. The proxies verify them via SigV4 signature verification and then call `STS:AssumeRole` with a session policy scoped to that account's buckets.

### 4.4 Transparent Bucket Naming

Bucket names visible to CloudStack users are identical to the actual S3 bucket names. No prefixing, mangling, or mapping. This is a hard requirement for STS credential forwarding — scoped IAM policies reference bucket ARNs by name.

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
| `accesskey` | AWS IAM access key (service account) | `AKIA...` |
| `secretkey` | AWS IAM secret key (service account) | `wJal...` |
| `region` | AWS region | `sa-east-1` |
| `role-arn` | IAM role ARN for AssumeRole | `arn:aws:iam::123:role/cs-s3-role` |
| `sts-proxy-url` | URL of the STS proxy (informational, for user display) | `https://sts.example.com` |
| `s3-proxy-url` | URL of the S3 proxy (informational, for user display) | `https://s3proxy.example.com` |

The `url` field on the object store itself will hold the S3 regional endpoint (e.g., `https://s3.sa-east-1.amazonaws.com`).

### 5.4 Driver Implementation (AWSS3ObjectStoreDriverImpl)

Extends `BaseObjectStoreDriverImpl`. Key method implementations:

#### createUser(accountId, storeId)
- Generate a random CloudStack-issued access key and secret key (synthetic, not AWS keys).
- Store them in `account_details` table (same pattern as MinIO: keys `s3-accesskey`, `s3-secretkey`).
- These credentials are what users will use to authenticate against the proxies.

#### createBucket(bucket, objectLock)
- Using the service account's AWS credentials:
  1. Call `S3:HeadBucket` to check if name is taken. If taken, throw error.
  2. Call `S3:CreateBucket` with `LocationConstraint` set to the configured region.
  3. Apply post-creation fixups (from s3-proxy-poc):
     - `DeletePublicAccessBlock` — remove default Block Public Access.
     - `PutBucketOwnershipControls` with `BucketOwnerPreferred` — enable ACLs.
  4. Set the bucket URL on BucketVO to the standard S3 URL format.
  5. Stamp account-level CloudStack-issued credentials onto the BucketVO.

#### deleteBucket(bucket, storeId)
- Call `S3:DeleteBucket` using service account credentials.
- Handle `BucketNotEmpty` error and surface it to the user.

#### setBucketEncryption / deleteBucketEncryption
- Call `S3:PutBucketEncryption` / `S3:DeleteBucketEncryption` with SSE-S3 configuration.

#### setBucketVersioning / deleteBucketVersioning
- Call `S3:PutBucketVersioning` with `Enabled` or `Suspended`.

#### setBucketPolicy / deleteBucketPolicy
- Call `S3:PutBucketPolicy` / `S3:DeleteBucketPolicy`.
- Support `public` and `private` presets (same as MinIO provider).

#### setBucketQuota(bucket, storeId, size)
- S3 does not natively support bucket quotas.
- Store the quota value in CloudStack's database for tracking.
- Enforcement is advisory: the provider tracks usage via `getAllBucketsUsage` and can alert, but cannot block writes at the S3 level.

#### getAllBucketsUsage(storeId)
- Use CloudWatch `BucketSizeBytes` metric or `S3:ListBuckets` + `HeadBucket` for usage.
- Return map of bucket name to size in bytes.

### 5.5 Credential Storage

CloudStack-issued credentials (synthetic access/secret keys for proxy authentication) are stored in the `account_details` table:

| Key | Value |
|---|---|
| `s3-accesskey` | CloudStack-generated access key (e.g., `AKIACS<uuid-based>`) |
| `s3-secretkey` | CloudStack-generated secret key (random base64) |

This follows the same pattern as the MinIO provider's `minio-accesskey` / `minio-secretkey`.

The proxies read these credentials from the CloudStack database (or a synced credential store) to verify incoming SigV4 signatures.

### 5.6 Session Policy Construction

Bucket lifecycle and settings are managed exclusively through CloudStack. The session policy must deny all bucket-level administrative operations so users cannot bypass CloudStack by calling S3 directly.

When a proxy needs to build a session policy for a given CloudStack account, it:

1. Queries the `bucket` table for all buckets owned by that account in state `Created`.
2. Builds a policy document with an explicit deny for bucket admin operations:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowObjectOperations",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:ListBucket",
        "s3:GetBucketLocation",
        "s3:ListBucketMultipartUploads",
        "s3:ListMultipartUploadParts",
        "s3:AbortMultipartUpload",
        "s3:PutObjectAcl",
        "s3:GetObjectAcl",
        "s3:GetObjectVersion",
        "s3:DeleteObjectVersion",
        "s3:PutObjectTagging",
        "s3:GetObjectTagging",
        "s3:DeleteObjectTagging"
      ],
      "Resource": [
        "arn:aws:s3:::<bucket1>",
        "arn:aws:s3:::<bucket1>/*",
        "arn:aws:s3:::<bucket2>",
        "arn:aws:s3:::<bucket2>/*"
      ]
    },
    {
      "Sid": "AllowListBuckets",
      "Effect": "Allow",
      "Action": "s3:ListAllMyBuckets",
      "Resource": "arn:aws:s3:::*"
    },
    {
      "Sid": "DenyBucketAdmin",
      "Effect": "Deny",
      "Action": [
        "s3:CreateBucket",
        "s3:DeleteBucket",
        "s3:PutBucketVersioning",
        "s3:PutEncryptionConfiguration",
        "s3:DeleteEncryptionConfiguration",
        "s3:PutBucketPolicy",
        "s3:DeleteBucketPolicy",
        "s3:PutBucketAcl",
        "s3:PutBucketOwnershipControls",
        "s3:PutPublicAccessBlock",
        "s3:DeletePublicAccessBlock",
        "s3:PutObjectLockConfiguration",
        "s3:PutBucketTagging",
        "s3:DeleteBucketTagging"
      ],
      "Resource": "arn:aws:s3:::*"
    }
  ]
}
```

**Design rationale:**
- The Allow statement uses an explicit action list (not `s3:*`) to grant only object-level data operations. This is the whitelist of what users can do directly.
- The Deny statement explicitly blocks all bucket-level administrative operations. Even if the Allow list is accidentally broadened, the Deny takes precedence (AWS always honors explicit Deny over Allow).
- Bucket creation/deletion, versioning, encryption, policies, ACLs, public access, and object lock configuration are all reserved for the CloudStack management server, which uses the service account credentials directly (not STS).

3. Passes this as the `Policy` parameter to `STS:AssumeRole`.

AWS enforces the intersection of this session policy with the role's permission policy, ensuring a user can never access buckets outside their own set or perform bucket admin operations.

## 6. Proxy Services

### 6.1 STS Proxy

Standalone Go service. Based on `~/sts-poc`.

**Configuration:**
- `CLOUDSTACK_DB_DSN` or API endpoint for reading credentials and bucket mappings.
- `AWS_ROLE_ARN` — the IAM role to assume.
- `AWS_REGION` — target region.
- AWS service account credentials (from environment or instance profile).
- Listen address (default `:8085`).

**Endpoints:**
- `POST /` — handles `Action=AssumeRole` requests signed with CloudStack-issued credentials.

**Flow:**
1. Verify SigV4 against CloudStack-issued credentials.
2. Look up account's buckets from CloudStack DB.
3. Build session policy scoped to those buckets.
4. Call `STS:AssumeRole` with session policy.
5. Return temporary credentials in STS XML format.

### 6.2 S3 Proxy

Standalone Go service. Based on `~/s3-proxy-poc`.

**Configuration:**
- Same credential and bucket mapping source as STS proxy.
- `AWS_ROLE_ARN`, `AWS_REGION`.
- AWS service account credentials.
- Listen address (default `:9000`).

**Endpoints:**
- `*` — all S3 API operations, proxied to `https://s3.<region>.amazonaws.com`.

**Flow:**
1. Verify SigV4 against CloudStack-issued credentials.
2. Obtain scoped STS credentials (cached per account, refreshed 5 min before expiry).
3. Decode aws-chunked transfer encoding if present.
4. Re-sign request with scoped credentials.
5. Forward to S3, return response.

**Bucket admin operations (CreateBucket, DeleteBucket) are rejected by the proxy** — these are handled by the CloudStack management server only.

## 7. Public Object Access

When objects are configured with public access (via ACL or bucket policy), anonymous downloads bypass the proxy entirely and go to the upstream S3 URL:

- Path-style: `https://s3.sa-east-1.amazonaws.com/<bucket>/<key>`
- Virtual-hosted: `https://<bucket>.s3.sa-east-1.amazonaws.com/<key>`

The CloudStack UI should display this URL for public objects. The proxy cannot serve anonymous requests (it requires SigV4).

## 8. Bucket Name Collision Handling

Since S3 bucket names are globally unique:

1. `createBucket("my-bucket")` → provider calls `S3:HeadBucket("my-bucket")`.
2. If 404 (not found) → proceed with `S3:CreateBucket`.
3. If 200 or 403 (exists, owned by someone else) → return error: `"Bucket name 'my-bucket' is already taken in AWS S3. Please choose a different name."`.
4. If the bucket exists and is owned by the same AWS account but not tracked in CloudStack's `bucket` table → return error: `"Bucket 'my-bucket' exists in the upstream store but is not managed by CloudStack."`.

## 9. Implementation Phases

### Phase 1: CloudStack Plugin (MVP)
- Implement `AWSS3ObjectStoreProviderImpl`, `AWSS3ObjectStoreDriverImpl`, `AWSS3ObjectStoreLifeCycleImpl`.
- Bucket CRUD via CloudStack API and UI.
- Credential generation and storage in `account_details`.
- Bucket creation with post-creation fixups.
- Region parameterization.

### Phase 2: STS Proxy
- Port `~/sts-poc` to read credentials and bucket mappings from CloudStack DB.
- Parameterize role ARN, region, listen address.
- Deploy alongside management server.

### Phase 3: S3 Proxy
- Port `~/s3-proxy-poc` to read credentials and bucket mappings from CloudStack DB.
- Preserve all S3 compatibility fixups (aws-chunked, SSE-C, CreateBucket fixups, header casing).
- Reject bucket admin operations (CreateBucket/DeleteBucket) at proxy level.
- Deploy alongside management server.

### Phase 4: UI Integration
- Display proxy connection instructions per account (STS proxy URL, S3 proxy URL, credentials).
- Show public object URLs pointing to upstream S3.
- Bucket management through existing CloudStack Object Storage UI.

## 10. AWS Prerequisites

The operator must set up the following in their AWS account before configuring the provider:

1. **IAM User** (service account) with programmatic access.
2. **IAM Role** with:
   - Trust policy allowing the service account to assume it.
   - Permission policy granting `s3:*` on the desired bucket namespace and `s3:ListAllMyBuckets`.
3. **S3 access** in the target region.

Example role permission policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::*", "arn:aws:s3:::*/*"]
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListAllMyBuckets",
      "Resource": "arn:aws:s3:::*"
    }
  ]
}
```

(Operators may narrow the resource scope to a prefix pattern for additional safety.)

## 11. Configuration Summary

| Parameter | Where | Description |
|---|---|---|
| AWS Access Key | `object_store_details.accesskey` | Service account access key |
| AWS Secret Key | `object_store_details.secretkey` | Service account secret key |
| Region | `object_store_details.region` | AWS region (e.g., `sa-east-1`) |
| Role ARN | `object_store_details.role-arn` | IAM role for AssumeRole |
| STS Proxy URL | `object_store_details.sts-proxy-url` | For display to users |
| S3 Proxy URL | `object_store_details.s3-proxy-url` | For display to users |
| Object Store URL | `object_store.url` | S3 regional endpoint |

## 12. Testing Strategy

- Unit tests for driver methods (mock AWS SDK calls).
- Integration tests against LocalStack or real S3.
- S3 compatibility tests using Ceph s3-tests suite (same as used for s3-proxy-poc, target 62%+ pass rate).
- Manual testing through CloudStack UI: create/delete buckets, verify in S3 console.
