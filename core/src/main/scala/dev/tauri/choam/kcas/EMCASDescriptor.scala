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

import java.util.ArrayList

final class EMCASDescriptor private (
  /**
   * Word descriptors
   *
   * Thread safety: we only read the list after reading the descriptor from a `Ref`;
   * we only mutate the list before writing the descriptor to a `Ref`.
   */
  private val words: ArrayList[WordDescriptor[_]]
) extends EMCASDescriptorBase { self =>

  /** Intrusive linked list of finalized descriptors (see `ThreadContext`) */
  private[kcas] var next: EMCASDescriptor =
    null

  private[kcas] def this() =
    this(new ArrayList(EMCASDescriptor.minArraySize))

  private[kcas] def copy(): EMCASDescriptor = {
    @tailrec
    def copy(
      from: ArrayList[WordDescriptor[_]],
      to: ArrayList[WordDescriptor[_]],
      newParent: EMCASDescriptor,
      idx: Int,
      len: Int
    ): Unit = {
      if (idx < len) {
        val oldWd = from.get(idx)
        val newWd = oldWd.withParent(newParent)
        to.add(newWd)
        copy(from, to, newParent, idx + 1, len)
      }
    }
    val newArrCapacity = Math.max(this.words.size(), EMCASDescriptor.minArraySize)
    val newArr = new ArrayList[WordDescriptor[_]](newArrCapacity)
    val r = new EMCASDescriptor(newArr)
    copy(this.words, newArr, r, 0, this.words.size())
    r
  }

  private[kcas] def add[A](word: WordDescriptor[A]): Unit = {
    this.words.add(word)
    ()
  }

  private[kcas] def addAll(that: EMCASDescriptor): Unit = {
    this.words.addAll(that.words)
    ()
  }

  private[kcas] def prepare(ctx: ThreadContext): Unit = {
    this.sort()
    val it = this.wordIterator()
    while (it.hasNext()) {
      it.next().prepare(ctx)
    }
  }

  private[this] def sort(): Unit = {
    this.words.sort(WordDescriptor.comparator)
  }

  private[kcas] final def wordIterator(): java.util.Iterator[WordDescriptor[_]] = {
    new EMCASDescriptor.Iterator(this)
  }

  /** Only for testing */
  private[kcas] def setStatusSuccessful(): Unit = {
    this.setStatus(EMCASStatus.SUCCESSFUL)
  }

  /** Only for testing */
  private[kcas] def setStatusFailed(): Unit = {
    this.setStatus(EMCASStatus.FAILED)
  }
}

object EMCASDescriptor {

  // TODO: should always be inlined
  private final val minArraySize = 8

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
        if (this.lastIdx >= 0) {
          desc.words.set(this.lastIdx, null)
          this.lastIdx = -1
        } else {
          throw new IllegalStateException
        }
      }
  }
}
