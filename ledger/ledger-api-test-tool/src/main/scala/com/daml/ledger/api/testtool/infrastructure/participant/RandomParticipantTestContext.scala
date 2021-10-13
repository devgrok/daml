package com.daml.ledger.api.testtool.infrastructure.participant

import java.time.Instant

import com.daml.ledger.api.refinements.ApiTypes.TemplateId
import com.daml.ledger.api.testtool.infrastructure.Endpoint
import com.daml.ledger.api.tls.TlsConfiguration
import com.daml.ledger.api.v1.active_contracts_service.GetActiveContractsRequest
import com.daml.ledger.api.v1.admin.config_management_service.{
  GetTimeModelResponse,
  SetTimeModelRequest,
  SetTimeModelResponse,
  TimeModel,
}
import com.daml.ledger.api.v1.admin.package_management_service.{
  PackageDetails,
  UploadDarFileRequest,
}
import com.daml.ledger.api.v1.admin.participant_pruning_service.PruneResponse
import com.daml.ledger.api.v1.admin.party_management_service.PartyDetails
import com.daml.ledger.api.v1.command_completion_service.{
  Checkpoint,
  CompletionEndRequest,
  CompletionEndResponse,
  CompletionStreamRequest,
  CompletionStreamResponse,
}
import com.daml.ledger.api.v1.command_service.SubmitAndWaitRequest
import com.daml.ledger.api.v1.command_submission_service.SubmitRequest
import com.daml.ledger.api.v1.commands.Command
import com.daml.ledger.api.v1.completion.Completion
import com.daml.ledger.api.v1.event.CreatedEvent
import com.daml.ledger.api.v1.ledger_configuration_service.LedgerConfiguration
import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.ledger.api.v1.package_service.{GetPackageResponse, PackageStatus}
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree}
import com.daml.ledger.api.v1.transaction_service.{
  GetTransactionByEventIdRequest,
  GetTransactionByIdRequest,
  GetTransactionsRequest,
  GetTransactionsResponse,
}
import com.daml.ledger.api.v1.value.{Identifier, Value}
import com.daml.ledger.client.binding.{Primitive, Template}
import com.google.protobuf.ByteString
import io.grpc.health.v1.health.HealthCheckResponse
import io.grpc.stub.StreamObserver

import scala.concurrent.Future
import scala.util.Random

class RandomParticipantTestContext(configuredLedgers: Seq[ParticipantTestContext])
    extends ParticipantTestContext {

  private def randomLedger = configuredLedgers(Random.between(0, configuredLedgers.size))

  override val ledgerId: String = randomLedger.ledgerId
  override val endpointId: String = randomLedger.endpointId
  override val applicationId: String = randomLedger.applicationId
  override val identifierSuffix: String = randomLedger.identifierSuffix
  override val ledgerEndpoint: Endpoint = randomLedger.ledgerEndpoint
  override val clientTlsConfiguration: Option[TlsConfiguration] = None
  override val referenceOffset: LedgerOffset = randomLedger.referenceOffset

  override val nextKeyId: () => String = randomLedger.nextKeyId

  override def currentEnd(): Future[LedgerOffset] = randomLedger.currentEnd()

  override def currentEnd(overrideLedgerId: String): Future[LedgerOffset] =
    randomLedger.currentEnd(overrideLedgerId)

  override def offsetBeyondLedgerEnd(): Future[LedgerOffset] = randomLedger.offsetBeyondLedgerEnd()

  override def time(): Future[Instant] = randomLedger.time()

  override def listKnownPackages(): Future[Seq[PackageDetails]] = randomLedger.listKnownPackages()

  override def uploadDarFile(bytes: ByteString): Future[Unit] = randomLedger.uploadDarFile(bytes)

  override def uploadDarRequest(bytes: ByteString): UploadDarFileRequest =
    randomLedger.uploadDarRequest(bytes)

  override def uploadDarFile(request: UploadDarFileRequest): Future[Unit] =
    randomLedger.uploadDarFile(request)

  override def participantId(): Future[String] = randomLedger.participantId()

  override def listPackages(): Future[Seq[String]] = randomLedger.listPackages()

  override def getPackage(packageId: String): Future[GetPackageResponse] =
    randomLedger.getPackage(packageId)

  override def getPackageStatus(packageId: String): Future[PackageStatus] =
    randomLedger.getPackageStatus(packageId)

  override def allocateParty(): Future[Primitive.Party] = randomLedger.allocateParty()

  override def allocateParty(
      partyIdHint: Option[String],
      displayName: Option[String],
  ): Future[Primitive.Party] = randomLedger.allocateParty(partyIdHint, displayName)

  override def allocateParties(n: Int): Future[Vector[Primitive.Party]] =
    randomLedger.allocateParties(n)

  override def getParties(parties: Seq[Primitive.Party]): Future[Seq[PartyDetails]] =
    randomLedger.getParties(parties)

  override def listKnownParties(): Future[Set[Primitive.Party]] = randomLedger.listKnownParties()

  override def waitForParties(
      otherParticipants: Iterable[ParticipantTestContext],
      expectedParties: Set[Primitive.Party],
  ): Future[Unit] = randomLedger.waitForParties(otherParticipants, expectedParties)

  override def activeContracts(
      request: GetActiveContractsRequest
  ): Future[(Option[LedgerOffset], Vector[CreatedEvent])] = randomLedger.activeContracts(request)

  override def activeContractsRequest(
      parties: Seq[Primitive.Party],
      templateIds: Seq[Identifier],
  ): GetActiveContractsRequest = randomLedger.activeContractsRequest(parties, templateIds)

  override def activeContracts(parties: Primitive.Party*): Future[Vector[CreatedEvent]] =
    randomLedger.activeContracts(parties: _*)

  override def activeContractsByTemplateId(
      templateIds: Seq[Identifier],
      parties: Primitive.Party*
  ): Future[Vector[CreatedEvent]] =
    randomLedger.activeContractsByTemplateId(templateIds, parties: _*)

  override def getTransactionsRequest(
      parties: Seq[Primitive.Party],
      templateIds: Seq[TemplateId],
      begin: LedgerOffset,
  ): GetTransactionsRequest = randomLedger.getTransactionsRequest(parties, templateIds, begin)

  override def transactionStream(
      request: GetTransactionsRequest,
      responseObserver: StreamObserver[GetTransactionsResponse],
  ): Unit = randomLedger.transactionStream(request, responseObserver)

  override def flatTransactionsByTemplateId(
      templateId: TemplateId,
      parties: Primitive.Party*
  ): Future[Vector[Transaction]] =
    randomLedger.flatTransactionsByTemplateId(templateId, parties: _*)

  override def flatTransactions(request: GetTransactionsRequest): Future[Vector[Transaction]] =
    randomLedger.flatTransactions(request)

  override def flatTransactions(parties: Primitive.Party*): Future[Vector[Transaction]] =
    randomLedger.flatTransactions(parties: _*)

  override def flatTransactions(
      take: Int,
      request: GetTransactionsRequest,
  ): Future[Vector[Transaction]] = randomLedger.flatTransactions(take, request)

  override def flatTransactions(take: Int, parties: Primitive.Party*): Future[Vector[Transaction]] =
    randomLedger.flatTransactions(take, parties: _*)

  override def transactionTreesByTemplateId(
      templateId: TemplateId,
      parties: Primitive.Party*
  ): Future[Vector[TransactionTree]] =
    randomLedger.transactionTreesByTemplateId(templateId, parties: _*)

  override def transactionTrees(request: GetTransactionsRequest): Future[Vector[TransactionTree]] =
    randomLedger.transactionTrees(request)

  override def transactionTrees(parties: Primitive.Party*): Future[Vector[TransactionTree]] =
    randomLedger.transactionTrees(parties: _*)

  override def transactionTrees(
      take: Int,
      request: GetTransactionsRequest,
  ): Future[Vector[TransactionTree]] = randomLedger.transactionTrees(take, request)

  override def transactionTrees(
      take: Int,
      parties: Primitive.Party*
  ): Future[Vector[TransactionTree]] = randomLedger.transactionTrees(take, parties: _*)

  override def getTransactionByIdRequest(
      transactionId: String,
      parties: Seq[Primitive.Party],
  ): GetTransactionByIdRequest = randomLedger.getTransactionByIdRequest(transactionId, parties)

  override def transactionTreeById(request: GetTransactionByIdRequest): Future[TransactionTree] =
    randomLedger.transactionTreeById(request)

  override def transactionTreeById(
      transactionId: String,
      parties: Primitive.Party*
  ): Future[TransactionTree] = randomLedger.transactionTreeById(transactionId, parties: _*)

  override def flatTransactionById(request: GetTransactionByIdRequest): Future[Transaction] =
    randomLedger.flatTransactionById(request)

  override def flatTransactionById(
      transactionId: String,
      parties: Primitive.Party*
  ): Future[Transaction] = randomLedger.flatTransactionById(transactionId, parties: _*)

  override def getTransactionByEventIdRequest(
      eventId: String,
      parties: Seq[Primitive.Party],
  ): GetTransactionByEventIdRequest = randomLedger.getTransactionByEventIdRequest(eventId, parties)

  override def transactionTreeByEventId(
      request: GetTransactionByEventIdRequest
  ): Future[TransactionTree] = randomLedger.transactionTreeByEventId(request)

  override def transactionTreeByEventId(
      eventId: String,
      parties: Primitive.Party*
  ): Future[TransactionTree] = randomLedger.transactionTreeByEventId(eventId, parties: _*)

  override def flatTransactionByEventId(
      request: GetTransactionByEventIdRequest
  ): Future[Transaction] = randomLedger.flatTransactionByEventId(request)

  override def flatTransactionByEventId(
      eventId: String,
      parties: Primitive.Party*
  ): Future[Transaction] = randomLedger.flatTransactionByEventId(eventId, parties: _*)

  override def create[T](
      party: Primitive.Party,
      template: Template[T],
  ): Future[Primitive.ContractId[T]] = randomLedger.create(party, template)

  override def create[T](
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      template: Template[T],
  ): Future[Primitive.ContractId[T]] = randomLedger.create(actAs, readAs, template)

  override def createAndGetTransactionId[T](
      party: Primitive.Party,
      template: Template[T],
  ): Future[(String, Primitive.ContractId[T])] =
    randomLedger.createAndGetTransactionId(party, template)

  override def exercise[T](
      party: Primitive.Party,
      exercise: Primitive.Party => Primitive.Update[T],
  ): Future[TransactionTree] = randomLedger.exercise(party, exercise)

  override def exercise[T](
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      exercise: => Primitive.Update[T],
  ): Future[TransactionTree] = randomLedger.exercise(actAs, readAs, exercise)

  override def exerciseForFlatTransaction[T](
      party: Primitive.Party,
      exercise: Primitive.Party => Primitive.Update[T],
  ): Future[Transaction] = randomLedger.exerciseForFlatTransaction(party, exercise)

  override def exerciseAndGetContract[T](
      party: Primitive.Party,
      exercise: Primitive.Party => Primitive.Update[Any],
  ): Future[Primitive.ContractId[T]] = randomLedger.exerciseAndGetContract(party, exercise)

  override def exerciseByKey[T](
      party: Primitive.Party,
      template: Primitive.TemplateId[T],
      key: Value,
      choice: String,
      argument: Value,
  ): Future[TransactionTree] = randomLedger.exerciseByKey(party, template, key, choice, argument)

  override def submitRequest(
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      commands: Command*
  ): SubmitRequest = randomLedger.submitRequest(actAs, readAs, commands: _*)

  override def submitRequest(party: Primitive.Party, commands: Command*): SubmitRequest =
    randomLedger.submitRequest(party, commands: _*)

  override def submitAndWaitRequest(
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      commands: Command*
  ): SubmitAndWaitRequest = randomLedger.submitAndWaitRequest(actAs, readAs, commands: _*)

  override def submitAndWaitRequest(
      party: Primitive.Party,
      commands: Command*
  ): SubmitAndWaitRequest = randomLedger.submitAndWaitRequest(party, commands: _*)

  override def submit(request: SubmitRequest): Future[Unit] = {
    val ledger = randomLedger
    println(
      s"Submitting request to ${ledger.endpointId} ${ledger.ledgerEndpoint}. Available ${configuredLedgers.map(_.endpointId).mkString(",")}"
    )
    ledger.submit(request)
  }

  override def submitAndWait(request: SubmitAndWaitRequest): Future[Unit] =
    randomLedger.submitAndWait(request)

  override def submitAndWaitForTransactionId(request: SubmitAndWaitRequest): Future[String] =
    randomLedger.submitAndWaitForTransactionId(request)

  override def submitAndWaitForTransaction(request: SubmitAndWaitRequest): Future[Transaction] =
    randomLedger.submitAndWaitForTransaction(request)

  override def submitAndWaitForTransactionTree(
      request: SubmitAndWaitRequest
  ): Future[TransactionTree] = randomLedger.submitAndWaitForTransactionTree(request)

  override def completionStreamRequest(from: LedgerOffset)(
      parties: Primitive.Party*
  ): CompletionStreamRequest = randomLedger.completionStreamRequest(from)(parties: _*)

  override def completionEnd(request: CompletionEndRequest): Future[CompletionEndResponse] =
    randomLedger.completionEnd(request)

  override def completionStream(
      request: CompletionStreamRequest,
      streamObserver: StreamObserver[CompletionStreamResponse],
  ): Unit = randomLedger.completionStream(request, streamObserver)

  override def firstCompletions(request: CompletionStreamRequest): Future[Vector[Completion]] =
    randomLedger.firstCompletions(request)

  override def firstCompletions(parties: Primitive.Party*): Future[Vector[Completion]] =
    randomLedger.firstCompletions(parties: _*)

  override def findCompletion(request: CompletionStreamRequest)(
      p: Completion => Boolean
  ): Future[Option[Completion]] = randomLedger.findCompletion(request)(p)

  override def findCompletion(parties: Primitive.Party*)(
      p: Completion => Boolean
  ): Future[Option[Completion]] = randomLedger.findCompletion(parties: _*)(p)

  override def checkpoints(n: Int, request: CompletionStreamRequest): Future[Vector[Checkpoint]] =
    randomLedger.checkpoints(n, request)

  override def checkpoints(n: Int, from: LedgerOffset)(
      parties: Primitive.Party*
  ): Future[Vector[Checkpoint]] = randomLedger.checkpoints(n, from)(parties: _*)

  override def firstCheckpoint(request: CompletionStreamRequest): Future[Checkpoint] =
    randomLedger.firstCheckpoint(request)

  override def firstCheckpoint(parties: Primitive.Party*): Future[Checkpoint] =
    randomLedger.firstCheckpoint(parties: _*)

  override def nextCheckpoint(request: CompletionStreamRequest): Future[Checkpoint] =
    randomLedger.nextCheckpoint(request)

  override def nextCheckpoint(from: LedgerOffset, parties: Primitive.Party*): Future[Checkpoint] =
    randomLedger.nextCheckpoint(from, parties: _*)

  override def configuration(overrideLedgerId: Option[String]): Future[LedgerConfiguration] =
    randomLedger.configuration(overrideLedgerId)

  override def checkHealth(): Future[HealthCheckResponse] = randomLedger.checkHealth()

  override def watchHealth(): Future[Seq[HealthCheckResponse]] = randomLedger.watchHealth()

  override def getTimeModel(): Future[GetTimeModelResponse] = randomLedger.getTimeModel()

  override def setTimeModel(
      mrt: Instant,
      generation: Long,
      newTimeModel: TimeModel,
  ): Future[SetTimeModelResponse] = randomLedger.setTimeModel(mrt, generation, newTimeModel)

  override def setTimeModelRequest(
      mrt: Instant,
      generation: Long,
      newTimeModel: TimeModel,
  ): SetTimeModelRequest = randomLedger.setTimeModelRequest(mrt, generation, newTimeModel)

  override def setTimeModel(request: SetTimeModelRequest): Future[SetTimeModelResponse] =
    randomLedger.setTimeModel(request)

  override def prune(
      pruneUpTo: String,
      attempts: Int,
      pruneAllDivulgedContracts: Boolean,
  ): Future[PruneResponse] = randomLedger.prune(pruneUpTo, attempts, pruneAllDivulgedContracts)

  override def prune(
      pruneUpTo: LedgerOffset,
      attempts: Int,
      pruneAllDivulgedContracts: Boolean,
  ): Future[PruneResponse] = randomLedger.prune(pruneUpTo, attempts, pruneAllDivulgedContracts)
}
