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
package async

import scala.collection.immutable.LongMap

import cats.{ Functor, Invariant, Contravariant }
import cats.syntax.all._
import cats.effect.kernel.{ Deferred, DeferredSink, DeferredSource }

sealed trait PromiseRead[A] { self =>
  def get[F[_]](implicit F: AsyncReactive[F]): F[A]
  def tryGet: Axn[Option[A]]
  def map[B](f: A => B): PromiseRead[B]
  def toCats[F[_]](implicit F: AsyncReactive[F]): DeferredSource[F, A]
}

object PromiseRead {

  implicit final def covariantFunctorForPromiseRead: Functor[PromiseRead] =
    _covariantFunctorForPromiseRead

  private[this] val _covariantFunctorForPromiseRead: Functor[PromiseRead] = {
    new Functor[PromiseRead] {
      final override def map[A, B](p: PromiseRead[A])(f: A => B): PromiseRead[B] =
        p.map(f)
    }
  }
}

sealed trait PromiseWrite[A] { self =>

  def complete: A =#> Boolean

  final def contramap[B](f: B => A): PromiseWrite[B] = new PromiseWrite[B] {
    final override def complete: Rxn[B, Boolean] =
      self.complete.contramap(f)
  }

  final def toCats[F[_]](implicit F: Reactive[F]): DeferredSink[F, A] = new DeferredSink[F, A] {
    final override def complete(a: A): F[Boolean] =
      self.complete[F](a)
  }
}

object PromiseWrite {

  private[this] val _contravariantFunctorForPromiseWrite: Contravariant[PromiseWrite] = {
    new Contravariant[PromiseWrite] {
      final override def contramap[A, B](p: PromiseWrite[A])(f: B => A): PromiseWrite[B] =
        p.contramap(f)
    }
  }

  implicit def contravariantFunctorForPromiseWrite: Contravariant[PromiseWrite] =
    _contravariantFunctorForPromiseWrite
}

// TODO: Can we make a `Promise[A]`?
// TODO: (With `def get[F[_]](implicit F: AsyncReactive[F]): F[A]`)
// TODO: If yes, that would make it possible to have
// TODO: `Promise.padded` and `Promise.unpadded` without bloating
// TODO: the AsyncReactive typeclass.

sealed trait Promise[A] extends PromiseRead[A] with PromiseWrite[A] {
  def imap[B](f: A => B)(g: B => A): Promise[B]
  override def toCats[F[_]](implicit F: AsyncReactive[F]): Deferred[F, A]
}

object Promise {

  // TODO: there should be a way to make an unpadded Promise
  final def apply[A]: Axn[Promise[A]] = {
    Axn.unsafe.delayContext { ctx =>
      new PromiseImpl[A](Ref.unsafePadded[State[A]](Waiting(LongMap.empty, 0L), ctx.refIdGen))
    }
  }

  implicit final def invariantFunctorForPromise[F[_]]: Invariant[Promise] =
    _invariantFunctorForPromise

  private[this] val _invariantFunctorForPromise = new Invariant[Promise] {
    final override def imap[A, B](fa: Promise[A])(f: A => B)(g: B => A): Promise[B] =
      fa.imap(f)(g)
  }

  private[this] sealed abstract class State[A] extends Product with Serializable

  /**
   * We store the callbacks in a `LongMap`, because apparently
   * it is faster this way. Benchmarks show that it is measurably
   * faster if there are a lot of callbacks, and not slower
   * even if there are only a few callbacks.
   *
   * The idea is from here: https://github.com/typelevel/cats-effect/pull/1128.
   */
  private[this] final case class Waiting[A](cbs: LongMap[A => Unit], nextId: Long) extends State[A]

  private[this] final case class Done[A](a: A) extends State[A]

  private[this] abstract class PromiseReadImpl[A]
    extends PromiseRead[A] { self =>

    final def map[B](f: A => B): PromiseRead[B] = new PromiseReadImpl[B] {
      final override def get[F[_]](implicit F: AsyncReactive[F]): F[B] =
        F.monad.map(self.get)(f)
      final override def tryGet: Axn[Option[B]] =
        self.tryGet.map(_.map(f))
    }

    def toCats[F[_]](implicit F: AsyncReactive[F]): DeferredSource[F, A] = new DeferredSource[F, A] {
      final override def get: F[A] =
        self.get
      final override def tryGet: F[Option[A]] =
        F.run(self.tryGet)
    }
  }

  private[this] abstract class PromiseImplBase[A]
    extends PromiseReadImpl[A]
    with Promise[A] { self =>

    final def imap[B](f: A => B)(g: B => A): Promise[B] = new PromiseImplBase[B] {
      final override def complete: Rxn[B, Boolean] =
        self.complete.lmap(g)
      final override def tryGet: Axn[Option[B]] =
        self.tryGet.map(_.map(f))
      final override def get[F[_]](implicit F: AsyncReactive[F]): F[B] =
        F.monad.map(self.get)(f)
    }

    final override def toCats[F[_]](implicit F: AsyncReactive[F]): Deferred[F, A] = new Deferred[F, A] {
      final override def get: F[A] =
        self.get
      final override def tryGet: F[Option[A]] =
        F.run(self.tryGet)
      final override def complete(a: A): F[Boolean] =
        F.apply(self.complete, a)
    }
  }

  /**
   * Abstract base class for a minimal implementation of `Promise`.
   */
  private[this] abstract class AbstractPromise[A] extends PromiseImplBase[A] {

    def complete: A =#> Boolean

    def tryGet: Axn[Option[A]]

    def get[F[_]](implicit F: AsyncReactive[F]): F[A]
  }

  private[this] final class PromiseImpl[A](
    ref: Ref[State[A]]
  ) extends AbstractPromise[A] {

    final def complete: A =#> Boolean = {
      ref.updWith[A, Boolean] { (state, a) =>
        state match {
          case Waiting(cbs, _) =>
            Rxn.postCommit[Any](Axn.unsafe.delay {
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

    final def get[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      F.monad.flatMap(ref.unsafeDirectRead.run[F]) {
        case Waiting(_, _) =>
          F.asyncInst.asyncCheckAttempt { cb =>
            F.monad.map(insertCallback(cb).run[F]) {
              case Left(id) =>
                Left(Some(removeCallback(id)))
              case Right(a) =>
                Right(a)
            }
          }
        case Done(a) =>
          F.monad.pure(a)
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

    private[this] final def removeCallback[F[_]](id: Long)(implicit F: AsyncReactive[F]): F[Unit] = {
      ref.update {
        case Waiting(cbs, nid) => Waiting(cbs - id, nid)
        case d @ Done(_) => d
      }.run[F]
    }
  }
}
