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

trait GenWaitList[F[_], A] { self =>

  def trySet: A =#> Boolean

  def tryGet: Axn[Option[A]]

  def asyncSet(a: A): F[Unit]

  def asyncGet: F[A]

  def mapK[G[_]](t: F ~> G)(implicit G: Reactive[G]): GenWaitList[G, A]
}

trait WaitList[F[_], A] extends GenWaitList[F, A] { self =>

  def set: A =#> Unit

  final override def trySet: A =#> Boolean =
    this.set.as(true)

  override def mapK[G[_]](t: F ~> G)(implicit G: Reactive[G]): WaitList[G, A]
}

object GenWaitList {

  private type Callback[A] =
    Either[Throwable, A] => Unit

  def genWaitListForAsync[F[_], A](
    tryGet: Axn[Option[A]],
    trySet: A =#> Boolean,
  )(implicit F: Async[F], rF: AsyncReactive[F]): Axn[GenWaitList[F, A]] = {
    data.Queue.unboundedWithRemove[Callback[A]].flatMapF { getters =>
      data.Queue.unboundedWithRemove[(A, Callback[Unit])].map { setters =>
        new AsyncGenWaitList[F, A](tryGet, trySet, getters, setters)
      }
    }
  }

  def waitListForAsync[F[_], A](
    tryGet: Axn[Option[A]],
    syncSet: A =#> Unit
  )(implicit F: Async[F], rF: AsyncReactive[F]): Axn[WaitList[F, A]] = {
    data.Queue.unboundedWithRemove[Callback[A]].map { waiters =>
      new AsyncWaitList[F, A](tryGet, syncSet, waiters)
    }
  }

  private abstract class GenWaitListCommon[F[_], A]
    extends GenWaitList[F, A] { self =>

    def mapK[G[_]](t: F ~> G)(implicit G: Reactive[G]): GenWaitList[G, A] = {
      new GenWaitListCommon[G, A] {
        override def trySet =
          self.trySet
        override def tryGet =
          self.tryGet
        override def asyncSet(a: A): G[Unit] =
          t(self.asyncSet(a))
        override def asyncGet: G[A] =
          t(self.asyncGet)
      }
    }

    protected[this] final def callCb[B](cb: Callback[B]): Rxn[B, Unit] = {
      Rxn.identity[B].postCommit(Rxn.unsafe.delay { (b: B) =>
        cb(Right(b))
      }).void
    }
  }

  private final class AsyncGenWaitList[F[_], A](
    _tryGet: Axn[Option[A]],
    _trySet: A =#> Boolean,
    getters: data.Queue.WithRemove[Callback[A]],
    setters: data.Queue.WithRemove[(A, Callback[Unit])],
  )(implicit F: Async[F], rF: AsyncReactive[F]) extends GenWaitListCommon[F, A] {

    private[this] val rightUnit: Either[Nothing, Unit] =
      Right(())

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
          // can't get, so it's "empty"; however,
          // it can also be "full" (e.g., queue of
          // size 0), so we need to check setters:
          setters.tryDeque.flatMapF {
            case Some((setterVal, setterCb)) =>
              callCbUnit(setterCb).as(Some(setterVal))
            case None =>
              Rxn.pure(None)
          }
      }
    }

    override def asyncSet(a: A): F[Unit] = {
      F.asyncCheckAttempt { cb =>
        rF.apply(
          getters.tryDeque.flatMap {
            case Some(getterCb) =>
              callCb(getterCb).as(rightUnit)
            case None =>
              _trySet.flatMapF { ok =>
                if (ok) Rxn.pure(rightUnit)
                else setters.enqueueWithRemover.provide((a, cb)).map { remover => Left(Some(rF.run(remover))) }
              }
          },
          a
        )
      }
    }

    override def asyncGet: F[A] = {
      F.asyncCheckAttempt { cb =>
        rF.run(
          this.tryGet.flatMapF {
            case Some(a) =>
              Rxn.pure(Right(a))
            case None =>
              getters.enqueueWithRemover.provide(cb).map { remover => Left(Some(rF.run(remover))) }
          }
        )
      }
    }

    private[this] final def callCbUnit(cb: Callback[Unit]): Rxn[Any, Unit] = {
      this.callCb(cb).provide(())
    }
  }

  private abstract class WaitListCommon[F[_], A]
    extends GenWaitListCommon[F, A]
    with WaitList[F, A] { self =>

    final override def mapK[G[_]](t: F ~> G)(implicit G: Reactive[G]): WaitList[G, A] = {
      new WaitListCommon[G, A] with WaitList[G, A] {
        override def set =
          self.set
        final override def asyncSet(a: A): G[Unit] =
          G.apply(this.set, a)
        override def asyncGet =
          t(self.asyncGet)
        override def tryGet =
          self.tryGet
      }
    }
  }

  private final class AsyncWaitList[F[_], A](
    val tryGet: Axn[Option[A]],
    val syncSet: A =#> Unit,
    waiters: data.Queue.WithRemove[Callback[A]],
  )(implicit F: Async[F], arF: AsyncReactive[F]) extends WaitListCommon[F, A] { self =>

    final def set: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None =>
          this.syncSet
        case Some(cb) =>
          callCb(cb)
      }
    }

    final def asyncSet(a: A): F[Unit] = {
      arF.apply(this.set, a)
    }

    final def asyncGet: F[A] = {
      F.async[A] { cb =>
        val rxn: Axn[Either[Axn[Unit], A]] = this.tryGet.flatMapF {
          case Some(a) =>
            Rxn.pure(Right(a))
          case None =>
            this.waiters.enqueueWithRemover.provide(cb).map { remover =>
              Left(remover)
            }
        }
        F.flatMap[Either[Axn[Unit], A], Option[F[Unit]]](arF.run(rxn)) {
          case Right(a) =>
            F.as(F.delay(cb(Right(a))), Some(F.unit))
          case Left(remover) =>
            F.pure(Some(arF.run(remover)))
        }
      }
    }
  }
}
