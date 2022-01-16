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

// TODO: this really should have a better name
final class HalfEMCASDescriptor private (
  private[mcas] val map: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]],
  val validTs: Long,
  val readOnly: Boolean,
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
      new HalfEMCASDescriptor(
        newMap,
        this.validTs,
        this.readOnly && desc.readOnly,
      )
    }
  }

  private[mcas] final def overwrite[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    val d = desc.cast[Any]
    require(this.map.contains(d.address))
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTs,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
    )
  }

  private[mcas] final def getOrElseNull[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
    val r = ref.asInstanceOf[MemoryLocation[Any]]
    this.map.getOrElse(r, null) match {
      case null => null
      case hwd => hwd.cast[A]
    }
  }

  private[mcas] final def extendValidTs(newValidTs: Long): HalfEMCASDescriptor = {
    require(newValidTs >= this.validTs)
    new HalfEMCASDescriptor(
      map = this.map,
      validTs = newValidTs,
      readOnly = this.readOnly,
    )
  }

  final override def toString: String =
    s"HalfEMCASDescriptor(${map.valuesIterator.mkString(", ")})"
}

object HalfEMCASDescriptor {

  def empty(ts: Long): HalfEMCASDescriptor = {
    new HalfEMCASDescriptor(
      TreeMap.empty(MemoryLocation.orderingInstance[Any]),
      validTs = ts,
      readOnly = true,
    )
  }
}
