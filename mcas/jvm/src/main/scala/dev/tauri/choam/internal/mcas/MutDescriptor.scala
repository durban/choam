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

import emcas.EmcasDescriptor

final class MutDescriptor private (
  private[this] val map: LogMapMut[Any],
  private[this] var _validTs: Long,
  private[this] var _versionIncr: Long,
) extends AbstractDescriptor {

  final override type D = MutDescriptor

  final override def readOnly: Boolean =
    this.map.definitelyReadOnly

  final override def validTs: Long =
    this._validTs

  final override def versionIncr: Long =
    this._versionIncr

  final override def toImmutable: Descriptor = {
    Descriptor.fromLogMapAndVer(
      map = this.map.copyToImmutable(),
      validTs = this.validTs,
      versionIncr = this.versionIncr,
    )
  }

  protected final override def hamt: AbstractHamt[_, _, _, _, _, _] =
    this.map

  private[mcas] final override def hasVersionCas: Boolean =
    false

  private[mcas] final override def addVersionCas(commitTsRef: MemoryLocation[Long]): AbstractDescriptor.Aux[MutDescriptor] = {
    impossible("MutDescriptor#addVersionCas")
  }

  private[choam] final override def getOrElseNull[A](ref: MemoryLocation[A]): LogEntry[A] = {
    this.map.asInstanceOf[LogMapMut[A]].getOrElseNull(ref.id)
  }

  private[choam] final override def add[A](desc: LogEntry[A]): AbstractDescriptor.Aux[MutDescriptor] = {
    // Note, that it is important, that we don't allow
    // adding an already included ref; the Exchanger
    // depends on this behavior:
    this.map.insert(desc.cast[Any])
    this
  }

  private[choam] final override def overwrite[A](desc: LogEntry[A]): AbstractDescriptor.Aux[MutDescriptor] = {
    require(desc.version <= this.validTs)
    this.map.update(desc.cast[Any])
    this
  }

  private[choam] final override def addOrOverwrite[A](desc: LogEntry[A]): AbstractDescriptor.Aux[MutDescriptor] = {
    require(desc.version <= this.validTs)
    this.map.upsert(desc.cast[Any])
    this
  }

  private[choam] final override def computeIfAbsent[A, T](
    ref: MemoryLocation[A],
    tok: T,
    visitor: Hamt.EntryVisitor[MemoryLocation[A], LogEntry[A], T],
  ): MutDescriptor = {
    this.map.computeIfAbsent(ref.cast[Any], tok, visitor.asInstanceOf[Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], T]])
    this
  }

  private[choam] final override def computeOrModify[A, T](
    ref: MemoryLocation[A],
    tok: T,
    visitor: Hamt.EntryVisitor[MemoryLocation[A],LogEntry[A],T],
  ): MutDescriptor = {
    this.map.computeOrModify(ref.cast[Any], tok, visitor.asInstanceOf[Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], T]])
    this
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
    additionalHwd: LogEntry[_],
  ): AbstractDescriptor.Aux[MutDescriptor] = {
    impossible("MutDescriptor#validateAndTryExtend")
  }

  private[mcas] final override def validateAndTryExtendVer(
    currentTs: Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[_]
  ): AbstractDescriptor.Aux[MutDescriptor] = {
    val newValidTs = currentTs
    if (currentTs > this.validTs) {
      if (
        ctx.validate(this) &&
        ((additionalHwd eq null) || ctx.validateHwd(additionalHwd))
      ) {
        assert((additionalHwd eq null) || (additionalHwd.version <= newValidTs))
        this._validTs = currentTs
        this
      } else {
        null
      }
    } else {
      // no need to validate:
      this
    }
  }

  private[mcas] final override def withNoNewVersion: MutDescriptor = {
    this._versionIncr = 0L
    this
  }

  private[choam] final override def hwdIterator: Iterator[LogEntry[Any]] = {
    this.map.valuesIterator
  }

  /** This is used by EMCAS instead of the above */
  private[mcas] final override def toWdArray(parent: EmcasDescriptor, instRo: Boolean): Array[WdLike[Any]] = {
    this.map.copyToArray(parent, flag = instRo, nullIfBlue = true)
  }

  final override def toString: String = {
    val m = this.map.toString(pre = "[", post = "]")
    val vi = if (versionIncr == MutDescriptor.DefaultVersionIncr) {
      ""
    } else {
      s", versionIncr = ${versionIncr}"
    }
    s"mcas.MutDescriptor(${m}, validTs = ${validTs}, readOnly = ${readOnly}${vi})"
  }

  final override def equals(that: Any): Boolean = {
    that match {
      case that: MutDescriptor =>
        (this eq that) || (
          (this.validTs == that.validTs) &&
          (this.versionIncr == that.versionIncr) &&
          (this.map == that.hamt)
        )
      case _ =>
        false
    }
  }

  final override def hashCode: Int = {
    var h = MurmurHash3.mix(0x9bae16ae, this.validTs.##)
    h = MurmurHash3.mix(h, this.versionIncr.##)
    h = MurmurHash3.mix(h, this.map.##)
    MurmurHash3.finalizeHash(h, this.map.size)
  }
}

object MutDescriptor {

  private final val DefaultVersionIncr =
    Version.Incr

  private[mcas] final def newEmptyFromVer(currentTs: Long): MutDescriptor = {
    new MutDescriptor(
      LogMapMut.newEmpty(),
      _validTs = currentTs,
      _versionIncr = DefaultVersionIncr,
    )
  }
}
