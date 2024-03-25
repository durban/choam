/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.concurrent.duration._

import cats.syntax.all._
import cats.effect.{ IO, IOApp }
import cats.effect.instances.all._

import dev.tauri.choam.{ Ref, Reactive }

/** Simple program to interactively try the JMX MBean (with visualvm, or jconsole, or similar) */
object JmxDemo extends IOApp.Simple {

  implicit def reactive: Reactive[IO] =
    Reactive.forSync[IO]

  private final val N = 1024

  def run: IO[Unit] = {
    for {
      r1 <- Ref.unpadded("foo").run[IO]
      r2 <- Ref.padded("bar").run[IO]
      arr <- Ref.array(N, "x").run[IO]
      tsk = Ref.swap(r1, r2).run[IO].parReplicateA_(0xffff)
      arrTsk = (0 until N by 4).toVector.traverse_ { idx =>
        arr.unsafeGet(idx).update(_ + "y")
      }.run[IO].parReplicateA_(0xfff)
      _ <- IO.both(IO.both(tsk, tsk), arrTsk)
      _ <- (IO.sleep(1.second) *> Ref.swap(arr.unsafeGet(1), arr.unsafeGet(2)).run[IO]).replicateA_(60)
    } yield ()
  }
}
