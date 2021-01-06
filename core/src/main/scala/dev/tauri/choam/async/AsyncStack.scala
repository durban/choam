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

import cats.implicits._
import cats.effect.Async

import cats.data.NonEmptyChain

import kcas.Ref

import AsyncStack._

final class AsyncStack[A] private (ref: Ref[State[A]]) {

  val push: React[A, Unit] = ref.upd[A, Option[(Promise[A], A)]] { (st, a) =>
    st match {
      case Empty =>
        (Empty.addItem(a), None)
      case w @ Waiting(_) =>
        val (s, p) = w.removePromise
        (s, Some((p, a)))
      case l @ Lst(_) =>
        (l.addItem(a), None)
    }
  }.postCommit(React.computed {
    case None => React.unit
    case Some((p, a)) => p.tryComplete.lmap[Unit](_ => a).void
  }).discard

  // TODO: what happens when the popper cancels?
  def pop[F[_]](implicit rF: Reactive[F], aF: Async[F]): F[A] = for {
    newP <- Promise[A].run[F]
    res <- ref.modify2[Either[Promise[A], A]] {
      case Empty =>
        (Empty.addPromise(newP), Left(newP))
      case w @ Waiting(_) =>
        (w.addPromise(newP), Left(newP))
      case l @ Lst(_) =>
        val (s, a) = l.removeItem
        (s, Right(a))
    }.run[F]
    a <- res match {
      case Left(p) => p.get[F]
      case Right(a) => aF.pure(a)
    }
  } yield a
}

object AsyncStack {

  private sealed abstract class State[+A]

  private final case object Empty extends State[Nothing] {

    def addPromise[A](p: Promise[A]): Waiting[A] =
      Waiting(NonEmptyChain.one(p))

    def addItem[A](a: A): Lst[A] =
      Lst(NonEmptyChain.one(a))
  }

  private final case class Waiting[A](ps: NonEmptyChain[Promise[A]]) extends State[A] {

    def removePromise: (State[A], Promise[A]) = ps.uncons match { case (h, t) =>
      NonEmptyChain.fromChain(t) match {
        case Some(ps) => (Waiting(ps), h)
        case None => (Empty, h)
      }
    }

    def addPromise(p: Promise[A]): Waiting[A] =
      Waiting(this.ps :+ p)
  }

  private final case class Lst[A](as: NonEmptyChain[A]) extends State[A] {

    def removeItem: (State[A], A) = as.uncons match { case (h, t) =>
      NonEmptyChain.fromChain(t) match {
        case Some(as) => (Lst(as), h)
        case None => (Empty, h)
      }
    }

    def addItem(a: A): Lst[A] =
      Lst(a +: this.as)
  }

  def apply[A]: React[Unit, AsyncStack[A]] =
    React.delay(_ => new AsyncStack(Ref.mk(Empty)))
}
