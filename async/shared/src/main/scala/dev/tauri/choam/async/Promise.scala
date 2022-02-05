/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.collection.immutable.LongMap

import cats.{ ~>, Functor, Invariant, Contravariant, Monad }
import cats.syntax.all._
import cats.effect.kernel.{ Async, Deferred, DeferredSink, DeferredSource }

sealed trait PromiseRead[F[_], A] { self =>

  def get: F[A]

  def tryGet: Axn[Option[A]]

  protected def rF: Reactive[F]

  final def map[B](f: A => B): PromiseRead[F, B] = new PromiseRead[F, B] {
    final override def rF =
      self.rF
    final override def get: F[B] =
      rF.monad.map(self.get)(f)
    final override def tryGet: Axn[Option[B]] =
      self.tryGet.map(_.map(f))
  }

  def mapK[G[_] : Monad](t: F ~> G): PromiseRead[G, A] = new PromiseRead[G, A] {
    final override def rF =
      self.rF.mapK(t)
    final override def get: G[A] =
      t(self.get)
    final override def tryGet: Axn[Option[A]] =
      self.tryGet
  }

  def toCats: DeferredSource[F, A] = new DeferredSource[F, A] {
    final override def get: F[A] =
      self.get
    final override def tryGet: F[Option[A]] =
      rF.run(self.tryGet, ())
  }
}

object PromiseRead {

  implicit def covariantFunctorForPromiseRead[F[_]]: Functor[PromiseRead[F, *]] = {
    new Functor[PromiseRead[F, *]] {
      final override def map[A, B](p: PromiseRead[F, A])(f: A => B): PromiseRead[F, B] =
        p.map(f)
    }
  }
}

sealed trait PromiseWrite[A] { self =>

  def complete: A =#> Boolean

  final def contramap[B](f: B => A): PromiseWrite[B] = new PromiseWrite[B] {
    final override def complete: Rxn[B, Boolean] =
      self.complete.lmap(f)
  }

  final def toCatsIn[F[_]](implicit F: Reactive[F]): DeferredSink[F, A] = new DeferredSink[F, A] {
    final override def complete(a: A): F[Boolean] =
      self.complete[F](a)
  }
}

object PromiseWrite {

  implicit def contravariantFunctorForPromiseWrite: Contravariant[PromiseWrite] = {
    new Contravariant[PromiseWrite] {
      final override def contramap[A, B](p: PromiseWrite[A])(f: B => A): PromiseWrite[B] =
        p.contramap(f)
    }
  }
}

sealed abstract class Promise[F[_], A] extends PromiseRead[F, A] with PromiseWrite[A] { self =>

  final def imap[B](f: A => B)(g: B => A): Promise[F, B] = new Promise[F, B] {
    final override def rF =
      self.rF
    final override def complete: Rxn[B, Boolean] =
      self.complete.lmap(g)
    final override def tryGet: Axn[Option[B]] =
      self.tryGet.map(_.map(f))
    final override def get: F[B] =
      rF.monad.map(self.get)(f)
  }

  final override def mapK[G[_] : Monad](t: F ~> G): Promise[G, A] = new Promise[G, A] {
    final override def rF =
      self.rF.mapK(t)
    final override def complete: Rxn[A, Boolean] =
      self.complete
    final override def tryGet: Axn[Option[A]] =
      self.tryGet
    final override def get: G[A] =
      t(self.get)
  }

  final override def toCats: Deferred[F, A] = new Deferred[F, A] {
    final override def get: F[A] =
      self.get
    final override def tryGet: F[Option[A]] =
      rF.run(self.tryGet, ())
    final override def complete(a: A): F[Boolean] =
      rF.run(self.complete, a)
  }
}

object Promise {

  def apply[F[_], A](implicit F: AsyncReactive[F]): Axn[Promise[F, A]] =
    F.promise[A]

  def forAsync[F[_], A](implicit rF: Reactive[F], F: Async[F]): Axn[Promise[F, A]] =
    Rxn.unsafe.delay(_ => new PromiseImpl[F, A](Ref.unsafe[State[A]](Waiting(LongMap.empty, 0L))))

  implicit def invariantFunctorForPromise[F[_]]: Invariant[Promise[F, *]] = new Invariant[Promise[F, *]] {
    final override def imap[A, B](fa: Promise[F, A])(f: A => B)(g: B => A): Promise[F, B] =
      fa.imap(f)(g)
  }

  private sealed abstract class State[A] extends Product with Serializable

  /**
   * We store the callbacks in a `LongMap`, because apparently
   * it is faster this way. Benchmarks show that it is measurably
   * faster if there are a lot of callbacks, and not slower
   * even if there are only a few callbacks.
   *
   * The idea is from here: https://github.com/typelevel/cats-effect/pull/1128.
   */
  private final case class Waiting[A](cbs: LongMap[A => Unit], nextId: Long) extends State[A]

  private final case class Done[A](a: A) extends State[A]

  private final class PromiseImpl[F[_], A](ref: Ref[State[A]])(implicit _rF: Reactive[F], F: Async[F])
    extends Promise[F, A] {

    final override def rF =
      _rF

    def complete: A =#> Boolean = {
      ref.updWith[A, Boolean] { (state, a) =>
        state match {
          case Waiting(cbs, _) =>
            Rxn.postCommit[Any](Rxn.unsafe.delay { _ =>
              cbs.valuesIterator.foreach(_(a))
            }).as((Done(a), true))
          case d @ Done(_) =>
            Rxn.pure((d, false))
        }
      }
    }

    def tryGet: Axn[Option[A]] = {
      ref.get.map {
        case Done(a) => Some(a)
        case Waiting(_, _) => None
      }
    }

    final def get: F[A] = {
      ref.unsafeDirectRead.run[F].flatMap {
        case Waiting(_, _) =>
          F.async { cb =>
            F.uncancelable { poll =>
              insertCallback(cb).run[F].flatMap {
                case Left(id) =>
                  F.pure(Some(removeCallback(id)))
                case Right(a) =>
                  poll(F.delay { cb(Right(a)) }).as(None)
              }
            }
          }
        case Done(a) =>
          F.pure(a)
      }
    }

    private[this] final def insertCallback(cb: Either[Throwable, A] => Unit): Axn[Either[Long, A]] = {
      val rcb = { (a: A) => cb(Right(a)) }
      ref.getAndUpdate {
        case Waiting(cbs, nid) => Waiting(cbs + (nid -> rcb), nid + 1)
        case d @ Done(_) => d
      }.map {
        case Waiting(_, nid) => Left(nid)
        case Done(a) => Right(a)
      }
    }

    private[this] final def removeCallback(id: Long): F[Unit] = {
      F.uncancelable { _ =>
        ref.update {
          case Waiting(cbs, nid) => Waiting(cbs - id, nid)
          case d @ Done(_) => d
        }.run[F]
      }
    }
  }
}
