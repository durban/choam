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
import cats.effect.kernel.syntax.all._

import core.{ Rxn, Ref, AsyncReactive }
import data.RemoveQueue

private[choam] sealed trait GenWaitList[A] { self =>

  def trySet(a: A): Rxn[Boolean]

  def tryGet: Rxn[Option[A]]

  def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit]

  // TODO: try to use this to implement `asyncSet`
  def asyncSetCb(a: A, cb: Either[Throwable, Unit] => Unit, firstTry: Boolean): Rxn[Either[Rxn[Unit], Unit]]

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

  final override def asyncSetCb(a: A, cb: Either[Throwable, Unit] => Unit, firstTry: Boolean): Rxn[Right[Nothing, Unit]] = {
    _assert(firstTry)
    this.set(a).as(GenWaitList.RightUnit)
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

  private[async] final def RightUnit: Right[Nothing, Unit] =
    _rightUnit

  private[this] val _rightUnit: Right[Nothing, Unit] =
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
        final override def tryGetUnderlyingCount: Rxn[Int] =
          tguCount.get
        final override def trySetUnderlyingCount: Rxn[Int] =
          tsuCount.get
        final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] =
          gwl.asyncGet[F]
        final override def asyncSet[F[_]](a: A)(implicit F: AsyncReactive[F]): F[Unit] =
          gwl.asyncSet[F](a)
        final override def asyncSetCb(a: A, cb: Either[Throwable, Unit] => Unit, firstTry: Boolean): Rxn[Either[Rxn[Unit], Unit]] =
          gwl.asyncSetCb(a, cb, firstTry)
        final override def tryGet: Rxn[Option[A]] =
          gwl.tryGet
        final override def trySet(a: A): Rxn[Boolean] =
          gwl.trySet(a)
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

  private[GenWaitList] final class Flag(var value: Boolean)

  private abstract class GenWaitListCommon[A]
    extends GenWaitList[A] { self =>

    private[this] final def callCb[B](cb: B => Unit, b: B): Rxn[Unit] = {
      Rxn.postCommit(Rxn.unsafe.delay {
        cb(b)
      })
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
      poll: F ~> F,
    )(implicit F: AsyncReactive[F]): F[A] = {
      implicit val FF: Async[F] = F.asyncInst
      // This `Flag` is a side-channel between the 2 finalizers
      // below (we need this to detect in the outer one if the
      // inner one was already executed). Note, that we don't
      // need volatile or anything here, since finalizers are
      // ordered.
      FF.delay(new Flag(false)).flatMap { asyncFinalizerDone =>
        poll( // cancellation point right here
          // also, there is another one _inside_
          // the `cont` on the next line:
          asyncCheckAttemptEither[F, Unit, A] { cb =>
            // when we're trying first, we must check for the
            // existence of other waiters (to be fair); however,
            // when we're woken up later, we're supposed to go
            // directly to the underlying data structure:
            val tg = if (isFirstTry) self.tryGet else tryGetUnderlying
            F.run(
              tg.flatMap {
                case Some(a) =>
                  // in GenWaitList, we may need to notify a setter now:
                  if ((!isFirstTry) && (settersOrNull ne null)) {
                    wakeUpNextWaiter(settersOrNull).as(Right(a))
                  } else {
                    Rxn.pure(Right(a))
                  }
                case None =>
                  getters.enqueueWithRemover(cb).map { remover =>
                    // this inner finalizer takes care of cleanup
                    // if we're cancelled while suspended:
                    val cancel: F[Unit] = F.run(remover.flatMap { ok =>
                      if (ok) {
                        Rxn.unit
                      } else {
                        // wake up someone else instead of ourselves:
                        wakeUpNextWaiter(getters)
                      }
                    }) *> FF.delay {
                      // also signal to the outer finalizer, that
                      // it doesn't need to do anything:
                      asyncFinalizerDone.value = true
                    }
                    Left(cancel)
                  }
              }
            )
          }
        ).onCancel(fallbackWakeup(getters, asyncFinalizerDone, firstTry = isFirstTry))
      }.flatMap {
        case Left(_) =>
          asyncGetImpl(
            isFirstTry = false,
            getters = getters,
            tryGetUnderlying = tryGetUnderlying,
            settersOrNull = settersOrNull,
            poll = poll,
          )
        case Right(a) =>
          FF.pure(a)
      }
    }

    protected[this] final def fallbackWakeup[F[_]](
      getters: RemoveQueue[Either[Throwable, Unit] => Unit],
      asyncFinalizerDone: Flag,
      firstTry: Boolean,
    )(implicit F: AsyncReactive[F]): F[Unit] = {
      val FF = F.asyncInst
      FF.defer {
        // we need this outer finalizer, because
        // if we're cancelled NOT when suspended,
        // but before (or right inside) the
        // `asyncCheckAttemptEither` (see above),
        // then the inner finalizer doesn't run
        // (obviously; it doesn't even exist)
        if (firstTry || asyncFinalizerDone.value) {
          // either we weren't suspended, so we were
          // cancelled right at the beginning (so we
          // don't need a finalizer); or we detected
          // through the side-channel, that the inner
          // finalizer already ran, so we don't need to:
          FF.unit
        } else {
          wakeUpNextWaiter(getters).run[F]
        }
      }
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
      F.asyncInst.uncancelable { poll =>
        asyncSetImpl(a, firstTry = true, poll = poll)
      }
    }

    private[this] final def asyncSetImpl[F[_]](
      a: A,
      firstTry: Boolean,
      poll: F ~> F,
    )(implicit F: AsyncReactive[F]): F[Unit] = {
      implicit val FF: Async[F] = F.asyncInst
      // Note: about the `Flag`, see the comment in `asyncGetImpl`
      FF.delay(new Flag(false)).flatMap { asyncFinalizerDone =>
        poll(
          asyncCheckAttemptEither[F, Unit, Unit] { cb =>
            F.run(
              asyncSetCb(a, cb, firstTry = firstTry).map {
                case Left(fin) =>
                  Left(fin.run[F] *> FF.delay { asyncFinalizerDone.value = true })
                case Right(()) =>
                  RightUnit
              }
            )
          }
        ).onCancel(fallbackWakeup(setters, asyncFinalizerDone, firstTry = firstTry))
      }.flatMap {
        case Left(()) =>
          asyncSetImpl(
            a,
            firstTry = false,
            poll = poll,
          )
        case Right(()) =>
          FF.unit
      }
    }

    final override def asyncSetCb(a: A, cb: Either[Throwable, Unit] => Unit, firstTry: Boolean): Rxn[Either[Rxn[Unit], Unit]] = {
      val ts = if (firstTry) self.trySet(a) else self.trySetUnderlying(a)
      ts.flatMap { ok =>
        if (ok) {
          // we're basically done, but might need to wake a getter:
          if (!firstTry) {
            wakeUpNextWaiter(getters).as(RightUnit)
          } else {
            Rxn.rightUnit
          }
        } else {
          setters.enqueueWithRemover(cb).map { remover =>
            val cancel: Rxn[Unit] = remover.flatMap { ok =>
              if (ok) Rxn.unit
              else wakeUpNextWaiter(setters)
            }
            Left(cancel)
          }
        }
      }
    }

    final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      F.asyncInst.uncancelable { poll =>
        asyncGetImpl(
          isFirstTry = true,
          getters = this.getters,
          tryGetUnderlying = this.tryGetUnderlying,
          settersOrNull = this.setters,
          poll = poll,
        )
      }
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

    final override def asyncGet[F[_]](implicit F: AsyncReactive[F]): F[A] = {
      F.asyncInst.uncancelable { poll =>
        asyncGetImpl[F](
          isFirstTry = true,
          getters = this.waiters,
          tryGetUnderlying = this.tryGetUnderlying,
          settersOrNull = null,
          poll = poll,
        )
      }
    }
  }
}
