// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.error.definitions

import com.daml.error.{ErrorClass, ErrorGroupImpl}

object ErrorGroups {
  val rootErrorClass: ErrorClass = ErrorClass.root()

  object ParticipantErrorGroup extends ErrorGroupImpl("Participant")(rootErrorClass) {
    abstract class IndexErrorGroup extends ErrorGroupImpl("Index") {
      abstract class DatabaseErrorGroup extends ErrorGroupImpl("Database")
    }
    abstract class LedgerApiErrorGroup extends ErrorGroupImpl("Ledger API") {
      abstract class CommandExecutionErrorGroup extends ErrorGroupImpl("Command Execution")
      abstract class PackageServiceErrorGroup extends ErrorGroupImpl("Package Management Service")
    }
  }
}
