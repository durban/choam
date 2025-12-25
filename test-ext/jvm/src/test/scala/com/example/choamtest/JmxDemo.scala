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

import scala.concurrent.duration._
import scala.util.hashing.byteswap32

import cats.syntax.all._
import cats.effect.{ IO, IOApp }

import dev.tauri.choam.core.{ Rxn, Ref }
import dev.tauri.choam.ce.RxnAppMixin

/**
 * Simple program to interactively try the JMX MBean
 * (with visualvm, or jconsole, or similar); start it
 * with `testExt/Test/run`.
 */
object JmxDemo extends IOApp.Simple with RxnAppMixin {

  private final val N = 1024

  private[this] def unsafeApply[A](arr: Ref.Array[A], idx: Int): Ref[A] = // TODO: avoid this
    arr.refs.get(idx.toLong).getOrElse(throw new IllegalStateException)

  def run: IO[Unit] = {
    for {
      r1 <- Ref("foo").run[IO]
      r2 <- Ref("bar", Ref.AllocationStrategy(padded = true)).run[IO]
      arr <- Ref.array(N, "x").run[IO]
      tsk = Ref.swap(r1, r2).run[IO].parReplicateA_(0xffff)
      arrTsk = (0 until N by 4).toVector.traverse_ { idx =>
        unsafeApply(arr, idx).update(_ + "y") // TODO: avoid throw
      }.run[IO].parReplicateA_(0xfff)
      _ <- IO.both(IO.both(tsk, tsk), arrTsk)
      ta1 = trickyRxn(r1, r2)
      ta2 = trickyRxn(r2, r1)
      runTricky = IO.both(ta1.run[IO], ta2.run[IO])
      _ <- (runTricky *> IO.sleep(0.01.second)).foreverM.background.use { _ =>
        (IO.sleep(1.second) *> Ref.swap(unsafeApply(arr, 1), unsafeApply(arr, 2)).run[IO]).replicateA_(120)
      }
      _ <- IO { checkConsistency() }
    } yield ()
  }

  private def trickyRxn(r1: Ref[String], r2: Ref[String]): Rxn[String] = {
    r1.update(s => byteswap32(s.length).toString) *> r2.get
  }

  private def checkConsistency() = {
    val srv = java.lang.management.ManagementFactory.getPlatformMBeanServer()
    val beanName = srv.queryNames(
      new javax.management.ObjectName("dev.tauri.choam.stats:type=EmcasJmxStats*"),
      null
    ).iterator().next()
    val errMsg = srv.invoke(beanName, "checkConsistency", Array.empty[AnyRef], Array.empty[String])
    if (errMsg ne null) {
      throw new AssertionError(errMsg)
    }
  }
}
