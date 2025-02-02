// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.auth

import java.util.concurrent.{CompletableFuture, CompletionStage}

import com.daml.lf.data.Ref
import com.daml.jwt.{JwtVerifier, JwtVerifierBase}
import com.daml.ledger.api.auth.AuthServiceJWT.Error
import io.grpc.Metadata
import org.slf4j.{Logger, LoggerFactory}
import spray.json._

import scala.collection.mutable.ListBuffer
import scala.util.Try

/** An AuthService that reads a JWT token from a `Authorization: Bearer` HTTP header.
  * The token is expected to use the format as defined in [[AuthServiceJWTPayload]]:
  */
class AuthServiceJWT(verifier: JwtVerifierBase) extends AuthService {

  protected val logger: Logger = LoggerFactory.getLogger(AuthServiceJWT.getClass)

  override def decodeMetadata(headers: Metadata): CompletionStage[ClaimSet] =
    CompletableFuture.completedFuture {
      getAuthorizationHeader(headers) match {
        case None => ClaimSet.Unauthenticated
        case Some(header) => parseHeader(header)
      }
    }

  private[this] def getAuthorizationHeader(headers: Metadata): Option[String] =
    Option.apply(headers.get(AUTHORIZATION_KEY))

  private[this] def parseHeader(header: String): ClaimSet =
    parseJWTPayload(header).fold(
      error => {
        logger.warn("Authorization error: " + error.message)
        ClaimSet.Unauthenticated
      },
      token => payloadToClaims(token),
    )

  private[this] def parsePayload(jwtPayload: String): Either[Error, SupportedJWTPayload] = {
    import SupportedJWTCodec.JsonImplicits._
    Try(JsonParser(jwtPayload).convertTo[SupportedJWTPayload]).toEither.left.map(t =>
      Error("Could not parse JWT token: " + t.getMessage)
    )
  }

  private[this] def parseJWTPayload(header: String): Either[Error, SupportedJWTPayload] = {
    val BearerTokenRegex = "Bearer (.*)".r

    for {
      token <- BearerTokenRegex
        .findFirstMatchIn(header)
        .map(_.group(1))
        .toRight(Error("Authorization header does not use Bearer format"))
      decoded <- verifier
        .verify(com.daml.jwt.domain.Jwt(token))
        .toEither
        .left
        .map(e => Error("Could not verify JWT token: " + e.message))
      parsed <- parsePayload(decoded.payload)
    } yield parsed
  }

  private[this] def payloadToClaims(payload: SupportedJWTPayload): ClaimSet = payload match {
    case CustomDamlJWTPayload(payload) =>
      val claims = ListBuffer[Claim]()

      // Any valid token authorizes the user to use public services
      claims.append(ClaimPublic)

      if (payload.admin)
        claims.append(ClaimAdmin)

      payload.actAs
        .foreach(party => claims.append(ClaimActAsParty(Ref.Party.assertFromString(party))))

      payload.readAs
        .foreach(party => claims.append(ClaimReadAsParty(Ref.Party.assertFromString(party))))

      ClaimSet.Claims(
        claims = claims.toList,
        ledgerId = payload.ledgerId,
        participantId = payload.participantId,
        applicationId = payload.applicationId,
        expiration = payload.exp,
        resolvedFromUser = false,
      )

    case StandardJWTPayload(payload) =>
      ClaimSet.AuthenticatedUser(
        participantId = payload.participantId,
        userId = payload.applicationId.get,
        expiration = payload.exp,
      )
  }
}

object AuthServiceJWT {
  final case class Error(message: String)

  def apply(verifier: com.auth0.jwt.interfaces.JWTVerifier) =
    new AuthServiceJWT(new JwtVerifier(verifier))

  def apply(verifier: JwtVerifierBase) =
    new AuthServiceJWT(verifier)
}
