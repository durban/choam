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

import scala.collection.immutable.TreeMap

import mcas.MemoryLocation

final class HalfEMCASDescriptor private (
  private[kcas] val map: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]]
) {

  private[kcas] final def nonEmpty: Boolean =
    this.map.nonEmpty

  private[kcas] final def add[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    val d = desc.cast[Any]
    if (this.map.contains(d.address)) {
      val other = this.map.get(d.address).get
      KCAS.impossibleKCAS(d.address, other, desc)
    } else {
      val newMap = this.map.updated(d.address, d)
      new HalfEMCASDescriptor(newMap)
    }
  }

  private[kcas] final def prepare(ctx: ThreadContext): EMCASDescriptor = {
    val emcasDesc = new EMCASDescriptor(initialSize = this.map.size)
    val it = this.map.valuesIterator
    while (it.hasNext) {
      emcasDesc.add(it.next().prepare(emcasDesc, ctx))
    }
    emcasDesc
  }
}

object HalfEMCASDescriptor {

  private[this] val _empty = {
    new HalfEMCASDescriptor(TreeMap.empty(
      MemoryLocation.orderingInstance[Any]
    ))
  }

  def empty: HalfEMCASDescriptor =
    _empty
}

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
  private[kcas] var next: EMCASDescriptor =
    null

  private[kcas] def this(initialSize: Int) =
    this(new ArrayList[WordDescriptor[_]](initialSize))

  private[kcas] def add[A](word: WordDescriptor[A]): Unit = {
    this.words.add(word)
    ()
  }

  private[kcas] def addAll(that: EMCASDescriptor): Unit = {
    this.words.addAll(that.words)
    ()
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

private object EMCASDescriptor {

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
