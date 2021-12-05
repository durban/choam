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

  final def withParent(parent: EMCASDescriptor): WordDescriptor[A] =
    WordDescriptor[A](this, parent)

  final def prepare(parent: EMCASDescriptor, ctx: ThreadContext): WordDescriptor[A] = {
    val wd = this.withParent(parent)
    ctx.alloc(wd)
    wd
  }

  final def cast[B]: HalfWordDescriptor[B] =
    this.asInstanceOf[HalfWordDescriptor[B]]

  final override def toString: String =
    s"HalfWordDescriptor(${this.address}, ${this.ov}, ${this.nv})"
}

private object HalfWordDescriptor {

  def apply[A](address: MemoryLocation[A], ov: A, nv: A): HalfWordDescriptor[A] =
    new HalfWordDescriptor[A](address = address, ov = ov, nv = nv)
}

private final class WordDescriptor[A] private (
  val half: HalfWordDescriptor[A],
  val parent: EMCASDescriptor,
) extends IBRManaged[ThreadContext, WordDescriptor[A]] {

  def address: MemoryLocation[A] =
    this.half.address

  def ov: A =
    this.half.ov

  def nv: A =
    this.half.nv

  final def cast[B]: WordDescriptor[B] =
    this.asInstanceOf[WordDescriptor[B]]

  final def castToData: A =
    this.asInstanceOf[A]

  final override def toString: String =
    s"WordDescriptor(${this.address}, ${this.ov}, ${this.nv})"
}

private object WordDescriptor {

  def apply[A](
    half: HalfWordDescriptor[A],
    parent: EMCASDescriptor,
  ): WordDescriptor[A] = {
    new WordDescriptor[A](half, parent)
  }
}
