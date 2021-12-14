// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.backend.common

import java.sql.Connection
import java.time.Instant

import anorm.SqlParser.{int, long, str}
import com.daml.ledger.api.domain
import com.daml.lf.data.Ref.UserId
import com.daml.platform.store.backend.UserManagementStorageBackend
import anorm.{RowParser, SqlStringInterpolation, ~}
import com.daml.lf.data.Ref
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.platform.store.SimpleSqlAsVectorOf.SimpleSqlAsVectorOf

object UserManagementStorageBackendTemplate extends UserManagementStorageBackend {

  private val logger = ContextualizedLogger.get(getClass)
  implicit private val loggingContext: LoggingContext = LoggingContext.newLoggingContext(identity)

  private val ParticipantUserParser: RowParser[(Long, String, Option[String], Long)] =
    long("internal_id") ~ str("user_id") ~ str("primary_party").? ~ long("created_at") map {
      case internalId ~ userId ~ primaryParty ~ createdAt =>
        (internalId, userId, primaryParty, createdAt)
    }

  private val UserRightParser: RowParser[(Int, Option[String])] =
    int("user_right") ~ str("for_party").? map { case user_right ~ for_party =>
      (user_right, for_party)
    }




  override def createUser(user: domain.User, rights: Set[domain.UserRight])(
      connection: Connection
  ): (Option[Long], Long) = {
    // TODO: What's the best way to get epoch?
    // TODO: Seconds or micros, millis or nanos?
    val nowEpoch = Instant.now.getEpochSecond
    val _ = nowEpoch
    //    VALUES (${user.id.toString}, '${user.primaryParty.map(_.toString)}')
    val id: Option[Long] =
      // TODO: `user.id.toString` ?? in anorm's string interpolation
      SQL"""
         INSERT INTO participant_user (user_id, primary_party, created_at)
         VALUES (${user.id.toString}, ${user.primaryParty.map(_.toString)}, $nowEpoch)
       """.executeInsert()(connection)

    // TODO
    require(id.isDefined)

    val values: Seq[(Long, Int, String, Long)] = rights.map { right =>
      // TODO insert NULL when no target party
      (id.get, right.internal_id, right.target_party.getOrElse(""), nowEpoch)
    }.toSeq
    // TODO insert all rows at once: anorm ? https://stackoverflow.com/questions/14675862/inserting-multiple-values-into-table-with-anorm
    val addedRightsCount: Long = values.map { case (id, right, target_party, granted_at) =>
      SQL"""
         INSERT INTO user_rights (user_internal_id, user_right, for_party, granted_at)
         VALUES
            ($id, $right, $target_party, $granted_at)

         """.executeUpdate()(connection).toLong
    }.sum
    (id, addedRightsCount)
  }

  override def getUser(id: UserId)(connection: Connection): Option[domain.User] = {
    val rec0 =
      SQL"""
         SELECT internal_id, user_id, primary_party, created_at
         FROM participant_user
         WHERE user_id = ${id.toString}
         """.asVectorOf(ParticipantUserParser)(connection).headOption // TODO headOption

    rec0.map { case (internalId, userId, primaryParty, createdAt) =>
      logger.info(
        s"Retrieved user: (internalId, userId, primaryParty, createdAt) = ${(internalId, userId, primaryParty, createdAt)}"
      )
      domain.User(
        id = Ref.UserId.assertFromString(userId),
        primaryParty = primaryParty.map(Ref.Party.assertFromString),
      )

    }

  }

  override def deleteUser(id: UserId)(connection: Connection): Unit = {
    // TODO: Fail if user was non existent
    val updatedRowsCount =
      SQL"""
         DELETE FROM  participant_user WHERE user_id = ${id.toString}
         """.executeUpdate()(connection)
    assert(updatedRowsCount == 1)
  }

  override def addUserRights(id: UserId, rights: Set[domain.UserRight])(
      connection: Connection
  ): Set[domain.UserRight] = {
    // TODO batch update?
    //    http://playframework.github.io/anorm/#multi-value-parameter

    // import anorm.BatchSql
    //
    //val batch = BatchSql(
    //  "INSERT INTO books(title, author) VALUES({title}, {author})",
    //  Seq[NamedParameter]("title" -> "Play 2 for Scala",
    //    "author" -> "Peter Hilton"),
    //  Seq[NamedParameter]("title" -> "Learning Play! Framework 2",
    //    "author" -> "Andy Petrella"))

    //    WITH archival_event AS (
    //      SELECT participant_events.*
    //    FROM participant_events
    //      WHERE contract_id = $id
    //    AND event_kind = 20  -- consuming exercise
    //      AND event_sequential_id <= $lastEventSequentialId
    //      FETCH NEXT 1 ROW ONLY
    //    ),
    val nowEpoch = Instant.now.getEpochSecond

    // SELECT internal_id from user_internal_id_x LIMIT 1

    val effective = rights.map { right =>
      val rowsUpdatedCount: Int =
        SQL"""
         WITH user_internal_id_x AS (
          SELECT internal_id
          FROM participant_user
          WHERE user_id = ${id.toString}
          FETCH NEXT 1 ROW ONLY
         )
         INSERT INTO user_rights (user_internal_id, user_right, for_party, granted_at)
         VALUES (
            (SELECT internal_id from user_internal_id_x LIMIT 1),
            ${right.internal_id},
            ${right.target_party.map(_.toString)},
            $nowEpoch
            )
         ON CONFLICT DO NOTHING
         """.executeUpdate()(connection)
      rowsUpdatedCount match {
        case 0 => None
        case 1 => Some(right)
        case other => throw new RuntimeException(other.toString)
      }
    }
    effective.flatten
  }

  override def getUserRights(id: UserId)(connection: Connection): Set[domain.UserRight] = {
    val rec: Seq[(Int, Option[String])] =
      SQL"""
         SELECT ur.user_right, ur.for_party
         FROM user_rights AS ur
         JOIN participant_user AS pu
            ON pu.internal_id = ur.user_internal_id
         WHERE
            pu.user_id = ${id.toString}
         """.asVectorOf(UserRightParser)(connection)
    rec.map { case (userRight, forParty) =>
      // TODO forParty is empty: Problem when inserting or when retrieving? How to debug it?
      domain.UserRight.from(
        internal_id = userRight,
        party = if (forParty.exists(_.isBlank)) None else forParty.map(Ref.Party.assertFromString),
      )
    }.toSet
    // TODO: ? Check for duplicates ?
  }

//  override def deleteUser(id: UserId)(connection: Connection): Unit = {
//    // TODO: Fail if user was non existent
//    val updatedRowsCount =
//      SQL"""
//         DELETE FROM  participant_user WHERE user_id = ${id.toString}
//         """.executeUpdate()(connection)
//    assert(updatedRowsCount == 1)
//  }
  override def deleteUserRights(id: UserId, rights: Set[domain.UserRight])(
      connection: Connection
  ): Set[domain.UserRight] = {
//    SQL"""
//       DELETE FROM user_rights
//
//       """

  }
}
