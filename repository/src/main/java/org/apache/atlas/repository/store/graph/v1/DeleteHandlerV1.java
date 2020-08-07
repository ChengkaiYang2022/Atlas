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
package org.apache.atlas.repository.store.graph.v1;


import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.AtlasException;
import org.apache.atlas.RequestContext;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TypeCategory;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.Status;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.typedef.AtlasRelationshipDef.PropagateTags;
import org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef;
import org.apache.atlas.repository.graph.AtlasEdgeLabel;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2;
import org.apache.atlas.repository.store.graph.v2.EntityGraphRetriever;
import org.apache.atlas.type.AtlasArrayType;
import org.apache.atlas.type.AtlasClassificationType;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasMapType;
import org.apache.atlas.type.AtlasStructType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.utils.AtlasEntityUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.apache.atlas.model.TypeCategory.*;
import static org.apache.atlas.model.instance.AtlasEntity.Status.ACTIVE;
import static org.apache.atlas.model.instance.AtlasEntity.Status.DELETED;
import static org.apache.atlas.model.typedef.AtlasRelationshipDef.PropagateTags.ONE_TO_TWO;
import static org.apache.atlas.repository.Constants.CLASSIFICATION_EDGE_NAME_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.CLASSIFICATION_ENTITY_STATUS;
import static org.apache.atlas.repository.Constants.CLASSIFICATION_LABEL;
import static org.apache.atlas.repository.Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.MODIFIED_BY_KEY;
import static org.apache.atlas.repository.Constants.PROPAGATED_TRAIT_NAMES_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.RELATIONSHIP_GUID_PROPERTY_KEY;
import static org.apache.atlas.repository.graph.GraphHelper.*;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.getIdFromEdge;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.getQualifiedAttributePropertyKey;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.getState;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.isReference;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection.OUT;

public abstract class DeleteHandlerV1 {
    public static final Logger LOG = LoggerFactory.getLogger(DeleteHandlerV1.class);
    protected static final GraphHelper graphHelper = GraphHelper.getInstance();
    private final AtlasTypeRegistry    typeRegistry;
    private final EntityGraphRetriever entityRetriever;
    private final boolean              shouldUpdateInverseReferences;
    private final boolean              softDelete;

    public DeleteHandlerV1(AtlasTypeRegistry typeRegistry, boolean shouldUpdateInverseReference, boolean softDelete) {
        this.typeRegistry                  = typeRegistry;
        this.entityRetriever               = new EntityGraphRetriever(typeRegistry);
        this.shouldUpdateInverseReferences = shouldUpdateInverseReference;
        this.softDelete                    = softDelete;
    }

    /**
     * Deletes the specified entity vertices.
     * Deletes any traits, composite entities, and structs owned by each entity.
     * Also deletes all the references from/to the entity.
     *
     * @param instanceVertices
     * @throws AtlasException
     */
    public void deleteEntities(Collection<AtlasVertex> instanceVertices) throws AtlasBaseException {
        RequestContext   requestContext            = RequestContext.get();
        Set<AtlasVertex> deletionCandidateVertices = new HashSet<>();

        for (AtlasVertex instanceVertex : instanceVertices) {
            String              guid = AtlasGraphUtilsV2.getIdFromVertex(instanceVertex);
            AtlasEntity.Status state = getState(instanceVertex);

            if (state == DELETED || requestContext.isDeletedEntity(guid)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Skipping deletion of {} as it is already deleted", guid);
                }

                continue;
            }

            // Record all deletion candidate entities in RequestContext
            // and gather deletion candidate vertices.
            for (GraphHelper.VertexInfo vertexInfo : getOwnedVertices(instanceVertex)) {
                requestContext.recordEntityDelete(vertexInfo.getEntity());
                deletionCandidateVertices.add(vertexInfo.getVertex());
            }
        }

        // Delete traits and vertices.
        for (AtlasVertex deletionCandidateVertex : deletionCandidateVertices) {
            deleteAllClassifications(deletionCandidateVertex);
            deleteTypeVertex(deletionCandidateVertex, isInternalType(deletionCandidateVertex));
        }
    }

    /**
     * Delete the specified relationship edge.
     *
     * @param edge
     * @throws AtlasBaseException
     */
    public void deleteRelationship(AtlasEdge edge) throws AtlasBaseException {
        deleteRelationships(Collections.singleton(edge), false);
    }

    /**
     * Deletes the specified relationship edges.
     *
     * @param edges
     * @param forceDelete
     * @throws AtlasBaseException
     */
    public void deleteRelationships(Collection<AtlasEdge> edges, final boolean forceDelete) throws AtlasBaseException {
        for (AtlasEdge edge : edges) {
            boolean isInternal = isInternalType(edge.getInVertex()) && isInternalType(edge.getOutVertex());

            if (!isInternal && getState(edge) == DELETED) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Skipping deletion of {} as it is already deleted", getIdFromEdge(edge));
                }

                continue;
            }

            // re-evaluate tag propagation
            removeTagPropagation(edge);

            deleteEdge(edge, isInternal || forceDelete);
        }
    }

    /**
     * Get the GUIDs and vertices for all composite entities owned/contained by the specified root entity AtlasVertex.
     * The graph is traversed from the root entity through to the leaf nodes of the containment graph.
     *
     * @param entityVertex the root entity vertex
     * @return set of VertexInfo for all composite entities
     * @throws AtlasException
     */
    public Collection<GraphHelper.VertexInfo> getOwnedVertices(AtlasVertex entityVertex) throws AtlasBaseException {
        Map<String, GraphHelper.VertexInfo> vertexInfoMap = new HashMap<>();
        Stack<AtlasVertex>                  vertices      = new Stack<>();

        vertices.push(entityVertex);

        while (vertices.size() > 0) {
            AtlasVertex        vertex = vertices.pop();
            AtlasEntity.Status state  = getState(vertex);

            if (state == DELETED) {
                //If the reference vertex is marked for deletion, skip it
                continue;
            }

            String guid = GraphHelper.getGuid(vertex);

            if (vertexInfoMap.containsKey(guid)) {
                continue;
            }

            AtlasEntityHeader entity     = entityRetriever.toAtlasEntityHeader(vertex);
            String            typeName   = entity.getTypeName();
            AtlasEntityType   entityType = typeRegistry.getEntityTypeByName(typeName);

            if (entityType == null) {
                throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, TypeCategory.ENTITY.name(), typeName);
            }

            vertexInfoMap.put(guid, new GraphHelper.VertexInfo(entity, vertex));

            for (AtlasStructType.AtlasAttribute attributeInfo : entityType.getOwnedRefAttributes()) {
                String       edgeLabel    = attributeInfo.getRelationshipEdgeLabel();
                AtlasType    attrType     = attributeInfo.getAttributeType();
                TypeCategory typeCategory = attrType.getTypeCategory();

                if (typeCategory == OBJECT_ID_TYPE) {
                    if (attributeInfo.getAttributeDef().isSoftReferenced()) {
                        String        softRefVal = vertex.getProperty(attributeInfo.getVertexPropertyName(), String.class);
                        AtlasObjectId refObjId   = AtlasEntityUtil.parseSoftRefValue(softRefVal);
                        AtlasVertex   refVertex  = refObjId != null ? AtlasGraphUtilsV2.findByGuid(refObjId.getGuid()) : null;

                        if (refVertex != null) {
                            vertices.push(refVertex);
                        }
                    } else {
                        AtlasEdge edge = graphHelper.getEdgeForLabel(vertex, edgeLabel);

                        if (edge == null || getState(edge) == DELETED) {
                            continue;
                        }

                        vertices.push(edge.getInVertex());
                    }
                } else if (typeCategory == ARRAY || typeCategory == MAP) {
                    TypeCategory elementType = null;

                    if (typeCategory == ARRAY) {
                        elementType = ((AtlasArrayType) attrType).getElementType().getTypeCategory();
                    } else if (typeCategory == MAP) {
                        elementType = ((AtlasMapType) attrType).getValueType().getTypeCategory();
                    }

                    if (elementType != OBJECT_ID_TYPE) {
                        continue;
                    }

                    if (attributeInfo.getAttributeDef().isSoftReferenced()) {
                        if (typeCategory == ARRAY) {
                            List                softRefVal = vertex.getListProperty(attributeInfo.getVertexPropertyName(), List.class);
                            List<AtlasObjectId> refObjIds  = AtlasEntityUtil.parseSoftRefValue(softRefVal);

                            if (CollectionUtils.isNotEmpty(refObjIds)) {
                                for (AtlasObjectId refObjId : refObjIds) {
                                    AtlasVertex refVertex = AtlasGraphUtilsV2.findByGuid(refObjId.getGuid());

                                    if (refVertex != null) {
                                        vertices.push(refVertex);
                                    }
                                }
                            }
                        } else if (typeCategory == MAP) {
                            Map                        softRefVal = vertex.getProperty(attributeInfo.getVertexPropertyName(), Map.class);
                            Map<String, AtlasObjectId> refObjIds  = AtlasEntityUtil.parseSoftRefValue(softRefVal);

                            if (MapUtils.isNotEmpty(refObjIds)) {
                                for (AtlasObjectId refObjId : refObjIds.values()) {
                                    AtlasVertex refVertex = AtlasGraphUtilsV2.findByGuid(refObjId.getGuid());

                                    if (refVertex != null) {
                                        vertices.push(refVertex);
                                    }
                                }
                            }
                        }

                    } else {
                        List<AtlasEdge> edges = getCollectionElementsUsingRelationship(vertex, attributeInfo);

                        if (CollectionUtils.isNotEmpty(edges)) {
                            for (AtlasEdge edge : edges) {
                                if (edge == null || getState(edge) == DELETED) {
                                    continue;
                                }

                                vertices.push(edge.getInVertex());
                            }
                        }
                    }
                }
            }
        }

        return vertexInfoMap.values();
    }

    /**
     * Force delete is used to remove struct/trait in case of entity updates
     * @param edge
     * @param typeCategory
     * @param isOwned
     * @param forceDeleteStructTrait
     * @return returns true if the edge reference is hard deleted
     * @throws AtlasException
     */
    public boolean deleteEdgeReference(AtlasEdge edge, TypeCategory typeCategory, boolean isOwned,
                                       boolean forceDeleteStructTrait, AtlasVertex vertex) throws AtlasBaseException {
        // default edge direction is outward
        return deleteEdgeReference(edge, typeCategory, isOwned, forceDeleteStructTrait, OUT, vertex);
    }

    public boolean deleteEdgeReference(AtlasEdge edge, TypeCategory typeCategory, boolean isOwned, boolean forceDeleteStructTrait,
                                       AtlasRelationshipEdgeDirection relationshipDirection, AtlasVertex entityVertex) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting {}, force = {}", string(edge), forceDeleteStructTrait);
        }

        boolean isInternalType = isInternalType(entityVertex);
        boolean forceDelete    = (typeCategory == STRUCT || typeCategory == CLASSIFICATION) && (forceDeleteStructTrait || isInternalType);

        if (LOG.isDebugEnabled()) {
            LOG.debug("isInternal = {}, forceDelete = {}", isInternalType, forceDelete);
        }

        if (typeCategory == STRUCT || typeCategory == CLASSIFICATION || (typeCategory == OBJECT_ID_TYPE && isOwned)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Processing delete for typeCategory={}, isOwned={}", typeCategory, isOwned);
            }
            //If the vertex is of type struct delete the edge and then the reference vertex as the vertex is not shared by any other entities.
            //If the vertex is of type classification, delete the edge and then the reference vertex only if the vertex is not shared by any other propagated entities.
            //If the vertex is of type class, and its composite attribute, this reference vertex' lifecycle is controlled
            //through this delete, hence delete the edge and the reference vertex.
            AtlasVertex vertexForDelete = edge.getInVertex();

            //If deleting the edge and then the in vertex, reverse attribute shouldn't be updated
            deleteEdge(edge, false, forceDelete);
            deleteTypeVertex(vertexForDelete, typeCategory, forceDelete);
        } else {
            //If the vertex is of type class, and its not a composite attributes, the reference AtlasVertex' lifecycle is not controlled
            //through this delete. Hence just remove the reference edge. Leave the reference AtlasVertex as is

            // for relationship edges, inverse vertex's relationship attribute doesn't need to be updated.
            // only delete the reference relationship edge
            if (GraphHelper.isRelationshipEdge(edge)) {
                deleteEdge(edge, isInternalType);

                AtlasVertex referencedVertex = entityRetriever.getReferencedEntityVertex(edge, relationshipDirection, entityVertex);

                if (referencedVertex != null) {
                    RequestContext requestContext = RequestContext.get();

                    if (!requestContext.isUpdatedEntity(GraphHelper.getGuid(referencedVertex))) {
                        AtlasGraphUtilsV2.setEncodedProperty(referencedVertex, MODIFICATION_TIMESTAMP_PROPERTY_KEY, requestContext.getRequestTime());
                        AtlasGraphUtilsV2.setEncodedProperty(referencedVertex, MODIFIED_BY_KEY, requestContext.getUser());

                        requestContext.recordEntityUpdate(entityRetriever.toAtlasEntityHeader(referencedVertex));
                    }
                }
            } else {
                //legacy case - not a relationship edge
                //If deleting just the edge, reverse attribute should be updated for any references
                //For example, for the department type system, if the person's manager edge is deleted, subordinates of manager should be updated
                deleteEdge(edge, true, isInternalType);
            }
        }

        return !softDelete || forceDelete;
    }

    public void addTagPropagation(AtlasEdge edge, PropagateTags propagateTags) throws AtlasBaseException {
        if (edge == null) {
            return;
        }

        AtlasVertex outVertex = edge.getOutVertex();
        AtlasVertex inVertex  = edge.getInVertex();

        if (propagateTags == ONE_TO_TWO || propagateTags == PropagateTags.BOTH) {
            addTagPropagation(outVertex, inVertex, edge);
        }

        if (propagateTags == PropagateTags.TWO_TO_ONE || propagateTags == PropagateTags.BOTH) {
            addTagPropagation(inVertex, outVertex, edge);
        }
    }

    private void addTagPropagation(AtlasVertex fromVertex, AtlasVertex toVertex, AtlasEdge edge) throws AtlasBaseException {
        final List<AtlasVertex> classificationVertices   = getPropagationEnabledClassificationVertices(fromVertex);
        final List<AtlasVertex> propagatedEntityVertices = CollectionUtils.isNotEmpty(classificationVertices) ? graphHelper.getIncludedImpactedVerticesWithReferences(toVertex, getRelationshipGuid(edge)) : null;

        if (CollectionUtils.isNotEmpty(propagatedEntityVertices)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Propagate {} tags: from {} entity to {} entities", classificationVertices.size(), getTypeName(fromVertex), propagatedEntityVertices.size());
            }

            for (AtlasVertex classificationVertex : classificationVertices) {
                addTagPropagation(classificationVertex, propagatedEntityVertices);
            }
        }
    }

    public List<AtlasVertex> addTagPropagation(AtlasVertex classificationVertex, List<AtlasVertex> propagatedEntityVertices) throws AtlasBaseException {
        List<AtlasVertex> ret = null;

        if (CollectionUtils.isNotEmpty(propagatedEntityVertices) && classificationVertex != null) {
            String                  classificationName     = getTypeName(classificationVertex);
            AtlasClassificationType classificationType     = typeRegistry.getClassificationTypeByName(classificationName);
            AtlasVertex             associatedEntityVertex = getAssociatedEntityVertex(classificationVertex);

            for (AtlasVertex propagatedEntityVertex : propagatedEntityVertices) {
                if (getClassificationEdge(propagatedEntityVertex, classificationVertex) != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(" --> Classification edge already exists from [{}] --> [{}][{}] using edge label: [{}]",
                                  getTypeName(propagatedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex), classificationName);
                    }

                    continue;
                } else if (getPropagatedClassificationEdge(propagatedEntityVertex, classificationVertex) != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(" --> Propagated classification edge already exists from [{}] --> [{}][{}] using edge label: [{}]",
                                  getTypeName(propagatedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex), CLASSIFICATION_LABEL);
                    }

                    continue;
                }

                String          entityTypeName = getTypeName(propagatedEntityVertex);
                AtlasEntityType entityType     = typeRegistry.getEntityTypeByName(entityTypeName);
                String          entityGuid     = getGuid(propagatedEntityVertex);

                if (!classificationType.canApplyToEntityType(entityType)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(" --> Not creating propagated classification edge from [{}] --> [{}][{}], classification is not applicable for entity type",
                                   getTypeName(propagatedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex));
                    }

                    continue;
                }

                AtlasEdge existingEdge = getPropagatedClassificationEdge(propagatedEntityVertex, classificationVertex);

                if (existingEdge != null) {
                    continue;
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug(" --> Adding propagated classification: [{}] to {} ({}) using edge label: [{}]", classificationName, getTypeName(propagatedEntityVertex),
                              GraphHelper.getGuid(propagatedEntityVertex), CLASSIFICATION_LABEL);
                }

                if (ret == null) {
                    ret = new ArrayList<>();
                }

                ret.add(propagatedEntityVertex);

                graphHelper.addClassificationEdge(propagatedEntityVertex, classificationVertex, true);

                addToPropagatedTraitNames(propagatedEntityVertex, classificationName);

                // record add propagation details to send notifications at the end
                RequestContext      context        = RequestContext.get();
                AtlasClassification classification = entityRetriever.toAtlasClassification(classificationVertex);

                context.recordAddedPropagation(entityGuid, classification);
            }
        }

        return ret;
    }

    public void removeTagPropagation(AtlasEdge edge) throws AtlasBaseException {
        if (edge == null || !isRelationshipEdge(edge)) {
            return;
        }

        List<AtlasVertex>                   currentClassificationVertices = getClassificationVertices(edge);
        Map<AtlasVertex, List<AtlasVertex>> currentClassificationsMap     = graphHelper.getClassificationPropagatedEntitiesMapping(currentClassificationVertices);
        Map<AtlasVertex, List<AtlasVertex>> updatedClassificationsMap     = graphHelper.getClassificationPropagatedEntitiesMapping(currentClassificationVertices, getRelationshipGuid(edge));
        Map<AtlasVertex, List<AtlasVertex>> removePropagationsMap         = new HashMap<>();

        if (MapUtils.isNotEmpty(currentClassificationsMap) && MapUtils.isEmpty(updatedClassificationsMap)) {
            removePropagationsMap.putAll(currentClassificationsMap);
        } else {
            for (AtlasVertex classificationVertex : updatedClassificationsMap.keySet()) {
                List<AtlasVertex> currentPropagatingEntities = currentClassificationsMap.containsKey(classificationVertex) ? currentClassificationsMap.get(classificationVertex) : Collections.emptyList();
                List<AtlasVertex> updatedPropagatingEntities = updatedClassificationsMap.containsKey(classificationVertex) ? updatedClassificationsMap.get(classificationVertex) : Collections.emptyList();
                List<AtlasVertex> entitiesRemoved            = (List<AtlasVertex>) CollectionUtils.subtract(currentPropagatingEntities, updatedPropagatingEntities);

                if (CollectionUtils.isNotEmpty(entitiesRemoved)) {
                    removePropagationsMap.put(classificationVertex, entitiesRemoved);
                }
            }
        }

        boolean isTermEntityEdge = isTermEntityEdge(edge);

        for (AtlasVertex classificationVertex : removePropagationsMap.keySet()) {
            boolean removePropagations = getRemovePropagations(classificationVertex);

            if (isTermEntityEdge || removePropagations) {
                removeTagPropagation(classificationVertex, removePropagationsMap.get(classificationVertex));
            }
        }
    }

    public boolean isRelationshipEdge(AtlasEdge edge) {
        boolean ret = false;

        if (edge != null) {
            String outVertexType = getTypeName(edge.getOutVertex());
            String inVertexType  = getTypeName(edge.getInVertex());

            ret = GraphHelper.isRelationshipEdge(edge) || edge.getPropertyKeys().contains(RELATIONSHIP_GUID_PROPERTY_KEY) ||
                  (typeRegistry.getEntityTypeByName(outVertexType) != null && typeRegistry.getEntityTypeByName(inVertexType) != null);
        }

        return ret;
    }

    public List<AtlasVertex> removeTagPropagation(AtlasVertex classificationVertex) throws AtlasBaseException {
        List<AtlasVertex> ret = new ArrayList<>();

        if (classificationVertex != null) {
            List<AtlasEdge> propagatedEdges = getPropagatedEdges(classificationVertex);

            if (CollectionUtils.isNotEmpty(propagatedEdges)) {
                AtlasClassification classification = entityRetriever.toAtlasClassification(classificationVertex);

                for (AtlasEdge propagatedEdge : propagatedEdges) {
                    AtlasVertex entityVertex = propagatedEdge.getOutVertex();

                    ret.add(entityVertex);

                    // record remove propagation details to send notifications at the end
                    RequestContext.get().recordRemovedPropagation(getGuid(entityVertex), classification);

                    deletePropagatedEdge(propagatedEdge);
                }
            }
        }

        return ret;
    }

    public void removeTagPropagation(AtlasVertex classificationVertex, List<AtlasVertex> entityVertices) throws AtlasBaseException {
        if (classificationVertex != null && CollectionUtils.isNotEmpty(entityVertices)) {
            String              classificationName = getClassificationName(classificationVertex);
            AtlasClassification classification     = entityRetriever.toAtlasClassification(classificationVertex);
            String              entityGuid         = getClassificationEntityGuid(classificationVertex);
            RequestContext      context            = RequestContext.get();

            for (AtlasVertex entityVertex : entityVertices) {
                AtlasEdge propagatedEdge = getPropagatedClassificationEdge(entityVertex, classificationName, entityGuid);

                if (propagatedEdge != null) {
                    deletePropagatedEdge(propagatedEdge);

                    // record remove propagation details to send notifications at the end
                    context.recordRemovedPropagation(getGuid(entityVertex), classification);
                }
            }
        }
    }

    public void removeTagPropagation(AtlasEdge edge, PropagateTags propagateTags) throws AtlasBaseException {
        if (edge == null) {
            return;
        }

        AtlasVertex outVertex = edge.getOutVertex();
        AtlasVertex inVertex  = edge.getInVertex();

        if (propagateTags == ONE_TO_TWO || propagateTags == PropagateTags.BOTH) {
            removeTagPropagation(outVertex, inVertex, edge);
        }

        if (propagateTags == PropagateTags.TWO_TO_ONE || propagateTags == PropagateTags.BOTH) {
            removeTagPropagation(inVertex, outVertex, edge);
        }
    }

    private void removeTagPropagation(AtlasVertex fromVertex, AtlasVertex toVertex, AtlasEdge edge) throws AtlasBaseException {
        final List<AtlasVertex> classificationVertices = getPropagationEnabledClassificationVertices(fromVertex);
        final List<AtlasVertex> impactedEntityVertices = CollectionUtils.isNotEmpty(classificationVertices) ? graphHelper.getIncludedImpactedVerticesWithReferences(toVertex, getRelationshipGuid(edge)) : null;

        if (CollectionUtils.isNotEmpty(impactedEntityVertices)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Removing {} propagated tags: for {} from {} entities", classificationVertices.size(), getTypeName(fromVertex), impactedEntityVertices.size());
            }

            for (AtlasVertex classificationVertex : classificationVertices) {
                String            classificationName     = getTypeName(classificationVertex);
                AtlasVertex       associatedEntityVertex = getAssociatedEntityVertex(classificationVertex);
                List<AtlasVertex> referrals              = graphHelper.getIncludedImpactedVerticesWithReferences(associatedEntityVertex, getRelationshipGuid(edge));

                for (AtlasVertex impactedEntityVertex : impactedEntityVertices) {
                    if (referrals.contains(impactedEntityVertex)) {
                        if (LOG.isDebugEnabled()) {
                            if (org.apache.commons.lang3.StringUtils.equals(getGuid(impactedEntityVertex), getGuid(associatedEntityVertex))) {
                                LOG.debug(" --> Not removing propagated classification edge from [{}] --> [{}][{}] with edge label: [{}], since [{}] is associated with [{}]",
                                        getTypeName(impactedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex), CLASSIFICATION_LABEL, classificationName, getTypeName(associatedEntityVertex));
                            } else {
                                LOG.debug(" --> Not removing propagated classification edge from [{}] --> [{}][{}] with edge label: [{}], since [{}] is propagated through other path",
                                        getTypeName(impactedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex), CLASSIFICATION_LABEL, classificationName);
                            }
                        }

                        continue;
                    }

                    // remove propagated classification edge and classificationName from propagatedTraitNames vertex property
                    AtlasEdge propagatedEdge = getPropagatedClassificationEdge(impactedEntityVertex, classificationVertex);

                    if (propagatedEdge != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(" --> Removing propagated classification edge from [{}] --> [{}][{}] with edge label: [{}]",
                                    getTypeName(impactedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex), CLASSIFICATION_LABEL);
                        }

                        graphHelper.removeEdge(propagatedEdge);

                        removeFromPropagatedTraitNames(impactedEntityVertex, classificationName);
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(" --> Not removing propagated classification edge from [{}] --> [{}][{}] using edge label: [{}], since edge doesn't exist",
                                    getTypeName(impactedEntityVertex), getTypeName(classificationVertex), getTypeName(associatedEntityVertex), CLASSIFICATION_LABEL);
                        }
                    }
                }
            }
        }
    }

    public void deletePropagatedClassification(AtlasVertex entityVertex, String classificationName, String associatedEntityGuid) throws AtlasBaseException {
        AtlasEdge propagatedEdge = getPropagatedClassificationEdge(entityVertex, classificationName, associatedEntityGuid);

        if (propagatedEdge == null) {
            throw new AtlasBaseException(AtlasErrorCode.PROPAGATED_CLASSIFICATION_NOT_ASSOCIATED_WITH_ENTITY, classificationName, associatedEntityGuid, getGuid(entityVertex));
        }

        AtlasVertex classificationVertex = propagatedEdge.getInVertex();

        // do not remove propagated classification with ACTIVE associated entity
        if (getClassificationEntityStatus(classificationVertex) == ACTIVE) {
            throw new AtlasBaseException(AtlasErrorCode.PROPAGATED_CLASSIFICATION_REMOVAL_NOT_SUPPORTED, classificationName, associatedEntityGuid);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Removing propagated classification: [{} - associatedEntityGuid: {}] from: [{}][{}] with edge label: [{}]",
                       classificationName, associatedEntityGuid, getTypeName(entityVertex), getGuid(entityVertex), CLASSIFICATION_LABEL);
        }

        AtlasClassification classification = entityRetriever.toAtlasClassification(classificationVertex);

        // delete classification edge
        deletePropagatedEdge(propagatedEdge);

        // delete classification vertex
        deleteClassificationVertex(classificationVertex, true);

        // record remove propagation details to send notifications at the end
        RequestContext.get().recordRemovedPropagation(getGuid(entityVertex), classification);
    }

    public void deletePropagatedEdge(AtlasEdge edge) throws AtlasBaseException {
        String      classificationName = AtlasGraphUtilsV2.getEncodedProperty(edge, CLASSIFICATION_EDGE_NAME_PROPERTY_KEY, String.class);
        AtlasVertex entityVertex       = edge.getOutVertex();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Removing propagated classification: [{}] from: [{}][{}] with edge label: [{}]", classificationName,
                       getTypeName(entityVertex), GraphHelper.getGuid(entityVertex), CLASSIFICATION_LABEL);
        }

        removeFromPropagatedTraitNames(entityVertex, classificationName);

        deleteEdge(edge, true);

        updateModificationMetadata(entityVertex);
    }

    public void deleteEdgeReference(AtlasVertex outVertex, String edgeLabel, TypeCategory typeCategory, boolean isOwned) throws AtlasBaseException {
        AtlasEdge edge = graphHelper.getEdgeForLabel(outVertex, edgeLabel);

        if (edge != null) {
            deleteEdgeReference(edge, typeCategory, isOwned, false, outVertex);
        }
    }

    protected void deleteEdge(AtlasEdge edge, boolean updateInverseAttribute, boolean force) throws AtlasBaseException {
        //update inverse attribute
        if (updateInverseAttribute) {
            AtlasEdgeLabel atlasEdgeLabel = new AtlasEdgeLabel(edge.getLabel());
            AtlasType      parentType     = typeRegistry.getType(atlasEdgeLabel.getTypeName());

            if (parentType instanceof AtlasEntityType) {
                AtlasEntityType                parentEntityType = (AtlasEntityType) parentType;
                AtlasStructType.AtlasAttribute attribute        = parentEntityType.getAttribute(atlasEdgeLabel.getAttributeName());

                if (attribute == null) {
                    attribute = parentEntityType.getRelationshipAttribute(atlasEdgeLabel.getAttributeName(), AtlasGraphUtilsV2.getTypeName(edge));
                }

                if (attribute != null && attribute.getInverseRefAttribute() != null) {
                    deleteEdgeBetweenVertices(edge.getInVertex(), edge.getOutVertex(), attribute.getInverseRefAttribute());
                }
            }
        }

        if (isClassificationEdge(edge)) {
            AtlasVertex classificationVertex = edge.getInVertex();

            AtlasGraphUtilsV2.setEncodedProperty(classificationVertex, CLASSIFICATION_ENTITY_STATUS, DELETED.name());
        }

        deleteEdge(edge, force);
    }

    protected void deleteTypeVertex(AtlasVertex instanceVertex, TypeCategory typeCategory, boolean force) throws AtlasBaseException {
        switch (typeCategory) {
            case STRUCT:
                deleteTypeVertex(instanceVertex, force);
            break;

            case CLASSIFICATION:
                deleteClassificationVertex(instanceVertex, force);
            break;

            case ENTITY:
            case OBJECT_ID_TYPE:
                deleteEntities(Collections.singletonList(instanceVertex));
            break;

            default:
                throw new IllegalStateException("Type category " + typeCategory + " not handled");
        }
    }

    /**
     * Deleting any type vertex. Goes over the complex attributes and removes the references
     * @param instanceVertex
     * @throws AtlasException
     */
    protected void deleteTypeVertex(AtlasVertex instanceVertex, boolean force) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting {}, force={}", string(instanceVertex), force);
        }

        String    typeName   = GraphHelper.getTypeName(instanceVertex);
        AtlasType parentType = typeRegistry.getType(typeName);

        if (parentType instanceof AtlasStructType) {
            AtlasStructType structType   = (AtlasStructType) parentType;
            boolean         isEntityType = (parentType instanceof AtlasEntityType);

            for (AtlasStructType.AtlasAttribute attributeInfo : structType.getAllAttributes().values()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Deleting attribute {} for {}", attributeInfo.getName(), string(instanceVertex));
                }

                boolean   isOwned   = isEntityType && attributeInfo.isOwnedRef();
                AtlasType attrType  = attributeInfo.getAttributeType();
                String    edgeLabel = attributeInfo.getRelationshipEdgeLabel();

                switch (attrType.getTypeCategory()) {
                    case OBJECT_ID_TYPE:
                        //If its class attribute, delete the reference
                        deleteEdgeReference(instanceVertex, edgeLabel, attrType.getTypeCategory(), isOwned);
                    break;

                    case STRUCT:
                        //If its struct attribute, delete the reference
                        deleteEdgeReference(instanceVertex, edgeLabel, attrType.getTypeCategory(), false);
                    break;

                    case ARRAY:
                        //For array attribute, if the element is struct/class, delete all the references
                        AtlasArrayType arrType  = (AtlasArrayType) attrType;
                        AtlasType      elemType = arrType.getElementType();

                        if (isReference(elemType.getTypeCategory())) {
                            List<AtlasEdge> edges = getCollectionElementsUsingRelationship(instanceVertex, attributeInfo);

                            if (CollectionUtils.isNotEmpty(edges)) {
                                for (AtlasEdge edge : edges) {
                                    deleteEdgeReference(edge, elemType.getTypeCategory(), isOwned, false, instanceVertex);
                                }
                            }
                        }
                    break;

                    case MAP:
                        //For map attribute, if the value type is struct/class, delete all the references
                        AtlasMapType mapType           = (AtlasMapType) attrType;
                        TypeCategory valueTypeCategory = mapType.getValueType().getTypeCategory();

                        if (isReference(valueTypeCategory)) {
                            List<AtlasEdge> edges = getMapValuesUsingRelationship(instanceVertex, attributeInfo);

                            for (AtlasEdge edge : edges) {
                                deleteEdgeReference(edge, valueTypeCategory, isOwned, false, instanceVertex);
                            }
                        }
                     break;

                    case PRIMITIVE:
                        if (attributeInfo.getVertexUniquePropertyName() != null) {
                            instanceVertex.removeProperty(attributeInfo.getVertexUniquePropertyName());
                        }
                    break;
                }
            }
        }

        deleteVertex(instanceVertex, force);
    }

    protected AtlasAttribute getAttributeForEdge(String edgeLabel) throws AtlasBaseException {
        AtlasEdgeLabel  atlasEdgeLabel   = new AtlasEdgeLabel(edgeLabel);
        AtlasType       parentType       = typeRegistry.getType(atlasEdgeLabel.getTypeName());
        AtlasStructType parentStructType = (AtlasStructType) parentType;

        return parentStructType.getAttribute(atlasEdgeLabel.getAttributeName());
    }

    protected abstract void _deleteVertex(AtlasVertex instanceVertex, boolean force);

    protected abstract void deleteEdge(AtlasEdge edge, boolean force) throws AtlasBaseException;

    /**
     * Deletes the edge between outvertex and inVertex. The edge is for attribute attributeName of outVertex
     * @param outVertex
     * @param inVertex
     * @param attribute
     * @throws AtlasException
     */
    protected void deleteEdgeBetweenVertices(AtlasVertex outVertex, AtlasVertex inVertex, AtlasAttribute attribute) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Removing edge from {} to {} with attribute name {}", string(outVertex), string(inVertex), attribute.getName());
        }

        final String typeName = GraphHelper.getTypeName(outVertex);
        final String outId    = GraphHelper.getGuid(outVertex);
        final Status state    = getState(outVertex);

        if (state == DELETED || (outId != null && RequestContext.get().isDeletedEntity(outId))) {
            //If the reference vertex is marked for deletion, skip updating the reference
            return;
        }

        AtlasStructType   parentType   = (AtlasStructType) typeRegistry.getType(typeName);
        String            propertyName = getQualifiedAttributePropertyKey(parentType, attribute.getName());
        String            edgeLabel    = attribute.getRelationshipEdgeLabel();
        AtlasEdge         edge         = null;
        AtlasAttributeDef attrDef      = attribute.getAttributeDef();
        AtlasType         attrType     = attribute.getAttributeType();

        switch (attrType.getTypeCategory()) {
            case OBJECT_ID_TYPE: {
                //If its class attribute, its the only edge between two vertices
                if (attrDef.getIsOptional()) {
                    edge = graphHelper.getEdgeForLabel(outVertex, edgeLabel);

                    if (shouldUpdateInverseReferences) {
                        AtlasGraphUtilsV2.setEncodedProperty(outVertex, propertyName, null);
                    }
                } else {
                    // Cannot unset a required attribute.
                    throw new AtlasBaseException("Cannot unset required attribute " + propertyName + " on " + GraphHelper.vertexString(outVertex) + " edge = " + edgeLabel);
                }
            }
            break;

            case ARRAY: {
                //If its array attribute, find the right edge between the two vertices and update array property
                List<AtlasEdge> elementEdges = getCollectionElementsUsingRelationship(outVertex, attribute);

                if (elementEdges != null) {
                    elementEdges = new ArrayList<>(elementEdges);

                    for (AtlasEdge elementEdge : elementEdges) {
                        if (elementEdge == null) {
                            continue;
                        }

                        AtlasVertex elementVertex = elementEdge.getInVertex();

                        if (elementVertex.equals(inVertex)) {
                            edge = elementEdge;

                            //TODO element.size includes deleted items as well. should exclude
                            if (!attrDef.getIsOptional() && elementEdges.size() <= attrDef.getValuesMinCount()) {
                                // Deleting this edge would violate the attribute's lower bound.
                                throw new AtlasBaseException("Cannot remove array element from required attribute " + propertyName + " on " + GraphHelper.getVertexDetails(outVertex) + " " + GraphHelper.getEdgeDetails(elementEdge));
                            }
                        }
                    }
                }
            }
            break;

            case MAP: {
                //If its map attribute, find the right edge between two vertices and update map property
                List<AtlasEdge> mapEdges = getMapValuesUsingRelationship(outVertex, attribute);

                if (mapEdges != null) {
                    mapEdges = new ArrayList<>(mapEdges);

                    for (AtlasEdge mapEdge : mapEdges) {
                        if (mapEdge != null) {
                            AtlasVertex mapVertex = mapEdge.getInVertex();

                            if (mapVertex.getId().toString().equals(inVertex.getId().toString())) {
                                //TODO keys.size includes deleted items as well. should exclude
                                if (attrDef.getIsOptional() || mapEdges.size() > attrDef.getValuesMinCount()) {
                                    edge = mapEdge;
                                } else {
                                    // Deleting this entry would violate the attribute's lower bound.
                                    throw new AtlasBaseException("Cannot remove map entry " + propertyName + " from required attribute " + propertyName + " on " + GraphHelper.getVertexDetails(outVertex) + " " + GraphHelper.getEdgeDetails(mapEdge));
                                }
                                break;
                            }
                        }
                    }
                }
            }
            break;

            case STRUCT:
            case CLASSIFICATION:
            break;

            default:
                throw new IllegalStateException("There can't be an edge from " + GraphHelper.getVertexDetails(outVertex) + " to " + GraphHelper.getVertexDetails(inVertex) + " with attribute name " + attribute.getName() + " which is not class/array/map attribute. found " + attrType.getTypeCategory().name());
        }

        if (edge != null) {
            deleteEdge(edge, isInternalType(inVertex) && isInternalType(outVertex));

            RequestContext requestContext = RequestContext.get();

            if (! requestContext.isUpdatedEntity(outId)) {
                AtlasGraphUtilsV2.setEncodedProperty(outVertex, MODIFICATION_TIMESTAMP_PROPERTY_KEY, requestContext.getRequestTime());
                AtlasGraphUtilsV2.setEncodedProperty(outVertex, MODIFIED_BY_KEY, requestContext.getUser());

                requestContext.recordEntityUpdate(entityRetriever.toAtlasEntityHeader(outVertex));
            }
        }
    }

    protected void deleteVertex(AtlasVertex instanceVertex, boolean force) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Setting the external references to {} to null(removing edges)", string(instanceVertex));
        }

        // Delete external references to this vertex - incoming edges from lineage or glossary term edges
        Iterable<AtlasEdge> incomingEdges = instanceVertex.getEdges(AtlasEdgeDirection.IN);

        for (AtlasEdge edge : incomingEdges) {
            Status edgeState = getState(edge);

            if (edgeState == ACTIVE) {
                if (isRelationshipEdge(edge)) {
                    deleteRelationship(edge);
                } else {
                    AtlasVertex    outVertex = edge.getOutVertex();
                    AtlasVertex    inVertex  = edge.getInVertex();
                    AtlasAttribute attribute = getAttributeForEdge(edge.getLabel());

                    deleteEdgeBetweenVertices(outVertex, inVertex, attribute);
                }
            }
        }

        _deleteVertex(instanceVertex, force);
    }

    public void deleteClassificationVertex(AtlasVertex classificationVertex, boolean force) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting classification vertex", string(classificationVertex));
        }

        List<AtlasEdge> incomingClassificationEdges = getIncomingClassificationEdges(classificationVertex);

        // delete classification vertex only if it has no more entity references (direct or propagated)
        if (CollectionUtils.isEmpty(incomingClassificationEdges)) {
            _deleteVertex(classificationVertex, force);
        }
    }

    private boolean isInternalType(final AtlasVertex instanceVertex) {
        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(GraphHelper.getTypeName(instanceVertex));
        return Objects.nonNull(entityType) && entityType.isInternalType();
    }

    private void removeFromPropagatedTraitNames(AtlasVertex entityVertex, String classificationName) {
        if (entityVertex != null && StringUtils.isNotEmpty(classificationName)) {
            List<String> propagatedTraitNames = getTraitNames(entityVertex, true);

            propagatedTraitNames.remove(classificationName);

            entityVertex.removeProperty(PROPAGATED_TRAIT_NAMES_PROPERTY_KEY);

            for (String propagatedTraitName : propagatedTraitNames) {
                addToPropagatedTraitNames(entityVertex, propagatedTraitName);
            }
        }
    }

    /**
     * Delete all associated classifications from the specified entity vertex.
     * @param instanceVertex
     * @throws AtlasException
     */
    private void deleteAllClassifications(AtlasVertex instanceVertex) throws AtlasBaseException {
        List<AtlasEdge> classificationEdges = getAllClassificationEdges(instanceVertex);

        for (AtlasEdge edge : classificationEdges) {
            AtlasVertex classificationVertex = edge.getInVertex();
            boolean     isClassificationEdge = isClassificationEdge(edge);
            boolean     removePropagations   = getRemovePropagations(classificationVertex);

            if (isClassificationEdge && removePropagations) {
                removeTagPropagation(classificationVertex);
            }

            deleteEdgeReference(edge, CLASSIFICATION, false, false, instanceVertex);
        }
    }
}
