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

import cats.~>
import cats.effect.kernel.Async

abstract class GenWaitList[F[_], A] { self =>

  def trySet: A =#> Boolean

  def tryGet: Axn[Option[A]]

  def asyncSet(a: A): F[Unit]

  def asyncGet: F[A]

  def reactive: Reactive[F]

  def mapK[G[_]](t: F ~> G)(implicit G: Reactive[G]): GenWaitList[G, A] = {
    new GenWaitList[G, A] {
      override def trySet =
        self.trySet
      override def tryGet =
        self.tryGet
      override def asyncSet(a: A): G[Unit] =
        t(self.asyncSet(a))
      override def asyncGet: G[A] =
        t(self.asyncGet)
      override def reactive: Reactive[G] =
        G
    }
  }
}

object GenWaitList {

  import WaitList.Callback

  def forAsync[F[_], A](
    _tryGet: Axn[Option[A]],
    _trySet: A =#> Boolean,
  )(implicit F: Async[F], rF: AsyncReactive[F]): Axn[GenWaitList[F, A]] = {
    data.Queue.withRemove[Callback[A]].flatMapF { getters =>
      data.Queue.withRemove[(A, Callback[Unit])].map { setters =>
        new AsyncGenWaitList[F, A](_tryGet, _trySet, getters, setters)
      }
    }
  }

  private final class AsyncGenWaitList[F[_], A](
    _tryGet: Axn[Option[A]],
    _trySet: A =#> Boolean,
    getters: data.Queue.WithRemove[Callback[A]],
    setters: data.Queue.WithRemove[(A, Callback[Unit])],
  )(implicit F: Async[F], rF: AsyncReactive[F]) extends GenWaitList[F, A] {

    override def trySet: A =#> Boolean = {
      getters.tryDeque.flatMap {
        case Some(cb) =>
          callCb(cb).as(true)
        case None =>
          _trySet
      }
    }

    override def tryGet: Axn[Option[A]] = {
      _tryGet.flatMapF {
        case s @ Some(_) =>
          // success, try to unblock a setter:
          setters.tryDeque.flatMapF {
            case Some((setterVal, setterCb)) =>
              _trySet.provide(setterVal).flatMapF { ok =>
                if (ok) callCbUnit(setterCb).as(s)
                else throw new IllegalStateException("couldn't _trySet after successful _tryGet")
              }
            case None =>
              // no setter to unblock:
              Rxn.pure(s)
          }
        case None =>
          // can't get:
          Rxn.pure(None)
      }
    }

    override def asyncSet(a: A): F[Unit] = {
      val trySync = rF.apply(
        getters.tryDeque.flatMap {
          case Some(cb) =>
            callCb(cb).as(true)
          case None =>
            trySet
        },
        a,
      )
      F.flatMap(trySync) { ok =>
        if (ok) {
          F.unit
        } else {
          F.async[Unit] { cb =>
            F.map(rF.apply(setters.enqueueWithRemover, (a, cb))) { remover =>
              Some(rF.run(remover))
            }
          }
        }
      }
    }

    override def asyncGet: F[A] = {
      F.flatMap(rF.run(this.tryGet)) {
        case Some(a) =>
          F.pure(a)
        case None =>
          F.async[A] { cb =>
            F.map(rF.apply(getters.enqueueWithRemover, cb)) { remover =>
              Some(rF.run(remover))
            }
          }
      }
    }

    override def reactive: Reactive[F] =
      rF

    private[this] final def callCbUnit(cb: Callback[Unit]): Rxn[Any, Unit] = {
      this.callCb(cb).provide(())
    }

    // TODO: copy-paste
    private[this] final def callCb[B](cb: Callback[B]): Rxn[B, Unit] = {
      Rxn.identity[B].postCommit(Rxn.unsafe.delay { (b: B) =>
        cb(Right(b))
      }).void
    }
  }
}

abstract class WaitList[F[_], A] extends GenWaitList[F, A] { self =>

  def set: A =#> Unit

  def get: F[A]

  def syncGet: Axn[Option[A]] // TODO: better name

  def unsafeSetWaitersOrRetry: A =#> Unit // TODO: better name (or remove)

  final override def tryGet: Axn[Option[A]] =
    this.syncGet

  final override def trySet: A =#> Boolean =
    this.set.as(true)

  final override def asyncGet: F[A] =
    this.get

  final override def asyncSet(a: A): F[Unit] =
    this.reactive.apply(this.set, a)

  override def mapK[G[_]](t: F ~> G)(implicit G: Reactive[G]): WaitList[G, A] = {
    new WaitList[G, A] {
      override def set =
        self.set
      override def get =
        t(self.get)
      override def syncGet =
        self.syncGet
      override def unsafeSetWaitersOrRetry =
        self.unsafeSetWaitersOrRetry
      override def reactive: Reactive[G] =
        G
    }
  }
}

object WaitList {

  type Callback[A] =
    Either[Throwable, A] => Unit

  def forAsync[F[_], A](
    _syncGet: Axn[Option[A]],
    _syncSet: A =#> Unit
  )(implicit F: Async[F], rF: AsyncReactive[F]): Axn[WaitList[F, A]] = {
    data.Queue.withRemove[Callback[A]].map { waiters =>
      new AsyncWaitList[F, A](_syncGet, _syncSet, waiters)
    }
  }

  private final class AsyncWaitList[F[_], A](
    val syncGet: Axn[Option[A]],
    val syncSet: A =#> Unit,
    waiters: data.Queue.WithRemove[Callback[A]],
  )(implicit F: Async[F], arF: AsyncReactive[F]) extends WaitList[F, A] {

    /** Partial, retries if no waiters */
    final def unsafeSetWaitersOrRetry: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None =>
          Rxn.unsafe.retry
        case Some(cb) =>
          callCb(cb)
      }
    }

    private[this] final def callCb(cb: Callback[A]): Rxn[A, Unit] = {
      Rxn.identity[A].postCommit(Rxn.unsafe.delay { (a: A) =>
        cb(Right(a))
      }).void
    }

    def set: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None =>
          this.syncSet
        case Some(cb) =>
          callCb(cb)
      }
    }

    def get: F[A] = {
      F.async[A] { cb =>
        val rxn: Axn[Either[Axn[Unit], A]] = this.syncGet.flatMapF {
          case Some(a) =>
            Rxn.pure(Right(a))
          case None =>
            this.waiters.enqueueWithRemover.provide(cb).map { remover =>
              Left(remover)
            }
        }
        F.flatMap[Either[Axn[Unit], A], Option[F[Unit]]](arF.run(rxn)) {
          case Right(a) =>
            F.as(F.delay(cb(Right(a))), None)
          case Left(remover) =>
            F.pure(Some(arF.run(remover)))
        }
      }
    }

    def reactive: Reactive[F] =
      arF
  }
}
