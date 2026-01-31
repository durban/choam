/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
  protected final override val map: LogMap[Any],
  final override val validTs: Long,
) extends DescriptorPlatform {

  final override type D = Descriptor

  final override val readOnly: Boolean =
    this.map.definitelyReadOnly

  final def isEmpty: Boolean =
    (this.size == 0)

  final override def toImmutable: Descriptor =
    this

  protected final override def hamt: AbstractHamt[?, ?, ?, ?, ?, ?] =
    this.map

  private[choam] final override def getOrElseNull[A](ref: MemoryLocation[A]): LogEntry[A] = {
    val r = this.map.asInstanceOf[LogMap[A]].getOrElseNull(ref.id)
    _assert((r eq null) || (r.address eq ref))
    r
  }

  private[choam] final override def add[A](desc: LogEntry[A]): Descriptor = {
    // Note, that it is important, that we don't allow
    // adding an already included ref; the Exchanger
    // depends on this behavior:
    val newMap = this.map.inserted(desc.cast[Any])
    this.withLogMap(newMap)
  }

  @throws[IllegalArgumentException]("if the ref is not in fact read-only")
  private[choam] final override def removeReadOnlyRef[A](ref: MemoryLocation[A]): Descriptor = {
    val newMap = this.map.withoutBlueValue(ref.cast[Any])
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

  private[mcas] final def validateAndTryExtendVer(
    currentTs: Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[?], // can be null
  ): Descriptor = {
    this.validateAndTryExtendInternal(
      newValidTs = currentTs,
      ctx = ctx,
      additionalHwd = additionalHwd,
    )
  }

  private[this] final def validateAndTryExtendInternal(
    newValidTs: Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[?], // can be null
  ): Descriptor = {
    if (newValidTs > this.validTs) {
      if (
        ctx.validate(this) &&
        ((additionalHwd eq null) || ctx.validateHwd(additionalHwd))
      ) {
        _assert((additionalHwd eq null) || (additionalHwd.version <= newValidTs))
        new Descriptor(
          map = this.map,
          validTs = newValidTs,
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
    this.map.valuesIterator
  }

  private final def withLogMap(newMap: LogMap[Any]): Descriptor = {
    if (newMap eq this.map) {
      this
    } else {
      new Descriptor(
        map = newMap,
        validTs = this.validTs,
      )
    }
  }

  private final def withLogMapAndValidTs(newMap: LogMap[Any], newValidTs: Long): Descriptor = {
    if ((newMap eq this.map) && (newValidTs == this.validTs)) {
      this
    } else {
      new Descriptor(
        map = newMap,
        validTs = newValidTs,
      )
    }
  }

  final override def toString: String = {
    val m = this.map.toString(pre = "[", post = "]")
    s"mcas.Descriptor(${m}, validTs = ${validTs}, readOnly = ${readOnly})"
  }

  final override def equals(that: Any): Boolean = {
    that match {
      case that: Descriptor =>
        (this eq that) || (
          (this.validTs == that.validTs) &&
          (this.map == that.map)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    var h = MurmurHash3.mix(0xefebde66, this.validTs.##)
    h = MurmurHash3.mixLast(h, this.map.##)
    MurmurHash3.finalizeHash(h, this.map.size)
  }
}

object Descriptor {

  private[mcas] final def emptyFromVer(currentTs: Long): Descriptor = {
    new Descriptor(
      LogMap.empty,
      validTs = currentTs,
    )
  }

  private[mcas] final def fromLogMapAndVer(
    map: LogMap[Any],
    validTs: Long,
  ): Descriptor = {
    new Descriptor(
      map = map,
      validTs = validTs,
    )
  }

  private[mcas] final def merge(
    a: Descriptor,
    b: Descriptor,
    ctx: Mcas.ThreadContext,
    canExtend: Boolean,
  ): Descriptor = {
    // throws `Hamt.IllegalInsertException` in case of conflict:
    val mergedMap = a.map.insertedAllFrom(b.map)

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
      if (canExtend) {
        merged = validateAndTryExtendMerged(merged, ctx)
      } else {
        merged = null
      }
    }
    merged
  }

  private[this] final def validateAndTryExtendMerged(merged: Descriptor, ctx: Mcas.ThreadContext): Descriptor = {
    ctx.validateAndTryExtend(merged, hwd = null) match {
      case null =>
        // couldn't extend:
        null
      case extended =>
        // we know it's immutable here
        // (so `toImmutable` is a NOP),
        // we just need to convince scalac
        // that it's type is `Descriptor`:
        val r = extended.toImmutable
        _assert(r eq extended)
        r
    }
  }

  private[choam] final def mergeReadsInto(
    into: Descriptor,
    from: AbstractDescriptor,
  ): Descriptor = {
    var logMap = if (into ne null) into.map else LogMap.empty[Any]
    val itr = from.hwdIterator
    val visitor = this.mergeReadsVisitor
    while (itr.hasNext) {
      val hwd = itr.next()
      logMap = logMap.computeIfAbsent(hwd.address, hwd, visitor)
    }
    // Note: `from` is a valid extension of `into`,
    // so we can safely replace `into`'s `validTs`
    // with `from`'s `validTs`.
    if (into ne null) {
      into.withLogMapAndValidTs(logMap, newValidTs = from.validTs)
    } else {
      new Descriptor(
        map = logMap,
        validTs = from.validTs,
      )
    }
  }

  private[this] val mergeReadsVisitor: Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], LogEntry[Any]] = {
    new Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], LogEntry[Any]] {
      final override def entryPresent(k: MemoryLocation[Any], v: LogEntry[Any], tok: LogEntry[Any]): LogEntry[Any] = {
        v // already there, nothing to do
      }
      final override def entryAbsent(k: MemoryLocation[Any], tok: LogEntry[Any]): LogEntry[Any] = {
        if (tok.readOnly) tok
        else tok.withNv(tok.ov)
      }
    }
  }
}
