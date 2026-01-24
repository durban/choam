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

import scala.concurrent.duration._

import cats.syntax.all._
import cats.effect.kernel.Resource
import cats.effect.IO

import core.{ AsyncReactive, Ref, Rxn, Reactive }

final class ChoamRuntimeImplSpec extends ChoamRuntimeImplSpecPlatform {

  final override def munitIOTimeout: Duration =
    super.munitIOTimeout * 2

  test("Using multiple runtimes in parallel") {
    val N = 8192
    val res = for {
      rt12 <- Resource.both(ChoamRuntime.make[IO], ChoamRuntime.make[IO])
      rt3 <- ChoamRuntime.make[IO]
      rt4 <- ChoamRuntime.make[IO]
      ar1 <- AsyncReactive.from[IO](rt12._1)
      ar2 <- AsyncReactive.from[IO](rt12._2)
      ar3 <- AsyncReactive.from[IO](rt3)
      ar4 <- AsyncReactive.from[IO](rt4)
    } yield (ar1, ar2, ar3, ar4)
    res.use {
      case (ar1, ar2, ar3, ar4) =>
        for {
          r1 <- ar1.run(Ref(0))
          r2 <- ar2.run(Ref(0))
          r3 <- ar3.run(Ref(0))
          r4 <- ar4.run(Ref(0))
          _ <- IO.both(
            IO.both(
              IO.cede *> ar1.run(r1.update(_ + 1) *> r2.update(_ + 1)).replicateA_(N),
              ar2.run(r3.update(_ + 1) *> r4.update(_ + 1)).replicateA_(N),
            ),
            IO.both(
              IO.cede *> ar3.run(r3.update(_ + 1) *> r1.update(_ + 1)).replicateA_(N),
              ar4.run(r4.update(_ + 1) *> r2.update(_ + 1)).replicateA_(N),
            ),
          )
          v1 <- ar4.run(r1.get)
          v2 <- ar3.run(r2.get)
          v3 <- ar2.run(r3.get)
          v4 <- ar1.run(r4.get)
          _ <- IO(assertEquals(v1, 2 * N))
          _ <- IO(assertEquals(v2, 2 * N))
          _ <- IO(assertEquals(v3, 2 * N))
          _ <- IO(assertEquals(v4, 2 * N))
          uuid <- ar1.run(Rxn.newUuid)
        } yield (r1, r2, uuid)
    }.flatMap {
      case (r1, r2, uuid1) =>
        // after the RTs are closed, we initialize and use a new one:
        val res = for {
          rt <- ChoamRuntime.make[IO]
          ar <- AsyncReactive.from[IO](rt)
        } yield ar
        res.use { ar =>
          for {
            v2 <- ar.run(r1.update(_ + 1) *> r2.get)
            _ <- IO(assertEquals(v2, 2 * N))
            _ <- assertIO(ar.run(r1.get), (2 * N) + 1)
            uuid2 <- ar.run(Rxn.newUuid)
            _ <- IO(assertNotEquals(uuid1, uuid2))
          } yield ()
        }
    }
  }

  test("Creating/closing runtimes concurrently") {
    val (n, k) = (256, 16)
    for {
      ref <- ChoamRuntime.make[IO].use { rt =>
        AsyncReactive.from[IO](rt).use { implicit ar =>
          Ref(0).run[IO]
        }
      }
      tsk = ChoamRuntime.make[IO].use { rt =>
        AsyncReactive.from[IO](rt).use { implicit ar =>
          ref.update(_ + 1).run[IO]
        }
      }
      _ <- tsk.parReplicateA_(n).replicateA_(k)
      _ <- ChoamRuntime.make[IO].use { rt =>
        AsyncReactive.from[IO](rt).use { implicit ar =>
          assertIO(ref.get.run[IO], n * k)
        }
      }
    } yield ()
  }

  test("Refcounts") {
    ChoamRuntime.make[IO].use { rt1 =>
      assertIO(IO(ChoamRuntime.getRefCntForTesting(rt1)), Right(1L)) *> ChoamRuntime.make[IO].use { rt2 =>
        for {
          _ <- assertIO(IO(ChoamRuntime.getRefCntForTesting(rt1)), Right(2L))
          _ <- assertIO(IO(ChoamRuntime.getRefCntForTesting(rt2)), Right(2L))
        } yield rt2
      }.flatMap { rt2 =>
        for {
          _ <- assertIO(IO(ChoamRuntime.getRefCntForTesting(rt1)), Right(1L))
          _ <- assertIO(IO(ChoamRuntime.getRefCntForTesting(rt2)), Right(1L))
        } yield rt1
      }
    }.flatMap { rt1 =>
      assertIO(IO(ChoamRuntime.getRefCntForTesting(rt1)), Left(0L)).flatMap { _ =>
        ChoamRuntime.make[IO].use { rt3 =>
          for {
            _ <- assertIO(IO(ChoamRuntime.getRefCntForTesting(rt1)), Left(1L))
            _ <- assertIO(IO(ChoamRuntime.getRefCntForTesting(rt3)), Right(1L))
          } yield ()
        }
      }
    }
  }

  test("Close last / create new race") {
    val t = (IO.ref(false), IO.ref(false)).flatMapN { case (closed1, closed2) =>
      ChoamRuntime.make[IO].allocated.flatMap { case (rt1, close1) =>
        val close1Idempotent = closed1.getAndSet(true).ifM(IO.unit, close1)
        val inner = assertIO(IO(ChoamRuntime.getRefCntForTesting(rt1)), Right(1L)).flatMap { _ =>
          Reactive.from[IO](rt1).use { implicit rea => Ref("a").run }.flatMap { ref1 =>
            IO.both(
              close1Idempotent,
              ChoamRuntime.make[IO].allocated
            ).flatMap { case ((), (rt2, close2)) =>
              val close2Idempotent = closed2.getAndSet(true).ifM(IO.unit, close2)
              val inner2 = assertIO(IO(ChoamRuntime.getRefCntForTesting(rt2)), Right(1L)).flatMap { _ =>
                IO(ChoamRuntime.getRefCntForTesting(rt1)).flatMap {
                  case Right(1L) =>
                    assertIOBoolean(IO(rt1 eq rt2))
                  case Left(1L) =>
                    assertIOBoolean(IO(rt1 ne rt2))
                  case x =>
                    IO(fail(s"unexpected result: ${x}"))
                } *> assertIO(IO(ChoamRuntime.getRefCntForTesting(rt2)), Right(1L)).flatMap { _ =>
                  Reactive.from[IO](rt2).use { implicit rea =>
                    Ref("b").run[IO].flatMap { ref2 =>
                      assertIOBoolean(IO(ref1.loc.id != ref2.loc.id)).flatMap { _ =>
                        Ref.swap(ref1, ref2).run[IO] *> assertIO(ref1.get.run, "b") *> assertIO(ref2.get.run, "a")
                      }
                    }
                  }
                }
              }
              inner2.guarantee(close2Idempotent)
            }
          }
        }
        inner.guarantee(close1Idempotent)
      }
    }
    t.replicateA_(1000)
  }
}
