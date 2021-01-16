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

import cats.{ ~>, Functor, Invariant }
import cats.syntax.all._
import cats.effect.Async

abstract class Promise[F[_], A] { self =>

  def complete: React[A, Boolean]

  def tryGet: React[Unit, Option[A]]

  def get: F[A]

  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Promise[F, B] = new Promise[F, B] {
    final override def complete: React[B, Boolean] =
      self.complete.lmap(g)
    final override def tryGet: React[Unit, Option[B]] =
      self.tryGet.map(_.map(f))
    final override def get: F[B] =
      self.get.map(f)
  }

  def mapK[G[_]](t: F ~> G): Promise[G, A] = new Promise[G, A] {
    final override def complete: React[A, Boolean] =
      self.complete
    final override def tryGet: React[Unit, Option[A]] =
      self.tryGet
    final override def get: G[A] =
      t(self.get)
  }
}

object Promise {

  def apply[F[_], A](implicit F: Reactive.Async[F]): React[Unit, Promise[F, A]] =
    F.promise[A]

  def forAsync[F[_], A](implicit rF: Reactive[F], F: Async[F]): React[Unit, Promise[F, A]] =
    React.delay(_ => new PromiseImpl[F, A](kcas.Ref.mk[State[A]](Waiting(Map.empty))))

  implicit def invariantFunctorForPromise[F[_] : Functor]: Invariant[Promise[F, *]] = new Invariant[Promise[F, *]] {
    override def imap[A, B](fa: Promise[F, A])(f: A => B)(g: B => A): Promise[F, B] =
      fa.imap(f)(g)
  }

  // TODO: try to optimize (maybe with `LongMap`?)
  private final class Id

  private sealed abstract class State[A]
  private final case class Waiting[A](cbs: Map[Id, A => Unit]) extends State[A]
  private final case class Done[A](a: A) extends State[A]

  private final class PromiseImpl[F[_], A](ref: kcas.Ref[State[A]])(implicit rF: Reactive[F], F: Async[F])
    extends Promise[F, A] {

    val complete: React[A, Boolean] = React.computed { a =>
      ref.invisibleRead.flatMap {
        case w @ Waiting(cbs) =>
          ref.cas(w, Done(a)).rmap(_ => true).postCommit(React.delay { _ =>
            cbs.valuesIterator.foreach(_(a))
          })
        case Done(_) =>
          React.ret(false)
      }
    }

    val tryGet: React[Unit, Option[A]] = {
      ref.getter.map {
        case Done(a) => Some(a)
        case Waiting(_) => None
      }
    }

    def get: F[A] = {
      ref.invisibleRead.run[F].flatMap {
        case Waiting(_) =>
          F.delay(new Id).flatMap { id =>
            F.async { cb =>
              F.uncancelable { poll =>
                val removeCallback = F.uncancelable { _ =>
                  ref.modify {
                    case Waiting(cbs) => Waiting(cbs - id)
                    case d @ Done(_) => d
                  }.discard.run[F]
                }
                insertCallback(id, cb).run[F].flatMap {
                  case None => F.pure(true)
                  case Some(a) => poll(F.delay { cb(Right(a)) }).as(false)
                }.map { cbWasInserted =>
                  // cancellation token:
                  if (cbWasInserted) Some(removeCallback)
                  else None
                }.onError { case _ => removeCallback }
              }
            }
          }
        case Done(a) =>
          F.pure(a)
      }
    }

    private def insertCallback(id: Id, cb: Either[Throwable, A] => Unit): React[Unit, Option[A]] = {
      val kv = (id, { (a: A) => cb(Right(a)) })
      ref.modify {
        case Waiting(cbs) => Waiting(cbs + kv)
        case d @ Done(_) => d
      }.map {
        case Waiting(_) => None
        case Done(a) => Some(a)
      }
    }
  }
}
