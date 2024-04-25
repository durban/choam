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

sealed abstract class TRef[F[_], A] {

  def get: Txn[F, A]

  def set(nv: A): Txn[F, Unit]
}

object Txn {

  sealed abstract class TxnImpl extends Serializable {
    type T[F[_], +A]
    private[Txn] def wrap[F[_], A](rxn: Rxn[Any, A]): T[F, A]
    private[Txn] def unwrap[F[_], A](tfa: T[F, A]): Rxn[Any, A]
  }

  type Type[F[_], +A] = instance.T[F, A]

  private[Txn] final def wrap[F[_], A](rxn: Rxn[Any, A]): Type[F, A] =
    instance.wrap(rxn)

  private[Txn] final def unwrap[F[_], A](tfa: Type[F, A]): Rxn[Any, A] =
    instance.unwrap(tfa)

  val instance: TxnImpl = new TxnImpl {
    type T[F[_], +A] = Rxn[Any, A]
    private[Txn] def wrap[F[_], A](rxn: Rxn[Any, A]): T[F, A] = rxn
    private[Txn] def unwrap[F[_], A](tfa: T[F, A]): Rxn[Any, A] = tfa
  }

  final def pure[F[_], A](a: A): Txn[F, A] =
    wrap(Rxn.pure(a))

  final def retry[F[_], A]: Txn[F, A] =
    wrap(Rxn.unsafe.retry[Any, A])

  implicit final class TxnSyntax[F[_], A](private val self: Txn[F, A]) extends AnyVal {

    final def map[B](f: A => B): Txn[F, B] = {
      wrap(unwrap(self).map(f))
    }

    final def flatMap[B](f: A => Txn[F, B]): Txn[F, B] = {
      wrap(unwrap(self).flatMapF { a => unwrap(f(a)) })
    }

    final def commit(implicit F: AsyncLike[F]): F[A] = {
      val rxn = unwrap(self)
      rxn

      F.async.never[A]
    }
  }
}

sealed abstract class AsyncLike[F[_]] {
  def async: cats.effect.kernel.Async[F] // TODO
}
