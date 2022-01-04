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

import java.util.ArrayList

private final class EMCASDescriptor private (
  /**
   * Word descriptors
   *
   * Thread safety: we only read the list after reading the descriptor from a `Ref`;
   * we only mutate the list before writing the descriptor to a `Ref`.
   */
  private val words: ArrayList[WordDescriptor[_]]
  // TODO: this could be a simple `Array`
) extends EMCASDescriptorBase { self =>

  /** Intrusive linked list of finalized descriptors (see `ThreadContext`) */
  private[mcas] var next: EMCASDescriptor =
    null

  private[mcas] def this(initialSize: Int) =
    this(new ArrayList[WordDescriptor[_]](initialSize))

  private[mcas] def add[A](word: WordDescriptor[A]): Unit = {
    this.words.add(word)
    ()
  }

  private[mcas] final def wordIterator(): java.util.Iterator[WordDescriptor[_]] = {
    new EMCASDescriptor.Iterator(this)
  }

  /** Only for testing */
  private[mcas] final def casStatus(ov: EMCASStatus, nv: EMCASStatus): Boolean = {
    this.casStatusInternal(ov, nv)
  }
}

private object EMCASDescriptor {

  def prepare(half: HalfEMCASDescriptor): EMCASDescriptor = {
    val emcasDesc = new EMCASDescriptor(initialSize = half.map.size)
    val it = half.map.valuesIterator
    while (it.hasNext) {
      val wd = WordDescriptor.prepare(it.next(), emcasDesc)
      emcasDesc.add(wd)
    }
    emcasDesc
  }

  // TODO: reformat (extra indent)
  private final class Iterator(desc: EMCASDescriptor) extends java.util.Iterator[WordDescriptor[_]] {

      private[this] var idx: Int =
        0

      private[this] var lastIdx: Int =
        -1

      final override def hasNext(): Boolean = {
        this.idx != desc.words.size()
      }

      final override def next(): WordDescriptor[_] = {
        if (this.hasNext()) {
          this.lastIdx = this.idx
          this.idx += 1
          desc.words.get(this.lastIdx)
        } else {
          throw new NoSuchElementException
        }
      }

      final override def remove(): Unit = {
        throw new UnsupportedOperationException
      }
  }
}
