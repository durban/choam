/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.Unique
import cats.data.State
import cats.syntax.traverse._

import fs2.Stream
import fs2.concurrent.SignallingRef

import data.Map
import async.{ AsyncReactive, Promise }

import Fs2SignallingRefWrapper._

// TODO: this needs cleanup
abstract class RxnSignallingRef[F[_], A]
  extends SignallingRef[F, A] {

  def rxn: RxnApi[A]
}

// TODO: this needs cleanup
trait RxnApi[A] {
  def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C]
  // TODO: stuff derived from upd...
}

private[stream] object Fs2SignallingRefWrapper {

  def apply[F[_] : AsyncReactive, A](initial: A): Axn[Fs2SignallingRefWrapper[F, A]] = {
    (Ref[A](initial) * Map.simple[Unique.Token, Ref[Listener[F, A]]]).map {
      case (underlying, listeners) =>
        new Fs2SignallingRefWrapper[F, A](underlying, listeners)
    }
  }

  /** Internal state of the `Ref` of each listener */
  sealed abstract class Listener[F[_], A]

  /** The listener is waiting for an item with this `Promise` */
  final case class Waiting[F[_], A](next: Promise[F, A])
    extends Listener[F, A]

  /** An item is ready to be taken by the listener */
  final case class Full[F[_], A](value: A)
    extends Listener[F, A]

  /** An item was taken by the listener, but it's not (yet) waiting for the next */
  final case class Empty[F[_], A]()
    extends Listener[F, A]
}

// TODO: also consider fs2.concurrent.Channel
private[stream] final class Fs2SignallingRefWrapper[F[_], A](
  underlying: Ref[A],
  val listeners: Map[Unique.Token, Ref[Listener[F, A]]],
)(implicit F: AsyncReactive[F]) extends RxnSignallingRef[F, A] {

  // Rxn API:

  def rxn: RxnApi[A] = new RxnApi[A] {

    final override def upd[B, C](f: (A, B) => (A, C)): Rxn[B, C] = {
      underlying.updWith[B, C] { (oldVal, b) =>
        val (newVal, c) = f(oldVal, b)
        notifyListeners(newVal).as((newVal, c))
      }
    }

    private[this] def notifyListeners(newVal: A): Axn[Unit] = {
      listeners.values.flatMapF(_.traverse { lRef =>
        lRef.modify {
          case Waiting(next) => (Empty(), Some(next))
          case Full(_) => (Full(newVal), None)
          case Empty() => (Full(newVal), None)
        }.flatMapF {
          case Some(next) => next.complete.provide(newVal).void
          case None => Rxn.unit
        }
      }.void)
    }
  }

  // F[_] API:

  final override def continuous: Stream[F, A] =
    Stream.repeatEval(this.get)

  final override def discrete: Stream[F, A] = {

    val acq: Axn[(Unique.Token, Ref[Listener[F, A]])] = {
      (Rxn.unique * underlying.get).flatMapF { case (tok, current) =>
        Ref[Listener[F, A]](Full(current)).flatMapF { ref =>
          val tup = (tok, ref)
          listeners.put.provide(tup).as(tup)
        }
      }
    }

    val rel: Unique.Token =#> Unit =
      listeners.del.void

    def nextElement(ref: Ref[Listener[F, A]]): F[A] = {
      val rxn = Promise[F, A] >>> ref.upd { (l, p) =>
        l match {
          case Full(a) =>
            (Empty(), F.monad.pure(a))
          case Empty() =>
            (Waiting(p), p.get)
          case Waiting(_) =>
            impossible("before the Promise is completed, Waiting is removed")
            // (see `notifyListeners`)
        }
      }
      F.monad.flatten(F.run(rxn, ()))
    }

    Stream.bracket(acquire = F.run(acq, ()))(release = { tokRef =>
      F.run(rel, tokRef._1)
    }).flatMap { tokRef =>
      Stream.repeatEval(nextElement(tokRef._2))
    }
  }

  final override def get: F[A] =
    underlying.unsafeInvisibleRead.run[F]

  final override def set(a: A): F[Unit] =
    this.rxn.upd[Any, Unit] { (_, _) => (a, ()) }.run[F]

  override def access: F[(A, A => F[Boolean])] =
    sys.error("TODO")

  override def tryUpdate(f: A => A): F[Boolean] =
    sys.error("TODO")

  override def tryModify[B](f: A => (A, B)): F[Option[B]] =
    sys.error("TODO")

  override def update(f: A => A): F[Unit] =
    sys.error("TODO")

  override def modify[B](f: A => (A, B)): F[B] =
    sys.error("TODO")

  override def tryModifyState[B](state: State[A, B]): F[Option[B]] =
    sys.error("TODO")

  override def modifyState[B](state: State[A, B]): F[B] =
    sys.error("TODO")
}
