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

// TODO: this really should have a better name
final class HalfWordDescriptor[A] private (
  val address: MemoryLocation[A],
  val ov: A,
  val nv: A,
  val version: Long,
) {

  require(Version.isValid(version))

  private[mcas] final def cast[B]: HalfWordDescriptor[B] =
    this.asInstanceOf[HalfWordDescriptor[B]]

  final def withNv(a: A): HalfWordDescriptor[A] = {
    if (equ(this.nv, a)) this
    else new HalfWordDescriptor[A](address = this.address, ov = this.ov, nv = a, version = this.version)
  }

  final def tryMergeTicket(ticket: HalfWordDescriptor[A], newest: A): HalfWordDescriptor[A] = {
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
    s"HalfWordDescriptor(${this.address}, ${this.ov}, ${this.nv}, version = ${version})"

  final override def equals(that: Any): Boolean = {
    that match {
      case that: HalfWordDescriptor[_] =>
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
    val h4 = MurmurHash3.mix(h3, this.version.toInt)
    val h5 = MurmurHash3.mixLast(h4, (this.version >>> 32).toInt)
    MurmurHash3.finalizeHash(h5, 5)
  }
}

private object HalfWordDescriptor {

  private[mcas] def apply[A](
    address: MemoryLocation[A],
    ov: A,
    nv: A,
    version: Long,
  ): HalfWordDescriptor[A] = {
    new HalfWordDescriptor[A](address = address, ov = ov, nv = nv, version = version)
  }
}
