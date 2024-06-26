/*
 * Copyright 2021 Qbeast Analytics, S.L.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.qbeast.spark.internal

import io.qbeast.core.model.QTableID
import io.qbeast.spark.index.ColumnsToIndex
import org.apache.spark.qbeast.config.DEFAULT_CUBE_SIZE
import org.apache.spark.sql.delta.DeltaOptions
import org.apache.spark.sql.AnalysisExceptionFactory
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession

/**
 * Container for Qbeast options.
 *
 * @param columnsToIndex
 *   value of columnsToIndex option
 * @param cubeSize
 *   value of cubeSize option
 * @param stats
 *   the stats if available
 * @param txnVersion
 *   the transaction identifier
 * @param txnAppId
 *   the application identifier
 * @param userMetadata
 *   user-provided metadata for each CommitInfo
 */
case class QbeastOptions(
    columnsToIndex: Seq[String],
    cubeSize: Int,
    stats: Option[DataFrame],
    txnAppId: Option[String],
    txnVersion: Option[String],
    userMetadata: Option[String])

/**
 * Options available when trying to write in qbeast format
 */

object QbeastOptions {
  val COLUMNS_TO_INDEX: String = "columnsToIndex"
  val CUBE_SIZE: String = "cubeSize"
  val PATH: String = "path"
  val STATS: String = "columnStats"
  val TXN_APP_ID: String = DeltaOptions.TXN_APP_ID
  val TXN_VERSION: String = DeltaOptions.TXN_VERSION
  val USER_METADATA: String = DeltaOptions.USER_METADATA_OPTION

  /**
   * Gets the columns to index from the options
   * @param options
   *   the options passed on the dataframe
   * @return
   */
  private def getColumnsToIndex(options: Map[String, String]): Seq[String] = {
    val encodedColumnsToIndex = options.getOrElse(
      COLUMNS_TO_INDEX, {
        throw AnalysisExceptionFactory.create(
          "you must specify the columns to index in a comma separated way" +
            " as .option(columnsToIndex, ...)")
      })
    ColumnsToIndex.decode(encodedColumnsToIndex)
  }

  /**
   * Gets the desired cube size from the options
   * @param options
   *   the options passed on the dataframe
   * @return
   */

  private def getDesiredCubeSize(options: Map[String, String]): Int = {
    options.get(CUBE_SIZE) match {
      case Some(value) => value.toInt
      case None => DEFAULT_CUBE_SIZE
    }
  }

  /**
   * Get the column stats from the options This stats should be in a JSON formatted string with
   * the following schema {columnName_min:value, columnName_max:value, ...}
   * @param options
   *   the options passed on the dataframe
   * @return
   */
  private def getStats(options: Map[String, String]): Option[DataFrame] = {
    val spark = SparkSession.active

    options.get(STATS) match {
      case Some(value) =>
        import spark.implicits._
        val ds = Seq(value).toDS()
        val df = spark.read
          .option("inferTimestamp", "true")
          .option("timestampFormat", "yyyy-MM-dd HH:mm:ss.SSSSSS'Z'")
          .json(ds)
        Some(df)
      case None => None
    }
  }

  private def getTxnAppId(options: Map[String, String]): Option[String] = options.get(TXN_APP_ID)

  private def getTxnVersion(options: Map[String, String]): Option[String] =
    options.get(TXN_VERSION)

  private def getUserMetadata(options: Map[String, String]): Option[String] =
    options.get(USER_METADATA)

  /**
   * Create QbeastOptions object from options map
   * @param options
   *   the options map
   * @return
   *   the QbeastOptions
   */
  def apply(options: Map[String, String]): QbeastOptions = {
    val columnsToIndex = getColumnsToIndex(options)
    val desiredCubeSize = getDesiredCubeSize(options)
    val stats = getStats(options)
    val txnAppId = getTxnAppId(options)
    val txnVersion = getTxnVersion(options)
    val userMetadata = getUserMetadata(options)
    QbeastOptions(columnsToIndex, desiredCubeSize, stats, txnAppId, txnVersion, userMetadata)
  }

  /**
   * The empty options to be used as a placeholder.
   */
  lazy val empty: QbeastOptions =
    QbeastOptions(Seq.empty, DEFAULT_CUBE_SIZE, None, None, None, None)

  def loadTableIDFromParameters(parameters: Map[String, String]): QTableID = {
    new QTableID(
      parameters.getOrElse(
        PATH, {
          throw AnalysisExceptionFactory.create("'path' is not specified")
        }))
  }

  def checkQbeastProperties(parameters: Map[String, String]): Unit = {
    require(
      parameters.contains("columnsToIndex") || parameters.contains("columnstoindex"),
      throw AnalysisExceptionFactory.create("'columnsToIndex is not specified"))
  }

}
