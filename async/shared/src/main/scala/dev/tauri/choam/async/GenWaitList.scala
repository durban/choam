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
import cats.effect.kernel.{ Cont, MonadCancel, Async }

import core.{ Rxn, Ref, AsyncReactive }
import data.RemoveQueue

private[choam] sealed trait GenWaitList[A] { self =>

  def trySet(a: A): Rxn[Boolean]

  def tryGet: Rxn[Option[A]]

  def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit]

  def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A]
}

private[choam] sealed trait WaitList[A] extends GenWaitList[A] { self =>

  /** Returns `true` for a "normal" set, and `false` for waking a waiting getter. */
  def set(a: A): Rxn[Boolean]

  final override def trySet(a: A): Rxn[Boolean] =
    this.set(a).as(true)

  final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = {
    F.apply(this.set(a).void)
  }
}

private[choam] object WaitList { // TODO: should support AllocationStrategy

  final def apply[A](
    tryGetUnderlying: Rxn[Option[A]],
    setUnderlying: A => Rxn[Unit],
  ): Rxn[WaitList[A]] = {
    GenWaitList.waitListForAsync[A](tryGetUnderlying, setUnderlying)
  }

  private[choam] final def debug[A](
    tryGetUnderlying: Rxn[Option[A]],
    setUnderlying: A => Rxn[Unit],
  ): Rxn[GenWaitList.Debug[A]] = {
    (Ref(0), Ref(0)).flatMapN { (tguCount, suCount) =>
      apply[A](
        tryGetUnderlying = tryGetUnderlying <* tguCount.update(_ + 1),
        setUnderlying = { a => setUnderlying(a) <* suCount.update(_ + 1) },
      ).map { wl =>
        GenWaitList.Debug.from(wl, tguCount = tguCount, tsuCount = suCount)
      }
    }
  }
}

private[choam] object GenWaitList { // TODO: should support AllocationStrategy

  private[this] final val RightUnit: Right[Nothing, Unit] =
    Right(())

  final def apply[A](
    tryGetUnderlying: Rxn[Option[A]],
    trySetUnderlying: A => Rxn[Boolean],
  ): Rxn[GenWaitList[A]] = {
    RemoveQueue[Either[Throwable, Unit] => Unit].flatMap { getters =>
      RemoveQueue[Either[Throwable, Unit] => Unit].map { setters =>
        new AsyncGenWaitList[A](tryGetUnderlying, trySetUnderlying, getters, setters)
      }
    }
  }

  private[choam] sealed trait Debug[A] extends GenWaitList[A] {
    def tryGetUnderlyingCount: Rxn[Int]
    def trySetUnderlyingCount: Rxn[Int]
  }

  private[choam] final object Debug {
    final def from[A](
      gwl: GenWaitList[A],
      tguCount: Ref[Int],
      tsuCount: Ref[Int],
    ): GenWaitList.Debug[A] = {
      new GenWaitList.Debug[A] {
        final override def tryGetUnderlyingCount: Rxn[Int] = tguCount.get
        final override def trySetUnderlyingCount: Rxn[Int] = tsuCount.get
        final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = gwl.asyncGet[F]
        final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] = gwl.asyncSet[F](a)
        final override def tryGet: Rxn[Option[A]] = gwl.tryGet
        final override def trySet(a: A): Rxn[Boolean] = gwl.trySet(a)
      }
    }
  }

  private[choam] final def debug[A](
    tryGetUnderlying: Rxn[Option[A]],
    trySetUnderlying: A => Rxn[Boolean],
  ): Rxn[GenWaitList.Debug[A]] = {
    (Ref(0), Ref(0)).flatMapN { (tguCount, tsuCount) =>
      apply[A](
        tryGetUnderlying = tryGetUnderlying <* tguCount.update(_ + 1),
        trySetUnderlying = { a => trySetUnderlying(a) <* tsuCount.update(_ + 1) },
      ).map { gwl =>
        GenWaitList.Debug.from(gwl, tguCount = tguCount, tsuCount = tsuCount)
      }
    }
  }

  private[async] final def waitListForAsync[A](
    tryGetUnderlying: Rxn[Option[A]],
    setUnderlying: A => Rxn[Unit],
  ): Rxn[WaitList[A]] = {
    RemoveQueue[Either[Throwable, Unit] => Unit].map { waiters =>
      new AsyncWaitList[A](tryGetUnderlying, setUnderlying, waiters)
    }
  }

  private abstract class GenWaitListCommon[A]
    extends GenWaitList[A] { self =>

    private[this] final def callCb[B](cb: B => Unit, b: B): Rxn[Unit] = {
      Rxn.postCommit(Rxn.unsafe.delay {
        cb(b)
      })
    }

    protected[this] final def callCbUnit(cb: Unit => Unit): Rxn[Unit] = {
      this.callCb(cb, ())
    }

    protected[this] final def callCbRightUnit(cb: Right[Nothing, Unit] => Unit): Rxn[Unit] = {
      this.callCb(cb, RightUnit)
    }

    protected[this] final def wakeUpNextWaiter(waiters: RemoveQueue[Either[Throwable, Unit] => Unit]): Rxn[Unit] = {
      waiters.poll.flatMap {
        case None => Rxn.unit
        case Some(other) => callCbRightUnit(other)
      }
    }

    /**
     * This is like `Async#asyncCheckAttempt`, except:
     * (1) the immediate result can have a different type, and
     * (2) a finalizer is mandatory.
     *
     * TODO: use this (or delete it)
     */
    protected[this] final def asyncCheckAttemptEither[F[_], B, C](
      k: (Either[Throwable, B] => Unit) => F[Either[F[Unit], C]]
    )(implicit F: Async[F]): F[Either[B, C]] = {
      F.cont(new Cont[F, B, Either[B, C]] {
        final override def apply[G[_]](
          implicit G: MonadCancel[G, Throwable]
        ): (Either[Throwable, B] => Unit, G[B], F ~> G) => G[Either[B, C]] = { (cb, get, lift) =>
          G.uncancelable { poll =>
            lift(k(cb)).flatMap {
              case Right(b) => G.pure(Right(b))
              case Left(fin) => G.onCancel(poll(get), lift(fin)).map(Left(_))
            }
          }
        }
      })
    }

    protected[this] final def asyncGetImpl[F[_]](
      isFirstTry: Boolean,
      getters: RemoveQueue[Either[Throwable, Unit] => Unit],
      tryGetUnderlying: Rxn[Option[A]],
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
              tg.flatMap {
                case Some(a) =>
                  // in GenWaitList, we may need to notify a setter now:
                  if ((!isFirstTry) && (settersOrNull ne null)) {
                    wakeUpNextWaiter(settersOrNull).as(G.pure(a))
                  } else {
                    Rxn.pure(G.pure(a))
                  }
                case None =>
                  getters.enqueueWithRemover(cb).map { remover =>
                    val cancel: F[Unit] = ar.run(remover.flatMap { ok =>
                      if (ok) {
                        Rxn.unit
                      } else {
                        // wake up someone else instead of ourselves:
                        wakeUpNextWaiter(getters)
                      }
                    })
                    G.onCancel(poll(get), lift(cancel)) *> {
                      // we need a `poll` also on the recursive call,
                      // because right now we're inside an `uncancelable`:
                      G.onCancel(
                        poll( // cancellation gap right here
                          // also, there is another gap _inside_
                          // the `cont` in the recursive call
                          lift(asyncGetImpl(false,  getters, tryGetUnderlying, settersOrNull))
                        ),
                        lift(ar.run(wakeUpNextWaiter(getters))) // we need this due to the gap(s) above
                        // Note: This second finalizer never runs, if
                        // Note: the first one ran (and vice versa).
                        // Note: (They're on the same fiber, with a `*>`.)
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
    tryGetUnderlying: Rxn[Option[A]],
    trySetUnderlying: A => Rxn[Boolean],
    getters: RemoveQueue[Either[Throwable, Unit] => Unit],
    setters: RemoveQueue[Either[Throwable, Unit] => Unit],
  ) extends GenWaitListCommon[A] { self =>

    final override def trySet(a: A): Rxn[Boolean] = {
      setters.isEmpty.flatMap { noSetters =>
        if (noSetters) {
          getters.poll.flatMap {
            case None =>
              trySetUnderlying(a)
            case Some(cb) =>
              trySetUnderlying(a).flatMap { ok =>
                if (ok) {
                  callCbRightUnit(cb).as(true)
                } else {
                  impossible("AsyncGenWaitList#trySet found a getter, but trySetUnderlying failed")
                }
              }
          }
        } else {
          Rxn.false_
        }
      }
    }

    final override def tryGet: Rxn[Option[A]] = {
      this.getters.isEmpty.flatMap { noGetters =>
        if (noGetters) {
          this.tryGetUnderlying.flatMap {
            case s @ Some(_) =>
              // success, try to unblock a setter:
              setters.poll.flatMap {
                case Some(setterCb) =>
                  callCbRightUnit(setterCb).as(s)
                case None =>
                  // no setter to unblock:
                  Rxn.pure(s)
              }
            case None =>
              Rxn.none
          }
        } else {
          // there are already getters waiting
          Rxn.none
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
            val ts = if (firstTry) self.trySet(a) else self.trySetUnderlying(a)
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
                  setters.enqueueWithRemover(cb).map { remover =>
                    val cancel: F[Unit] = ar.run(remover.flatMap { ok =>
                      if (ok) {
                        Rxn.unit
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
              }
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

  private final class AsyncWaitList[A](
    tryGetUnderlying: Rxn[Option[A]],
    setUnderlying: A => Rxn[Unit],
    waiters: RemoveQueue[Either[Throwable, Unit] => Unit],
  ) extends GenWaitListCommon[A]
    with WaitList[A] { self =>

    final override def tryGet: Rxn[Option[A]] = {
      this.waiters.isEmpty.flatMap { noWaiters =>
        if (noWaiters) {
          this.tryGetUnderlying
        } else {
          Rxn.none
        }
      }
    }

    final override def set(a: A): Rxn[Boolean] = {
      this.waiters.poll.flatMap {
        case None =>
          this.setUnderlying(a).as(true)
        case Some(cb) =>
          this.setUnderlying(a) *> callCbRightUnit(cb).as(false)
      }
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
