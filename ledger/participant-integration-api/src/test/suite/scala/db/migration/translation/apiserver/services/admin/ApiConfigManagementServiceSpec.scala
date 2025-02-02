// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.apiserver.services.admin

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.api.util.TimeProvider
import com.daml.error.ErrorCodesVersionSwitcher
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.ledger.api.domain.{ConfigurationEntry, LedgerOffset}
import com.daml.ledger.api.testing.utils.AkkaBeforeAndAfterAll
import com.daml.ledger.api.v1.admin.config_management_service.{
  GetTimeModelRequest,
  SetTimeModelRequest,
  TimeModel,
}
import com.daml.ledger.configuration.{Configuration, LedgerTimeModel}
import com.daml.ledger.participant.state.index.v2.IndexConfigManagementService
import com.daml.ledger.participant.state.v2.{SubmissionResult, WriteConfigService, WriteService}
import com.daml.ledger.participant.state.{v2 => state}
import com.daml.lf.data.Ref.SubmissionId
import com.daml.lf.data.{Ref, Time}
import com.daml.logging.LoggingContext
import com.daml.platform.apiserver.services.admin.ApiConfigManagementServiceSpec._
import com.daml.telemetry.TelemetrySpecBase._
import com.daml.telemetry.{TelemetryContext, TelemetrySpecBase}
import com.google.protobuf.duration.{Duration => DurationProto}
import com.google.protobuf.timestamp.Timestamp
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Duration
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.collection.immutable
import scala.concurrent.duration.{Duration => ScalaDuration}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}

class ApiConfigManagementServiceSpec
    extends AsyncWordSpec
    with Matchers
    with Inside
    with MockitoSugar
    with ArgumentMatchersSugar
    with TelemetrySpecBase
    with AkkaBeforeAndAfterAll {

  private implicit val loggingContext: LoggingContext = LoggingContext.ForTesting

  private val useSelfServiceErrorCodes = mock[ErrorCodesVersionSwitcher]

  "ApiConfigManagementService" should {
    "get the time model" in {
      val indexedTimeModel = LedgerTimeModel(
        avgTransactionLatency = Duration.ofMinutes(5),
        minSkew = Duration.ofMinutes(3),
        maxSkew = Duration.ofMinutes(2),
      ).get
      val expectedTimeModel = TimeModel.of(
        avgTransactionLatency = Some(DurationProto.of(5 * 60, 0)),
        minSkew = Some(DurationProto.of(3 * 60, 0)),
        maxSkew = Some(DurationProto.of(2 * 60, 0)),
      )

      val writeService = mock[state.WriteConfigService]
      val apiConfigManagementService = ApiConfigManagementService.createApiService(
        new FakeCurrentIndexConfigManagementService(
          LedgerOffset.Absolute(Ref.LedgerString.assertFromString("0")),
          Configuration(aConfigurationGeneration, indexedTimeModel, Duration.ZERO),
        ),
        writeService,
        TimeProvider.UTC,
        useSelfServiceErrorCodes,
      )

      apiConfigManagementService
        .getTimeModel(GetTimeModelRequest.defaultInstance)
        .map { response =>
          response.timeModel should be(Some(expectedTimeModel))
          verifyZeroInteractions(writeService)
          verifyZeroInteractions(useSelfServiceErrorCodes)
          succeed
        }
    }

    "return a `NOT_FOUND` error if a time model is not found (V1 error codes)" in {
      testReturnANotFoundErrorIfTimeModelNotFound(false)
    }

    "return a `NOT_FOUND` error if a time model is not found (V2 self-service error codes)" in {
      testReturnANotFoundErrorIfTimeModelNotFound(true)
    }

    "set a new time model" in {
      val maximumDeduplicationTime = Duration.ofHours(6)
      val initialGeneration = 2L
      val initialTimeModel = LedgerTimeModel(
        avgTransactionLatency = Duration.ofMinutes(1),
        minSkew = Duration.ofMinutes(2),
        maxSkew = Duration.ofMinutes(3),
      ).get
      val initialConfiguration = Configuration(
        generation = initialGeneration,
        timeModel = initialTimeModel,
        maxDeduplicationTime = maximumDeduplicationTime,
      )
      val expectedGeneration = 3L
      val expectedTimeModel = LedgerTimeModel(
        avgTransactionLatency = Duration.ofMinutes(2),
        minSkew = Duration.ofMinutes(1),
        maxSkew = Duration.ofSeconds(30),
      ).get
      val expectedConfiguration = Configuration(
        generation = expectedGeneration,
        timeModel = expectedTimeModel,
        maxDeduplicationTime = maximumDeduplicationTime,
      )

      val timeProvider = TimeProvider.UTC
      val maximumRecordTime = timeProvider.getCurrentTime.plusSeconds(60)

      val (indexService, writeService, currentConfiguration) = fakeServices(
        startingOffset = 7,
        submissions = Seq(Ref.SubmissionId.assertFromString("one") -> initialConfiguration),
      )
      val apiConfigManagementService = ApiConfigManagementService.createApiService(
        indexService,
        writeService,
        timeProvider,
        useSelfServiceErrorCodes,
      )

      apiConfigManagementService
        .setTimeModel(
          SetTimeModelRequest.of(
            "some submission ID",
            maximumRecordTime = Some(Timestamp.of(maximumRecordTime.getEpochSecond, 0)),
            configurationGeneration = initialGeneration,
            newTimeModel = Some(
              TimeModel(
                avgTransactionLatency = Some(DurationProto.of(2 * 60, 0)),
                minSkew = Some(DurationProto.of(60, 0)),
                maxSkew = Some(DurationProto.of(30, 0)),
              )
            ),
          )
        )
        .map { response =>
          response.configurationGeneration should be(expectedGeneration)
          currentConfiguration() should be(Some(expectedConfiguration))
          verifyZeroInteractions(useSelfServiceErrorCodes)
          succeed
        }
    }

    "refuse to set a new time model if none is indexed (V1 error codes)" in {
      testRefuseToSetANewTimeModelIfNoneIsIndexedYet(false)
    }

    "refuse to set a new time model if none is indexed (V2 self-service error codes)" in {
      testRefuseToSetANewTimeModelIfNoneIsIndexedYet(true)
    }

    "propagate trace context" in {
      val apiConfigManagementService = ApiConfigManagementService.createApiService(
        new FakeStreamingIndexConfigManagementService(someConfigurationEntries),
        TestWriteConfigService,
        TimeProvider.UTC,
        useSelfServiceErrorCodes,
        _ => Ref.SubmissionId.assertFromString("aSubmission"),
      )

      val span = anEmptySpan()
      val scope = span.makeCurrent()
      apiConfigManagementService
        .setTimeModel(aSetTimeModelRequest)
        .andThen { case _ =>
          scope.close()
          span.end()
        }
        .map { _ =>
          spanExporter.finishedSpanAttributes should contain(anApplicationIdSpanAttribute)
          verifyZeroInteractions(useSelfServiceErrorCodes)
          succeed
        }
    }
  }

  private def testReturnANotFoundErrorIfTimeModelNotFound(useSelfServiceErrorCodes: Boolean) = {
    val errorCodesVersionSwitcher = new ErrorCodesVersionSwitcher(useSelfServiceErrorCodes)
    val writeService = mock[WriteConfigService]
    val apiConfigManagementService = ApiConfigManagementService.createApiService(
      EmptyIndexConfigManagementService,
      writeService,
      TimeProvider.UTC,
      errorCodesVersionSwitcher,
    )

    apiConfigManagementService
      .getTimeModel(GetTimeModelRequest.defaultInstance)
      .transform(Success.apply)
      .map { response =>
        response should matchPattern { case Failure(GrpcException(GrpcStatus.NOT_FOUND(), _)) =>
        }
      }
  }

  private def testRefuseToSetANewTimeModelIfNoneIsIndexedYet(useSelfServiceErrorCodes: Boolean) = {
    val errorCodesVersionSwitcher = new ErrorCodesVersionSwitcher(useSelfServiceErrorCodes)
    val initialGeneration = 0L

    val timeProvider = TimeProvider.UTC
    val maximumRecordTime = timeProvider.getCurrentTime.plusSeconds(60)

    val writeService = mock[WriteService]
    val apiConfigManagementService = ApiConfigManagementService.createApiService(
      EmptyIndexConfigManagementService,
      writeService,
      timeProvider,
      errorCodesVersionSwitcher,
    )

    apiConfigManagementService
      .setTimeModel(
        SetTimeModelRequest.of(
          "a submission ID",
          maximumRecordTime = Some(Timestamp.of(maximumRecordTime.getEpochSecond, 0)),
          configurationGeneration = initialGeneration,
          newTimeModel = Some(
            TimeModel(
              avgTransactionLatency = Some(DurationProto.of(10, 0)),
              minSkew = Some(DurationProto.of(20, 0)),
              maxSkew = Some(DurationProto.of(40, 0)),
            )
          ),
        )
      )
      .transform(Success.apply)
      .map { response =>
        verifyZeroInteractions(writeService)
        if (useSelfServiceErrorCodes) {
          response should matchPattern { case Failure(GrpcException(GrpcStatus.NOT_FOUND(), _)) =>
          }
        } else {
          response should matchPattern { case Failure(GrpcException(GrpcStatus.UNAVAILABLE(), _)) =>
          }
        }
      }
  }
}

object ApiConfigManagementServiceSpec {
  private val aSubmissionId = "aSubmission"

  private val aConfigurationGeneration = 0L

  private val someConfigurationEntries = List(
    LedgerOffset.Absolute(Ref.LedgerString.assertFromString("0")) ->
      ConfigurationEntry.Accepted(
        aSubmissionId,
        Configuration(
          aConfigurationGeneration,
          LedgerTimeModel.reasonableDefault,
          Duration.ZERO,
        ),
      )
  )

  private val aSetTimeModelRequest = SetTimeModelRequest(
    aSubmissionId,
    Some(Timestamp.defaultInstance),
    aConfigurationGeneration,
    Some(
      TimeModel(
        Some(DurationProto.defaultInstance),
        Some(DurationProto.defaultInstance),
        Some(DurationProto.defaultInstance),
      )
    ),
  )

  private object EmptyIndexConfigManagementService extends IndexConfigManagementService {
    override def lookupConfiguration()(implicit
        loggingContext: LoggingContext
    ): Future[Option[(LedgerOffset.Absolute, Configuration)]] =
      Future.successful(None)

    override def configurationEntries(startExclusive: Option[LedgerOffset.Absolute])(implicit
        loggingContext: LoggingContext
    ): Source[(LedgerOffset.Absolute, ConfigurationEntry), NotUsed] =
      Source.never
  }

  private final class FakeCurrentIndexConfigManagementService(
      offset: LedgerOffset.Absolute,
      configuration: Configuration,
  ) extends IndexConfigManagementService {
    override def lookupConfiguration()(implicit
        loggingContext: LoggingContext
    ): Future[Option[(LedgerOffset.Absolute, Configuration)]] =
      Future.successful(Some(offset -> configuration))

    override def configurationEntries(startExclusive: Option[LedgerOffset.Absolute])(implicit
        loggingContext: LoggingContext
    ): Source[(LedgerOffset.Absolute, ConfigurationEntry), NotUsed] =
      Source.never
  }

  private final class FakeStreamingIndexConfigManagementService(
      entries: immutable.Iterable[(LedgerOffset.Absolute, ConfigurationEntry)]
  ) extends IndexConfigManagementService {
    private val currentConfiguration =
      entries.collect { case (offset, ConfigurationEntry.Accepted(_, configuration)) =>
        offset -> configuration
      }.lastOption

    override def lookupConfiguration()(implicit
        loggingContext: LoggingContext
    ): Future[Option[(LedgerOffset.Absolute, Configuration)]] =
      Future.successful(currentConfiguration)

    override def configurationEntries(startExclusive: Option[LedgerOffset.Absolute])(implicit
        loggingContext: LoggingContext
    ): Source[(LedgerOffset.Absolute, ConfigurationEntry), NotUsed] =
      Source(entries)
  }

  private object TestWriteConfigService extends state.WriteConfigService {
    override def submitConfiguration(
        maxRecordTime: Time.Timestamp,
        submissionId: Ref.SubmissionId,
        config: Configuration,
    )(implicit
        loggingContext: LoggingContext,
        telemetryContext: TelemetryContext,
    ): CompletionStage[state.SubmissionResult] = {
      telemetryContext.setAttribute(
        anApplicationIdSpanAttribute._1,
        anApplicationIdSpanAttribute._2,
      )
      completedFuture(state.SubmissionResult.Acknowledged)
    }
  }

  private def fakeServices(
      startingOffset: Long,
      submissions: Iterable[(Ref.SubmissionId, Configuration)],
  )(implicit
      materializer: Materializer
  ): (IndexConfigManagementService, WriteConfigService, () => Option[Configuration]) = {
    val currentOffset = new AtomicLong(startingOffset)
    val (configurationQueue, configurationSource) =
      Source.queue[(Long, SubmissionId, Configuration)](1).preMaterialize()
    submissions.foreach { case (submissionId, configuration) =>
      configurationQueue.offer((currentOffset.getAndIncrement(), submissionId, configuration))
    }
    val currentConfiguration =
      new AtomicReference[Option[(LedgerOffset.Absolute, Configuration)]](None)

    val indexService: IndexConfigManagementService = new IndexConfigManagementService {
      private val atLeastOneConfig = Promise[Unit]()
      private val source = configurationSource
        .map { case (offset, submissionId, configuration) =>
          val ledgerOffset =
            LedgerOffset.Absolute(Ref.LedgerString.assertFromString(offset.toString))
          currentConfiguration.set(Some(ledgerOffset -> configuration))
          atLeastOneConfig.trySuccess(())
          val entry = ConfigurationEntry.Accepted(submissionId, configuration)
          ledgerOffset -> entry
        }
        .preMaterialize()
      Await.result(atLeastOneConfig.future, ScalaDuration.Inf)

      override def lookupConfiguration()(implicit
          loggingContext: LoggingContext
      ): Future[Option[(LedgerOffset.Absolute, Configuration)]] =
        Future.successful(currentConfiguration.get())

      override def configurationEntries(startExclusive: Option[LedgerOffset.Absolute])(implicit
          loggingContext: LoggingContext
      ): Source[(LedgerOffset.Absolute, ConfigurationEntry), NotUsed] =
        source._2
    }
    val writeService = new WriteConfigService {
      override def submitConfiguration(
          maxRecordTime: Time.Timestamp,
          submissionId: SubmissionId,
          configuration: Configuration,
      )(implicit
          loggingContext: LoggingContext,
          telemetryContext: TelemetryContext,
      ): CompletionStage[SubmissionResult] = {
        configurationQueue.offer((currentOffset.getAndIncrement(), submissionId, configuration))
        completedFuture(state.SubmissionResult.Acknowledged)
      }
    }
    (indexService, writeService, () => currentConfiguration.get.map(_._2))
  }
}
