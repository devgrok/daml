// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.logging

import com.daml.logging.entries.LoggingEntries
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LoggingContextSpec extends AnyFlatSpec with Matchers {

  it should "do it" in {
    val empty: LoggingContext = LoggingContext.newLoggingContext(identity)

    val next = LoggingContext.enrichedLoggingContext("a" -> "b")(empty)

    next.entries shouldBe LoggingEntries("a" -> "b")
  }

}
