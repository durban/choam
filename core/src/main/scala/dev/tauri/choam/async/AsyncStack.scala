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

import cats.data.Chain

import kcas.Ref

import AsyncStack._

final class AsyncStack[A](ref: Ref[State[A]]) {

  private[this] val head: Ref[State[A]] =
    ref

  val push: React[A, Unit] = head.upd[A, Option[(Promise[A], A)]] { (st, a) =>
    st match {
      case Empty =>
        (Lst(a, TreiberStack.End), None)
      case Waiting(p, c) =>
        c.uncons match {
          case None =>
            (Empty, Some((p, a)))
          case Some((h, t)) =>
            (Waiting(h, t), Some((p, a)))
        }
      case Lst(h, t) =>
        (Lst(a, TreiberStack.Cons(h, t)), None)
    }
  }.postCommit(React.computed {
    case None => React.unit
    case Some((p, a)) => p.tryComplete.lmap[Unit](_ => a).void
  }).discard

  def pop[F[_]](implicit rF: Reactive[F], aF: Async[F]): F[A] = for {
    newP <- Promise[A].run[F]
    res <- head.modify2[Either[Promise[A], A]] {
      case Empty =>
        (Waiting(newP, Chain.empty), Left(newP))
      case Waiting(p, c) =>
        (Waiting(p, c :+ newP), Left(newP))
      case Lst(h1, TreiberStack.Cons(h2, t)) =>
        (Lst(h2, t), Right(h1))
      case Lst(h, TreiberStack.End) =>
        (Empty, Right(h))
    }.run[F]
    a <- res match {
      case Left(p) => p.get[F]
      case Right(a) => aF.pure(a)
    }
  } yield a
}

object AsyncStack {

  private sealed abstract class State[+A]
  private final case object Empty extends State[Nothing]
  private final case class Waiting[A](p: Promise[A], t: Chain[Promise[A]]) extends State[A]
  private final case class Lst[A](h: A, t: TreiberStack.Lst[A]) extends State[A]

  def apply[A]: React[Unit, AsyncStack[A]] =
    React.delay(_ => new AsyncStack(Ref.mk(Empty)))
}
