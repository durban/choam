/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

// NB: It is important, that this is private;
// we're writing `WordDescriptor[A]`s into
// `Ref[A]`s, and we need to be able to distinguish
// them from user data. If the user data could
// be an instance of `WordDescriptor`, we could
// not do that.
private final class WordDescriptor[A] private ( // TODO: rename to EmcasWordDescriptor
  final val half: HalfWordDescriptor[A],
  final val parent: EmcasDescriptor,
) {

  final def address: MemoryLocation[A] =
    this.half.address

  final def ov: A =
    this.half.ov

  final def nv: A =
    this.half.nv

  final def oldVersion: Long =
    this.half.version

  // TODO: when we call this, usually
  // TODO: we already know the status,
  // TODO: so this volatile read could
  // TODO: be avoided
  final def newVersion: Long = {
    val cv = this.parent.getStatus()
    assert(EmcasStatus.isSuccessful(cv))
    cv
  }

  final def cast[B]: WordDescriptor[B] =
    this.asInstanceOf[WordDescriptor[B]]

  final def castToData: A =
    this.asInstanceOf[A]

  final override def toString: String =
    s"WordDescriptor(${this.address}, ${this.ov} -> ${this.nv}, oldVer = ${this.oldVersion})"
}

private object WordDescriptor {

  private[mcas] def apply[A](
    half: HalfWordDescriptor[A],
    parent: EmcasDescriptor,
  ): WordDescriptor[A] = new WordDescriptor[A](half, parent)

  private[mcas] def prepare[A](
    half: HalfWordDescriptor[A],
    parent: EmcasDescriptor,
  ): WordDescriptor[A] = WordDescriptor(half, parent)
}
