// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.sandbox.auth

import com.daml.platform.sandbox.services.SubmitAndWaitDummyCommand

import scala.concurrent.Future

final class SubmitAndWaitForTransactionTreeAuthIT
    extends ReadWriteServiceCallAuthTests
    with SubmitAndWaitDummyCommand {

  override def serviceCallName: String = "CommandService#SubmitAndWaitForTransactionTree"

  override def serviceCallWithToken(token: Option[String]): Future[Any] =
    submitAndWaitForTransactionTree(token)

  override def serviceCallWithoutApplicationId(token: Option[String]): Future[Any] =
    submitAndWaitForTransactionTree(token, "")

}
