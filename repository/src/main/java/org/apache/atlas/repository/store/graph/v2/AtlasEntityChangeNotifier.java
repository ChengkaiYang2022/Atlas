/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.graph.v2;


import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.AtlasException;
import org.apache.atlas.RequestContext;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.listener.EntityChangeListener;
import org.apache.atlas.listener.EntityChangeListenerV2;
import org.apache.atlas.model.audit.EntityAuditEventV2.EntityAuditActionV2;
import org.apache.atlas.model.glossary.AtlasGlossaryTerm;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;

import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasRelatedObjectId;
import org.apache.atlas.model.instance.AtlasRelationship;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.model.instance.EntityMutations.EntityOperation;
import org.apache.atlas.model.notification.EntityNotification;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.utils.AtlasPerfMetrics.MetricRecorder;
import org.apache.atlas.v1.model.instance.Referenceable;
import org.apache.atlas.v1.model.instance.Struct;
import org.apache.atlas.repository.converters.AtlasInstanceConverter;
import org.apache.atlas.repository.graph.FullTextMapperV2;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.util.AtlasRepositoryConfiguration;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import static org.apache.atlas.model.audit.EntityAuditEventV2.EntityAuditActionV2.PROPAGATED_CLASSIFICATION_ADD;
import static org.apache.atlas.model.audit.EntityAuditEventV2.EntityAuditActionV2.PROPAGATED_CLASSIFICATION_DELETE;
import static org.apache.atlas.repository.Constants.ENTITY_TEXT_PROPERTY_KEY;


@Component
public class AtlasEntityChangeNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasEntityChangeNotifier.class);

    private final Set<EntityChangeListener>   entityChangeListeners;
    private final Set<EntityChangeListenerV2> entityChangeListenersV2;
    private final AtlasInstanceConverter      instanceConverter;
    private final FullTextMapperV2            fullTextMapperV2;
    private final AtlasTypeRegistry           atlasTypeRegistry;
    private final boolean                     isV2EntityNotificationEnabled;


    @Inject
    public AtlasEntityChangeNotifier(Set<EntityChangeListener> entityChangeListeners,
                                     Set<EntityChangeListenerV2> entityChangeListenersV2,
                                     AtlasInstanceConverter instanceConverter,
                                     FullTextMapperV2 fullTextMapperV2,
                                     AtlasTypeRegistry atlasTypeRegistry) {
        this.entityChangeListeners         = entityChangeListeners;
        this.entityChangeListenersV2       = entityChangeListenersV2;
        this.instanceConverter             = instanceConverter;
        this.fullTextMapperV2              = fullTextMapperV2;
        this.atlasTypeRegistry             = atlasTypeRegistry;
        this.isV2EntityNotificationEnabled = AtlasRepositoryConfiguration.isV2EntityNotificationEnabled();
    }

    public void onEntitiesMutated(EntityMutationResponse entityMutationResponse, boolean isImport) throws AtlasBaseException {
        if (CollectionUtils.isEmpty(entityChangeListeners) || instanceConverter == null) {
            return;
        }

        pruneResponse(entityMutationResponse);

        List<AtlasEntityHeader> createdEntities          = entityMutationResponse.getCreatedEntities();
        List<AtlasEntityHeader> updatedEntities          = entityMutationResponse.getUpdatedEntities();
        List<AtlasEntityHeader> partiallyUpdatedEntities = entityMutationResponse.getPartialUpdatedEntities();
        List<AtlasEntityHeader> deletedEntities          = entityMutationResponse.getDeletedEntities();

        // complete full text mapping before calling toReferenceables(), from notifyListners(), to
        // include all vertex updates in the current graph-transaction
        doFullTextMapping(createdEntities);
        doFullTextMapping(updatedEntities);
        doFullTextMapping(partiallyUpdatedEntities);

        notifyListeners(createdEntities, EntityOperation.CREATE, isImport);
        notifyListeners(updatedEntities, EntityOperation.UPDATE, isImport);
        notifyListeners(partiallyUpdatedEntities, EntityOperation.PARTIAL_UPDATE, isImport);
        notifyListeners(deletedEntities, EntityOperation.DELETE, isImport);

        notifyPropagatedEntities();
    }

    public void notifyRelationshipMutation(AtlasRelationship relationship, EntityNotification.EntityNotificationV2.OperationType operationType) throws AtlasBaseException {
        if (CollectionUtils.isEmpty(entityChangeListeners) || instanceConverter == null) {
            return;
        }

        switch (operationType) {
            case RELATIONSHIP_CREATE:
                notifyRelationshipListeners(Collections.singletonList(relationship), EntityOperation.CREATE, false);
                break;
            case RELATIONSHIP_UPDATE:
                notifyRelationshipListeners(Collections.singletonList(relationship), EntityOperation.UPDATE, false);
                break;
            case RELATIONSHIP_DELETE:
                notifyRelationshipListeners(Collections.singletonList(relationship), EntityOperation.DELETE, false);
                break;
        }
    }

    public void onClassificationAddedToEntity(AtlasEntity entity, List<AtlasClassification> addedClassifications) throws AtlasBaseException {
        if (isV2EntityNotificationEnabled) {
            doFullTextMapping(entity.getGuid());

            for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
                listener.onClassificationsAdded(entity, addedClassifications);
            }
        } else {
            updateFullTextMapping(entity.getGuid(), addedClassifications);

            Referenceable entityRef = toReferenceable(entity.getGuid());
            List<Struct>  traits    = toStruct(addedClassifications);

            if (entity == null || CollectionUtils.isEmpty(traits)) {
                return;
            }

            for (EntityChangeListener listener : entityChangeListeners) {
                try {
                    listener.onTraitsAdded(entityRef, traits);
                } catch (AtlasException e) {
                    throw new AtlasBaseException(AtlasErrorCode.NOTIFICATION_FAILED, e, getListenerName(listener), "TraitAdd");
                }
            }
        }
    }

    public void onClassificationUpdatedToEntity(AtlasEntity entity, List<AtlasClassification> updatedClassifications) throws AtlasBaseException {
        if (isV2EntityNotificationEnabled) {
            doFullTextMapping(entity.getGuid());

            for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
                listener.onClassificationsUpdated(entity, updatedClassifications);
            }
        } else {
            doFullTextMapping(entity.getGuid());

            Referenceable entityRef = toReferenceable(entity.getGuid());
            List<Struct>  traits    = toStruct(updatedClassifications);

            if (entityRef == null || CollectionUtils.isEmpty(traits)) {
                return;
            }

            for (EntityChangeListener listener : entityChangeListeners) {
                try {
                    listener.onTraitsUpdated(entityRef, traits);
                } catch (AtlasException e) {
                    throw new AtlasBaseException(AtlasErrorCode.NOTIFICATION_FAILED, e, getListenerName(listener), "TraitUpdate");
                }
            }
        }
    }

    public void onClassificationDeletedFromEntity(AtlasEntity entity, List<AtlasClassification> deletedClassifications) throws AtlasBaseException {
        if (isV2EntityNotificationEnabled) {
            doFullTextMapping(entity.getGuid());

            for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
                listener.onClassificationsDeleted(entity, deletedClassifications);
            }
        } else {
            doFullTextMapping(entity.getGuid());

            Referenceable entityRef = toReferenceable(entity.getGuid());
            List<Struct>  traits    = toStruct(deletedClassifications);

            if (entityRef == null || CollectionUtils.isEmpty(deletedClassifications)) {
                return;
            }

            for (EntityChangeListener listener : entityChangeListeners) {
                try {
                    listener.onTraitsDeleted(entityRef, traits);
                } catch (AtlasException e) {
                    throw new AtlasBaseException(AtlasErrorCode.NOTIFICATION_FAILED, e, getListenerName(listener), "TraitDelete");
                }
            }

        }
    }

    public void onTermAddedToEntities(AtlasGlossaryTerm term, List<AtlasRelatedObjectId> entityIds) throws AtlasBaseException {
        // listeners notified on term-entity association only if v2 notifications are enabled
        if (isV2EntityNotificationEnabled) {
            for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
                listener.onTermAdded(term, entityIds);
            }
        } else {
            List<Referenceable> entityRefs = toReferenceables(entityIds);

            for (EntityChangeListener listener : entityChangeListeners) {
                try {
                    listener.onTermAdded(entityRefs, term);
                } catch (AtlasException e) {
                    throw new AtlasBaseException(AtlasErrorCode.NOTIFICATION_FAILED, e, getListenerName(listener), "TermAdd");
                }
            }
        }
    }

    public void onTermDeletedFromEntities(AtlasGlossaryTerm term, List<AtlasRelatedObjectId> entityIds) throws AtlasBaseException {
        // listeners notified on term-entity disassociation only if v2 notifications are enabled
        if (isV2EntityNotificationEnabled) {
            for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
                listener.onTermDeleted(term, entityIds);
            }
        } else {
            List<Referenceable> entityRefs = toReferenceables(entityIds);

            for (EntityChangeListener listener : entityChangeListeners) {
                try {
                    listener.onTermDeleted(entityRefs, term);
                } catch (AtlasException e) {
                    throw new AtlasBaseException(AtlasErrorCode.NOTIFICATION_FAILED, e, getListenerName(listener), "TermDelete");
                }
            }
        }
    }

    public void notifyPropagatedEntities() throws AtlasBaseException {
        RequestContext                         context             = RequestContext.get();
        Map<String, List<AtlasClassification>> addedPropagations   = context.getAddedPropagations();
        Map<String, List<AtlasClassification>> removedPropagations = context.getRemovedPropagations();

        notifyPropagatedEntities(addedPropagations, PROPAGATED_CLASSIFICATION_ADD);
        notifyPropagatedEntities(removedPropagations, PROPAGATED_CLASSIFICATION_DELETE);
    }

    private void notifyPropagatedEntities(Map<String, List<AtlasClassification>> entityPropagationMap, EntityAuditActionV2 action) throws AtlasBaseException {
        if (MapUtils.isEmpty(entityPropagationMap) || action == null) {
            return;
        }

        RequestContext context = RequestContext.get();

        for (String guid : entityPropagationMap.keySet()) {
            // if entity is deleted, don't send propagated classifications add/remove notifications.
            if (context.isDeletedEntity(guid)) {
                continue;
            }

            AtlasEntity entity = instanceConverter.getAndCacheEntity(guid);

            if (entity == null) {
                continue;
            }

            if (action == PROPAGATED_CLASSIFICATION_ADD) {
                onClassificationAddedToEntity(entity, entityPropagationMap.get(guid));
            } else if (action == PROPAGATED_CLASSIFICATION_DELETE) {
                onClassificationDeletedFromEntity(entity, entityPropagationMap.get(guid));
            }
        }
    }

    private String getListenerName(EntityChangeListener listener) {
        return listener.getClass().getSimpleName();
    }

    private void notifyListeners(List<AtlasEntityHeader> entityHeaders, EntityOperation operation, boolean isImport) throws AtlasBaseException {
        if (CollectionUtils.isEmpty(entityHeaders)) {
            return;
        }

        if (isV2EntityNotificationEnabled) {
            notifyV2Listeners(entityHeaders, operation, isImport);
        } else {
            notifyV1Listeners(entityHeaders, operation, isImport);
        }
    }

    private void notifyRelationshipListeners(List<AtlasRelationship> relationships, EntityOperation operation, boolean isImport) throws AtlasBaseException {
        if (CollectionUtils.isEmpty(relationships)) {
            return;
        }

        if (isV2EntityNotificationEnabled) {
            notifyV2RelationshipListeners(relationships, operation, isImport);
            return;
        }

        LOG.warn("Relationships not supported by v1 notifications. {}", relationships);
    }


    private void notifyV1Listeners(List<AtlasEntityHeader> entityHeaders, EntityOperation operation, boolean isImport) throws AtlasBaseException {
        List<Referenceable> typedRefInsts = toReferenceables(entityHeaders, operation);

        for (EntityChangeListener listener : entityChangeListeners) {
            try {
                switch (operation) {
                    case CREATE:
                        listener.onEntitiesAdded(typedRefInsts, isImport);
                        break;
                    case UPDATE:
                    case PARTIAL_UPDATE:
                        listener.onEntitiesUpdated(typedRefInsts, isImport);
                        break;
                    case DELETE:
                        listener.onEntitiesDeleted(typedRefInsts, isImport);
                        break;
                }
            } catch (AtlasException e) {
                throw new AtlasBaseException(AtlasErrorCode.NOTIFICATION_FAILED, e, getListenerName(listener), operation.toString());
            }
        }
    }

    private void notifyV2Listeners(List<AtlasEntityHeader> entityHeaders, EntityOperation operation, boolean isImport) throws AtlasBaseException {
        List<AtlasEntity> entities = toAtlasEntities(entityHeaders, operation);

        for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
            switch (operation) {
                case CREATE:
                    listener.onEntitiesAdded(entities, isImport);
                    break;

                case UPDATE:
                case PARTIAL_UPDATE:
                    listener.onEntitiesUpdated(entities, isImport);
                    break;

                case DELETE:
                    listener.onEntitiesDeleted(entities, isImport);
                    break;
            }
        }
    }

    private void notifyV2RelationshipListeners(List<AtlasRelationship> relationships, EntityOperation operation, boolean isImport) throws AtlasBaseException {

        for (EntityChangeListenerV2 listener : entityChangeListenersV2) {
            switch (operation) {
                case CREATE:
                    listener.onRelationshipsAdded(relationships, isImport);
                    break;
                case UPDATE:
                case PARTIAL_UPDATE:
                    listener.onRelationshipsUpdated(relationships, isImport);
                    break;
                case DELETE:
                    listener.onRelationshipsDeleted(relationships, isImport);
                    break;
            }
        }
    }

        private List<Referenceable> toReferenceables(List<AtlasEntityHeader> entityHeaders, EntityOperation operation) throws AtlasBaseException {
        List<Referenceable> ret = new ArrayList<>(entityHeaders.size());

        // delete notifications don't need all attributes. Hence the special handling for delete operation
        if (operation == EntityOperation.DELETE) {
            for (AtlasEntityHeader entityHeader : entityHeaders) {
                ret.add(new Referenceable(entityHeader.getGuid(), entityHeader.getTypeName(), entityHeader.getAttributes()));
            }
        } else {
            for (AtlasEntityHeader entityHeader : entityHeaders) {
                ret.add(toReferenceable(entityHeader.getGuid()));
            }
        }

        return ret;
    }

    private List<Referenceable> toReferenceables(List<AtlasRelatedObjectId> entityIds) throws AtlasBaseException {
        List<Referenceable> ret = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(entityIds)) {
            for (AtlasRelatedObjectId relatedObjectId : entityIds) {
                String entityGuid = relatedObjectId.getGuid();

                ret.add(toReferenceable(entityGuid));
            }
        }

        return ret;
    }

    private Referenceable toReferenceable(String entityId) throws AtlasBaseException {
        Referenceable ret = null;

        if (StringUtils.isNotEmpty(entityId)) {
            ret = instanceConverter.getReferenceable(entityId);
        }

        return ret;
    }

        private List<Struct> toStruct(List<AtlasClassification> classifications) throws AtlasBaseException {
        List<Struct> ret = null;

        if (classifications != null) {
            ret = new ArrayList<>(classifications.size());

            for (AtlasClassification classification : classifications) {
                if (classification != null) {
                    ret.add(instanceConverter.getTrait(classification));
                }
            }
        }

        return ret;
    }

    private List<AtlasEntity> toAtlasEntities(List<AtlasEntityHeader> entityHeaders, EntityOperation operation) throws AtlasBaseException {
        List<AtlasEntity> ret = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(entityHeaders)) {
            for (AtlasEntityHeader entityHeader : entityHeaders) {
                String          typeName   = entityHeader.getTypeName();
                AtlasEntityType entityType = atlasTypeRegistry.getEntityTypeByName(typeName);

                if (entityType == null) {
                    continue;
                }

                // Skip all internal types as the HARD DELETE will cause lookup errors
                if (entityType.isInternalType()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Skipping internal type = {}", typeName);
                    }
                    continue;
                }

                final AtlasEntity entity;

                // delete notifications don't need all attributes. Hence the special handling for delete operation
                if (operation == EntityOperation.DELETE) {
                    entity = new AtlasEntity(entityHeader);
                } else {
                    String entityGuid = entityHeader.getGuid();

                    entity = instanceConverter.getAndCacheEntity(entityGuid);
                }

                if (entity != null) {
                    ret.add(entity);
                }
            }
        }

        return ret;
    }

    private void doFullTextMapping(List<AtlasEntityHeader> entityHeaders) {
        if (CollectionUtils.isEmpty(entityHeaders)) {
            return;
        }

        try {
            if(!AtlasRepositoryConfiguration.isFullTextSearchEnabled()) {
                return;
            }
        } catch (AtlasException e) {
            LOG.warn("Unable to determine if FullText is disabled. Proceeding with FullText mapping");
        }

        MetricRecorder metric = RequestContext.get().startMetricRecord("fullTextMapping");

        for (AtlasEntityHeader entityHeader : entityHeaders) {
            if(GraphHelper.isInternalType(entityHeader.getTypeName())) {
                continue;
            }

            String      guid   = entityHeader.getGuid();
            AtlasVertex vertex = AtlasGraphUtilsV2.findByGuid(guid);

            if(vertex == null) {
                continue;
            }

            try {
                String fullText = fullTextMapperV2.getIndexTextForEntity(guid);

                AtlasGraphUtilsV2.setEncodedProperty(vertex, ENTITY_TEXT_PROPERTY_KEY, fullText);
            } catch (AtlasBaseException e) {
                LOG.error("FullText mapping failed for Vertex[ guid = {} ]", guid, e);
            }
        }

        RequestContext.get().endMetricRecord(metric);
    }

    private void updateFullTextMapping(String entityId, List<AtlasClassification> classifications) {
        try {
            if(!AtlasRepositoryConfiguration.isFullTextSearchEnabled()) {
                return;
            }
        } catch (AtlasException e) {
            LOG.warn("Unable to determine if FullText is disabled. Proceeding with FullText mapping");
        }

        if (StringUtils.isEmpty(entityId) || CollectionUtils.isEmpty(classifications)) {
            return;
        }

        AtlasVertex atlasVertex = AtlasGraphUtilsV2.findByGuid(entityId);
        if(atlasVertex == null || GraphHelper.isInternalType(atlasVertex)) {
            return;
        }

        MetricRecorder metric = RequestContext.get().startMetricRecord("fullTextMapping");

        try {
            String classificationFullText = fullTextMapperV2.getIndexTextForClassifications(entityId, classifications);
            String existingFullText       = AtlasGraphUtilsV2.getEncodedProperty(atlasVertex, ENTITY_TEXT_PROPERTY_KEY, String.class);
            String newFullText            = existingFullText + " " + classificationFullText;

            AtlasGraphUtilsV2.setEncodedProperty(atlasVertex, ENTITY_TEXT_PROPERTY_KEY, newFullText);
        } catch (AtlasBaseException e) {
            LOG.error("FullText mapping failed for Vertex[ guid = {} ]", entityId, e);
        }

        RequestContext.get().endMetricRecord(metric);
    }

    private void doFullTextMapping(String guid) {
        AtlasEntityHeader entityHeader = new AtlasEntityHeader();
        entityHeader.setGuid(guid);

        doFullTextMapping(Collections.singletonList(entityHeader));
    }

    private void pruneResponse(EntityMutationResponse resp) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> pruneResponse()");
        }

        List<AtlasEntityHeader> createdEntities        = resp.getCreatedEntities();
        List<AtlasEntityHeader> updatedEntities        = resp.getUpdatedEntities();
        List<AtlasEntityHeader> partialUpdatedEntities = resp.getPartialUpdatedEntities();
        List<AtlasEntityHeader> deletedEntities        = resp.getDeletedEntities();

        // remove entities with DELETED status from created & updated lists
        purgeDeletedEntities(createdEntities);
        purgeDeletedEntities(updatedEntities);
        purgeDeletedEntities(partialUpdatedEntities);

        // remove entities deleted in this mutation from created & updated lists
        if (deletedEntities != null) {
            for (AtlasEntityHeader entity : deletedEntities) {
                purgeEntity(entity.getGuid(), createdEntities);
                purgeEntity(entity.getGuid(), updatedEntities);
                purgeEntity(entity.getGuid(), partialUpdatedEntities);
            }
        }

        // remove entities created in this mutation from updated lists
        if (createdEntities != null) {
            for (AtlasEntityHeader entity : createdEntities) {
                purgeEntity(entity.getGuid(),updatedEntities);
                purgeEntity(entity.getGuid(), partialUpdatedEntities);
            }
        }

        // remove entities updated in this mutation from partial-updated list
        if (updatedEntities != null) {
            for (AtlasEntityHeader entity : updatedEntities) {
                purgeEntity(entity.getGuid(), partialUpdatedEntities);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== pruneResponse()");
        }
    }

    private void purgeDeletedEntities(List<AtlasEntityHeader> entities) {
        if (entities != null) {
            for (ListIterator<AtlasEntityHeader> iter = entities.listIterator(); iter.hasNext(); ) {
                AtlasEntityHeader entity = iter.next();

                if (entity.getStatus() == AtlasEntity.Status.DELETED) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("purgeDeletedEntities(guid={}, status={}): REMOVED", entity.getGuid(), entity.getStatus());
                    }

                    iter.remove();
                }
            }
        }
    }

    private void purgeEntity(String guid, List<AtlasEntityHeader> entities) {
        if (guid != null && entities != null) {
            for (ListIterator<AtlasEntityHeader> iter = entities.listIterator(); iter.hasNext(); ) {
                AtlasEntityHeader entity = iter.next();

                if (org.apache.commons.lang.StringUtils.equals(guid, entity.getGuid())) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("purgeEntity(guid={}): REMOVED", entity.getGuid());
                    }

                    iter.remove();
                }
            }
        }
    }

}
