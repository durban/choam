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
import cats.effect.kernel.{ Deferred, DeferredSource }

import core.{ Rxn, Ref, AsyncReactive }
import Ref.AllocationStrategy

sealed trait Promise[A] extends Promise.Get[A] with Promise.Complete[A] {

  def imap[B](f: A => B)(g: B => A): Promise[B]

  def toCats[F[_]](implicit F: AsyncReactive[F]): Deferred[F, A]
}

object Promise {

  sealed trait Get[+A] { self =>

    /**
     * Note: this is not called `get`, because a more convenient version
     * (with one type parameter) is available as an extension method `get`.
     */
    def getF[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA]

    def tryGet: Rxn[Option[A]]

    def map[B](f: A => B): Promise.Get[B]
  }

  object Get {

    implicit final def covariantFunctorForPromiseGet: Functor[Promise.Get] =
      _covariantFunctorForPromiseGet

    private[this] val _covariantFunctorForPromiseGet: Functor[Promise.Get] = {
      new Functor[Promise.Get] {
        final override def map[A, B](p: Promise.Get[A])(f: A => B): Promise.Get[B] =
          p.map(f)
      }
    }

    implicit final class InvariantSyntax[A](private val self: Promise.Get[A]) extends AnyVal {
      final def get[F[_]](implicit F: AsyncReactive[F]): F[A] =
        self.getF[F, A](using F)
    }
  }

  sealed trait Complete[-A] { self =>

    def complete(a: A): Rxn[Boolean]

    private[choam] def unsafeComplete(a: A)(implicit ir: unsafe.InRxn2): Boolean

    final def contramap[B](f: B => A): Promise.Complete[B] = new Promise.Complete[B] {
      final override def complete(b: B): Rxn[Boolean] =
        self.complete(f(b))
      final override def unsafeComplete(b: B)(implicit ir: unsafe.InRxn2): Boolean =
        self.unsafeComplete(f(b))
    }
  }

  object Complete {

    implicit final def contravariantFunctorForPromiseComplete: Contravariant[Promise.Complete] =
      _contravariantFunctorForPromiseComplete

    private[this] val _contravariantFunctorForPromiseComplete: Contravariant[Promise.Complete] = {
      new Contravariant[Promise.Complete] {
        final override def contramap[A, B](p: Promise.Complete[A])(f: B => A): Promise.Complete[B] =
          p.contramap(f)
      }
    }
  }

  final def apply[A]: Rxn[Promise[A]] =
    apply[A](AllocationStrategy.Default)

  final def apply[A](str: AllocationStrategy): Rxn[Promise[A]] = {
    Rxn.unsafe.delayContext { ctx =>
      new PromiseImpl[A](Ref.unsafe[State[A]](Waiting.empty, str, ctx.refIdGen))
    }
  }

  private[choam] final def unsafeNew[A](str: AllocationStrategy = AllocationStrategy.Default)(implicit ir: unsafe.InRxn): Promise[A] = {
    val ctx = ir.currentContext()
    new PromiseImpl[A](Ref.unsafe[State[A]](Waiting.empty, str, ctx.refIdGen))
  }

  implicit final def invariantFunctorForPromise: Invariant[Promise] =
    _invariantFunctorForPromise

  private[this] val _invariantFunctorForPromise = new Invariant[Promise] {
    final override def imap[A, B](fa: Promise[A])(f: A => B)(g: B => A): Promise[B] =
      fa.imap(f)(g)
  }

  private[this] sealed abstract class State[A]

  private[this] sealed trait InsertRes[+A]

  /**
   * We store the callbacks in a `LongMap`, because apparently
   * it is faster this way. Benchmarks show that it is measurably
   * faster if there are a lot of callbacks, and not slower
   * even if there are only a few callbacks.
   *
   * The idea is from here: https://github.com/typelevel/cats-effect/pull/1128.
   */
  private[this] final class Waiting[A](
    val cbs: LongMap[Right[Throwable, A] => Unit],
    val nextId: Long
  ) extends State[A]

  private[this] final object Waiting {

    private[this] val _empty: Waiting[Any] =
      new Waiting(LongMap.empty, 0L)

    final def empty[A]: Waiting[A] =
      _empty.asInstanceOf[Waiting[A]]
  }

  private[this] final class Done[A](val a: A) extends State[A] with InsertRes[A]

  private[this] final class CancelId(val id: Long) extends InsertRes[Nothing]

  private[this] abstract class PromiseReadImpl[A]
    extends Promise.Get[A] { self =>

    final def map[B](f: A => B): Promise.Get[B] = new PromiseReadImpl[B] {
      final override def getF[F[_], BB >: B](implicit F: AsyncReactive[F]): F[BB] =
        F.monad.map(self.get)(f)
      final override def tryGet: Rxn[Option[B]] =
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
      final override def complete(b: B): Rxn[Boolean] =
        self.complete(g(b))
      final override def unsafeComplete(b: B)(implicit ir: unsafe.InRxn2): Boolean =
        self.unsafeComplete(g(b))
      final override def tryGet: Rxn[Option[B]] =
        self.tryGet.map(_.map(f))
      final override def getF[F[_], BB >: B](implicit F: AsyncReactive[F]): F[BB] =
        F.monad.map(self.get)(f)
    }

    final override def toCats[F[_]](implicit F: AsyncReactive[F]): Deferred[F, A] = new Deferred[F, A] {
      final override def get: F[A] =
        self.get
      final override def tryGet: F[Option[A]] =
        F.run(self.tryGet)
      final override def complete(a: A): F[Boolean] =
        F.apply(self.complete(a))
    }
  }

  private[this] final class PromiseImpl[A](
    ref: Ref[State[A]]
  ) extends PromiseImplBase[A] {

    private[this] final def callCbs(cbs: LongMap[Right[Throwable, A] => Unit], a: A): Rxn[Unit] = {
      Rxn.unsafe.delay {
        val ra = Right(a)
        val itr = cbs.valuesIterator
        while (itr.hasNext) {
          itr.next()(ra)
        }
      }
    }

    final override def complete(a: A): Rxn[Boolean] = {
      ref.modify { state =>
        state match {
          case w: Waiting[_] =>
            (new Done(a), w.cbs)
          case d: Done[_] =>
            (d, null)
        }
      }.flatMap { cbs =>
        if (cbs ne null) {
          Rxn.true_.postCommit(callCbs(cbs, a))
        } else {
          Rxn.false_
        }
      }
    }

    private[choam] final override def unsafeComplete(a: A)(implicit ir: unsafe.InRxn2): Boolean = {
      import unsafe.{ readRef, writeRef, addPostCommit }
      val state = readRef(ref)
      state match {
        case w: Waiting[_] =>
          writeRef(ref, new Done(a))
          addPostCommit(callCbs(w.cbs, a))
          true
        case _: Done[_] =>
          false
      }
    }

    final override def tryGet: Rxn[Option[A]] = {
      ref.get.map {
        case d: Done[_] => Some(d.a)
        case _: Waiting[_] => None
      }
    }

    final override def getF[F[_], AA >: A](implicit F: AsyncReactive[F]): F[AA] = {
      F.monad.flatMap(Rxn.unsafe.directRead(ref).run[F]) {
        case _: Waiting[_] =>
          F.asyncInst.asyncCheckAttempt { cb =>
            F.monad.map(insertCallback(cb).run[F]) {
              case cid: CancelId =>
                Left(Some(removeCallback(cid.id)))
              case d: Done[_] =>
                Right(d.a)
            }
          }
        case d: Done[_] =>
          F.monad.pure(d.a)
      }
    }

    private[this] final def insertCallback(cb: Either[Throwable, A] => Unit): Rxn[InsertRes[A]] = {
      ref.getAndUpdate {
        case w: Waiting[_] =>
          val nid = w.nextId
          new Waiting(w.cbs.updated(nid, cb), nid + 1)
        case d: Done[_] =>
          d
      }.map {
        case w: Waiting[_] => new CancelId(w.nextId)
        case d: Done[_] => d
      }
    }

    private[this] final def removeCallback[F[_]](id: Long)(implicit F: AsyncReactive[F]): F[Unit] = {
      ref.update {
        case w: Waiting[_] => new Waiting(w.cbs.removed(id), w.nextId)
        case d: Done[_] => d
      }.run[F]
    }
  }
}
