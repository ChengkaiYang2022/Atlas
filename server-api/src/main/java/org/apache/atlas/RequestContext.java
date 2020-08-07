/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas;

import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.store.DeleteType;
import org.apache.atlas.utils.AtlasPerfMetrics;
import org.apache.atlas.utils.AtlasPerfMetrics.MetricRecorder;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RequestContext {
    private static final Logger METRICS = LoggerFactory.getLogger("METRICS");

    private static final ThreadLocal<RequestContext> CURRENT_CONTEXT = new ThreadLocal<>();
    private static final Set<RequestContext>         ACTIVE_REQUESTS = new HashSet<>();
    private static final boolean                     isMetricsEnabled = METRICS.isDebugEnabled();

    private final long                                   requestTime         = System.currentTimeMillis();
    private final Map<String, AtlasEntityHeader>         updatedEntities     = new HashMap<>();
    private final Map<String, AtlasEntityHeader>         deletedEntities     = new HashMap<>();
    private final Map<String, AtlasEntity>               entityCache         = new HashMap<>();
    private final Map<String, AtlasEntityWithExtInfo>    entityExtInfoCache  = new HashMap<>();
    private final Map<String, List<AtlasClassification>> addedPropagations   = new HashMap<>();
    private final Map<String, List<AtlasClassification>> removedPropagations = new HashMap<>();
    private final AtlasPerfMetrics                       metrics             = isMetricsEnabled ? new AtlasPerfMetrics() : null;
    private       List<EntityGuidPair>                   entityGuidInRequest = null;

    private String      user;
    private Set<String> userGroups;
    private String      clientIPAddress;
    private DeleteType  deleteType   = DeleteType.DEFAULT;
    private int         maxAttempts  = 1;
    private int         attemptCount = 1;
    private boolean     isImportInProgress = false;
    private boolean     isInNotificationProcessing = false;
    private boolean     isInTypePatching           = false;


    private RequestContext() {
    }

    //To handle gets from background threads where createContext() is not called
    //createContext called for every request in the filter
    public static RequestContext get() {
        RequestContext ret = CURRENT_CONTEXT.get();

        if (ret == null) {
            ret = new RequestContext();
            CURRENT_CONTEXT.set(ret);

            synchronized (ACTIVE_REQUESTS) {
                ACTIVE_REQUESTS.add(ret);
            }
        }

        return ret;
    }

    public static void clear() {
        RequestContext instance = CURRENT_CONTEXT.get();

        if (instance != null) {
            instance.clearCache();

            synchronized (ACTIVE_REQUESTS) {
                ACTIVE_REQUESTS.remove(instance);
            }
        }

        CURRENT_CONTEXT.remove();
    }

    public void clearCache() {
        this.updatedEntities.clear();
        this.deletedEntities.clear();
        this.entityCache.clear();
        this.entityExtInfoCache.clear();
        this.addedPropagations.clear();
        this.removedPropagations.clear();

        if (metrics != null && !metrics.isEmpty()) {
            METRICS.debug(metrics.toString());

            metrics.clear();
        }

        if (this.entityGuidInRequest != null) {
            this.entityGuidInRequest.clear();
        }
    }

    public static String getCurrentUser() {
        RequestContext context = CURRENT_CONTEXT.get();
        return context != null ? context.getUser() : null;
    }

    public String getUser() {
        return user;
    }

    public Set<String> getUserGroups() {
        return userGroups;
    }

    public void setUser(String user, Set<String> userGroups) {
        this.user       = user;
        this.userGroups = userGroups;
    }

    public DeleteType getDeleteType() { return deleteType; }

    public void setDeleteType(DeleteType deleteType) { this.deleteType = (deleteType == null) ? DeleteType.DEFAULT : deleteType; }

    public String getClientIPAddress() {
        return clientIPAddress;
    }

    public void setClientIPAddress(String clientIPAddress) {
        this.clientIPAddress = clientIPAddress;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public boolean isImportInProgress() {
        return isImportInProgress;
    }

    public void setImportInProgress(boolean importInProgress) {
        isImportInProgress = importInProgress;
    }

    public boolean isInNotificationProcessing() {
        return isInNotificationProcessing;
    }

    public void setInNotificationProcessing(boolean inNotificationProcessing) {
        isInNotificationProcessing = inNotificationProcessing;
    }

    public boolean isInTypePatching() {
        return isInTypePatching;
    }

    public void setInTypePatching(boolean inTypePatching) {
        isInTypePatching = inTypePatching;
    }


    public void recordEntityUpdate(AtlasEntityHeader entity) {
        if (entity != null && entity.getGuid() != null) {
            updatedEntities.put(entity.getGuid(), entity);
        }
    }

    public void recordEntityDelete(AtlasEntityHeader entity) {
        if (entity != null && entity.getGuid() != null) {
            deletedEntities.put(entity.getGuid(), entity);
        }
    }

    public void recordAddedPropagation(String guid, AtlasClassification classification) {
        if (StringUtils.isNotEmpty(guid) && classification != null) {
            List<AtlasClassification> classifications = addedPropagations.get(guid);

            if (classifications == null) {
                classifications = new ArrayList<>();
            }

            classifications.add(classification);

            addedPropagations.put(guid, classifications);
        }
    }

    public static int getActiveRequestsCount() {
        return ACTIVE_REQUESTS.size();
    }

    public static long earliestActiveRequestTime() {
        long ret = System.currentTimeMillis();

        synchronized (ACTIVE_REQUESTS) {
            for (RequestContext context : ACTIVE_REQUESTS) {
                if (ret > context.getRequestTime()) {
                    ret = context.getRequestTime();
                }
            }
        }

        return ret;
    }

    public void recordRemovedPropagation(String guid, AtlasClassification classification) {
        if (StringUtils.isNotEmpty(guid) && classification != null) {
            List<AtlasClassification> classifications = removedPropagations.get(guid);

            if (classifications == null) {
                classifications = new ArrayList<>();
            }

            classifications.add(classification);

            removedPropagations.put(guid, classifications);
        }
    }

    public Map<String, List<AtlasClassification>> getAddedPropagations() {
        return addedPropagations;
    }

    public Map<String, List<AtlasClassification>> getRemovedPropagations() {
        return removedPropagations;
    }

    /**
     * Adds the specified instance to the cache
     *
     */
    public void cache(AtlasEntityWithExtInfo entity) {
        if (entity != null && entity.getEntity() != null && entity.getEntity().getGuid() != null) {
            entityExtInfoCache.put(entity.getEntity().getGuid(), entity);
            entityCache.put(entity.getEntity().getGuid(), entity.getEntity());
        }
    }

    public void cache(AtlasEntity entity) {
        if (entity != null && entity.getGuid() != null) {
            entityCache.put(entity.getGuid(), entity);
        }
    }


    public Collection<AtlasEntityHeader> getUpdatedEntities() {
        return updatedEntities.values();
    }

    public Collection<AtlasEntityHeader> getDeletedEntities() {
        return deletedEntities.values();
    }

    /**
     * Checks if an instance with the given guid is in the cache for this request.  Either returns the instance
     * or null if it is not in the cache.
     *
     * @param guid the guid to find
     * @return Either the instance or null if it is not in the cache.
     */
    public AtlasEntityWithExtInfo getEntityWithExtInfo(String guid) {
        return entityExtInfoCache.get(guid);
    }

    public AtlasEntity getEntity(String guid) {
        return entityCache.get(guid);
    }

    public long getRequestTime() {
        return requestTime;
    }

    public boolean isUpdatedEntity(String guid) {
        return updatedEntities.containsKey(guid);
    }

    public boolean isDeletedEntity(String guid) {
        return deletedEntities.containsKey(guid);
    }



    public MetricRecorder startMetricRecord(String name) { return metrics != null ? metrics.getMetricRecorder(name) : null; }

    public void endMetricRecord(MetricRecorder recorder) {
        if (metrics != null && recorder != null) {
            metrics.recordMetric(recorder);
        }
    }

    public void recordEntityGuidUpdate(AtlasEntity entity, String guidInRequest) {
        if (entityGuidInRequest == null) {
            entityGuidInRequest = new ArrayList<>();
        }

        entityGuidInRequest.add(new EntityGuidPair(entity, guidInRequest));
    }

    public void resetEntityGuidUpdates() {
        if (entityGuidInRequest != null) {
            for (EntityGuidPair entityGuidPair : entityGuidInRequest) {
                entityGuidPair.resetEntityGuid();
            }
        }
    }

    public class EntityGuidPair {
        private final AtlasEntity entity;
        private final String      guid;

        public EntityGuidPair(AtlasEntity entity, String guid) {
            this.entity = entity;
            this.guid   = guid;
        }

        public void resetEntityGuid() {
            entity.setGuid(guid);
        }
    }
}
