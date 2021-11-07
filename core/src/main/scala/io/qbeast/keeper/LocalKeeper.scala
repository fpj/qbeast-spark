/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.keeper

import io.qbeast.model.QTableID

import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementation of Keeper which saves announced set in memory. This implementation is used
 * when no other implementation can be obtained from ServiceLoader.
 */
object LocalKeeper extends Keeper {
  private val generator = new AtomicInteger()
  private val announcedMap = scala.collection.mutable.Map.empty[(QTableID, Long), Set[String]]

  override def beginWrite(tableID: QTableID, revision: Long): Write = new LocalWrite(
    generator.getAndIncrement().toString,
    announcedMap.getOrElse((tableID, revision), Set.empty[String]))

  override def announce(tableID: QTableID, revision: Long, cubes: Seq[String]): Unit = {
    val announcedCubes = announcedMap.getOrElse((tableID, revision), Set.empty[String])
    announcedMap.update((tableID, revision), announcedCubes.union(cubes.toSet))
  }

  override def beginOptimization(
      tableID: QTableID,
      revision: Long,
      cubeLimit: Integer): Optimization = new LocalOptimization(
    generator.getAndIncrement().toString,
    announcedMap.getOrElse((tableID, revision), Set.empty[String]))

  override def stop(): Unit = {}
}

private class LocalWrite(val id: String, override val announcedCubes: Set[String]) extends Write {

  override def end(): Unit = {}
}

private class LocalOptimization(val id: String, override val cubesToOptimize: Set[String])
    extends Optimization {

  override def end(replicatedCubes: Set[String]): Unit = {}
}
