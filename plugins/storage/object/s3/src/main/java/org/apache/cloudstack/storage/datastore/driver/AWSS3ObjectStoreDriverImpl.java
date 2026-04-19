/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.datastore.driver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.BaseObjectStoreDriverImpl;
import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.BucketObject;
import org.apache.commons.codec.binary.Base64;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.BucketPolicy;
import com.amazonaws.services.s3.model.BucketVersioningConfiguration;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.DeletePublicAccessBlockRequest;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import com.amazonaws.services.s3.model.SSEAlgorithm;
import com.amazonaws.services.s3.model.ServerSideEncryptionByDefault;
import com.amazonaws.services.s3.model.ServerSideEncryptionConfiguration;
import com.amazonaws.services.s3.model.ServerSideEncryptionRule;
import com.amazonaws.services.s3.model.SetBucketEncryptionRequest;
import com.amazonaws.services.s3.model.SetBucketVersioningConfigurationRequest;
import com.amazonaws.services.s3.model.SetBucketOwnershipControlsRequest;
import com.amazonaws.services.s3.model.ownership.ObjectOwnership;
import com.amazonaws.services.s3.model.ownership.OwnershipControls;
import com.amazonaws.services.s3.model.ownership.OwnershipControlsRule;
import com.cloud.agent.api.to.BucketTO;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;

import java.util.Collections;

public class AWSS3ObjectStoreDriverImpl extends BaseObjectStoreDriverImpl {

    private static final String ACCESS_KEY = "accesskey";
    private static final String SECRET_KEY = "secretkey";
    private static final String REGION = "region";

    protected static final String S3_ACCESS_KEY = "s3-accesskey";
    protected static final String S3_SECRET_KEY = "s3-secretkey";

    @Inject
    AccountDao _accountDao;

    @Inject
    AccountDetailsDao _accountDetailsDao;

    @Inject
    ObjectStoreDao _storeDao;

    @Inject
    BucketDao _bucketDao;

    @Inject
    ObjectStoreDetailsDao _storeDetailsDao;

    @Override
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    @Override
    public Bucket createBucket(Bucket bucket, boolean objectLock) {
        String bucketName = bucket.getName();
        long storeId = bucket.getObjectStoreId();
        long accountId = bucket.getAccountId();
        AmazonS3 s3Client = getS3Client(storeId);

        if ((_accountDetailsDao.findDetail(accountId, S3_ACCESS_KEY) == null)
                || (_accountDetailsDao.findDetail(accountId, S3_SECRET_KEY) == null)) {
            throw new CloudRuntimeException("Bucket access credentials unavailable for account: " + accountId);
        }

        // Check if bucket name is already taken
        try {
            s3Client.headBucket(new HeadBucketRequest(bucketName));
            // If we get here, bucket exists
            throw new CloudRuntimeException("Bucket name '" + bucketName + "' is already taken in AWS S3. Please choose a different name.");
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) {
                // Bucket does not exist — proceed
            } else if (e.getStatusCode() == 403) {
                throw new CloudRuntimeException("Bucket name '" + bucketName + "' is already taken in AWS S3. Please choose a different name.");
            } else {
                throw new CloudRuntimeException("Error checking bucket existence: " + e.getMessage(), e);
            }
        }

        // Create the bucket
        Map<String, String> storeDetails = _storeDetailsDao.getDetails(storeId);
        String region = storeDetails.get(REGION);
        try {
            CreateBucketRequest createRequest = new CreateBucketRequest(bucketName, region);
            if (objectLock) {
                createRequest.setObjectLockEnabledForBucket(true);
            }
            s3Client.createBucket(createRequest);
        } catch (AmazonServiceException e) {
            throw new CloudRuntimeException("Failed to create bucket '" + bucketName + "': " + e.getMessage(), e);
        }

        // Post-creation fixups
        try {
            // Remove Block Public Access so ACLs and bucket policies can work
            s3Client.deletePublicAccessBlock(
                    new DeletePublicAccessBlockRequest().withBucketName(bucketName));
        } catch (Exception e) {
            logger.warn("Failed to delete public access block for bucket " + bucketName + ": " + e.getMessage());
        }

        try {
            // Set BucketOwnerPreferred ownership controls to enable ACLs
            OwnershipControlsRule rule = new OwnershipControlsRule()
                    .withOwnership(ObjectOwnership.BucketOwnerPreferred);
            OwnershipControls controls = new OwnershipControls()
                    .withRules(Collections.singletonList(rule));
            s3Client.setBucketOwnershipControls(
                    new SetBucketOwnershipControlsRequest()
                            .withBucketName(bucketName)
                            .withOwnershipControls(controls));
        } catch (Exception e) {
            logger.warn("Failed to set ownership controls for bucket " + bucketName + ": " + e.getMessage());
        }

        // Update the BucketVO with URL and credentials
        String accessKey = _accountDetailsDao.findDetail(accountId, S3_ACCESS_KEY).getValue();
        String secretKey = _accountDetailsDao.findDetail(accountId, S3_SECRET_KEY).getValue();
        ObjectStoreVO store = _storeDao.findById(storeId);
        BucketVO bucketVO = _bucketDao.findById(bucket.getId());
        bucketVO.setAccessKey(accessKey);
        bucketVO.setSecretKey(secretKey);
        bucketVO.setBucketURL("https://s3." + region + ".amazonaws.com/" + bucketName);
        _bucketDao.update(bucket.getId(), bucketVO);
        return bucket;
    }

    @Override
    public List<Bucket> listBuckets(long storeId) {
        AmazonS3 s3Client = getS3Client(storeId);
        List<Bucket> bucketsList = new ArrayList<>();
        try {
            List<com.amazonaws.services.s3.model.Bucket> s3Buckets = s3Client.listBuckets();
            for (com.amazonaws.services.s3.model.Bucket s3Bucket : s3Buckets) {
                Bucket bucket = new BucketObject();
                bucket.setName(s3Bucket.getName());
                bucketsList.add(bucket);
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to list buckets: " + e.getMessage(), e);
        }
        return bucketsList;
    }

    @Override
    public boolean deleteBucket(BucketTO bucket, long storeId) {
        String bucketName = bucket.getName();
        AmazonS3 s3Client = getS3Client(storeId);

        try {
            s3Client.headBucket(new HeadBucketRequest(bucketName));
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) {
                logger.warn("Bucket does not exist in S3, skipping delete: " + bucketName);
                return true;
            }
            throw new CloudRuntimeException("Error checking bucket existence: " + e.getMessage(), e);
        }

        try {
            s3Client.deleteBucket(bucketName);
        } catch (AmazonServiceException e) {
            if ("BucketNotEmpty".equals(e.getErrorCode())) {
                throw new CloudRuntimeException("Cannot delete bucket '" + bucketName + "': bucket is not empty. Delete all objects first.");
            }
            throw new CloudRuntimeException("Failed to delete bucket '" + bucketName + "': " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public AccessControlList getBucketAcl(BucketTO bucket, long storeId) {
        return null;
    }

    @Override
    public void setBucketAcl(BucketTO bucket, AccessControlList acl, long storeId) {
    }

    @Override
    public void setBucketPolicy(BucketTO bucket, String policy, long storeId) {
        String bucketName = bucket.getName();
        AmazonS3 s3Client = getS3Client(storeId);

        if ("public".equalsIgnoreCase(policy)) {
            String publicPolicy = "{\n" +
                    "    \"Version\": \"2012-10-17\",\n" +
                    "    \"Statement\": [\n" +
                    "        {\n" +
                    "            \"Sid\": \"PublicReadGetObject\",\n" +
                    "            \"Effect\": \"Allow\",\n" +
                    "            \"Principal\": \"*\",\n" +
                    "            \"Action\": [\n" +
                    "                \"s3:GetBucketLocation\",\n" +
                    "                \"s3:ListBucket\"\n" +
                    "            ],\n" +
                    "            \"Resource\": \"arn:aws:s3:::" + bucketName + "\"\n" +
                    "        },\n" +
                    "        {\n" +
                    "            \"Sid\": \"PublicReadGetObject2\",\n" +
                    "            \"Effect\": \"Allow\",\n" +
                    "            \"Principal\": \"*\",\n" +
                    "            \"Action\": \"s3:GetObject\",\n" +
                    "            \"Resource\": \"arn:aws:s3:::" + bucketName + "/*\"\n" +
                    "        }\n" +
                    "    ]\n" +
                    "}";
            try {
                s3Client.setBucketPolicy(bucketName, publicPolicy);
            } catch (Exception e) {
                throw new CloudRuntimeException("Failed to set public policy on bucket '" + bucketName + "': " + e.getMessage(), e);
            }
        } else {
            // Private: remove policy
            deleteBucketPolicy(bucket, storeId);
        }
    }

    @Override
    public BucketPolicy getBucketPolicy(BucketTO bucket, long storeId) {
        return null;
    }

    @Override
    public void deleteBucketPolicy(BucketTO bucket, long storeId) {
        String bucketName = bucket.getName();
        AmazonS3 s3Client = getS3Client(storeId);
        try {
            s3Client.deleteBucketPolicy(bucketName);
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to delete policy on bucket '" + bucketName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public boolean createUser(long accountId, long storeId) {
        // Check if credentials already exist
        if (_accountDetailsDao.findDetail(accountId, S3_ACCESS_KEY) != null
                && _accountDetailsDao.findDetail(accountId, S3_SECRET_KEY) != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("AWS S3 credentials already exist for account: " + accountId);
            }
            return true;
        }

        // Generate synthetic CloudStack-issued credentials for proxy authentication
        String accessKey = "AKIACS" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();

        KeyGenerator generator;
        try {
            generator = KeyGenerator.getInstance("HmacSHA256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CloudRuntimeException("Failed to generate secret key", e);
        }
        SecretKey key = generator.generateKey();
        String secretKey = Base64.encodeBase64URLSafeString(key.getEncoded());

        Map<String, String> details = _accountDetailsDao.findDetails(accountId);
        details.put(S3_ACCESS_KEY, accessKey);
        details.put(S3_SECRET_KEY, secretKey);
        _accountDetailsDao.persist(accountId, details);

        if (logger.isDebugEnabled()) {
            logger.debug("Generated AWS S3 proxy credentials for account: " + accountId + ", accessKey: " + accessKey);
        }
        return true;
    }

    @Override
    public boolean setBucketEncryption(BucketTO bucket, long storeId) {
        AmazonS3 s3Client = getS3Client(storeId);
        try {
            ServerSideEncryptionByDefault sseDefault = new ServerSideEncryptionByDefault()
                    .withSSEAlgorithm(SSEAlgorithm.AES256);
            ServerSideEncryptionRule rule = new ServerSideEncryptionRule()
                    .withApplyServerSideEncryptionByDefault(sseDefault);
            ServerSideEncryptionConfiguration sseConfig = new ServerSideEncryptionConfiguration()
                    .withRules(Collections.singletonList(rule));
            s3Client.setBucketEncryption(new SetBucketEncryptionRequest()
                    .withBucketName(bucket.getName())
                    .withServerSideEncryptionConfiguration(sseConfig));
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to set encryption on bucket '" + bucket.getName() + "': " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean deleteBucketEncryption(BucketTO bucket, long storeId) {
        AmazonS3 s3Client = getS3Client(storeId);
        try {
            s3Client.deleteBucketEncryption(bucket.getName());
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to delete encryption on bucket '" + bucket.getName() + "': " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean setBucketVersioning(BucketTO bucket, long storeId) {
        AmazonS3 s3Client = getS3Client(storeId);
        try {
            s3Client.setBucketVersioningConfiguration(
                    new SetBucketVersioningConfigurationRequest(
                            bucket.getName(),
                            new BucketVersioningConfiguration(BucketVersioningConfiguration.ENABLED)));
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to enable versioning on bucket '" + bucket.getName() + "': " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean deleteBucketVersioning(BucketTO bucket, long storeId) {
        AmazonS3 s3Client = getS3Client(storeId);
        try {
            s3Client.setBucketVersioningConfiguration(
                    new SetBucketVersioningConfigurationRequest(
                            bucket.getName(),
                            new BucketVersioningConfiguration(BucketVersioningConfiguration.SUSPENDED)));
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to suspend versioning on bucket '" + bucket.getName() + "': " + e.getMessage(), e);
        }
        return true;
    }

    @Override
    public void setBucketQuota(BucketTO bucket, long storeId, long size) {
        // S3 does not natively support bucket quotas.
        // Quota is tracked in CloudStack DB only (advisory).
        logger.debug("setBucketQuota is advisory for AWS S3 — quota stored in CloudStack DB only for bucket: " + bucket.getName());
    }

    @Override
    public Map<String, Long> getAllBucketsUsage(long storeId) {
        // CloudWatch BucketSizeBytes has ~48h delay; return empty map for now.
        // Future: query CloudWatch GetMetricStatistics for BucketSizeBytes.
        return new HashMap<>();
    }

    protected AmazonS3 getS3Client(long storeId) {
        ObjectStoreVO store = _storeDao.findById(storeId);
        Map<String, String> storeDetails = _storeDetailsDao.getDetails(storeId);
        String accessKey = storeDetails.get(ACCESS_KEY);
        String secretKey = storeDetails.get(SECRET_KEY);
        String region = storeDetails.get(REGION);

        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(accessKey, secretKey)))
                .build();
        return s3Client;
    }
}
