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

import scala.collection.immutable.TreeMap

final class HalfEMCASDescriptor private (
  private[mcas] val map: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]]
) {

  private[mcas] final def nonEmpty: Boolean =
    this.map.nonEmpty

  private[mcas] final def add[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    val d = desc.cast[Any]
    if (this.map.contains(d.address)) {
      val other = this.map.get(d.address).get
      MCAS.impossibleOp(d.address, other, desc)
    } else {
      val newMap = this.map.updated(d.address, d)
      new HalfEMCASDescriptor(newMap)
    }
  }

  final override def toString: String =
    s"HalfEMCASDescriptor(${map.valuesIterator.mkString(", ")})"
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
