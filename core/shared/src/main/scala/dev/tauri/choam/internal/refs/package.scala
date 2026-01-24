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

import core.Ref

package object refs extends RefsPackagePlatform {

  private[choam] def unsafeNewRefU1[A](initial: A)(i: Long): Ref[A] =
    RefsPlatform.unsafeNewRefU1[A](initial, i)

  private[choam] def unsafeNewRefP1[A](initial: A)(i: Long): Ref[A] =
    RefsPlatform.unsafeNewRefP1[A](initial, i)

  private[choam] def unsafeNewDenseRefArray[A](size: Int, initial: A)(idBase: Long): Ref.Array[A] = {
    new DenseRefArray[A](__size = size, initial = initial, _idBase = idBase)
  }

  private[choam] def unsafeNewDenseRefTArray[A](size: Int, initial: A)(idBase: Long): stm.TArray[A] with Ref.Array[A] = {
    new DenseTRefArray[A](__size = size, initial = initial, _idBase = idBase)
  }

  private[choam] def unsafeNewSparseRefArray[A](size: Int, initial: A)(idBase: Long): Ref.Array[A] = {
    new SparseRefArray[A](__size = size, initial = initial, _idBase = idBase)
  }

  private[choam] def unsafeNewSparseRefTArray[A](size: Int, initial: A)(idBase: Long): stm.TArray[A] with Ref.Array[A] = {
    new SparseTRefArray[A](__size = size, initial = initial, _idBase = idBase)
  }

  private[refs] def refStringFrom1(
    i0: Long,
  ): String = {
    "Ref@" + internal.mcas.refIdHexString(i0)
  }

  private[refs] def refStringFrom2(
    i0: Long,
    i1: Long,
  ): String = {
    "Ref2@" + internal.mcas.refIdHexString(i0 ^ i1)
  }

  private[refs] def refArrayRefToString(prefix: String, idBase: Long, offset: Int): String = {
    val baseHash = internal.mcas.refHashArrayIdBase(idBase)
    s"${prefix}@${baseHash}+${offset}"
  }
}
