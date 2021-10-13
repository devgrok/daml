// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.testtool.infrastructure

import com.daml.ledger.api.testtool.infrastructure.Allocation.{
  Participant,
  ParticipantAllocation,
  Participants,
}
import com.daml.ledger.api.testtool.infrastructure.participant.{
  LedgerParticipantTestContext,
  ParticipantTestContext,
  RandomParticipantTestContext,
}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

private[testtool] final class LedgerTestContext private[infrastructure] (
    val configuredParticipants: immutable.Seq[ParticipantTestContext]
)(implicit ec: ExecutionContext) {

  require(configuredParticipants.nonEmpty, "At least one participant must be provided.")

  private[this] val participantsRing = Iterator.continually(configuredParticipants).flatten

  /** This allocates participants and a specified number of parties for each participant.
    *
    * e.g. `allocate(ParticipantAllocation(SingleParty, Parties(3), NoParties, TwoParties))`
    * will eventually return:
    *
    * {{{
    * Participants(
    *   Participant(alpha: ParticipantTestContext, alice: Party),
    *   Participant(beta: ParticipantTestContext, bob: Party, barbara: Party, bernard: Party),
    *   Participant(gamma: ParticipantTestContext),
    *   Participant(delta: ParticipantTestContext, doreen: Party, dan: Party),
    * )
    * }}}
    *
    * Each test allocates participants, then deconstructs the result and uses the various ledgers
    * and parties throughout the test.
    */
  def allocate(
      allocation: ParticipantAllocation,
      multiParticipantTest: Boolean,
  ): Future[Participants] = {
    val participantAllocations = allocation.partyCounts.map(nextParticipant() -> _)
    Future
      .sequence(participantAllocations.map {
        case (participant: LedgerParticipantTestContext, partyCount) =>
          participant
            .preallocateParties(partyCount.count, configuredParticipants)
            .map(parties => Participant(participant, parties: _*))
        case _ =>
          throw new IllegalArgumentException(
            "During allocation only the actual ledger participant should be present"
          )
      })
      .map(allocatedParticipants =>
        if (multiParticipantTest)
          Participants(
            Participant(
              new RandomParticipantTestContext(configuredParticipants),
              allocatedParticipants.flatMap(_.parties): _*
            )
          )
        else Participants(allocatedParticipants: _*)
      )
  }

  private[this] def nextParticipant(): ParticipantTestContext =
    participantsRing.synchronized {
      participantsRing.next()
    }
}
