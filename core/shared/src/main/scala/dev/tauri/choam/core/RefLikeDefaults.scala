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
package core

private[choam] trait RefLikeDefaults[A] extends RefLike.UnsealedRefLike[A] {

  // TODO: These derived implementations are duplicated with
  // TODO: RefGetAxn, except that those return `RxnImpl`.

  final def getAndSet(nv: A): Rxn[A] =
    getAndUpdate { _ => nv }

  /** Returns `false` iff the update failed */
  final def tryUpdate(f: A => A): Rxn[Boolean] =
    update(f).maybe

  /** Returns previous value */
  final def getAndUpdate(f: A => A): Rxn[A] =
    modify { oa => (f(oa), oa) }

  /** Returns new value */
  final def updateAndGet(f: A => A): Rxn[A] = {
    modify { oa =>
      val na = f(oa)
      (na, na)
    }
  }

  final def tryModify[B](f: A => (A, B)): Rxn[Option[B]] =
    modify(f).?

  final def flatModify[B](f: A => (A, Rxn[B])): Rxn[B] =
    modify(f).flatten
}
