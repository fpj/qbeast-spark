/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.index

import io.qbeast.model._
import io.qbeast.spark.index.QbeastColumns.{cubeToReplicateColumnName, weightColumnName}
import io.qbeast.spark.internal.rules.Functions.qbeastHash
import org.apache.spark.sql.functions.{col, udaf}
import org.apache.spark.sql.{DataFrame, SparkSession}

trait OTreeDataAnalyzer {

  def analyze(
      data: DataFrame,
      indexStatus: IndexStatus,
      isReplication: Boolean): (DataFrame, TableChanges)

}

object DoublePassOTreeDataAnalyzer extends OTreeDataAnalyzer {

  /**
   * Estimates MaxWeight on DataFrame
   */
  val maxWeightEstimation = udaf(MaxWeightEstimation)

  private[index] def calculateRevisionChanges(
      data: DataFrame,
      revision: Revision): Option[RevisionChange] = {
    val columnStats = revision.columnTransformers.map(_.stats)
    val columnsExpr = columnStats.flatMap(_.columns)
    // This is a actions that will be executed on the dataframe
    val row = data.selectExpr(columnsExpr: _*).first()

    val newTransformation =
      revision.columnTransformers.map(_.makeTransformation(colName => row.getAs[Object](colName)))

    val transformationDelta = if (revision.transformations.isEmpty) {
      newTransformation.map(a => Some(a))
    } else {
      revision.transformations.zip(newTransformation).map {
        case (oldTransformation, newTransformation)
            if oldTransformation.isSupersededBy(newTransformation) =>
          Some(oldTransformation.merge(newTransformation))
        case _ => None
      }
    }

    if (transformationDelta.flatten.isEmpty) {
      None
    } else {
      Some(
        RevisionChange(
          newRevisionID = revision.revisionID + 1,
          supersededRevision = revision,
          timestamp = System.currentTimeMillis(),
          transformationsChanges = transformationDelta))

    }
  }

  override def analyze(
      dataFrame: DataFrame,
      indexStatus: IndexStatus,
      isReplication: Boolean): (DataFrame, TableChanges) = {
    val spaceChanges = calculateRevisionChanges(dataFrame, indexStatus.revision)
    val revision = spaceChanges match {
      case Some(revisionChange) =>
        revisionChange.newRevision
      case None => indexStatus.revision
    }
    val sqlContext = SparkSession.active.sqlContext
    import sqlContext.implicits._
    val weightedDataFrame = dataFrame.transform(df => addRandomWeight(df, revision))

    val partitionCount: Int = weightedDataFrame.rdd.getNumPartitions
    val partitionedDesiredCubeSize = if (partitionCount > 0) {
      revision.desiredCubeSize / partitionCount
    } else {
      revision.desiredCubeSize
    }
    val columnsToIndex = revision.columnTransformers.map(_.columnName)

    val partitionedEstimatedCubeWeights = weightedDataFrame
      .mapPartitions(rows => {
        val weights =
          new CubeWeightsBuilder(
            partitionedDesiredCubeSize,
            partitionCount,
            indexStatus.announcedSet,
            indexStatus.replicatedSet)
        rows.foreach { row =>
          val values = columnsToIndex.map(row.getAs[Any])
          val point = OTreeAlgorithmImpl.rowValuesToPoint(values, revision)
          val weight = Weight(row.getAs[Int](weightColumnName))
          if (isReplication) {
            val parentBytes = row.getAs[Array[Byte]](cubeToReplicateColumnName)
            val parent = Some(revision.createCubeId(parentBytes))
            weights.update(point, weight, parent)
          } else weights.update(point, weight)
        }
        weights.result().iterator
      })
    // These column names are the ones specified in case clas CubeNormalizedWeight
    val estimatedCubeWeights = partitionedEstimatedCubeWeights
      .groupBy("cubeBytes")
      .agg(maxWeightEstimation(col("normalizedWeight")))
      .collect()
      .map { row =>
        val bytes = row.getAs[Array[Byte]](0)
        val estimatedWeight = row.getAs[Double](1)
        (revision.createCubeId(bytes), estimatedWeight)
      }
      .toMap

    (
      weightedDataFrame,
      TableChanges(spaceChanges, IndexStatusChange(indexStatus, estimatedCubeWeights)))
  }

  private def addRandomWeight(df: DataFrame, revision: Revision): DataFrame = {
    df.withColumn(
      weightColumnName,
      qbeastHash(revision.columnTransformers.map(name => df(name.columnName)): _*))
  }

}
