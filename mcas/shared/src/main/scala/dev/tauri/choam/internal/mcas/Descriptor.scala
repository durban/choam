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

import scala.util.hashing.MurmurHash3

final class Descriptor private (
  protected[choam] final override val map: LogMap2[Any],
  final val validTs: Long,
  private val validTsBoxed: java.lang.Long,
  val readOnly: Boolean,
  private val versionIncr: Long,
  protected final override val versionCas: LogEntry[java.lang.Long], // can be null
) extends DescriptorPlatform {

  require((versionCas eq null) || (versionIncr > 0L))
  require((validTsBoxed eq null) || (validTsBoxed.longValue == validTs))

  final def size: Int =
    this.map.size + (if (this.versionCas ne null) 1 else 0)

  final def isValidHwd[A](hwd: LogEntry[A]): Boolean = {
    hwd.version <= this.validTs
  }

  private[choam] final def withLogMap(newMap: LogMap2[Any]): Descriptor = {
    if (newMap eq this.map) {
      this
    } else {
      new Descriptor(
        map = newMap,
        validTs = this.validTs,
        validTsBoxed = this.validTsBoxed,
        readOnly = this.readOnly,
        versionIncr = this.versionIncr,
        versionCas = this.versionCas,
      )
    }
  }

  private[mcas] final def hasVersionCas: Boolean = {
    this.versionCas ne null
  }

  private[mcas] final def withNoNewVersion: Descriptor = {
    require(this.versionCas eq null)
    new Descriptor(
      map = this.map,
      validTs = this.validTs,
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

  private[choam] final def add[A](desc: LogEntry[A]): Descriptor = {
    // Note, that it is important, that we don't allow
    // adding an already included ref; the Exchanger
    // depends on this behavior:
    val newMap = this.map.inserted(desc.cast[Any])
    new Descriptor(
      newMap,
      this.validTs,
      this.validTsBoxed,
      this.readOnly && desc.readOnly,
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[choam] final def overwrite[A](desc: LogEntry[A]): Descriptor = {
    require(desc.version <= this.validTs)
    val newMap = this.map.updated(desc.cast[Any])
    new Descriptor(
      newMap,
      this.validTs,
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }

  private[choam] final def addOrOverwrite[A](desc: LogEntry[A]): Descriptor = {
    require(desc.version <= this.validTs)
    val newMap = this.map.upserted(desc.cast[Any])
    new Descriptor(
      newMap,
      this.validTs,
      this.validTsBoxed,
      this.readOnly && desc.readOnly, // this is a simplification:
      // we don't want to rescan here the whole log, so we only pass
      // true if it's DEFINITELY read-only
      versionIncr = this.versionIncr,
      versionCas = this.versionCas,
    )
  }


  /**
   * Tries to revalidate `this` based on the current
   * versions of the refs it contains.
   *
   * @return true, iff `this` is still valid.
   */
  private[mcas] final def revalidate(ctx: Mcas.ThreadContext): Boolean = {
    this.map.revalidate(ctx)
  }

  private[mcas] final def addVersionCas(commitTsRef: MemoryLocation[Long]): Descriptor = {
    require(this.versionCas eq null)
    require(!this.readOnly)
    require(this.versionIncr > 0L)
    require(this.validTsBoxed ne null)
    val hwd = LogEntry[java.lang.Long](
      commitTsRef.asInstanceOf[MemoryLocation[java.lang.Long]],
      ov = this.validTsBoxed, // no re-boxing here
      nv = java.lang.Long.valueOf(this.newVersion),
      version = Version.Start, // the version's version is unused/arbitrary
    )
    new Descriptor(
      map = this.map,
      validTs = this.validTs,
      validTsBoxed = this.validTsBoxed,
      readOnly = false,
      versionIncr = this.versionIncr,
      versionCas = hwd,
    )
  }

  private[choam] final def getOrElseNull[A](ref: MemoryLocation[A]): LogEntry[A] = {
    this.map.asInstanceOf[LogMap2[A]].getOrElseNull(ref.id)
  }

  private[mcas] final def validateAndTryExtend(
    commitTsRef: MemoryLocation[Long],
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[_], // can be null
  ): Descriptor = {
    require(this.versionCas eq null)
    // NB: we must read the commitTs *before* the `ctx.validate...`
    val newValidTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    this.validateAndTryExtendInternal(newValidTsBoxed.longValue, newValidTsBoxed, ctx, additionalHwd)
  }

  private[mcas] final def validateAndTryExtendVer(
    currentTs: Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[_], // can be null
  ): Descriptor = {
    // We're passing `newValidTsBoxed = null`. This is ugly,
    // but nothing will actually need it (we're doing EMCAS
    // if we're here), and this way we avoid allocating
    // an extra `java.lang.Long`.
    this.validateAndTryExtendInternal(
      newValidTs = currentTs,
      newValidTsBoxed = null, // see above
      ctx = ctx,
      additionalHwd = additionalHwd,
    )
  }

  private[this] final def validateAndTryExtendInternal(
    newValidTs: Long,
    newValidTsBoxed: java.lang.Long, // can be null
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[_], // can be null
  ): Descriptor = {
    if (newValidTs > this.validTs) {
      if (
        ctx.validate(this) &&
        ((additionalHwd eq null) || ctx.validateHwd(additionalHwd))
      ) {
        assert((additionalHwd eq null) || (additionalHwd.version <= newValidTs))
        new Descriptor(
          map = this.map,
          validTs = newValidTs,
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
    val m = this.map.toString(pre = "[", post = "]")
    val vi = if (versionIncr == Descriptor.DefaultVersionIncr) {
      ""
    } else {
      s", versionIncr = ${versionIncr}"
    }
    val vc = if (versionCas eq null) {
      ""
    } else {
      s", versionCas = ${versionCas}"
    }
    s"mcas.Descriptor(${m}, validTs = ${validTs}, readOnly = ${readOnly}${vi}${vc})"
  }

  final override def equals(that: Any): Boolean = {
    that match {
      case that: Descriptor =>
        (this eq that) || (
          (this.readOnly == that.readOnly) &&
          (this.versionCas == that.versionCas) &&
          (this.validTs == that.validTs) &&
          (this.validTsBoxed eq that.validTsBoxed) &&
          (this.versionIncr == that.versionIncr) &&
          (this.map == that.map)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    val h1 = MurmurHash3.mix(0xefebde66, this.validTs.##)
    val h2 = MurmurHash3.mix(h1, this.readOnly.##)
    val h3 = MurmurHash3.mix(h2, this.versionIncr.##)
    val h4 = MurmurHash3.mix(h3, this.versionCas.##)
    val h5 = MurmurHash3.mix(h4, this.map.##)
    MurmurHash3.finalizeHash(h5, this.map.size)
  }
}

object Descriptor {

  private final val DefaultVersionIncr =
    Version.Incr

  private[mcas] final def empty(commitTsRef: MemoryLocation[Long], ctx: Mcas.ThreadContext): Descriptor = {
    val validTsBoxed: java.lang.Long =
      (ctx.readDirect(commitTsRef) : Any).asInstanceOf[java.lang.Long]
    new Descriptor(
      LogMap2.empty,
      validTs = validTsBoxed.longValue,
      validTsBoxed = validTsBoxed,
      readOnly = true,
      versionIncr = DefaultVersionIncr,
      versionCas = null,
    )
  }

  private[mcas] final def emptyFromVer(currentTs: Long): Descriptor = {
    // We're passing `validTsBoxed = null`. This is ugly,
    // but nothing will actually need it (we're doing EMCAS
    // if we're here), and this way we avoid allocating
    // an extra `java.lang.Long`.
    new Descriptor(
      LogMap2.empty,
      validTs = currentTs,
      validTsBoxed = null, // see above
      readOnly = true,
      versionIncr = DefaultVersionIncr,
      versionCas = null,
    )
  }

  private[mcas] final def merge(
    a: Descriptor,
    b: Descriptor,
    ctx: Mcas.ThreadContext,
  ): Descriptor = {
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
    val mergedMap = a.map.insertedAllFrom(b.map) // throws in case of conflict

    // we temporarily choose the older `validTs`,
    // but will extend if they're not equal:
    var merged: Descriptor = null
    val needToExtend = if (a.validTs < b.validTs) {
      merged = a.withLogMap(mergedMap)
      true
    } else if (a.validTs > b.validTs) {
      merged = b.withLogMap(mergedMap)
      true
    } else {
      // they're equal, no need to extend:
      merged = a.withLogMap(mergedMap)
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
