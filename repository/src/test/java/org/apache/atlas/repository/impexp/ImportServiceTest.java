/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.impexp;

import com.google.inject.Inject;
import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.RequestContext;
import org.apache.atlas.TestModules;
import org.apache.atlas.TestUtilsV2;
import org.apache.atlas.discovery.EntityDiscoveryService;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.impexp.AtlasImportRequest;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasRelatedObjectId;
import org.apache.atlas.repository.graph.AtlasGraphProvider;
import org.apache.atlas.repository.store.graph.AtlasEntityStore;
import org.apache.atlas.runner.LocalSolrRunner;
import org.apache.atlas.store.AtlasTypeDefStore;
import org.apache.atlas.type.AtlasClassificationType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.commons.lang.StringUtils;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestContext;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.atlas.graph.GraphSandboxUtil.useLocalSolr;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.getDefaultImportRequest;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.getZipSource;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.loadModelFromJson;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.loadModelFromResourcesJson;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.runAndVerifyQuickStart_v1_Import;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.runImportWithNoParameters;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.runImportWithParameters;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Guice(modules = TestModules.TestOnlyModule.class)
public class ImportServiceTest extends ExportImportTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(ImportServiceTest.class);
    private static final int DEFAULT_LIMIT = 25;
    private final ImportService importService;

    @Inject
    AtlasTypeRegistry typeRegistry;

    @Inject
    private AtlasTypeDefStore typeDefStore;

    @Inject
    private EntityDiscoveryService discoveryService;

    @Inject
    AtlasEntityStore entityStore;

    @Inject
    private ExportImportAuditService auditService;

    @Inject
    public ImportServiceTest(ImportService importService) {
        this.importService = importService;
    }

    @BeforeTest
    public void setupTest() {
        RequestContext.clear();
        RequestContext.get().setUser(TestUtilsV2.TEST_USER, null);
    }

    @AfterClass
    public void clear() throws Exception {
        AtlasGraphProvider.cleanup();

        if (useLocalSolr()) {
            LocalSolrRunner.stop();
        }
    }

    @AfterTest
    public void postTest() throws InterruptedException {
        assertAuditEntry(auditService);
    }

    @DataProvider(name = "sales")
    public static Object[][] getDataFromQuickStart_v1_Sales(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("sales-v1-full.zip");
    }

    @Test(dataProvider = "sales")
    public void importDB1(ZipSource zipSource) throws AtlasBaseException, IOException {
        loadBaseModel();
        runAndVerifyQuickStart_v1_Import(importService, zipSource);

        assertEntityCount("DB_v1", "bfe88eb8-7556-403c-8210-647013f44a44", 1);

        AtlasEntity entity = assertEntity("Table_v1", "fe91bf93-eb0c-4638-8361-15937390c810");
        assertEquals(entity.getClassifications().size(), 1);
        assertFalse(entity.getClassifications().get(0).isPropagate(), "Default propagate should be false");
    }

    @DataProvider(name = "reporting")
    public static Object[][] getDataFromReporting() throws IOException, AtlasBaseException {
        return getZipSource("reporting-v1-full.zip");
    }

    @Test(dataProvider = "reporting")
    public void importDB2(ZipSource zipSource) throws AtlasBaseException, IOException {
        loadBaseModel();
        runAndVerifyQuickStart_v1_Import(importService, zipSource);
    }

    @DataProvider(name = "logging")
    public static Object[][] getDataFromLogging(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("logging-v1-full.zip");
    }

    @Test(dataProvider = "logging")
    public void importDB3(ZipSource zipSource) throws AtlasBaseException, IOException {
        loadBaseModel();
        runAndVerifyQuickStart_v1_Import(importService, zipSource);
    }

    @DataProvider(name = "salesNewTypeAttrs")
    public static Object[][] getDataFromSalesNewTypeAttrs(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("salesNewTypeAttrs.zip");
    }

    @Test(dataProvider = "salesNewTypeAttrs", dependsOnMethods = "importDB1")
    public void importDB4(ZipSource zipSource) throws AtlasBaseException, IOException {
        loadBaseModel();
        runImportWithParameters(importService, getDefaultImportRequest(), zipSource);
    }

    @DataProvider(name = "salesNewTypeAttrs-next")
    public static Object[][] getDataFromSalesNewTypeAttrsNext(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("salesNewTypeAttrs-next.zip");
    }

    @Test(dataProvider = "salesNewTypeAttrs-next", dependsOnMethods = "importDB4")
    public void importDB5(ZipSource zipSource) throws AtlasBaseException, IOException {
        final String newEnumDefName = "database_action";

        assertNotNull(typeDefStore.getEnumDefByName(newEnumDefName));

        AtlasImportRequest request = getDefaultImportRequest();
        Map<String, String> options = new HashMap<>();
        options.put("updateTypeDefinition", "false");
        request.setOptions(options);

        runImportWithParameters(importService, request, zipSource);
        assertNotNull(typeDefStore.getEnumDefByName(newEnumDefName));
        assertEquals(typeDefStore.getEnumDefByName(newEnumDefName).getElementDefs().size(), 4);
    }

    @Test(dataProvider = "salesNewTypeAttrs-next", dependsOnMethods = "importDB4")
    public void importDB6(ZipSource zipSource) throws AtlasBaseException, IOException {
        final String newEnumDefName = "database_action";

        assertNotNull(typeDefStore.getEnumDefByName(newEnumDefName));

        AtlasImportRequest request = getDefaultImportRequest();
        Map<String, String> options = new HashMap<>();
        options.put("updateTypeDefinition", "true");
        request.setOptions(options);

        runImportWithParameters(importService, request, zipSource);
        assertNotNull(typeDefStore.getEnumDefByName(newEnumDefName));
        assertEquals(typeDefStore.getEnumDefByName(newEnumDefName).getElementDefs().size(), 8);
    }

    @DataProvider(name = "ctas")
    public static Object[][] getDataFromCtas(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("ctas.zip");
    }

    @Test(dataProvider = "ctas")
    public void importCTAS(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadHiveModel();

        runImportWithNoParameters(importService, zipSource);
    }

    @DataProvider(name = "stocks-legacy")
    public static Object[][] getDataFromLegacyStocks(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("stocks.zip");
    }

    @Test(dataProvider = "stocks-legacy")
    public void importLegacy(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadFsModel();
        loadHiveModel();

        runImportWithNoParameters(importService, zipSource);
        List<AtlasEntityHeader> result = getImportedEntities("hive_db", "886c5e9c-3ac6-40be-8201-fb0cebb64783");
        assertEquals(result.size(), 1);

        AtlasEntity.AtlasEntityWithExtInfo entityWithExtInfo = getEntity(result.get(0));
        Map<String, Object> relationshipAttributes = entityWithExtInfo.getEntity().getRelationshipAttributes();
        assertNotNull(relationshipAttributes);
        assertNotNull(relationshipAttributes.get("tables"));

        List<AtlasRelatedObjectId> relatedList = (List<AtlasRelatedObjectId>) relationshipAttributes.get("tables");
        AtlasRelatedObjectId relatedObjectId = relatedList.get(0);
        assertNotNull(relatedObjectId.getRelationshipGuid());
    }

    @Test(dataProvider = "tag-prop-2")
    public void importTagProp2(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadFsModel();
        loadHiveModel();

        runImportWithNoParameters(importService, zipSource);
        assertEntityCount("hive_db", "7d7d5a18-d992-457e-83c0-e36f5b95ebdb", 1);
        assertEntityCount("hive_table", "dbe729bb-c614-4e23-b845-3258efdf7a58", 1);
        AtlasEntity entity = assertEntity("hive_table", "092e9888-de96-4908-8be3-925ee72e3395");
        assertEquals(entity.getClassifications().size(), 2);
        assertTrue(entity.getClassifications().get(0).isPropagate());
        assertFalse(entity.getClassifications().get(0).getEntityGuid().equalsIgnoreCase(entity.getGuid()));
        assertFalse(entity.getClassifications().get(1).getEntityGuid().equalsIgnoreCase(entity.getGuid()));
    }

    @DataProvider(name = "stocks-glossary")
    public static Object[][] getDataFromGlossary(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("stocks-glossary.zip");
    }

    @Test(dataProvider = "stocks-glossary")
    public void importGlossary(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadGlossary();
        runImportWithNoParameters(importService, zipSource);

        assertEntityCount("AtlasGlossary", "40c80052-3129-4f7c-8f2f-391677935416", 1);
        assertEntityCount("AtlasGlossaryTerm", "e93ac426-de04-4d54-a7c9-d76c1e96369b", 1);
        assertEntityCount("AtlasGlossaryTerm", "93ad3bf6-23dc-4e3f-b70e-f8fad6438203", 1);
        assertEntityCount("AtlasGlossaryTerm", "105533b6-c125-4a87-bed5-cdf67fb68c39", 1);
    }

    private List<AtlasEntityHeader> getImportedEntities(String query, String guid) throws AtlasBaseException {
        String q = StringUtils.isEmpty(guid) ? query : String.format("%s where __guid = '%s'", query, guid);
        return discoveryService.searchUsingDslQuery(q, DEFAULT_LIMIT, 0).getEntities();
    }

    @DataProvider(name = "hdfs_path1")
    public static Object[][] getDataFromHdfsPath1(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("hdfs_path1.zip");
    }

    @Test(dataProvider = "hdfs_path1", expectedExceptions = AtlasBaseException.class)
    public void importHdfs_path1(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadFsModel();
        loadModelFromResourcesJson("tag1.json", typeDefStore, typeRegistry);

        try {
            runImportWithNoParameters(importService, zipSource);
        } catch (AtlasBaseException e) {
            assertEquals(e.getAtlasErrorCode(), AtlasErrorCode.INVALID_IMPORT_ATTRIBUTE_TYPE_CHANGED);
            AtlasClassificationType tag1 = typeRegistry.getClassificationTypeByName("tag1");
            assertNotNull(tag1);
            assertEquals(tag1.getAllAttributes().size(), 2);
            throw e;
        }
    }

    @DataProvider(name = "relationshipLineage")
    public static Object[][] getImportWithRelationships(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("rel-lineage.zip");
    }

    @DataProvider(name = "tag-prop-2")
    public static Object[][] getImportWithTagProp2(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("tag-prop-2.zip");
    }

    @Test(dataProvider = "relationshipLineage")
    public void importDB8(ZipSource zipSource) throws AtlasBaseException, IOException {
        loadBaseModel();
        loadHiveModel();
        AtlasImportRequest request = getDefaultImportRequest();
        runImportWithParameters(importService, request, zipSource);
    }

    @DataProvider(name = "relationship")
    public static Object[][] getImportWithRelationshipsWithLineage(ITestContext context) throws IOException, AtlasBaseException {
        return getZipSource("stocks-rel-2.zip");
    }

    @Test(dataProvider = "relationship")
    public void importDB7(ZipSource zipSource) throws AtlasBaseException, IOException {
        loadBaseModel();
        loadHiveModel();
        AtlasImportRequest request = getDefaultImportRequest();
        runImportWithParameters(importService, request, zipSource);

        assertEntityCount("hive_db", "d7dc0848-fbba-4d63-9264-a460798361f5", 1);
        assertEntityCount("hive_table", "2fb31eaa-4bb2-4eb8-b333-a888ba7c84fe", 1);
        assertEntityCount("hive_column", "13422f0c-9265-4960-91a9-290ffd83b7f1",1);
        assertEntityCount("hive_column", "c1ae870f-ce0c-44ae-832f-ff77035b1f7e",1);
        assertEntityCount("hive_column", "b84baab3-0664-4f13-82f1-e81d043db02f",1);
        assertEntityCount("hive_column", "53ea1991-6ca8-44f2-a75e-61b8d4866fc8",1);
        assertEntityCount("hive_column", "a973c04c-aa42-49f4-877c-66fbe6754fb5",1);
        assertEntityCount("hive_column", "a4550803-f18e-4072-a1e8-1201e6022a58",1);
        assertEntityCount("hive_column", "6c4f196a-4046-493b-8c3a-2b1a9ef255a2",1);
    }

    private List<AtlasEntityHeader>  assertEntityCount(String entityType, String guid, int expectedCount) throws AtlasBaseException {
        List<AtlasEntityHeader> result = getImportedEntities(entityType, guid);
        assertEquals(result.size(), expectedCount);
        return result;
    }

    private AtlasEntity  assertEntity(String entityType, String guid) throws AtlasBaseException {
        List<AtlasEntityHeader> result = getImportedEntities(entityType, guid);
        int expectedCount = 1;
        assertEquals(result.size(), expectedCount);
        AtlasEntity.AtlasEntityWithExtInfo entityWithExtInfo = getEntity(result.get(0));
        assertNotNull(entityWithExtInfo);
        return entityWithExtInfo.getEntity();
    }

    @Test
    public void importServiceProcessesIOException() {
        ImportService importService = new ImportService(typeDefStore, typeRegistry, null, null,null);
        AtlasImportRequest req = mock(AtlasImportRequest.class);

        Answer<Map> answer = invocationOnMock -> {
            throw new IOException("file is read only");
        };

        when(req.getFileName()).thenReturn("some-file.zip");
        when(req.getOptions()).thenAnswer(answer);

        try {
            importService.run(req, "a", "b", "c");
        }
        catch (AtlasBaseException ex) {
            assertEquals(ex.getAtlasErrorCode().getErrorCode(), AtlasErrorCode.INVALID_PARAMETERS.getErrorCode());
        }
    }

    private void loadFsModel() throws IOException, AtlasBaseException {
        loadModelFromJson("1000-Hadoop/1020-fs_model.json", typeDefStore, typeRegistry);
    }

    private void loadHiveModel() throws IOException, AtlasBaseException {
        loadModelFromJson("1000-Hadoop/1030-hive_model.json", typeDefStore, typeRegistry);
    }

    private void loadBaseModel() throws IOException, AtlasBaseException {
        loadModelFromJson("0000-Area0/0010-base_model.json", typeDefStore, typeRegistry);
    }

    private void loadGlossary() throws IOException, AtlasBaseException {
        loadModelFromJson("0000-Area0/0011-glossary_model.json", typeDefStore, typeRegistry);
    }

    private AtlasEntity.AtlasEntityWithExtInfo getEntity(AtlasEntityHeader header) throws AtlasBaseException {
        return entityStore.getById(header.getGuid());
    }

    @Test(dataProvider = "salesNewTypeAttrs-next")
    public void transformUpdatesForSubTypes(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadHiveModel();

        String transformJSON = "{ \"Asset\": { \"qualifiedName\":[ \"lowercase\", \"replace:@cl1:@cl2\" ] } }";
        importService.setImportTransform(zipSource, transformJSON);
        ImportTransforms importTransforms = zipSource.getImportTransform();

        assertTrue(importTransforms.getTransforms().containsKey("Asset"));
        assertTrue(importTransforms.getTransforms().containsKey("hive_table"));
        assertTrue(importTransforms.getTransforms().containsKey("hive_column"));
    }

    @Test(dataProvider = "salesNewTypeAttrs-next")
    public void transformUpdatesForSubTypesAddsToExistingTransforms(ZipSource zipSource) throws IOException, AtlasBaseException {
        loadBaseModel();
        loadHiveModel();

        String transformJSON = "{ \"Asset\": { \"qualifiedName\":[ \"replace:@cl1:@cl2\" ] }, \"hive_table\": { \"qualifiedName\":[ \"lowercase\" ] } }";
        importService.setImportTransform(zipSource, transformJSON);
        ImportTransforms importTransforms = zipSource.getImportTransform();

        assertTrue(importTransforms.getTransforms().containsKey("Asset"));
        assertTrue(importTransforms.getTransforms().containsKey("hive_table"));
        assertTrue(importTransforms.getTransforms().containsKey("hive_column"));
        assertEquals(importTransforms.getTransforms().get("hive_table").get("qualifiedName").size(), 2);
    }

    @Test(dataProvider = "empty-zip", expectedExceptions = AtlasBaseException.class)
    public void importEmptyZip(ZipSource zipSource) {

    }

    @Test(expectedExceptions = AtlasBaseException.class)
    public void importEmptyZip() throws IOException, AtlasBaseException {
        getZipSource("empty.zip");
    }
}
