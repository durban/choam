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

private final class EMCASDescriptor private (
  half: HalfEMCASDescriptor,
  final val newVersion: Long,
) extends EMCASDescriptorBase { self =>

  // effectively immutable array:
  private[this] val words: Array[WordDescriptor[_]] = {
    val arr = new Array[WordDescriptor[_]](half.map.size)
    val it = half.map.valuesIterator
    var idx = 0
    while (it.hasNext) {
      val wd = WordDescriptor.prepare(it.next(), this)
      arr(idx) = wd
      idx += 1
    }
    arr
  }

  private[mcas] final def wordIterator(): java.util.Iterator[WordDescriptor[_]] = {
    new EMCASDescriptor.Iterator(this.words)
  }

  /** Only for testing */
  private[mcas] final def casStatus(ov: EMCASStatus, nv: EMCASStatus): Boolean = {
    this.casStatusInternal(ov, nv)
  }
}

private object EMCASDescriptor {

  def prepare(half: HalfEMCASDescriptor): EMCASDescriptor = {
    new EMCASDescriptor(half, newVersion = half.newVersion)
  }

  private final class Iterator(words: Array[WordDescriptor[_]])
    extends java.util.Iterator[WordDescriptor[_]] {

    private[this] var idx: Int =
      0

    private[this] var lastIdx: Int =
      -1

    final override def hasNext(): Boolean = {
      this.idx != this.words.length
    }

    final override def next(): WordDescriptor[_] = {
      if (this.hasNext()) {
        this.lastIdx = this.idx
        this.idx += 1
        this.words(this.lastIdx)
      } else {
        throw new NoSuchElementException
      }
    }

    final override def remove(): Unit = {
      throw new UnsupportedOperationException("EMCASDescriptor.Iterator#remove")
    }
  }
}
