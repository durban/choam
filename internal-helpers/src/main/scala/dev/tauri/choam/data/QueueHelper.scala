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
package data

import core.{ Rxn, Ref, Reactive }

/**
 * Public access to package-private utilities
 * (for the purposes of testing/benchmarking)
 */
object QueueHelper {

  def fromList[F[_] : Reactive, Q[a] <: Queue[a], A](mkEmpty: Rxn[Q[A]])(as: List[A]): F[Q[A]] =
    Queue.fromList(mkEmpty)(as)

  def msQueueFromList[F[_], A](as: List[A])(implicit F: Reactive[F]): F[Queue[A]] =
    F.monad.widen(this.fromList(MsQueue[A](Ref.AllocationStrategy(padded = true)))(as))

  def msQueueUnpaddedFromList[F[_], A](as: List[A])(implicit F: Reactive[F]): F[Queue[A]] =
    F.monad.widen(this.fromList(MsQueue[A](Ref.AllocationStrategy(padded = false)))(as))

  def gcHostileMsQueueFromList[F[_], A](as: List[A])(implicit F: Reactive[F]): F[Queue[A]] =
    F.monad.widen(GcHostileMsQueue.fromList(as))
}
