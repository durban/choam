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
package mcas
package emcas

private final class EmcasDescriptor private (
  private val half: Descriptor,
) extends EmcasDescriptorBase { self =>

  /**
   * While the status is `Active`, this array
   * is never mutated. After the op is finalized,
   * it may be cleared (to help GC), so helpers
   * must be prepared to handle `null`s. (There
   * is no need to help an op which is finalized,
   * so this is not a problem.)
   */
  private[this] val words: Array[WordDescriptor[_]] = {
    val arr = new Array[WordDescriptor[_]](half.size)
    val it = half.iterator()
    var idx = 0
    while (it.hasNext) {
      val wd = WordDescriptor.prepare(it.next(), this)
      arr(idx) = wd
      idx += 1
    }
    arr
  }

  final def size: Int =
    this.words.length

  private[emcas] final def wordIterator(): java.util.Iterator[WordDescriptor[_]] = {
    new EmcasDescriptor.Iterator(this.words)
  }

  private[emcas] final def casStatus(ov: Long, nv: Long): Boolean = {
    this.casStatusInternal(ov, nv)
  }

  private[emcas] final def hasVersionCas: Boolean = {
    this.half.hasVersionCas
  }

  /** Only for informational purposes, not actually used */
  private[emcas] final def expectedNewVersion: Long = {
    this.half.newVersion
  }

  private[emcas] final def wasFinalized(): Unit = {
    // help the GC (best effort,
    // so just plain writes):
    var idx = 0
    while (idx < this.size) {
      this.words(idx) = null
      idx += 1
    }
  }

  final override def toString: String = {
    s"EMCASDescriptor(${half})"
  }
}

private object EmcasDescriptor {

  def prepare(half: Descriptor): EmcasDescriptor = {
    new EmcasDescriptor(half)
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
