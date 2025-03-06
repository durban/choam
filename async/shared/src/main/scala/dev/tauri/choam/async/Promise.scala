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
import cats.effect.kernel.{ Deferred, DeferredSink, DeferredSource }

import Ref.AllocationStrategy

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

  def complete0: A =#> Boolean

  def complete1(a: A): Axn[Boolean]

  final def contramap[B](f: B => A): PromiseWrite[B] = new PromiseWrite[B] {
    final override def complete0: Rxn[B, Boolean] =
      self.complete0.contramap(f)
    final override def complete1(b: B): Axn[Boolean] =
      self.complete1(f(b))
  }

  final def toCats[F[_]](implicit F: Reactive[F]): DeferredSink[F, A] = new DeferredSink[F, A] {
    final override def complete(a: A): F[Boolean] =
      self.complete0[F](a)
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

  final def apply[A]: Axn[Promise[A]] =
    apply[A](AllocationStrategy.Default)

  final def apply[A](str: AllocationStrategy): Axn[Promise[A]] = {
    Axn.unsafe.delayContext { ctx =>
      new PromiseImpl[A](Ref.unsafe[State[A]](Waiting(LongMap.empty, 0L), str, ctx.refIdGen))
    }
  }

  implicit final def invariantFunctorForPromise[F[_]]: Invariant[Promise] =
    _invariantFunctorForPromise

  private[this] val _invariantFunctorForPromise = new Invariant[Promise] {
    final override def imap[A, B](fa: Promise[A])(f: A => B)(g: B => A): Promise[B] =
      fa.imap(f)(g)
  }

  private[this] sealed abstract class State[A] extends Product with Serializable

  private[this] sealed trait InsertRes[+A] extends Product with Serializable

  /**
   * We store the callbacks in a `LongMap`, because apparently
   * it is faster this way. Benchmarks show that it is measurably
   * faster if there are a lot of callbacks, and not slower
   * even if there are only a few callbacks.
   *
   * The idea is from here: https://github.com/typelevel/cats-effect/pull/1128.
   */
  private[this] final case class Waiting[A](cbs: LongMap[Right[Throwable, A] => Unit], nextId: Long) extends State[A]

  private[this] final case class Done[A](a: A) extends State[A] with InsertRes[A]

  private[this] final case class CancelId(id: Long) extends InsertRes[Nothing]

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
      final override def complete0: Rxn[B, Boolean] =
        self.complete0.contramap(g)
      final override def complete1(b: B): Axn[Boolean] =
        self.complete1(g(b))
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
        F.apply(self.complete0, a)
    }
  }

  private[this] final class PromiseImpl[A](
    ref: Ref[State[A]]
  ) extends PromiseImplBase[A] {

    private[this] final def callCbs(cbs: LongMap[Right[Throwable, A] => Unit], a: A): Axn[Unit] = {
      Axn.unsafe.delay {
        val ra = Right(a)
        val itr = cbs.valuesIterator
        while (itr.hasNext) {
          itr.next()(ra)
        }
      }
    }

    final override def complete0: A =#> Boolean = {
      ref.updWith[A, Boolean] { (state, a) =>
        state match {
          case Waiting(cbs, _) =>
            Rxn.postCommit[Any](callCbs(cbs, a)).as((Done(a), true))
          case d @ Done(_) =>
            Rxn.pure((d, false))
        }
      }
    }

    final override def complete1(a: A): Axn[Boolean] = {
      ref.upd[Any, LongMap[Right[Throwable, A] => Unit]] { (state, _) =>
        state match {
          case Waiting(cbs, _) =>
            (Done(a), cbs)
          case d @ Done(_) =>
            (d, null)
        }
      }.flatMapF { cbs =>
        if (cbs ne null) {
          Rxn.pure(true).postCommit(callCbs(cbs, a))
        } else {
          Rxn.pure(false)
        }
      }
    }

    final override def tryGet: Axn[Option[A]] = {
      ref.get.map {
        case Done(a) => Some(a)
        case Waiting(_, _) => None
      }
    }

    final override def get[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      F.monad.flatMap(ref.unsafeDirectRead.run[F]) {
        case Waiting(_, _) =>
          F.asyncInst.asyncCheckAttempt { cb =>
            F.monad.map(insertCallback(cb).run[F]) {
              case CancelId(id) =>
                Left(Some(removeCallback(id)))
              case Done(a) =>
                Right(a)
            }
          }
        case Done(a) =>
          F.monad.pure(a)
      }
    }

    private[this] final def insertCallback(cb: Either[Throwable, A] => Unit): Axn[InsertRes[A]] = {
      ref.getAndUpdate {
        case Waiting(cbs, nid) => Waiting(cbs.updated(nid, cb), nid + 1)
        case d @ Done(_) => d
      }.map {
        case Waiting(_, nid) => CancelId(nid)
        case d @ Done(a) => d
      }
    }

    private[this] final def removeCallback[F[_]](id: Long)(implicit F: AsyncReactive[F]): F[Unit] = {
      ref.update {
        case Waiting(cbs, nid) => Waiting(cbs.removed(id), nid)
        case d @ Done(_) => d
      }.run[F]
    }
  }
}
