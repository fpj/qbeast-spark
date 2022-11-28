package io.qbeast.core.model

import io.qbeast.core.model.CubeWeightsBuilder.estimateGroupCubeSize
import io.qbeast.core.model.Weight.MaxValue

import scala.collection.mutable

object CubeWeightsBuilder {
  val minGroupCubeSize = 30

  /**
   * Estimates the groupCubeSize depending on the input parameters.
   * The formula to compute the estimated value is the following:
   *   numGroups = MAX(numPartitions, (numElements / cubeWeightsBufferCapacity))
   *   groupCubeSize = desiredCubeSize / numGroups
   * @param desiredCubeSize the desired cube size
   * @param numPartitions the number of partitions
   * @param numElements the total number of elements in the input data
   * @param bufferCapacity buffer capacity; number of elements that fit in memory
   * @return the estimated groupCubeSize as a Double.
   */
  private[CubeWeightsBuilder] def estimateGroupCubeSize(
      desiredCubeSize: Int,
      numPartitions: Int,
      numElements: Long,
      bufferCapacity: Long): Int = {
    val numGroups = Math.max(numPartitions, numElements / bufferCapacity)
    val groupCubeSize = desiredCubeSize / numGroups
    Math.max(minGroupCubeSize, groupCubeSize.toInt)
  }

}

/**
 * Builder for creating cube weights.
 *
 * @param desiredCubeSize           the desired cube size
 * @param groupCubeSize                 the number of elements for each group
 * @param bufferCapacity the buffer capacity to store the cube weights in memory
 * @param replicatedOrAnnouncedSet the announced or replicated cubes' identifiers
 */
class CubeWeightsBuilder protected (
    private val desiredCubeSize: Int,
    private val groupCubeSize: Int,
    private val bufferCapacity: Long,
    private val replicatedOrAnnouncedSet: Set[CubeId] = Set.empty)
    extends Serializable {

  def this(
      indexStatus: IndexStatus,
      numPartitions: Int,
      numElements: Long,
      bufferCapacity: Long) =
    this(
      indexStatus.revision.desiredCubeSize,
      estimateGroupCubeSize(
        indexStatus.revision.desiredCubeSize,
        numPartitions,
        numElements,
        bufferCapacity),
      bufferCapacity,
      indexStatus.replicatedOrAnnouncedSet)

  private val byWeight = Ordering.by[PointWeightAndParent, Weight](_.weight).reverse
  protected val queue = new mutable.PriorityQueue[PointWeightAndParent]()(byWeight)
  private var resultBuffer = Seq.empty[LocalTree]

  /**
   * Updates the builder with given point with weight.
   *
   * @param point  the point
   * @param weight the weight
   * @param parent the parent cube identifier used to find
   *               the container cube if available
   * @return this instance
   */
  def update(point: Point, weight: Weight, parent: Option[CubeId] = None): CubeWeightsBuilder = {
    queue.enqueue(PointWeightAndParent(point, weight, parent))
    if (queue.size >= bufferCapacity) {
      val unpopulatedTree = resultInternal()
      resultBuffer = resultBuffer :+ populateTreeSize(unpopulatedTree).toMap
      queue.clear()
    }
    this
  }

  /**
   * Builds the resulting cube weights sequence.
   *
   * @return the resulting cube weights map
   */
  def result(): Seq[LocalTree] = {
    val unpopulatedTree = resultInternal()
    if (unpopulatedTree.nonEmpty) {
      resultBuffer :+ populateTreeSize(unpopulatedTree).toMap
    } else {
      resultBuffer
    }
  }

  def resultInternal(): mutable.Map[CubeId, CubeInfo] = {
    val weights = mutable.Map.empty[CubeId, WeightAndCount]
    while (queue.nonEmpty) {
      val PointWeightAndParent(point, weight, parent) = queue.dequeue()
      val containers = parent match {
        case Some(parentCubeId) => CubeId.containers(point, parentCubeId)
        case None => CubeId.containers(point)
      }
      var continue = true
      while (continue && containers.hasNext) {
        val cubeId = containers.next()
        val weightAndCount = weights.getOrElseUpdate(cubeId, new WeightAndCount(MaxValue, 0))
        if (weightAndCount.count < groupCubeSize) {
          weightAndCount.count += 1
          if (weightAndCount.count == groupCubeSize) {
            weightAndCount.weight = weight
          }
          continue = replicatedOrAnnouncedSet.contains(cubeId)
        }
      }
    }

    weights
      .map {
        case (cubeId, weightAndCount) if weightAndCount.count == groupCubeSize =>
          val nw = NormalizedWeight(weightAndCount.weight)
          (cubeId, CubeInfo(nw, groupCubeSize))

        case (cubeId, weightAndCount) =>
          val nw = NormalizedWeight(desiredCubeSize, weightAndCount.count)
          (cubeId, CubeInfo(nw, weightAndCount.count))
      }
  }

  /**
   * Populate cube tree sizes for all existing cubes in the tree
   * @param cubeMap local tree
   * @return
   */
  def populateTreeSize(cubeMap: mutable.Map[CubeId, CubeInfo]): mutable.Map[CubeId, CubeInfo] = {
    // This could have been implemented in the DFS fashion if it wasn't for
    // the optimization processes where we don't know the cubes the DFS traversal
    // should begin with.
    // Unlike the global index, the local trees are assumed to have no corrupt branches.

    // TODO: Alternatively, we can usd DFS on cubes that don't have parent nodes
    //  in the map as the starting cubes. This is to be discussed.
    val levelCubes = cubeMap.keys.groupBy(_.depth)
    val minLevel = levelCubes.keys.min
    val maxLevel = levelCubes.keys.max
    (maxLevel until minLevel by -1) foreach { level =>
      // We group cubes by their parent cube to avoid copying CubeInfo too many times
      // An alternative is to use a var for treeSize in CubeInfo, but we don't want
      // this value to be modified once the values are populated.
      levelCubes(level).groupBy(c => c.parent.get) foreach { case (parent, siblings) =>
        val parentCubeInfo = cubeMap(parent)
        var treeSize = parentCubeInfo.treeSize
        siblings.foreach(c => treeSize += cubeMap(c).treeSize)
        cubeMap(parent) = parentCubeInfo.copy(treeSize = treeSize)
      }
    }
    cubeMap
  }

}

/**
 * Weight and count.
 *
 * @param weight the weight
 * @param count  the count
 */
private class WeightAndCount(var weight: Weight, var count: Int)

/**
 * Point, weight and parent cube identifier if available.
 *
 * @param point  the point
 * @param weight the weight
 * @param parent the parent
 */
protected case class PointWeightAndParent(point: Point, weight: Weight, parent: Option[CubeId])

case class CubeInfo(normalizedWeight: NormalizedWeight, treeSize: Double)
