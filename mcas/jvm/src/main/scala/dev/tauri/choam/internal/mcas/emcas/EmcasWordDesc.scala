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
package emcas

// NB: It is important, that this is private;
// we're writing `EmcasWordDesc[A]`s into
// `Ref[A]`s, and we need to be able to distinguish
// them from user data. If the user data could
// be an instance of `EmcasWordDesc`, we could
// not do that.
private[mcas] final class EmcasWordDesc[A] private (
  final val parent: EmcasDescriptor,
  final override val address: MemoryLocation[A],
  final override val ov: A,
  final override val nv: A,
  final override val oldVersion: Long,
) extends WdLike.UnsealedWdLike[A] {

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
    parent: EmcasDescriptor,
  ) = this(
    parent = parent,
    address = half.address,
    ov = half.ov,
    nv = half.nv,
    oldVersion = half.version,
  )

  final def readOnly: Boolean =
    equ(this.ov, this.nv)

  final def withParent(newParent: EmcasDescriptor): EmcasWordDesc[A] = {
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
  private[emcas] val Invalid: EmcasWordDesc[_] = new EmcasWordDesc[AnyRef](
    parent = null,
    address = null,
    ov = null,
    nv = null,
    oldVersion = Version.None,
  )
}
