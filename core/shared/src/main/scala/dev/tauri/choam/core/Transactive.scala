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
package core

import cats.effect.kernel.Async

// Note: not really private, published in dev.tauri.choam.stm
private[choam] sealed trait Transactive[F[_]] {
  def commit[B](txn: Txn[F, B]): F[B]
}

// Note: not really private, published in dev.tauri.choam.stm
private[choam] object Transactive {

  final def forAsync[F[_]](implicit F: Async[F]): Transactive[F] = {
    new Reactive.SyncReactive[F](Rxn.DefaultMcas) with Transactive[F] {
      final override def commit[B](txn: Txn[F, B]): F[B] = {
        txn.impl.performStm[F, B](null, this.mcasImpl)
      }
    }
  }
}
