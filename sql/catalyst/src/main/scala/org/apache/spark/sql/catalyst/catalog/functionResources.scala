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

package org.apache.spark.sql.catalyst.catalog

import java.io.File
import java.net.URL
import java.util.Locale

import org.apache.hadoop.fs.FsUrlStreamHandlerFactory
import org.apache.hadoop.fs.Path

import org.apache.spark.Logging
import org.apache.spark.SparkContext
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.internal.NonClosableMutableURLClassLoader

/** A trait that represents the type of a resourced needed by a function. */
abstract class FunctionResourceType(val resourceType: String)

object JarResource extends FunctionResourceType("jar")

object FileResource extends FunctionResourceType("file")

// We do not allow users to specify an archive because it is YARN specific.
// When loading resources, we will throw an exception and ask users to
// use --archive with spark submit.
object ArchiveResource extends FunctionResourceType("archive")

object FunctionResourceType {
  def fromString(resourceType: String): FunctionResourceType = {
    resourceType.toLowerCase(Locale.ROOT) match {
      case "jar" => JarResource
      case "file" => FileResource
      case "archive" => ArchiveResource
      case other =>
        throw new AnalysisException(s"Resource Type '$resourceType' is not supported.")
    }
  }
}

case class FunctionResource(resourceType: FunctionResourceType, uri: String)

/**
 * A simple trait representing a class that can be used to load resources used by
 * a function. Because only a SQLContext can load resources, we create this trait
 * to avoid of explicitly passing SQLContext around.
 */
trait FunctionResourceLoader {
  def loadResource(resource: FunctionResource): Unit
}

object DummyFunctionResourceLoader extends FunctionResourceLoader {
  override def loadResource(resource: FunctionResource): Unit = {
    throw new UnsupportedOperationException
  }
}

/**
 * Session shared [[FunctionResourceLoader]].
 */
class SessionResourceLoader(sc: SparkContext) extends FunctionResourceLoader with Logging {
  override def loadResource(resource: FunctionResource): Unit = {
    resource.resourceType match {
      case JarResource => addJar(resource.uri)
      case FileResource => sc.addFile(resource.uri)
      case ArchiveResource =>
        throw new AnalysisException(
          "Archive is not allowed to be loaded. If YARN mode is used, " +
            "please use --archives options while calling spark-submit.")
    }
  }

  /**
   * A classloader used to load all user-added jar.
   */
  lazy val jarClassLoader = new NonClosableMutableURLClassLoader(
    org.apache.spark.util.Utils.getContextOrSparkClassLoader)

  /**
   * Add a jar path to [[SparkContext]] and the classloader.
   *
   * Note: this method seems not access any session state, but a Hive based `SessionState` needs
   * to add the jar to its hive client for the current session. Hence, it still needs to be in
   * SessionState.
   */
  def addJar(path: String): Unit = {
    try {
      URL.setURLStreamHandlerFactory(new FsUrlStreamHandlerFactory())
    } catch {
      case e: Error =>
        logWarning("URL.setURLStreamHandlerFactory failed to set FsUrlStreamHandlerFactory")
    }
    sc.addJar(path)
    val uri = new Path(path).toUri
    val jarURL = if (uri.getScheme == null) {
      // `path` is a local file path without a URL scheme
      new File(path).toURI.toURL
    } else {
      // `path` is a URL with a scheme
      uri.toURL
    }
    jarClassLoader.addURL(jarURL)
    Thread.currentThread().setContextClassLoader(jarClassLoader)
  }
}