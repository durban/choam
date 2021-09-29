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
package kcas

import java.util.Comparator

import mcas.MemoryLocation

final class WordDescriptor[A] private (
  val address: MemoryLocation[A],
  val ov: A,
  val nv: A,
  val parent: EMCASDescriptor
) extends IBRManaged[ThreadContext, WordDescriptor[A]] {

  final def withParent(newParent: EMCASDescriptor): WordDescriptor[A] =
    WordDescriptor[A](this.address, this.ov, this.nv, newParent)

  final def prepare(ctx: ThreadContext): Unit = {
    ctx.alloc(this)
  }

  final def cast[B]: WordDescriptor[B] =
    this.asInstanceOf[WordDescriptor[B]]

  final def castToData: A =
    this.asInstanceOf[A]

  final override def toString: String =
    s"WordDescriptor(${this.address}, ${this.ov}, ${this.nv})"
}

object WordDescriptor {

  def apply[A](
    address: MemoryLocation[A],
    ov: A,
    nv: A,
    parent: EMCASDescriptor,
  ): WordDescriptor[A] = {
    new WordDescriptor[A](address, ov, nv, parent)
  }

  val comparator: Comparator[WordDescriptor[_]] = new Comparator[WordDescriptor[_]] {
    final override def compare(x: WordDescriptor[_], y: WordDescriptor[_]): Int = {
      // NB: `x ne y` is always true, because we create fresh descriptors in `withCAS`
      val res = MemoryLocation.globalCompare(x.address, y.address)
      if (res == 0) {
        assert(x.address eq y.address)
        KCAS.impossibleKCAS(x.address, x.ov, x.nv, y.ov, y.nv)
      } else {
        res
      }
    }
  }
}
