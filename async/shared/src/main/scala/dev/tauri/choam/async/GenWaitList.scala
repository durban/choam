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

import cats.~>
import cats.syntax.all._
import cats.effect.kernel.{ Async, Cont, MonadCancel }

import core.{ =#>, Rxn, Axn, AsyncReactive }

private[choam] sealed trait GenWaitList[A] { self =>

  def trySet0: A =#> Boolean

  def tryGet: Axn[Option[A]]

  def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit]

  def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A]
}

private[choam] sealed trait WaitList[A] extends GenWaitList[A] { self =>

  /** Returns `true` for a "normal" set, and `false` for waking a waiting getter. */
  def set0: A =#> Boolean

  final override def trySet0: A =#> Boolean =
    this.set0.as(true)
}

private[choam] object WaitList {

  final def apply[A](
    tryGet: Axn[Option[A]],
    syncSet: A =#> Unit
  ): Axn[WaitList[A]] = {
    GenWaitList.waitListForAsync[A](tryGet, syncSet)
  }
}

private[choam] object GenWaitList {

  private[this] final val RightUnit: Right[Nothing, Unit] =
    Right(())

  final def apply[A](
    tryGet: Axn[Option[A]],
    trySet: A =#> Boolean,
  ): Axn[GenWaitList[A]] = {
    data.Queue.unboundedWithRemove[A => Unit].flatMapF { getters =>
      data.Queue.unboundedWithRemove[(A, Unit => Unit)].map { setters =>
        new AsyncGenWaitList[A](tryGet, trySet, getters, setters)
      }
    }
  }

  private[async] final def waitListForAsync[A](
    tryGet: Axn[Option[A]],
    syncSet: A =#> Unit
  ): Axn[WaitList[A]] = {
    data.Queue.unboundedWithRemove[Either[Throwable, Unit] => Unit].map { waiters =>
      new AsyncWaitList[A](tryGet, syncSet, waiters)
    }
  }

  private abstract class GenWaitListCommon[A]
    extends GenWaitList[A] { self =>

    protected[this] final def callCb[B](cb: B => Unit): Rxn[B, B] = {
      Rxn.postCommit[B](Rxn.unsafe.delay { (b: B) =>
        cb(b)
      })
    }

    protected[this] final def callCbUnit(cb: Unit => Unit): Rxn[Any, Unit] = {
      this.callCb(cb).provide(())
    }

    protected[this] final def callCbRightUnit(cb: Right[Nothing,Unit] => Unit): Rxn[Any, Unit] = {
      this.callCb(cb).provide(RightUnit).void
    }

    protected[this] final def asyncGetImpl[F[_]](
      waiters: data.Queue.WithRemove[A => Unit],
      fallback: Rxn[A, Boolean],
    )(implicit ar: AsyncReactive[F]): F[A] = {
      implicit val F: Async[F] = ar.asyncInst
      SideChannel[F, A].flatMap { sideChannel =>
        F.asyncCheckAttempt { cb =>
          ar.run(
            this.tryGet.flatMapF {
              case Some(a) =>
                Rxn.pure(Right(a))
              case None =>
                val cb2 = { (a: A) =>
                  val ra = Right(a)
                  if (sideChannel.completeSync(ra)) {
                    cb(ra)
                  } // else: we've lost anyway, no reason to call `cb`
                }
                waiters.enqueueWithRemover.provide(cb2).map { remover =>
                  val cancel: F[Unit] = ar.run(remover).flatMap { ok =>
                    if (!ok) {
                      // TODO: Is this still linearizable?
                      // TODO: Due to the sideChannel, there
                      // TODO: is a point in time, then the
                      // TODO: item is logically _in_ the queue,
                      // TODO: but unaccessible (to tryGet at least).
                      sideChannel.get.flatMap { a =>
                        ar.apply(fallback.void, a)
                      }
                    } else {
                      F.unit
                    }
                  }
                  Left(Some(cancel))
                }
            }
          )
        }
      }
    }
  }

  private final class AsyncGenWaitList[A](
    _tryGet: Axn[Option[A]],
    _trySet: A =#> Boolean,
    getters: data.Queue.WithRemove[A => Unit],
    setters: data.Queue.WithRemove[(A, Unit => Unit)],
  ) extends GenWaitListCommon[A] {

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
                else impossible("couldn't _trySet after successful _tryGet")
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
              callCb(getterCb).as(RightUnit)
            case None =>
              _trySet.flatMapF { ok =>
                if (ok) {
                  Rxn.pure(RightUnit)
                } else {
                  val cb2 = { (_: Unit) =>
                    // TODO: ...
                    cb(RightUnit)
                  }
                  setters.enqueueWithRemover.provide((a, cb2)).map { remover =>
                    Left(Some(F.run(remover.void))) // TODO: don't .void
                  }
                }
              }
          },
          a
        )
      }
    }

    final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      this.asyncGetImpl[F](waiters = this.getters, fallback = this.trySet0)
    }
  }

  private final class AsyncWaitList[A](
    val tryGet: Axn[Option[A]],
    val syncSet0: A =#> Unit,
    waiters: data.Queue.WithRemove[Either[Throwable, Unit] => Unit],
  ) extends GenWaitListCommon[A]
    with WaitList[A] { self =>

    final override def set0: A =#> Boolean = {
      this.waiters.tryDeque.flatMap {
        case None =>
          this.syncSet0.as(true)
        case Some(cb) =>
          this.syncSet0 >>> callCbRightUnit(cb).as(false)
      }
    }

    final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = {
      F.asyncInst.void(F.apply(this.set0, a))
    }

    final override def asyncGet[F[_]](implicit ar: AsyncReactive[F]): F[A] = {
      (new AsyncGetCont[F]).cont
    }

    private[this] final class AsyncGetCont[F[_]]()(implicit ar: AsyncReactive[F]) extends Cont[F, Unit, A] {

      val cont: F[A] =
        ar.asyncInst.cont(this)

      final override def apply[G[_]](
        implicit G: MonadCancel[G, Throwable]
      ): (Either[Throwable, Unit] => Unit, G[Unit], F ~> G) => G[A] = { (cb, get, lift) =>
        G.uncancelable { poll =>
          lift(ar.run(
            self.tryGet.flatMapF {
              case Some(a) =>
                Axn.pure(G.pure(a))
              case None =>
                waiters.enqueueWithRemover.provide(cb).map { remover =>
                  val cancel: F[Unit] = ar.run(remover.flatMapF { ok =>
                    if (ok) {
                      Axn.unit
                    } else {
                      // wake up someone else instead of ourselves:
                      waiters.tryDeque.flatMapF {
                        case None => Axn.unit
                        case Some(other) => callCbRightUnit(other)
                      }
                    }
                  })
                  G.onCancel(poll(get), lift(cancel)) *> lift(this.cont)
                }
            }
          )).flatten
        }
      }
    }
  }
}
