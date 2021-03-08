// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.index

import java.sql.SQLException
import java.time.Instant

import akka.actor.Cancellable
import akka.stream._
import akka.stream.scaladsl.{Keep, RestartSource, Sink, Source}
import akka.{Done, NotUsed}
import com.daml.ledger.api.domain.LedgerId
import com.daml.ledger.api.health.HealthStatus
import com.daml.ledger.participant.state.v1.Offset
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.lf.engine.ValueEnricher
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.platform.common.{LedgerIdNotFoundException, MismatchException}
import com.daml.platform.configuration.ServerRole
import com.daml.platform.store.dao.events.LfValueTranslation
import com.daml.platform.store.dao.{JdbcLedgerDao, LedgerReadDao}
import com.daml.platform.store.state.CachingContractsReader
import com.daml.platform.store.{BaseLedger, ReadOnlyLedger}
import com.daml.resources.ProgramResource.StartupException
import com.daml.timer.RetryStrategy

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

private[platform] object ReadOnlySqlLedger {

  private val logger = ContextualizedLogger.get(this.getClass)

  //jdbcUrl must have the user/password encoded in form of: "jdbc:postgresql://localhost/test?user=fred&password=secret"
  final class Owner(
      serverRole: ServerRole,
      jdbcUrl: String,
      initialLedgerId: LedgerId,
      databaseConnectionPoolSize: Int,
      eventsPageSize: Int,
      servicesExecutionContext: ExecutionContext,
      metrics: Metrics,
      lfValueTranslationCache: LfValueTranslation.Cache,
      enricher: ValueEnricher,
  )(implicit mat: Materializer, loggingContext: LoggingContext)
      extends ResourceOwner[ReadOnlyLedger] {
    override def acquire()(implicit context: ResourceContext): Resource[ReadOnlyLedger] =
      for {
        ledgerDao <- ledgerDaoOwner(servicesExecutionContext).acquire()
        ledgerId <- Resource.fromFuture(verifyLedgerId(ledgerDao, initialLedgerId))
        (ledgerEndOffset, ledgerEndSeqId) <- Resource.fromFuture(ledgerDao.lookupLedgerEndAndEventSequentialId())
        dispatcher <- dispatcherOwner(ledgerEndOffset).acquire()
        contractStateEventsDispatcher <- dispatcherOffsetSeqIdOwner(ledgerEndOffset, ledgerEndSeqId).acquire()
        ledger <- ResourceOwner
          .forCloseable(() =>
            new ReadOnlySqlLedger(
              ledgerId,
              ledgerDao,
              dispatcher,
              contractStateEventsDispatcher,
              metrics,
              servicesExecutionContext,
            )
          )
          .acquire()
      } yield ledger

    private def verifyLedgerId(
        ledgerDao: LedgerReadDao,
        initialLedgerId: LedgerId,
    )(implicit
        executionContext: ExecutionContext,
        loggingContext: LoggingContext,
    ): Future[LedgerId] = {
      val predicate: PartialFunction[Throwable, Boolean] = {
        // If the index database is not yet fully initialized,
        // querying for the ledger ID will throw different errors,
        // depending on the database, and how far the initialization is.
        case _: SQLException => true
        case _: LedgerIdNotFoundException => true
        case _: MismatchException.LedgerId => false
        case _ => false
      }
      val retryDelay = 5.seconds
      val maxAttempts = 100
      RetryStrategy.constant(attempts = Some(maxAttempts), waitTime = retryDelay)(predicate) {
        (attempt, _) =>
          ledgerDao
            .lookupLedgerId()
            .flatMap {
              case Some(`initialLedgerId`) =>
                logger.info(s"Found existing ledger with ID: $initialLedgerId")
                Future.successful(initialLedgerId)
              case Some(foundLedgerId) =>
                Future.failed(
                  new MismatchException.LedgerId(foundLedgerId, initialLedgerId)
                    with StartupException
                )
              case None =>
                logger.info(
                  s"Ledger ID not found in the index database on attempt $attempt/$maxAttempts. Retrying again in $retryDelay."
                )
                Future.failed(new LedgerIdNotFoundException(attempt))
            }
      }

    }

    private def ledgerDaoOwner(
        servicesExecutionContext: ExecutionContext
    ): ResourceOwner[LedgerReadDao] =
      JdbcLedgerDao.readOwner(
        serverRole,
        jdbcUrl,
        databaseConnectionPoolSize,
        eventsPageSize,
        servicesExecutionContext,
        metrics,
        lfValueTranslationCache,
        Some(enricher),
      )

    private def dispatcherOwner(ledgerEnd: Offset): ResourceOwner[Dispatcher[Offset]] =
      Dispatcher.owner(
        name = "sql-ledger",
        zeroIndex = Offset.beforeBegin,
        headAtInitialization = ledgerEnd,
      )

    private def dispatcherOffsetSeqIdOwner(ledgerEnd: Offset, evtSeqId: Long) = {
      implicit val ordering: Ordering[(Offset, Long)] = Ordering.fromLessThan{
        case ((fOffset, fSeqId), (sOffset, sSeqId)) =>
          (fOffset < sOffset) || (fOffset == sOffset && fSeqId < sSeqId)
      }
      Dispatcher.owner(
        name = "contract-state-events",
        zeroIndex = (Offset.beforeBegin, -1L),
        headAtInitialization = (ledgerEnd, evtSeqId)
      )
    }
  }

}

private final class ReadOnlySqlLedger(
    ledgerId: LedgerId,
    ledgerDao: LedgerReadDao,
    dispatcher: Dispatcher[Offset],
    contractStateEventsDispatcher: Dispatcher[(Offset, Long)],
    metrics: Metrics,
    executionContext: ExecutionContext,
)(implicit mat: Materializer, loggingContext: LoggingContext)
    extends BaseLedger(ledgerId, ledgerDao, dispatcher, contractStateEventsDispatcher) {

  private val logger = ContextualizedLogger.get(getClass)
  override protected val cachingContractsReader: CachingContractsReader = {
    val cachingLayer =
      CachingContractsReader(
        store = ledgerDao.contractsReader,
        metrics = metrics,
        globallySignalNewLedgerEnd = dispatcher.signalNewHead, // TDT
      )(executionContext)
    contractLifecycleEvents
      .map(_._2)
      .via(cachingLayer.consumeFrom)
      .run()
      .onComplete {
        case Failure(exception) =>
          logger.error("Event state consumption stream failed", exception)
        case Success(_) =>
          logger.info("Finished consuming state events")
      }(executionContext)
    cachingLayer
  }

  private val (ledgerEndUpdateKillSwitch, ledgerEndUpdateDone) =
    RestartSource
      .withBackoff(
        RestartSettings(minBackoff = 1.second, maxBackoff = 10.seconds, randomFactor = 0.2)
      )(() =>
        Source
          .tick(0.millis, 100.millis, ())
          .mapAsync(1)(_ => ledgerDao.lookupLedgerEndAndEventSequentialId())
      )
      .viaMat(KillSwitches.single)(Keep.right[NotUsed, UniqueKillSwitch])
      .toMat(Sink.foreach { ledgerEnd =>
        contractStateEventsDispatcher.signalNewHead(ledgerEnd)
        cachingContractsReader.signalNewHead(loggingContext)(ledgerEnd)
      })(
        Keep.both[UniqueKillSwitch, Future[Done]]
      )
      .run()

  // Periodically remove all expired deduplication cache entries.
  // The current approach is not ideal for multiple ReadOnlySqlLedgers sharing
  // the same database (as is the case for a horizontally scaled ledger API server).
  // In that case, an external process periodically clearing expired entries would be better.
  //
  // Deduplication entries are added by the submission service, which might use
  // a different clock than the current clock (e.g., horizontally scaled ledger API server).
  // This is not an issue, because applications are not expected to submit towards the end
  // of the deduplication time window.
  private val (deduplicationCleanupKillSwitch, deduplicationCleanupDone) =
    Source
      .tick[Unit](0.millis, 10.minutes, ())
      .mapAsync[Unit](1)(_ => ledgerDao.removeExpiredDeduplicationData(Instant.now()))
      .viaMat(KillSwitches.single)(Keep.right[Cancellable, UniqueKillSwitch])
      .toMat(Sink.ignore)(Keep.both[UniqueKillSwitch, Future[Done]])
      .run()

  override def currentHealth(): HealthStatus = ledgerDao.currentHealth()

  override def close(): Unit = {
    // Terminate the dispatcher first so that it doesn't trigger new queries.
    dispatcher.close()

    deduplicationCleanupKillSwitch.shutdown()
    ledgerEndUpdateKillSwitch.shutdown()
    Await.result(deduplicationCleanupDone, 10.seconds)
    Await.result(ledgerEndUpdateDone, 10.seconds)
    super.close()
  }
}
