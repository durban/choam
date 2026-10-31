/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package util

import java.lang.Integer.remainderUnsigned

import scala.concurrent.duration._

import org.openjdk.jmh.annotations.{ State, Setup, Scope }

import cats.Id
import cats.effect.kernel.{ Sync, Async, Poll, CancelScope }
import cats.effect.{ IO, SyncIO }
import cats.effect.unsafe.IORuntime

import core.{ Reactive, AsyncReactive }
import internal.mcas.Mcas

@State(Scope.Thread)
class RandomState {

  private[this] val rnd =
    this.mkXorShift()

  protected def mkXorShift(): XorShift =
    XorShift()

  final def nextInt(): Int =
    rnd.nextInt()

  final def nextIntBounded(bound: Int): Int =
    remainderUnsigned(rnd.nextInt(), bound)

  final def nextLong(): Long =
    rnd.nextLong()

  final def nextString(): String =
    rnd.nextLong().abs.toString

  final def nextBoolean(): Boolean =
    (this.nextInt() % 2) == 0

  final def nextBooleanIO: IO[Boolean] =
    IO { this.nextBoolean() }

  final def nextBooleanZIO: zio.Task[Boolean] =
    zio.ZIO.attempt { this.nextBoolean() }
}

@State(Scope.Thread)
class McasImplState extends McasImplStateBase {

  private[choam] var mcasCtx: Mcas.ThreadContext =
    null

  @Setup
  def setupMcasImpl(): Unit = {
    this.mcasCtx = this.mcasImpl.currentContext()
    java.lang.invoke.VarHandle.releaseFence()
  }
}

@State(Scope.Thread)
class UnsafeApiState extends McasImplState {

  val api: unsafe.UnsafeApi =
    unsafe.UnsafeApi(McasImplStateBase.rt)
}

trait AsyncGenMcasImplStateBase[F[_]]
  extends GenMcasImplStateBase[F] {

  implicit def asyncF: Async[F]

  implicit val asyncReactive: AsyncReactive[F] =
    runAndBlock(AsyncReactive.from[F](choamRuntime).allocated)._1
}

trait GenMcasImplStateBase[F[_]] {

  implicit def syncF: Sync[F]

  def runAndBlock[A](fa: F[A]): A

  implicit val choamRuntime: ChoamRuntime =
    GenMcasImplStateBase.rt

  implicit val reactive: Reactive[F] =
    runAndBlock(Reactive.from[F](choamRuntime).allocated)._1

  val mcasImpl: Mcas = {
    this.choamRuntime.mcasImpl
  }
}

object GenMcasImplStateBase {

  object ForIO {

    private[ForIO] val rt: IORuntime =
      IORuntime.global
  }

  trait ForIO extends/* TODO: Async */GenMcasImplStateBase[IO] {

    implicit final override def syncF: Sync[IO] =
      IO.asyncForIO

    final override def runAndBlock[A](fa: IO[A]): A = {
      fa.unsafeRunSync()(using ForIO.rt)
    }
  }

  trait ForId extends GenMcasImplStateBase[Id] {

    implicit final override def syncF: Sync[Id] = {
      syncForId
    }

    final override def runAndBlock[A](fa: Id[A]): A = {
      fa
    }
  }

  private[this] val nopPoll: Poll[Id] = new Poll[Id] {
    final override def apply[A](a: A): A = a
  }

  private[this] val syncForId: Sync[Id] = new Sync[Id] {
    final override def pure[A](x: A): Id[A] = x
    final override def raiseError[A](e: Throwable): Id[A] = throw e
    final override def monotonic: Id[FiniteDuration] = System.nanoTime().nanos
    final override def realTime: Id[FiniteDuration] = System.currentTimeMillis().millis
    final override def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B] = f(fa)
    final override def tailRecM[A, B](a: A)(f: A => Id[Either[A, B]]): Id[B] = f(a) match {
      case Left(aa) => tailRecM(aa)(f)
      case Right(b) => b
    }
    final override def rootCancelScope: CancelScope = CancelScope.Uncancelable
    final override def forceR[A, B](fa: Id[A])(fb: Id[B]): Id[B] = fb
    final override def uncancelable[A](body: Poll[Id] => Id[A]): Id[A] = body(nopPoll)
    final override def canceled: Id[Unit] = ()
    final override def onCancel[A](fa: Id[A], fin: Id[Unit]): Id[A] = fa
    final override def suspend[A](hint: Sync.Type)(thunk: => A): Id[A] = thunk
    final override def handleErrorWith[A](fa: Id[A])(f: Throwable => Id[A]): Id[A] = fa
  }

  private[bench] val rt: ChoamRuntime = {
    ChoamRuntime.unsafeBlocking()
  }
}

abstract class McasImplStateBase {

  private[choam] implicit val reactive: AsyncReactive[IO] =
    McasImplStateBase.reactiveIO

  private[choam] implicit val reactiveSyncIO: Reactive[SyncIO] =
    McasImplStateBase.reactiveSyncIO

  private[choam] val mcasImpl: Mcas =
    McasImplStateBase.mcasImpl

  private[choam] val choamRuntime: ChoamRuntime =
    McasImplStateBase.rt
}

private[choam] object McasImplStateBase {

  private[bench] val rt: ChoamRuntime = {
    GenMcasImplStateBase.rt
  }

  private[choam] val reactiveIO: AsyncReactive[IO] = {
    AsyncReactive.fromIn[SyncIO, IO](this.rt).allocated.unsafeRunSync()._1
  }

  private[choam] val reactiveSyncIO: Reactive[SyncIO] = {
    Reactive.fromIn[SyncIO, SyncIO](this.rt).allocated.unsafeRunSync()._1
  }

  private[bench] val mcasImpl: Mcas = {
    this.rt.mcasImpl
  }
}
