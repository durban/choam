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

import scala.collection.immutable.LongMap

import cats.{ ~>, Functor, Invariant, Contravariant }
import cats.syntax.all._
import cats.effect.kernel.{ Async, Deferred, DeferredSink, DeferredSource }

sealed trait PromiseRead[F[_], A] { self =>

  def get: F[A]

  def tryGet: Axn[Option[A]]

  final def map[B](f: A => B)(implicit F: Functor[F]): PromiseRead[F, B] = new PromiseRead[F, B] {
    final override def get: F[B] =
      self.get.map(f)
    final override def tryGet: Axn[Option[B]] =
      self.tryGet.map(_.map(f))
  }

  def mapK[G[_]](t: F ~> G): PromiseRead[G, A] = new PromiseRead[G, A] {
    final override def get: G[A] =
      t(self.get)
    final override def tryGet: Axn[Option[A]] =
      self.tryGet
  }

  def toCats(implicit F: Reactive.Async[F]): DeferredSource[F, A] = new DeferredSource[F, A] {
    final override def get: F[A] =
      self.get
    final override def tryGet: F[Option[A]] =
      self.tryGet.run[F]
  }
}

object PromiseRead {

  implicit def covariantFunctorForPromiseRead[F[_] : Functor]: Functor[PromiseRead[F, *]] = {
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

  def toCatsIn[F[_]](implicit F: Reactive.Async[F]): DeferredSink[F, A] = new DeferredSink[F, A] {
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

  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Promise[F, B] = new Promise[F, B] {
    final override def complete: Rxn[B, Boolean] =
      self.complete.lmap(g)
    final override def tryGet: Axn[Option[B]] =
      self.tryGet.map(_.map(f))
    final override def get: F[B] =
      self.get.map(f)
  }

  override def mapK[G[_]](t: F ~> G): Promise[G, A] = new Promise[G, A] {
    final override def complete: Rxn[A, Boolean] =
      self.complete
    final override def tryGet: Axn[Option[A]] =
      self.tryGet
    final override def get: G[A] =
      t(self.get)
  }

  override def toCats(implicit F: Reactive.Async[F]): Deferred[F, A] = new Deferred[F, A] {
    final override def get: F[A] =
      self.get
    final override def tryGet: F[Option[A]] =
      self.tryGet.run[F]
    final override def complete(a: A): F[Boolean] =
      self.complete[F](a)
  }
}

object Promise {

  def apply[F[_], A](implicit F: Reactive.Async[F]): Axn[Promise[F, A]] =
    F.promise[A]

  @deprecated("old, slower implementation", since = "2021-02-08")
  def slow[F[_], A](implicit rF: Reactive[F], F: Async[F]): Axn[Promise[F, A]] =
    Rxn.delay(_ => new PromiseImpl[F, A](Ref.unsafe[State[A]](Waiting(Map.empty))))

  def fast[F[_], A](implicit rF: Reactive[F], F: Async[F]): Axn[Promise[F, A]] =
    Rxn.delay(_ => new PromiseImpl2[F, A](Ref.unsafe[State2[A]](Waiting2(LongMap.empty, 0L))))

  implicit def invariantFunctorForPromise[F[_] : Functor]: Invariant[Promise[F, *]] = new Invariant[Promise[F, *]] {
    override def imap[A, B](fa: Promise[F, A])(f: A => B)(g: B => A): Promise[F, B] =
      fa.imap(f)(g)
  }

  // TODO: try to optimize (maybe with `LongMap`?)
  private final class Id

  private sealed abstract class State[A]
  private final case class Waiting[A](cbs: Map[Id, A => Unit]) extends State[A]
  private final case class Done[A](a: A) extends State[A]

  private final class PromiseImpl[F[_], A](ref: Ref[State[A]])(implicit rF: Reactive[F], F: Async[F])
    extends Promise[F, A] {

    val complete: Rxn[A, Boolean] = Rxn.computed { a =>
      ref.unsafeInvisibleRead.flatMap {
        case w @ Waiting(cbs) =>
          ref.unsafeCas(w, Done(a)).rmap(_ => true).postCommit(Rxn.delay { _ =>
            cbs.valuesIterator.foreach(_(a))
          })
        case Done(_) =>
          Rxn.ret(false)
      }
    }

    val tryGet: Axn[Option[A]] = {
      ref.get.map {
        case Done(a) => Some(a)
        case Waiting(_) => None
      }
    }

    def get: F[A] = {
      ref.unsafeInvisibleRead.run[F].flatMap {
        case Waiting(_) =>
          F.delay(new Id).flatMap { id =>
            F.async { cb =>
              F.uncancelable { poll =>
                val removeCallback = F.uncancelable { _ =>
                  ref.update {
                    case Waiting(cbs) => Waiting(cbs - id)
                    case d @ Done(_) => d
                  }.run[F]
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

    private def insertCallback(id: Id, cb: Either[Throwable, A] => Unit): Axn[Option[A]] = {
      val kv = (id, { (a: A) => cb(Right(a)) })
      ref.getAndUpdate {
        case Waiting(cbs) => Waiting(cbs + kv)
        case d @ Done(_) => d
      }.map {
        case Waiting(_) => None
        case Done(a) => Some(a)
      }
    }
  }

  private sealed abstract class State2[A]

  /**
   * We store the callbacks in a `LongMap`, because apparently
   * it is faster this way. Benchmarks show that it is measurably
   * faster if there are a lot of callbacks, and doesn't seem slower
   * even if there are only a few callbacks.
   *
   * The idea is from here: https://github.com/typelevel/cats-effect/pull/1128.
   */
  private final case class Waiting2[A](cbs: LongMap[A => Unit], nextId: Long) extends State2[A]

  private final case class Done2[A](a: A) extends State2[A]

  private final class PromiseImpl2[F[_], A](ref: Ref[State2[A]])(implicit rF: Reactive[F], F: Async[F])
    extends Promise[F, A] {

    val complete: A =#> Boolean = Rxn.computed { a =>
      ref.unsafeInvisibleRead.flatMap {
        case w @ Waiting2(cbs, _) =>
          ref.unsafeCas(w, Done2(a)).as(true).postCommit(Rxn.delay { _ =>
            cbs.valuesIterator.foreach(_(a))
          })
        case Done2(_) =>
          Rxn.ret(false)
      }
    }

    val tryGet: Axn[Option[A]] = {
      ref.get.map {
        case Done2(a) => Some(a)
        case Waiting2(_, _) => None
      }
    }

    def get: F[A] = {
      ref.unsafeInvisibleRead.run[F].flatMap {
        case Waiting2(_, _) =>
          F.async { cb =>
            F.uncancelable[Either[Long, A]] { poll =>
              insertCallback(cb).run[F].flatMap[Either[Long, A]] {
                case l @ Left(_) =>
                  F.pure(l)
                case r @ Right(a) =>
                  poll(F.delay { cb(Right(a)) }).as(r)
              }
            }.map {
              case Left(id) =>
                Some(removeCallback(id))
              case Right(_) =>
                None
            }
          }
        case Done2(a) =>
          F.pure(a)
      }
    }

    private def insertCallback(cb: Either[Throwable, A] => Unit): Axn[Either[Long, A]] = {
      val rcb = { (a: A) => cb(Right(a)) }
      ref.getAndUpdate {
        case Waiting2(cbs, nid) => Waiting2(cbs + (nid -> rcb), nid + 1)
        case d @ Done2(_) => d
      }.map {
        case Waiting2(_, nid) => Left(nid)
        case Done2(a) => Right(a)
      }
    }

    private def removeCallback(id: Long): F[Unit] = {
      F.uncancelable { _ =>
        ref.update {
          case Waiting2(cbs, nid) => Waiting2(cbs - id, nid)
          case d @ Done2(_) => d
        }.run[F]
      }
    }
  }
}
