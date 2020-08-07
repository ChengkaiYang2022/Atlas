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
package org.apache.atlas.services;

import org.apache.atlas.RequestContext;
import org.apache.atlas.TestModules;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.metrics.AtlasMetrics;
import org.apache.atlas.repository.graph.AtlasGraphProvider;
import org.apache.atlas.repository.impexp.ImportService;
import org.apache.atlas.repository.impexp.ZipFileResourceTestUtils;
import org.apache.atlas.repository.impexp.ZipSource;
import org.apache.atlas.runner.LocalSolrRunner;
import org.apache.atlas.store.AtlasTypeDefStore;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.util.AtlasMetricsCounter;
import org.apache.atlas.util.AtlasMetricsUtil;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.apache.atlas.graph.GraphSandboxUtil.useLocalSolr;
import static org.apache.atlas.model.metrics.AtlasMetrics.*;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.loadModelFromJson;
import static org.apache.atlas.repository.impexp.ZipFileResourceTestUtils.runImportWithNoParameters;
import static org.apache.atlas.services.MetricsService.ENTITY;
import static org.apache.atlas.services.MetricsService.GENERAL;
import static org.apache.atlas.services.MetricsService.METRIC_ENTITIES_PER_TAG;
import static org.apache.atlas.services.MetricsService.METRIC_ENTITY_ACTIVE;
import static org.apache.atlas.services.MetricsService.METRIC_ENTITY_COUNT;
import static org.apache.atlas.services.MetricsService.METRIC_ENTITY_DELETED;
import static org.apache.atlas.services.MetricsService.METRIC_TAG_COUNT;
import static org.apache.atlas.services.MetricsService.METRIC_TYPE_COUNT;
import static org.apache.atlas.services.MetricsService.METRIC_TYPE_UNUSED_COUNT;
import static org.apache.atlas.services.MetricsService.TAG;
import static org.testng.Assert.*;

@Guice(modules = TestModules.TestOnlyModule.class)
public class MetricsServiceTest {

    public static final String IMPORT_FILE = "metrics-entities-data.zip";

    @Inject
    private AtlasTypeDefStore typeDefStore;

    @Inject
    private AtlasTypeRegistry typeRegistry;

    @Inject
    private ImportService importService;

    @Inject
    private MetricsService metricsService;

    @Inject
    private AtlasMetricsUtil metricsUtil;

    TestClock clock = new TestClock(Clock.systemUTC(), ZoneOffset.UTC);

    long msgOffset = 0;


    private final Map<String, Long> activeEntityMetricsExpected = new HashMap<String, Long>() {{
        put("hive_storagedesc", 5L);
        put("__ExportImportAuditEntry", 1L);
        put("AtlasServer", 1L);
        put("hive_column_lineage", 8L);
        put("hive_table", 5L);
        put("hive_column", 13L);
        put("hive_db", 2L);
        put("hive_process", 3L);
    }};

    private final Map<String, Long> deletedEntityMetricsExpected = new HashMap<String, Long>() {{
        put("hive_storagedesc", 1L);
        put("hive_table", 1L);
        put("hive_column", 2L);
        put("hive_db", 1L);
    }};


    private final Map<String, Long> tagMetricsExpected = new HashMap<String, Long>() {{
        put("PII", 1L);
    }};

    private final Map<String, Object> metricExpected = new HashMap<String, Object>() {{
        put(STAT_NOTIFY_COUNT_CURR_HOUR, 11L);
        put(STAT_NOTIFY_FAILED_COUNT_CURR_HOUR, 1L);
        put(STAT_NOTIFY_COUNT_PREV_HOUR, 11L);
        put(STAT_NOTIFY_FAILED_COUNT_PREV_HOUR, 1L);
        put(STAT_NOTIFY_COUNT_CURR_DAY, 33L);
        put(STAT_NOTIFY_FAILED_COUNT_CURR_DAY, 3L);
        put(STAT_NOTIFY_COUNT_PREV_DAY, 11L);
        put(STAT_NOTIFY_FAILED_COUNT_PREV_DAY, 1L);
    }};

    @BeforeClass
    public void setup() {
        RequestContext.clear();

        loadModelFilesAndImportTestData();

        // sleep for sometime for import to complete
        sleep();
    }

    private void sleep() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @AfterClass
    public void clear() throws Exception {
        AtlasGraphProvider.cleanup();

        if (useLocalSolr()) {
            LocalSolrRunner.stop();
        }
    }

    @Test
    public void testGetMetrics() {
        AtlasMetrics metrics = metricsService.getMetrics();

        assertNotNull(metrics);

        // general metrics
        assertEquals(metrics.getNumericMetric(GENERAL, METRIC_ENTITY_COUNT).intValue(), 43);
        assertEquals(metrics.getNumericMetric(GENERAL, METRIC_TAG_COUNT).intValue(), 1);
        assertTrue(metrics.getNumericMetric(GENERAL, METRIC_TYPE_UNUSED_COUNT).intValue() >= 10);
        assertTrue(metrics.getNumericMetric(GENERAL, METRIC_TYPE_COUNT).intValue() >= 44);

        // tag metrics
        Map tagMetricsActual           = (Map) metrics.getMetric(TAG, METRIC_ENTITIES_PER_TAG);
        Map activeEntityMetricsActual  = (Map) metrics.getMetric(ENTITY, METRIC_ENTITY_ACTIVE);
        Map deletedEntityMetricsActual = (Map) metrics.getMetric(ENTITY, METRIC_ENTITY_DELETED);

        assertEquals(tagMetricsActual.size(), 1);
        assertEquals(activeEntityMetricsActual.size(), 8);
        assertEquals(deletedEntityMetricsActual.size(), 4);

        assertEquals(tagMetricsActual, tagMetricsExpected);
        assertEquals(activeEntityMetricsActual, activeEntityMetricsExpected);
        assertEquals(deletedEntityMetricsActual, deletedEntityMetricsExpected);
    }

    @Test
    public void testNotificationMetrics() {
        Instant now           = Clock.systemUTC().instant();
        Instant dayStartTime  = AtlasMetricsCounter.getDayStartTime(now);
        Instant dayEndTime    = AtlasMetricsCounter.getNextDayStartTime(now);
        Instant hourStartTime = dayEndTime.minusSeconds(60 * 60);

        prepareNotificationData(dayStartTime, hourStartTime);

        clock.setInstant(dayEndTime.minusSeconds(1));

        Map<String, Object> notificationMetricMap = metricsUtil.getStats();

        clock.setInstant(null);

        verifyNotificationMetric(metricExpected, notificationMetricMap);
    }


    private void loadModelFilesAndImportTestData() {
        try {
            loadModelFromJson("0000-Area0/0010-base_model.json", typeDefStore, typeRegistry);
            loadModelFromJson("0000-Area0/patches/001-base_model_replication_attributes.json", typeDefStore, typeRegistry);
            loadModelFromJson("1000-Hadoop/1020-fs_model.json", typeDefStore, typeRegistry);
            loadModelFromJson("1000-Hadoop/1030-hive_model.json", typeDefStore, typeRegistry);
            loadModelFromJson("1000-Hadoop/patches/001-hive_column_add_position.json", typeDefStore, typeRegistry);
            loadModelFromJson("1000-Hadoop/patches/002-hive_column_table_add_options.json", typeDefStore, typeRegistry);
            loadModelFromJson("1000-Hadoop/patches/003-hive_column_update_table_remove_constraint.json", typeDefStore, typeRegistry);

            runImportWithNoParameters(importService, getZipSource(IMPORT_FILE));
        } catch (AtlasBaseException | IOException e) {
            throw new SkipException("Model loading failed!");
        }
    }

    private void prepareNotificationData(Instant dayStartTime, Instant hourStartTime) {
        Instant prevDayStartTime = AtlasMetricsCounter.getDayStartTime(dayStartTime.minusSeconds(1));

        msgOffset = 0;

        clock.setInstant(prevDayStartTime);
        metricsUtil.init(clock);
        clock.setInstant(null);

        processMessage(prevDayStartTime.plusSeconds(3)); // yesterday
        processMessage(dayStartTime.plusSeconds(3));     // today
        processMessage(hourStartTime.minusSeconds(3));   // past hour
        processMessage(hourStartTime.plusSeconds(3));    // this hour
    }

    private void processMessage(Instant instant) {
        clock.setInstant(instant);

        metricsUtil.onNotificationProcessingComplete(++msgOffset, new AtlasMetricsUtil.NotificationStat(true, 1));

        for (int i = 0; i < 10; i++) {
            metricsUtil.onNotificationProcessingComplete(msgOffset++, new AtlasMetricsUtil.NotificationStat(false, 1));
        }

        clock.setInstant(null);
    }

    private void verifyNotificationMetric(Map<String, Object> metricExpected, Map<String, Object> notificationMetrics) {
        assertNotNull(notificationMetrics);
        assertNotEquals(notificationMetrics.size(), 0);
        assertTrue(notificationMetrics.size() >= metricExpected.size());

        for (Map.Entry<String, Object> entry : metricExpected.entrySet()) {
            assertEquals(notificationMetrics.get(entry.getKey()), entry.getValue(), entry.getKey());
        }
    }

    public static ZipSource getZipSource(String fileName) throws IOException, AtlasBaseException {
        FileInputStream fs = ZipFileResourceTestUtils.getFileInputStream(fileName);
        return new ZipSource(fs);
    }

    private static class TestClock extends Clock {
        private final Clock   baseClock;
        private final ZoneId  zone;
        private       Instant instant = null;

        public TestClock(Clock baseClock, ZoneId zone) {
            this.baseClock = baseClock;
            this.zone      = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public TestClock withZone(ZoneId zone) {
            return new TestClock(baseClock, zone);
        }

        @Override
        public Instant instant() {
            return instant != null ? instant : baseClock.instant();
        }

        public void setInstant(Instant instant) {
            this.instant = instant;
        }
    }
}