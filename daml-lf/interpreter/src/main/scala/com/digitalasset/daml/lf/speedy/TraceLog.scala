// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.speedy

import com.daml.lf.data.Ref.Location
import org.apache.commons.text.StringEscapeUtils
import org.slf4j.Logger

private[lf] trait TraceLog {
  def add(message: String, optLocation: Option[Location]): Unit
  def iterator: Iterator[(String, Option[Location])]
}

private[lf] final case class RingBufferTraceLog(logger: Logger, capacity: Int) extends TraceLog {

  private val buffer = Array.ofDim[(String, Option[Location])](capacity)
  private var pos: Int = 0
  private var size: Int = 0

  def add(message: String, optLocation: Option[Location]): Unit = {
    if (logger.isDebugEnabled) {
      logger.debug(
        "{}: {}",
        StringEscapeUtils.escapeJava(Pretty.prettyLoc(optLocation).renderWideStream.mkString),
        StringEscapeUtils.escapeJava(message),
      )
    }
    buffer(pos) = (message, optLocation)
    pos = (pos + 1) % capacity
    if (size < capacity)
      size += 1
  }

  def iterator: Iterator[(String, Option[Location])] =
    new RingIterator(if (size < capacity) 0 else pos, size, buffer)
}

private final class RingIterator[A](ringStart: Int, ringSize: Int, buffer: Array[A])
    extends Iterator[A] {
  private var pos: Int = ringStart
  private var first = true
  private def nextPos: Int = (pos + 1) % ringSize
  def hasNext: Boolean = ringSize != 0 && (first || pos != ringStart)
  def next(): A = {
    val x = buffer(pos)
    first = false
    pos = nextPos
    x
  }
}
