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

trait ParticipantTestContext {

  val ledgerId: String
  val endpointId: String
  val applicationId: String
  val identifierSuffix: String
  val ledgerEndpoint: Endpoint
  val clientTlsConfiguration: Option[TlsConfiguration]
  val referenceOffset: LedgerOffset

  val begin: LedgerOffset =
    LedgerOffset(LedgerOffset.Value.Boundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN))

  val end: LedgerOffset =
    LedgerOffset(LedgerOffset.Value.Boundary(LedgerOffset.LedgerBoundary.LEDGER_END))
  val nextKeyId: () => String

  def toString: String

  def currentEnd(): Future[LedgerOffset]

  def currentEnd(overrideLedgerId: String): Future[LedgerOffset]

  def offsetBeyondLedgerEnd(): Future[LedgerOffset]
  def time(): Future[Instant]
  def listKnownPackages(): Future[Seq[PackageDetails]]
  def uploadDarFile(bytes: ByteString): Future[Unit]
  def uploadDarRequest(bytes: ByteString): UploadDarFileRequest
  def uploadDarFile(request: UploadDarFileRequest): Future[Unit]
  def participantId(): Future[String]
  def listPackages(): Future[Seq[String]]
  def getPackage(packageId: String): Future[GetPackageResponse]
  def getPackageStatus(packageId: String): Future[PackageStatus]

  def allocateParty(): Future[Primitive.Party]
  def allocateParty(
      partyIdHint: Option[String],
      displayName: Option[String],
  ): Future[Primitive.Party]
  def allocateParties(n: Int): Future[Vector[Primitive.Party]]
  def getParties(parties: Seq[Primitive.Party]): Future[Seq[PartyDetails]]
  def listKnownParties(): Future[Set[Primitive.Party]]
  def waitForParties(
      otherParticipants: Iterable[ParticipantTestContext],
      expectedParties: Set[Primitive.Party],
  ): Future[Unit]
  def activeContracts(
      request: GetActiveContractsRequest
  ): Future[(Option[LedgerOffset], Vector[CreatedEvent])]
  def activeContractsRequest(
      parties: Seq[Primitive.Party],
      templateIds: Seq[Identifier] = Seq.empty,
  ): GetActiveContractsRequest
  def activeContracts(parties: Primitive.Party*): Future[Vector[CreatedEvent]]
  def activeContractsByTemplateId(
      templateIds: Seq[Identifier],
      parties: Primitive.Party*
  ): Future[Vector[CreatedEvent]]

  def getTransactionsRequest(
      parties: Seq[Primitive.Party],
      templateIds: Seq[TemplateId] = Seq.empty,
      begin: LedgerOffset = referenceOffset,
  ): GetTransactionsRequest
  def transactionStream(
      request: GetTransactionsRequest,
      responseObserver: StreamObserver[GetTransactionsResponse],
  ): Unit
  def flatTransactionsByTemplateId(
      templateId: TemplateId,
      parties: Primitive.Party*
  ): Future[Vector[Transaction]]

  def flatTransactions(request: GetTransactionsRequest): Future[Vector[Transaction]]

  def flatTransactions(parties: Primitive.Party*): Future[Vector[Transaction]]

  def flatTransactions(take: Int, request: GetTransactionsRequest): Future[Vector[Transaction]]

  def flatTransactions(take: Int, parties: Primitive.Party*): Future[Vector[Transaction]]
  def transactionTreesByTemplateId(
      templateId: TemplateId,
      parties: Primitive.Party*
  ): Future[Vector[TransactionTree]]

  def transactionTrees(request: GetTransactionsRequest): Future[Vector[TransactionTree]]

  def transactionTrees(parties: Primitive.Party*): Future[Vector[TransactionTree]]

  def transactionTrees(
      take: Int,
      request: GetTransactionsRequest,
  ): Future[Vector[TransactionTree]]

  def transactionTrees(take: Int, parties: Primitive.Party*): Future[Vector[TransactionTree]]

  def getTransactionByIdRequest(
      transactionId: String,
      parties: Seq[Primitive.Party],
  ): GetTransactionByIdRequest

  def transactionTreeById(request: GetTransactionByIdRequest): Future[TransactionTree]

  def transactionTreeById(transactionId: String, parties: Primitive.Party*): Future[TransactionTree]

  def flatTransactionById(request: GetTransactionByIdRequest): Future[Transaction]

  def flatTransactionById(transactionId: String, parties: Primitive.Party*): Future[Transaction]

  def getTransactionByEventIdRequest(
      eventId: String,
      parties: Seq[Primitive.Party],
  ): GetTransactionByEventIdRequest

  def transactionTreeByEventId(request: GetTransactionByEventIdRequest): Future[TransactionTree]

  def transactionTreeByEventId(eventId: String, parties: Primitive.Party*): Future[TransactionTree]

  def flatTransactionByEventId(request: GetTransactionByEventIdRequest): Future[Transaction]

  def flatTransactionByEventId(eventId: String, parties: Primitive.Party*): Future[Transaction]
  def create[T](
      party: Primitive.Party,
      template: Template[T],
  ): Future[Primitive.ContractId[T]]
  def create[T](
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      template: Template[T],
  ): Future[Primitive.ContractId[T]]
  def createAndGetTransactionId[T](
      party: Primitive.Party,
      template: Template[T],
  ): Future[(String, Primitive.ContractId[T])]
  def exercise[T](
      party: Primitive.Party,
      exercise: Primitive.Party => Primitive.Update[T],
  ): Future[TransactionTree]
  def exercise[T](
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      exercise: => Primitive.Update[T],
  ): Future[TransactionTree]
  def exerciseForFlatTransaction[T](
      party: Primitive.Party,
      exercise: Primitive.Party => Primitive.Update[T],
  ): Future[Transaction]
  def exerciseAndGetContract[T](
      party: Primitive.Party,
      exercise: Primitive.Party => Primitive.Update[Any],
  ): Future[Primitive.ContractId[T]]
  def exerciseByKey[T](
      party: Primitive.Party,
      template: Primitive.TemplateId[T],
      key: Value,
      choice: String,
      argument: Value,
  ): Future[TransactionTree]
  def submitRequest(
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      commands: Command*
  ): SubmitRequest
  def submitRequest(party: Primitive.Party, commands: Command*): SubmitRequest
  def submitAndWaitRequest(
      actAs: List[Primitive.Party],
      readAs: List[Primitive.Party],
      commands: Command*
  ): SubmitAndWaitRequest
  def submitAndWaitRequest(party: Primitive.Party, commands: Command*): SubmitAndWaitRequest
  def submit(request: SubmitRequest): Future[Unit]
  def submitAndWait(request: SubmitAndWaitRequest): Future[Unit]
  def submitAndWaitForTransactionId(request: SubmitAndWaitRequest): Future[String]
  def submitAndWaitForTransaction(request: SubmitAndWaitRequest): Future[Transaction]
  def submitAndWaitForTransactionTree(request: SubmitAndWaitRequest): Future[TransactionTree]
  def completionStreamRequest(from: LedgerOffset = referenceOffset)(
      parties: Primitive.Party*
  ): CompletionStreamRequest
  def completionEnd(request: CompletionEndRequest): Future[CompletionEndResponse]
  def completionStream(
      request: CompletionStreamRequest,
      streamObserver: StreamObserver[CompletionStreamResponse],
  ): Unit
  def firstCompletions(request: CompletionStreamRequest): Future[Vector[Completion]]
  def firstCompletions(parties: Primitive.Party*): Future[Vector[Completion]]
  def findCompletion(
      request: CompletionStreamRequest
  )(p: Completion => Boolean): Future[Option[Completion]]
  def findCompletion(parties: Primitive.Party*)(
      p: Completion => Boolean
  ): Future[Option[Completion]]
  def checkpoints(n: Int, request: CompletionStreamRequest): Future[Vector[Checkpoint]]
  def checkpoints(n: Int, from: LedgerOffset)(
      parties: Primitive.Party*
  ): Future[Vector[Checkpoint]]
  def firstCheckpoint(request: CompletionStreamRequest): Future[Checkpoint]
  def firstCheckpoint(parties: Primitive.Party*): Future[Checkpoint]
  def nextCheckpoint(request: CompletionStreamRequest): Future[Checkpoint]
  def nextCheckpoint(from: LedgerOffset, parties: Primitive.Party*): Future[Checkpoint]
  def configuration(overrideLedgerId: Option[String] = None): Future[LedgerConfiguration]
  def checkHealth(): Future[HealthCheckResponse]
  def watchHealth(): Future[Seq[HealthCheckResponse]]
  def getTimeModel(): Future[GetTimeModelResponse]
  def setTimeModel(
      mrt: Instant,
      generation: Long,
      newTimeModel: TimeModel,
  ): Future[SetTimeModelResponse]
  def setTimeModelRequest(
      mrt: Instant,
      generation: Long,
      newTimeModel: TimeModel,
  ): SetTimeModelRequest
  def setTimeModel(
      request: SetTimeModelRequest
  ): Future[SetTimeModelResponse]
  def prune(
      pruneUpTo: String,
      attempts: Int,
      pruneAllDivulgedContracts: Boolean,
  ): Future[PruneResponse]
  def prune(
      pruneUpTo: LedgerOffset,
      attempts: Int = 10,
      pruneAllDivulgedContracts: Boolean = false,
  ): Future[PruneResponse]
}
