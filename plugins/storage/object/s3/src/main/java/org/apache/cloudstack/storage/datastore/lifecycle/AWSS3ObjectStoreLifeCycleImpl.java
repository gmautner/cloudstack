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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
            throw new CloudRuntimeException("S3 middleware configuration is missing");
        }

        String adminUrl = details.get("adminurl");
        String apiKey = details.get("apikey");

        if (adminUrl == null || apiKey == null) {
            throw new CloudRuntimeException("S3 middleware requires adminurl and apikey");
        }

        // Validate connectivity by calling the health endpoint
        try {
            URL healthUrl = new URL(adminUrl + "/admin/health");
            HttpURLConnection conn = (HttpURLConnection) healthUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes());
            is.close();

            if (status != 200) {
                throw new RuntimeException("Health check failed (" + status + "): " + body);
            }
            logger.debug("Successfully connected to S3 middleware at: " + adminUrl);
        } catch (Exception e) {
            logger.debug("Error while initializing AWS S3 Object Store: " + e.getMessage());
            throw new RuntimeException("Error while initializing AWS S3 Object Store. Cannot reach S3 middleware at " + adminUrl + ": " + e.getMessage());
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
