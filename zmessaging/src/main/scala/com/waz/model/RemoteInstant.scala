/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.model

import java.util.Date
import org.threeten.bp
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit

import scala.concurrent.duration.FiniteDuration

trait WireInstant {

  val instant: Instant

  def javaDate: Date = new Date(instant.toEpochMilli)
  def -(d: FiniteDuration): Instant = instant.minusNanos(d.toNanos)
  def -(d: bp.Duration): Instant = instant.minusNanos(d.toNanos)
  def +(d: FiniteDuration): Instant = instant.plusNanos(d.toNanos)
  def +(d: bp.Duration): Instant = instant.plusNanos(d.toNanos)
  def toEpochMilli: Long = instant.toEpochMilli

  def isEpoch: Boolean = instant == Instant.EPOCH
}

case class RemoteInstant(instant: Instant) extends WireInstant
object RemoteInstant {
  def Epoch = RemoteInstant(Instant.EPOCH)
  def Max = RemoteInstant(Instant.MAX)
  def ofEpochMilli(epochMilli: Long) = RemoteInstant(Instant.ofEpochMilli(epochMilli))
}

case class LocalInstant(instant: Instant) extends WireInstant {
  def isToday: Boolean = instant.truncatedTo(ChronoUnit.DAYS) == Instant.now.truncatedTo(ChronoUnit.DAYS)
}

object LocalInstant {
  def Epoch = LocalInstant(Instant.EPOCH)
  def Max = LocalInstant(Instant.MAX)
  def Now = LocalInstant(Instant.now())
  def ofEpochMilli(epochMilli: Long) = LocalInstant(Instant.ofEpochMilli(epochMilli))
}
