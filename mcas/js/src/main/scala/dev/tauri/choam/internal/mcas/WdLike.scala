/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.tauri.choam
package internal
package mcas

import scala.util.hashing.MurmurHash3

sealed abstract class WdLike[A] extends Hamt.HasKey[MemoryLocation[A]] {
  val address: MemoryLocation[A]
  val ov: A
  val nv: A
  val oldVersion: Long
  def wasFinalized(wasSuccessful: Boolean, sentinel: A): Unit

  final override def key: MemoryLocation[A] =
    this.address

  final override def isTomb: Boolean =
    false
}

final class LogEntry[A] private ( // formerly called HWD
  final override val address: MemoryLocation[A],
  final override val ov: A,
  final override val nv: A,
  final override val oldVersion: Long,
) extends WdLike[A] {

  require(VersionFunctions.isValid(oldVersion))

  private[choam] final def cast[B]: LogEntry[B] =
    this.asInstanceOf[LogEntry[B]]

  final override def wasFinalized(wasSuccessful: Boolean, sentinel: A): Unit = {
    if (wasSuccessful) {
      this.address.unsafeNotifyListeners()
    }
  }

  final def version: Long =
    this.oldVersion

  final def withNv(a: A): LogEntry[A] = {
    if (equ(this.nv, a)) this
    else new LogEntry[A](address = this.address, ov = this.ov, nv = a, oldVersion = this.oldVersion)
  }

  /**
   * Tries to revalidate `this` HWD based on the
   * _current_ version of its ref.
   *
   * @return true, iff `this` is still valid.
   */
  private[mcas] final def revalidate(ctx: Mcas.ThreadContext): Boolean = {
    val currVer: Long = ctx.readVersion(this.address)
    currVer == this.version
  }

  final def tryMergeTicket(ticket: LogEntry[A], newest: A): LogEntry[A] = {
    if (this == ticket) {
      // OK, was not modified since reading the ticket:
      this.withNv(newest)
    } else {
      throw new LogEntry.TicketInvalidException(ticket.address, newest)
    }
  }

  final def readOnly: Boolean =
    equ(this.ov, this.nv)

  final override def toString: String =
    s"LogEntry(${this.address}, ${this.ov}, ${this.nv}, version = ${version})"

  final override def equals(that: Any): Boolean = {
    that match {
      case that: LogEntry[_] =>
        (this eq that) || (
          (this.address == that.address) &&
          equ[Any](this.ov, that.ov) &&
          equ[Any](this.nv, that.nv) &&
          (this.version == that.version)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    val h1 = MurmurHash3.mix(0x4beb8a16, this.address.##)
    val h2 = MurmurHash3.mix(h1, System.identityHashCode(this.ov))
    val h3 = MurmurHash3.mix(h2, System.identityHashCode(this.nv))
    val h4 = MurmurHash3.mixLast(h3, this.version.##)
    MurmurHash3.finalizeHash(h4, 4)
  }
}

object LogEntry {

  final class TicketInvalidException(
    ref: MemoryLocation[_],
    nv: Any
  ) extends IllegalStateException(s"ticket invalid for ${ref}; cannot write new value ${nv}")

  private[mcas] def apply[A](
    address: MemoryLocation[A],
    ov: A,
    nv: A,
    version: Long,
  ): LogEntry[A] = {
    new LogEntry[A](address = address, ov = ov, nv = nv, oldVersion = version)
  }
}
