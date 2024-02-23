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

package object refs {

  type Ref1[A] = Ref[A]
  val Ref1: Ref.type = Ref

  private[choam] def unsafeNewRefU1[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] =
    RefsPlatform.unsafeNewRefU1[A](initial, i0, i1, i2, i3)

  private[choam] def unsafeNewRefP1[A](initial: A)(i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] =
    RefsPlatform.unsafeNewRefP1[A](initial, i0, i1, i2, i3)

  private[choam] def unsafeNewStrictRefArray[A](size: Int, initial: A)(i0: Long, i1: Long, i2: Long, i3: Int): Ref.Array[A] = {
    new StrictRefArray[A](size = size, initial = initial, i0 = i0, i1 = i1, i2 = i2, i3 = i3)
  }

  private[choam] def unsafeNewLazyRefArray[A](size: Int, initial: A)(i0: Long, i1: Long, i2: Long, i3: Int): Ref.Array[A] = {
    new LazyRefArray[A](size = size, initial = initial, i0 = i0, i1 = i1, i2 = i2, i3 = i3)
  }

  private[refs] def refStringFrom4Ids(
    i0: Long,
    i1: Long,
    i2: Long,
    i3: Long
  ): String = {
    "Ref@" + internal.mcas.refHashString(i0, i1, i2, i3)
  }

  private[refs] def refStringFrom8Ids(
    i0: Long,
    i1: Long,
    i2: Long,
    i3: Long,
    i4: Long,
    i5: Long,
    i6: Long,
    i7: Long
  ): String = {
    "Ref2@" + internal.mcas.refHashString(i0 ^ i1, i2 ^ i3, i4 ^ i5, i6 ^ i7)
  }

  private[refs] def refStringFromIdsAndIdx(
    i0: Long,
    i1: Long,
    i2: Long,
    i3: Long,
    idx: Int,
  ): String = {
    val l: Long = internal.mcas.refShortHash(i0, i1, i2, i3) & (~0xffffL)
    val s: Int = (idx & 0xffff)
    "ARef@" + internal.mcas.toHexPadded(l | s.toLong)
  }
}
