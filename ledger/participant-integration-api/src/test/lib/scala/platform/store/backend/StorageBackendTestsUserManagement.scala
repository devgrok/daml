// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.backend

import com.daml.ledger.api.domain.UserRight.{CanActAs, CanReadAs, ParticipantAdmin}
import com.daml.ledger.api.domain.{User, UserRight}
import com.daml.lf.data.Ref
import org.scalatest.Inside
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

private[backend] trait StorageBackendTestsUserManagement
    extends Matchers
    with Inside
    with StorageBackendSpec {
  this: AsyncFlatSpec =>

  behavior of "StorageBackend (UserManagement)"

  it should "create user with rights" in {
    val user = User(
      id = Ref.UserId.assertFromString("user_id_123"),
      primaryParty = Some(Ref.Party.assertFromString("primary_party_123")),
    )
    val rights: Set[UserRight] = Seq(
      ParticipantAdmin,
      CanActAs(Ref.Party.assertFromString("party_act_as_1")),
      CanActAs(Ref.Party.assertFromString("party_act_as_2")),
      CanReadAs(Ref.Party.assertFromString("party_read_as_1")),
    ).toSet
    val rightsToAdd: Set[UserRight] = Set(
      CanActAs(Ref.Party.assertFromString("party_act_as_2")),
      CanActAs(Ref.Party.assertFromString("party_act_as_3")),
      CanReadAs(Ref.Party.assertFromString("party_read_as_2")),
    )

    val tested = backend.userManagement

    for {
      (user_id, addedRightsCount) <- executeSql(tested.createUser(user = user, rights = rights))

      addedUser <- executeSql(tested.getUser(id = user.id))
      addedUserRights <- executeSql(tested.getUserRights(id = user.id))

      effectivelyAddedRights <- executeSql(tested.addUserRights(id = user.id, rights = rightsToAdd))
      allUserRights <- executeSql(tested.getUserRights(id = user.id))

      _ <- executeSql(tested.deleteUser(id = user.id))
      deletedUser <- executeSql(tested.getUser(id = user.id))
      deletedRights <- executeSql(tested.getUserRights(id = user.id))
    } yield {
      user_id shouldBe Some(1)
      addedRightsCount shouldBe 4

      addedUser shouldBe Some(user)
      addedUserRights shouldBe rights

      effectivelyAddedRights shouldBe Set(
        CanActAs(Ref.Party.assertFromString("party_act_as_3")),
        CanReadAs(Ref.Party.assertFromString("party_read_as_2")),
      )
      allUserRights shouldBe (rightsToAdd ++ rights)

      deletedUser shouldBe None
      deletedRights shouldBe Set.empty

    }
  }

}
