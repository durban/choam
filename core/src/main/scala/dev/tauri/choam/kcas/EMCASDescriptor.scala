/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

final class EMCASDescriptor(val impl: KCAS, wds: ArrayList[WordDescriptor[_]])
  extends EMCASDescriptorBase {

  /**
   * Word descriptors
   *
   * Thread safety: we only read the list after reading the descriptor from a `Ref`;
   * we only mutate the list before writing the descriptor to a `Ref`.
   */
  val words: ArrayList[WordDescriptor[_]] =
    wds

  def this(kcasImpl: KCAS) = {
    this(kcasImpl, new ArrayList(EMCASDescriptor.minArraySize))
  }

  def copy(addHolder: Boolean): EMCASDescriptor = {
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
        val newWd = if (addHolder) {
          oldWd.withParent(newParent)
        } else {
          oldWd.withParentNoHolder(newParent)
        }
        to.add(newWd)
        copy(from, to, newParent, idx + 1, len)
      }
    }
    val newArrCapacity = Math.max(this.words.size(), EMCASDescriptor.minArraySize)
    val newArr = new ArrayList[WordDescriptor[_]](newArrCapacity)
    val r = new EMCASDescriptor(this.impl, newArr)
    copy(this.words, newArr, r, 0, this.words.size())
    r
  }

  def sort(): Unit = {
    this.words.sort(WordDescriptor.comparator)
  }
}

final object EMCASDescriptor {
  // TODO: should always be inlined
  final val minArraySize = 8
}
