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

private[mcas] final class LogMap2[A] private (
  _sizeAndBlue: Int,
  _bitmap: Long,
  _contents: Array[AnyRef],
) extends Hamt[MemoryLocation[A], LogEntry[A], LogEntry[A], Unit, Mcas.ThreadContext, LogMap2[A]](_sizeAndBlue, _bitmap, _contents) {

  final def revalidate(ctx: Mcas.ThreadContext): Boolean = {
    this.forAll(ctx)
  }

  final def definitelyReadOnly: Boolean =
    this.isBlueSubtree

  protected final override def isBlue(a: LogEntry[A]): Boolean =
    a.readOnly

  protected final override def newNode(sizeAndBlue: Int, bitmap: Long, contents: Array[AnyRef]): LogMap2[A] =
    new LogMap2(sizeAndBlue, bitmap, contents)

  protected final override def newArray(size: Int): Array[LogEntry[A]] =
    new Array[LogEntry[A]](size)

  protected final override def convertForArray(a: LogEntry[A], tok: Unit, flag: Boolean): LogEntry[A] =
    a

  protected final override def predicateForForAll(a: LogEntry[A], tok: Mcas.ThreadContext): Boolean =
    a.revalidate(tok)
}

private[mcas] object LogMap2 {

  private[this] val _empty: LogMap2[Any] =
    new LogMap2(0, 0L, new Array[AnyRef](0))

  final def empty[A]: LogMap2[A] =
    _empty.asInstanceOf[LogMap2[A]]
}
