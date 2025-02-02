// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package transaction

/** Container for transaction statistics.
  *
  * @param creates number of creates nodes,
  * @param consumingExercisesByCid number of consuming exercises by contract ID nodes,
  * @param nonconsumingExercisesByCid number of non-consuming Exercises by contract ID nodes,
  * @param consumingExercisesByKey number of consuming exercise by contract key nodes,
  * @param nonconsumingExercisesByKey number of non-consuming exercise by key nodes,
  * @param fetchesByCid number of fetch by contract ID nodes,
  * @param fetchesByKey number of fetch by key nodes,
  * @param lookupsByKey number of lookup by key nodes,
  * @param rollbacks number of rollback nodes.
  */
final case class TransactionNodesStatistics(
    creates: Int,
    consumingExercisesByCid: Int,
    nonconsumingExercisesByCid: Int,
    consumingExercisesByKey: Int,
    nonconsumingExercisesByKey: Int,
    fetchesByCid: Int,
    fetchesByKey: Int,
    lookupsByKey: Int,
    rollbacks: Int,
) {

  def +(that: TransactionNodesStatistics) =
    TransactionNodesStatistics(
      creates = this.creates + that.creates,
      consumingExercisesByCid = this.consumingExercisesByCid + that.consumingExercisesByCid,
      nonconsumingExercisesByCid =
        this.nonconsumingExercisesByCid + that.nonconsumingExercisesByCid,
      consumingExercisesByKey = this.consumingExercisesByKey + that.consumingExercisesByKey,
      nonconsumingExercisesByKey =
        this.nonconsumingExercisesByKey + that.nonconsumingExercisesByKey,
      fetchesByCid = this.fetchesByCid + that.fetchesByCid,
      fetchesByKey = this.fetchesByKey + that.fetchesByKey,
      lookupsByKey = this.lookupsByKey + that.lookupsByKey,
      rollbacks = this.rollbacks + that.rollbacks,
    )

  def exercisesByCid: Int = consumingExercisesByCid + nonconsumingExercisesByCid
  def exercisesByKey: Int = consumingExercisesByKey + nonconsumingExercisesByKey
  def exercises: Int = exercisesByCid + exercisesByKey
  def consumingExercises: Int = consumingExercisesByCid + consumingExercisesByKey
  def nonconsumingExercises: Int = nonconsumingExercisesByCid + nonconsumingExercisesByKey
  def fetches: Int = fetchesByCid + fetchesByKey
  def byKeys: Int = exercisesByKey + fetchesByKey + lookupsByKey
  def actions: Int = creates + exercises + fetches + lookupsByKey
  def nodes: Int = actions + rollbacks
}

object TransactionNodesStatistics {

  val Empty = TransactionNodesStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0)

  private[this] val numberOfFields = Empty.productArity

  private[this] val Seq(
    createsIdx,
    consumingExercisesByCidIdx,
    nonconsumingExerciseCidsIdx,
    consumingExercisesByKeyIdx,
    nonconsumingExercisesByKeyIdx,
    fetchesIdx,
    fetchesByKeyIdx,
    lookupsByKeyIdx,
    rollbacksIdx,
  ) =
    (0 until numberOfFields)

  private[this] def emptyFields = Array.fill(numberOfFields)(0)

  private[this] def build(stats: Array[Int]) =
    TransactionNodesStatistics(
      creates = stats(createsIdx),
      consumingExercisesByCid = stats(consumingExercisesByCidIdx),
      nonconsumingExercisesByCid = stats(nonconsumingExerciseCidsIdx),
      consumingExercisesByKey = stats(consumingExercisesByKeyIdx),
      nonconsumingExercisesByKey = stats(nonconsumingExercisesByKeyIdx),
      fetchesByCid = stats(fetchesIdx),
      fetchesByKey = stats(fetchesByKeyIdx),
      lookupsByKey = stats(lookupsByKeyIdx),
      rollbacks = stats(rollbacksIdx),
    )

  /** This function produces statistics about the committed nodes (those nodes
    *  that do not appear under a rollback node) on the one hand and
    *  rolled back nodes (those nodes that do appear under a rollback node) on
    *  the other hand within a given transaction `tx`.
    */
  def stats(tx: VersionedTransaction): (TransactionNodesStatistics, TransactionNodesStatistics) =
    stats(tx.transaction)

  def stats(tx: Transaction): (TransactionNodesStatistics, TransactionNodesStatistics) = {
    val committed = emptyFields
    val rolledBack = emptyFields
    var rollbackDepth = 0

    def incr(fieldIdx: Int) =
      if (rollbackDepth > 0) rolledBack(fieldIdx) += 1 else committed(fieldIdx) += 1

    tx.foreachInExecutionOrder(
      exerciseBegin = { (_, exe) =>
        val idx =
          if (exe.consuming)
            if (exe.byKey)
              consumingExercisesByKeyIdx
            else
              consumingExercisesByCidIdx
          else if (exe.byKey)
            nonconsumingExercisesByKeyIdx
          else
            nonconsumingExerciseCidsIdx
        incr(idx)
        Transaction.ChildrenRecursion.DoRecurse
      },
      rollbackBegin = { (_, _) =>
        incr(rollbacksIdx)
        rollbackDepth += 1
        Transaction.ChildrenRecursion.DoRecurse
      },
      leaf = { (_, node) =>
        val idx = node match {
          case _: Node.Create =>
            createsIdx
          case fetch: Node.Fetch =>
            if (fetch.byKey)
              fetchesByKeyIdx
            else
              fetchesIdx
          case _: Node.LookupByKey =>
            lookupsByKeyIdx
        }
        incr(idx)
      },
      exerciseEnd = (_, _) => (),
      rollbackEnd = (_, _) => rollbackDepth -= 1,
    )

    (build(committed), build(rolledBack))
  }

}
