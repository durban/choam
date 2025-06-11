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
package bench

import org.openjdk.jmh.annotations._

import cats.syntax.all._
import cats.effect.IO

import core.{ Rxn, AsyncReactive }
import _root_.dev.tauri.choam.bench.BenchUtils

@Fork(2)
@Threads(1) // because it runs on the IO compute pool
class PromiseBench extends BenchUtils {

  import PromiseBench._

  final val size = 2048

  @Benchmark
  def completeRxn0(s: PromiseSt): Unit = {
    val tsk = s.ar(Promise[String]).flatMap(taskRxn0(s.numWaiters, s.ar))
    run(s.runtime, tsk, size = size)
  }

  private[this] def taskRxn0(waiters: Int, ar: AsyncReactive[IO])(p: Promise[String]): IO[Unit] = {
    for {
      fibs <- p.get[IO](ar).start.replicateA(waiters)
      _ <- IO.cede
      _ <- IO.race(ar(p.complete0, "left"), ar(p.complete0, "right"))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()
  }

  @Benchmark
  def completeRxn1(s: PromiseSt): Unit = {
    val tsk = s.ar(Promise[String]).flatMap(taskRxn1(s.numWaiters, s.ar))
    run(s.runtime, tsk, size = size)
  }

  private[this] def taskRxn1(waiters: Int, ar: AsyncReactive[IO])(p: Promise[String]): IO[Unit] = {
    for {
      fibs <- p.get[IO](ar).start.replicateA(waiters)
      _ <- IO.cede
      _ <- IO.race(ar(Rxn.computed(p.complete1), "left"), ar(Rxn.computed(p.complete1), "right"))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()
  }

  @Benchmark
  def completeAxn0(s: PromiseSt): Unit = {
    val tsk = s.ar(Promise[String]).flatMap(taskAxn0(s.numWaiters, s.ar))
    run(s.runtime, tsk, size = size)
  }

  private[this] def taskAxn0(waiters: Int, ar: AsyncReactive[IO])(p: Promise[String]): IO[Unit] = {
    for {
      fibs <- p.get[IO](ar).start.replicateA(waiters)
      _ <- IO.cede
      _ <- IO.race(ar(p.complete0.provide("left")), ar(p.complete0.provide("right")))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()
  }

  @Benchmark
  def completeAxn1(s: PromiseSt): Unit = {
    val tsk = s.ar(Promise[String]).flatMap(taskAxn1(s.numWaiters, s.ar))
    run(s.runtime, tsk, size = size)
  }

  private[this] def taskAxn1(waiters: Int, ar: AsyncReactive[IO])(p: Promise[String]): IO[Unit] = {
    for {
      fibs <- p.get[IO](ar).start.replicateA(waiters)
      _ <- IO.cede
      _ <- IO.race(ar(p.complete1("left")), ar(p.complete1("right")))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()
  }
}

object PromiseBench {

  @State(Scope.Benchmark)
  class PromiseSt {

    val runtime =
      cats.effect.unsafe.IORuntime.global

    @Param(Array("1", "2", "8", "64", "1024"))
    private[choam] var _numWaiters: Int =
      1

    def numWaiters: Int =
      this._numWaiters

    val ar: AsyncReactive[IO] =
      ce.unsafeImplicits.asyncReactiveForIO
  }
}
