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
import cats.effect.kernel.{ Cont, MonadCancel }

import core.{ =#>, Rxn, Axn, AsyncReactive }
import data.RemoveQueue

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
    tryGetUnderlying: Axn[Option[A]],
    setUnderlying: A =#> Unit
  ): Axn[WaitList[A]] = {
    GenWaitList.waitListForAsync[A](tryGetUnderlying, setUnderlying)
  }
}

private[choam] object GenWaitList {

  private[this] final val RightUnit: Right[Nothing, Unit] =
    Right(())

  final def apply[A](
    tryGetUnderlying: Axn[Option[A]],
    trySetUnderlying: A =#> Boolean,
  ): Axn[GenWaitList[A]] = {
    RemoveQueue[Either[Throwable, Unit] => Unit].flatMapF { getters =>
      RemoveQueue[Either[Throwable, Unit] => Unit].map { setters =>
        new AsyncGenWaitList[A](tryGetUnderlying, trySetUnderlying, getters, setters)
      }
    }
  }

  private[async] final def waitListForAsync[A](
    tryGetUnderlying: Axn[Option[A]],
    setUnderlying: A =#> Unit
  ): Axn[WaitList[A]] = {
    RemoveQueue[Either[Throwable, Unit] => Unit].map { waiters =>
      new AsyncWaitList[A](tryGetUnderlying, setUnderlying, waiters)
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

    protected[this] final def callCbRightUnit(cb: Right[Nothing, Unit] => Unit): Axn[Unit] = {
      this.callCb(cb).provide(RightUnit).void
    }

    protected[this] final def wakeUpNextWaiter(waiters: RemoveQueue[Either[Throwable, Unit] => Unit]): Axn[Unit] = {
      waiters.tryDeque.flatMapF {
        case None => Axn.unit
        case Some(other) => callCbRightUnit(other)
      }
    }

    protected[this] final def asyncGetImpl[F[_]](
      isFirstTry: Boolean,
      getters: RemoveQueue[Either[Throwable, Unit] => Unit],
      tryGetUnderlying: Axn[Option[A]],
      settersOrNull: RemoveQueue[Either[Throwable, Unit] => Unit],
    )(implicit ar: AsyncReactive[F]): F[A] = {
      ar.asyncInst.cont(new Cont[F, Unit, A] {
        final override def apply[G[_]](
          implicit G: MonadCancel[G, Throwable]
        ): (Either[Throwable, Unit] => Unit, G[Unit], F ~> G) => G[A] = { (cb, get, lift) =>
          G.uncancelable { poll =>
            // when we're trying first, we must check for the
            // existence of other waiters (to be fair); however,
            // when we're woken up later, we're supposed to go
            // directly to the underlying data structure:
            val tg = if (isFirstTry) self.tryGet else tryGetUnderlying
            lift(ar.run(
              tg.flatMapF {
                case Some(a) =>
                  // in GenWaitList, we may need to notify a setter now:
                  if ((!isFirstTry) && (settersOrNull ne null)) {
                    wakeUpNextWaiter(settersOrNull).as(G.pure(a))
                  } else {
                    Axn.pure(G.pure(a))
                  }
                case None =>
                  getters.enqueueWithRemover.provide(cb).map { remover =>
                    val cancel: F[Unit] = ar.run(remover.flatMapF { ok =>
                      if (ok) {
                        Axn.unit
                      } else {
                        // wake up someone else instead of ourselves:
                        wakeUpNextWaiter(getters)
                      }
                    })
                    G.onCancel(poll(get), lift(cancel)) *> {
                      // we need a `poll` also on the recursive call,
                      // because right now we're inside an `uncancelable`:
                      G.onCancel(
                        poll(/* cancellation gap right here */
                          lift(asyncGetImpl(false,  getters, tryGetUnderlying, settersOrNull))
                        ),
                        lift(ar.run(wakeUpNextWaiter(getters))) // we need this due to the gap above
                      )
                    }
                  }
              }
            )).flatten
          }
        }
      })
    }
  }

  private final class AsyncGenWaitList[A](
    tryGetUnderlying: Axn[Option[A]],
    trySetUnderlying: A =#> Boolean,
    getters: RemoveQueue[Either[Throwable, Unit] => Unit],
    setters: RemoveQueue[Either[Throwable, Unit] => Unit],
  ) extends GenWaitListCommon[A] { self =>

    final override def trySet0: A =#> Boolean = {
      setters.isEmpty.flatMap { noSetters =>
        if (noSetters) {
          getters.tryDeque.flatMap {
            case None =>
              trySetUnderlying
            case Some(cb) =>
              trySetUnderlying.flatMapF { ok =>
                if (ok) {
                  callCbRightUnit(cb).as(true)
                } else {
                  impossible("AsyncGenWaitList#trySet0 found a getter, but trySetUnderlying failed")
                }
              }
          }
        } else {
          Axn.pure(false)
        }
      }
    }

    final override def tryGet: Axn[Option[A]] = {
      this.getters.isEmpty.flatMapF { noGetters =>
        if (noGetters) {
          this.tryGetUnderlying.flatMapF {
            case s @ Some(_) =>
              // success, try to unblock a setter:
              setters.tryDeque.flatMapF {
                case Some(setterCb) =>
                  callCbRightUnit(setterCb).as(s)
                case None =>
                  // no setter to unblock:
                  Rxn.pure(s)
              }
            case None =>
              Rxn.pure(None)
          }
        } else {
          // there are already getters waiting
          Axn.none
        }
      }
    }

    final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = {
      asyncSetImpl(a, firstTry = true)
    }

    private[this] final def asyncSetImpl[F[_]](a: A, firstTry: Boolean)(implicit ar: AsyncReactive[F]): F[Unit] = {
      ar.asyncInst.cont(new Cont[F, Unit, Unit] {
        final override def apply[G[_]](
          implicit G: MonadCancel[G, Throwable]
        ): (Either[Throwable, Unit] => Unit, G[Unit], F ~> G) => G[Unit] = { (cb, get, lift) =>
          G.uncancelable { poll =>
            val ts = if (firstTry) self.trySet0 else self.trySetUnderlying
            lift(ar.apply(
              ts.flatMap { ok =>
                if (ok) {
                  // we're basically done, but might need to wake a getter:
                  if (!firstTry) {
                    wakeUpNextWaiter(getters).as(G.unit)
                  } else {
                    Rxn.pure(G.unit)
                  }
                } else {
                  setters.enqueueWithRemover.provide(cb).map { remover =>
                    val cancel: F[Unit] = ar.run(remover.flatMapF { ok =>
                      if (ok) {
                        Axn.unit
                      } else {
                        // wake up someone else instead of ourselves:
                        wakeUpNextWaiter(setters)
                      }
                    })
                    G.onCancel(poll(get), lift(cancel)) *> {
                      G.onCancel(
                        poll(lift(asyncSetImpl(a, false))),
                        lift(ar.run(wakeUpNextWaiter(setters)))
                      )
                    }
                  }
                }
              },
              a
            )).flatten
          }
        }
      })
    }

    final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      asyncGetImpl(
        isFirstTry = true,
        getters = this.getters,
        tryGetUnderlying = this.tryGetUnderlying,
        settersOrNull = this.setters,
      )
    }
  }

  // TODO: Look at all usages of (Gen)WaitList, and make
  // TODO: sure, that none of them uses the underlying
  // TODO: data structure directly (unless really
  // TODO: necessary), because that's dangerous.
  private final class AsyncWaitList[A](
    tryGetUnderlying: Axn[Option[A]],
    setUnderlying: A =#> Unit,
    waiters: RemoveQueue[Either[Throwable, Unit] => Unit],
  ) extends GenWaitListCommon[A]
    with WaitList[A] { self =>

    final override def tryGet: Axn[Option[A]] = {
      this.waiters.isEmpty.flatMapF { noWaiters =>
        if (noWaiters) {
          this.tryGetUnderlying
        } else {
          Axn.none
        }
      }
    }

    final override def set0: A =#> Boolean = {
      this.waiters.tryDeque.flatMap {
        case None =>
          this.setUnderlying.as(true)
        case Some(cb) =>
          this.setUnderlying >>> callCbRightUnit(cb).as(false)
      }
    }

    final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = {
      F.asyncInst.void(F.apply(this.set0, a))
    }

    final override def asyncGet[F[_]](implicit ar: AsyncReactive[F]): F[A] = {
      asyncGetImpl[F](
        isFirstTry = true,
        getters = this.waiters,
        tryGetUnderlying = this.tryGetUnderlying,
        settersOrNull = null,
      )
    }
  }
}
