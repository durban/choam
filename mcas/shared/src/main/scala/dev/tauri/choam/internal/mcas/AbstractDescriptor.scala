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

object AbstractDescriptor {

  type Aux[d <: AbstractDescriptor] = AbstractDescriptor {
    type D = d
  }
}

abstract class AbstractDescriptor extends AbstractDescriptorPlatform {

  type D <: AbstractDescriptor

  def self: D

  def readOnly: Boolean

  def validTs: Long

  def versionIncr: Long

  final def size: Int =
    this.hamt.size + (if (this.hasVersionCas) 1 else 0)

  protected def hamt: AbstractHamt[_, _, _, _, _, _]

  private[choam] def computeIfAbsent[A, T](
    ref: MemoryLocation[A],
    tok: T,
    visitor: Hamt.EntryVisitor[MemoryLocation[A], LogEntry[A], T],
  ): AbstractDescriptor.Aux[D]

  private[choam] def computeOrModify[A, T](
    ref: MemoryLocation[A],
    tok: T,
    visitor: Hamt.EntryVisitor[MemoryLocation[A], LogEntry[A], T],
  ): AbstractDescriptor.Aux[D]

  private[mcas] final def nonEmpty: Boolean =
    this.size > 0

  private[mcas] def hasVersionCas: Boolean

  private[mcas] def addVersionCas(commitTsRef: MemoryLocation[Long]): AbstractDescriptor.Aux[D]

  private[mcas] final def newVersion: Long =
    this.validTs + this.versionIncr

  private[choam] def getOrElseNull[A](ref: MemoryLocation[A]): LogEntry[A]

  final def isValidHwd[A](hwd: LogEntry[A]): Boolean = {
    hwd.version <= this.validTs
  }

  /**
   * Tries to revalidate `this` based on the current
   * versions of the refs it contains.
   *
   * @return true, iff `this` is still valid.
   */
  private[mcas] def revalidate(ctx: Mcas.ThreadContext): Boolean

  private[choam] def add[A](desc: LogEntry[A]): AbstractDescriptor.Aux[D]

  private[choam] def overwrite[A](desc: LogEntry[A]): AbstractDescriptor.Aux[D]

  private[choam] def addOrOverwrite[A](desc: LogEntry[A]): AbstractDescriptor.Aux[D]

  private[mcas] def validateAndTryExtend(
    commitTsRef: MemoryLocation[Long],
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[_], // can be null
  ): AbstractDescriptor.Aux[D]

  private[mcas] def validateAndTryExtendVer(
    currentTs: Long,
    ctx: Mcas.ThreadContext,
    additionalHwd: LogEntry[_], // can be null
  ): AbstractDescriptor.Aux[D]

  private[mcas] def withNoNewVersion: AbstractDescriptor.Aux[D]

  def toImmutable: Descriptor
}
