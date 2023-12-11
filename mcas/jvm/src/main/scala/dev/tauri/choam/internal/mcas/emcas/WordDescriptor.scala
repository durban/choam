/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
// we're writing `WordDescriptor[A]`s into
// `Ref[A]`s, and we need to be able to distinguish
// them from user data. If the user data could
// be an instance of `WordDescriptor`, we could
// not do that.
private final class WordDescriptor[A] private ( // TODO: rename to EmcasWordDescriptor
  final val parent: EmcasDescriptor,
  final val address: MemoryLocation[A],
  final val ov: A,
  final val nv: A,
  final val oldVersion: Long,
) {

  final def cast[B]: WordDescriptor[B] =
    this.asInstanceOf[WordDescriptor[B]]

  final def castToData: A =
    this.asInstanceOf[A]

  final override def toString: String =
    s"WordDescriptor(${this.address}, ${this.ov} -> ${this.nv}, oldVer = ${this.oldVersion})"
}

private object WordDescriptor {

  private[emcas] def apply[A](
    half: HalfWordDescriptor[A],
    parent: EmcasDescriptor,
  ): WordDescriptor[A] = new WordDescriptor[A](
    parent = parent,
    address = half.address,
    ov = half.ov,
    nv = half.nv,
    oldVersion = half.version,
  )

  private[emcas] def prepare[A](
    half: HalfWordDescriptor[A],
    parent: EmcasDescriptor,
  ): WordDescriptor[A] = WordDescriptor(half, parent)
}
