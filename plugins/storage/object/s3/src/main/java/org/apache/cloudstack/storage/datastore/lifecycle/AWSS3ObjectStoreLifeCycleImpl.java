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
package org.apache.cloudstack.storage.datastore.lifecycle;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.datastore.ObjectStoreHelper;
import org.apache.cloudstack.storage.object.datastore.ObjectStoreProviderManager;
import org.apache.cloudstack.storage.object.store.lifecycle.ObjectStoreLifeCycle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;

public class AWSS3ObjectStoreLifeCycleImpl implements ObjectStoreLifeCycle {

    protected Logger logger = LogManager.getLogger(AWSS3ObjectStoreLifeCycleImpl.class);

    @Inject
    ObjectStoreHelper objectStoreHelper;
    @Inject
    ObjectStoreProviderManager objectStoreMgr;

    public AWSS3ObjectStoreLifeCycleImpl() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public DataStore initialize(Map<String, Object> dsInfos) {
        String url = (String) dsInfos.get("url");
        String name = (String) dsInfos.get("name");
        Long size = (Long) dsInfos.get("size");
        String providerName = (String) dsInfos.get("providerName");
        Map<String, String> details = (Map<String, String>) dsInfos.get("details");

        if (details == null) {
            throw new CloudRuntimeException("AWS S3 credentials are missing");
        }

        String accessKey = details.get("accesskey");
        String secretKey = details.get("secretkey");
        String region = details.get("region");

        if (accessKey == null || secretKey == null || region == null) {
            throw new CloudRuntimeException("AWS S3 requires accesskey, secretkey, and region");
        }

        // Validate credentials by listing buckets
        try {
            AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials(accessKey, secretKey)))
                    .build();
            s3Client.listBuckets();
            logger.debug("Successfully connected to AWS S3 in region: " + region);
        } catch (Exception e) {
            logger.debug("Error while initializing AWS S3 Object Store: " + e.getMessage());
            throw new RuntimeException("Error while initializing AWS S3 Object Store. Invalid credentials or region: " + e.getMessage());
        }

        Map<String, Object> objectStoreParameters = new HashMap<>();
        objectStoreParameters.put("name", name);
        objectStoreParameters.put("url", url);
        objectStoreParameters.put("size", size);
        objectStoreParameters.put("providerName", providerName);

        ObjectStoreVO objectStore = objectStoreHelper.createObjectStore(objectStoreParameters, details);
        return objectStoreMgr.getObjectStore(objectStore.getId());
    }

    @Override
    public boolean attachCluster(DataStore store, ClusterScope scope) {
        return false;
    }

    @Override
    public boolean attachHost(DataStore store, HostScope scope, StoragePoolInfo existingInfo) {
        return false;
    }

    @Override
    public boolean attachZone(DataStore dataStore, ZoneScope scope, HypervisorType hypervisorType) {
        return false;
    }

    @Override
    public boolean maintain(DataStore store) {
        return false;
    }

    @Override
    public boolean cancelMaintain(DataStore store) {
        return false;
    }

    @Override
    public boolean deleteDataStore(DataStore store) {
        return false;
    }

    @Override
    public boolean migrateToObjectStore(DataStore store) {
        return false;
    }
}
