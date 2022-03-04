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

import java.util.concurrent.atomic.AtomicLong

private final class EmcasDescriptor private (
  private val half: HalfEMCASDescriptor,
) extends EmcasDescriptorBase { self =>

  // TODO: do not store `commitVer` and `status` separately
  private[this] val commitVer: AtomicLong =
    new AtomicLong(Version.None)

  final def cmpxchgCommitVer(nv: Long): Long = {
    require(Version.isValid(nv))
    this.commitVer.compareAndExchange(Version.None, nv)
  }

  final def getCommitVer(): Long = {
    val r = this.commitVer.get()
    assert(Version.isValid(r))
    r
  }

  // effectively immutable array:
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

  private[mcas] final def wordIterator(): java.util.Iterator[WordDescriptor[_]] = {
    new EmcasDescriptor.Iterator(this.words)
  }

  private[mcas] final def casStatus(ov: Long, nv: Long): Boolean = {
    this.casStatusInternal(ov, nv)
  }

  private[mcas] final def hasVersionCas: Boolean = {
    this.half.hasVersionCas
  }

  /** Only for informational purposes, not actually used */
  private[mcas] final def expectedNewVersion: Long = {
    this.half.newVersion
  }

  final override def toString: String = {
    s"EMCASDescriptor(${half})"
  }
}

private object EmcasDescriptor {

  def prepare(half: HalfEMCASDescriptor): EmcasDescriptor = {
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
