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
package core

import cats.{ ~>, Monad }
import cats.effect.kernel.{ Sync, Resource }

import internal.mcas.{ Mcas, OsRng }

// TODO: We should have a way to "propagate"
// TODO: a `Strategy`, because this way a
// TODO: data structure might just run things
// TODO: with the `Default` (e.g., CDL#toCats).

trait Reactive[F[_]] extends ~>[Axn, F] { self => // TODO:0.5: make it sealed
  def apply[A, B](r: Rxn[A, B], a: A, s: RetryStrategy.Spin = RetryStrategy.Default): F[B]
  def mcasImpl: Mcas
  def monad: Monad[F]
  def mapK[G[_]](t: F ~> G)(implicit G: Monad[G]): Reactive[G] =
    new Reactive.TransformedReactive[F, G](self, t)
  final def run[A](a: Axn[A], s: RetryStrategy.Spin = RetryStrategy.Default): F[A] =
    this.apply[Any, A](a, null: Any, s)
  final override def apply[A](a: Axn[A]): F[A] =
    this.run(a, RetryStrategy.Default)
}

object Reactive {

  def apply[F[_]](implicit inst: Reactive[F]): inst.type =
    inst

  @deprecated("Use forSyncRes", since = "0.4.11") // TODO:0.5: remove
  def forSync[F[_]](implicit F: Sync[F]): Reactive[F] =
    new SyncReactive[F](Rxn.DefaultMcas)(F)

  final def forSyncRes[F[_]](implicit F: Sync[F]): Resource[F, Reactive[F]] = // TODO:0.5: rename to forSync
    forSyncResIn[F, F]

  final def forSyncResIn[G[_], F[_]](implicit G: Sync[G], F: Sync[F]): Resource[G, Reactive[F]] = // TODO:0.5: rename to forSyncIn
    defaultMcasResource[G].map(new SyncReactive(_))

  // TODO: this needs a better place:
  private[choam] final def defaultMcasResource[F[_]](implicit F: Sync[F]): Resource[F, Mcas] = {
    osRngResource[F].flatMap { osRng =>
      Resource.make(
        acquire = F.blocking { // `blocking` because:
          // who knows what JMX is doing
          // when we're registering the mbean
          Mcas.newDefaultMcas(osRng)
         }
      )(
        release = { mcasImpl =>
          F.blocking { // `blocking` because:
            // who knows what JMX is doing
            // when we're unregistering the mbean
            mcasImpl.close()
          }
        }
      )
    }
  }

  // TODO: this needs a better place:
  private[choam] final def osRngResource[F[_]](implicit F: Sync[F]): Resource[F, OsRng] = {
    Resource.make(
      acquire = F.blocking { // `blocking` because:
        OsRng.mkNew() // <- this call may block
      }
    )(
      release = { osRng =>
        F.blocking {  // `blocking` because:
          // closing the FileInputStream of
          // the OsRng involves a lock
          osRng.close()
        }
      }
    )
  }

  private[choam] class SyncReactive[F[_]](
    final override val mcasImpl: Mcas
  )(implicit F: Sync[F]) extends Reactive[F] {

    final override def apply[A, B](r: Rxn[A, B], a: A, s: RetryStrategy.Spin): F[B] = {
      F.delay { r.unsafePerform(a = a, mcas = this.mcasImpl, strategy = s) }
    }

    final override def monad: Monad[F] =
      F
  }

  private[choam] class TransformedReactive[F[_], G[_]](
    underlying: Reactive[F],
    t: F ~> G,
  )(implicit G: Monad[G]) extends Reactive[G] {
    final override def apply[A, B](r: Rxn[A, B], a: A, s: RetryStrategy.Spin): G[B] =
      t(underlying.apply(r, a, s))
    final override def mcasImpl: Mcas =
      underlying.mcasImpl
    final override def monad: Monad[G] =
      G
  }
}
