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

sealed trait GenWaitList[A] { self =>

  def trySet0: A =#> Boolean

  def tryGet: Axn[Option[A]]

  def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit]

  def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A]
}

sealed trait WaitList[A] extends GenWaitList[A] { self =>

  def set0: A =#> Unit

  final override def trySet0: A =#> Boolean =
    this.set0.as(true)
}

object WaitList {

  final def apply[A](
    tryGet: Axn[Option[A]],
    syncSet: A =#> Unit
  ): Axn[WaitList[A]] = {
    GenWaitList.waitListForAsync[A](tryGet, syncSet)
  }
}

object GenWaitList {

  private type Callback[A] =
    Either[Throwable, A] => Unit

  private[this] final val RightUnit: Either[Nothing, Unit] =
    Right(())

  final def apply[A](
    tryGet: Axn[Option[A]],
    trySet: A =#> Boolean,
  ): Axn[GenWaitList[A]] = {
    data.Queue.unboundedWithRemove[Callback[A]].flatMapF { getters =>
      data.Queue.unboundedWithRemove[(A, Callback[Unit])].map { setters =>
        new AsyncGenWaitList[A](tryGet, trySet, getters, setters)
      }
    }
  }

  private[async] final def waitListForAsync[A](
    tryGet: Axn[Option[A]],
    syncSet: A =#> Unit
  ): Axn[WaitList[A]] = {
    data.Queue.unboundedWithRemove[Callback[A]].map { waiters =>
      new AsyncWaitList[A](tryGet, syncSet, waiters)
    }
  }

  private abstract class GenWaitListCommon[A]
    extends GenWaitList[A] { self =>

    protected[this] final def callCb[B](cb: Callback[B]): Rxn[B, B] = {
      Rxn.postCommit[B](Rxn.unsafe.delay { (b: B) =>
        cb(Right(b))
      })
    }
  }

  private final class AsyncGenWaitList[A](
    _tryGet: Axn[Option[A]],
    _trySet: A =#> Boolean,
    getters: data.Queue.WithRemove[Callback[A]],
    setters: data.Queue.WithRemove[(A, Callback[Unit])],
  ) extends GenWaitListCommon[A] {

    private[this] val rightUnit: Either[Nothing, Unit] =
      RightUnit

    final override def trySet0: A =#> Boolean = {
      getters.tryDeque.flatMap {
        case Some(cb) =>
          callCb(cb).as(true)
        case None =>
          _trySet
      }
    }

    final override def tryGet: Axn[Option[A]] = {
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

    final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = {
      F.asyncInst.asyncCheckAttempt { cb =>
        F.apply(
          getters.tryDeque.flatMap {
            case Some(getterCb) =>
              callCb(getterCb).as(rightUnit)
            case None =>
              _trySet.flatMapF { ok =>
                if (ok) Rxn.pure(rightUnit)
                else setters.enqueueWithRemover.provide((a, cb)).map { remover => Left(Some(F.run(remover))) }
              }
          },
          a
        )
      }
    }

    final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      F.asyncInst.asyncCheckAttempt { cb =>
        F.run(
          this.tryGet.flatMapF {
            case Some(a) =>
              Rxn.pure(Right(a))
            case None =>
              getters.enqueueWithRemover.provide(cb).map { remover => Left(Some(F.run(remover))) }
          }
        )
      }
    }

    private[this] final def callCbUnit(cb: Callback[Unit]): Rxn[Any, Unit] = {
      this.callCb(cb).provide(())
    }
  }

  private abstract class WaitListCommon[A] // TODO: remove this
    extends GenWaitListCommon[A]
    with WaitList[A] { self =>
  }

  private final class AsyncWaitList[A](
    val tryGet: Axn[Option[A]],
    val syncSet0: A =#> Unit,
    waiters: data.Queue.WithRemove[Callback[A]],
  ) extends WaitListCommon[A] { self =>

    final override def set0: A =#> Unit = {
      this.waiters.tryDeque.flatMap {
        case None =>
          this.syncSet0
        case Some(cb) =>
          callCb(cb).void
      }
    }

    final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = {
      F.apply(this.set0, a)
    }

    final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      F.asyncInst.asyncCheckAttempt { cb =>
        val rxn: Axn[Either[Some[F[Unit]], A]] = this.tryGet.flatMapF {
          case Some(a) =>
            Rxn.pure(Right(a))
          case None =>
            this.waiters.enqueueWithRemover.provide(cb).map { remover =>
              Left(Some(F.run(remover)))
            }
        }
        F.run(rxn)
      }
    }
  }
}
