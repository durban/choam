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

import cats.effect.kernel.{ Unique, Ref => CatsRef }
import cats.data.State
import cats.syntax.traverse._

import fs2.Stream

import core.{ =#>, Rxn, Axn, Ref, RefLike }
import data.Map
import async.{ AsyncReactive, Promise }

import Fs2SignallingRefWrapper.{ Listener, Waiting, Full, Empty }

private[stream] final class Fs2SignallingRefWrapper[F[_], A](
  underlying: Ref[A],
  val listeners: Map.Extra[Unique.Token, Ref[Listener[F, A]]],
)(implicit F: AsyncReactive[F]) extends RxnSignallingRef.UnsealedRxnSignallingRef[F, A] {

  // Rxn API:

  final override def refLike: RefLike[A] =
    _refLike

  private[this] val _refLike: RefLike[A] = new RefLike.UnsealedRefLike[A] {

    final override def get: Axn[A] =
      underlying.get

    final override def set0: Rxn[A, Unit] = {
      Rxn.computed(set1)
    }

    final override def set1(a: A): Axn[Unit] = {
      underlying.set1(a) >>> notifyListeners(a)
    }

    final override def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C] = {
      underlying.updWith[B, C] { (oldVal, b) =>
        val ac = f(oldVal, b)
        notifyListeners(ac._1).as(ac)
      }
    }

    final override def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[B, C] = {
      underlying.updWith[B, C] { (oldVal, b) =>
        f(oldVal, b).flatMapF { ac =>
          notifyListeners(ac._1).as(ac)
        }
      }
    }

    private[this] def notifyListeners(newVal: A): Axn[Unit] = {
      listeners.values.flatMapF(_.traverse { lRef =>
        lRef.modify {
          case Waiting(next) => (Empty(), Some(next))
          case Full(_) => (Full(newVal), None) // TODO:0.5: here we lose a value
          case Empty() => (Full(newVal), None)
        }.flatMapF {
          case Some(next) => next.complete1(newVal).void
          case None => Rxn.unit
        }
      }.void)
    }
  }

  private[this] val _refLikeAsCats: CatsRef[F, A] =
    RefLike.catsRefFromRefLike[F, A](_refLike)

  // Streams:

  final override def continuous: Stream[F, A] =
    Stream.repeatEval(this.get)

  final override def discrete: Stream[F, A] = {

    val acq: Axn[(Unique.Token, Ref[Listener[F, A]])] = {
      (Rxn.unique * underlying.get).flatMapF { case (tok, current) =>
        Ref.unpadded[Listener[F, A]](Full(current)).flatMapF { ref =>
          val tup = (tok, ref)
          listeners.put.provide(tup).as(tup)
        }
      }
    }

    val rel: Unique.Token =#> Unit =
      listeners.del.void

    def nextElement(ref: Ref[Listener[F, A]]): F[A] = {
      val rxn = Promise[A] >>> ref.upd { (l, p) =>
        l match {
          case Full(a) =>
            (Empty(), F.monad.pure(a))
          case Empty() =>
            (Waiting(p), p.get)
          case Waiting(_) =>
            impossible("got Waiting, but shouldn't, because before the Promise is completed, Waiting is removed")
            // (see `notifyListeners`)
        }
      }
      F.monad.flatten(F.run(rxn))
    }

    Stream.bracket(acquire = F.run(acq))(release = { tokRef =>
      F.apply(rel, tokRef._1)
    }).flatMap { tokRef =>
      Stream.repeatEval(nextElement(tokRef._2))
    }
  }

  // CatsRef:

  final override def get: F[A] =
    _refLikeAsCats.get

  final override def set(a: A): F[Unit] =
    _refLikeAsCats.set(a)

  override def access: F[(A, A => F[Boolean])] =
    _refLikeAsCats.access

  override def tryUpdate(f: A => A): F[Boolean] =
    _refLikeAsCats.tryUpdate(f)

  override def tryModify[B](f: A => (A, B)): F[Option[B]] =
    _refLikeAsCats.tryModify(f)

  override def update(f: A => A): F[Unit] =
    _refLikeAsCats.update(f)

  override def modify[B](f: A => (A, B)): F[B] =
    _refLikeAsCats.modify(f)

  override def tryModifyState[B](state: State[A, B]): F[Option[B]] =
    _refLikeAsCats.tryModifyState(state)

  override def modifyState[B](state: State[A, B]): F[B] =
    _refLikeAsCats.modifyState(state)
}

private[stream] object Fs2SignallingRefWrapper {

  final def apply[F[_] : AsyncReactive, A](initial: A): Axn[Fs2SignallingRefWrapper[F, A]] = {
    (Ref.unpadded[A](initial) * Map.simpleHashMap[Unique.Token, Ref[Listener[F, A]]]).map {
      case (underlying, listeners) =>
        new Fs2SignallingRefWrapper[F, A](underlying, listeners)
    }
  }

  /** Internal state of the `Ref` of each listener */
  sealed abstract class Listener[F[_], A]

  /** The listener is waiting for an item with this `Promise` */
  final case class Waiting[F[_], A](next: Promise[A])
    extends Listener[F, A]

  /** An item is ready to be taken by the listener */
  final case class Full[F[_], A](value: A)
    extends Listener[F, A]

  /** An item was taken by the listener, but it's not (yet) waiting for the next */
  final case class Empty[F[_], A]()
    extends Listener[F, A]
}
