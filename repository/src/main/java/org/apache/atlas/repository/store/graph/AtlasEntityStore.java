/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.graph;

import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.instance.AtlasCheckStateRequest;
import org.apache.atlas.model.instance.AtlasCheckStateResult;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntitiesWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasEntityHeaders;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.repository.store.graph.v2.EntityStream;
import org.apache.atlas.type.AtlasEntityType;

import java.util.List;
import java.util.Map;

/**
 * Persistence/Retrieval API for AtlasEntity
 */
public interface AtlasEntityStore {

    /**
     * List all the entity guids for a given typename
     * @param typename
     * @return
     * @throws AtlasBaseException
     */
    List<String> getEntityGUIDS(String typename) throws AtlasBaseException;

    /**
     *
     * Get entity definition by its guid
     * @param guid
     * @return AtlasEntity
     */
    AtlasEntityWithExtInfo getById(String guid) throws AtlasBaseException;

    /**
     *
     * Get entity definition by its guid
     * @param guid
     * @param isMinExtInfo
     * @return AtlasEntity
     */
    AtlasEntityWithExtInfo getById(String guid, boolean isMinExtInfo, boolean ignoreRelationships) throws AtlasBaseException;

    /**
     * Get entity header for the given GUID
     * @param guid
     * @return
     * @throws AtlasBaseException
     */
    AtlasEntityHeader getHeaderById(String guid) throws AtlasBaseException;

    /**
     * Batch GET to retrieve entities by their ID
     * @param guid
     * @return
     * @throws AtlasBaseException
     */
    AtlasEntitiesWithExtInfo getByIds(List<String> guid) throws AtlasBaseException;

    /**
     * Batch GET to retrieve entities by their ID
     * @param guid
     * @param isMinExtInfo
     * @return
     * @throws AtlasBaseException
     */
    AtlasEntitiesWithExtInfo getByIds(List<String> guid, boolean isMinExtInfo, boolean ignoreRelationships) throws AtlasBaseException;

    /**
     * Batch GET to retrieve entities by their uniqueIds
     * @param entityType
     * @param uniqueAttributes
     * @param isMinExtInfo
     * @param ignoreRelationships
     * @return
     * @throws AtlasBaseException
     */
    AtlasEntitiesWithExtInfo getEntitiesByUniqueAttributes(AtlasEntityType entityType, List<Map<String, Object>> uniqueAttributes, boolean isMinExtInfo, boolean ignoreRelationships) throws AtlasBaseException;

    /**
     *
     * Get an eneity by its unique attribute
     * @param entityType     type of the entity
     * @param uniqAttributes Attributes that uniquely identify the entity
     * @return EntityMutationResponse details of the updates performed by this call
     */
    AtlasEntityWithExtInfo getByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes)
            throws AtlasBaseException;

    /**
     *
     * Get an eneity by its unique attribute
     * @param entityType     type of the entity
     * @param uniqAttributes Attributes that uniquely identify the entity
     * @param isMinExtInfo
     * @param ignoreRelationships ignore relationship attributes
     * @return EntityMutationResponse details of the updates performed by this call
     */
    AtlasEntityWithExtInfo getByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes, boolean isMinExtInfo, boolean ignoreRelationships)
            throws AtlasBaseException;

    /**
     * Check state of entities in the store
     * @param request AtlasCheckStateRequest
     * @return AtlasCheckStateResult
     * @throws AtlasBaseException
     */
    AtlasCheckStateResult checkState(AtlasCheckStateRequest request) throws AtlasBaseException;

    /**
     * Create or update  entities in the stream
     * @param entityStream AtlasEntityStream
     * @return EntityMutationResponse Entity mutations operations with the corresponding set of entities on which these operations were performed
     * @throws AtlasBaseException
     */
    EntityMutationResponse createOrUpdate(EntityStream entityStream, boolean isPartialUpdate) throws AtlasBaseException;

    /**
     * Create or update  entities with parameters necessary for import process
     * @param entityStream AtlasEntityStream
     * @return EntityMutationResponse Entity mutations operations with the corresponding set of entities on which these operations were performed
     * @throws AtlasBaseException
     */
    EntityMutationResponse createOrUpdateForImport(EntityStream entityStream) throws AtlasBaseException;

    /**
     * Update a single entity
     * @param objectId     ID of the entity
     * @param updatedEntityInfo updated entity information
     * @return EntityMutationResponse details of the updates performed by this call
     * @throws AtlasBaseException
     *
     */
    EntityMutationResponse updateEntity(AtlasObjectId objectId, AtlasEntityWithExtInfo updatedEntityInfo, boolean isPartialUpdate) throws AtlasBaseException;

    /**
     * Update a single entity
     * @param entityType     type of the entity
     * @param uniqAttributes Attributes that uniquely identify the entity
     * @return EntityMutationResponse details of the updates performed by this call
     * @throws AtlasBaseException
     *
     */
    EntityMutationResponse updateByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes,
                                                    AtlasEntityWithExtInfo entity) throws AtlasBaseException;

    /**
     * Partial update entities attribute using its guid.
     * @param guid Entity guid
     * @param attrName attribute name to be updated
     * @param attrValue updated attribute value
     * @return EntityMutationResponse details of the updates performed by this call
     * @throws AtlasBaseException
     */
    EntityMutationResponse updateEntityAttributeByGuid(String guid, String attrName, Object attrValue) throws AtlasBaseException;

    /**
     * Delete an entity by its guid
     * @param guid
     * @return
     */
    EntityMutationResponse deleteById(String guid) throws AtlasBaseException;

    /**
     * Deletes an entity using its type and unique attributes
     * @param entityType      type of the entity
     * @param uniqAttributes Attributes that uniquely identify the entity
     * @return EntityMutationResponse details of the updates performed by this call
     * @throws AtlasBaseException
     */
    EntityMutationResponse deleteByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes)
            throws AtlasBaseException;
    /**
     *
     * Get an entity guid by its unique attributes
     * @param entityType     type of the entity
     * @param uniqAttributes Attributes that uniquely identify the entity
     * @return String entity guid
     * @throws AtlasBaseException
     */

    String getGuidByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes) throws AtlasBaseException;
    /*
     * Return list of deleted entity guids
     */
    EntityMutationResponse deleteByIds(List<String> guid) throws AtlasBaseException;

    /**
     * Add classification(s)
     */
    void addClassifications(String guid, List<AtlasClassification> classification) throws AtlasBaseException;

    /**
     * Update classification(s)
     */
    void updateClassifications(String guid, List<AtlasClassification> classifications) throws AtlasBaseException;

    void addClassification(List<String> guids, AtlasClassification classification) throws AtlasBaseException;

    /**
     * Delete classification
     */
    void deleteClassification(String guid, String classificationName) throws AtlasBaseException;

    void deleteClassification(String guid, String classificationName, String associatedEntityGuid) throws AtlasBaseException;

    List<AtlasClassification> getClassifications(String guid) throws AtlasBaseException;

    AtlasClassification getClassification(String guid, String classificationName) throws AtlasBaseException;

    String setClassifications(AtlasEntityHeaders entityHeaders);
}
