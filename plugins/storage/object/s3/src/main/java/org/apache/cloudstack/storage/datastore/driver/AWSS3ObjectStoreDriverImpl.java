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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDetailsDao;
import org.apache.cloudstack.storage.object.BaseObjectStoreDriverImpl;
import org.apache.cloudstack.storage.object.Bucket;

import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.BucketPolicy;
import com.cloud.agent.api.to.BucketTO;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class AWSS3ObjectStoreDriverImpl extends BaseObjectStoreDriverImpl {

    private static final String ADMIN_URL = "adminurl";
    private static final String API_KEY = "apikey";
    private static final String REGION = "region";

    protected static final String S3_ACCESS_KEY = "s3-accesskey";
    protected static final String S3_SECRET_KEY = "s3-secretkey";

    private static final Gson gson = new Gson();

    @Inject
    AccountDao _accountDao;

    @Inject
    AccountDetailsDao _accountDetailsDao;

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

        if ((_accountDetailsDao.findDetail(accountId, S3_ACCESS_KEY) == null)
                || (_accountDetailsDao.findDetail(accountId, S3_SECRET_KEY) == null)) {
            throw new CloudRuntimeException("Bucket access credentials unavailable for account: " + accountId);
        }

        String accountUuid = _accountDao.findById(accountId).getUuid();
        JsonObject body = new JsonObject();
        body.addProperty("name", bucketName);
        body.addProperty("account_id", accountUuid);
        body.addProperty("object_lock", objectLock);

        JsonObject resp = adminPost(storeId, "/admin/buckets", body);

        String accessKey = _accountDetailsDao.findDetail(accountId, S3_ACCESS_KEY).getValue();
        String secretKey = _accountDetailsDao.findDetail(accountId, S3_SECRET_KEY).getValue();
        Map<String, String> storeDetails = _storeDetailsDao.getDetails(storeId);
        String region = storeDetails.get(REGION);

        BucketVO bucketVO = _bucketDao.findById(bucket.getId());
        bucketVO.setAccessKey(accessKey);
        bucketVO.setSecretKey(secretKey);
        bucketVO.setBucketURL("https://s3." + region + ".amazonaws.com/" + bucketName);
        _bucketDao.update(bucket.getId(), bucketVO);
        return bucket;
    }

    @Override
    public List<Bucket> listBuckets(long storeId) {
        return new ArrayList<>();
    }

    @Override
    public boolean deleteBucket(BucketTO bucket, long storeId) {
        adminDelete(storeId, "/admin/buckets/" + bucket.getName());
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
        JsonObject body = new JsonObject();
        body.addProperty("policy", policy);
        adminPut(storeId, "/admin/buckets/" + bucket.getName() + "/policy", body);
    }

    @Override
    public BucketPolicy getBucketPolicy(BucketTO bucket, long storeId) {
        return null;
    }

    @Override
    public void deleteBucketPolicy(BucketTO bucket, long storeId) {
        JsonObject body = new JsonObject();
        body.addProperty("policy", "private");
        adminPut(storeId, "/admin/buckets/" + bucket.getName() + "/policy", body);
    }

    @Override
    public boolean createUser(long accountId, long storeId) {
        if (_accountDetailsDao.findDetail(accountId, S3_ACCESS_KEY) != null
                && _accountDetailsDao.findDetail(accountId, S3_SECRET_KEY) != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("AWS S3 credentials already exist for account: " + accountId);
            }
            return true;
        }

        String accountUuid = _accountDao.findById(accountId).getUuid();
        JsonObject body = new JsonObject();
        body.addProperty("id", accountUuid);

        JsonObject resp = adminPost(storeId, "/admin/users", body);

        String accessKey = resp.get("access_key").getAsString();
        String secretKey = resp.get("secret_key").getAsString();

        Map<String, String> details = _accountDetailsDao.findDetails(accountId);
        details.put(S3_ACCESS_KEY, accessKey);
        details.put(S3_SECRET_KEY, secretKey);
        _accountDetailsDao.persist(accountId, details);

        if (logger.isDebugEnabled()) {
            logger.debug("Registered S3 middleware credentials for account: " + accountId + ", accessKey: " + accessKey);
        }
        return true;
    }

    @Override
    public boolean setBucketEncryption(BucketTO bucket, long storeId) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", true);
        adminPut(storeId, "/admin/buckets/" + bucket.getName() + "/encryption", body);
        return true;
    }

    @Override
    public boolean deleteBucketEncryption(BucketTO bucket, long storeId) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", false);
        adminPut(storeId, "/admin/buckets/" + bucket.getName() + "/encryption", body);
        return true;
    }

    @Override
    public boolean setBucketVersioning(BucketTO bucket, long storeId) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", true);
        adminPut(storeId, "/admin/buckets/" + bucket.getName() + "/versioning", body);
        return true;
    }

    @Override
    public boolean deleteBucketVersioning(BucketTO bucket, long storeId) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", false);
        adminPut(storeId, "/admin/buckets/" + bucket.getName() + "/versioning", body);
        return true;
    }

    @Override
    public void setBucketQuota(BucketTO bucket, long storeId, long size) {
        logger.debug("setBucketQuota is advisory for AWS S3 — quota stored in CloudStack DB only for bucket: " + bucket.getName());
    }

    @Override
    public Map<String, Long> getAllBucketsUsage(long storeId) {
        return new HashMap<>();
    }

    // --- Middleware admin API HTTP helpers ---

    private String[] getAdminConfig(long storeId) {
        Map<String, String> details = _storeDetailsDao.getDetails(storeId);
        String adminUrl = details.get(ADMIN_URL);
        String apiKey = details.get(API_KEY);
        if (adminUrl == null || apiKey == null) {
            throw new CloudRuntimeException("S3 middleware admin URL or API key not configured for store: " + storeId);
        }
        return new String[]{adminUrl, apiKey};
    }

    private JsonObject adminPost(long storeId, String path, JsonObject body) {
        return adminRequest(storeId, "POST", path, body);
    }

    private JsonObject adminPut(long storeId, String path, JsonObject body) {
        return adminRequest(storeId, "PUT", path, body);
    }

    private JsonObject adminDelete(long storeId, String path) {
        return adminRequest(storeId, "DELETE", path, null);
    }

    private JsonObject adminRequest(long storeId, String method, String path, JsonObject body) {
        String[] config = getAdminConfig(storeId);
        String adminUrl = config[0];
        String apiKey = config[1];

        try {
            URL url = new URL(adminUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            if (body != null) {
                conn.setDoOutput(true);
                byte[] jsonBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBytes);
                }
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            is.close();

            if (status >= 200 && status < 300) {
                return gson.fromJson(responseBody, JsonObject.class);
            }

            // Parse error
            JsonObject errorResp = gson.fromJson(responseBody, JsonObject.class);
            String errorMsg = errorResp.has("error") ? errorResp.get("error").getAsString() : responseBody;
            throw new CloudRuntimeException("S3 middleware error (" + status + "): " + errorMsg);
        } catch (CloudRuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to communicate with S3 middleware: " + e.getMessage(), e);
        }
    }
}
