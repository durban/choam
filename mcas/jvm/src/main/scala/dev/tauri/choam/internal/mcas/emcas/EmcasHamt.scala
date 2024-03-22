/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package internal
package mcas
package emcas

object EmcasHamt {

  private final class MemLocNode[A](
    _size: Int,
    _bitmap: Long,
    _contents: Array[AnyRef],
  ) extends Hamt.Node[HalfWordDescriptor[A], WordDescriptor[A], MemLocNode[A]](_size, _bitmap, _contents) {

    protected final override def hashOf(a: HalfWordDescriptor[A]): Long =
      a.address.id

    protected final override def newNode(size: Int, bitmap: Long, contents: Array[AnyRef]): MemLocNode[A] =
      new MemLocNode(size, bitmap, contents)

    protected final override def convertForArray(a: HalfWordDescriptor[A]): WordDescriptor[A] = {
      // TODO: we need access to the `EmcasDescriptor` here
      new WordDescriptor(a, sys.error("TODO") : EmcasDescriptor)
    }
  }
}
