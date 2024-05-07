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

private[mcas] final class LogMapMut[A] private (
  _logIdx: Int,
  _contents: Array[AnyRef],
) extends MutHamt[MemoryLocation[A], LogEntry[A], WdLike[A], emcas.EmcasDescriptor, Mcas.ThreadContext, LogMap2[A], LogMapMut[A]](_logIdx, _contents) {

  protected final override def keyOf(a: LogEntry[A]): MemoryLocation[A] =
    a.address

  protected final override def hashOf(k: MemoryLocation[A]): Long =
    k.id

  protected final override def newNode(logIdx: Int, contents: Array[AnyRef]): LogMapMut[A] =
    new LogMapMut[A](logIdx, contents)

  protected final override def newImmutableNode(size: Int, bitmap: Long, contents: Array[AnyRef]): LogMap2[A] =
    new LogMap2[A](size, bitmap, contents)

  protected final override def newArray(size: Int): Array[WdLike[A]] =
    new Array[WdLike[A]](size)

  protected final override def convertForArray(a: LogEntry[A], tok: emcas.EmcasDescriptor, instRo: Boolean): WdLike[A] = {
    if ((!instRo) && a.readOnly) a
    else new emcas.EmcasWordDesc[A](a, parent = tok)
  }

  protected final override def predicateForForAll(a: LogEntry[A], tok: Mcas.ThreadContext): Boolean =
    a.revalidate(tok)
}

private[mcas] object LogMapMut {

  final def newEmpty[A](): LogMapMut[A] =
    new LogMapMut(0, new Array[AnyRef](1))
}
