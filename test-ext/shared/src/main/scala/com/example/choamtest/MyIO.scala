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

package com.example.choamtest

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import cats.~>
import cats.effect.IO
import cats.effect.kernel.{
  MonadCancel,
  Async,
  Sync,
  Poll,
  Resource,
  Fiber,
  Cont,
  Deferred,
  Ref,
  Outcome,
}

import dev.tauri.choam.ChoamRuntime
import dev.tauri.choam.core.AsyncReactive

final case class MyIO[+A](val impl: IO[A]) {

  final def flatMap[B](f: A => MyIO[B]): MyIO[B] =
    MyIO(this.impl.flatMap { a => f(a).impl })

  final def map[B](f: A => B): MyIO[B] =
    this.flatMap { a => MyIO.pure(f(a)) }
}

object MyIO {

  def pure[A](a: A): MyIO[A] =
    MyIO(IO.pure(a))

  def asyncReactiveForMyIO[F[_]](rt: ChoamRuntime)(implicit F: Sync[F]): Resource[F, AsyncReactive[MyIO]] =
    AsyncReactive.fromIn[F, MyIO](rt)

  implicit def asyncForMyIO: Async[MyIO] = new Async[MyIO] {
    override def start[A](fa: MyIO[A]): MyIO[Fiber[MyIO, Throwable, A]] = {
      MyIO(fa.impl.start.map { fio =>
        new Fiber[MyIO, Throwable, A] {
          override def cancel: MyIO[Unit] =
            MyIO(fio.cancel)
          override def join: MyIO[Outcome[MyIO, Throwable, A]] =
            MyIO(fio.join.map(_.mapK(myIOFromIO)))
        }
      })
    }
    override def cede: MyIO[Unit] =
      MyIO(IO.cede)
    override def ref[A](a: A): MyIO[Ref[MyIO, A]] =
      MyIO(IO.ref(a).map(_.mapK(myIOFromIO)))
    override def deferred[A]: MyIO[Deferred[MyIO, A]] =
      MyIO(IO.deferred[A].map(_.mapK(myIOFromIO)))
    override def sleep(time: FiniteDuration): MyIO[Unit] =
      MyIO(IO.sleep(time))
    override def evalOn[A](fa: MyIO[A], ec: ExecutionContext): MyIO[A] =
      MyIO(fa.impl.evalOn(ec))
    override def executionContext: MyIO[ExecutionContext] =
      MyIO(IO.executionContext)
    override def cont[K, R](body: Cont[MyIO, K, R]): MyIO[R] =
      MyIO(IO.cont[K, R](new Cont[IO, K, R] {
        override def apply[G[_]](implicit G: MonadCancel[G, Throwable]): (Either[Throwable, K] => Unit, G[K], IO ~> G) => G[R] =
          (cb, get, tr) => body.apply[G](using G).apply(cb, get, ioFromMyIO.andThen(tr))
      }))
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

  private def ioFromMyIO: MyIO ~> IO = new ~>[MyIO, IO] {
    final def apply[B](fa: MyIO[B]) = fa.impl
  }
}
