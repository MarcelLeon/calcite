/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rel2sql;

import org.apache.calcite.config.NullCollation;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.rules.ProjectToWindowRule;
import org.apache.calcite.rel.rules.PruneEmptyRules;
import org.apache.calcite.rel.rules.UnionMergeRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialect.Context;
import org.apache.calcite.sql.SqlDialect.DatabaseProduct;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.dialect.HiveSqlDialect;
import org.apache.calcite.sql.dialect.JethroDataSqlDialect;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.test.CalciteAssert;
import org.apache.calcite.test.MockSqlOperatorTable;
import org.apache.calcite.test.RelBuilderTest;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RuleSet;
import org.apache.calcite.tools.RuleSets;
import org.apache.calcite.util.TestUtil;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.calcite.test.Matchers.isLinux;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RelToSqlConverter}.
 */
public class RelToSqlConverterTest {
  static final SqlToRelConverter.Config DEFAULT_REL_CONFIG =
      SqlToRelConverter.configBuilder()
          .withTrimUnusedFields(false)
          .withConvertTableAccess(false)
          .build();

  static final SqlToRelConverter.Config NO_EXPAND_CONFIG =
      SqlToRelConverter.configBuilder()
          .withTrimUnusedFields(false)
          .withConvertTableAccess(false)
          .withExpand(false)
          .build();

  /** Initiates a test case with a given SQL query. */
  private Sql sql(String sql) {
    return new Sql(CalciteAssert.SchemaSpec.JDBC_FOODMART, sql,
        CalciteSqlDialect.DEFAULT, DEFAULT_REL_CONFIG,
        ImmutableList.of());
  }

  private static Planner getPlanner(List<RelTraitDef> traitDefs,
      SqlParser.Config parserConfig, SchemaPlus schema,
      SqlToRelConverter.Config sqlToRelConf, Program... programs) {
    final MockSqlOperatorTable operatorTable =
        new MockSqlOperatorTable(SqlStdOperatorTable.instance());
    MockSqlOperatorTable.addRamp(operatorTable);
    final FrameworkConfig config = Frameworks.newConfigBuilder()
        .parserConfig(parserConfig)
        .defaultSchema(schema)
        .traitDefs(traitDefs)
        .sqlToRelConverterConfig(sqlToRelConf)
        .programs(programs)
        .operatorTable(operatorTable)
        .build();
    return Frameworks.getPlanner(config);
  }

  private static JethroDataSqlDialect jethroDataSqlDialect() {
    Context dummyContext = SqlDialect.EMPTY_CONTEXT
        .withDatabaseProduct(SqlDialect.DatabaseProduct.JETHRO)
        .withDatabaseMajorVersion(1)
        .withDatabaseMinorVersion(0)
        .withDatabaseVersion("1.0")
        .withIdentifierQuoteString("\"")
        .withNullCollation(NullCollation.HIGH)
        .withJethroInfo(JethroDataSqlDialect.JethroInfo.EMPTY);
    return new JethroDataSqlDialect(dummyContext);
  }

  private static MysqlSqlDialect mySqlDialect(NullCollation nullCollation) {
    return new MysqlSqlDialect(MysqlSqlDialect.DEFAULT_CONTEXT
        .withNullCollation(nullCollation));
  }

  /** Returns a collection of common dialects, and the database products they
   * represent. */
  private static Map<SqlDialect, DatabaseProduct> dialects() {
    return ImmutableMap.<SqlDialect, DatabaseProduct>builder()
        .put(SqlDialect.DatabaseProduct.BIG_QUERY.getDialect(),
            SqlDialect.DatabaseProduct.BIG_QUERY)
        .put(SqlDialect.DatabaseProduct.CALCITE.getDialect(),
            SqlDialect.DatabaseProduct.CALCITE)
        .put(SqlDialect.DatabaseProduct.DB2.getDialect(),
            SqlDialect.DatabaseProduct.DB2)
        .put(SqlDialect.DatabaseProduct.HIVE.getDialect(),
            SqlDialect.DatabaseProduct.HIVE)
        .put(jethroDataSqlDialect(),
            SqlDialect.DatabaseProduct.JETHRO)
        .put(SqlDialect.DatabaseProduct.MSSQL.getDialect(),
            SqlDialect.DatabaseProduct.MSSQL)
        .put(SqlDialect.DatabaseProduct.MYSQL.getDialect(),
            SqlDialect.DatabaseProduct.MYSQL)
        .put(mySqlDialect(NullCollation.HIGH),
            SqlDialect.DatabaseProduct.MYSQL)
        .put(SqlDialect.DatabaseProduct.ORACLE.getDialect(),
            SqlDialect.DatabaseProduct.ORACLE)
        .put(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect(),
            SqlDialect.DatabaseProduct.POSTGRESQL)
        .build();
  }

  /** Creates a RelBuilder. */
  private static RelBuilder relBuilder() {
    return RelBuilder.create(RelBuilderTest.config().build());
  }

  /** Converts a relational expression to SQL. */
  private String toSql(RelNode root) {
    return toSql(root, SqlDialect.DatabaseProduct.CALCITE.getDialect());
  }

  /** Converts a relational expression to SQL in a given dialect. */
  private static String toSql(RelNode root, SqlDialect dialect) {
    final RelToSqlConverter converter = new RelToSqlConverter(dialect);
    final SqlNode sqlNode = converter.visitChild(0, root).asStatement();
    return sqlNode.toSqlString(dialect).getSql();
  }

  @Test public void testSimpleSelectStarFromProductTable() {
    String query = "select * from \"product\"";
    sql(query).ok("SELECT *\nFROM \"foodmart\".\"product\"");
  }

  @Test public void testSimpleSelectQueryFromProductTable() {
    String query = "select \"product_id\", \"product_class_id\" from \"product\"";
    final String expected = "SELECT \"product_id\", \"product_class_id\"\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithWhereClauseOfLessThan() {
    String query =
        "select \"product_id\", \"shelf_width\"  from \"product\" where \"product_id\" < 10";
    final String expected = "SELECT \"product_id\", \"shelf_width\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_id\" < 10";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithWhereClauseOfBasicOperators() {
    String query = "select * from \"product\" "
        + "where (\"product_id\" = 10 OR \"product_id\" <= 5) "
        + "AND (80 >= \"shelf_width\" OR \"shelf_width\" > 30)";
    final String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE (\"product_id\" = 10 OR \"product_id\" <= 5) "
        + "AND (80 >= \"shelf_width\" OR \"shelf_width\" > 30)";
    sql(query).ok(expected);
  }


  @Test public void testSelectQueryWithGroupBy() {
    String query = "select count(*) from \"product\" group by \"product_class_id\", \"product_id\"";
    final String expected = "SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\", \"product_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithHiveCube() {
    String query = "select \"product_class_id\", \"product_id\", count(*) "
            + "from \"product\" group by cube(\"product_class_id\", \"product_id\")";
    String expected = "SELECT product_class_id, product_id, COUNT(*)\n"
            + "FROM foodmart.product\n"
            + "GROUP BY product_class_id, product_id WITH CUBE";
    sql(query).withHive().ok(expected);
    SqlDialect sqlDialect = sql(query).withHive().dialect;
    assertTrue(sqlDialect.supportsGroupByWithCube());
  }

  @Test public void testSelectQueryWithHiveRollup() {
    String query = "select \"product_class_id\", \"product_id\", count(*) "
            + "from \"product\" group by rollup(\"product_class_id\", \"product_id\")";
    String expected = "SELECT product_class_id, product_id, COUNT(*)\n"
            + "FROM foodmart.product\n"
            + "GROUP BY product_class_id, product_id WITH ROLLUP";
    sql(query).withHive().ok(expected);
    SqlDialect sqlDialect = sql(query).withHive().dialect;
    assertTrue(sqlDialect.supportsGroupByWithRollup());
  }

  @Test public void testSelectQueryWithGroupByEmpty() {
    final String sql0 = "select count(*) from \"product\" group by ()";
    final String sql1 = "select count(*) from \"product\"";
    final String expected = "SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"";
    final String expectedMySql = "SELECT COUNT(*)\n"
        + "FROM `foodmart`.`product`";
    sql(sql0)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
    sql(sql1)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  @Test public void testSelectQueryWithGroupByEmpty2() {
    final String query = "select 42 as c from \"product\" group by ()";
    final String expected = "SELECT 42 AS \"C\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ()";
    final String expectedMySql = "SELECT 42 AS `C`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY ()";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3097">[CALCITE-3097]
   * GROUPING SETS breaks on sets of size &gt; 1 due to precedence issues</a>,
   * in particular, that we maintain proper precedence around nested lists. */
  @Test public void testGroupByGroupingSets() {
    final String query = "select \"product_class_id\", \"brand_name\"\n"
        + "from \"product\"\n"
        + "group by GROUPING SETS ((\"product_class_id\", \"brand_name\"),"
        + " (\"product_class_id\"))\n"
        + "order by 2, 1";
    final String expected = "SELECT \"product_class_id\", \"brand_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY GROUPING SETS((\"product_class_id\", \"brand_name\"),"
        + " \"product_class_id\")\n"
        + "ORDER BY \"brand_name\", \"product_class_id\"";
    sql(query)
        .withPostgresql()
        .ok(expected);
  }

  /** Tests GROUP BY ROLLUP of two columns. The SQL for MySQL has
   * "GROUP BY ... ROLLUP" but no "ORDER BY". */
  @Test public void testSelectQueryWithGroupByRollup() {
    final String query = "select \"product_class_id\", \"brand_name\"\n"
        + "from \"product\"\n"
        + "group by rollup(\"product_class_id\", \"brand_name\")\n"
        + "order by 1, 2";
    final String expected = "SELECT \"product_class_id\", \"brand_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_class_id\", \"brand_name\")\n"
        + "ORDER BY \"product_class_id\", \"brand_name\"";
    final String expectedMySql = "SELECT `product_class_id`, `brand_name`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `product_class_id`, `brand_name` WITH ROLLUP";
    final String expectedMySql8 = "SELECT `product_class_id`, `brand_name`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY ROLLUP(`product_class_id`, `brand_name`)\n"
        + "ORDER BY `product_class_id` NULLS LAST, `brand_name` NULLS LAST";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql)
        .withMysql8()
        .ok(expectedMySql8);
  }

  /** As {@link #testSelectQueryWithGroupByRollup()},
   * but ORDER BY columns reversed. */
  @Test public void testSelectQueryWithGroupByRollup2() {
    final String query = "select \"product_class_id\", \"brand_name\"\n"
        + "from \"product\"\n"
        + "group by rollup(\"product_class_id\", \"brand_name\")\n"
        + "order by 2, 1";
    final String expected = "SELECT \"product_class_id\", \"brand_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_class_id\", \"brand_name\")\n"
        + "ORDER BY \"brand_name\", \"product_class_id\"";
    final String expectedMySql = "SELECT `product_class_id`, `brand_name`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `brand_name`, `product_class_id` WITH ROLLUP";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  /** CUBE of one column is equivalent to ROLLUP, and Calcite recognizes
   * this. */
  @Test public void testSelectQueryWithSingletonCube() {
    final String query = "select \"product_class_id\", count(*) as c\n"
        + "from \"product\"\n"
        + "group by cube(\"product_class_id\")\n"
        + "order by 1, 2";
    final String expected = "SELECT \"product_class_id\", COUNT(*) AS \"C\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_class_id\")\n"
        + "ORDER BY \"product_class_id\", COUNT(*)";
    final String expectedMySql = "SELECT `product_class_id`, COUNT(*) AS `C`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `product_class_id` WITH ROLLUP\n"
        + "ORDER BY `product_class_id` IS NULL, `product_class_id`,"
        + " COUNT(*) IS NULL, COUNT(*)";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  /** As {@link #testSelectQueryWithSingletonCube()}, but no ORDER BY
   * clause. */
  @Test public void testSelectQueryWithSingletonCubeNoOrderBy() {
    final String query = "select \"product_class_id\", count(*) as c\n"
        + "from \"product\"\n"
        + "group by cube(\"product_class_id\")";
    final String expected = "SELECT \"product_class_id\", COUNT(*) AS \"C\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_class_id\")";
    final String expectedMySql = "SELECT `product_class_id`, COUNT(*) AS `C`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `product_class_id` WITH ROLLUP";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  /** Cannot rewrite if ORDER BY contains a column not in GROUP BY (in this
   * case COUNT(*)). */
  @Test public void testSelectQueryWithRollupOrderByCount() {
    final String query = "select \"product_class_id\", \"brand_name\",\n"
        + " count(*) as c\n"
        + "from \"product\"\n"
        + "group by rollup(\"product_class_id\", \"brand_name\")\n"
        + "order by 1, 2, 3";
    final String expected = "SELECT \"product_class_id\", \"brand_name\","
        + " COUNT(*) AS \"C\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_class_id\", \"brand_name\")\n"
        + "ORDER BY \"product_class_id\", \"brand_name\", COUNT(*)";
    final String expectedMySql = "SELECT `product_class_id`, `brand_name`,"
        + " COUNT(*) AS `C`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `product_class_id`, `brand_name` WITH ROLLUP\n"
        + "ORDER BY `product_class_id` IS NULL, `product_class_id`,"
        + " `brand_name` IS NULL, `brand_name`,"
        + " COUNT(*) IS NULL, COUNT(*)";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  /** As {@link #testSelectQueryWithSingletonCube()}, but with LIMIT. */
  @Test public void testSelectQueryWithCubeLimit() {
    final String query = "select \"product_class_id\", count(*) as c\n"
        + "from \"product\"\n"
        + "group by cube(\"product_class_id\")\n"
        + "limit 5";
    final String expected = "SELECT \"product_class_id\", COUNT(*) AS \"C\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_class_id\")\n"
        + "FETCH NEXT 5 ROWS ONLY";
    // If a MySQL 5 query has GROUP BY ... ROLLUP, you cannot add ORDER BY,
    // but you can add LIMIT.
    final String expectedMySql = "SELECT `product_class_id`, COUNT(*) AS `C`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `product_class_id` WITH ROLLUP\n"
        + "LIMIT 5";
    sql(query)
        .ok(expected)
        .withMysql()
        .ok(expectedMySql);
  }

  @Test public void testSelectQueryWithMinAggregateFunction() {
    String query = "select min(\"net_weight\") from \"product\" group by \"product_class_id\" ";
    final String expected = "SELECT MIN(\"net_weight\")\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithMinAggregateFunction1() {
    String query = "select \"product_class_id\", min(\"net_weight\") from"
        + " \"product\" group by \"product_class_id\"";
    final String expected = "SELECT \"product_class_id\", MIN(\"net_weight\")\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithSumAggregateFunction() {
    String query =
        "select sum(\"net_weight\") from \"product\" group by \"product_class_id\" ";
    final String expected = "SELECT SUM(\"net_weight\")\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithMultipleAggregateFunction() {
    String query = "select sum(\"net_weight\"), min(\"low_fat\"), count(*)"
        + " from \"product\" group by \"product_class_id\" ";
    final String expected = "SELECT SUM(\"net_weight\"), MIN(\"low_fat\"),"
        + " COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithMultipleAggregateFunction1() {
    String query = "select \"product_class_id\","
        + " sum(\"net_weight\"), min(\"low_fat\"), count(*)"
        + " from \"product\" group by \"product_class_id\" ";
    final String expected = "SELECT \"product_class_id\","
        + " SUM(\"net_weight\"), MIN(\"low_fat\"), COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithGroupByAndProjectList() {
    String query = "select \"product_class_id\", \"product_id\", count(*) "
        + "from \"product\" group by \"product_class_id\", \"product_id\"  ";
    final String expected = "SELECT \"product_class_id\", \"product_id\","
        + " COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\", \"product_id\"";
    sql(query).ok(expected);
  }

  @Test public void testCastDecimal1() {
    final String query = "select -0.0000000123\n"
        + " from \"expense_fact\"";
    final String expected = "SELECT -1.23E-8\n"
        + "FROM \"foodmart\".\"expense_fact\"";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2713">[CALCITE-2713]
   * JDBC adapter may generate casts on PostgreSQL for VARCHAR type exceeding
   * max length</a>. */
  @Test public void testCastLongVarchar1() {
    final String query = "select cast(\"store_id\" as VARCHAR(10485761))\n"
        + " from \"expense_fact\"";
    final String expectedPostgreSQL = "SELECT CAST(\"store_id\" AS VARCHAR(256))\n"
        + "FROM \"foodmart\".\"expense_fact\"";
    sql(query)
        .withPostgresqlModifiedTypeSystem()
        .ok(expectedPostgreSQL);

    final String expectedOracle = "SELECT CAST(\"store_id\" AS VARCHAR(512))\n"
        + "FROM \"foodmart\".\"expense_fact\"";
    sql(query)
        .withOracleModifiedTypeSystem()
        .ok(expectedOracle);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2713">[CALCITE-2713]
   * JDBC adapter may generate casts on PostgreSQL for VARCHAR type exceeding
   * max length</a>. */
  @Test public void testCastLongVarchar2() {
    final String query = "select cast(\"store_id\" as VARCHAR(175))\n"
        + " from \"expense_fact\"";
    final String expectedPostgreSQL = "SELECT CAST(\"store_id\" AS VARCHAR(175))\n"
        + "FROM \"foodmart\".\"expense_fact\"";
    sql(query)
        .withPostgresqlModifiedTypeSystem()
        .ok(expectedPostgreSQL);

    final String expectedOracle = "SELECT CAST(\"store_id\" AS VARCHAR(175))\n"
        + "FROM \"foodmart\".\"expense_fact\"";
    sql(query)
        .withOracleModifiedTypeSystem()
        .ok(expectedOracle);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1174">[CALCITE-1174]
   * When generating SQL, translate SUM0(x) to COALESCE(SUM(x), 0)</a>. */
  @Test public void testSum0BecomesCoalesce() {
    final RelBuilder builder = relBuilder();
    final RelNode root = builder
        .scan("EMP")
        .aggregate(builder.groupKey(),
            builder.aggregateCall(SqlStdOperatorTable.SUM0, builder.field(3))
                .as("s"))
        .build();
    final String expectedMysql = "SELECT COALESCE(SUM(`MGR`), 0) AS `s`\n"
        + "FROM `scott`.`EMP`";
    assertThat(toSql(root, SqlDialect.DatabaseProduct.MYSQL.getDialect()),
        isLinux(expectedMysql));
    final String expectedPostgresql = "SELECT COALESCE(SUM(\"MGR\"), 0) AS \"s\"\n"
        + "FROM \"scott\".\"EMP\"";
    assertThat(toSql(root, SqlDialect.DatabaseProduct.POSTGRESQL.getDialect()),
        isLinux(expectedPostgresql));
  }

  /** As {@link #testSum0BecomesCoalesce()} but for windowed aggregates. */
  @Test public void testWindowedSum0BecomesCoalesce() {
    final String query = "select\n"
        + "  AVG(\"net_weight\") OVER (order by \"product_id\" rows 3 preceding)\n"
        + "from \"foodmart\".\"product\"";
    final String expectedPostgresql = "SELECT CASE WHEN (COUNT(\"net_weight\")"
        + " OVER (ORDER BY \"product_id\" ROWS BETWEEN 3 PRECEDING AND CURRENT ROW)) > 0 "
        + "THEN COALESCE(SUM(\"net_weight\")"
        + " OVER (ORDER BY \"product_id\" ROWS BETWEEN 3 PRECEDING AND CURRENT ROW), 0)"
        + " ELSE NULL END / (COUNT(\"net_weight\")"
        + " OVER (ORDER BY \"product_id\" ROWS BETWEEN 3 PRECEDING AND CURRENT ROW))\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query)
        .withPostgresql()
        .ok(expectedPostgresql);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2722">[CALCITE-2722]
   * SqlImplementor createLeftCall method throws StackOverflowError</a>. */
  @Test public void testStack() {
    final RelBuilder builder = relBuilder();
    final RelNode root = builder
        .scan("EMP")
        .filter(
            builder.or(
                IntStream.range(1, 10000)
                    .mapToObj(i -> builder.equals(builder.field("EMPNO"), builder.literal(i)))
                    .collect(Collectors.toList())))
        .build();
    final SqlDialect dialect = SqlDialect.DatabaseProduct.CALCITE.getDialect();
    final SqlNode sqlNode = new RelToSqlConverter(dialect)
        .visitChild(0, root).asStatement();
    final String sqlString = sqlNode.accept(new SqlShuttle())
        .toSqlString(dialect).getSql();
    assertThat(sqlString, notNullValue());
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2792">[CALCITE-2792]
   * Stackoverflow while evaluating filter with large number of OR conditions</a>. */
  @Test public void testBalancedBinaryCall() {
    final RelBuilder builder = relBuilder();
    final RelNode root = builder
        .scan("EMP")
        .filter(
            builder.and(
                builder.or(
                    IntStream.range(0, 4)
                        .mapToObj(i -> builder.equals(builder.field("EMPNO"), builder.literal(i)))
                        .collect(Collectors.toList())),
                builder.or(
                    IntStream.range(5, 8)
                        .mapToObj(i -> builder.equals(builder.field("DEPTNO"), builder.literal(i)))
                        .collect(Collectors.toList()))))
        .build();
    final String expected =
        "(\"EMPNO\" = 0 OR \"EMPNO\" = 1 OR (\"EMPNO\" = 2 OR \"EMPNO\" = 3))"
        + " AND (\"DEPTNO\" = 5 OR (\"DEPTNO\" = 6 OR \"DEPTNO\" = 7))";
    assertTrue(toSql(root).contains(expected));
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1946">[CALCITE-1946]
   * JDBC adapter should generate sub-SELECT if dialect does not support nested
   * aggregate functions</a>. */
  @Test public void testNestedAggregates() {
    // PostgreSQL, MySQL, Vertica do not support nested aggregate functions, so
    // for these, the JDBC adapter generates a SELECT in the FROM clause.
    // Oracle can do it in a single SELECT.
    final String query = "select\n"
        + "    SUM(\"net_weight1\") as \"net_weight_converted\"\n"
        + "  from ("
        + "    select\n"
        + "       SUM(\"net_weight\") as \"net_weight1\"\n"
        + "    from \"foodmart\".\"product\"\n"
        + "    group by \"product_id\")";
    final String expectedOracle = "SELECT SUM(SUM(\"net_weight\")) \"net_weight_converted\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_id\"";
    final String expectedMySQL = "SELECT SUM(`net_weight1`) AS `net_weight_converted`\n"
        + "FROM (SELECT SUM(`net_weight`) AS `net_weight1`\n"
        + "FROM `foodmart`.`product`\n"
        + "GROUP BY `product_id`) AS `t1`";
    final String expectedPostgresql = "SELECT SUM(\"net_weight1\") AS \"net_weight_converted\"\n"
        + "FROM (SELECT SUM(\"net_weight\") AS \"net_weight1\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_id\") AS \"t1\"";
    final String expectedVertica = expectedPostgresql;
    final String expectedBigQuery = "SELECT SUM(net_weight1) AS net_weight_converted\n"
        + "FROM (SELECT SUM(net_weight) AS net_weight1\n"
        + "FROM foodmart.product\n"
        + "GROUP BY product_id) AS t1";
    final String expectedHive = "SELECT SUM(net_weight1) net_weight_converted\n"
        + "FROM (SELECT SUM(net_weight) net_weight1\n"
        + "FROM foodmart.product\n"
        + "GROUP BY product_id) t1";
    final String expectedSpark = expectedHive;
    sql(query)
        .withOracle()
        .ok(expectedOracle)
        .withMysql()
        .ok(expectedMySQL)
        .withVertica()
        .ok(expectedVertica)
        .withPostgresql()
        .ok(expectedPostgresql)
        .withBigQuery()
        .ok(expectedBigQuery)
        .withHive()
        .ok(expectedHive)
        .withSpark()
        .ok(expectedSpark);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2628">[CALCITE-2628]
   * JDBC adapter throws NullPointerException while generating GROUP BY query
   * for MySQL</a>.
   *
   * <p>MySQL does not support nested aggregates, so {@link RelToSqlConverter}
   * performs some extra checks, looking for aggregates in the input
   * sub-query, and these would fail with {@code NullPointerException}
   * and {@code ClassCastException} in some cases. */
  @Test public void testNestedAggregatesMySqlTable() {
    final RelBuilder builder = relBuilder();
    final RelNode root = builder
        .scan("EMP")
        .aggregate(builder.groupKey(),
            builder.count(false, "c", builder.field(3)))
        .build();
    final SqlDialect dialect = SqlDialect.DatabaseProduct.MYSQL.getDialect();
    final String expectedSql = "SELECT COUNT(`MGR`) AS `c`\n"
        + "FROM `scott`.`EMP`";
    assertThat(toSql(root, dialect), isLinux(expectedSql));
  }

  /** As {@link #testNestedAggregatesMySqlTable()}, but input is a sub-query,
   * not a table. */
  @Test public void testNestedAggregatesMySqlStar() {
    final RelBuilder builder = relBuilder();
    final RelNode root = builder
        .scan("EMP")
        .filter(builder.equals(builder.field("DEPTNO"), builder.literal(10)))
        .aggregate(builder.groupKey(),
            builder.count(false, "c", builder.field(3)))
        .build();
    final SqlDialect dialect = SqlDialect.DatabaseProduct.MYSQL.getDialect();
    final String expectedSql = "SELECT COUNT(`MGR`) AS `c`\n"
        + "FROM `scott`.`EMP`\n"
        + "WHERE `DEPTNO` = 10";
    assertThat(toSql(root, dialect), isLinux(expectedSql));
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3207">[CALCITE-3207]
   * Fail to convert Join RelNode with like condition to sql statement </a>.
   */
  @Test public void testJoinWithLikeConditionRel2Sql() {
    final RelBuilder builder = relBuilder();
    final RelNode rel = builder
        .scan("EMP")
        .scan("DEPT")
        .join(JoinRelType.LEFT,
            builder.and(
                builder.call(SqlStdOperatorTable.EQUALS,
                    builder.field(2, 0, "DEPTNO"),
                    builder.field(2, 1, "DEPTNO")),
            builder.call(SqlStdOperatorTable.LIKE,
                builder.field(2, 1, "DNAME"),
                builder.literal("ACCOUNTING"))))
        .build();
    final String sql = toSql(rel);
    final String expectedSql = "SELECT *\n"
        + "FROM \"scott\".\"EMP\"\n"
        + "LEFT JOIN \"scott\".\"DEPT\" "
        + "ON \"EMP\".\"DEPTNO\" = \"DEPT\".\"DEPTNO\" "
        + "AND \"DEPT\".\"DNAME\" LIKE 'ACCOUNTING'";
    assertThat(sql, isLinux(expectedSql));
  }

  @Test public void testSelectQueryWithGroupByAndProjectList1() {
    String query =
        "select count(*)  from \"product\" group by \"product_class_id\", \"product_id\"";

    final String expected = "SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\", \"product_id\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithGroupByHaving() {
    String query = "select count(*) from \"product\" group by \"product_class_id\","
        + " \"product_id\"  having \"product_id\"  > 10";
    final String expected = "SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\", \"product_id\"\n"
        + "HAVING \"product_id\" > 10";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1665">[CALCITE-1665]
   * Aggregates and having cannot be combined</a>. */
  @Test public void testSelectQueryWithGroupByHaving2() {
    String query = " select \"product\".\"product_id\",\n"
        + "    min(\"sales_fact_1997\".\"store_id\")\n"
        + "    from \"product\"\n"
        + "    inner join \"sales_fact_1997\"\n"
        + "    on \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
        + "    group by \"product\".\"product_id\"\n"
        + "    having count(*) > 1";

    String expected = "SELECT \"product\".\"product_id\", "
        + "MIN(\"sales_fact_1997\".\"store_id\")\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "INNER JOIN \"foodmart\".\"sales_fact_1997\" "
        + "ON \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
        + "GROUP BY \"product\".\"product_id\"\n"
        + "HAVING COUNT(*) > 1";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1665">[CALCITE-1665]
   * Aggregates and having cannot be combined</a>. */
  @Test public void testSelectQueryWithGroupByHaving3() {
    String query = " select * from (select \"product\".\"product_id\",\n"
        + "    min(\"sales_fact_1997\".\"store_id\")\n"
        + "    from \"product\"\n"
        + "    inner join \"sales_fact_1997\"\n"
        + "    on \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
        + "    group by \"product\".\"product_id\"\n"
        + "    having count(*) > 1) where \"product_id\" > 100";

    String expected = "SELECT *\n"
        + "FROM (SELECT \"product\".\"product_id\", MIN(\"sales_fact_1997\".\"store_id\")\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "INNER JOIN \"foodmart\".\"sales_fact_1997\" ON \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
        + "GROUP BY \"product\".\"product_id\"\n"
        + "HAVING COUNT(*) > 1) AS \"t2\"\n"
        + "WHERE \"t2\".\"product_id\" > 100";
    sql(query).ok(expected);
  }

  @Test public void testHaving4() {
    final String query = "select \"product_id\"\n"
        + "from (\n"
        + "  select \"product_id\", avg(\"gross_weight\") as agw\n"
        + "  from \"product\"\n"
        + "  where \"net_weight\" < 100\n"
        + "  group by \"product_id\")\n"
        + "where agw > 50\n"
        + "group by \"product_id\"\n"
        + "having avg(agw) > 60\n";
    final String expected = "SELECT \"product_id\"\n"
        + "FROM (SELECT \"product_id\", AVG(\"gross_weight\") AS \"AGW\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"net_weight\" < 100\n"
        + "GROUP BY \"product_id\"\n"
        + "HAVING AVG(\"gross_weight\") > 50) AS \"t2\"\n"
        + "GROUP BY \"product_id\"\n"
        + "HAVING AVG(\"AGW\") > 60";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithOrderByClause() {
    String query = "select \"product_id\"  from \"product\" order by \"net_weight\"";
    final String expected = "SELECT \"product_id\", \"net_weight\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"net_weight\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithOrderByClause1() {
    String query =
        "select \"product_id\", \"net_weight\" from \"product\" order by \"net_weight\"";
    final String expected = "SELECT \"product_id\", \"net_weight\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"net_weight\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithTwoOrderByClause() {
    String query =
        "select \"product_id\"  from \"product\" order by \"net_weight\", \"gross_weight\"";
    final String expected = "SELECT \"product_id\", \"net_weight\","
        + " \"gross_weight\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"net_weight\", \"gross_weight\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithAscDescOrderByClause() {
    String query = "select \"product_id\" from \"product\" "
        + "order by \"net_weight\" asc, \"gross_weight\" desc, \"low_fat\"";
    final String expected = "SELECT"
        + " \"product_id\", \"net_weight\", \"gross_weight\", \"low_fat\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"net_weight\", \"gross_weight\" DESC, \"low_fat\"";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3440">[CALCITE-3440]
   * RelToSqlConverter does not properly alias ambiguous ORDER BY</a>. */
  @Test public void testOrderByColumnWithSameNameAsAlias() {
    String query = "select \"product_id\" as \"p\",\n"
        + " \"net_weight\" as \"product_id\"\n"
        + "from \"product\"\n"
        + "order by 1";
    final String expected = "SELECT \"product_id\" AS \"p\","
        + " \"net_weight\" AS \"product_id\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY 1";
    sql(query).ok(expected);
  }

  @Test public void testOrderByColumnWithSameNameAsAlias2() {
    // We use ordinal "2" because the column name "product_id" is obscured
    // by alias "product_id".
    String query = "select \"net_weight\" as \"product_id\",\n"
        + "  \"product_id\" as \"product_id\"\n"
        + "from \"product\"\n"
        + "order by \"product\".\"product_id\"";
    final String expected = "SELECT \"net_weight\" AS \"product_id\","
        + " \"product_id\" AS \"product_id0\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY 2";
    final String expectedMysql = "SELECT `net_weight` AS `product_id`,"
        + " `product_id` AS `product_id0`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL, 2";
    sql(query).ok(expected)
        .withMysql().ok(expectedMysql);
  }

  @Test public void testHiveSelectCharset() {
    String query = "select \"hire_date\", cast(\"hire_date\" as varchar(10)) "
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT hire_date, CAST(hire_date AS VARCHAR(10))\n"
        + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3282">[CALCITE-3282]
   * HiveSqlDialect unparse Interger type as Int in order
   * to be compatible with Hive1.x</a>. */
  @Test public void testHiveCastAsInt() {
    String query = "select cast( cast(\"employee_id\" as varchar) as int) "
        + "from \"foodmart\".\"reserve_employee\" ";
    final String expected = "SELECT CAST(CAST(employee_id AS VARCHAR) AS INT)\n"
        + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testBigQueryCast() {
    String query = "select cast(cast(\"employee_id\" as varchar) as bigint), "
            + "cast(cast(\"employee_id\" as varchar) as smallint), "
            + "cast(cast(\"employee_id\" as varchar) as tinyint), "
            + "cast(cast(\"employee_id\" as varchar) as integer), "
            + "cast(cast(\"employee_id\" as varchar) as float), "
            + "cast(cast(\"employee_id\" as varchar) as char), "
            + "cast(cast(\"employee_id\" as varchar) as binary), "
            + "cast(cast(\"employee_id\" as varchar) as varbinary), "
            + "cast(cast(\"employee_id\" as varchar) as timestamp), "
            + "cast(cast(\"employee_id\" as varchar) as double), "
            + "cast(cast(\"employee_id\" as varchar) as decimal), "
            + "cast(cast(\"employee_id\" as varchar) as date), "
            + "cast(cast(\"employee_id\" as varchar) as time), "
            + "cast(cast(\"employee_id\" as varchar) as boolean) "
            + "from \"foodmart\".\"reserve_employee\" ";
    final String expected = "SELECT CAST(CAST(employee_id AS STRING) AS INT64), "
            + "CAST(CAST(employee_id AS STRING) AS INT64), "
            + "CAST(CAST(employee_id AS STRING) AS INT64), "
            + "CAST(CAST(employee_id AS STRING) AS INT64), "
            + "CAST(CAST(employee_id AS STRING) AS FLOAT64), "
            + "CAST(CAST(employee_id AS STRING) AS STRING), "
            + "CAST(CAST(employee_id AS STRING) AS BYTES), "
            + "CAST(CAST(employee_id AS STRING) AS BYTES), "
            + "CAST(CAST(employee_id AS STRING) AS TIMESTAMP), "
            + "CAST(CAST(employee_id AS STRING) AS FLOAT64), "
            + "CAST(CAST(employee_id AS STRING) AS NUMERIC), "
            + "CAST(CAST(employee_id AS STRING) AS DATE), "
            + "CAST(CAST(employee_id AS STRING) AS TIME), "
            + "CAST(CAST(employee_id AS STRING) AS BOOL)\n"
            + "FROM foodmart.reserve_employee";
    sql(query).withBigQuery().ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3220">[CALCITE-3220]
   * HiveSqlDialect should transform the SQL-standard TRIM function to TRIM,
   * LTRIM or RTRIM</a>. */
  @Test public void testHiveTrim() {
    final String query = "SELECT TRIM(' str ')\n"
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT TRIM(' str ')\n"
        + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveTrimWithBoth() {
    final String query = "SELECT TRIM(both ' ' from ' str ')\n"
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT TRIM(' str ')\n"
        + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveTrimWithLeading() {
    final String query = "SELECT TRIM(LEADING ' ' from ' str ')\n"
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT LTRIM(' str ')\n"
        + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveTrimWithTailing() {
    final String query = "SELECT TRIM(TRAILING ' ' from ' str ')\n"
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT RTRIM(' str ')\n"
        + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2715">[CALCITE-2715]
   * MS SQL Server does not support character set as part of data type</a>. */
  @Test public void testMssqlCharacterSet() {
    String query = "select \"hire_date\", cast(\"hire_date\" as varchar(10))\n"
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT [hire_date], CAST([hire_date] AS VARCHAR(10))\n"
        + "FROM [foodmart].[reserve_employee]";
    sql(query).withMssql().ok(expected);
  }

  /**
   * Tests that IN can be un-parsed.
   *
   * <p>This cannot be tested using "sql", because because Calcite's SQL parser
   * replaces INs with ORs or sub-queries.
   */
  @Test public void testUnparseIn1() {
    final RelBuilder builder = relBuilder().scan("EMP");
    final RexNode condition =
        builder.call(SqlStdOperatorTable.IN, builder.field("DEPTNO"),
            builder.literal(21));
    final RelNode root = relBuilder().scan("EMP").filter(condition).build();
    final String sql = toSql(root);
    final String expectedSql = "SELECT *\n"
        + "FROM \"scott\".\"EMP\"\n"
        + "WHERE \"DEPTNO\" IN (21)";
    assertThat(sql, isLinux(expectedSql));
  }

  @Test public void testUnparseIn2() {
    final RelBuilder builder = relBuilder();
    final RelNode rel = builder
        .scan("EMP")
        .filter(
            builder.call(SqlStdOperatorTable.IN, builder.field("DEPTNO"),
                builder.literal(20), builder.literal(21)))
        .build();
    final String sql = toSql(rel);
    final String expectedSql = "SELECT *\n"
        + "FROM \"scott\".\"EMP\"\n"
        + "WHERE \"DEPTNO\" IN (20, 21)";
    assertThat(sql, isLinux(expectedSql));
  }

  @Test public void testUnparseInStruct1() {
    final RelBuilder builder = relBuilder().scan("EMP");
    final RexNode condition =
        builder.call(SqlStdOperatorTable.IN,
            builder.call(SqlStdOperatorTable.ROW, builder.field("DEPTNO"),
                builder.field("JOB")),
            builder.call(SqlStdOperatorTable.ROW, builder.literal(1),
                builder.literal("PRESIDENT")));
    final RelNode root = relBuilder().scan("EMP").filter(condition).build();
    final String sql = toSql(root);
    final String expectedSql = "SELECT *\n"
        + "FROM \"scott\".\"EMP\"\n"
        + "WHERE ROW(\"DEPTNO\", \"JOB\") IN (ROW(1, 'PRESIDENT'))";
    assertThat(sql, isLinux(expectedSql));
  }

  @Test public void testUnparseInStruct2() {
    final RelBuilder builder = relBuilder().scan("EMP");
    final RexNode condition =
        builder.call(SqlStdOperatorTable.IN,
            builder.call(SqlStdOperatorTable.ROW, builder.field("DEPTNO"),
                builder.field("JOB")),
            builder.call(SqlStdOperatorTable.ROW, builder.literal(1),
                builder.literal("PRESIDENT")),
            builder.call(SqlStdOperatorTable.ROW, builder.literal(2),
                builder.literal("PRESIDENT")));
    final RelNode root = relBuilder().scan("EMP").filter(condition).build();
    final String sql = toSql(root);
    final String expectedSql = "SELECT *\n"
        + "FROM \"scott\".\"EMP\"\n"
        + "WHERE ROW(\"DEPTNO\", \"JOB\") IN (ROW(1, 'PRESIDENT'), ROW(2, 'PRESIDENT'))";
    assertThat(sql, isLinux(expectedSql));
  }

  @Test public void testSelectQueryWithLimitClause() {
    String query = "select \"product_id\"  from \"product\" limit 100 offset 10";
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "LIMIT 100\nOFFSET 10";
    sql(query).withHive().ok(expected);
  }

  @Test public void testPositionFunctionForHive() {
    final String query = "select position('A' IN 'ABC') from \"product\"";
    final String expected = "SELECT INSTR('ABC', 'A')\n"
        + "FROM foodmart.product";
    sql(query).withHive().ok(expected);
  }

  @Test public void testPositionFunctionForBigQuery() {
    final String query = "select position('A' IN 'ABC') from \"product\"";
    final String expected = "SELECT STRPOS('ABC', 'A')\n"
        + "FROM foodmart.product";
    sql(query).withBigQuery().ok(expected);
  }

  /** Tests that we escape single-quotes in character literals using back-slash
   * in BigQuery. The norm is to escape single-quotes with single-quotes. */
  @Test public void testCharLiteralForBigQuery() {
    final String query = "select 'that''s all folks!' from \"product\"";
    final String expectedPostgresql = "SELECT 'that''s all folks!'\n"
        + "FROM \"foodmart\".\"product\"";
    final String expectedBigQuery = "SELECT 'that\\'s all folks!'\n"
        + "FROM foodmart.product";
    sql(query)
        .withPostgresql().ok(expectedPostgresql)
        .withBigQuery().ok(expectedBigQuery);
  }

  @Test public void testIdentifier() {
    // Note that IGNORE is reserved in BigQuery but not in standard SQL
    final String query = "select *\n"
        + "from (\n"
        + "  select 1 as \"one\", 2 as \"tWo\", 3 as \"THREE\",\n"
        + "    4 as \"fo$ur\", 5 as \"ignore\"\n"
        + "  from \"foodmart\".\"days\") as \"my$table\"\n"
        + "where \"one\" < \"tWo\" and \"THREE\" < \"fo$ur\"";
    final String expectedBigQuery = "SELECT *\n"
        + "FROM (SELECT 1 AS one, 2 AS tWo, 3 AS THREE,"
        + " 4 AS `fo$ur`, 5 AS `ignore`\n"
        + "FROM foodmart.days) AS t\n"
        + "WHERE one < tWo AND THREE < `fo$ur`";
    final String expectedMysql =  "SELECT *\n"
        + "FROM (SELECT 1 AS `one`, 2 AS `tWo`, 3 AS `THREE`,"
        + " 4 AS `fo$ur`, 5 AS `ignore`\n"
        + "FROM `foodmart`.`days`) AS `t`\n"
        + "WHERE `one` < `tWo` AND `THREE` < `fo$ur`";
    final String expectedPostgresql = "SELECT *\n"
        + "FROM (SELECT 1 AS \"one\", 2 AS \"tWo\", 3 AS \"THREE\","
        + " 4 AS \"fo$ur\", 5 AS \"ignore\"\n"
        + "FROM \"foodmart\".\"days\") AS \"t\"\n"
        + "WHERE \"one\" < \"tWo\" AND \"THREE\" < \"fo$ur\"";
    final String expectedOracle = expectedPostgresql.replaceAll(" AS ", " ");
    sql(query)
        .withBigQuery().ok(expectedBigQuery)
        .withMysql().ok(expectedMysql)
        .withOracle().ok(expectedOracle)
        .withPostgresql().ok(expectedPostgresql);
  }

  @Test public void testModFunctionForHive() {
    final String query = "select mod(11,3) from \"product\"";
    final String expected = "SELECT 11 % 3\n"
        + "FROM foodmart.product";
    sql(query).withHive().ok(expected);
  }

  @Test public void testUnionOperatorForBigQuery() {
    final String query = "select mod(11,3) from \"product\"\n"
        + "UNION select 1 from \"product\"";
    final String expected = "SELECT MOD(11, 3)\n"
        + "FROM foodmart.product\n"
        + "UNION DISTINCT\n"
        + "SELECT 1\n"
        + "FROM foodmart.product";
    sql(query).withBigQuery().ok(expected);
  }

  @Test public void testUnionAllOperatorForBigQuery() {
    final String query = "select mod(11,3) from \"product\"\n"
        + "UNION ALL select 1 from \"product\"";
    final String expected = "SELECT MOD(11, 3)\n"
        + "FROM foodmart.product\n"
        + "UNION ALL\n"
        + "SELECT 1\n"
        + "FROM foodmart.product";
    sql(query).withBigQuery().ok(expected);
  }

  @Test public void testIntersectOperatorForBigQuery() {
    final String query = "select mod(11,3) from \"product\"\n"
        + "INTERSECT select 1 from \"product\"";
    final String expected = "SELECT MOD(11, 3)\n"
        + "FROM foodmart.product\n"
        + "INTERSECT DISTINCT\n"
        + "SELECT 1\n"
        + "FROM foodmart.product";
    sql(query).withBigQuery().ok(expected);
  }

  @Test public void testExceptOperatorForBigQuery() {
    final String query = "select mod(11,3) from \"product\"\n"
        + "EXCEPT select 1 from \"product\"";
    final String expected = "SELECT MOD(11, 3)\n"
        + "FROM foodmart.product\n"
        + "EXCEPT DISTINCT\n"
        + "SELECT 1\n"
        + "FROM foodmart.product";
    sql(query).withBigQuery().ok(expected);
  }

  @Test public void testSelectOrderByDescNullsFirst() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    // Hive and MSSQL do not support NULLS FIRST, so need to emulate
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id IS NULL DESC, product_id DESC";
    final String mssqlExpected = "SELECT [product_id]\n"
        + "FROM [foodmart].[product]\n"
        + "ORDER BY CASE WHEN [product_id] IS NULL THEN 0 ELSE 1 END, [product_id] DESC";
    sql(query)
        .dialect(HiveSqlDialect.DEFAULT).ok(expected)
        .dialect(MssqlSqlDialect.DEFAULT).ok(mssqlExpected);
  }

  @Test public void testSelectOrderByAscNullsLast() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls last";
    // Hive and MSSQL do not support NULLS LAST, so need to emulate
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id IS NULL, product_id";
    final String mssqlExpected = "SELECT [product_id]\n"
        + "FROM [foodmart].[product]\n"
        + "ORDER BY CASE WHEN [product_id] IS NULL THEN 1 ELSE 0 END, [product_id]";
    sql(query)
        .dialect(HiveSqlDialect.DEFAULT).ok(expected)
        .dialect(MssqlSqlDialect.DEFAULT).ok(mssqlExpected);
  }

  @Test public void testSelectOrderByAscNullsFirst() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls first";
    // Hive and MSSQL do not support NULLS FIRST, but nulls sort low, so no
    // need to emulate
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id";
    final String mssqlExpected = "SELECT [product_id]\n"
        + "FROM [foodmart].[product]\n"
        + "ORDER BY [product_id]";
    sql(query)
        .dialect(HiveSqlDialect.DEFAULT).ok(expected)
        .dialect(MssqlSqlDialect.DEFAULT).ok(mssqlExpected);
  }

  @Test public void testSelectOrderByDescNullsLast() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls last";
    // Hive and MSSQL do not support NULLS LAST, but nulls sort low, so no
    // need to emulate
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id DESC";
    final String mssqlExpected = "SELECT [product_id]\n"
        + "FROM [foodmart].[product]\n"
        + "ORDER BY [product_id] DESC";
    sql(query)
        .dialect(HiveSqlDialect.DEFAULT).ok(expected)
        .dialect(MssqlSqlDialect.DEFAULT).ok(mssqlExpected);
  }

  @Test public void testHiveSelectQueryWithOverDescAndNullsFirstShouldBeEmulated() {
    final String query = "SELECT row_number() over "
        + "(order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
        + "OVER (ORDER BY hire_date IS NULL DESC, hire_date DESC)\n"
        + "FROM foodmart.employee";
    sql(query).dialect(HiveSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testHiveSelectQueryWithOverAscAndNullsLastShouldBeEmulated() {
    final String query = "SELECT row_number() over "
        + "(order by \"hire_date\" nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY hire_date IS NULL, hire_date)\n"
        + "FROM foodmart.employee";
    sql(query).dialect(HiveSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testHiveSelectQueryWithOverAscNullsFirstShouldNotAddNullEmulation() {
    final String query = "SELECT row_number() over "
        + "(order by \"hire_date\" nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY hire_date)\n"
        + "FROM foodmart.employee";
    sql(query).dialect(HiveSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testHiveSubstring() {
    String query = "SELECT SUBSTRING('ABC', 2)"
            + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT SUBSTRING('ABC', 2)\n"
            + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveSubstringWithLength() {
    String query = "SELECT SUBSTRING('ABC', 2, 3)"
            + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT SUBSTRING('ABC', 2, 3)\n"
            + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveSubstringWithANSI() {
    String query = "SELECT SUBSTRING('ABC' FROM 2)"
            + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT SUBSTRING('ABC', 2)\n"
            + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveSubstringWithANSIAndLength() {
    String query = "SELECT SUBSTRING('ABC' FROM 2 FOR 3)"
            + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT SUBSTRING('ABC', 2, 3)\n"
            + "FROM foodmart.reserve_employee";
    sql(query).withHive().ok(expected);
  }

  @Test public void testHiveSelectQueryWithOverDescNullsLastShouldNotAddNullEmulation() {
    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" desc nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY hire_date DESC)\n"
            + "FROM foodmart.employee";
    sql(query).dialect(HiveSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMysqlCastToBigint() {
    // MySQL does not allow cast to BIGINT; instead cast to SIGNED.
    final String query = "select cast(\"product_id\" as bigint) from \"product\"";
    final String expected = "SELECT CAST(`product_id` AS SIGNED)\n"
        + "FROM `foodmart`.`product`";
    sql(query).withMysql().ok(expected);
  }


  @Test public void testMysqlCastToInteger() {
    // MySQL does not allow cast to INTEGER; instead cast to SIGNED.
    final String query = "select \"employee_id\",\n"
        + "  cast(\"salary_paid\" * 10000 as integer)\n"
        + "from \"salary\"";
    final String expected = "SELECT `employee_id`,"
        + " CAST(`salary_paid` * 10000 AS SIGNED)\n"
        + "FROM `foodmart`.`salary`";
    sql(query).withMysql().ok(expected);
  }

  @Test public void testHiveSelectQueryWithOrderByDescAndHighNullsWithVersionGreaterThanOrEq21() {
    final HiveSqlDialect hive2_1Dialect =
        new HiveSqlDialect(HiveSqlDialect.DEFAULT_CONTEXT
            .withDatabaseMajorVersion(2)
            .withDatabaseMinorVersion(1)
            .withNullCollation(NullCollation.LOW));

    final HiveSqlDialect hive2_2_Dialect =
        new HiveSqlDialect(HiveSqlDialect.DEFAULT_CONTEXT
            .withDatabaseMajorVersion(2)
            .withDatabaseMinorVersion(2)
            .withNullCollation(NullCollation.LOW));

    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id DESC NULLS FIRST";
    sql(query).dialect(hive2_1Dialect).ok(expected);
    sql(query).dialect(hive2_2_Dialect).ok(expected);
  }

  @Test public void testHiveSelectQueryWithOverDescAndHighNullsWithVersionGreaterThanOrEq21() {
    final HiveSqlDialect hive2_1Dialect =
            new HiveSqlDialect(SqlDialect.EMPTY_CONTEXT
                    .withDatabaseMajorVersion(2)
                    .withDatabaseMinorVersion(1)
                    .withNullCollation(NullCollation.LOW));

    final HiveSqlDialect hive2_2_Dialect =
            new HiveSqlDialect(SqlDialect.EMPTY_CONTEXT
                    .withDatabaseMajorVersion(2)
                    .withDatabaseMinorVersion(2)
                    .withNullCollation(NullCollation.LOW));

    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY hire_date DESC NULLS FIRST)\n"
            + "FROM foodmart.employee";
    sql(query).dialect(hive2_1Dialect).ok(expected);
    sql(query).dialect(hive2_2_Dialect).ok(expected);
  }

  @Test public void testHiveSelectQueryWithOrderByDescAndHighNullsWithVersion20() {
    final HiveSqlDialect hive2_1_0_Dialect =
        new HiveSqlDialect(HiveSqlDialect.DEFAULT_CONTEXT
            .withDatabaseMajorVersion(2)
            .withDatabaseMinorVersion(0)
            .withNullCollation(NullCollation.LOW));
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    final String expected = "SELECT product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id IS NULL DESC, product_id DESC";
    sql(query).dialect(hive2_1_0_Dialect).ok(expected);
  }

  @Test public void testHiveSelectQueryWithOverDescAndHighNullsWithVersion20() {
    final HiveSqlDialect hive2_1_0_Dialect =
            new HiveSqlDialect(SqlDialect.EMPTY_CONTEXT
                    .withDatabaseMajorVersion(2)
                    .withDatabaseMinorVersion(0)
                    .withNullCollation(NullCollation.LOW));
    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER "
            + "(ORDER BY hire_date IS NULL DESC, hire_date DESC)\n"
            + "FROM foodmart.employee";
    sql(query).dialect(hive2_1_0_Dialect).ok(expected);
  }

  @Test public void testJethroDataSelectQueryWithOrderByDescAndNullsFirstShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";

    final String expected = "SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"product_id\", \"product_id\" DESC";
    sql(query).dialect(jethroDataSqlDialect()).ok(expected);
  }

  @Test public void testJethroDataSelectQueryWithOverDescAndNullsFirstShouldBeEmulated() {
    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" desc nulls first) FROM \"employee\"";

    final String expected = "SELECT ROW_NUMBER() OVER "
            + "(ORDER BY \"hire_date\", \"hire_date\" DESC)\n"
            + "FROM \"foodmart\".\"employee\"";
    sql(query).dialect(jethroDataSqlDialect()).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOrderByDescAndNullsFirstShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL DESC, `product_id` DESC";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOverDescAndNullsFirstShouldBeEmulated() {
    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER "
            + "(ORDER BY `hire_date` IS NULL DESC, `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOrderByAscAndNullsLastShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL, `product_id`";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOverAscAndNullsLastShouldBeEmulated() {
    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER "
            + "(ORDER BY `hire_date` IS NULL, `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOrderByAscNullsFirstShouldNotAddNullEmulation() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id`";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOverAscNullsFirstShouldNotAddNullEmulation() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOrderByDescNullsLastShouldNotAddNullEmulation() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` DESC";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlSelectQueryWithOverDescNullsLastShouldNotAddNullEmulation() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(MysqlSqlDialect.DEFAULT).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOrderByAscNullsLastAndNoEmulation() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id`";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOverAscNullsLastAndNoEmulation() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOrderByAscNullsFirstAndNullEmulation() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL DESC, `product_id`";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOverAscNullsFirstAndNullEmulation() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
            + "OVER (ORDER BY `hire_date` IS NULL DESC, `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOrderByDescNullsFirstAndNoEmulation() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` DESC";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOverDescNullsFirstAndNoEmulation() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOrderByDescNullsLastAndNullEmulation() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL, `product_id` DESC";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithHighNullsSelectWithOverDescNullsLastAndNullEmulation() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
            + "OVER (ORDER BY `hire_date` IS NULL, `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.HIGH)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOrderByDescAndNullsFirstShouldNotBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` DESC";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOverDescAndNullsFirstShouldNotBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOrderByAscAndNullsFirstShouldNotBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id`";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOverAscAndNullsFirstShouldNotBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOrderByDescAndNullsLastShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL, `product_id` DESC";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOverDescAndNullsLastShouldBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
            + "OVER (ORDER BY `hire_date` IS NULL, `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOrderByAscAndNullsLastShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL, `product_id`";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithFirstNullsSelectWithOverAscAndNullsLastShouldBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
            + "OVER (ORDER BY `hire_date` IS NULL, `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.FIRST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOrderByDescAndNullsFirstShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL DESC, `product_id` DESC";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOverDescAndNullsFirstShouldBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
            + "OVER (ORDER BY `hire_date` IS NULL DESC, `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOrderByAscAndNullsFirstShouldBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls first";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` IS NULL DESC, `product_id`";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOverAscAndNullsFirstShouldBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" nulls first) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() "
            + "OVER (ORDER BY `hire_date` IS NULL DESC, `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOrderByDescAndNullsLastShouldNotBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" desc nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id` DESC";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOverDescAndNullsLastShouldNotBeEmulated() {
    final String query = "SELECT row_number() "
            + "over (order by \"hire_date\" desc nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date` DESC)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOrderByAscAndNullsLastShouldNotBeEmulated() {
    final String query = "select \"product_id\" from \"product\"\n"
        + "order by \"product_id\" nulls last";
    final String expected = "SELECT `product_id`\n"
        + "FROM `foodmart`.`product`\n"
        + "ORDER BY `product_id`";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testMySqlWithLastNullsSelectWithOverAscAndNullsLastShouldNotBeEmulated() {
    final String query = "SELECT row_number() over "
            + "(order by \"hire_date\" nulls last) FROM \"employee\"";
    final String expected = "SELECT ROW_NUMBER() OVER (ORDER BY `hire_date`)\n"
            + "FROM `foodmart`.`employee`";
    sql(query).dialect(mySqlDialect(NullCollation.LAST)).ok(expected);
  }

  @Test public void testSelectQueryWithLimitClauseWithoutOrder() {
    String query = "select \"product_id\"  from \"product\" limit 100 offset 10";
    final String expected = "SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "OFFSET 10 ROWS\n"
        + "FETCH NEXT 100 ROWS ONLY";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithLimitOffsetClause() {
    String query = "select \"product_id\"  from \"product\" order by \"net_weight\" asc"
        + " limit 100 offset 10";
    final String expected = "SELECT \"product_id\", \"net_weight\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"net_weight\"\n"
        + "OFFSET 10 ROWS\n"
        + "FETCH NEXT 100 ROWS ONLY";
    // BigQuery uses LIMIT/OFFSET, and nulls sort low by default
    final String expectedBigQuery = "SELECT product_id, net_weight\n"
        + "FROM foodmart.product\n"
        + "ORDER BY net_weight IS NULL, net_weight\n"
        + "LIMIT 100\n"
        + "OFFSET 10";
    sql(query).ok(expected)
        .withBigQuery().ok(expectedBigQuery);
  }

  @Test public void testSelectQueryWithParameters() {
    String query = "select * from \"product\" "
        + "where \"product_id\" = ? "
        + "AND ? >= \"shelf_width\"";
    final String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_id\" = ? "
        + "AND ? >= \"shelf_width\"";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithFetchOffsetClause() {
    String query = "select \"product_id\"  from \"product\" order by \"product_id\""
        + " offset 10 rows fetch next 100 rows only";
    final String expected = "SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"product_id\"\n"
        + "OFFSET 10 ROWS\n"
        + "FETCH NEXT 100 ROWS ONLY";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithFetchClause() {
    String query = "select \"product_id\"\n"
        + "from \"product\"\n"
        + "order by \"product_id\" fetch next 100 rows only";
    final String expected = "SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "ORDER BY \"product_id\"\n"
        + "FETCH NEXT 100 ROWS ONLY";
    final String expectedMssql10 = "SELECT TOP (100) [product_id]\n"
        + "FROM [foodmart].[product]\n"
        + "ORDER BY CASE WHEN [product_id] IS NULL THEN 1 ELSE 0 END, [product_id]";
    final String expectedMssql = "SELECT [product_id]\n"
        + "FROM [foodmart].[product]\n"
        + "ORDER BY CASE WHEN [product_id] IS NULL THEN 1 ELSE 0 END, [product_id]\n"
        + "FETCH NEXT 100 ROWS ONLY";
    final String expectedSybase = "SELECT TOP (100) product_id\n"
        + "FROM foodmart.product\n"
        + "ORDER BY product_id";
    sql(query).ok(expected)
        .withMssql(10).ok(expectedMssql10)
        .withMssql(11).ok(expectedMssql)
        .withMssql(14).ok(expectedMssql)
        .withSybase().ok(expectedSybase);
  }

  @Test public void testSelectQueryComplex() {
    String query =
        "select count(*), \"units_per_case\" from \"product\" where \"cases_per_pallet\" > 100 "
            + "group by \"product_id\", \"units_per_case\" order by \"units_per_case\" desc";
    final String expected = "SELECT COUNT(*), \"units_per_case\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"cases_per_pallet\" > 100\n"
        + "GROUP BY \"product_id\", \"units_per_case\"\n"
        + "ORDER BY \"units_per_case\" DESC";
    sql(query).ok(expected);
  }

  @Test public void testSelectQueryWithGroup() {
    String query = "select"
        + " count(*), sum(\"employee_id\") from \"reserve_employee\" "
        + "where \"hire_date\" > '2015-01-01' "
        + "and (\"position_title\" = 'SDE' or \"position_title\" = 'SDM') "
        + "group by \"store_id\", \"position_title\"";
    final String expected = "SELECT COUNT(*), SUM(\"employee_id\")\n"
        + "FROM \"foodmart\".\"reserve_employee\"\n"
        + "WHERE \"hire_date\" > '2015-01-01' "
        + "AND (\"position_title\" = 'SDE' OR \"position_title\" = 'SDM')\n"
        + "GROUP BY \"store_id\", \"position_title\"";
    sql(query).ok(expected);
  }

  @Test public void testSimpleJoin() {
    String query = "select *\n"
        + "from \"sales_fact_1997\" as s\n"
        + "join \"customer\" as c on s.\"customer_id\" = c.\"customer_id\"\n"
        + "join \"product\" as p on s.\"product_id\" = p.\"product_id\"\n"
        + "join \"product_class\" as pc\n"
        + "  on p.\"product_class_id\" = pc.\"product_class_id\"\n"
        + "where c.\"city\" = 'San Francisco'\n"
        + "and pc.\"product_department\" = 'Snacks'\n";
    final String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "INNER JOIN \"foodmart\".\"customer\" "
        + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\""
        + ".\"customer_id\"\n"
        + "INNER JOIN \"foodmart\".\"product\" "
        + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\"\n"
        + "INNER JOIN \"foodmart\".\"product_class\" "
        + "ON \"product\".\"product_class_id\" = \"product_class\""
        + ".\"product_class_id\"\n"
        + "WHERE \"customer\".\"city\" = 'San Francisco' AND "
        + "\"product_class\".\"product_department\" = 'Snacks'";
    sql(query).ok(expected);
  }

  @Test public void testSimpleJoinUsing() {
    String query = "select *\n"
        + "from \"sales_fact_1997\" as s\n"
        + "  join \"customer\" as c using (\"customer_id\")\n"
        + "  join \"product\" as p using (\"product_id\")\n"
        + "  join \"product_class\" as pc using (\"product_class_id\")\n"
        + "where c.\"city\" = 'San Francisco'\n"
        + "and pc.\"product_department\" = 'Snacks'\n";
    final String expected = "SELECT"
        + " \"product\".\"product_class_id\","
        + " \"sales_fact_1997\".\"product_id\","
        + " \"sales_fact_1997\".\"customer_id\","
        + " \"sales_fact_1997\".\"time_id\","
        + " \"sales_fact_1997\".\"promotion_id\","
        + " \"sales_fact_1997\".\"store_id\","
        + " \"sales_fact_1997\".\"store_sales\","
        + " \"sales_fact_1997\".\"store_cost\","
        + " \"sales_fact_1997\".\"unit_sales\","
        + " \"customer\".\"account_num\","
        + " \"customer\".\"lname\","
        + " \"customer\".\"fname\","
        + " \"customer\".\"mi\","
        + " \"customer\".\"address1\","
        + " \"customer\".\"address2\","
        + " \"customer\".\"address3\","
        + " \"customer\".\"address4\","
        + " \"customer\".\"city\","
        + " \"customer\".\"state_province\","
        + " \"customer\".\"postal_code\","
        + " \"customer\".\"country\","
        + " \"customer\".\"customer_region_id\","
        + " \"customer\".\"phone1\","
        + " \"customer\".\"phone2\","
        + " \"customer\".\"birthdate\","
        + " \"customer\".\"marital_status\","
        + " \"customer\".\"yearly_income\","
        + " \"customer\".\"gender\","
        + " \"customer\".\"total_children\","
        + " \"customer\".\"num_children_at_home\","
        + " \"customer\".\"education\","
        + " \"customer\".\"date_accnt_opened\","
        + " \"customer\".\"member_card\","
        + " \"customer\".\"occupation\","
        + " \"customer\".\"houseowner\","
        + " \"customer\".\"num_cars_owned\","
        + " \"customer\".\"fullname\","
        + " \"product\".\"brand_name\","
        + " \"product\".\"product_name\","
        + " \"product\".\"SKU\","
        + " \"product\".\"SRP\","
        + " \"product\".\"gross_weight\","
        + " \"product\".\"net_weight\","
        + " \"product\".\"recyclable_package\","
        + " \"product\".\"low_fat\","
        + " \"product\".\"units_per_case\","
        + " \"product\".\"cases_per_pallet\","
        + " \"product\".\"shelf_width\","
        + " \"product\".\"shelf_height\","
        + " \"product\".\"shelf_depth\","
        + " \"product_class\".\"product_subcategory\","
        + " \"product_class\".\"product_category\","
        + " \"product_class\".\"product_department\","
        + " \"product_class\".\"product_family\"\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "INNER JOIN \"foodmart\".\"customer\" "
        + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\""
        + ".\"customer_id\"\n"
        + "INNER JOIN \"foodmart\".\"product\" "
        + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\"\n"
        + "INNER JOIN \"foodmart\".\"product_class\" "
        + "ON \"product\".\"product_class_id\" = \"product_class\""
        + ".\"product_class_id\"\n"
        + "WHERE \"customer\".\"city\" = 'San Francisco' AND "
        + "\"product_class\".\"product_department\" = 'Snacks'";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1636">[CALCITE-1636]
   * JDBC adapter generates wrong SQL for self join with sub-query</a>. */
  @Test public void testSubQueryAlias() {
    String query = "select t1.\"customer_id\", t2.\"customer_id\" \n"
        + "from (select \"customer_id\" from \"sales_fact_1997\") as t1 \n"
        + "inner join (select \"customer_id\" from \"sales_fact_1997\") t2 \n"
        + "on t1.\"customer_id\" = t2.\"customer_id\"";
    final String expected = "SELECT *\n"
        + "FROM (SELECT sales_fact_1997.customer_id\n"
        + "FROM foodmart.sales_fact_1997 AS sales_fact_1997) AS t\n"
        + "INNER JOIN (SELECT sales_fact_19970.customer_id\n"
        + "FROM foodmart.sales_fact_1997 AS sales_fact_19970) AS t0 ON t.customer_id = t0.customer_id";

    sql(query).withDb2().ok(expected);
  }

  @Test public void testCartesianProductWithCommaSyntax() {
    String query = "select * from \"department\" , \"employee\"";
    String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"department\",\n"
        + "\"foodmart\".\"employee\"";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2652">[CALCITE-2652]
   * SqlNode to SQL conversion fails if the join condition references a BOOLEAN
   * column</a>. */
  @Test public void testJoinOnBoolean() {
    final String sql = "SELECT 1\n"
        + "from emps\n"
        + "join emp on (emp.deptno = emps.empno and manager)";
    final String s = sql(sql).schema(CalciteAssert.SchemaSpec.POST).exec();
    assertThat(s, notNullValue()); // sufficient that conversion did not throw
  }

  @Test public void testCartesianProductWithInnerJoinSyntax() {
    String query = "select * from \"department\"\n"
        + "INNER JOIN \"employee\" ON TRUE";
    String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"department\",\n"
        + "\"foodmart\".\"employee\"";
    sql(query).ok(expected);
  }

  @Test public void testFullJoinOnTrueCondition() {
    String query = "select * from \"department\"\n"
        + "FULL JOIN \"employee\" ON TRUE";
    String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"department\"\n"
        + "FULL JOIN \"foodmart\".\"employee\" ON TRUE";
    sql(query).ok(expected);
  }

  @Test public void testSimpleIn() {
    String query = "select * from \"department\" where \"department_id\" in (\n"
        + "  select \"department_id\" from \"employee\"\n"
        + "  where \"store_id\" < 150)";
    final String expected = "SELECT "
        + "\"department\".\"department_id\", \"department\""
        + ".\"department_description\"\n"
        + "FROM \"foodmart\".\"department\"\nINNER JOIN "
        + "(SELECT \"department_id\"\nFROM \"foodmart\".\"employee\"\n"
        + "WHERE \"store_id\" < 150\nGROUP BY \"department_id\") AS \"t1\" "
        + "ON \"department\".\"department_id\" = \"t1\".\"department_id\"";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1332">[CALCITE-1332]
   * DB2 should always use aliases for tables: x.y.z AS z</a>. */
  @Test public void testDb2DialectJoinStar() {
    String query = "select * "
        + "from \"foodmart\".\"employee\" A "
        + "join \"foodmart\".\"department\" B\n"
        + "on A.\"department_id\" = B.\"department_id\"";
    final String expected = "SELECT *\n"
        + "FROM foodmart.employee AS employee\n"
        + "INNER JOIN foodmart.department AS department "
        + "ON employee.department_id = department.department_id";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectSelfJoinStar() {
    String query = "select * "
        + "from \"foodmart\".\"employee\" A join \"foodmart\".\"employee\" B\n"
        + "on A.\"department_id\" = B.\"department_id\"";
    final String expected = "SELECT *\n"
        + "FROM foodmart.employee AS employee\n"
        + "INNER JOIN foodmart.employee AS employee0 "
        + "ON employee.department_id = employee0.department_id";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectJoin() {
    String query = "select A.\"employee_id\", B.\"department_id\" "
        + "from \"foodmart\".\"employee\" A join \"foodmart\".\"department\" B\n"
        + "on A.\"department_id\" = B.\"department_id\"";
    final String expected = "SELECT"
        + " employee.employee_id, department.department_id\n"
        + "FROM foodmart.employee AS employee\n"
        + "INNER JOIN foodmart.department AS department "
        + "ON employee.department_id = department.department_id";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectSelfJoin() {
    String query = "select A.\"employee_id\", B.\"employee_id\" from "
        + "\"foodmart\".\"employee\" A join \"foodmart\".\"employee\" B\n"
        + "on A.\"department_id\" = B.\"department_id\"";
    final String expected = "SELECT"
        + " employee.employee_id, employee0.employee_id AS employee_id0\n"
        + "FROM foodmart.employee AS employee\n"
        + "INNER JOIN foodmart.employee AS employee0 "
        + "ON employee.department_id = employee0.department_id";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectWhere() {
    String query = "select A.\"employee_id\" from "
        + "\"foodmart\".\"employee\" A where A.\"department_id\" < 1000";
    final String expected = "SELECT employee.employee_id\n"
        + "FROM foodmart.employee AS employee\n"
        + "WHERE employee.department_id < 1000";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectJoinWhere() {
    String query = "select A.\"employee_id\", B.\"department_id\" "
        + "from \"foodmart\".\"employee\" A join \"foodmart\".\"department\" B\n"
        + "on A.\"department_id\" = B.\"department_id\" "
        + "where A.\"employee_id\" < 1000";
    final String expected = "SELECT"
        + " employee.employee_id, department.department_id\n"
        + "FROM foodmart.employee AS employee\n"
        + "INNER JOIN foodmart.department AS department "
        + "ON employee.department_id = department.department_id\n"
        + "WHERE employee.employee_id < 1000";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectSelfJoinWhere() {
    String query = "select A.\"employee_id\", B.\"employee_id\" from "
        + "\"foodmart\".\"employee\" A join \"foodmart\".\"employee\" B\n"
        + "on A.\"department_id\" = B.\"department_id\" "
        + "where B.\"employee_id\" < 2000";
    final String expected = "SELECT "
        + "employee.employee_id, employee0.employee_id AS employee_id0\n"
        + "FROM foodmart.employee AS employee\n"
        + "INNER JOIN foodmart.employee AS employee0 "
        + "ON employee.department_id = employee0.department_id\n"
        + "WHERE employee0.employee_id < 2000";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectCast() {
    String query = "select \"hire_date\", cast(\"hire_date\" as varchar(10)) "
        + "from \"foodmart\".\"reserve_employee\"";
    final String expected = "SELECT reserve_employee.hire_date, "
        + "CAST(reserve_employee.hire_date AS VARCHAR(10))\n"
        + "FROM foodmart.reserve_employee AS reserve_employee";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectSelectQueryWithGroupByHaving() {
    String query = "select count(*) from \"product\" "
        + "group by \"product_class_id\", \"product_id\" "
        + "having \"product_id\"  > 10";
    final String expected = "SELECT COUNT(*)\n"
        + "FROM foodmart.product AS product\n"
        + "GROUP BY product.product_class_id, product.product_id\n"
        + "HAVING product.product_id > 10";
    sql(query).withDb2().ok(expected);
  }


  @Test public void testDb2DialectSelectQueryComplex() {
    String query = "select count(*), \"units_per_case\" "
        + "from \"product\" where \"cases_per_pallet\" > 100 "
        + "group by \"product_id\", \"units_per_case\" "
        + "order by \"units_per_case\" desc";
    final String expected = "SELECT COUNT(*), product.units_per_case\n"
        + "FROM foodmart.product AS product\n"
        + "WHERE product.cases_per_pallet > 100\n"
        + "GROUP BY product.product_id, product.units_per_case\n"
        + "ORDER BY product.units_per_case DESC";
    sql(query).withDb2().ok(expected);
  }

  @Test public void testDb2DialectSelectQueryWithGroup() {
    String query = "select count(*), sum(\"employee_id\") "
        + "from \"reserve_employee\" "
        + "where \"hire_date\" > '2015-01-01' "
        + "and (\"position_title\" = 'SDE' or \"position_title\" = 'SDM') "
        + "group by \"store_id\", \"position_title\"";
    final String expected = "SELECT"
        + " COUNT(*), SUM(reserve_employee.employee_id)\n"
        + "FROM foodmart.reserve_employee AS reserve_employee\n"
        + "WHERE reserve_employee.hire_date > '2015-01-01' "
        + "AND (reserve_employee.position_title = 'SDE' OR "
        + "reserve_employee.position_title = 'SDM')\n"
        + "GROUP BY reserve_employee.store_id, reserve_employee.position_title";
    sql(query).withDb2().ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1372">[CALCITE-1372]
   * JDBC adapter generates SQL with wrong field names</a>. */
  @Test public void testJoinPlan2() {
    final String sql = "SELECT v1.deptno, v2.deptno\n"
        + "FROM dept v1 LEFT JOIN emp v2 ON v1.deptno = v2.deptno\n"
        + "WHERE v2.job LIKE 'PRESIDENT'";
    final String expected = "SELECT \"DEPT\".\"DEPTNO\","
        + " \"EMP\".\"DEPTNO\" AS \"DEPTNO0\"\n"
        + "FROM \"SCOTT\".\"DEPT\"\n"
        + "LEFT JOIN \"SCOTT\".\"EMP\""
        + " ON \"DEPT\".\"DEPTNO\" = \"EMP\".\"DEPTNO\"\n"
        + "WHERE \"EMP\".\"JOB\" LIKE 'PRESIDENT'";
    // DB2 does not have implicit aliases, so generates explicit "AS DEPT"
    // and "AS EMP"
    final String expectedDb2 = "SELECT DEPT.DEPTNO, EMP.DEPTNO AS DEPTNO0\n"
        + "FROM SCOTT.DEPT AS DEPT\n"
        + "LEFT JOIN SCOTT.EMP AS EMP ON DEPT.DEPTNO = EMP.DEPTNO\n"
        + "WHERE EMP.JOB LIKE 'PRESIDENT'";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .ok(expected)
        .withDb2()
        .ok(expectedDb2);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1422">[CALCITE-1422]
   * In JDBC adapter, allow IS NULL and IS NOT NULL operators in generated SQL
   * join condition</a>. */
  @Test public void testSimpleJoinConditionWithIsNullOperators() {
    String query = "select *\n"
        + "from \"foodmart\".\"sales_fact_1997\" as \"t1\"\n"
        + "inner join \"foodmart\".\"customer\" as \"t2\"\n"
        + "on \"t1\".\"customer_id\" = \"t2\".\"customer_id\" or "
        + "(\"t1\".\"customer_id\" is null "
        + "and \"t2\".\"customer_id\" is null) or\n"
        + "\"t2\".\"occupation\" is null\n"
        + "inner join \"foodmart\".\"product\" as \"t3\"\n"
        + "on \"t1\".\"product_id\" = \"t3\".\"product_id\" or "
        + "(\"t1\".\"product_id\" is not null or "
        + "\"t3\".\"product_id\" is not null)";
    // Some of the "IS NULL" and "IS NOT NULL" are reduced to TRUE or FALSE,
    // but not all.
    String expected = "SELECT *\nFROM \"foodmart\".\"sales_fact_1997\"\n"
        + "INNER JOIN \"foodmart\".\"customer\" "
        + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\".\"customer_id\""
        + " OR FALSE AND FALSE"
        + " OR \"customer\".\"occupation\" IS NULL\n"
        + "INNER JOIN \"foodmart\".\"product\" "
        + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\""
        + " OR TRUE"
        + " OR TRUE";
    sql(query).ok(expected);
  }


  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1586">[CALCITE-1586]
   * JDBC adapter generates wrong SQL if UNION has more than two inputs</a>. */
  @Test public void testThreeQueryUnion() {
    String query = "SELECT \"product_id\" FROM \"product\" "
        + " UNION ALL "
        + "SELECT \"product_id\" FROM \"sales_fact_1997\" "
        + " UNION ALL "
        + "SELECT \"product_class_id\" AS product_id FROM \"product_class\"";
    String expected = "SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "UNION ALL\n"
        + "SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "UNION ALL\n"
        + "SELECT \"product_class_id\" AS \"PRODUCT_ID\"\n"
        + "FROM \"foodmart\".\"product_class\"";

    final RuleSet rules = RuleSets.ofList(UnionMergeRule.INSTANCE);
    sql(query)
        .optimize(rules, null)
        .ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1800">[CALCITE-1800]
   * JDBC adapter fails to SELECT FROM a UNION query</a>. */
  @Test public void testUnionWrappedInASelect() {
    final String query = "select sum(\n"
        + "  case when \"product_id\"=0 then \"net_weight\" else 0 end)"
        + " as net_weight\n"
        + "from (\n"
        + "  select \"product_id\", \"net_weight\"\n"
        + "  from \"product\"\n"
        + "  union all\n"
        + "  select \"product_id\", 0 as \"net_weight\"\n"
        + "  from \"sales_fact_1997\") t0";
    final String expected = "SELECT SUM(CASE WHEN \"product_id\" = 0"
        + " THEN \"net_weight\" ELSE 0 END) AS \"NET_WEIGHT\"\n"
        + "FROM (SELECT \"product_id\", \"net_weight\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "UNION ALL\n"
        + "SELECT \"product_id\", 0 AS \"net_weight\"\n"
        + "FROM \"foodmart\".\"sales_fact_1997\") AS \"t1\"";
    sql(query).ok(expected);
  }

  @Test public void testLiteral() {
    checkLiteral("DATE '1978-05-02'");
    checkLiteral2("DATE '1978-5-2'", "DATE '1978-05-02'");
    checkLiteral("TIME '12:34:56'");
    checkLiteral("TIME '12:34:56.78'");
    checkLiteral2("TIME '1:4:6.080'", "TIME '01:04:06.080'");
    checkLiteral("TIMESTAMP '1978-05-02 12:34:56.78'");
    checkLiteral2("TIMESTAMP '1978-5-2 2:4:6.80'",
        "TIMESTAMP '1978-05-02 02:04:06.80'");
    checkLiteral("'I can''t explain'");
    checkLiteral("''");
    checkLiteral("TRUE");
    checkLiteral("123");
    checkLiteral("123.45");
    checkLiteral("-123.45");
    checkLiteral("INTERVAL '1-2' YEAR TO MONTH");
    checkLiteral("INTERVAL -'1-2' YEAR TO MONTH");
    checkLiteral("INTERVAL '12-11' YEAR TO MONTH");
    checkLiteral("INTERVAL '1' YEAR");
    checkLiteral("INTERVAL '1' MONTH");
    checkLiteral("INTERVAL '12' DAY");
    checkLiteral("INTERVAL -'12' DAY");
    checkLiteral2("INTERVAL '1 2' DAY TO HOUR",
        "INTERVAL '1 02' DAY TO HOUR");
    checkLiteral2("INTERVAL '1 2:10' DAY TO MINUTE",
        "INTERVAL '1 02:10' DAY TO MINUTE");
    checkLiteral2("INTERVAL '1 2:00' DAY TO MINUTE",
        "INTERVAL '1 02:00' DAY TO MINUTE");
    checkLiteral2("INTERVAL '1 2:34:56' DAY TO SECOND",
        "INTERVAL '1 02:34:56' DAY TO SECOND");
    checkLiteral2("INTERVAL '1 2:34:56.789' DAY TO SECOND",
        "INTERVAL '1 02:34:56.789' DAY TO SECOND");
    checkLiteral2("INTERVAL '1 2:34:56.78' DAY TO SECOND",
        "INTERVAL '1 02:34:56.78' DAY TO SECOND");
    checkLiteral2("INTERVAL '1 2:34:56.078' DAY TO SECOND",
        "INTERVAL '1 02:34:56.078' DAY TO SECOND");
    checkLiteral2("INTERVAL -'1 2:34:56.078' DAY TO SECOND",
        "INTERVAL -'1 02:34:56.078' DAY TO SECOND");
    checkLiteral2("INTERVAL '1 2:3:5.070' DAY TO SECOND",
        "INTERVAL '1 02:03:05.07' DAY TO SECOND");
    checkLiteral("INTERVAL '1:23' HOUR TO MINUTE");
    checkLiteral("INTERVAL '1:02' HOUR TO MINUTE");
    checkLiteral("INTERVAL -'1:02' HOUR TO MINUTE");
    checkLiteral("INTERVAL '1:23:45' HOUR TO SECOND");
    checkLiteral("INTERVAL '1:03:05' HOUR TO SECOND");
    checkLiteral("INTERVAL '1:23:45.678' HOUR TO SECOND");
    checkLiteral("INTERVAL '1:03:05.06' HOUR TO SECOND");
    checkLiteral("INTERVAL '12' MINUTE");
    checkLiteral("INTERVAL '12:34' MINUTE TO SECOND");
    checkLiteral("INTERVAL '12:34.567' MINUTE TO SECOND");
    checkLiteral("INTERVAL '12' SECOND");
    checkLiteral("INTERVAL '12.345' SECOND");
  }

  private void checkLiteral(String expression) {
    checkLiteral2(expression, expression);
  }

  private void checkLiteral2(String expression, String expected) {
    sql("VALUES " + expression)
        .withHsqldb()
        .ok("SELECT *\n"
            + "FROM (VALUES  (" + expected + ")) AS t (EXPR$0)");
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2625">[CALCITE-2625]
   * Removing Window Boundaries from SqlWindow of Aggregate Function which do not allow Framing</a>
   * */
  @Test public void testRowNumberFunctionForPrintingOfFrameBoundary() {
    String query = "SELECT row_number() over (order by \"hire_date\") FROM \"employee\"";
    String expected = "SELECT ROW_NUMBER() OVER (ORDER BY \"hire_date\")\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3112">[CALCITE-3112]
   * Support Window in RelToSqlConverter</a>. */
  @Test public void testConvertWinodwToSql() {
    String query0 = "SELECT row_number() over (order by \"hire_date\") FROM \"employee\"";
    String expected0 = "SELECT ROW_NUMBER() OVER (ORDER BY \"hire_date\") AS \"$0\"\n"
            + "FROM \"foodmart\".\"employee\"";

    String query1 = "SELECT rank() over (order by \"hire_date\") FROM \"employee\"";
    String expected1 = "SELECT RANK() OVER (ORDER BY \"hire_date\") AS \"$0\"\n"
            + "FROM \"foodmart\".\"employee\"";

    String query2 = "SELECT lead(\"employee_id\",1,'NA') over "
            + "(partition by \"hire_date\" order by \"employee_id\")\n"
            + "FROM \"employee\"";
    String expected2 = "SELECT LEAD(\"employee_id\", 1, 'NA') OVER "
            + "(PARTITION BY \"hire_date\" "
            + "ORDER BY \"employee_id\") AS \"$0\"\n"
            + "FROM \"foodmart\".\"employee\"";

    String query3 = "SELECT lag(\"employee_id\",1,'NA') over "
            + "(partition by \"hire_date\" order by \"employee_id\")\n"
            + "FROM \"employee\"";
    String expected3 = "SELECT LAG(\"employee_id\", 1, 'NA') OVER "
            + "(PARTITION BY \"hire_date\" ORDER BY \"employee_id\") AS \"$0\"\n"
            + "FROM \"foodmart\".\"employee\"";

    String query4 = "SELECT lag(\"employee_id\",1,'NA') "
            + "over (partition by \"hire_date\" order by \"employee_id\") as lag1, "
            + "lag(\"employee_id\",1,'NA') "
            + "over (partition by \"birth_date\" order by \"employee_id\") as lag2, "
            + "count(*) over (partition by \"hire_date\" order by \"employee_id\") as count1, "
            + "count(*) over (partition by \"birth_date\" order by \"employee_id\") as count2\n"
            + "FROM \"employee\"";
    String expected4 = "SELECT LAG(\"employee_id\", 1, 'NA') OVER "
            + "(PARTITION BY \"hire_date\" ORDER BY \"employee_id\") AS \"$0\", "
            + "LAG(\"employee_id\", 1, 'NA') OVER "
            + "(PARTITION BY \"birth_date\" ORDER BY \"employee_id\") AS \"$1\", "
            + "COUNT(*) OVER (PARTITION BY \"hire_date\" ORDER BY \"employee_id\" "
            + "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS \"$2\", "
            + "COUNT(*) OVER (PARTITION BY \"birth_date\" ORDER BY \"employee_id\" "
            + "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS \"$3\"\n"
            + "FROM \"foodmart\".\"employee\"";

    String query5 = "SELECT lag(\"employee_id\",1,'NA') "
            + "over (partition by \"hire_date\" order by \"employee_id\") as lag1, "
            + "lag(\"employee_id\",1,'NA') "
            + "over (partition by \"birth_date\" order by \"employee_id\") as lag2, "
            + "max(sum(\"employee_id\")) over (partition by \"hire_date\" order by \"employee_id\") as count1, "
            + "max(sum(\"employee_id\")) over (partition by \"birth_date\" order by \"employee_id\") as count2\n"
            + "FROM \"employee\" group by \"employee_id\", \"hire_date\", \"birth_date\"";
    String expected5 = "SELECT LAG(\"employee_id\", 1, 'NA') OVER "
            + "(PARTITION BY \"hire_date\" ORDER BY \"employee_id\") AS \"$0\", "
            + "LAG(\"employee_id\", 1, 'NA') OVER "
            + "(PARTITION BY \"birth_date\" ORDER BY \"employee_id\") AS \"$1\", "
            + "MAX(SUM(\"employee_id\")) OVER (PARTITION BY \"hire_date\" ORDER BY \"employee_id\" "
            + "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS \"$2\", "
            + "MAX(SUM(\"employee_id\")) OVER (PARTITION BY \"birth_date\" ORDER BY \"employee_id\" "
            + "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS \"$3\"\n"
            + "FROM \"foodmart\".\"employee\"\n"
            + "GROUP BY \"employee_id\", \"hire_date\", \"birth_date\"";

    String query6 = "SELECT lag(\"employee_id\",1,'NA') over "
            + "(partition by \"hire_date\" order by \"employee_id\"), \"hire_date\"\n"
            + "FROM \"employee\"\n"
            + "group by \"hire_date\", \"employee_id\"";
    String expected6 = "SELECT LAG(\"employee_id\", 1, 'NA') "
            + "OVER (PARTITION BY \"hire_date\" ORDER BY \"employee_id\"), \"hire_date\"\n"
            + "FROM \"foodmart\".\"employee\"\n"
            + "GROUP BY \"hire_date\", \"employee_id\"";

    HepProgramBuilder builder = new HepProgramBuilder();
    builder.addRuleClass(ProjectToWindowRule.class);
    HepPlanner hepPlanner = new HepPlanner(builder.build());
    RuleSet rules = RuleSets.ofList(ProjectToWindowRule.PROJECT);

    sql(query0).optimize(rules, hepPlanner).ok(expected0);
    sql(query1).optimize(rules, hepPlanner).ok(expected1);
    sql(query2).optimize(rules, hepPlanner).ok(expected2);
    sql(query3).optimize(rules, hepPlanner).ok(expected3);
    sql(query4).optimize(rules, hepPlanner).ok(expected4);
    sql(query5).optimize(rules, hepPlanner).ok(expected5);
    sql(query6).optimize(rules, hepPlanner).ok(expected6);
  }

  @Test public void testRankFunctionForPrintingOfFrameBoundary() {
    String query = "SELECT rank() over (order by \"hire_date\") FROM \"employee\"";
    String expected = "SELECT RANK() OVER (ORDER BY \"hire_date\")\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query).ok(expected);
  }

  @Test public void testLeadFunctionForPrintingOfFrameBoundary() {
    String query = "SELECT lead(\"employee_id\",1,'NA') over "
        + "(partition by \"hire_date\" order by \"employee_id\") FROM \"employee\"";
    String expected = "SELECT LEAD(\"employee_id\", 1, 'NA') OVER "
        + "(PARTITION BY \"hire_date\" ORDER BY \"employee_id\")\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query).ok(expected);
  }

  @Test public void testLagFunctionForPrintingOfFrameBoundary() {
    String query = "SELECT lag(\"employee_id\",1,'NA') over "
        + "(partition by \"hire_date\" order by \"employee_id\") FROM \"employee\"";
    String expected = "SELECT LAG(\"employee_id\", 1, 'NA') OVER "
        + "(PARTITION BY \"hire_date\" ORDER BY \"employee_id\")\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1798">[CALCITE-1798]
   * Generate dialect-specific SQL for FLOOR operator</a>. */
  @Test public void testFloor() {
    String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
    String expected = "SELECT TRUNC(hire_date, 'MI')\nFROM foodmart.employee";
    sql(query)
        .withHsqldb()
        .ok(expected);
  }

  @Test public void testFloorPostgres() {
    String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
    String expected = "SELECT DATE_TRUNC('MINUTE', \"hire_date\")\nFROM \"foodmart\".\"employee\"";
    sql(query)
        .withPostgresql()
        .ok(expected);
  }

  @Test public void testFloorOracle() {
    String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
    String expected = "SELECT TRUNC(\"hire_date\", 'MINUTE')\nFROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testFloorMssqlWeek() {
    String query = "SELECT floor(\"hire_date\" TO WEEK) FROM \"employee\"";
    String expected = "SELECT CONVERT(DATETIME, CONVERT(VARCHAR(10), "
        + "DATEADD(day, - (6 + DATEPART(weekday, [hire_date] )) % 7, [hire_date] ), 126))\n"
        + "FROM [foodmart].[employee]";
    sql(query)
        .withMssql()
        .ok(expected);
  }

  @Test public void testFloorMssqlMonth() {
    String query = "SELECT floor(\"hire_date\" TO MONTH) FROM \"employee\"";
    String expected = "SELECT CONVERT(DATETIME, CONVERT(VARCHAR(7), [hire_date] , 126)+'-01')\n"
        + "FROM [foodmart].[employee]";
    sql(query)
        .withMssql()
        .ok(expected);
  }

  @Test public void testFloorMysqlMonth() {
    String query = "SELECT floor(\"hire_date\" TO MONTH) FROM \"employee\"";
    String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-01')\n"
        + "FROM `foodmart`.`employee`";
    sql(query)
        .withMysql()
        .ok(expected);
  }

  @Test public void testUnparseSqlIntervalQualifierDb2() {
    String queryDatePlus = "select  * from \"employee\" where  \"hire_date\" + "
        + "INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
    String expectedDatePlus = "SELECT *\n"
        + "FROM foodmart.employee AS employee\n"
        + "WHERE (employee.hire_date + 19800 SECOND)"
        + " > TIMESTAMP '2005-10-17 00:00:00'";

    sql(queryDatePlus)
        .withDb2()
        .ok(expectedDatePlus);

    String queryDateMinus = "select  * from \"employee\" where  \"hire_date\" - "
        + "INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
    String expectedDateMinus = "SELECT *\n"
        + "FROM foodmart.employee AS employee\n"
        + "WHERE (employee.hire_date - 19800 SECOND)"
        + " > TIMESTAMP '2005-10-17 00:00:00'";

    sql(queryDateMinus)
        .withDb2()
        .ok(expectedDateMinus);
  }

  @Test public void testUnparseSqlIntervalQualifierMySql() {
    final String sql0 = "select  * from \"employee\" where  \"hire_date\" - "
        + "INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
    final String expect0 = "SELECT *\n"
        + "FROM `foodmart`.`employee`\n"
        + "WHERE (`hire_date` - INTERVAL '19800' SECOND)"
        + " > TIMESTAMP '2005-10-17 00:00:00'";
    sql(sql0).withMysql().ok(expect0);

    final String sql1 = "select  * from \"employee\" where  \"hire_date\" + "
        + "INTERVAL '10' HOUR > TIMESTAMP '2005-10-17 00:00:00' ";
    final String expect1 = "SELECT *\n"
        + "FROM `foodmart`.`employee`\n"
        + "WHERE (`hire_date` + INTERVAL '10' HOUR)"
        + " > TIMESTAMP '2005-10-17 00:00:00'";
    sql(sql1).withMysql().ok(expect1);

    final String sql2 = "select  * from \"employee\" where  \"hire_date\" + "
        + "INTERVAL '1-2' year to month > TIMESTAMP '2005-10-17 00:00:00' ";
    final String expect2 = "SELECT *\n"
        + "FROM `foodmart`.`employee`\n"
        + "WHERE (`hire_date` + INTERVAL '1-2' YEAR_MONTH)"
        + " > TIMESTAMP '2005-10-17 00:00:00'";
    sql(sql2).withMysql().ok(expect2);

    final String sql3 = "select  * from \"employee\" "
        + "where  \"hire_date\" + INTERVAL '39:12' MINUTE TO SECOND"
        + " > TIMESTAMP '2005-10-17 00:00:00' ";
    final String expect3 = "SELECT *\n"
        + "FROM `foodmart`.`employee`\n"
        + "WHERE (`hire_date` + INTERVAL '39:12' MINUTE_SECOND)"
        + " > TIMESTAMP '2005-10-17 00:00:00'";
    sql(sql3).withMysql().ok(expect3);
  }

  @Test public void testUnparseSqlIntervalQualifierMsSql() {
    String queryDatePlus = "select  * from \"employee\" where  \"hire_date\" +"
        + "INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
    String expectedDatePlus = "SELECT *\n"
        + "FROM [foodmart].[employee]\n"
        + "WHERE DATEADD(SECOND, 19800, [hire_date]) > '2005-10-17 00:00:00'";

    sql(queryDatePlus)
        .withMssql()
        .ok(expectedDatePlus);

    String queryDateMinus = "select  * from \"employee\" where  \"hire_date\" -"
        + "INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
    String expectedDateMinus = "SELECT *\n"
        + "FROM [foodmart].[employee]\n"
        + "WHERE DATEADD(SECOND, -19800, [hire_date]) > '2005-10-17 00:00:00'";

    sql(queryDateMinus)
        .withMssql()
        .ok(expectedDateMinus);

    String queryDateMinusNegate = "select  * from \"employee\" "
        + "where  \"hire_date\" -INTERVAL '-19800' SECOND(5)"
        + " > TIMESTAMP '2005-10-17 00:00:00' ";
    String expectedDateMinusNegate = "SELECT *\n"
        + "FROM [foodmart].[employee]\n"
        + "WHERE DATEADD(SECOND, 19800, [hire_date]) > '2005-10-17 00:00:00'";

    sql(queryDateMinusNegate)
        .withMssql()
        .ok(expectedDateMinusNegate);
  }

  @Test public void testUnparseSqlIntervalQualifierBigQuery() {
    final String sql0 = "select  * from \"employee\" where  \"hire_date\" - "
            + "INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
    final String expect0 = "SELECT *\n"
            + "FROM foodmart.employee\n"
            + "WHERE (hire_date - INTERVAL 19800 SECOND)"
            + " > TIMESTAMP '2005-10-17 00:00:00'";
    sql(sql0).withBigQuery().ok(expect0);

    final String sql1 = "select  * from \"employee\" where  \"hire_date\" + "
            + "INTERVAL '10' HOUR > TIMESTAMP '2005-10-17 00:00:00' ";
    final String expect1 = "SELECT *\n"
            + "FROM foodmart.employee\n"
            + "WHERE (hire_date + INTERVAL 10 HOUR)"
            + " > TIMESTAMP '2005-10-17 00:00:00'";
    sql(sql1).withBigQuery().ok(expect1);

    final String sql2 = "select  * from \"employee\" where  \"hire_date\" + "
            + "INTERVAL '1 2:34:56.78' DAY TO SECOND > TIMESTAMP '2005-10-17 00:00:00' ";
    sql(sql2).withBigQuery().throws_("Only INT64 is supported as the interval value for BigQuery.");
  }

  @Test public void testFloorMysqlWeek() {
    String query = "SELECT floor(\"hire_date\" TO WEEK) FROM \"employee\"";
    String expected = "SELECT STR_TO_DATE(DATE_FORMAT(`hire_date` , '%x%v-1'), '%x%v-%w')\n"
        + "FROM `foodmart`.`employee`";
    sql(query)
        .withMysql()
        .ok(expected);
  }

  @Test public void testFloorMysqlHour() {
    String query = "SELECT floor(\"hire_date\" TO HOUR) FROM \"employee\"";
    String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:00:00')\n"
        + "FROM `foodmart`.`employee`";
    sql(query)
        .withMysql()
        .ok(expected);
  }

  @Test public void testFloorMysqlMinute() {
    String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
    String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:00')\n"
        + "FROM `foodmart`.`employee`";
    sql(query)
        .withMysql()
        .ok(expected);
  }

  @Test public void testFloorMysqlSecond() {
    String query = "SELECT floor(\"hire_date\" TO SECOND) FROM \"employee\"";
    String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:%s')\n"
        + "FROM `foodmart`.`employee`";
    sql(query)
        .withMysql()
        .ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1826">[CALCITE-1826]
   * JDBC dialect-specific FLOOR fails when in GROUP BY</a>. */
  @Test public void testFloorWithGroupBy() {
    final String query = "SELECT floor(\"hire_date\" TO MINUTE)\n"
        + "FROM \"employee\"\n"
        + "GROUP BY floor(\"hire_date\" TO MINUTE)";
    final String expected = "SELECT TRUNC(hire_date, 'MI')\n"
        + "FROM foodmart.employee\n"
        + "GROUP BY TRUNC(hire_date, 'MI')";
    final String expectedOracle = "SELECT TRUNC(\"hire_date\", 'MINUTE')\n"
        + "FROM \"foodmart\".\"employee\"\n"
        + "GROUP BY TRUNC(\"hire_date\", 'MINUTE')";
    final String expectedPostgresql = "SELECT DATE_TRUNC('MINUTE', \"hire_date\")\n"
        + "FROM \"foodmart\".\"employee\"\n"
        + "GROUP BY DATE_TRUNC('MINUTE', \"hire_date\")";
    final String expectedMysql = "SELECT"
        + " DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:00')\n"
        + "FROM `foodmart`.`employee`\n"
        + "GROUP BY DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:00')";
    sql(query)
        .withHsqldb()
        .ok(expected)
        .withOracle()
        .ok(expectedOracle)
        .withPostgresql()
        .ok(expectedPostgresql)
        .withMysql()
        .ok(expectedMysql);
  }

  @Test public void testSubstring() {
    final String query = "select substring(\"brand_name\" from 2) "
        + "from \"product\"\n";
    final String expectedOracle = "SELECT SUBSTR(\"brand_name\", 2)\n"
        + "FROM \"foodmart\".\"product\"";
    final String expectedPostgresql = "SELECT SUBSTRING(\"brand_name\" FROM 2)\n"
        + "FROM \"foodmart\".\"product\"";
    final String expectedSnowflake = expectedPostgresql;
    final String expectedRedshift = expectedPostgresql;
    final String expectedMysql = "SELECT SUBSTRING(`brand_name` FROM 2)\n"
        + "FROM `foodmart`.`product`";
    sql(query)
        .withOracle()
        .ok(expectedOracle)
        .withPostgresql()
        .ok(expectedPostgresql)
        .withSnowflake()
        .ok(expectedSnowflake)
        .withRedshift()
        .ok(expectedRedshift)
        .withMysql()
        .ok(expectedMysql)
        .withMssql()
        // mssql does not support this syntax and so should fail
        .throws_("MSSQL SUBSTRING requires FROM and FOR arguments");
  }

  @Test public void testSubstringWithFor() {
    final String query = "select substring(\"brand_name\" from 2 for 3) "
        + "from \"product\"\n";
    final String expectedOracle = "SELECT SUBSTR(\"brand_name\", 2, 3)\n"
        + "FROM \"foodmart\".\"product\"";
    final String expectedPostgresql = "SELECT SUBSTRING(\"brand_name\" FROM 2 FOR 3)\n"
        + "FROM \"foodmart\".\"product\"";
    final String expectedSnowflake = expectedPostgresql;
    final String expectedRedshift = expectedPostgresql;
    final String expectedMysql = "SELECT SUBSTRING(`brand_name` FROM 2 FOR 3)\n"
        + "FROM `foodmart`.`product`";
    final String expectedMssql = "SELECT SUBSTRING([brand_name], 2, 3)\n"
        + "FROM [foodmart].[product]";
    sql(query)
        .withOracle()
        .ok(expectedOracle)
        .withPostgresql()
        .ok(expectedPostgresql)
        .withSnowflake()
        .ok(expectedSnowflake)
        .withRedshift()
        .ok(expectedRedshift)
        .withMysql()
        .ok(expectedMysql)
        .withMssql()
        .ok(expectedMssql);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-1849">[CALCITE-1849]
   * Support sub-queries (RexSubQuery) in RelToSqlConverter</a>. */
  @Test public void testExistsWithExpand() {
    String query = "select \"product_name\" from \"product\" a "
        + "where exists (select count(*) "
        + "from \"sales_fact_1997\"b "
        + "where b.\"product_id\" = a.\"product_id\")";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE EXISTS (SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "WHERE \"product_id\" = \"product\".\"product_id\")";
    sql(query).config(NO_EXPAND_CONFIG).ok(expected);
  }

  @Test public void testNotExistsWithExpand() {
    String query = "select \"product_name\" from \"product\" a "
        + "where not exists (select count(*) "
        + "from \"sales_fact_1997\"b "
        + "where b.\"product_id\" = a.\"product_id\")";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE NOT EXISTS (SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "WHERE \"product_id\" = \"product\".\"product_id\")";
    sql(query).config(NO_EXPAND_CONFIG).ok(expected);
  }

  @Test public void testSubQueryInWithExpand() {
    String query = "select \"product_name\" from \"product\" a "
        + "where \"product_id\" in (select \"product_id\" "
        + "from \"sales_fact_1997\"b "
        + "where b.\"product_id\" = a.\"product_id\")";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_id\" IN (SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "WHERE \"product_id\" = \"product\".\"product_id\")";
    sql(query).config(NO_EXPAND_CONFIG).ok(expected);
  }

  @Test public void testSubQueryInWithExpand2() {
    String query = "select \"product_name\" from \"product\" a "
        + "where \"product_id\" in (1, 2)";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_id\" = 1 OR \"product_id\" = 2";
    sql(query).config(NO_EXPAND_CONFIG).ok(expected);
  }

  @Test public void testSubQueryNotInWithExpand() {
    String query = "select \"product_name\" from \"product\" a "
        + "where \"product_id\" not in (select \"product_id\" "
        + "from \"sales_fact_1997\"b "
        + "where b.\"product_id\" = a.\"product_id\")";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_id\" NOT IN (SELECT \"product_id\"\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "WHERE \"product_id\" = \"product\".\"product_id\")";
    sql(query).config(NO_EXPAND_CONFIG).ok(expected);
  }

  @Test public void testLike() {
    String query = "select \"product_name\" from \"product\" a "
        + "where \"product_name\" like 'abc'";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_name\" LIKE 'abc'";
    sql(query).ok(expected);
  }

  @Test public void testNotLike() {
    String query = "select \"product_name\" from \"product\" a "
        + "where \"product_name\" not like 'abc'";
    String expected = "SELECT \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"product_name\" NOT LIKE 'abc'";
    sql(query).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression() {
    String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    partition by \"product_class_id\", \"brand_name\" \n"
        + "    order by \"product_class_id\" asc, \"brand_name\" desc \n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "PARTITION BY \"product_class_id\", \"brand_name\"\n"
        + "ORDER BY \"product_class_id\", \"brand_name\" DESC\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression2() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+$)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" + $)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression3() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (^strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (^ \"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression4() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (^strt down+ up+$)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (^ \"STRT\" \"DOWN\" + \"UP\" + $)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression5() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down* up?)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" * \"UP\" ?)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression6() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt {-down-} up?)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" {- \"DOWN\" -} \"UP\" ?)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression7() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down{2} up{3,})\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" { 2 } \"UP\" { 3, })\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression8() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down{,2} up{3,5})\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" { , 2 } \"UP\" { 3, 5 })\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression9() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt {-down+-} {-up*-})\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" {- \"DOWN\" + -} {- \"UP\" * -})\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression10() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (A B C | A C B | B A C | B C A | C A B | C B A)\n"
        + "    define\n"
        + "      A as A.\"net_weight\" < PREV(A.\"net_weight\"),\n"
        + "      B as B.\"net_weight\" > PREV(B.\"net_weight\"),\n"
        + "      C as C.\"net_weight\" < PREV(C.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN "
        + "(\"A\" \"B\" \"C\" | \"A\" \"C\" \"B\" | \"B\" \"A\" \"C\" "
        + "| \"B\" \"C\" \"A\" | \"C\" \"A\" \"B\" | \"C\" \"B\" \"A\")\n"
        + "DEFINE "
        + "\"A\" AS PREV(\"A\".\"net_weight\", 0) < PREV(\"A\".\"net_weight\", 1), "
        + "\"B\" AS PREV(\"B\".\"net_weight\", 0) > PREV(\"B\".\"net_weight\", 1), "
        + "\"C\" AS PREV(\"C\".\"net_weight\", 0) < PREV(\"C\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression11() {
    final String sql = "select *\n"
        + "  from (select * from \"product\") match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression12() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr order by MR.\"net_weight\"";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))\n"
        + "ORDER BY \"net_weight\"";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternExpression13() {
    final String sql = "select *\n"
        + "  from (\n"
        + "select *\n"
        + "from \"sales_fact_1997\" as s\n"
        + "join \"customer\" as c\n"
        + "  on s.\"customer_id\" = c.\"customer_id\"\n"
        + "join \"product\" as p\n"
        + "  on s.\"product_id\" = p.\"product_id\"\n"
        + "join \"product_class\" as pc\n"
        + "  on p.\"product_class_id\" = pc.\"product_class_id\"\n"
        + "where c.\"city\" = 'San Francisco'\n"
        + "and pc.\"product_department\" = 'Snacks'"
        + ") match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr order by MR.\"net_weight\"";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "INNER JOIN \"foodmart\".\"customer\" "
        + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\".\"customer_id\"\n"
        + "INNER JOIN \"foodmart\".\"product\" "
        + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\"\n"
        + "INNER JOIN \"foodmart\".\"product_class\" "
        + "ON \"product\".\"product_class_id\" = \"product_class\".\"product_class_id\"\n"
        + "WHERE \"customer\".\"city\" = 'San Francisco' "
        + "AND \"product_class\".\"product_department\" = 'Snacks') "
        + "MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))\n"
        + "ORDER BY \"net_weight\"";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeDefineClause() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeDefineClause2() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < FIRST(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > LAST(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "FIRST(\"DOWN\".\"net_weight\", 0), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "LAST(\"UP\".\"net_weight\", 0))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeDefineClause3() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\",1),\n"
        + "      up as up.\"net_weight\" > LAST(up.\"net_weight\" + up.\"gross_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "LAST(\"UP\".\"net_weight\", 0) + LAST(\"UP\".\"gross_weight\", 0))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeDefineClause4() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\",1),\n"
        + "      up as up.\"net_weight\" > "
        + "PREV(LAST(up.\"net_weight\" + up.\"gross_weight\"),3)\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(LAST(\"UP\".\"net_weight\", 0) + "
        + "LAST(\"UP\".\"gross_weight\", 0), 3))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures1() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures MATCH_NUMBER() as match_num, "
        + "   CLASSIFIER() as var_match, "
        + "   STRT.\"net_weight\" as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   LAST(up.\"net_weight\") as end_nw"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL MATCH_NUMBER () AS \"MATCH_NUM\", "
        + "FINAL CLASSIFIER() AS \"VAR_MATCH\", "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"END_NW\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures2() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   FINAL LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   LAST(up.\"net_weight\") as end_nw"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"END_NW\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures3() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   RUNNING LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   LAST(up.\"net_weight\") as end_nw"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL (RUNNING LAST(\"DOWN\".\"net_weight\", 0)) AS \"BOTTOM_NW\", "
        + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"END_NW\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures4() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   FINAL COUNT(up.\"net_weight\") as up_cnt,"
        + "   FINAL COUNT(\"net_weight\") as down_cnt,"
        + "   RUNNING COUNT(\"net_weight\") as running_cnt"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL COUNT(\"UP\".\"net_weight\") AS \"UP_CNT\", "
        + "FINAL COUNT(\"*\".\"net_weight\") AS \"DOWN_CNT\", "
        + "FINAL (RUNNING COUNT(\"*\".\"net_weight\")) AS \"RUNNING_CNT\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures5() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures "
        + "   FIRST(STRT.\"net_weight\") as start_nw,"
        + "   LAST(UP.\"net_weight\") as up_cnt,"
        + "   AVG(DOWN.\"net_weight\") as down_cnt"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL FIRST(\"STRT\".\"net_weight\", 0) AS \"START_NW\", "
        + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"UP_CNT\", "
        + "FINAL (SUM(\"DOWN\".\"net_weight\") / "
        + "COUNT(\"DOWN\".\"net_weight\")) AS \"DOWN_CNT\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures6() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures "
        + "   FIRST(STRT.\"net_weight\") as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as up_cnt,"
        + "   FINAL SUM(DOWN.\"net_weight\") as down_cnt"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL FIRST(\"STRT\".\"net_weight\", 0) AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"UP_CNT\", "
        + "FINAL SUM(\"DOWN\".\"net_weight\") AS \"DOWN_CNT\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN "
        + "(\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeMeasures7() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures "
        + "   FIRST(STRT.\"net_weight\") as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as up_cnt,"
        + "   FINAL SUM(DOWN.\"net_weight\") as down_cnt"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr order by start_nw, up_cnt";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL FIRST(\"STRT\".\"net_weight\", 0) AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"UP_CNT\", "
        + "FINAL SUM(\"DOWN\".\"net_weight\") AS \"DOWN_CNT\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN "
        + "(\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))\n"
        + "ORDER BY \"START_NW\", \"UP_CNT\"";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternSkip1() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    after match skip to next row\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternSkip2() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    after match skip past last row\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP PAST LAST ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternSkip3() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    after match skip to FIRST down\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO FIRST \"DOWN\"\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE \"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternSkip4() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    after match skip to last down\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO LAST \"DOWN\"\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizePatternSkip5() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    after match skip to down\n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO LAST \"DOWN\"\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeSubset1() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    after match skip to down\n"
        + "    pattern (strt down+ up+)\n"
        + "    subset stdn = (strt, down)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
        + "  ) mr";
    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO LAST \"DOWN\"\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\")\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeSubset2() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   AVG(STDN.\"net_weight\") as avg_stdn"
        + "    pattern (strt down+ up+)\n"
        + "    subset stdn = (strt, down)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "FINAL (SUM(\"STDN\".\"net_weight\") / "
        + "COUNT(\"STDN\".\"net_weight\")) AS \"AVG_STDN\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\")\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeSubset3() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   SUM(STDN.\"net_weight\") as avg_stdn"
        + "    pattern (strt down+ up+)\n"
        + "    subset stdn = (strt, down)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "FINAL SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\")\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeSubset4() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   SUM(STDN.\"net_weight\") as avg_stdn"
        + "    pattern (strt down+ up+)\n"
        + "    subset stdn = (strt, down), stdn2 = (strt, down)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "FINAL SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\"), \"STDN2\" = (\"DOWN\", \"STRT\")\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeRowsPerMatch1() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   SUM(STDN.\"net_weight\") as avg_stdn"
        + "    ONE ROW PER MATCH\n"
        + "    pattern (strt down+ up+)\n"
        + "    subset stdn = (strt, down), stdn2 = (strt, down)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "FINAL SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\"), \"STDN2\" = (\"DOWN\", \"STRT\")\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeRowsPerMatch2() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "   measures STRT.\"net_weight\" as start_nw,"
        + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
        + "   SUM(STDN.\"net_weight\") as avg_stdn"
        + "    ALL ROWS PER MATCH\n"
        + "    pattern (strt down+ up+)\n"
        + "    subset stdn = (strt, down), stdn2 = (strt, down)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") "
        + "MATCH_RECOGNIZE(\n"
        + "MEASURES "
        + "RUNNING \"STRT\".\"net_weight\" AS \"START_NW\", "
        + "RUNNING LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
        + "RUNNING SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
        + "ALL ROWS PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\"), \"STDN2\" = (\"DOWN\", \"STRT\")\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
        + "PREV(\"DOWN\".\"net_weight\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeWithin() {
    final String sql = "select *\n"
        + "  from \"employee\" match_recognize\n"
        + "  (\n"
        + "   order by \"hire_date\"\n"
        + "   ALL ROWS PER MATCH\n"
        + "   pattern (strt down+ up+) within interval '3:12:22.123' hour to second\n"
        + "   define\n"
        + "     down as down.\"salary\" < PREV(down.\"salary\"),\n"
        + "     up as up.\"salary\" > prev(up.\"salary\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"employee\") "
        + "MATCH_RECOGNIZE(\n"
        + "ORDER BY \"hire_date\"\n"
        + "ALL ROWS PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +) WITHIN INTERVAL '3:12:22.123' HOUR TO SECOND\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"salary\", 0) < "
        + "PREV(\"DOWN\".\"salary\", 1), "
        + "\"UP\" AS PREV(\"UP\".\"salary\", 0) > "
        + "PREV(\"UP\".\"salary\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testMatchRecognizeIn() {
    final String sql = "select *\n"
        + "  from \"product\" match_recognize\n"
        + "  (\n"
        + "    partition by \"product_class_id\", \"brand_name\" \n"
        + "    order by \"product_class_id\" asc, \"brand_name\" desc \n"
        + "    pattern (strt down+ up+)\n"
        + "    define\n"
        + "      down as down.\"net_weight\" in (0, 1),\n"
        + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
        + "  ) mr";

    final String expected = "SELECT *\n"
        + "FROM (SELECT *\n"
        + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
        + "PARTITION BY \"product_class_id\", \"brand_name\"\n"
        + "ORDER BY \"product_class_id\", \"brand_name\" DESC\n"
        + "ONE ROW PER MATCH\n"
        + "AFTER MATCH SKIP TO NEXT ROW\n"
        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
        + "DEFINE "
        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) = "
        + "0 OR PREV(\"DOWN\".\"net_weight\", 0) = 1, "
        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
        + "PREV(\"UP\".\"net_weight\", 1))";
    sql(sql).ok(expected);
  }

  @Test public void testValues() {
    final String sql = "select \"a\"\n"
        + "from (values (1, 'x'), (2, 'yy')) as t(\"a\", \"b\")";
    final String expectedHsqldb = "SELECT a\n"
        + "FROM (VALUES  (1, 'x '),\n"
        + " (2, 'yy')) AS t (a, b)";
    final String expectedMysql = "SELECT `a`\n"
        + "FROM (SELECT 1 AS `a`, 'x ' AS `b`\n"
        + "UNION ALL\n"
        + "SELECT 2 AS `a`, 'yy' AS `b`) AS `t`";
    final String expectedPostgresql = "SELECT \"a\"\n"
        + "FROM (VALUES  (1, 'x '),\n"
        + " (2, 'yy')) AS \"t\" (\"a\", \"b\")";
    final String expectedOracle = "SELECT \"a\"\n"
        + "FROM (SELECT 1 \"a\", 'x ' \"b\"\n"
        + "FROM \"DUAL\"\n"
        + "UNION ALL\n"
        + "SELECT 2 \"a\", 'yy' \"b\"\n"
        + "FROM \"DUAL\")";
    final String expectedSnowflake = expectedPostgresql;
    final String expectedRedshift = expectedPostgresql;
    sql(sql)
        .withHsqldb()
        .ok(expectedHsqldb)
        .withMysql()
        .ok(expectedMysql)
        .withPostgresql()
        .ok(expectedPostgresql)
        .withOracle()
        .ok(expectedOracle)
        .withSnowflake()
        .ok(expectedSnowflake)
        .withRedshift()
        .ok(expectedRedshift);
  }

  @Test public void testValuesEmpty() {
    final String sql = "select *\n"
        + "from (values (1, 'a'), (2, 'bb')) as t(x, y)\n"
        + "limit 0";
    final RuleSet rules =
        RuleSets.ofList(PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE);
    final String expectedMysql = "SELECT *\n"
        + "FROM (SELECT NULL AS `X`, NULL AS `Y`) AS `t`\n"
        + "WHERE 1 = 0";
    final String expectedOracle = "SELECT NULL \"X\", NULL \"Y\"\n"
        + "FROM \"DUAL\"\n"
        + "WHERE 1 = 0";
    final String expectedPostgresql = "SELECT *\n"
        + "FROM (VALUES  (NULL, NULL)) AS \"t\" (\"X\", \"Y\")\n"
        + "WHERE 1 = 0";
    sql(sql)
        .optimize(rules, null)
        .withMysql()
        .ok(expectedMysql)
        .withOracle()
        .ok(expectedOracle)
        .withPostgresql()
        .ok(expectedPostgresql);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-2118">[CALCITE-2118]
   * RelToSqlConverter should only generate "*" if field names match</a>. */
  @Test public void testPreserveAlias() {
    final String sql = "select \"warehouse_class_id\" as \"id\",\n"
        + " \"description\"\n"
        + "from \"warehouse_class\"";
    final String expected = ""
        + "SELECT \"warehouse_class_id\" AS \"id\", \"description\"\n"
        + "FROM \"foodmart\".\"warehouse_class\"";
    sql(sql).ok(expected);

    final String sql2 = "select \"warehouse_class_id\", \"description\"\n"
        + "from \"warehouse_class\"";
    final String expected2 = "SELECT *\n"
        + "FROM \"foodmart\".\"warehouse_class\"";
    sql(sql2).ok(expected2);
  }

  @Test public void testPreservePermutation() {
    final String sql = "select \"description\", \"warehouse_class_id\"\n"
        + "from \"warehouse_class\"";
    final String expected = "SELECT \"description\", \"warehouse_class_id\"\n"
        + "FROM \"foodmart\".\"warehouse_class\"";
    sql(sql).ok(expected);
  }

  @Test public void testFieldNamesWithAggregateSubQuery() {
    final String query = "select mytable.\"city\",\n"
        + "  sum(mytable.\"store_sales\") as \"my-alias\"\n"
        + "from (select c.\"city\", s.\"store_sales\"\n"
        + "  from \"sales_fact_1997\" as s\n"
        + "    join \"customer\" as c using (\"customer_id\")\n"
        + "  group by c.\"city\", s.\"store_sales\") AS mytable\n"
        + "group by mytable.\"city\"";

    final String expected = "SELECT \"t0\".\"city\","
        + " SUM(\"t0\".\"store_sales\") AS \"my-alias\"\n"
        + "FROM (SELECT \"customer\".\"city\","
        + " \"sales_fact_1997\".\"store_sales\"\n"
        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
        + "INNER JOIN \"foodmart\".\"customer\""
        + " ON \"sales_fact_1997\".\"customer_id\""
        + " = \"customer\".\"customer_id\"\n"
        + "GROUP BY \"customer\".\"city\","
        + " \"sales_fact_1997\".\"store_sales\") AS \"t0\"\n"
        + "GROUP BY \"t0\".\"city\"";
    sql(query).ok(expected);
  }

  @Test public void testUnparseSelectMustUseDialect() {
    final String query = "select * from \"product\"";
    final String expected = "SELECT *\n"
        + "FROM foodmart.product";

    final boolean[] callsUnparseCallOnSqlSelect = {false};
    final SqlDialect dialect = new SqlDialect(SqlDialect.EMPTY_CONTEXT) {
      @Override public void unparseCall(SqlWriter writer, SqlCall call,
          int leftPrec, int rightPrec) {
        if (call instanceof SqlSelect) {
          callsUnparseCallOnSqlSelect[0] = true;
        }
        super.unparseCall(writer, call, leftPrec, rightPrec);
      }
    };
    sql(query).dialect(dialect).ok(expected);

    assertThat("Dialect must be able to customize unparseCall() for SqlSelect",
        callsUnparseCallOnSqlSelect[0], is(true));
  }

  @Test public void testCorrelate() {
    final String sql = "select d.\"department_id\", d_plusOne "
        + "from \"department\" as d, "
        + "       lateral (select d.\"department_id\" + 1 as d_plusOne"
        + "                from (values(true)))";

    final String expected = "SELECT \"$cor0\".\"department_id\", \"$cor0\".\"D_PLUSONE\"\n"
        + "FROM \"foodmart\".\"department\" AS \"$cor0\",\n"
        + "LATERAL (SELECT \"$cor0\".\"department_id\" + 1 AS \"D_PLUSONE\"\n"
        + "FROM (VALUES  (TRUE)) AS \"t\" (\"EXPR$0\")) AS \"t0\"";
    sql(sql).ok(expected);
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-3651">[CALCITE-3651]
   * NullPointerException when convert relational algebra that correlates TableFunctionScan</a>. */
  @Test public void testLateralCorrelate() {
    final String query = "select * from \"product\",\n"
        + "lateral table(RAMP(\"product\".\"product_id\"))";
    final String expected = "SELECT *\n"
        + "FROM \"foodmart\".\"product\" AS \"$cor0\",\n"
        + "LATERAL (SELECT *\n"
        + "FROM TABLE(RAMP(\"$cor0\".\"product_id\"))) AS \"t\"";
    sql(query).ok(expected);
  }

  @Test public void testUncollectExplicitAlias() {
    final String sql = "select did + 1 \n"
        + "from unnest(select collect(\"department_id\") as deptid"
        + "            from \"department\") as t(did)";

    final String expected = "SELECT \"DEPTID\" + 1\n"
        + "FROM UNNEST (SELECT COLLECT(\"department_id\") AS \"DEPTID\"\n"
        + "FROM \"foodmart\".\"department\") AS \"t0\" (\"DEPTID\")";
    sql(sql).ok(expected);
  }

  @Test public void testUncollectImplicitAlias() {
    final String sql = "select did + 1 \n"
        + "from unnest(select collect(\"department_id\") "
        + "            from \"department\") as t(did)";

    final String expected = "SELECT \"col_0\" + 1\n"
        + "FROM UNNEST (SELECT COLLECT(\"department_id\")\n"
        + "FROM \"foodmart\".\"department\") AS \"t0\" (\"col_0\")";
    sql(sql).ok(expected);
  }


  @Test public void testWithinGroup1() {
    final String query = "select \"product_class_id\", collect(\"net_weight\") "
        + "within group (order by \"net_weight\" desc) "
        + "from \"product\" group by \"product_class_id\"";
    final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
        + "WITHIN GROUP (ORDER BY \"net_weight\" DESC)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testWithinGroup2() {
    final String query = "select \"product_class_id\", collect(\"net_weight\") "
        + "within group (order by \"low_fat\", \"net_weight\" desc nulls last) "
        + "from \"product\" group by \"product_class_id\"";
    final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
        + "WITHIN GROUP (ORDER BY \"low_fat\", \"net_weight\" DESC NULLS LAST)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testWithinGroup3() {
    final String query = "select \"product_class_id\", collect(\"net_weight\") "
        + "within group (order by \"net_weight\" desc), "
        + "min(\"low_fat\")"
        + "from \"product\" group by \"product_class_id\"";
    final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
        + "WITHIN GROUP (ORDER BY \"net_weight\" DESC), MIN(\"low_fat\")\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testWithinGroup4() {
    // filter in AggregateCall is not unparsed
    final String query = "select \"product_class_id\", collect(\"net_weight\") "
        + "within group (order by \"net_weight\" desc) filter (where \"net_weight\" > 0)"
        + "from \"product\" group by \"product_class_id\"";
    final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
        + "WITHIN GROUP (ORDER BY \"net_weight\" DESC)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY \"product_class_id\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonValueExpressionOperator() {
    String query = "select \"product_name\" format json, "
        + "\"product_name\" format json encoding utf8, "
        + "\"product_name\" format json encoding utf16, "
        + "\"product_name\" format json encoding utf32 from \"product\"";
    final String expected = "SELECT \"product_name\" FORMAT JSON, "
        + "\"product_name\" FORMAT JSON, "
        + "\"product_name\" FORMAT JSON, "
        + "\"product_name\" FORMAT JSON\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonExists() {
    String query = "select json_exists(\"product_name\", 'lax $') from \"product\"";
    final String expected = "SELECT JSON_EXISTS(\"product_name\", 'lax $')\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonPretty() {
    String query = "select json_pretty(\"product_name\") from \"product\"";
    final String expected = "SELECT JSON_PRETTY(\"product_name\")\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonValue() {
    String query = "select json_value(\"product_name\", 'lax $') from \"product\"";
    // todo translate to JSON_VALUE rather than CAST
    final String expected = "SELECT CAST(JSON_VALUE_ANY(\"product_name\", "
        + "'lax $' NULL ON EMPTY NULL ON ERROR) AS VARCHAR(2000) CHARACTER SET \"ISO-8859-1\")\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonQuery() {
    String query = "select json_query(\"product_name\", 'lax $') from \"product\"";
    final String expected = "SELECT JSON_QUERY(\"product_name\", 'lax $' "
        + "WITHOUT ARRAY WRAPPER NULL ON EMPTY NULL ON ERROR)\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonArray() {
    String query = "select json_array(\"product_name\", \"product_name\") from \"product\"";
    final String expected = "SELECT JSON_ARRAY(\"product_name\", \"product_name\" ABSENT ON NULL)\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonArrayAgg() {
    String query = "select json_arrayagg(\"product_name\") from \"product\"";
    final String expected = "SELECT JSON_ARRAYAGG(\"product_name\" ABSENT ON NULL)\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonObject() {
    String query = "select json_object(\"product_name\": \"product_id\") from \"product\"";
    final String expected = "SELECT "
        + "JSON_OBJECT(KEY \"product_name\" VALUE \"product_id\" NULL ON NULL)\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonObjectAgg() {
    String query = "select json_objectagg(\"product_name\": \"product_id\") from \"product\"";
    final String expected = "SELECT "
        + "JSON_OBJECTAGG(KEY \"product_name\" VALUE \"product_id\" NULL ON NULL)\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonPredicate() {
    String query = "select "
        + "\"product_name\" is json, "
        + "\"product_name\" is json value, "
        + "\"product_name\" is json object, "
        + "\"product_name\" is json array, "
        + "\"product_name\" is json scalar, "
        + "\"product_name\" is not json, "
        + "\"product_name\" is not json value, "
        + "\"product_name\" is not json object, "
        + "\"product_name\" is not json array, "
        + "\"product_name\" is not json scalar "
        + "from \"product\"";
    final String expected = "SELECT "
        + "\"product_name\" IS JSON VALUE, "
        + "\"product_name\" IS JSON VALUE, "
        + "\"product_name\" IS JSON OBJECT, "
        + "\"product_name\" IS JSON ARRAY, "
        + "\"product_name\" IS JSON SCALAR, "
        + "\"product_name\" IS NOT JSON VALUE, "
        + "\"product_name\" IS NOT JSON VALUE, "
        + "\"product_name\" IS NOT JSON OBJECT, "
        + "\"product_name\" IS NOT JSON ARRAY, "
        + "\"product_name\" IS NOT JSON SCALAR\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testCrossJoinEmulationForSpark() {
    String query = "select * from \"employee\", \"department\"";
    final String expected = "SELECT *\n"
        + "FROM foodmart.employee\n"
        + "CROSS JOIN foodmart.department";
    sql(query).withSpark().ok(expected);
  }

  @Test public void testSubstringInSpark() {
    final String query = "select substring(\"brand_name\" from 2) "
        + "from \"product\"\n";
    final String expected = "SELECT SUBSTRING(brand_name, 2)\n"
        + "FROM foodmart.product";
    sql(query).withSpark().ok(expected);
  }

  @Test public void testSubstringWithForInSpark() {
    final String query = "select substring(\"brand_name\" from 2 for 3) "
        + "from \"product\"\n";
    final String expected = "SELECT SUBSTRING(brand_name, 2, 3)\n"
        + "FROM foodmart.product";
    sql(query).withSpark().ok(expected);
  }

  @Test public void testFloorInSpark() {
    final String query = "select floor(\"hire_date\" TO MINUTE) "
        + "from \"employee\"";
    final String expected = "SELECT DATE_TRUNC('MINUTE', hire_date)\n"
        + "FROM foodmart.employee";
    sql(query).withSpark().ok(expected);
  }

  @Test public void testNumericFloorInSpark() {
    final String query = "select floor(\"salary\") "
        + "from \"employee\"";
    final String expected = "SELECT FLOOR(salary)\n"
        + "FROM foodmart.employee";
    sql(query).withSpark().ok(expected);
  }

  @Test public void testJsonStorageSize() {
    String query = "select json_storage_size(\"product_name\") from \"product\"";
    final String expected = "SELECT JSON_STORAGE_SIZE(\"product_name\")\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testCubeInSpark() {
    final String query = "select count(*) "
        + "from \"foodmart\".\"product\" "
        + "group by cube(\"product_id\",\"product_class_id\")";
    final String expected = "SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY CUBE(\"product_id\", \"product_class_id\")";
    final String expectedInSpark = "SELECT COUNT(*)\n"
        + "FROM foodmart.product\n"
        + "GROUP BY product_id, product_class_id WITH CUBE";
    sql(query)
        .ok(expected)
        .withSpark()
        .ok(expectedInSpark);
  }

  @Test public void testRollupInSpark() {
    final String query = "select count(*) "
        + "from \"foodmart\".\"product\" "
        + "group by rollup(\"product_id\",\"product_class_id\")";
    final String expected = "SELECT COUNT(*)\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "GROUP BY ROLLUP(\"product_id\", \"product_class_id\")";
    final String expectedInSpark = "SELECT COUNT(*)\n"
        + "FROM foodmart.product\n"
        + "GROUP BY product_id, product_class_id WITH ROLLUP";
    sql(query)
        .ok(expected)
        .withSpark()
        .ok(expectedInSpark);
  }

  @Test public void testJsonType() {
    String query = "select json_type(\"product_name\") from \"product\"";
    final String expected = "SELECT "
        + "JSON_TYPE(\"product_name\")\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonDepth() {
    String query = "select json_depth(\"product_name\") from \"product\"";
    final String expected = "SELECT "
        + "JSON_DEPTH(\"product_name\")\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonLength() {
    String query = "select json_length(\"product_name\", 'lax $'), "
        + "json_length(\"product_name\") from \"product\"";
    final String expected = "SELECT JSON_LENGTH(\"product_name\", 'lax $'), "
        + "JSON_LENGTH(\"product_name\")\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonKeys() {
    String query = "select json_keys(\"product_name\", 'lax $') from \"product\"";
    final String expected = "SELECT JSON_KEYS(\"product_name\", 'lax $')\n"
        + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testJsonRemove() {
    String query = "select json_remove(\"product_name\", '$[0]') from \"product\"";
    final String expected = "SELECT JSON_REMOVE(\"product_name\", '$[0]')\n"
           + "FROM \"foodmart\".\"product\"";
    sql(query).ok(expected);
  }

  @Test public void testUnionAllWithNoOperandsUsingOracleDialect() {
    String query = "select A.\"department_id\" "
        + "from \"foodmart\".\"employee\" A "
        + " where A.\"department_id\" = ( select min( A.\"department_id\") from \"foodmart\".\"department\" B where 1=2 )";
    final String expected = "SELECT \"employee\".\"department_id\"\n"
        + "FROM \"foodmart\".\"employee\"\n"
        + "INNER JOIN (SELECT \"t1\".\"department_id\" \"department_id0\", MIN(\"t1\".\"department_id\")\n"
        + "FROM (SELECT NULL \"department_id\", NULL \"department_description\"\nFROM \"DUAL\"\nWHERE 1 = 0) \"t\",\n"
        + "(SELECT \"department_id\"\nFROM \"foodmart\".\"employee\"\nGROUP BY \"department_id\") \"t1\"\n"
        + "GROUP BY \"t1\".\"department_id\") \"t3\" ON \"employee\".\"department_id\" = \"t3\".\"department_id0\""
        + " AND \"employee\".\"department_id\" = MIN(\"t1\".\"department_id\")";
    sql(query).withOracle().ok(expected);
  }

  @Test public void testUnionAllWithNoOperands() {
    String query = "select A.\"department_id\" "
        + "from \"foodmart\".\"employee\" A "
        + " where A.\"department_id\" = ( select min( A.\"department_id\") from \"foodmart\".\"department\" B where 1=2 )";
    final String expected = "SELECT \"employee\".\"department_id\"\n"
        + "FROM \"foodmart\".\"employee\"\n"
        + "INNER JOIN (SELECT \"t1\".\"department_id\" AS \"department_id0\","
        + " MIN(\"t1\".\"department_id\")\n"
        + "FROM (SELECT *\nFROM (VALUES  (NULL, NULL))"
        + " AS \"t\" (\"department_id\", \"department_description\")"
        + "\nWHERE 1 = 0) AS \"t\","
        + "\n(SELECT \"department_id\"\nFROM \"foodmart\".\"employee\""
        + "\nGROUP BY \"department_id\") AS \"t1\""
        + "\nGROUP BY \"t1\".\"department_id\") AS \"t3\" "
        + "ON \"employee\".\"department_id\" = \"t3\".\"department_id0\""
        + " AND \"employee\".\"department_id\" = MIN(\"t1\".\"department_id\")";
    sql(query).ok(expected);
  }

  @Test public void testSmallintOracle() {
    String query = "SELECT CAST(\"department_id\" AS SMALLINT) FROM \"employee\"";
    String expected = "SELECT CAST(\"department_id\" AS NUMBER(5))\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testBigintOracle() {
    String query = "SELECT CAST(\"department_id\" AS BIGINT) FROM \"employee\"";
    String expected = "SELECT CAST(\"department_id\" AS NUMBER(19))\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testDoubleOracle() {
    String query = "SELECT CAST(\"department_id\" AS DOUBLE) FROM \"employee\"";
    String expected = "SELECT CAST(\"department_id\" AS DOUBLE PRECISION)\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testDateLiteralOracle() {
    String query = "SELECT DATE '1978-05-02' FROM \"employee\"";
    String expected = "SELECT TO_DATE('1978-05-02', 'YYYY-MM-DD')\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testTimestampLiteralOracle() {
    String query = "SELECT TIMESTAMP '1978-05-02 12:34:56.78' FROM \"employee\"";
    String expected = "SELECT TO_TIMESTAMP('1978-05-02 12:34:56.78',"
        + " 'YYYY-MM-DD HH24:MI:SS.FF')\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testTimeLiteralOracle() {
    String query = "SELECT TIME '12:34:56.78' FROM \"employee\"";
    String expected = "SELECT TO_TIME('12:34:56.78', 'HH24:MI:SS.FF')\n"
        + "FROM \"foodmart\".\"employee\"";
    sql(query)
        .withOracle()
        .ok(expected);
  }

  @Test public void testSupportsDataType() {
    final RelDataTypeFactory typeFactory =
        new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    final RelDataType booleanDataType = typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    final RelDataType integerDataType = typeFactory.createSqlType(SqlTypeName.INTEGER);
    final SqlDialect oracleDialect = SqlDialect.DatabaseProduct.ORACLE.getDialect();
    assertFalse(oracleDialect.supportsDataType(booleanDataType));
    assertTrue(oracleDialect.supportsDataType(integerDataType));
    final SqlDialect postgresqlDialect = SqlDialect.DatabaseProduct.POSTGRESQL.getDialect();
    assertTrue(postgresqlDialect.supportsDataType(booleanDataType));
    assertTrue(postgresqlDialect.supportsDataType(integerDataType));
  }

  @Test public void testSelectNull() {
    String query = "SELECT CAST(NULL AS INT)";
    final String expected = "SELECT CAST(NULL AS INTEGER)\n"
            + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")";
    sql(query).ok(expected);
    // validate
    sql(expected).exec();
  }

  @Test public void testSelectNullWithCount() {
    String query = "SELECT COUNT(CAST(NULL AS INT))";
    final String expected = "SELECT COUNT(CAST(NULL AS INTEGER))\n"
            + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")";
    sql(query).ok(expected);
    // validate
    sql(expected).exec();
  }

  @Test public void testSelectNullWithGroupByNull() {
    String query = "SELECT COUNT(CAST(NULL AS INT)) FROM (VALUES  (0))\n"
            + "AS \"t\" GROUP BY CAST(NULL AS VARCHAR CHARACTER SET \"ISO-8859-1\")";
    final String expected = "SELECT COUNT(CAST(NULL AS INTEGER))\n"
            + "FROM (VALUES  (0)) AS \"t\" (\"EXPR$0\")\nGROUP BY CAST(NULL "
            + "AS VARCHAR CHARACTER SET \"ISO-8859-1\")";
    sql(query).ok(expected);
    // validate
    sql(expected).exec();
  }

  @Test public void testSelectNullWithGroupByVar() {
    String query = "SELECT COUNT(CAST(NULL AS INT)) FROM \"account\"\n"
            + "AS \"t\" GROUP BY \"account_type\"";
    final String expected = "SELECT COUNT(CAST(NULL AS INTEGER))\n"
            + "FROM \"foodmart\".\"account\"\n"
            + "GROUP BY \"account_type\"";
    sql(query).ok(expected);
    // validate
    sql(expected).exec();
  }

  @Test public void testSelectNullWithInsert() {
    String query = "insert into\n"
            + "\"account\"(\"account_id\",\"account_parent\",\"account_type\",\"account_rollup\")\n"
            + "select 1, cast(NULL AS INT), cast(123 as varchar), cast(123 as varchar)";
    final String expected = "INSERT INTO \"foodmart\".\"account\" ("
            + "\"account_id\", \"account_parent\", \"account_description\", "
            + "\"account_type\", \"account_rollup\", \"Custom_Members\")\n"
            + "(SELECT 1 AS \"account_id\", CAST(NULL AS INTEGER) AS \"account_parent\","
            + " CAST(NULL AS VARCHAR(30) CHARACTER SET "
            + "\"ISO-8859-1\") AS \"account_description\", '123' AS \"account_type\", "
            + "'123' AS \"account_rollup\", CAST(NULL AS VARCHAR"
            + "(255) CHARACTER SET \"ISO-8859-1\") AS \"Custom_Members\"\n"
            + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\"))";
    sql(query).ok(expected);
    // validate
    sql(expected).exec();
  }

  @Test public void testSelectNullWithInsertFromJoin() {
    String query = "insert into \n"
            + "\"account\"(\"account_id\",\"account_parent\",\n"
            + "\"account_type\",\"account_rollup\")\n"
            + "select \"product\".\"product_id\", \n"
            + "cast(NULL AS INT),\n"
            + "cast(\"product\".\"product_id\" as varchar),\n"
            + "cast(\"sales_fact_1997\".\"store_id\" as varchar)\n"
            + "from \"product\"\n"
            + "inner join \"sales_fact_1997\"\n"
            + "on \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"";
    final String expected = "INSERT INTO \"foodmart\".\"account\" "
            + "(\"account_id\", \"account_parent\", \"account_description\", "
            + "\"account_type\", \"account_rollup\", \"Custom_Members\")\n"
            + "(SELECT \"product\".\"product_id\" AS \"account_id\", "
            + "CAST(NULL AS INTEGER) AS \"account_parent\", CAST(NULL AS VARCHAR"
            + "(30) CHARACTER SET \"ISO-8859-1\") AS \"account_description\", "
            + "CAST(\"product\".\"product_id\" AS VARCHAR CHARACTER SET "
            + "\"ISO-8859-1\") AS \"account_type\", "
            + "CAST(\"sales_fact_1997\".\"store_id\" AS VARCHAR CHARACTER SET \"ISO-8859-1\") AS "
            + "\"account_rollup\", "
            + "CAST(NULL AS VARCHAR(255) CHARACTER SET \"ISO-8859-1\") AS \"Custom_Members\"\n"
            + "FROM \"foodmart\".\"product\"\n"
            + "INNER JOIN \"foodmart\".\"sales_fact_1997\" "
            + "ON \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\")";
    sql(query).ok(expected);
    // validate
    sql(expected).exec();
  }

  @Test public void testCastInStringIntegerComparison() {
    final String query = "select \"employee_id\" "
        + "from \"foodmart\".\"employee\" "
        + "where 10 = cast('10' as int) and \"birth_date\" = cast('1914-02-02' as date) or "
        + "\"hire_date\" = cast('1996-01-01 '||'00:00:00' as timestamp)";
    final String expected = "SELECT \"employee_id\"\n"
        + "FROM \"foodmart\".\"employee\"\n"
        + "WHERE 10 = '10' AND \"birth_date\" = '1914-02-02' OR \"hire_date\" = '1996-01-01 ' || "
        + "'00:00:00'";
    final String expectedBiqquery = "SELECT employee_id\n"
        + "FROM foodmart.employee\n"
        + "WHERE 10 = CAST('10' AS INT64) AND birth_date = '1914-02-02' OR hire_date = "
        + "CAST('1996-01-01 ' || '00:00:00' AS TIMESTAMP)";
    sql(query)
        .ok(expected)
        .withBigQuery()
        .ok(expectedBiqquery);
  }

  @Test public void testDialectQuoteStringLiteral() {
    dialects().forEach((dialect, databaseProduct) -> {
      assertThat(dialect.quoteStringLiteral(""), is("''"));
      assertThat(dialect.quoteStringLiteral("can't run"),
          databaseProduct == DatabaseProduct.BIG_QUERY
              ? is("'can\\'t run'")
              : is("'can''t run'"));

      assertThat(dialect.unquoteStringLiteral("''"), is(""));
      if (databaseProduct == DatabaseProduct.BIG_QUERY) {
        assertThat(dialect.unquoteStringLiteral("'can\\'t run'"),
            is("can't run"));
      } else {
        assertThat(dialect.unquoteStringLiteral("'can't run'"),
            is("can't run"));
      }
    });
  }

  @Test public void testSelectCountStar() {
    final String query = "select count(*) from \"product\"";
    final String expected = "SELECT COUNT(*)\n"
            + "FROM \"foodmart\".\"product\"";
    Sql sql = sql(query);
    sql.ok(expected);
  }

  @Test public void testRowValueExpression() {
    final String expected0 = "INSERT INTO SCOTT.DEPT (DEPTNO, DNAME, LOC)\n"
        + "SELECT 1, 'Fred', 'San Francisco'\n"
        + "FROM (VALUES  (0)) t (ZERO)\n"
        + "UNION ALL\n"
        + "SELECT 2, 'Eric', 'Washington'\n"
        + "FROM (VALUES  (0)) t (ZERO)";
    String sql = "insert into \"DEPT\"\n"
            + "values ROW(1,'Fred', 'San Francisco'), ROW(2, 'Eric', 'Washington')";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .withHive()
        .ok(expected0);

    final String expected1 = "INSERT INTO `SCOTT`.`DEPT` (`DEPTNO`, `DNAME`, `LOC`)\n"
        + "SELECT 1, 'Fred', 'San Francisco'\n"
        + "UNION ALL\n"
        + "SELECT 2, 'Eric', 'Washington'";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .withMysql()
        .ok(expected1);

    final String expected2 = "INSERT INTO \"SCOTT\".\"DEPT\" (\"DEPTNO\", "
        + "\"DNAME\", \"LOC\")\n"
        + "SELECT 1, 'Fred', 'San Francisco'\n"
        + "FROM \"DUAL\"\n"
        + "UNION ALL\n"
        + "SELECT 2, 'Eric', 'Washington'\n"
        + "FROM \"DUAL\"";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .withOracle()
        .ok(expected2);

    final String expected3 = "INSERT INTO [SCOTT].[DEPT] ([DEPTNO], [DNAME], [LOC])\n"
        + "SELECT 1, 'Fred', 'San Francisco'\n"
        + "FROM (VALUES  (0)) AS [t] ([ZERO])\n"
        + "UNION ALL\n"
        + "SELECT 2, 'Eric', 'Washington'\n"
        + "FROM (VALUES  (0)) AS [t] ([ZERO])";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .withMssql()
        .ok(expected3);

    final String expected4 = "INSERT INTO \"SCOTT\".\"DEPT\" (\"DEPTNO\", "
        + "\"DNAME\", \"LOC\")\n"
        + "SELECT 1, 'Fred', 'San Francisco'\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")\n"
        + "UNION ALL\n"
        + "SELECT 2, 'Eric', 'Washington'\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .ok(expected4);

    final String expected5 = "INSERT INTO \"SCOTT\".\"DEPT\" (\"DEPTNO\", "
        + "\"DNAME\", \"LOC\")\n"
        + "SELECT 1, 'Fred', 'San Francisco'\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")\n"
        + "UNION ALL\n"
        + "SELECT 2, 'Eric', 'Washington'\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")";
    sql(sql).withCalcite()
            .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
            .ok(expected5);
  }

  @Test public void testInsertValuesWithDynamicParams() {
    final String sql = "insert into \"DEPT\" values (?,?,?), (?,?,?)";
    final String expected = ""
        + "INSERT INTO \"SCOTT\".\"DEPT\" (\"DEPTNO\", \"DNAME\", \"LOC\")\n"
        + "SELECT ?, ?, ?\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")\n"
        + "UNION ALL\n"
        + "SELECT ?, ?, ?\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .ok(expected);
  }

  @Test public void testInsertValuesWithExplicitColumnsAndDynamicParams() {
    final String sql = ""
        + "insert into \"DEPT\" (\"DEPTNO\", \"DNAME\", \"LOC\")\n"
        + "values (?,?,?), (?,?,?)";
    final String expected = ""
        + "INSERT INTO \"SCOTT\".\"DEPT\" (\"DEPTNO\", \"DNAME\", \"LOC\")\n"
        + "SELECT ?, ?, ?\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")\n"
        + "UNION ALL\n"
        + "SELECT ?, ?, ?\n"
        + "FROM (VALUES  (0)) AS \"t\" (\"ZERO\")";
    sql(sql)
        .schema(CalciteAssert.SchemaSpec.JDBC_SCOTT)
        .ok(expected);
  }

  @Test public void testTableFunctionScan() {
    final String query = "SELECT *\n"
        + "FROM TABLE(DEDUP(CURSOR(select \"product_id\", \"product_name\"\n"
        + "from \"product\"), CURSOR(select  \"employee_id\", \"full_name\"\n"
        + "from \"employee\"), 'NAME'))";

    final String expected = "SELECT *\n"
        + "FROM TABLE(DEDUP(CURSOR ((SELECT \"product_id\", \"product_name\"\n"
        + "FROM \"foodmart\".\"product\")), CURSOR ((SELECT \"employee_id\", \"full_name\"\n"
        + "FROM \"foodmart\".\"employee\")), 'NAME'))";
    sql(query).ok(expected);

    final String query2 = "select * from table(ramp(3))";
    sql(query2).ok("SELECT *\n"
        + "FROM TABLE(RAMP(3))");
  }

  @Test public void testTableFunctionScanWithComplexQuery() {
    final String query = "SELECT *\n"
        + "FROM TABLE(DEDUP(CURSOR(select \"product_id\", \"product_name\"\n"
        + "from \"product\"\n"
        + "where \"net_weight\" > 100 and \"product_name\" = 'Hello World')\n"
        + ",CURSOR(select  \"employee_id\", \"full_name\"\n"
        + "from \"employee\"\n"
        + "group by \"employee_id\", \"full_name\"), 'NAME'))";

    final String expected = "SELECT *\n"
        + "FROM TABLE(DEDUP(CURSOR ((SELECT \"product_id\", \"product_name\"\n"
        + "FROM \"foodmart\".\"product\"\n"
        + "WHERE \"net_weight\" > 100 AND \"product_name\" = 'Hello World')), "
        + "CURSOR ((SELECT \"employee_id\", \"full_name\"\n"
        + "FROM \"foodmart\".\"employee\"\n"
        + "GROUP BY \"employee_id\", \"full_name\")), 'NAME'))";
    sql(query).ok(expected);
  }

  /** Fluid interface to run tests. */
  static class Sql {
    private final SchemaPlus schema;
    private final String sql;
    private final SqlDialect dialect;
    private final List<Function<RelNode, RelNode>> transforms;
    private final SqlToRelConverter.Config config;

    Sql(CalciteAssert.SchemaSpec schemaSpec, String sql, SqlDialect dialect,
        SqlToRelConverter.Config config,
        List<Function<RelNode, RelNode>> transforms) {
      final SchemaPlus rootSchema = Frameworks.createRootSchema(true);
      this.schema = CalciteAssert.addSchema(rootSchema, schemaSpec);
      this.sql = sql;
      this.dialect = dialect;
      this.transforms = ImmutableList.copyOf(transforms);
      this.config = config;
    }

    Sql(SchemaPlus schema, String sql, SqlDialect dialect,
        SqlToRelConverter.Config config,
        List<Function<RelNode, RelNode>> transforms) {
      this.schema = schema;
      this.sql = sql;
      this.dialect = dialect;
      this.transforms = ImmutableList.copyOf(transforms);
      this.config = config;
    }

    Sql dialect(SqlDialect dialect) {
      return new Sql(schema, sql, dialect, config, transforms);
    }

    Sql withCalcite() {
      return dialect(SqlDialect.DatabaseProduct.CALCITE.getDialect());
    }

    Sql withDb2() {
      return dialect(SqlDialect.DatabaseProduct.DB2.getDialect());
    }

    Sql withHive() {
      return dialect(SqlDialect.DatabaseProduct.HIVE.getDialect());
    }

    Sql withHsqldb() {
      return dialect(SqlDialect.DatabaseProduct.HSQLDB.getDialect());
    }

    Sql withMssql() {
      return withMssql(14); // MSSQL 2008 = 10.0, 2012 = 11.0, 2017 = 14.0
    }

    Sql withMssql(int majorVersion) {
      final SqlDialect mssqlDialect = DatabaseProduct.MSSQL.getDialect();
      return dialect(
          new MssqlSqlDialect(MssqlSqlDialect.DEFAULT_CONTEXT
              .withDatabaseMajorVersion(majorVersion)
              .withIdentifierQuoteString(mssqlDialect.quoteIdentifier("")
                  .substring(0, 1))
              .withNullCollation(mssqlDialect.getNullCollation())));
    }

    Sql withMysql() {
      return dialect(SqlDialect.DatabaseProduct.MYSQL.getDialect());
    }

    Sql withMysql8() {
      final SqlDialect mysqlDialect = DatabaseProduct.MYSQL.getDialect();
      return dialect(
          new SqlDialect(MysqlSqlDialect.DEFAULT_CONTEXT
              .withDatabaseMajorVersion(8)
              .withIdentifierQuoteString(mysqlDialect.quoteIdentifier("")
                  .substring(0, 1))
              .withNullCollation(mysqlDialect.getNullCollation())));
    }

    Sql withOracle() {
      return dialect(SqlDialect.DatabaseProduct.ORACLE.getDialect());
    }

    Sql withPostgresql() {
      return dialect(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect());
    }

    Sql withRedshift() {
      return dialect(DatabaseProduct.REDSHIFT.getDialect());
    }

    Sql withSnowflake() {
      return dialect(DatabaseProduct.SNOWFLAKE.getDialect());
    }

    Sql withSybase() {
      return dialect(DatabaseProduct.SYBASE.getDialect());
    }

    Sql withVertica() {
      return dialect(SqlDialect.DatabaseProduct.VERTICA.getDialect());
    }

    Sql withBigQuery() {
      return dialect(SqlDialect.DatabaseProduct.BIG_QUERY.getDialect());
    }

    Sql withSpark() {
      return dialect(DatabaseProduct.SPARK.getDialect());
    }

    Sql withPostgresqlModifiedTypeSystem() {
      // Postgresql dialect with max length for varchar set to 256
      final PostgresqlSqlDialect postgresqlSqlDialect =
          new PostgresqlSqlDialect(PostgresqlSqlDialect.DEFAULT_CONTEXT
              .withDataTypeSystem(new RelDataTypeSystemImpl() {
                @Override public int getMaxPrecision(SqlTypeName typeName) {
                  switch (typeName) {
                  case VARCHAR:
                    return 256;
                  default:
                    return super.getMaxPrecision(typeName);
                  }
                }
              }));
      return dialect(postgresqlSqlDialect);
    }

    Sql withOracleModifiedTypeSystem() {
      // Oracle dialect with max length for varchar set to 512
      final OracleSqlDialect oracleSqlDialect =
          new OracleSqlDialect(OracleSqlDialect.DEFAULT_CONTEXT
              .withDataTypeSystem(new RelDataTypeSystemImpl() {
                @Override public int getMaxPrecision(SqlTypeName typeName) {
                  switch (typeName) {
                    case VARCHAR:
                      return 512;
                    default:
                      return super.getMaxPrecision(typeName);
                  }
                }
              }));
      return dialect(oracleSqlDialect);
    }

    Sql config(SqlToRelConverter.Config config) {
      return new Sql(schema, sql, dialect, config, transforms);
    }

    Sql optimize(final RuleSet ruleSet, final RelOptPlanner relOptPlanner) {
      return new Sql(schema, sql, dialect, config,
          FlatLists.append(transforms, r -> {
            Program program = Programs.of(ruleSet);
            final RelOptPlanner p =
                Util.first(relOptPlanner,
                    new HepPlanner(
                        new HepProgramBuilder().addRuleClass(RelOptRule.class)
                            .build()));
            return program.run(p, r, r.getTraitSet(),
                ImmutableList.of(), ImmutableList.of());
          }));
    }

    Sql ok(String expectedQuery) {
      assertThat(exec(), isLinux(expectedQuery));
      return this;
    }

    Sql throws_(String errorMessage) {
      try {
        final String s = exec();
        throw new AssertionError("Expected exception with message `"
            + errorMessage + "` but nothing was thrown; got " + s);
      } catch (Exception e) {
        assertThat(e.getMessage(), is(errorMessage));
        return this;
      }
    }

    String exec() {
      final Planner planner =
          getPlanner(null, SqlParser.Config.DEFAULT, schema, config);
      try {
        SqlNode parse = planner.parse(sql);
        SqlNode validate = planner.validate(parse);
        RelNode rel = planner.rel(validate).rel;
        for (Function<RelNode, RelNode> transform : transforms) {
          rel = transform.apply(rel);
        }
        return toSql(rel, dialect);
      } catch (Exception e) {
        throw TestUtil.rethrow(e);
      }
    }

    public Sql schema(CalciteAssert.SchemaSpec schemaSpec) {
      return new Sql(schemaSpec, sql, dialect, config, transforms);
    }
  }
}
