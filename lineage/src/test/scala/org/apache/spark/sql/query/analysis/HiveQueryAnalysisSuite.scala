/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.query.analysis

import java.io.File

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.hive.test.TestHive._
import org.apache.spark.sql.query.analysis.TestUtils._

/**
 * Tests that check that reading and writing to Hive tables produce the desired lineage data
 */
class HiveQueryAnalysisSuite
    extends SparkFunSuite
    with TestHiveSingleton
    with SQLTestUtils
    with ParquetHDFSTest {

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    hiveContext.listenerManager.register(TestQeListener)
    val testDataDirectory = "target" + File.separator + "query-analysis" + File.separator
    val testTables = Seq("test_table_1", "test_table_2").map(s => TestTable(s,
      s"""
         |CREATE EXTERNAL TABLE $s (
         |  code STRING,
         |  description STRING,
         |  total_emp INT,
         |  salary INT)
         |  ROW FORMAT DELIMITED FIELDS TERMINATED BY ","
         |  STORED AS TEXTFILE LOCATION "${new File(testDataDirectory, s).getCanonicalPath}"
      """.stripMargin.cmd))
    testTables.foreach(registerTestTable)

    TestUtils.createTable(hiveContext, "employee",
      Map(
        "id" -> "int",
        "first_name" -> "varchar(64)",
        "last_name" -> "varchar(64)",
        "salary" -> "int",
        "address" -> "string",
        "city" -> "varchar(64)"
      )
    )

    TestUtils.createTable(hiveContext, "department",
      Map(
        "dept_id" -> "int",
        "name" -> "varchar(64)",
        "location" -> "varchar(64)",
        "budget" -> "int"
      )
    )
  }

  test("QueryAnalysis.getInputMetadata returns back InputMetadata for simple queries") {
    val df = hiveContext.sql("select code, description, salary from test_table_1")
    assertHiveInputs(df.queryExecution, "test_table_1", Seq("code", "description", "salary"))
  }

  test("QueryAnalysis.getInputMetadata return back InputMetadata for complex joins") {
    var df2 = hiveContext.sql(
        "select code, sal from (select o.code as code,c.description as desc," +
          "c.salary as sal from test_table_1 c join test_table_2 o on (c.code = o.code)"
          + " where c.salary > 170000 sort by sal)t1 limit 3")
    df2 = df2.filter(df2("sal") > 100000)
    df2.write.saveAsTable("mytable")
    val qe = TestQeListener.getAndClear()
    val inputMetadata = QueryAnalysis.getInputMetadata(qe)
    assert(inputMetadata.length === 2)
    assertHiveFieldExists(inputMetadata, "test_table_1", "salary")
    assertHiveFieldExists(inputMetadata, "test_table_2", "code")
    assertHiveOutputs(qe, "mytable", Seq("code", "sal"))
  }

  test("QueryAnalysis.getInputMetadata returns back InputMetadata for * queries") {
    val df = hiveContext.sql("select * from test_table_1")
    assertHiveInputs(df.queryExecution, "test_table_1", Seq("code", "description", "salary",
      "total_emp"))
  }

  test("There is fully qualified table name in OutputMetadata") {
    val df = hiveContext.sql("select * from test_table_1")
    withTempDatabase { db =>
      activateDatabase(db) {
        df.write.saveAsTable("mytable")
        val qe = TestQeListener.getAndClear()
        assertHiveInputs(qe, "test_table_1", Seq("total_emp", "salary", "description", "code"))
        assertHiveOutputs(qe, "mytable", Seq("total_emp", "salary", "description", "code"), db)
      }
    }
  }

  test("CDH-50079 : a hive table joined with a parquet temp table is listed correctly") {
    withParquetHDFSFile((1 to 4).map(i => Customer(i, i.toString))) { prq =>
      sqlContext.read.parquet(prq).registerTempTable("customers")
      sqlContext
        .sql("select test_table_1.code, customers.name from test_table_1 join customers where " +
          "test_table_1.code = customers.id and test_table_1.description = 'Tom Cruise'")
        .write.saveAsTable("myowntable")
      val qe = TestQeListener.getAndClear()
      val inputMetadata = QueryAnalysis.getInputMetadata(qe)
      assert(inputMetadata.length === 2)
      assertHiveFieldExists(inputMetadata, "test_table_1", "code")
      assertHDFSFieldExists(inputMetadata, Array(prq), "name", DataSourceType.HDFS)
      val outputMetadata = QueryAnalysis.getOutputMetaData(qe)
      assert(outputMetadata.isDefined)
      assert(outputMetadata.get.source === "default.myowntable")
      assert(outputMetadata.get.dataSourceType === DataSourceType.HIVE)
    }
  }

  test("CDH-50366: Lineage should output data when there is no inputs") {
    import hiveContext.implicits._
    val nonInputTable: String = "MyNonInputTable"

    val nonInputDF = (1 to 4).map { i =>
      (i % 2 == 0, i, i.toLong, i.toFloat, i.toDouble)
    }.toDF
    nonInputDF.write.saveAsTable(nonInputTable)
    var qe = TestQeListener.getAndClear()
    assert(QueryAnalysis.getInputMetadata(qe).length === 0)
    assertHiveOutputs(qe, nonInputTable, Seq( "_1", "_2", "_3", "_4", "_5"))
    withTempPath { f =>
      nonInputDF.write.json(f.getCanonicalPath)
      val qe = TestQeListener.getAndClear()
      assert(QueryAnalysis.getInputMetadata(qe).length === 0)
      val outputMetadata = QueryAnalysis.getOutputMetaData(qe)
      assert(outputMetadata.isDefined)
      assert(outputMetadata.get.fields.size === 5)
      assert(outputMetadata.get.source === "file:" + f.getCanonicalPath)
      assert(outputMetadata.get.dataSourceType === DataSourceType.LOCAL)
    }

    var anotherDF = hiveContext.sql("select code, description, salary from test_table_1")
    anotherDF = anotherDF.join(nonInputDF, nonInputDF.col("_3") === anotherDF.col("salary"))
    anotherDF.write.saveAsTable(nonInputTable + 2)
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("salary", "code", "description"))
    assertHiveOutputs(qe, nonInputTable + 2, Seq("code", "description", "salary", "_1", "_2", "_3",
      "_4", "_5"))

    anotherDF.select("_2", "_5", "salary", "code").write.saveAsTable(nonInputTable + 3)
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("salary", "code"))
    assertHiveOutputs(qe, nonInputTable + 3, Seq("code", "salary", "_2", "_5"))

    var personDF = (1 to 4).map(i => Person(i.toString, i)).toDF
    personDF.write.saveAsTable(nonInputTable + 4)
    qe = TestQeListener.getAndClear()
    val inputMetadata = QueryAnalysis.getInputMetadata(qe)
    assert(inputMetadata.length === 0)
    assertHiveOutputs(qe, nonInputTable + 4, Seq("name", "age"))

    personDF = personDF.join(nonInputDF, nonInputDF.col("_3") === personDF.col("age"))
    personDF.write.saveAsTable(nonInputTable + 5)
    qe = TestQeListener.getAndClear()
    assert(QueryAnalysis.getInputMetadata(qe).length === 0)
    assertHiveOutputs(qe, nonInputTable + 5, Seq("name", "age" , "_1", "_2", "_3",
      "_4", "_5"))

    val testTableDF = hiveContext.sql("select code, description, salary from test_table_1")
    personDF = (1 to 4).map(i => Person(i.toString, i)).toDF
    personDF = personDF.join(testTableDF, testTableDF.col("salary") === personDF.col("age"))
    personDF.select("code", "description", "name", "age").write.saveAsTable(nonInputTable + 6)
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("description", "code"))
    assertHiveOutputs(qe, nonInputTable + 6, Seq("code", "description", "name", "age"))
  }

  test("CDH-51351: CTAS Queries should report lineage") {
    val inputDF = hiveContext.sql("select * from test_table_1")
    inputDF.write.saveAsTable("test_table_3")
    var qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("description", "code", "salary", "total_emp"))
    assertHiveOutputs(qe, "test_table_3", Seq("description", "code", "salary", "total_emp"))
    hiveContext.sql("create table test_table_4 as select * from test_table_1")
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("description", "code", "salary", "total_emp"))
    assertHiveOutputs(qe, "test_table_4", Seq("description", "code", "salary", "total_emp"))

    hiveContext.sql("create table test_table_7 as select code, salary from test_table_1")
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("code", "salary"))
    assertHiveOutputs(qe, "test_table_7", Seq("code", "salary"))

    hiveContext.sql(
      s"""create table test_table_5 as
        | select
        |   code, sal
        |   from (
        |     select
        |       t2.code as code,
        |       t1.description as desc,
        |       t1.salary as sal
        |   from
        |     test_table_1 t1
        |   join
        |     test_table_2 t2
        |   on
        |     (t1.code = t2.code)
        |   where t1.salary > 170000
        |   sort by sal
        |   ) t
        |limit 3""".stripMargin)
    qe = TestQeListener.getAndClear()
    val inputMetadata = QueryAnalysis.getInputMetadata(qe)
    assert(inputMetadata.length === 2)
    assertHiveFieldExists(inputMetadata, "test_table_1", "salary")
    assertHiveFieldExists(inputMetadata, "test_table_2", "code")
    assertHiveOutputs(qe, "test_table_5", Seq("code", "sal"))
  }

  test("CDH-51296: Insert Queries should report lineage") {
    // Create test table of different column names
    TestUtils.createTable(
      hiveContext, "test_table_6", Map("code_new" -> "STRING", "salary_new" -> "INT"))

    // Assert select * of tables of similar schema works for DataFrameWriter.insert method
    hiveContext.sql("select * from test_table_1").write.insertInto("test_table_2")
    var qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("description", "code", "salary", "total_emp"))
    assertHiveOutputs(qe, "test_table_2", Seq("description", "code", "salary", "total_emp"))

    // Assert select where column name of input table is different from column names of output
    // table for DataFrameWriter.insert method
    hiveContext.sql("select code, salary from test_table_1").write.insertInto("test_table_6")
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("code", "salary"))
    assertHiveOutputs(qe, "test_table_6", Seq("code_new", "salary_new"))

    // Assert select * works where output table column names vary from input table column names
    hiveContext.sql("select * from test_table_1").write.insertInto("test_table_6")
    qe = TestQeListener.getAndClear()
    // This issue fails because of this bug CDH-51466.
    // assertHiveInputs(qe, "test_table_1", Seq("code", "salary"))
    assertHiveOutputs(qe, "test_table_6", Seq("code_new", "salary_new"))

    // Assert insert with complex join query
    hiveContext.sql(
      s"""select
        |   code, sal
        | from
        |   (
        |     select
        |       tt_2.code as code,
        |       tt_1.description as desc,
        |       tt_1.salary as sal
        |     from
        |       test_table_1 tt_1
        |     join
        |       test_table_2 tt_2 on (tt_1.code = tt_2.code)
        |     where tt_1.salary > 170000
        |       sort by sal
        |     )t1
        |       limit 3""".stripMargin).write.insertInto("test_table_6")
    assertComplexInsert()

    // Repeat the above same tests for insert into query
    // Assert select * of tables of similar schema works insert into query
    hiveContext.sql("insert into test_table_2 select * from test_table_1")
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("description", "code", "salary", "total_emp"))
    assertHiveOutputs(qe, "test_table_2", Seq("description", "code", "salary", "total_emp"))

    // Assert select query where column name of input table is different from column names of output
    // table works for insert into query
    hiveContext.sql("insert into test_table_6 select code, salary from test_table_1")
    qe = TestQeListener.getAndClear()
    assertHiveInputs(qe, "test_table_1", Seq("code", "salary"))
    assertHiveOutputs(qe, "test_table_6", Seq("code_new", "salary_new"))

    // Assert select * works where output table column names vary from input table column names
    hiveContext.sql("insert into test_table_6 select * from test_table_1")
    qe = TestQeListener.getAndClear()
    // This issue fails because of this bug CDH-51466.
    // assertHiveInputs(qe, "test_table_1", Seq("code", "salary"))
    assertHiveOutputs(qe, "test_table_6", Seq("code_new", "salary_new"))

    // Assert select query with complex join works for insert into query
    hiveContext.sql(
      s"""insert into test_table_6
        |   select code, sal
        | from (
        |   select
        |     tt_2.code as code,
        |     tt_1.description as desc,
        |     tt_1 .salary as sal
        |   from
        |     test_table_1 tt_1
        |   join
        |     test_table_2 tt_2 on (tt_1.code = tt_2.code)
        |   where tt_1.salary > 170000
        |     sort by sal
        |   )t1
        |     limit 3""".stripMargin)
    assertComplexInsert()
  }

  test("CDH-51486 : Add an error code to lineage data to indicate an aggregated function is used") {
    // Assert DESCRIBE, CREATE, DROP queries
    val descDf = hiveContext.sql("DESCRIBE employee")
    assert(!QueryAnalysis.hasAggregateFunction(descDf.queryExecution.optimizedPlan))
    val createDf = hiveContext.sql("create table test1(code int, desc string)")
    assert(!QueryAnalysis.hasAggregateFunction(createDf.queryExecution.optimizedPlan))
    val dropDf = hiveContext.sql("drop table test1")
    assert(!QueryAnalysis.hasAggregateFunction(dropDf.queryExecution.optimizedPlan))

    // Assert a simple query isn't flagged as aggregate
    val simpleDf = hiveContext.sql("select * from employee")
    assert(!QueryAnalysis.hasAggregateFunction(simpleDf.queryExecution.optimizedPlan))

    // Assert a joined query isn't flagged as aggregate
    val joinedDf = hiveContext.sql(
      "select * from employee join department on employee.city = department.location")
    assert(!QueryAnalysis.hasAggregateFunction(joinedDf.queryExecution.optimizedPlan))

    // Test aggregate clauses are flagged as aggregate
    getAggregateClauses("salary", "id").foreach { agg =>
      val df = hiveContext.sql(
        s"select aggClause from (select $agg as aggClause from employee group by city, address)t1")
      assert(QueryAnalysis.hasAggregateFunction(df.queryExecution.optimizedPlan))
      // The below line fails because of CDH-51222
      // assertHiveInputs(df.queryExecution, "employee", "salary")
    }

    // Test complex joins are flagged as aggregate
    getAggregateClauses("budget", "dept_id").foreach { agg =>
      val df = hiveContext.sql(
        s"""select emp.first_name, emp.last_name, aggClause,location
              from employee emp
            join
              (
                select
                  $agg as aggClause,
                  location
                from department
                group by location, dept_id
                sort by location
                limit 15
              ) dept
                on emp.city = dept.location"""
      )
      // Assert aggregate function is detected
      assert(QueryAnalysis.hasAggregateFunction(df.queryExecution.optimizedPlan))

      // Assert that the input metadata has all columns other than the aggregated column
      // After fixing CDH-51222 assert on that column too.
      val inputMetadata = QueryAnalysis.getInputMetadata(df.queryExecution)
      // When CDH-51222 is fixed change 3 to 4
      assert(inputMetadata.length === 3)
      assertHiveFieldExists(inputMetadata, "employee", "first_name")
      assertHiveFieldExists(inputMetadata, "employee", "last_name")
      assertHiveFieldExists(inputMetadata, "department", "location")
      // The below line fails because of CDH-51222
      // assertHiveFieldExists(inputMetadata, "department", "budget")
    }

    // Test ctas with complex joins are flagged as aggregate
    getAggregateClauses("budget", "dept_id").foreach { agg =>
      hiveContext.sql("drop table if exists new_employee")
      val df = hiveContext.sql(
        s"""create table new_employee as
            select emp.first_name, emp.last_name, aggClause,location
              from employee emp
            join
              (
                select
                  $agg as aggClause,
                  location
                from department
                group by location, dept_id
                sort by location
                limit 15
              ) dept
                on emp.city = dept.location"""
      )
      // Assert aggregate function is detected
      assert(QueryAnalysis.hasAggregateFunction(df.queryExecution.optimizedPlan))

      // Assert that the input metadata has all columns other than the aggregated column
      // After fixing CDH-51222 assert on that column too.
      val inputMetadata = QueryAnalysis.getInputMetadata(df.queryExecution)
      // When CDH-51222 is fixed change 3 to 4
      assert(inputMetadata.length === 3)
      assertHiveFieldExists(inputMetadata, "employee", "first_name")
      assertHiveFieldExists(inputMetadata, "employee", "last_name")
      assertHiveFieldExists(inputMetadata, "department", "location")
      // The below line fails because of CDH-51222
      // assertHiveFieldExists(inputMetadata, "department", "budget")
      assertHiveOutputs(df.queryExecution, "new_employee",
        Seq("first_name", "last_name", "aggClause", "location"))
    }

    // Test inserts with complex joins are flagged as aggregate
    TestUtils.createTable(hiveContext, "emp_insert", Map("first_name" -> "String", "last_name" ->
      "String", "agg_clause" -> "Float", "location" -> "String"))
    Seq("sum(budget)", "max(budget)", "min(budget)", "avg(budget)", "count(*)")
      .foreach { agg =>
      val df = hiveContext.sql(
        s"""insert into emp_insert
            select emp.first_name as fname, emp.last_name as lname, aggClauseInsert,location
              from employee emp
            join
              (
                select
                  $agg as aggClauseInsert,
                  location
                from department
                group by location, dept_id
                sort by location
                limit 15
              ) dept
                on emp.city = dept.location"""
      )
      // Assert aggregate function is detected
      assert(QueryAnalysis.hasAggregateFunction(df.queryExecution.optimizedPlan))

      // Assert that the input metadata has all columns other than the aggregated column
      // After fixing CDH-51222 assert on that column too.
      val inputMetadata = QueryAnalysis.getInputMetadata(df.queryExecution)
      // When CDH-51222 is fixed change 3 to 4
      assert(inputMetadata.length === 3)
      assertHiveFieldExists(inputMetadata, "employee", "first_name")
      assertHiveFieldExists(inputMetadata, "employee", "last_name")
      assertHiveFieldExists(inputMetadata, "department", "location")
      // The below line fails because of CDH-51222
      // assertHiveFieldExists(inputMetadata, "department", "budget")
      assertHiveOutputs(df.queryExecution, "emp_insert",
        Seq("first_name", "last_name", "agg_clause", "location"))
    }
  }
  
  private def getAggregateClauses(col1: String, col2: String): Seq[String] = {
    Seq(
      s"sum($col1)",
      s"avg($col1)",
      s"count(*)",
      s"max($col1)",
      s"min($col1)",
      s"variance($col1)",
      s"stddev_pop($col1)",
      s"covar_pop($col1, $col2)",
      s"corr($col1, $col2)",
      s"percentile($col1,0)",
      s"histogram_numeric($col1,5)",
      s"collect_set($col1)"
    )
  }

  private def assertComplexInsert() = {
    val qe = TestQeListener.getAndClear()
    val inputMetadata = QueryAnalysis.getInputMetadata(qe)
    assert(inputMetadata.length === 2)
    assertHiveFieldExists(inputMetadata, "test_table_1", "salary")
    assertHiveFieldExists(inputMetadata, "test_table_2", "code")
    assertHiveOutputs(qe, "test_table_6", Seq("code_new", "salary_new"))
  }

  def assertHiveInputs(
      qe: org.apache.spark.sql.execution.QueryExecution,
      table: String,
      columns: Seq[String],
      db: String = "default") {
    val inputMetadata = QueryAnalysis.getInputMetadata(qe)
    assert(inputMetadata.length === columns.size)
    columns.foreach(assertHiveFieldExists(inputMetadata, table, _, db))
  }

  def assertHiveOutputs(
      qe: org.apache.spark.sql.execution.QueryExecution,
      table: String,
      columns: Seq[String],
      db: String = "default") {
    val outputMetadata = QueryAnalysis.getOutputMetaData(qe)
    assert(outputMetadata.isDefined)
    assert(outputMetadata.get.fields.size === columns.size)
    assert(outputMetadata.get.source === db + "." + table)
    assert(outputMetadata.get.dataSourceType === DataSourceType.HIVE)
    assert(outputMetadata.get.fields.forall(columns.contains(_)))
  }

  implicit class SqlCmd(sql: String) {
    def cmd: () => Unit = { () =>
      new QueryExecution(sql).stringResult(): Unit
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    hiveContext.listenerManager.clear()
  }
}

case class Person(name: String, age: Long)
