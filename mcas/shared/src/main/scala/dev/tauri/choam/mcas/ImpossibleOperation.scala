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

final class ImpossibleOperation private[mcas] (
  private val ref: MemoryLocation[_],
  private val a: HalfWordDescriptor[_],
  private val b: HalfWordDescriptor[_],
) extends IllegalArgumentException(
  s"Impossible k-CAS for ${ref}: ${a.ov} -> ${a.nv} and ${b.ov} -> ${b.nv}"
) {

  assert(a.address eq ref)
  assert(b.address eq ref)

  final override def fillInStackTrace(): Throwable =
    this

  /** Usually we want reference equality; except for law testing. */
  private[choam] final def equiv(that: ImpossibleOperation): Boolean = {
    def equu[A](a1: A, a2: A): Boolean =
      a1 == a2 // universal equals
    (
      equ(this.ref, that.ref) &&
      equu(this.a.ov, that.a.ov) &&
      equu(this.a.nv, that.a.nv) &&
      equu(this.b.ov, that.b.ov) &&
      equu(this.b.nv, that.b.nv)
    )
  }
}
