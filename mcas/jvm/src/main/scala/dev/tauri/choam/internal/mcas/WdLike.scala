/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

sealed abstract class WdLike[A] {
  val address: MemoryLocation[A]
  val ov: A
  val nv: A
  val oldVersion: Long
}

// TODO: this is duplicated on JS
final class LogEntry[A] private ( // formerly called HWD
  final override val address: MemoryLocation[A],
  final override val ov: A,
  final override val nv: A,
  final override val oldVersion: Long,
) extends WdLike[A] {

  require(Version.isValid(version))

  private[mcas] final def cast[B]: LogEntry[B] =
    this.asInstanceOf[LogEntry[B]]

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
      throw new IllegalArgumentException // TODO
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

private object LogEntry {

  private[mcas] def apply[A](
    address: MemoryLocation[A],
    ov: A,
    nv: A,
    version: Long,
  ): LogEntry[A] = {
    new LogEntry[A](address = address, ov = ov, nv = nv, oldVersion = version)
  }
}

package emcas {

  // Ideally this should be in the `emcas` folder; it's here, so that `WdLike` can be `sealed`.

  // NB: It is important, that this is private;
  // we're writing `EmcasWordDesc[A]`s into
  // `Ref[A]`s, and we need to be able to distinguish
  // them from user data. If the user data could
  // be an instance of `EmcasWordDesc`, we could
  // not do that.
  private[mcas] final class EmcasWordDesc[A] private (
    final val parent: emcas.EmcasDescriptor,
    final override val address: MemoryLocation[A],
    final override val ov: A,
    final override val nv: A,
    final override val oldVersion: Long,
  ) extends WdLike[A] {

    // TODO: Technically we could clear `ov` for a successful op
    // TODO: (and similarly `nv` for a failed); we'd need to
    // TODO: make them non-final, so it's unclear if it's worth
    // TODO: it. (Although, currently this is a little leak.)

    // TODO: But: we'd need to use a custom sentinel instead of
    // TODO: `null`, because `null` is a valid ov/nv, and helpers
    // TODO: need to be able to differentiate reading a valid
    // TODO: ov/nv from a racy read of a finalized descriptor.

    // TODO: In theory, `address` could be cleared too.
    // TODO: But that's probably not worth it.
    // TODO: (A lot of extra checks would need to be introduced.)

    def this(
      half: LogEntry[A],
      parent: emcas.EmcasDescriptor,
    ) = this(
      parent = parent,
      address = half.address,
      ov = half.ov,
      nv = half.nv,
      oldVersion = half.version,
    )

    final def readOnly: Boolean =
      equ(this.ov, this.nv)

    final def withParent(newParent: emcas.EmcasDescriptor): EmcasWordDesc[A] = {
      new EmcasWordDesc[A](
        parent = newParent,
        address = this.address,
        ov = this.ov,
        nv = this.nv,
        oldVersion = this.oldVersion,
      )
    }

    final def cast[B]: EmcasWordDesc[B] =
      this.asInstanceOf[EmcasWordDesc[B]]

    final def castToData: A =
      this.asInstanceOf[A]

    final override def toString: String =
      s"EmcasWordDesc(${this.address}, ${this.ov} -> ${this.nv}, oldVer = ${this.oldVersion})"
  }

  private object EmcasWordDesc {
    private[mcas] val Invalid: EmcasWordDesc[_] = new EmcasWordDesc[AnyRef](
      parent = null,
      address = null,
      ov = null,
      nv = null,
      oldVersion = Version.None,
    )
  }
}
