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

package com.example.choamtest

import scala.concurrent.duration.FiniteDuration

import cats.~>
import cats.Monad
import cats.effect.IO
import cats.effect.kernel.{ MonadCancel, Sync, CancelScope, Poll }


import dev.tauri.choam.mcas.Mcas
import dev.tauri.choam.{ Rxn, Axn, Reactive, =#> }
import dev.tauri.choam.async.{ AsyncReactive, Promise, GenWaitList, WaitList }

final case class MyIO[+A](val impl: IO[A]) {
}

object MyIO {

  implicit def asyncReactiveForMyIO: AsyncReactive[MyIO] = new AsyncReactive[MyIO] {

    override def apply[A, B](r: Rxn[A,B], a: A): MyIO[B] =
      MyIO(IO.delay { r.unsafePerform(a, this.mcasImpl) })

    override def mcasImpl: Mcas =
      Mcas.DefaultMcas

    override def monad: Monad[MyIO] =
      syncForMyIO

    override def promise[A]: Axn[Promise[MyIO, A]] = {
      // TODO: we should be able to implement Promise
      // TODO: directly for MyIO, but it's sealed
      asyncReactiveForIO.promise[A].map { p =>
        p.mapK[MyIO](myIOFromIO)(this.monad)
      }
    }

    override def waitList[A](syncGet: Axn[Option[A]], syncSet: A =#> Unit): Axn[WaitList[MyIO, A]] = {
      asyncReactiveForIO.waitList[A](syncGet, syncSet).map { wl =>
        new WaitListForMyIO[A](wl)
      }
    }

    override def genWaitList[A](tryGet: Axn[Option[A]], trySet: A =#> Boolean): Axn[GenWaitList[MyIO, A]] = {
      asyncReactiveForIO.genWaitList[A](tryGet, trySet).map { gwl =>
        new GenWaitListForMyIO[A](gwl)
      }
    }

    override def monadCancel: MonadCancel[MyIO, _] =
      syncForMyIO
  }

  // non-implicit!
  def syncForMyIO: Sync[MyIO] = new Sync[MyIO] {
    override def pure[A](x: A): MyIO[A] =
      MyIO(IO.pure(x))
    override def raiseError[A](e: Throwable): MyIO[A] =
      MyIO(IO.raiseError(e))
    override def handleErrorWith[A](fa: MyIO[A])(f: Throwable => MyIO[A]): MyIO[A] =
      MyIO(fa.impl.handleErrorWith(e => f(e).impl))
    override def flatMap[A, B](fa: MyIO[A])(f: A => MyIO[B]): MyIO[B] =
      MyIO(fa.impl.flatMap { a => f(a).impl })
    override def tailRecM[A, B](a: A)(f: A => MyIO[Either[A,B]]): MyIO[B] =
      MyIO(IO.asyncForIO.tailRecM(a) { a => f(a).impl })
    override def rootCancelScope: CancelScope =
      CancelScope.Cancelable
    override def forceR[A, B](fa: MyIO[A])(fb: MyIO[B]): MyIO[B] =
      MyIO(IO.asyncForIO.forceR(fa.impl)(fb.impl))
    override def uncancelable[A](body: Poll[MyIO] => MyIO[A]): MyIO[A] = MyIO(
      IO.uncancelable { poll =>
        body(new Poll[MyIO] { def apply[X](io: MyIO[X]) = MyIO(poll(io.impl)) }).impl
      }
    )
    override def canceled: MyIO[Unit] =
      MyIO(IO.canceled)
    override def onCancel[A](fa: MyIO[A], fin: MyIO[Unit]): MyIO[A] =
      MyIO(fa.impl.onCancel(fin.impl))
    override def monotonic: MyIO[FiniteDuration] =
      MyIO(IO.monotonic)
    override def realTime: MyIO[FiniteDuration] =
      MyIO(IO.realTime)
    override def suspend[A](hint: Sync.Type)(thunk: => A): MyIO[A] =
      MyIO(IO.suspend(hint)(thunk))
  }

  private def myIOFromIO: IO ~> MyIO = new ~>[IO, MyIO] {
    final def apply[B](fa: IO[B]): MyIO[B] = MyIO(fa)
  }

  private def asyncReactiveForIO: AsyncReactive[IO] =
    AsyncReactive.asyncReactiveForAsync[IO]

  private final class WaitListForMyIO[A](
    underlying: WaitList[IO, A],
  ) extends WaitList[MyIO, A] {

    override def tryGet: Axn[Option[A]] =
      underlying.tryGet

    override def asyncSet(a: A): MyIO[Unit] =
      MyIO(underlying.asyncSet(a))

    override def asyncGet: MyIO[A] =
      MyIO(underlying.asyncGet)

    override def set: A =#> Unit =
      underlying.set

    override def mapK[G[_]](t: MyIO ~> G)(implicit G: Reactive[G]): WaitList[G, A] =
      underlying.mapK(myIOFromIO.andThen(t))
  }

  private final class GenWaitListForMyIO[A](
    underlying: GenWaitList[IO, A],
  ) extends GenWaitList[MyIO, A] {

    override def trySet: A =#> Boolean =
      underlying.trySet

    override def tryGet: Axn[Option[A]] =
      underlying.tryGet

    override def asyncSet(a: A): MyIO[Unit] =
      MyIO(underlying.asyncSet(a))

    override def asyncGet: MyIO[A] =
      MyIO(underlying.asyncGet)

    override def mapK[G[_]](t: MyIO ~> G)(implicit G: Reactive[G]): GenWaitList[G, A] =
      underlying.mapK(myIOFromIO.andThen(t))
  }
}
