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
package stream

import fs2.concurrent.SignallingRef

import core.Axn
import async.AsyncReactive

/**
 * An [[fs2.concurrent.SignallingRef]], which
 * is also readable/writable in the context
 * of [[dev.tauri.choam.Rxn]] (i.e., it has
 * an associated [[dev.tauri.choam.RefLike]],
 * accessible through the `refLike` method).
 */
sealed abstract class RxnSignallingRef[F[_], A]
  extends SignallingRef[F, A] {

  def refLike: RefLike[A]
}

object RxnSignallingRef {

  private[choam] abstract class UnsealedRxnSignallingRef[F[_], A]
    extends RxnSignallingRef[F, A]

  final def apply[F[_] : AsyncReactive, A](initial: A): Axn[RxnSignallingRef[F, A]] =
    Fs2SignallingRefWrapper[F, A](initial)
}
