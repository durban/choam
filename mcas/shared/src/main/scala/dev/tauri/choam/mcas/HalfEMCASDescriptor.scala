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

import scala.collection.AbstractIterator
import scala.util.hashing.MurmurHash3

// TODO: this really should have a better name
final class HalfEMCASDescriptor private (
  private val map: LogMap,
  private val validTsBoxed: java.lang.Long,
  val readOnly: Boolean,
  private val versionIncr: Long,
  private val versionCas: HalfWordDescriptor[java.lang.Long], // can be null
) {

  require((versionCas eq null) || (versionIncr > 0L))

  final def validTs: Long =
    this.validTsBoxed.longValue()

  final def size: Int =
    this.map.size + (if (this.versionCas ne null) 1 else 0)

  final def iterator(): Iterator[HalfWordDescriptor[_]] = {
    if (this.versionCas eq null) {
      this.map.valuesIterator
    } else {
      val underlying = this.map.valuesIterator
      val vc = this.versionCas
      new AbstractIterator[HalfWordDescriptor[_]] {
        private[this] var vcDone: Boolean = false
        final override def hasNext: Boolean = {
          (!vcDone) || underlying.hasNext
        }
        final override def next(): HalfWordDescriptor[_] = {
          if (!vcDone) {
            vcDone = true
            vc
          } else if (underlying.hasNext) {
            underlying.next()
          } else {
            throw new NoSuchElementException
          }
        }
      }
    }
  }

  final def isValidHwd[A](hwd: HalfWordDescriptor[A]): Boolean = {
    hwd.version <= this.validTs
  }

  /** True iff `this` can (theoretically) commit with the same version as `that` */
  private[mcas] final def canShareVersionWith(that: HalfEMCASDescriptor): Boolean = {
    if (this.hasVersionCas) {
      assert(this.map.nonEmpty)
      (!this.readOnly) &&
      (!that.readOnly) &&
      (this.validTsBoxed eq that.validTsBoxed) &&
      (this.versionIncr == that.versionIncr) &&
      this.map.isDisjoint(that.map)
    } else {
      false
    }
  }

  private[mcas] final def hasVersionCas: Boolean = {
    this.versionCas ne null
  }

  private final def withValidTsBoxed(newBoxed: java.lang.Long): HalfEMCASDescriptor =  {
    new HalfEMCASDescriptor(
      map = this.map,
      validTsBoxed = newBoxed,
      readOnly = this.readOnly,
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[mcas] final def withNoNewVersion: HalfEMCASDescriptor = {
    require(this.versionCas eq null)
    new HalfEMCASDescriptor(
      map = this.map,
      validTsBoxed = this.validTsBoxed,
      readOnly = this.readOnly,
      versionIncr = 0L,
      versionCas = null,
    )
  }

  private[mcas] final def newVersion: Long =
    this.validTs + this.versionIncr

  private[mcas] final def nonEmpty: Boolean =
    this.map.nonEmpty

  private[choam] final def add[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    val d = desc.cast[Any]
    // Note, that it is important, that we don't allow
    // adding an already included ref; the Exchanger
    // depends on this behavior:
    require(!this.map.contains(d.address))
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTsBoxed,
      this.readOnly && desc.readOnly,
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[choam] final def overwrite[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    require(desc.version <= this.validTs)
    val d = desc.cast[Any]
    require(this.map.contains(d.address))
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[choam] final def addOrOverwrite[A](desc: HalfWordDescriptor[A]): HalfEMCASDescriptor = {
    require(desc.version <= this.validTs)
    val d = desc.cast[Any]
    val newMap = this.map.updated(d.address, d)
    new HalfEMCASDescriptor(
      newMap,
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[mcas] final def addVersionCas(commitTsRef: MemoryLocation[Long]): HalfEMCASDescriptor = {
    require(this.versionCas eq null)
    require(!this.readOnly)
    require(this.versionIncr > 0L)
    val hwd = HalfWordDescriptor[java.lang.Long](
      commitTsRef.asInstanceOf[MemoryLocation[java.lang.Long]],
      ov = this.validTsBoxed, // no boxing here
      nv = java.lang.Long.valueOf(this.newVersion),
      version = Version.Start, // the version's version is unused/arbitrary
    )
    new HalfEMCASDescriptor(
      map = this.map,
      validTsBoxed = this.validTsBoxed,
      readOnly = false,
      versionIncr = this.versionIncr,
      versionCas = hwd,
    )
  }

  private[choam] final def getOrElseNull[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
    val r = ref.asInstanceOf[MemoryLocation[Any]]
    this.map.getOrElse(r, null) match {
      case null => null
      case hwd => hwd.cast[A]
    }
  }

  private[mcas] final def validateAndTryExtend(
    commitTsRef: MemoryLocation[Long],
    ctx: Mcas.ThreadContext,
    additionalHwd: HalfWordDescriptor[_], // can be null
  ): HalfEMCASDescriptor = {
    require(this.versionCas eq null)
    // NB: we must read the commitTs *before* the `ctx.validate...`
    val newValidTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    this.validateAndTryExtendInternal(newValidTsBoxed, ctx, additionalHwd)
  }

  private[mcas] final def validateAndTryExtendVer(
    currentTs: Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: HalfWordDescriptor[_], // can be null
  ): HalfEMCASDescriptor = {
    this.validateAndTryExtendInternal(
      java.lang.Long.valueOf(currentTs),
      ctx,
      additionalHwd,
    )
  }

  private[this] final def validateAndTryExtendInternal(
    newValidTsBoxed: java.lang.Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: HalfWordDescriptor[_], // can be null
  ): HalfEMCASDescriptor = {
    if (newValidTsBoxed.longValue > this.validTs) {
      if (
        ctx.validate(this) &&
        ((additionalHwd eq null) || ctx.validateHwd(additionalHwd))
      ) {
        assert((additionalHwd eq null) || (additionalHwd.version <= newValidTsBoxed.longValue))
        new HalfEMCASDescriptor(
          map = this.map,
          validTsBoxed = newValidTsBoxed,
          readOnly = this.readOnly,
          versionIncr = this.versionIncr,
          versionCas = this.versionCas,
        )
      } else {
        null
      }
    } else {
      // no need to validate:
      this
    }
  }

  final override def toString: String = {
    val m = map.valuesIterator.mkString("[", ", ", "]")
    val vi = if (versionIncr == HalfEMCASDescriptor.DefaultVersionIncr) {
      ""
    } else {
      s", versionIncr = ${versionIncr}"
    }
    val vc = if (versionCas eq null) {
      ""
    } else {
      s", versionCas = ${versionCas}"
    }
    s"HalfEMCASDescriptor(${m}, validTs = ${validTs}, readOnly = ${readOnly}${vi}${vc})"
  }

  final override def equals(that: Any): Boolean = {
    that match {
      case that: HalfEMCASDescriptor =>
        (this eq that) || (
          (this.readOnly == that.readOnly) &&
          (this.versionCas == that.versionCas) && {
            // TODO: we only compare `validTsBoxed` identity,
            // TODO: if we already have a version-CAS
            if (this.hasVersionCas) {
              this.validTsBoxed eq that.validTsBoxed
            } else {
              this.validTsBoxed.longValue == that.validTsBoxed.longValue
            }
          } &&
          (this.versionIncr == that.versionIncr) &&
          (this.map == that.map)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    val h1 = MurmurHash3.mix(0xefebde66, this.validTsBoxed.longValue.##)
    val h2 = MurmurHash3.mix(h1, this.readOnly.##)
    val h3 = MurmurHash3.mix(h2, this.versionIncr.##)
    val h4 = MurmurHash3.mix(h3, this.versionCas.##)
    val h5 = MurmurHash3.mix(h4, this.map.##)
    MurmurHash3.finalizeHash(h5, this.map.size)
  }
}

object HalfEMCASDescriptor {

  private final val DefaultVersionIncr =
    Version.Incr

  private[mcas] final def empty(commitTsRef: MemoryLocation[Long], ctx: Mcas.ThreadContext): HalfEMCASDescriptor = {
    val validTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    emptyFromBoxed(validTsBoxed)
  }

  private[mcas] final def emptyFromVer(currentTs: Long): HalfEMCASDescriptor = {
    emptyFromBoxed(java.lang.Long.valueOf(currentTs))
  }

  private[this] final def emptyFromBoxed(validTsBoxed: java.lang.Long): HalfEMCASDescriptor = {
    new HalfEMCASDescriptor(
      LogMap.empty,
      validTsBoxed = validTsBoxed,
      readOnly = true,
      versionIncr = DefaultVersionIncr,
      versionCas = null,
    )
  }

  private[mcas] final def merge(
    a: HalfEMCASDescriptor,
    b: HalfEMCASDescriptor,
    ctx: Mcas.ThreadContext,
  ): HalfEMCASDescriptor = {
    require(a.versionCas eq null)
    require(b.versionCas eq null)
    require(a.versionIncr == b.versionIncr)
    // TODO: It is unclear, how should an exchange work when
    // TODO: both sides already touched the same refs;
    // TODO: for now, we only allow disjoint logs.
    // TODO: (This seems to make exchanges fundamentally
    // TODO: non-composable. Unless a solution is found
    // TODO: to this problem, an elimination stack (e.g.)
    // TODO: cannot be used in bigger `Rxn`s, because
    // TODO: by the time the elimination happens, the
    // TODO: two bigger `Rxn`s might've already touched
    // TODO: the same ref.)
    val it = b.map.valuesIterator
    var merged: HalfEMCASDescriptor = a
    while (it.hasNext) {
      // this also takes care of `readOnly`:
      merged = merged.add(it.next()) // throws in case of conflict
    }

    // we temporarily choose the older `validTs`,
    // but will extend if they're not equal:
    val needToExtend = if (a.validTs < b.validTs) {
      merged = merged.withValidTsBoxed(a.validTsBoxed)
      true
    } else if (a.validTs > b.validTs) {
      merged = merged.withValidTsBoxed(b.validTsBoxed)
      true
    } else {
      // they're equal, no need to extend:
      false
    }
    if (needToExtend) {
      // this will be null, if we cannot extend,
      // in which case we return null:
      merged = ctx.validateAndTryExtend(merged, hwd = null)
    }
    merged
  }
}
