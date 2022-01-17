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
import scala.util.hashing.MurmurHash3

// TODO: this really should have a better name
final class HalfEMCASDescriptor private (
  private[mcas] val map: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]],
  private val validTsBoxed: java.lang.Long,
  val readOnly: Boolean,
) {

  final def validTs: Long =
    this.validTsBoxed // unboxing happens

  private[mcas] final def newVersion: Long =
    this.validTs + Version.Incr

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
        this.validTsBoxed,
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
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
    )
  }

  private[mcas] final def addVersionCas(commitTsRef: MemoryLocation[Long]): HalfEMCASDescriptor = {
    val hwd = HalfWordDescriptor[java.lang.Long](
      commitTsRef.asInstanceOf[MemoryLocation[java.lang.Long]],
      ov = this.validTsBoxed, // no boxing
      nv = this.newVersion, // boxing happens
      version = Version.Start, // the version's version is unused/arbitrary
    )
    this.add(hwd)
  }

  private[mcas] final def getOrElseNull[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
    val r = ref.asInstanceOf[MemoryLocation[Any]]
    this.map.getOrElse(r, null) match {
      case null => null
      case hwd => hwd.cast[A]
    }
  }

  private[mcas] final def validateAndTryExtend(
    commitTsRef: MemoryLocation[Long],
    ctx: MCAS.ThreadContext,
  ): HalfEMCASDescriptor = {
    val newValidTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    require(newValidTsBoxed >= this.validTs)
    if (ctx.validate(this)) {
      new HalfEMCASDescriptor(
        map = this.map,
        validTsBoxed = newValidTsBoxed,
        readOnly = this.readOnly,
      )
    } else {
      null
    }
  }

  final override def toString: String = {
    val m = map.valuesIterator.mkString("[", ", ", "]")
    s"HalfEMCASDescriptor(${m}, validTs = ${validTs}, readOnly = ${readOnly})"
  }

  final override def equals(that: Any): Boolean = {
    that match {
      case that: HalfEMCASDescriptor =>
        (this eq that) || (
          (this.readOnly == that.readOnly) &&
          (this.validTsBoxed eq that.validTsBoxed) &&
          (this.map == that.map)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    val h1 = MurmurHash3.mix(0xefebde66, System.identityHashCode(this.validTsBoxed))
    val h2 = MurmurHash3.mix(h1, this.readOnly.##)
    val h3 = MurmurHash3.mix(h2, this.map.##)
    MurmurHash3.finalizeHash(h3, this.map.size)
  }
}

object HalfEMCASDescriptor {

  private[mcas] def empty(commitTsRef: MemoryLocation[Long], ctx: MCAS.ThreadContext): HalfEMCASDescriptor = {
    val validTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    new HalfEMCASDescriptor(
      TreeMap.empty(MemoryLocation.orderingInstance[Any]),
      validTsBoxed = validTsBoxed,
      readOnly = true,
    )
  }
}
