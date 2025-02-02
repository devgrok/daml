// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.testing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.daml.platform.testing.LogCollector.Entry
import com.daml.scalautil.Statement
import org.scalatest.matchers.should.Matchers
import org.scalatest.Checkpoints.Checkpoint
import org.slf4j.Marker

import scala.beans.BeanProperty
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.reflect.ClassTag

object LogCollector {

  case class Entry(level: Level, msg: String, marker: Option[Marker])
  case class ExpectedLogEntry(level: Level, msg: String, markerRegex: Option[String])

  private val log = TrieMap.empty[String, TrieMap[String, mutable.Builder[Entry, Vector[Entry]]]]

  def read[Test, Logger](implicit
      test: ClassTag[Test],
      logger: ClassTag[Logger],
  ): IndexedSeq[(Level, String)] =
    read[Test](logger.runtimeClass.getName)

  def read[Test](
      loggerClassName: String
  )(implicit test: ClassTag[Test]): IndexedSeq[(Level, String)] =
    log
      .get(test.runtimeClass.getName)
      .flatMap(_.get(loggerClassName))
      .map(_.mapResult(_.map(e => e.level -> e.msg)))
      .fold(IndexedSeq.empty[(Level, String)])(_.result())

  def readAsEntries[Test, Logger](implicit
      test: ClassTag[Test],
      logger: ClassTag[Logger],
  ): Seq[Entry] =
    log
      .get(test.runtimeClass.getName)
      .flatMap(_.get(logger.runtimeClass.getName))
      .fold(IndexedSeq.empty[Entry])(_.result())

  def clear[Test](implicit test: ClassTag[Test]): Unit = {
    log.remove(test.runtimeClass.getName)
    ()
  }

}

final class LogCollector extends AppenderBase[ILoggingEvent] {

  @BeanProperty
  var test: String = _

  override def append(e: ILoggingEvent): Unit = {
    if (test == null) {
      addError("Test identifier undefined, skipping logging")
    } else {
      val log = LogCollector.log
        .getOrElseUpdate(test, TrieMap.empty)
        .getOrElseUpdate(e.getLoggerName, Vector.newBuilder)
      val _ = log.synchronized { log += Entry(e.getLevel, e.getMessage, Option(e.getMarker)) }
    }
  }
}

trait LogCollectorAssertions { self: Matchers =>

  def assertLogEntry(actual: LogCollector.Entry, expected: LogCollector.ExpectedLogEntry): Unit = {
    assertLogEntry(actual, expected.level, expected.msg, expected.markerRegex)
  }

  def assertLogEntry(
      actual: LogCollector.Entry,
      expectedLogLevel: Level,
      expectedMsg: String,
      expectedMarkerRegex: Option[String],
  ): Unit = {
    val cp = new Checkpoint
    cp { Statement.discard { actual.level shouldBe expectedLogLevel } }
    cp { Statement.discard { actual.msg shouldBe expectedMsg } }
    if (expectedMarkerRegex.isDefined) {
      cp { Statement.discard { actual.marker shouldBe defined } }
      cp {
        Statement.discard {
          actual.marker.get.toString should fullyMatch regex expectedMarkerRegex.get
        }
      }
    } else {
      cp { Statement.discard { actual.marker shouldBe None } }
    }
    cp.reportAll()
  }
}
