/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

trait PromiseRead[F[_], A] { self =>
  def get: F[A]
  def tryGet: Axn[Option[A]]
  def map[B](f: A => B): PromiseRead[F, B]
  def mapK[G[_] : Monad](t: F ~> G): PromiseRead[G, A]
  def toCats: DeferredSource[F, A]
}

object PromiseRead {

  implicit def covariantFunctorForPromiseRead[F[_]]: Functor[PromiseRead[F, *]] = {
    new Functor[PromiseRead[F, *]] {
      final override def map[A, B](p: PromiseRead[F, A])(f: A => B): PromiseRead[F, B] =
        p.map(f)
    }
  }
}

trait PromiseWrite[A] { self =>

  def complete: A =#> Boolean

  final def contramap[B](f: B => A): PromiseWrite[B] = new PromiseWrite[B] {
    final override def complete: Rxn[B, Boolean] =
      self.complete.contramap(f)
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

trait Promise[F[_], A] extends PromiseRead[F, A] with PromiseWrite[A] {
  def imap[B](f: A => B)(g: B => A): Promise[F, B]
  override def mapK[G[_] : Monad](t: F ~> G): Promise[G, A]
  override def toCats: Deferred[F, A]
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

  private[this] abstract class PromiseReadImpl[F[_], A]
    extends PromiseRead[F, A] { self =>

    protected def rF: Reactive[F]

    final def map[B](f: A => B): PromiseRead[F, B] = new PromiseReadImpl[F, B] {
      final override def rF =
        self.rF
      final override def get: F[B] =
        self.rF.monad.map(self.get)(f)
      final override def tryGet: Axn[Option[B]] =
        self.tryGet.map(_.map(f))
    }

    def mapK[G[_] : Monad](t: F ~> G): PromiseRead[G, A] = new PromiseReadImpl[G, A] {
      final def rF =
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
        rF.run(self.tryGet)
    }
  }

  private[Promise] abstract class PromiseImplBase[F[_], A]
    extends PromiseReadImpl[F, A]
    with Promise[F, A] { self =>

    final def imap[B](f: A => B)(g: B => A): Promise[F, B] = new PromiseImplBase[F, B] {
      final override def rF =
        self.rF
      final override def complete: Rxn[B, Boolean] =
        self.complete.lmap(g)
      final override def tryGet: Axn[Option[B]] =
        self.tryGet.map(_.map(f))
      final override def get: F[B] =
        rF.monad.map(self.get)(f)
    }

    final override def mapK[G[_] : Monad](t: F ~> G): Promise[G, A] = new PromiseImplBase[G, A] {
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
        rF.run(self.tryGet)
      final override def complete(a: A): F[Boolean] =
        rF.apply(self.complete, a)
    }
  }

  /**
   * Abstract base class for a minimal implementation of `Promise`.
   */
  abstract class AbstractPromise[F[_], A](
  )(implicit override val rF: Reactive[F]) extends PromiseImplBase[F, A] {

    def complete: A =#> Boolean

    def tryGet: Axn[Option[A]]

    def get: F[A]
  }

  private[this] final class PromiseImpl[F[_], A](
    ref: Ref[State[A]]
  )(implicit _rF: Reactive[F], F: Async[F]) extends AbstractPromise[F, A]()(_rF) {

    final def complete: A =#> Boolean = {
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

    final def tryGet: Axn[Option[A]] = {
      ref.get.map {
        case Done(a) => Some(a)
        case Waiting(_, _) => None
      }
    }

    final def get: F[A] = {
      ref.unsafeDirectRead.run[F].flatMap {
        case Waiting(_, _) =>
          asyncCheckAttempt { cb =>
            insertCallback(cb).run[F].map {
              case Left(id) =>
                Left(Some(removeCallback(id)))
              case Right(a) =>
                Right(a)
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
      ref.update {
        case Waiting(cbs, nid) => Waiting(cbs - id, nid)
        case d @ Done(_) => d
      }.run[F]
    }
  }
}
