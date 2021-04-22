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
package async

import cats.data.NonEmptyChain
import cats.data.Ior
import cats.implicits._

import AsyncStack1._

private[choam] final class AsyncStack1[F[_], A] private (ref: Ref[State[F, A]])
  extends AsyncStack[F, A] {

  override val push: React[A, Unit] = ref.upd[A, Option[(Promise[F, A], A)]] { (st, a) =>
    st match {
      case e @ Empty() =>
        (e.addItem(a), None)
      case w @ Waiting(_) =>
        val (s, p) = w.removePromise
        (s, Some((p, a)))
      case l @ Lst(_) =>
        (l.addItem(a), None)
    }
  }.flatMap {
    case None => React.unit[A]
    case Some((p, a)) => p.complete.provide(a).void
  }

  override def pop(implicit F: Reactive.Async[F]): F[A] = {
    F.monadCancel.flatMap(F.promise[A].run[F]) { newP =>
      val acq = ref.modify[Either[Promise[F, A], A]] {
        case e @ Empty() =>
          (e.addPromise(newP), Left(newP))
        case w @ Waiting(_) =>
          (w.addPromise(newP), Left(newP))
        case l @ Lst(_) =>
          val (s, a) = l.removeItem
          (s, Right(a))
      }.run[F]
      val rel: (Either[Promise[F, A], A] => F[Unit]) = {
        case Left(p) => ref.update { state =>
          state.cancelPromise(p)._1
        }.run[F]
        case Right(_) => F.monadCancel.unit
      }
      F.monadCancel.bracket(acquire = acq)(use = {
        case Left(p) => p.get
        case Right(a) => F.monadCancel.pure(a)
      })(release = rel)
    }
  }
}

private[choam] object AsyncStack1 {

  def apply[F[_], A]: React[Unit, AsyncStack[F, A]] =
    React.delay(_ => new AsyncStack1(Ref.unsafe(Empty())))

  private sealed abstract class State[F[_], A] {
    def cancelPromise[B >: A](p: Promise[F, B]): (State[F, A], Boolean)
  }

  private final case class Empty[F[_], A]() extends State[F, A] {

    def addPromise(p: Promise[F, A]): Waiting[F, A] =
      Waiting(NonEmptyChain.one(p))

    def addItem(a: A): Lst[F, A] =
      Lst(NonEmptyChain.one(a))

    def cancelPromise[B >: A](p: Promise[F, B]): (State[F, A], Boolean) =
      (Empty(), false)
  }

  private final case class Waiting[F[_], A](ps: NonEmptyChain[Promise[F, A]]) extends State[F, A] {

    def removePromise: (State[F, A], Promise[F, A]) = ps.uncons match { case (h, t) =>
      NonEmptyChain.fromChain(t) match {
        case Some(ps) => (Waiting(ps), h)
        case None => (Empty(), h)
      }
    }

    def addPromise(p: Promise[F, A]): Waiting[F, A] =
      Waiting(this.ps :+ p)

    def cancelPromise[B >: A](p: Promise[F, B]): (State[F, A], Boolean) = {
      this.ps.nonEmptyPartition { p2 =>
        if (p2 eq p) Left(p2)
        else Right(p2)
      } match {
        case Ior.Both(_, ps) => (Waiting(NonEmptyChain.fromNonEmptyList(ps)), true)
        case Ior.Left(_) => (Empty(), true)
        case Ior.Right(ps) => (Waiting(NonEmptyChain.fromNonEmptyList(ps)), false)
      }
    }
  }

  private final case class Lst[F[_], A](as: NonEmptyChain[A]) extends State[F, A] {

    def removeItem: (State[F, A], A) = as.uncons match { case (h, t) =>
      NonEmptyChain.fromChain(t) match {
        case Some(as) => (Lst(as), h)
        case None => (Empty(), h)
      }
    }

    def addItem(a: A): Lst[F, A] =
      Lst(a +: this.as)

    def cancelPromise[B >: A](p: Promise[F, B]): (Lst[F, A], Boolean) =
      (this, false)
  }
}
