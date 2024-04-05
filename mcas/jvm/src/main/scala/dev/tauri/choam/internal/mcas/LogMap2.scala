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

private[mcas] final class LogMap2[A] private (
  _size: Int,
  _bitmap: Long,
  _contents: Array[AnyRef],
) extends Hamt[HalfWordDescriptor[A], emcas.EmcasWordDesc[A], emcas.EmcasDescriptor, Mcas.ThreadContext, Descriptor, LogMap2[A]](
  _size,
  _bitmap,
  _contents,
) {

  final def revalidate(ctx: Mcas.ThreadContext): Boolean = {
    this.forAll(ctx)
  }

  protected final override def hashOf(a: HalfWordDescriptor[A]): Long =
    a.address.id

  protected final override def newNode(size: Int, bitmap: Long, contents: Array[AnyRef]): LogMap2[A] =
    new LogMap2(size, bitmap, contents)

  protected final override def newArray(size: Int): Array[emcas.EmcasWordDesc[A]] =
    new Array[emcas.EmcasWordDesc[A]](size)

  protected final override def convertForArray(a: HalfWordDescriptor[A], tok: emcas.EmcasDescriptor): emcas.EmcasWordDesc[A] =
    new emcas.EmcasWordDesc[A](a, tok)

  protected final override def convertForFoldLeft(s: Descriptor, a: HalfWordDescriptor[A]): Descriptor =
    s.add(a)

  protected final override def predicateForForAll(a: HalfWordDescriptor[A], tok: Mcas.ThreadContext): Boolean =
    a.revalidate(tok)
}

private[mcas] object LogMap2 {

  private[this] val _empty: LogMap2[Any] =
    new LogMap2(0, 0L, new Array[AnyRef](0))

  final def empty[A]: LogMap2[A] =
    _empty.asInstanceOf[LogMap2[A]]
}
