/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
  protected final override val map: LogMap2[Any],
  final override val validTs: Long,
  private val validTsBoxed: java.lang.Long,
  final override val versionIncr: Long,
  private final val versionCas: LogEntry[java.lang.Long], // can be null
) extends DescriptorPlatform {

  require((versionCas eq null) || (versionIncr > 0L))
  require((validTsBoxed eq null) || (validTsBoxed.longValue == validTs))

  final override type D = Descriptor

  final override val readOnly: Boolean =
    this.map.definitelyReadOnly && (!this.hasVersionCas)

  final override def toImmutable: Descriptor =
    this

  protected final override def hamt: AbstractHamt[_, _, _, _, _, _] =
    this.map

  private[mcas] final override def hasVersionCas: Boolean = {
    this.versionCas ne null
  }

  private[mcas] final override def addVersionCas(commitTsRef: MemoryLocation[Long]): Descriptor = {
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
      versionIncr = this.versionIncr,
      versionCas = hwd,
    )
  }

  private[choam] final override def getOrElseNull[A](ref: MemoryLocation[A]): LogEntry[A] = {
    this.map.asInstanceOf[LogMap2[A]].getOrElseNull(ref.id)
  }

  private[choam] final override def add[A](desc: LogEntry[A]): Descriptor = {
    // Note, that it is important, that we don't allow
    // adding an already included ref; the Exchanger
    // depends on this behavior:
    val newMap = this.map.inserted(desc.cast[Any])
    this.withLogMap(newMap)
  }

  private[choam] final override def overwrite[A](desc: LogEntry[A]): Descriptor = {
    require(desc.version <= this.validTs)
    val newMap = this.map.updated(desc.cast[Any])
    this.withLogMap(newMap)
  }

  private[choam] final def addOrOverwrite[A](desc: LogEntry[A]): Descriptor = {
    require(desc.version <= this.validTs)
    val newMap = this.map.upserted(desc.cast[Any])
    this.withLogMap(newMap)
  }

  private[choam] final override def computeIfAbsent[A, T](
    ref: MemoryLocation[A],
    tok: T,
    visitor: Hamt.EntryVisitor[MemoryLocation[A], LogEntry[A], T],
  ): Descriptor = {
    val newMap = this.map.computeIfAbsent(ref.cast[Any], tok, visitor.asInstanceOf[Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], T]])
    if (newMap eq this.map) {
      this
    } else {
      this.withLogMap(newMap)
    }
  }

  private[choam] final override def computeOrModify[A, T](
    ref: MemoryLocation[A],
    tok: T,
    visitor: Hamt.EntryVisitor[MemoryLocation[A], LogEntry[A], T],
  ): Descriptor = {
    val newMap = this.map.computeOrModify(ref.cast[Any], tok, visitor.asInstanceOf[Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], T]])
    if (newMap eq this.map) {
      this
    } else {
      this.withLogMap(newMap)
    }
  }

  /**
   * Tries to revalidate `this` based on the current
   * versions of the refs it contains.
   *
   * @return true, iff `this` is still valid.
   */
  private[mcas] final override def revalidate(ctx: Mcas.ThreadContext): Boolean = {
    this.map.revalidate(ctx)
  }

  private[mcas] final override def validateAndTryExtend(
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

  private[choam] final override def hwdIterator: Iterator[LogEntry[Any]] = {
    val values = this.map.valuesIterator
    val vc = this.versionCas
    if (vc eq null) {
      values
    } else {
      Iterator.single(vc.cast[Any]).concat(values)
    }
  }

  private[mcas] final def withNoNewVersion: Descriptor = {
    require(this.versionCas eq null)
    new Descriptor(
      map = this.map,
      validTs = this.validTs,
      validTsBoxed = this.validTsBoxed,
      versionIncr = 0L,
      versionCas = null,
    )
  }

  private final def withLogMap(newMap: LogMap2[Any]): Descriptor = {
    if (newMap eq this.map) {
      this
    } else {
      new Descriptor(
        map = newMap,
        validTs = this.validTs,
        validTsBoxed = this.validTsBoxed,
        versionIncr = this.versionIncr,
        versionCas = this.versionCas,
      )
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
    var h = MurmurHash3.mix(0xefebde66, this.validTs.##)
    h = MurmurHash3.mix(h, this.versionIncr.##)
    h = MurmurHash3.mix(h, this.versionCas.##)
    h = MurmurHash3.mix(h, this.map.##)
    MurmurHash3.finalizeHash(h, this.map.size)
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
      versionIncr = DefaultVersionIncr,
      versionCas = null,
    )
  }

  private[mcas] final def fromLogMapAndVer(
    map: LogMap2[Any],
    validTs: Long,
    versionIncr: Long,
  ): Descriptor = {
    new Descriptor(
      map = map,
      validTs = validTs,
      validTsBoxed = null, // see above
      versionIncr = versionIncr,
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
      merged = ctx.validateAndTryExtend(merged, hwd = null) match {
        case null =>
          // couldn't extend:
          null
        case extended =>
          // we know it's immutable here
          // (so `toImmutable` is a NOP),
          // we just need to convince scalac
          // that it's type is `Descriptor`:
          val r = extended.toImmutable
          assert(r eq extended)
          r
      }
    }
    merged
  }
}
