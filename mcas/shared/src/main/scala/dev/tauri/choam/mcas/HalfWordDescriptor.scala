/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

private final class HalfWordDescriptor[A] private (
  val address: MemoryLocation[A],
  val ov: A,
  val nv: A,
) {

  // TODO: remove
  final def cast[B]: HalfWordDescriptor[B] =
    this.asInstanceOf[HalfWordDescriptor[B]]

  final override def toString: String =
    s"HalfWordDescriptor(${this.address}, ${this.ov}, ${this.nv})"
}

private object HalfWordDescriptor {

  def apply[A](address: MemoryLocation[A], ov: A, nv: A): HalfWordDescriptor[A] =
    new HalfWordDescriptor[A](address = address, ov = ov, nv = nv)
}