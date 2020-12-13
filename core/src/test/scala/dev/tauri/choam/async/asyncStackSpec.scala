/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.concurrent.duration._

import cats.effect.{ IO, Timer }

class AsyncStackSpecNaiveKCAS
  extends AsyncStackSpec
  with SpecNaiveKCAS

class AsyncStackSpecCASN
  extends AsyncStackSpec
  with SpecCASN

class AsyncStackSpecMCAS
  extends AsyncStackSpec
  with SpecMCAS

abstract class AsyncStackSpec extends BaseSpec {

  val timer: Timer[IO] =
    this.timerIo

  "pop on a non-empty stack" should "work like on Treiber stack" in {
    val s = AsyncStack[String].unsafeRun
    s.push.unsafePerform("foo")
    s.push.unsafePerform("bar")
    s.pop[IO].unsafeRunSync() should === ("bar")
    s.pop[IO].unsafeRunSync() should === ("foo")
  }

  it should "work for concurrent pops" in {
    val s = AsyncStack[String].unsafeRun
    s.push.unsafePerform("xyz")
    s.push.unsafePerform("foo")
    s.push.unsafePerform("bar")
    val pop = s.pop[IO]
    val tsk = for {
      f1 <- pop.start
      f2 <- pop.start
      p1 <- f1.join
      p2 <- f2.join
    } yield (p1, p2)
    val res = tsk.unsafeRunSync()
    Set(res._1, res._2) should === (Set("foo", "bar"))
    pop.unsafeRunSync() should === ("xyz")
  }

  def tid(): String = Thread.currentThread().getId.toString()

  "pop on an empty stack" should "complete with the correponding push" in {
    val s = AsyncStack[String].unsafeRun
    val tsk = for {
      f1 <- s.pop[IO].start
      _ <- timer.sleep(100.milliseconds)
      _ <- s.push[IO]("foo")
      p1 <- f1.join
    } yield p1
    val res = tsk.unsafeRunSync()
    res should === ("foo")
  }

  it should "work with racing pushes" in {
    val s = AsyncStack[String].unsafeRun
    val tsk = for {
      f1 <- s.pop[IO].start
      _ <- timer.sleep(100.milliseconds)
      f2 <- s.pop[IO].start
      _ <- timer.sleep(100.milliseconds)
      _ <- s.push[IO]("foo")
      p1 <- f1.join
      _ <- s.push[IO]("bar")
      p2 <- f2.join
    } yield (p1, p2)
    val res = tsk.unsafeRunSync()
    res should === (("foo", "bar"))
  }

  // TODO:
  // it should "serve pops in a FIFO manner" in {
  //   ???
  // }

  // TODO:
  // it should "be cancellable" in {
  //   ???
  // }
}
