// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils

import com.daml.error.{DamlContextualizedErrorLogger, ErrorResource, ValueSwitch}
import com.daml.ledger.api.DeduplicationPeriod
import com.daml.ledger.configuration.LedgerTimeModel
import com.daml.ledger.participant.state.kvutils.Conversions._
import com.daml.ledger.participant.state.kvutils.committer.transaction.Rejection
import com.daml.ledger.participant.state.kvutils.store.DamlStateKey
import com.daml.ledger.participant.state.kvutils.store.events.DamlTransactionBlindingInfo.{
  DisclosureEntry,
  DivulgenceEntry,
}
import com.daml.ledger.participant.state.kvutils.store.events.{
  DamlSubmitterInfo,
  DamlTransactionBlindingInfo,
  DamlTransactionRejectionEntry,
  Disputed,
  Duplicate,
  Inconsistent,
  InvalidLedgerTime,
  PartyNotKnownOnLedger,
  ResourcesExhausted,
  SubmitterCannotActViaParticipant,
}
import com.daml.ledger.participant.state.v2.Update.CommandRejected
import com.daml.lf.crypto
import com.daml.lf.crypto.Hash
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.Party
import com.daml.lf.data.Relation.Relation
import com.daml.lf.data.Time.{Timestamp => LfTimestamp}
import com.daml.lf.engine.Error
import com.daml.lf.transaction.{BlindingInfo, NodeId, TransactionOuterClass, TransactionVersion}
import com.daml.lf.value.Value.ContractId
import com.daml.lf.value.ValueOuterClass
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.{TextFormat, Timestamp}
import com.google.rpc.error_details.{ErrorInfo, ResourceInfo}
import io.grpc.Status.Code
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks.{Table, forAll}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Duration
import scala.annotation.nowarn
import scala.collection.immutable.{ListMap, ListSet}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

@nowarn("msg=deprecated")
class ConversionsSpec extends AnyWordSpec with Matchers with OptionValues {
  implicit private val testLoggingContext: LoggingContext = LoggingContext.ForTesting
  private val logger = ContextualizedLogger.get(getClass)
  implicit private val errorLoggingContext: DamlContextualizedErrorLogger =
    new DamlContextualizedErrorLogger(logger, testLoggingContext, None)

  "Conversions" should {
    "correctly and deterministically encode Blindinginfo" in {
      encodeBlindingInfo(
        wronglySortedBlindingInfo,
        Map(
          contractId0 -> rawApiContractInstance0,
          contractId1 -> rawApiContractInstance1,
        ),
      ) shouldBe correctlySortedEncodedBlindingInfo
    }

    "correctly decode BlindingInfo" in {
      val decodedBlindingInfo =
        decodeBlindingInfo(correctlySortedEncodedBlindingInfo)
      decodedBlindingInfo.disclosure.toSet should contain theSameElementsAs wronglySortedBlindingInfo.disclosure.toSet
      decodedBlindingInfo.divulgence.toSet should contain theSameElementsAs wronglySortedBlindingInfo.divulgence.toSet
    }

    "correctly extract divulged contracts" in {
      val maybeDivulgedContracts = extractDivulgedContracts(correctlySortedEncodedBlindingInfo)

      maybeDivulgedContracts shouldBe Right(
        Map(
          contractId0 -> rawApiContractInstance0,
          contractId1 -> rawApiContractInstance1,
        )
      )
    }

    "return Left with missing contract IDs when extracting divulged contracts if a contract instance is missing" in {
      val encodedBlindingInfoWithMissingContractInstance =
        correctlySortedEncodedBlindingInfo.toBuilder
          .addDivulgences(
            DivulgenceEntry.newBuilder().setContractId("some cid")
          )
          .build()

      val maybeDivulgedContracts =
        extractDivulgedContracts(encodedBlindingInfoWithMissingContractInstance)

      maybeDivulgedContracts shouldBe Left(Vector("some cid"))
    }

    "deterministically encode deduplication keys with multiple submitters (order independence)" in {
      val key1 = deduplicationKeyBytesFor(List("alice", "bob"))
      val key2 = deduplicationKeyBytesFor(List("bob", "alice"))
      key1 shouldBe key2
    }

    "deterministically encode deduplication keys with multiple submitters (duplicate submitters)" in {
      val key1 = deduplicationKeyBytesFor(List("alice", "bob"))
      val key2 = deduplicationKeyBytesFor(List("alice", "bob", "alice"))
      key1 shouldBe key2
    }

    "correctly encode deduplication keys with multiple submitters" in {
      val key1 = deduplicationKeyBytesFor(List("alice"))
      val key2 = deduplicationKeyBytesFor(List("alice", "bob"))
      key1 should not be key2
    }

    "encode/decode rejections" should {

      val submitterInfo = DamlSubmitterInfo.newBuilder().build()
      val now = LfTimestamp.now()

      "convert rejection to proto models and back to expected grpc v1 code" in {
        forAll(
          Table[Rejection, Code, Map[String, String]](
            ("Rejection", "Expected Code", "Expected Additional Details"),
            (
              Rejection.ValidationFailure(Error.Package(Error.Package.Internal("ERROR", "ERROR"))),
              Code.INVALID_ARGUMENT,
              Map.empty,
            ),
            (
              Rejection.InternallyInconsistentTransaction.InconsistentKeys,
              Code.INVALID_ARGUMENT,
              Map.empty,
            ),
            (
              Rejection.InternallyInconsistentTransaction.DuplicateKeys,
              Code.INVALID_ARGUMENT,
              Map.empty,
            ),
            (
              Rejection.ExternallyInconsistentTransaction.InconsistentContracts,
              Code.ABORTED,
              Map.empty,
            ),
            (
              Rejection.ExternallyInconsistentTransaction.InconsistentKeys,
              Code.ABORTED,
              Map.empty,
            ),
            (
              Rejection.ExternallyInconsistentTransaction.DuplicateKeys,
              Code.ABORTED,
              Map.empty,
            ),
            (
              Rejection.MissingInputState(DamlStateKey.getDefaultInstance),
              Code.ABORTED,
              Map.empty,
            ),
            (
              Rejection.InvalidParticipantState(Err.InternalError("error")),
              Code.INVALID_ARGUMENT,
              Map.empty,
            ),
            (
              Rejection.InvalidParticipantState(
                Err.ArchiveDecodingFailed(Ref.PackageId.assertFromString("id"), "reason")
              ),
              Code.INVALID_ARGUMENT,
              Map("package_id" -> "id"),
            ),
            (
              Rejection.InvalidParticipantState(Err.MissingDivulgedContractInstance("id")),
              Code.INVALID_ARGUMENT,
              Map("contract_id" -> "id"),
            ),
            (
              Rejection.RecordTimeOutOfRange(LfTimestamp.Epoch, LfTimestamp.Epoch),
              Code.ABORTED,
              Map(
                "minimum_record_time" -> LfTimestamp.Epoch.toString,
                "maximum_record_time" -> LfTimestamp.Epoch.toString,
              ),
            ),
            (
              Rejection.LedgerTimeOutOfRange(LedgerTimeModel.OutOfRange(now, now, now)),
              Code.ABORTED,
              Map.empty,
            ),
            (
              Rejection.CausalMonotonicityViolated,
              Code.ABORTED,
              Map.empty,
            ),
            (
              Rejection.PartiesNotKnownOnLedger(Seq.empty),
              Code.INVALID_ARGUMENT,
              Map.empty,
            ),
            (
              Rejection.MissingInputState(partyStateKey("party")),
              Code.ABORTED,
              Map("key" -> "party: \"party\"\n"),
            ),
            (
              Rejection.SubmittingPartyNotKnownOnLedger(party0),
              Code.INVALID_ARGUMENT,
              Map("submitter_party" -> party0),
            ),
            (
              Rejection.PartiesNotKnownOnLedger(Iterable(party0, party1)),
              Code.INVALID_ARGUMENT,
              Map("parties" -> s"""[\"$party0\",\"$party1\"]"""),
            ),
          )
        ) { (rejection, expectedCode, expectedAdditionalDetails) =>
          checkErrors(
            v1ErrorSwitch,
            submitterInfo,
            rejection,
            expectedCode,
            expectedAdditionalDetails,
          )
        }
      }

      "convert rejection to proto models and back to expected grpc v2 code" in {
        forAll(
          Table[Rejection, Code, Map[String, String], Map[ErrorResource, String]](
            (
              "Rejection",
              "Expected Code",
              "Expected Additional Details",
              "Expected Resources",
            ),
            (
              Rejection.ValidationFailure(Error.Package(Error.Package.Internal("ERROR", "ERROR"))),
              Code.FAILED_PRECONDITION,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.InternallyInconsistentTransaction.InconsistentKeys,
              Code.INTERNAL,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.InternallyInconsistentTransaction.DuplicateKeys,
              Code.INTERNAL,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.ExternallyInconsistentTransaction.InconsistentContracts,
              Code.FAILED_PRECONDITION,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.ExternallyInconsistentTransaction.InconsistentKeys,
              Code.FAILED_PRECONDITION,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.ExternallyInconsistentTransaction.DuplicateKeys,
              Code.ALREADY_EXISTS,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.MissingInputState(DamlStateKey.getDefaultInstance),
              Code.INTERNAL,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.InvalidParticipantState(Err.InternalError("error")),
              Code.INTERNAL,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.InvalidParticipantState(
                Err.ArchiveDecodingFailed(Ref.PackageId.assertFromString("id"), "reason")
              ),
              Code.INTERNAL,
              Map.empty, // package ID could be useful but the category is security sensitive
              Map.empty,
            ),
            (
              Rejection.InvalidParticipantState(Err.MissingDivulgedContractInstance("id")),
              Code.INTERNAL,
              Map.empty, // contract ID could be useful but the category is security sensitive
              Map.empty,
            ),
            (
              Rejection.RecordTimeOutOfRange(LfTimestamp.Epoch, LfTimestamp.Epoch),
              Code.FAILED_PRECONDITION,
              Map(
                "minimum_record_time" -> LfTimestamp.Epoch.toString,
                "maximum_record_time" -> LfTimestamp.Epoch.toString,
              ),
              Map.empty,
            ),
            (
              Rejection.LedgerTimeOutOfRange(
                LedgerTimeModel.OutOfRange(LfTimestamp.Epoch, LfTimestamp.Epoch, LfTimestamp.Epoch)
              ),
              Code.FAILED_PRECONDITION,
              Map(
                "ledger_time" -> LfTimestamp.Epoch.toString,
                "ledger_time_lower_bound" -> LfTimestamp.Epoch.toString,
                "ledger_time_upper_bound" -> LfTimestamp.Epoch.toString,
              ),
              Map.empty,
            ),
            (
              Rejection.CausalMonotonicityViolated,
              Code.FAILED_PRECONDITION,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.MissingInputState(partyStateKey("party")),
              Code.INTERNAL,
              Map.empty, // the missing state key could be useful but the category is security sensitive
              Map.empty,
            ),
            (
              Rejection.PartiesNotKnownOnLedger(Seq.empty),
              Code.NOT_FOUND,
              Map.empty,
              Map.empty,
            ),
            (
              Rejection.SubmittingPartyNotKnownOnLedger(party0),
              Code.NOT_FOUND,
              Map.empty,
              Map(ErrorResource.Party -> party0),
            ),
            (
              Rejection.PartiesNotKnownOnLedger(Iterable(party0, party1)),
              Code.NOT_FOUND,
              Map.empty,
              Map(ErrorResource.Party -> party0, ErrorResource.Party -> party1),
            ),
          )
        ) { (rejection, expectedCode, expectedAdditionalDetails, expectedResources) =>
          checkErrors(
            v2ErrorSwitch,
            submitterInfo,
            rejection,
            expectedCode,
            expectedAdditionalDetails,
            expectedResources,
          )
        }
      }

      "produce metadata that can be easily parsed" in {
        forAll(
          Table[Rejection, String, String => Any, Any](
            ("rejection", "metadata key", "metadata parser", "expected parsed metadata"),
            (
              Rejection.MissingInputState(partyStateKey("party")),
              "key",
              TextFormat.parse(_, classOf[DamlStateKey]),
              partyStateKey("party"),
            ),
            (
              Rejection.RecordTimeOutOfRange(LfTimestamp.Epoch, LfTimestamp.Epoch),
              "minimum_record_time",
              LfTimestamp.assertFromString,
              LfTimestamp.Epoch,
            ),
            (
              Rejection.RecordTimeOutOfRange(LfTimestamp.Epoch, LfTimestamp.Epoch),
              "maximum_record_time",
              LfTimestamp.assertFromString,
              LfTimestamp.Epoch,
            ),
            (
              Rejection.PartiesNotKnownOnLedger(Iterable(party0, party1)),
              "parties",
              jsonString => {
                val objectMapper = new ObjectMapper
                objectMapper.readValue(jsonString, classOf[java.util.List[_]]).asScala
              },
              mutable.Buffer(party0, party1),
            ),
          )
        ) { (rejection, metadataKey, metadataParser, expectedParsedMetadata) =>
          val encodedEntry = Conversions
            .encodeTransactionRejectionEntry(
              submitterInfo,
              rejection,
            )
            .build()
          val finalReason = Conversions
            .decodeTransactionRejectionEntry(encodedEntry, v1ErrorSwitch)
          finalReason.definiteAnswer shouldBe false
          val actualDetails = finalReasonDetails(finalReason).toMap
          metadataParser(actualDetails(metadataKey)) shouldBe expectedParsedMetadata
        }
      }

      "convert v1 rejections" should {

        "handle with expected status codes" in {
          forAll(
            Table[
              DamlTransactionRejectionEntry.Builder => DamlTransactionRejectionEntry.Builder,
              Code,
              Map[String, String],
            ](
              ("rejection builder", "code", "expected additional details"),
              (
                _.setInconsistent(Inconsistent.newBuilder()),
                Code.ABORTED,
                Map.empty,
              ),
              (
                _.setDisputed(Disputed.newBuilder()),
                Code.INVALID_ARGUMENT,
                Map.empty,
              ),
              (
                _.setResourcesExhausted(ResourcesExhausted.newBuilder()),
                Code.ABORTED,
                Map.empty,
              ),
              (
                _.setPartyNotKnownOnLedger(PartyNotKnownOnLedger.newBuilder()),
                Code.INVALID_ARGUMENT,
                Map.empty,
              ),
              (
                _.setDuplicateCommand(Duplicate.newBuilder().setSubmissionId("not_used")),
                Code.ALREADY_EXISTS,
                Map(),
              ),
              (
                _.setSubmitterCannotActViaParticipant(
                  SubmitterCannotActViaParticipant
                    .newBuilder()
                    .setSubmitterParty("party")
                    .setParticipantId("id")
                ),
                Code.PERMISSION_DENIED,
                Map(
                  "submitter_party" -> "party",
                  "participant_id" -> "id",
                ),
              ),
              (
                _.setInvalidLedgerTime(
                  InvalidLedgerTime
                    .newBuilder()
                    .setLowerBound(Timestamp.newBuilder().setSeconds(1L))
                    .setLedgerTime(Timestamp.newBuilder().setSeconds(2L))
                    .setUpperBound(Timestamp.newBuilder().setSeconds(3L))
                ),
                Code.ABORTED,
                Map(
                  "lower_bound" -> "seconds: 1\n",
                  "ledger_time" -> "seconds: 2\n",
                  "upper_bound" -> "seconds: 3\n",
                ),
              ),
            )
          ) { (rejectionBuilder, code, expectedAdditionalDetails) =>
            {
              val finalReason = Conversions
                .decodeTransactionRejectionEntry(
                  rejectionBuilder(DamlTransactionRejectionEntry.newBuilder())
                    .build(),
                  v1ErrorSwitch,
                )
              finalReason.code shouldBe code.value()
              finalReason.definiteAnswer shouldBe false
              val actualDetails = finalReasonDetails(finalReason)
              actualDetails should contain allElementsOf expectedAdditionalDetails
            }
          }
        }
      }
    }

    "decode duplicate command v2" in {
      val finalReason = Conversions
        .decodeTransactionRejectionEntry(
          DamlTransactionRejectionEntry
            .newBuilder()
            .setDuplicateCommand(Duplicate.newBuilder().setSubmissionId("submissionId"))
            .build(),
          v2ErrorSwitch,
        )
      finalReason.code shouldBe Code.ALREADY_EXISTS.value()
      finalReason.definiteAnswer shouldBe false
      val actualDetails = finalReasonDetails(finalReason)
      actualDetails should contain allElementsOf Map(
        "existing_submission_id" -> "submissionId"
      )
    }

    "decode completion info" should {
      val recordTime = LfTimestamp.now()
      def submitterInfo = {
        DamlSubmitterInfo.newBuilder().setApplicationId("id").setCommandId("commandId")
      }

      "use empty submission ID" in {
        val completionInfo = parseCompletionInfo(
          recordTime,
          submitterInfo.build(),
        )
        completionInfo.submissionId shouldBe None
      }

      "use defined submission ID" in {
        val submissionId = "submissionId"
        val completionInfo = parseCompletionInfo(
          recordTime,
          submitterInfo.setSubmissionId(submissionId).build(),
        )
        completionInfo.submissionId.value shouldBe submissionId
      }

      "calculate duration for deduplication for backwards compatibility with deduplicate until" in {
        val completionInfo = parseCompletionInfo(
          recordTime,
          submitterInfo
            .setDeduplicateUntil(buildTimestamp(recordTime.add(Duration.ofSeconds(30))))
            .build(),
        )
        completionInfo.optDeduplicationPeriod.value shouldBe DeduplicationPeriod
          .DeduplicationDuration(Duration.ofSeconds(30))
      }

      "handle deduplication which is the past relative to record time by using absolute values" in {
        val completionInfo = parseCompletionInfo(
          recordTime,
          submitterInfo
            .setDeduplicateUntil(buildTimestamp(recordTime.add(Duration.ofSeconds(30))))
            .build(),
        )
        completionInfo.optDeduplicationPeriod.value shouldBe DeduplicationPeriod
          .DeduplicationDuration(Duration.ofSeconds(30))
      }
    }
  }

  private def checkErrors(
      errorVersionSwitch: ValueSwitch,
      submitterInfo: DamlSubmitterInfo,
      rejection: Rejection,
      expectedCode: Code,
      expectedAdditionalDetails: Map[String, String],
      expectedResources: Map[ErrorResource, String] = Map.empty,
  ) = {
    val encodedEntry = Conversions
      .encodeTransactionRejectionEntry(
        submitterInfo,
        rejection,
      )
      .build()
    val finalReason = Conversions
      .decodeTransactionRejectionEntry(encodedEntry, errorVersionSwitch)
    finalReason.code shouldBe expectedCode.value()
    finalReason.definiteAnswer shouldBe false
    val actualDetails = finalReasonDetails(finalReason)
    val actualResources = finalReasonResources(finalReason)
    actualDetails should contain allElementsOf expectedAdditionalDetails
    actualResources should contain allElementsOf expectedResources
  }

  private def newDisclosureEntry(node: NodeId, parties: List[String]) =
    DisclosureEntry.newBuilder
      .setNodeId(node.index.toString)
      .addAllDisclosedToLocalParties(parties.asJava)
      .build

  private def newDivulgenceEntry(
      contractId: String,
      parties: List[String],
      rawContractInstance: Raw.ContractInstance,
  ) =
    DivulgenceEntry.newBuilder
      .setContractId(contractId)
      .addAllDivulgedToLocalParties(parties.asJava)
      .setRawContractInstance(rawContractInstance.bytes)
      .build

  private lazy val party0: Party = Party.assertFromString("party0")
  private lazy val party1: Party = Party.assertFromString("party1")
  private lazy val contractId0: ContractId = ContractId.V1(wronglySortedHashes.tail.head)
  private lazy val contractId1: ContractId = ContractId.V1(wronglySortedHashes.head)
  private lazy val node0: NodeId = NodeId(0)
  private lazy val node1: NodeId = NodeId(1)
  private lazy val rawApiContractInstance0 = rawApiContractInstance("contract 0")
  private lazy val rawApiContractInstance1 = rawApiContractInstance("contract 1")

  private lazy val wronglySortedPartySet = ListSet(party1, party0)
  private lazy val wronglySortedHashes: List[Hash] =
    List(crypto.Hash.hashPrivateKey("hash0"), crypto.Hash.hashPrivateKey("hash1")).sorted.reverse
  private lazy val wronglySortedDisclosure: Relation[NodeId, Party] =
    ListMap(node1 -> wronglySortedPartySet, node0 -> wronglySortedPartySet)
  private lazy val wronglySortedDivulgence: Relation[ContractId, Party] =
    ListMap(contractId1 -> wronglySortedPartySet, contractId0 -> wronglySortedPartySet)
  private lazy val wronglySortedBlindingInfo = BlindingInfo(
    disclosure = wronglySortedDisclosure,
    divulgence = wronglySortedDivulgence,
  )

  private lazy val correctlySortedParties = List(party0, party1)
  private lazy val correctlySortedPartiesAsStrings =
    correctlySortedParties.asInstanceOf[List[String]]
  private lazy val correctlySortedEncodedBlindingInfo =
    DamlTransactionBlindingInfo.newBuilder
      .addAllDisclosures(
        List(
          newDisclosureEntry(node0, correctlySortedPartiesAsStrings),
          newDisclosureEntry(node1, correctlySortedPartiesAsStrings),
        ).asJava
      )
      .addAllDivulgences(
        List(
          newDivulgenceEntry(
            contractId0.coid,
            correctlySortedPartiesAsStrings,
            rawApiContractInstance0,
          ),
          newDivulgenceEntry(
            contractId1.coid,
            correctlySortedPartiesAsStrings,
            rawApiContractInstance1,
          ),
        ).asJava
      )
      .build

  private lazy val v1ErrorSwitch = new ValueSwitch(enableSelfServiceErrorCodes = false)
  private lazy val v2ErrorSwitch = new ValueSwitch(enableSelfServiceErrorCodes = true)

  private[this] val txVersion = TransactionVersion.StableVersions.max

  private def deduplicationKeyBytesFor(parties: List[String]): Array[Byte] = {
    val submitterInfo = DamlSubmitterInfo.newBuilder
      .setApplicationId("test")
      .setCommandId("a command ID")
      .setDeduplicateUntil(com.google.protobuf.Timestamp.getDefaultInstance)
      .addAllSubmitters(parties.asJava)
      .build
    val deduplicationKey = commandDedupKey(submitterInfo)
    deduplicationKey.toByteArray
  }

  private def rawApiContractInstance(discriminator: String) = {
    val contractInstance = TransactionOuterClass.ContractInstance
      .newBuilder()
      .setTemplateId(
        ValueOuterClass.Identifier
          .newBuilder()
          .setPackageId("some")
          .addModuleName("template")
          .addName("name")
      )
      .setArgVersioned(
        ValueOuterClass.VersionedValue
          .newBuilder()
          .setVersion(txVersion.protoValue)
          .setValue(
            ValueOuterClass.Value.newBuilder().setText(discriminator).build().toByteString
          )
      )
      .build()
    Raw.ContractInstance(contractInstance.toByteString)
  }

  private def finalReasonDetails(
      finalReason: CommandRejected.FinalReason
  ): Seq[(String, String)] =
    finalReason.status.details.flatMap { anyProto =>
      if (anyProto.is[ErrorInfo])
        anyProto.unpack[ErrorInfo].metadata
      else
        Map.empty[String, String]
    }

  private def finalReasonResources(
      finalReason: CommandRejected.FinalReason
  ): Seq[(ErrorResource, String)] =
    finalReason.status.details.flatMap { anyProto =>
      if (anyProto.is[ResourceInfo]) {
        val resourceInfo = anyProto.unpack[ResourceInfo]
        Map(ErrorResource.fromString(resourceInfo.resourceType).get -> resourceInfo.resourceName)
      } else {
        Map.empty[ErrorResource, String]
      }
    }
}
