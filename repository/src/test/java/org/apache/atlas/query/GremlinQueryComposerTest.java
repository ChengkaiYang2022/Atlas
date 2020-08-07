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
package org.apache.atlas.query;

import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TypeCategory;
import org.apache.atlas.query.antlr4.AtlasDSLParser;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.commons.lang.StringUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class GremlinQueryComposerTest {
    @Test
    public void classification() {
        String expected = "g.V().outE('classifiedAs').has('__name', within('PII')).outV().dedup().limit(25).toList()";
        verify("PII", expected);
    }

    @Test()
    public void dimension() {
        String expected = "g.V().has('__typeName', 'Table').outE('classifiedAs').has('__name', within('Dimension')).outV().dedup().limit(25).toList()";
        verify("Table isa Dimension", expected);
        verify("Table is Dimension", expected);
        verify("Table where Table is Dimension", expected);
    }

    @Test
    public void fromDB() {
        String expected10 = "g.V().has('__typeName', 'DB').dedup().limit(10).toList()";
        verify("from DB", "g.V().has('__typeName', 'DB').dedup().limit(25).toList()");
        verify("from DB limit 10", expected10);
        verify("DB limit 10", expected10);
    }

    @Test
    public void DBHasName() {
        String expected = "g.V().has('__typeName', 'DB').has('DB.name').dedup().limit(25).toList()";
        verify("DB has name", expected);
        verify("DB where DB has name", expected);
    }

    @Test
    public void DBasD() {
        verify("DB as d", "g.V().has('__typeName', 'DB').as('d').dedup().limit(25).toList()");
    }

    @Test
    public void DBasDSelect() {
        String expected = "def f(r){ t=[['d.name','d.owner']];  r.each({t.add([it.value('DB.name'),it.value('DB.owner')])}); t.unique(); }; " +
                                  "f(g.V().has('__typeName', 'DB').as('d').has('DB.name').has('DB.owner')";
        verify("DB as d select d.name, d.owner", expected + ".dedup().limit(25).toList())");
        verify("DB as d select d.name, d.owner limit 10", expected + ".dedup().limit(10).toList())");
        verify("DB as d select d","def f(r){ r }; f(g.V().has('__typeName', 'DB').as('d').dedup().limit(25).toList())");
    }

    @Test
    public void tableSelectColumns() {
        String exMain = "g.V().has('__typeName', 'Table').out('__Table.columns').dedup().limit(10).toList()";
        String exSel = "def f(r){ r }";
        String exSel1 = "def f(r){ t=[['db.name']];  r.each({t.add([it.value('DB.name')])}); t.unique(); }";
        verify("Table select columns limit 10", getExpected(exSel, exMain));

        String exMain2 = "g.V().has('__typeName', 'Table').out('__Table.db').dedup().limit(25).toList()";
        verify("Table select db", getExpected(exSel, exMain2));

        String exMain3 = "g.V().has('__typeName', 'Table').out('__Table.db').dedup().limit(25).toList()";
        verify("Table select db.name", getExpected(exSel1, exMain3));

    }

    @Test
    public void valueArray() {
        verify("DB where owner = ['hdfs', 'anon']", "g.V().has('__typeName', 'DB').has('DB.owner', within('hdfs','anon')).dedup().limit(25).toList()");
        verify("DB owner = ['hdfs', 'anon']", "g.V().has('__typeName', 'DB').has('DB.owner', within('hdfs','anon')).dedup().limit(25).toList()");
        verify("hive_db as d owner = ['hdfs', 'anon']", "g.V().has('__typeName', 'hive_db').as('d').has('hive_db.owner', within('hdfs','anon')).dedup().limit(25).toList()");
    }
    @Test
    public void groupByMin() {
        verify("from DB groupby (owner) select min(name) orderby name limit 2",
                "def f(l){ t=[['min(name)']]; l.get(0).each({k,r -> L:{ def min=r.min({it.value('DB.name')}).value('DB.name'); t.add([min]); } }); t; }; " +
                        "f(g.V().has('__typeName', 'DB').group().by('DB.owner').dedup().toList())");
    }

    @Test
    public void groupByOrderBy() {
        verify("Table groupby(owner) select name, owner, clusterName orderby name",
                "def f(l){ h=[['name','owner','clusterName']]; t=[]; " +
                        "l.get(0).each({k,r -> L:{  r.each({t.add([it.value('Table.name'),it.value('Table.owner'),it.value('Table.clusterName')])}) } }); " +
                        "h.plus(t.unique().sort{a,b -> a[0] <=> b[0]}); }; " +
                        "f(g.V().has('__typeName', 'Table').has('Table.name').has('Table.owner').has('Table.clusterName').group().by('Table.owner').dedup().limit(25).toList())");
    }

    @Test
    public void DBAsDSelectLimit() {
        verify("from DB limit 5", "g.V().has('__typeName', 'DB').dedup().limit(5).toList()");
        verify("from DB limit 5 offset 2", "g.V().has('__typeName', 'DB').dedup().range(2, 2 + 5).toList()");
    }

    @Test
    public void DBOrderBy() {
        String expected = "g.V().has('__typeName', 'DB').order().by('DB.name').dedup().limit(25).toList()";
        verify("DB orderby name", expected);
        verify("from DB orderby name", expected);
        verify("from DB as d orderby d.owner limit 3", "g.V().has('__typeName', 'DB').as('d').order().by('DB.owner').dedup().limit(3).toList()");
        verify("DB as d orderby d.owner limit 3", "g.V().has('__typeName', 'DB').as('d').order().by('DB.owner').dedup().limit(3).toList()");

        String exSel = "def f(r){ t=[['d.name','d.owner']];  r.each({t.add([it.value('DB.name'),it.value('DB.owner')])}); t.unique(); }";
        String exMain = "g.V().has('__typeName', 'DB').as('d').has('DB.name').has('DB.owner').order().by('DB.owner').dedup().limit(25).toList()";
        verify("DB as d select d.name, d.owner orderby (d.owner) limit 25", getExpected(exSel, exMain));

        String exMain2 = "g.V().has('__typeName', 'Table').and(__.has('Table.name', eq(\"sales_fact\")),__.has('Table.createTime', gt('1418265300000'))).order().by('Table.createTime').dedup().limit(25).toList()";
        String exSel2 = "def f(r){ t=[['_col_0','_col_1']];  r.each({t.add([it.value('Table.name'),it.value('Table.createTime')])}); t.unique(); }";
        verify("Table where (name = \"sales_fact\" and createTime > \"2014-12-11T02:35:0.0Z\" ) select name as _col_0, createTime as _col_1 orderby _col_1",
                getExpected(exSel2, exMain2));
    }

    @Test
    public void fromDBOrderByNameDesc() {
        verify("from DB orderby name DESC", "g.V().has('__typeName', 'DB').order().by('DB.name', decr).dedup().limit(25).toList()");
    }

    @Test
    public void fromDBSelect() {
        String expected = "def f(r){ t=[['DB.name','DB.owner']];  r.each({t.add([it.value('DB.name'),it.value('DB.owner')])}); t.unique(); }; " +
                                  "f(g.V().has('__typeName', 'DB').has('DB.name').has('DB.owner').dedup().limit(25).toList())";
        verify("from DB select DB.name, DB.owner", expected);
    }

    @Test
    public void fromDBGroupBy() {
        verify("from DB groupby (DB.owner)", "g.V().has('__typeName', 'DB').group().by('DB.owner').dedup().limit(25).toList()");
    }

    @Test
    public void whereClauseTextContains() {
        String exMain = "g.V().has('__typeName', 'DB').has('DB.name', eq(\"Reporting\")).has('DB.owner').dedup().limit(25).toList()";
        String exSel = "def f(r){ t=[['name','owner']];  r.each({t.add([it.value('DB.name'),it.value('DB.owner')])}); t.unique(); }";
        verify("from DB where name = \"Reporting\" select name, owner", getExpected(exSel, exMain));
        verify("from DB where (name = \"Reporting\") select name, owner", getExpected(exSel, exMain));
        verify("Table where Asset.name like \"Tab*\"",
                "g.V().has('__typeName', 'Table').has('Table.name', org.janusgraph.core.attribute.Text.textRegex(\"Tab.*\")).dedup().limit(25).toList()");
        verify("from Table where (db.name = \"Reporting\")",
                "g.V().has('__typeName', 'Table').out('__Table.db').has('DB.name', eq(\"Reporting\")).dedup().in('__Table.db').dedup().limit(25).toList()");
    }

    @Test
    public void whereClauseWithAsTextContains() {
        String exSel = "def f(r){ t=[['t.name','t.owner']];  r.each({t.add([it.value('Table.name'),it.value('Table.owner')])}); t.unique(); }";
        String exMain = "g.V().has('__typeName', 'Table').as('t').has('Table.name', eq(\"testtable_1\")).has('Table.owner').dedup().limit(25).toList()";
        verify("Table as t where t.name = \"testtable_1\" select t.name, t.owner", getExpected(exSel, exMain));
    }

    @Test
    public void whereClauseWithDateCompare() {
        String exSel = "def f(r){ t=[['t.name','t.owner']];  r.each({t.add([it.value('Table.name'),it.value('Table.owner')])}); t.unique(); }";
        String exMain = "g.V().has('__typeName', 'Table').as('t').has('Table.createTime', eq('%s')).has('Table.name').has('Table.owner').dedup().limit(25).toList()";
        verify("Table as t where t.createTime = \"2017-12-12T02:35:58.440Z\" select t.name, t.owner", getExpected(exSel, String.format(exMain, "1513046158440")));
        verify("Table as t where t.createTime = \"2017-12-12\" select t.name, t.owner", getExpected(exSel, String.format(exMain, "1513036800000")));
    }

    @Test
    public void subType() {
        String exMain = "g.V().has('__typeName', within('Asset','Table')).has('Asset.name').has('Asset.owner').dedup().limit(25).toList()";
        String exSel = "def f(r){ t=[['name','owner']];  r.each({t.add([it.value('Asset.name'),it.value('Asset.owner')])}); t.unique(); }";

        verify("Asset select name, owner", getExpected(exSel, exMain));
    }

    @Test
    public void countMinMax() {
        verify("from DB groupby (owner) select count()",
                "def f(l){ t=[['count()']]; l.get(0).each({k,r -> L:{ def count=r.size(); t.add([count]); } }); t; }; f(g.V().has('__typeName', 'DB').group().by('DB.owner').dedup().toList())");
        verify("from DB groupby (owner) select max(name)",
                "def f(l){ t=[['max(name)']]; l.get(0).each({k,r -> L:{ def max=r.max({it.value('DB.name')}).value('DB.name'); t.add([max]); } }); t; }; f(g.V().has('__typeName', 'DB').group().by('DB.owner').dedup().toList())");
        verify("from DB groupby (owner) select min(name)",
                "def f(l){ t=[['min(name)']]; l.get(0).each({k,r -> L:{ def min=r.min({it.value('DB.name')}).value('DB.name'); t.add([min]); } }); t; }; f(g.V().has('__typeName', 'DB').group().by('DB.owner').dedup().toList())");
        verify("from Table select sum(createTime)",
                "def f(r){ t=[['sum(createTime)']]; def sum=r.sum({it.value('Table.createTime')}); t.add([sum]); t;}; f(g.V().has('__typeName', 'Table').dedup().toList())");
    }

    @Test
    public void traitWithSpace() {
        verify("`Log Data`", "g.V().has('__typeName', 'Log Data').dedup().limit(25).toList()");
    }

    @Test
    public void whereClauseWithBooleanCondition() {
        String queryFormat = "Table as t where name ='Reporting' or t.isFile = %s";
        String expectedFormat = "g.V().has('__typeName', 'Table').as('t').or(__.has('Table.name', eq('Reporting')),__.has('Table.isFile', eq(%s))).dedup().limit(25).toList()";
        verify(String.format(queryFormat, "true"), String.format(expectedFormat, "true"));
        verify(String.format(queryFormat, "false"), String.format(expectedFormat, "false"));
        verify(String.format(queryFormat, "True"), String.format(expectedFormat, "True"));
        verify(String.format(queryFormat, "FALSE"), String.format(expectedFormat, "FALSE"));
    }

    @DataProvider(name = "nestedQueriesProvider")
    private Object[][] nestedQueriesSource() {
        return new Object[][]{
                {"Table where name=\"sales_fact\" or name=\"testtable_1\"",
                        "g.V().has('__typeName', 'Table').or(__.has('Table.name', eq(\"sales_fact\")),__.has('Table.name', eq(\"testtable_1\"))).dedup().limit(25).toList()"},
                {"Table where name=\"sales_fact\" and name=\"testtable_1\"",
                        "g.V().has('__typeName', 'Table').and(__.has('Table.name', eq(\"sales_fact\")),__.has('Table.name', eq(\"testtable_1\"))).dedup().limit(25).toList()"},
                {"Table where name=\"sales_fact\" or name=\"testtable_1\" or name=\"testtable_2\"",
                        "g.V().has('__typeName', 'Table')" +
                                ".or(" +
                                "__.has('Table.name', eq(\"sales_fact\"))," +
                                "__.has('Table.name', eq(\"testtable_1\"))," +
                                "__.has('Table.name', eq(\"testtable_2\"))" +
                                ").dedup().limit(25).toList()"},
                {"Table where name=\"sales_fact\" and name=\"testtable_1\" and name=\"testtable_2\"",
                        "g.V().has('__typeName', 'Table')" +
                                ".and(" +
                                "__.has('Table.name', eq(\"sales_fact\"))," +
                                "__.has('Table.name', eq(\"testtable_1\"))," +
                                "__.has('Table.name', eq(\"testtable_2\"))" +
                                ").dedup().limit(25).toList()"},
                {"Table where (name=\"sales_fact\" or name=\"testtable_1\") and name=\"testtable_2\"",
                        "g.V().has('__typeName', 'Table')" +
                                ".and(" +
                                "__.or(" +
                                "__.has('Table.name', eq(\"sales_fact\"))," +
                                "__.has('Table.name', eq(\"testtable_1\"))" +
                                ")," +
                                "__.has('Table.name', eq(\"testtable_2\")))" +
                                ".dedup().limit(25).toList()"},
                {"Table where name=\"sales_fact\" or (name=\"testtable_1\" and name=\"testtable_2\")",
                        "g.V().has('__typeName', 'Table')" +
                                ".or(" +
                                "__.has('Table.name', eq(\"sales_fact\"))," +
                                "__.and(" +
                                "__.has('Table.name', eq(\"testtable_1\"))," +
                                "__.has('Table.name', eq(\"testtable_2\")))" +
                                ")" +
                                ".dedup().limit(25).toList()"},
                {"Table where name=\"sales_fact\" or name=\"testtable_1\" and name=\"testtable_2\"",
                        "g.V().has('__typeName', 'Table')" +
                                ".and(" +
                                "__.or(" +
                                "__.has('Table.name', eq(\"sales_fact\"))," +
                                "__.has('Table.name', eq(\"testtable_1\"))" +
                                ")," +
                                "__.has('Table.name', eq(\"testtable_2\")))" +
                                ".dedup().limit(25).toList()"},
                {"Table where (name=\"sales_fact\" and owner=\"Joe\") OR (name=\"sales_fact_daily_mv\" and owner=\"Joe BI\")",
                        "g.V().has('__typeName', 'Table')" +
                                ".or(" +
                                "__.and(" +
                                "__.has('Table.name', eq(\"sales_fact\"))," +
                                "__.has('Table.owner', eq(\"Joe\"))" +
                                ")," +
                                "__.and(" +
                                "__.has('Table.name', eq(\"sales_fact_daily_mv\"))," +
                                "__.has('Table.owner', eq(\"Joe BI\"))" +
                                "))" +
                                ".dedup().limit(25).toList()"},
                {"Table where owner=\"hdfs\" or ((name=\"testtable_1\" or name=\"testtable_2\") and createTime < \"2017-12-12T02:35:58.440Z\")",
                        "g.V().has('__typeName', 'Table').or(__.has('Table.owner', eq(\"hdfs\")),__.and(__.or(__.has('Table.name', eq(\"testtable_1\")),__.has('Table.name', eq(\"testtable_2\"))),__.has('Table.createTime', lt('1513046158440')))).dedup().limit(25).toList()"},
                {"hive_db where hive_db.name='Reporting' and hive_db.createTime < '2017-12-12T02:35:58.440Z'",
                        "g.V().has('__typeName', 'hive_db').and(__.has('hive_db.name', eq('Reporting')),__.has('hive_db.createTime', lt('1513046158440'))).dedup().limit(25).toList()"},
                {"Table where db.name='Sales' and db.clusterName='cl1'",
                        "g.V().has('__typeName', 'Table').and(__.out('__Table.db').has('DB.name', eq('Sales')).dedup().in('__Table.db'),__.out('__Table.db').has('DB.clusterName', eq('cl1')).dedup().in('__Table.db')).dedup().limit(25).toList()"},
        };
    }

    @Test(dataProvider = "nestedQueriesProvider")
    public void nestedQueries(String query, String expectedGremlin) {
        verify(query, expectedGremlin);
        verify(query.replace("where", " "), expectedGremlin);
    }

    @Test
    public void keywordsInWhereClause() {
        verify("Table as t where t has name and t isa Dimension",
                "g.V().has('__typeName', 'Table').as('t').and(__.has('Table.name'),__.outE('classifiedAs').has('__name', within('Dimension')).outV()).dedup().limit(25).toList()");
        verify("Table as t where t has name and t.name = 'sales_fact'",
                "g.V().has('__typeName', 'Table').as('t').and(__.has('Table.name'),__.has('Table.name', eq('sales_fact'))).dedup().limit(25).toList()");
        verify("Table as t where t is Dimension and t.name = 'sales_fact'",
                "g.V().has('__typeName', 'Table').as('t').and(__.outE('classifiedAs').has('__name', within('Dimension')).outV(),__.has('Table.name', eq('sales_fact'))).dedup().limit(25).toList()");
        verify("Table isa 'Dimension' and name = 'sales_fact'", "g.V().has('__typeName', 'Table').and(__.outE('classifiedAs').has('__name', within('Dimension')).outV(),__.has('Table.name', eq('sales_fact'))).dedup().limit(25).toList()");
        verify("Table has name and name = 'sales_fact'", "g.V().has('__typeName', 'Table').and(__.has('Table.name'),__.has('Table.name', eq('sales_fact'))).dedup().limit(25).toList()");
        verify("Table is 'Dimension' and Table has owner and name = 'sales_fact'", "g.V().has('__typeName', 'Table').and(__.outE('classifiedAs').has('__name', within('Dimension')).outV(),__.has('Table.owner'),__.has('Table.name', eq('sales_fact'))).dedup().limit(25).toList()");
        verify("Table has name and Table has owner and name = 'sales_fact'", "g.V().has('__typeName', 'Table').and(__.has('Table.name'),__.has('Table.owner'),__.has('Table.name', eq('sales_fact'))).dedup().limit(25).toList()");
    }


    @Test
    public void numericAttributes() {
        verify("Table where partitionSize = 2048", "g.V().has('__typeName', 'Table').has('Table.partitionSize', eq(2048f)).dedup().limit(25).toList()");
        verify("Table where partitionSize = 2048 or partitionSize = 10", "g.V().has('__typeName', 'Table').or(__.has('Table.partitionSize', eq(2048f)),__.has('Table.partitionSize', eq(10f))).dedup().limit(25).toList()");
    }

    @Test
    public void systemAttributes() {
        verify("Table has __state", "g.V().has('__typeName', 'Table').has('__state').dedup().limit(25).toList()");
        verify("Table select __guid", "def f(r){ t=[['__guid']];  r.each({t.add([it.value('__guid')])}); t.unique(); }; f(g.V().has('__typeName', 'Table').has('__guid').dedup().limit(25).toList())");
        verify("Table as t where t.__state = 'ACTIVE'", "g.V().has('__typeName', 'Table').as('t').has('__state', eq('ACTIVE')).dedup().limit(25).toList()");
    }

    @Test
    public void whereComplexAndSelect() {
        String exSel = "def f(r){ t=[['name']];  r.each({t.add([it.value('Table.name')])}); t.unique(); }";
        String exMain = "g.V().has('__typeName', 'Table').and(__.out('__Table.db').has('DB.name', eq(\"Reporting\")).dedup().in('__Table.db'),__.has('Table.name', eq(\"sales_fact\"))).dedup().limit(25).toList()";
        verify("Table where db.name = \"Reporting\" and name =\"sales_fact\" select name", getExpected(exSel, exMain));
        verify("Table where db.name = \"Reporting\" and name =\"sales_fact\"", exMain);
    }

    @Test
    public void invalidQueries() {
        verify("hdfs_path like h1", "");
    }

    @Test
    public void invalidQueries2() {
        verify("PIII", 3);
        verify("TableTTT where name = 'abcd'", 1);
        verify("Table isa Table", 1);
        verify("Table has xxx", 1);
        verify("Table where createType = '2010-03-30'", 1);
        verify("Table has db", 1);
        verify("Table groupby(db) select name", 1);
        verify("Table groupby(name) select name, max(db)", 1);
        verify("Table select db, columns", 2);
        verify("Table select db, owner, columns", 3);
    }

    private void verify(String dsl, String expectedGremlin, int expectedNumberOfErrors) {
        AtlasDSLParser.QueryContext queryContext = getParsedQuery(dsl);
        String actualGremlin = getGremlinQuery(dsl, queryContext, expectedNumberOfErrors);
        if(expectedNumberOfErrors == 0) {
            assertEquals(actualGremlin, expectedGremlin, dsl);
        }
    }

    private void verify(String dsl, int expectedNumberOfErrors) {
        verify(dsl, "", expectedNumberOfErrors);
    }

    private void verify(String dsl, String expectedGremlin) {
        verify(dsl, expectedGremlin, 0);
    }

    private String getExpected(String select, String main) {
        return String.format("%s; f(%s)", select, main);
    }

    private AtlasDSLParser.QueryContext getParsedQuery(String query) {
        AtlasDSLParser.QueryContext queryContext = null;
        try {
            queryContext = AtlasDSL.Parser.parse(query);
        } catch (AtlasBaseException e) {
            fail(e.getMessage());
        }
        return queryContext;
    }

    private String getGremlinQuery(String dsl, AtlasDSLParser.QueryContext queryContext, int expectedNumberOfErrors) {
        AtlasTypeRegistry             registry = mock(AtlasTypeRegistry.class);
        org.apache.atlas.query.Lookup lookup   = new TestLookup(registry);
        GremlinQueryComposer.Context  context  = new GremlinQueryComposer.Context(lookup);
        AtlasDSL.QueryMetadata queryMetadata   = new AtlasDSL.QueryMetadata(queryContext);

        GremlinQueryComposer gremlinQueryComposer = new GremlinQueryComposer(lookup, context, queryMetadata);
        DSLVisitor           qv                   = new DSLVisitor(gremlinQueryComposer);
        qv.visit(queryContext);

        String s = gremlinQueryComposer.get();
        int actualNumberOfErrors = gremlinQueryComposer.getErrorList().size();
        assertEquals(actualNumberOfErrors, expectedNumberOfErrors, dsl);
        return s;
    }

    private static class TestLookup implements org.apache.atlas.query.Lookup {
        AtlasTypeRegistry registry;

        TestLookup(AtlasTypeRegistry typeRegistry) {
            this.registry = typeRegistry;
        }

        @Override
        public AtlasType getType(String typeName) throws AtlasBaseException {
            AtlasType type;
            if(typeName.equals("PII") || typeName.equals("Dimension")) {
                type = mock(AtlasType.class);
                when(type.getTypeCategory()).thenReturn(TypeCategory.CLASSIFICATION);
            } else {
                type = mock(AtlasEntityType.class);
                when(type.getTypeCategory()).thenReturn(TypeCategory.ENTITY);
            }

            if(typeName.equals("PIII")) {
                throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_NOT_FOUND);
            }
            when(type.getTypeName()).thenReturn(typeName);
            return type;
        }

        @Override
        public String getQualifiedName(GremlinQueryComposer.Context context, String name) throws AtlasBaseException {
            if(name.startsWith("__")) {
                return name.equals("__state") || name.equals("__guid") ? name : "";
            }

            if(!hasAttribute(context, name)) {
                throw new AtlasBaseException("Invalid attribute");
            }

            if(name.contains("."))
                return name;

            if(!context.getActiveTypeName().equals(name))
                return String.format("%s.%s", context.getActiveTypeName(), name);
            else
                return name;
        }

        @Override
        public boolean isPrimitive(GremlinQueryComposer.Context context, String attributeName) {
            return attributeName.equals("name") ||
                    attributeName.equals("owner") ||
                    attributeName.equals("createTime") ||
                    attributeName.equals("clusterName") ||
                    attributeName.equals("__guid") ||
                    attributeName.equals("__state") ||
                    attributeName.equals("partitionSize");
        }

        @Override
        public String getRelationshipEdgeLabel(GremlinQueryComposer.Context context, String attributeName) {
            if (attributeName.equalsIgnoreCase("columns"))
                return "__Table.columns";
            if (attributeName.equalsIgnoreCase("db"))
                return "__Table.db";
            else
                return "__DB.Table";
        }

        @Override
        public boolean hasAttribute(GremlinQueryComposer.Context context, String attributeName) {
            return (context.getActiveTypeName().equals("Table") && attributeName.equals("db")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("columns")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("createTime")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("name")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("owner")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("clusterName")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("isFile")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("__guid")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("__state")) ||
                    (context.getActiveTypeName().equals("Table") && attributeName.equals("partitionSize")) ||
                    (context.getActiveTypeName().equals("hive_db") && attributeName.equals("name")) ||
                    (context.getActiveTypeName().equals("hive_db") && attributeName.equals("owner")) ||
                    (context.getActiveTypeName().equals("hive_db") && attributeName.equals("createTime")) ||
                    (context.getActiveTypeName().equals("DB") && attributeName.equals("name")) ||
                    (context.getActiveTypeName().equals("DB") && attributeName.equals("owner")) ||
                    (context.getActiveTypeName().equals("DB") && attributeName.equals("clusterName")) ||
                    (context.getActiveTypeName().equals("Asset") && attributeName.equals("name")) ||
                    (context.getActiveTypeName().equals("Asset") && attributeName.equals("owner"));
        }

        @Override
        public boolean doesTypeHaveSubTypes(GremlinQueryComposer.Context context) {
            return context.getActiveTypeName().equalsIgnoreCase("Asset");
        }

        @Override
        public String getTypeAndSubTypes(GremlinQueryComposer.Context context) {
            String[] str = new String[]{"'Asset'", "'Table'"};
            return StringUtils.join(str, ",");
        }

        @Override
        public boolean isTraitType(String typeName) {
            return typeName.equals("PII") || typeName.equals("Dimension");
        }

        @Override
        public String getTypeFromEdge(GremlinQueryComposer.Context context, String item) {
            if(context.getActiveTypeName().equals("DB") && item.equals("Table")) {
                return "Table";
            }

            if(context.getActiveTypeName().equals("Table") && item.equals("Column")) {
                return "Column";
            }

            if(context.getActiveTypeName().equals("Table") && item.equals("db")) {
                return "DB";
            }

            if(context.getActiveTypeName().equals("Table") && item.equals("columns")) {
                return "Column";
            }

            if(context.getActiveTypeName().equals(item)) {
                return null;
            }
            return context.getActiveTypeName();
        }

        @Override
        public boolean isDate(GremlinQueryComposer.Context context, String attributeName) {
            return attributeName.equals("createTime");
        }

        @Override
        public boolean isNumeric(GremlinQueryComposer.Context context, String attrName) {
            context.setNumericTypeFormatter("f");
            return attrName.equals("partitionSize");
        }
    }
}
