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

package org.apache.spark.sql.hive.execution

import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util
import java.util.{Date, Random}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.common.FileUtils

import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hadoop.hive.ql.exec.TaskRunner
import org.apache.hadoop.hive.ql.plan.TableDesc
import org.apache.hadoop.hive.ql.{Context, ErrorMsg}
import org.apache.hadoop.hive.serde2.Serializer
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils.ObjectInspectorCopyOption
import org.apache.hadoop.hive.serde2.objectinspector._
import org.apache.hadoop.mapred.{FileOutputCommitter, FileOutputFormat, JobConf}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.execution.{SparkPlan, UnaryNode}
import org.apache.spark.sql.hive.HiveShim.{ShimFileSinkDesc => FileSinkDesc}
import org.apache.spark.sql.hive._
import org.apache.spark.sql.types.DataType
import org.apache.spark.{SparkException, TaskContext}
import org.apache.spark.util.SerializableJobConf

private[hive]
case class InsertIntoHiveTable(
    table: MetastoreRelation,
    partition: Map[String, Option[String]],
    child: SparkPlan,
    overwrite: Boolean,
    ifNotExists: Boolean) extends UnaryNode with HiveInspectors {

  @transient val sc: HiveContext = sqlContext.asInstanceOf[HiveContext]
  @transient lazy val outputClass = newSerializer(table.tableDesc).getSerializedClass
  @transient private lazy val hiveContext = new Context(sc.hiveconf)
  @transient private lazy val catalog = sc.catalog

  @transient var createdTempDir: Option[Path] = None
  val stagingDir = new HiveConf().getVar(HiveConf.ConfVars.STAGINGDIR)

  private def executionId: String = {
    val rand: Random = new Random
    val format: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS")
    val executionId: String = "hive_" + format.format(new Date) + "_" + Math.abs(rand.nextLong)
    executionId
  }

  private def getStagingDir(inputPath: Path, hadoopConf: Configuration): Path = {
    val inputPathUri: URI = inputPath.toUri
    val inputPathName: String = inputPathUri.getPath
    val fs: FileSystem = inputPath.getFileSystem(hadoopConf)
    val stagingPathName: String =
      if (inputPathName.indexOf(stagingDir) == -1) {
        new Path(inputPathName, stagingDir).toString
      } else {
        inputPathName.substring(0, inputPathName.indexOf(stagingDir) + stagingDir.length)
      }
    val dir: Path =
      fs.makeQualified(
        new Path(stagingPathName + "_" + executionId + "-" + TaskRunner.getTaskRunnerID))
    logDebug("Created staging dir = " + dir + " for path = " + inputPath)
    try {
      if (!FileUtils.mkdir(fs, dir, true, hadoopConf)) {
        throw new IllegalStateException("Cannot create staging directory  '" + dir.toString + "'")
      }
      createdTempDir = Some(dir)
      fs.deleteOnExit(dir)
    }
    catch {
      case e: IOException =>
        throw new RuntimeException(
          "Cannot create staging directory '" + dir.toString + "': " + e.getMessage, e)

    }
    return dir
  }

  private def getExternalScratchDir(extURI: URI, hadoopConf: Configuration): Path = {
    getStagingDir(new Path(extURI.getScheme, extURI.getAuthority, extURI.getPath), hadoopConf)
  }

  def getExternalTmpPath(path: Path, hadoopConf: Configuration): Path = {
    val extURI: URI = path.toUri
    if (extURI.getScheme == "viewfs") {
      getExtTmpPathRelTo(path.getParent, hadoopConf)
    } else {
      new Path(getExternalScratchDir(extURI, hadoopConf), "-ext-10000")
    }
  }

  def getExtTmpPathRelTo(path: Path, hadoopConf: Configuration): Path = {
    new Path(getStagingDir(path, hadoopConf), "-ext-10000") // Hive uses 10000
  }

  private def newSerializer(tableDesc: TableDesc): Serializer = {
    val serializer = tableDesc.getDeserializerClass.newInstance().asInstanceOf[Serializer]
    serializer.initialize(null, tableDesc.getProperties)
    serializer
  }

  def output: Seq[Attribute] = Seq.empty

  private def saveAsHiveFile(
      rdd: RDD[InternalRow],
      valueClass: Class[_],
      fileSinkConf: FileSinkDesc,
      conf: SerializableJobConf,
      writerContainer: SparkHiveWriterContainer): Unit = {
    assert(valueClass != null, "Output value class not set")
    conf.value.setOutputValueClass(valueClass)

    val outputFileFormatClassName = fileSinkConf.getTableInfo.getOutputFileFormatClassName
    assert(outputFileFormatClassName != null, "Output format class not set")
    conf.value.set("mapred.output.format.class", outputFileFormatClassName)

    FileOutputFormat.setOutputPath(
      conf.value,
      SparkHiveWriterContainer.createPathFromString(fileSinkConf.getDirName, conf.value))
    log.debug("Saving as hadoop file of type " + valueClass.getSimpleName)

    writerContainer.driverSideSetup()
    sc.sparkContext.runJob(rdd, writeToFile _)
    writerContainer.commitJob()

    // Note that this function is executed on executor side
    def writeToFile(context: TaskContext, iterator: Iterator[InternalRow]): Unit = {
      val serializer = newSerializer(fileSinkConf.getTableInfo)
      val standardOI = ObjectInspectorUtils
        .getStandardObjectInspector(
          fileSinkConf.getTableInfo.getDeserializer.getObjectInspector,
          ObjectInspectorCopyOption.JAVA)
        .asInstanceOf[StructObjectInspector]

      val fieldOIs = standardOI.getAllStructFieldRefs.asScala
        .map(_.getFieldObjectInspector).toArray
      val dataTypes: Array[DataType] = child.output.map(_.dataType).toArray
      val wrappers = fieldOIs.zip(dataTypes).map { case (f, dt) => wrapperFor(f, dt)}
      val outputData = new Array[Any](fieldOIs.length)

      writerContainer.executorSideSetup(context.stageId, context.partitionId, context.attemptNumber)

      iterator.foreach { row =>
        var i = 0
        while (i < fieldOIs.length) {
          outputData(i) = if (row.isNullAt(i)) null else wrappers(i)(row.get(i, dataTypes(i)))
          i += 1
        }

        writerContainer
          .getLocalFileWriter(row, table.schema)
          .write(serializer.serialize(outputData, standardOI))
      }

      writerContainer.close()
    }
  }

  def isMergeRequired(tableLocation: Path, hiveconf: HiveConf) = {
    val mergeSparkFiles = hiveconf.getBoolVar(ConfVars.HIVEMERGESPARKFILES)
    val taskSize = hiveconf.getLongVar(ConfVars.HIVEMERGEMAPFILESSIZE)
    val avgConditionSize = hiveconf.getLongVar(ConfVars.HIVEMERGEMAPFILESAVGSIZE)

    var targetTaskNum = 0
    if(mergeSparkFiles) {
      val fs = tableLocation.getFileSystem(hiveconf)
      assert(fs.getFileStatus(tableLocation).isDirectory, "Table outputPath should be directory!")
      var totalSize = 0L
      var fileNum = 0L
      var smallFileNum = 0L
      fs.listStatus(tableLocation).map { status =>
        if (!status.getPath().getName().startsWith(stagingDir)) {
          totalSize += status.getLen
          fileNum += 1
          if(status.getLen < avgConditionSize) {
            smallFileNum = smallFileNum + 1
          }
        }
      }

      if(fileNum > 1 && taskSize > 0 &&
         (totalSize / fileNum < avgConditionSize || smallFileNum > 10)) {
        targetTaskNum = (totalSize / taskSize).toInt + 1
      }
      logInfo(s"calculate table(partition) size, path=$tableLocation, totalSize=$totalSize, " +
        s"fileNum=$fileNum, smallFileNum=$smallFileNum, targetTaskNum=$targetTaskNum" )
    }
    (targetTaskNum > 0,targetTaskNum)
  }
  /**
   * Inserts all the rows in the table into Hive.  Row objects are properly serialized with the
   * `org.apache.hadoop.hive.serde2.SerDe` and the
   * `org.apache.hadoop.mapred.OutputFormat` provided by the table definition.
   *
   * Note: this is run once and then kept to avoid double insertions.
   */
  protected[sql] lazy val sideEffectResult: Seq[InternalRow] = {
    // Have to pass the TableDesc object to RDD.mapPartitions and then instantiate new serializer
    // instances within the closure, since Serializer is not serializable while TableDesc is.
    val tableDesc = table.tableDesc
    val tableLocation = table.hiveQlTable.getDataLocation
    val jobConf = new JobConf(sc.hiveconf)
    val tmpLocation = getExternalTmpPath(tableLocation, jobConf)

    val fileSinkConf = new FileSinkDesc(tmpLocation.toString, tableDesc, false)
    val isCompressed = sc.hiveconf.getBoolean(
      ConfVars.COMPRESSRESULT.varname, ConfVars.COMPRESSRESULT.defaultBoolVal)

    if (isCompressed) {
      // Please note that isCompressed, "mapred.output.compress", "mapred.output.compression.codec",
      // and "mapred.output.compression.type" have no impact on ORC because it uses table properties
      // to store compression information.
      sc.hiveconf.set("mapred.output.compress", "true")
      fileSinkConf.setCompressed(true)
      fileSinkConf.setCompressCodec(sc.hiveconf.get("mapred.output.compression.codec"))
      fileSinkConf.setCompressType(sc.hiveconf.get("mapred.output.compression.type"))
    }

    val numDynamicPartitions = partition.values.count(_.isEmpty)
    val numStaticPartitions = partition.values.count(_.nonEmpty)
    val partitionSpec = partition.map {
      case (key, Some(value)) => key -> value
      case (key, None) => key -> ""
    }

    // All partition column names in the format of "<column name 1>/<column name 2>/..."
    val partitionColumns = fileSinkConf.getTableInfo.getProperties.getProperty("partition_columns")
    val partitionColumnNames = Option(partitionColumns).map(_.split("/")).orNull

    // Validate partition spec if there exist any dynamic partitions
    if (numDynamicPartitions > 0) {
      // Report error if dynamic partitioning is not enabled
      if (!sc.hiveconf.getBoolVar(HiveConf.ConfVars.DYNAMICPARTITIONING)) {
        throw new SparkException(ErrorMsg.DYNAMIC_PARTITION_DISABLED.getMsg)
      }

      // Report error if dynamic partition strict mode is on but no static partition is found
      if (numStaticPartitions == 0 &&
        sc.hiveconf.getVar(HiveConf.ConfVars.DYNAMICPARTITIONINGMODE).equalsIgnoreCase("strict")) {
        throw new SparkException(ErrorMsg.DYNAMIC_PARTITION_STRICT_MODE.getMsg)
      }

      // Report error if any static partition appears after a dynamic partition
      val isDynamic = partitionColumnNames.map(partitionSpec(_).isEmpty)
      if (isDynamic.init.zip(isDynamic.tail).contains((true, false))) {
        throw new SparkException(ErrorMsg.PARTITION_DYN_STA_ORDER.getMsg)
      }
    }

    val jobConfSer = new SerializableJobConf(jobConf)

    // When speculation is on and output committer class name contains "Direct", we should warn
    // users that they may loss data if they are using a direct output committer.
    val speculationEnabled = sqlContext.sparkContext.conf.getBoolean("spark.speculation", false)
    val outputCommitterClass = jobConf.get("mapred.output.committer.class", "")
    if (speculationEnabled && outputCommitterClass.contains("Direct")) {
      val warningMessage =
        s"$outputCommitterClass may be an output committer that writes data directly to " +
          "the final location. Because speculation is enabled, this output committer may " +
          "cause data loss (see the case in SPARK-10063). If possible, please use a output " +
          "committer that does not have this behavior (e.g. FileOutputCommitter)."
      logWarning(warningMessage)
    }

    val writerContainer = if (numDynamicPartitions > 0) {
      val dynamicPartColNames = partitionColumnNames.takeRight(numDynamicPartitions)
      new SparkHiveDynamicPartitionWriterContainer(jobConf, fileSinkConf, dynamicPartColNames)
    } else {
      new SparkHiveWriterContainer(jobConf, fileSinkConf)
    }

    saveAsHiveFile(child.execute(), outputClass, fileSinkConf, jobConfSer, writerContainer)

    val outputPath = FileOutputFormat.getOutputPath(jobConf)
    // Have to construct the format of dbname.tablename.
    val qualifiedTableName = s"${table.databaseName}.${table.tableName}"
    // TODO: Correctly set holdDDLTime.
    // In most of the time, we should have holdDDLTime = false.
    // holdDDLTime will be true when TOK_HOLD_DDLTIME presents in the query as a hint.
    val holdDDLTime = false
    if (partition.nonEmpty) {

      // loadPartition call orders directories created on the iteration order of the this map
      val orderedPartitionSpec = new util.LinkedHashMap[String, String]()
      table.hiveQlTable.getPartCols.asScala.foreach { entry =>
        orderedPartitionSpec.put(entry.getName, partitionSpec.get(entry.getName).getOrElse(""))
      }

      // inheritTableSpecs is set to true. It should be set to false for a IMPORT query
      // which is currently considered as a Hive native command.
      val inheritTableSpecs = true
      // TODO: Correctly set isSkewedStoreAsSubdir.
      val isSkewedStoreAsSubdir = false
      if (numDynamicPartitions > 0) {
        catalog.synchronized {
          catalog.client.loadDynamicPartitions(
            outputPath.toString,
            qualifiedTableName,
            orderedPartitionSpec,
            overwrite,
            numDynamicPartitions,
            holdDDLTime,
            isSkewedStoreAsSubdir)
        }
      } else {
        // scalastyle:off
        // ifNotExists is only valid with static partition, refer to
        // https://cwiki.apache.org/confluence/display/Hive/LanguageManual+DML#LanguageManualDML-InsertingdataintoHiveTablesfromqueries
        // scalastyle:on
        val oldPart =
          catalog.client.getPartitionOption(
            catalog.client.getTable(table.databaseName, table.tableName),
            partitionSpec.asJava)

        if (oldPart.isEmpty || !ifNotExists) {
            catalog.client.loadPartition(
              outputPath.toString,
              qualifiedTableName,
              orderedPartitionSpec,
              overwrite,
              holdDDLTime,
              inheritTableSpecs,
              isSkewedStoreAsSubdir)
        }
      }
    } else {
      catalog.client.loadTable(
        outputPath.toString, // TODO: URI
        qualifiedTableName,
        overwrite,
        holdDDLTime)
    }

    // Attempt to delete the staging directory and the inclusive files. If failed, the files are
    // expected to be dropped at the normal termination of VM since deleteOnExit is used.
    try {
      createdTempDir.foreach { path => path.getFileSystem(jobConf).delete(path, true) }
    } catch {
      case NonFatal(e) =>
        logWarning(s"Unable to delete staging directory: $stagingDir.\n" + e)
    }

    // Invalidate the cache.
    sqlContext.cacheManager.invalidateCache(table)

    // It would be nice to just return the childRdd unchanged so insert operations could be chained,
    // however for now we return an empty list to simplify compatibility checks with hive, which
    // does not return anything for insert operations.
    // TODO: implement hive compatibility as rules.
    if(partition.isEmpty || numDynamicPartitions ==0) {
      val destPath = if (partition.isEmpty) {
        tableLocation
      } else {
        partition.foldLeft(tableLocation) {
          case (path, (k, Some(v))) => new Path(path, s"$k=$v")
        }
      }

      val (chDir, targetTaskNum) = isMergeRequired(destPath, sc.hiveconf)
      if (chDir) {
        val srcDF = if(partition.isEmpty) {
          sqlContext.table(qualifiedTableName)
        } else {
          partition.foldLeft(sqlContext.table(qualifiedTableName)) {
            case (filterDF, (k, Some(v))) => filterDF.filter(s"$k = $v")
          }
        }
        val childRDD = srcDF.repartition(targetTaskNum).queryExecution.toRdd
        saveAsHiveFile(childRDD, outputClass, fileSinkConf, jobConfSer, writerContainer)

        if (partition.nonEmpty) {

          // loadPartition call orders directories created on the iteration order of the this map
          val orderedPartitionSpec = new util.LinkedHashMap[String, String]()
          table.hiveQlTable.getPartCols.asScala.foreach { entry =>
            orderedPartitionSpec.put(entry.getName, partitionSpec.get(entry.getName).getOrElse(""))
          }

          // inheritTableSpecs is set to true. It should be set to false for a IMPORT query
          // which is currently considered as a Hive native command.
          val inheritTableSpecs = true
          // TODO: Correctly set isSkewedStoreAsSubdir.
          val isSkewedStoreAsSubdir = false
          if (numDynamicPartitions > 0) {
            catalog.synchronized {
              catalog.client.loadDynamicPartitions(
                outputPath.toString,
                qualifiedTableName,
                orderedPartitionSpec,
                overwrite,
                numDynamicPartitions,
                holdDDLTime,
                isSkewedStoreAsSubdir)
            }
          } else {
            // scalastyle:off
            // ifNotExists is only valid with static partition, refer to
            // https://cwiki.apache.org/confluence/display/Hive/LanguageManual+DML#LanguageManualDML-InsertingdataintoHiveTablesfromqueries
            // scalastyle:on
            val oldPart =
            catalog.client.getPartitionOption(
              catalog.client.getTable(table.databaseName, table.tableName),
              partitionSpec.asJava)

            if (oldPart.isEmpty || !ifNotExists) {
              catalog.client.loadPartition(
                outputPath.toString,
                qualifiedTableName,
                orderedPartitionSpec,
                overwrite,
                holdDDLTime,
                inheritTableSpecs,
                isSkewedStoreAsSubdir)
            }
          }
        } else {
          catalog.client.loadTable(
            outputPath.toString, // TODO: URI
            qualifiedTableName,
            overwrite,
            holdDDLTime)
        }

        // Attempt to delete the staging directory and the inclusive files. If failed, the files are
        // expected to be dropped at the normal termination of VM since deleteOnExit is used.
        try {
          createdTempDir.foreach { path => path.getFileSystem(jobConf).delete(path, true) }
        } catch {
          case NonFatal(e) =>
            logWarning(s"Unable to delete staging directory: $stagingDir.\n" + e)
        }
      }
    }

    Seq.empty[InternalRow]
  }

  override def executeCollect(): Array[InternalRow] = sideEffectResult.toArray

  protected override def doExecute(): RDD[InternalRow] = {
    sqlContext.sparkContext.parallelize(sideEffectResult.asInstanceOf[Seq[InternalRow]], 1)
  }
}
