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
package core

import cats.effect.IO

import unsafe.RxnLocal

final class RxnLocalSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with RxnLocalSpec[IO]

trait RxnLocalSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("RxnLocal (simple)") {
    for {
      ref <- Ref[Int](0).run[F]
      rxn = for {
        local <- Rxn.unsafe.newLocal(42)
        ov <- local.get
        _ <- ref.set(ov)
        _ <- local.set(99)
        nv <- local.get
      } yield ("foo", nv)
      _ <- assertResultF(rxn.map(tup => (tup._1 + "bar", tup._2)).run, ("foobar", 99))
      _ <- assertResultF(ref.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal (simple, leaked)") {
    for {
      ref <- Ref[Int](0).run[F]
      local <- Rxn.unsafe.newLocal(42).run
      rxn = for {
        ov <- local.get
        _ <- ref.set(ov)
        _ <- local.set(99)
        nv <- local.get
      } yield ("foo", nv)
      _ <- assertResultF(rxn.map(tup => (tup._1 + "bar", tup._2)).run, ("foobar", 99))
      _ <- assertResultF(ref.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal.Array (simple)") {
    for {
      ref <- Ref[(Int, Int, Int)]((0, 0, 0)).run[F]
      ref2 <- Ref[Int](0).run[F]
      rxn = for {
        arr <- Rxn.unsafe.newLocalArray(size = 3, initial = 42)
        ov0 <- arr.unsafeGet(0)
        ov1 <- arr.unsafeGet(1)
        ov2 <- arr.unsafeGet(2)
        _ <- ref.set((ov0, ov1, ov2))
        _ <- arr.unsafeSet(1, 99)
        nv <- arr.unsafeGet(1)
        _ <- ref2.set(nv)
      } yield "foo"
      _ <- assertResultF(rxn.map(_ + "bar").run, "foobar")
      _ <- assertResultF(ref.get.run[F], (42, 42, 42))
      _ <- assertResultF(ref2.get.run[F], 99)
    } yield ()
  }

  test("RxnLocal.Array (simple, leaked)") {
    for {
      ref <- Ref[(Int, Int, Int)]((0, 0, 0)).run[F]
      ref2 <- Ref[Int](0).run[F]
      arr <- Rxn.unsafe.newLocalArray(size = 3, initial = 42).run
      rxn = for {
        ov0 <- arr.unsafeGet(0)
        ov1 <- arr.unsafeGet(1)
        ov2 <- arr.unsafeGet(2)
        _ <- ref.set((ov0, ov1, ov2))
        _ <- arr.unsafeSet(1, 99)
        nv <- arr.unsafeGet(1)
        _ <- ref2.set(nv)
      } yield "foo"
      _ <- assertResultF(rxn.map(_ + "bar").run, "foobar")
      _ <- assertResultF(ref.get.run[F], (42, 42, 42))
      _ <- assertResultF(ref2.get.run[F], 99)
    } yield ()
  }

  test("RxnLocal (compose with Rxn)") {
    val rxn: Rxn[Int] = for {
      ref <- Ref[Int](0)
      scratch <- Rxn.unsafe.newLocal(42)
      i <- ref.get
      _ <- scratch.set(i)
      _ <- scratch.update(_ + 1)
      v0 <- scratch.get
      _ <- ref.set(v0)
      v <- ref.get
    } yield v
    assertResultF(rxn.run[F], 1)
  }

  test("RxnLocal (compose with Rxn, leaked)") {
    def rxn(scratch: RxnLocal[Int]): Rxn[Int] = for {
      ref <- Ref[Int](0)
      i <- ref.get
      _ <- scratch.set(i)
      _ <- scratch.update(_ + 1)
      v0 <- scratch.get
      _ <- ref.set(v0)
      v <- ref.get
    } yield v
    for {
      scratch <- Rxn.unsafe.newLocal(42).run[F]
      _ <- assertResultF(rxn(scratch).run[F], 1)
    } yield ()
  }

  test("RxnLocal (rollback)") {
    for {
      ref <- Ref[Int](0).run[F]
      v <- (for {
        local <- Rxn.unsafe.newLocal(0)
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- local.getAndUpdate(_ + 1)
        res <- if (leftOrRight == 0) { // left
          if (ov == 0) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 0) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield res).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal (rollback, leaked)") {
    for {
      ref <- Ref[Int](0).run[F]
      local <- Rxn.unsafe.newLocal(0).run
      v <- (for {
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- local.getAndUpdate(_ + 1)
        res <- if (leftOrRight == 0) { // left
          if (ov == 0) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 0) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield res).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal.Array (rollback)") {
    for {
      ref <- Ref[Int](0).run[F]
      v <- (for {
        arr <- RxnLocal.newLocalArray(size = 3, initial = 0)
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal.Array (rollback, leaked)") {
    for {
      ref <- Ref[Int](0).run[F]
      arr <- RxnLocal.newLocalArray(size = 3, initial = 0).run
      v <- (for {
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal (nested)") {
    for {
      ref1 <- Ref[Int](0).run[F]
      ref2 <- Ref[Int](0).run[F]
      rxn1 = for {
        local1 <- Rxn.unsafe.newLocal(42)
        ov1 <- local1.get
        _ <- Rxn.unsafe.assert(ov1 == 42)
        local2 <- Rxn.unsafe.newLocal(99)
        ov2 <- local2.get
        _ <- Rxn.unsafe.assert(ov2 == 99)
        _ <- local2.set(42)
        retry <- Rxn.fastRandom.nextBoolean
        _ <- if (retry) {
          Rxn.unsafe.retry[Unit]
        } else {
          Rxn.unit
        }
        v2 <- local2.get
        _ <- ref2.set(v2)
        _ <- ref1.set(ov1)
        _ <- local1.set(99)
        x <- local1.get
      } yield x.toString
      _ <- assertResultF(rxn1.run, "99")
      _ <- assertResultF(ref1.get.run[F], 42)
      _ <- assertResultF(ref2.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal (nested, leaked)") {
    for {
      ref1 <- Ref[Int](0).run[F]
      ref2 <- Ref[Int](0).run[F]
      local1 <- Rxn.unsafe.newLocal(42).run
      local2 <- Rxn.unsafe.newLocal(99).run
      rxn1 = for {
        ov1 <- local1.get
        _ <- Rxn.unsafe.assert(ov1 == 42)
        ov2 <- local2.get
        _ <- Rxn.unsafe.assert(ov2 == 99)
        _ <- local2.set(42)
        retry <- Rxn.fastRandom.nextBoolean
        _ <- if (retry) {
          Rxn.unsafe.retry[Unit]
        } else {
          Rxn.unit
        }
        v2 <- local2.get
        _ <- ref2.set(v2)
        _ <- ref1.set(ov1)
        _ <- local1.set(99)
        x <- local1.get
      } yield x.toString
      _ <- assertResultF(rxn1.run, "99")
      _ <- assertResultF(ref1.get.run[F], 42)
      _ <- assertResultF(ref2.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal (escaped local must be separate)") {
    for {
      la <- (for {
        local <- Rxn.unsafe.newLocal(42)
        arr <- Rxn.unsafe.newLocalArray(3, 42)
        _ <- local.set(99)
        _ <- arr.unsafeSet(1, 99)
      } yield (local, arr)).run
      (local, arr) = la
      v1v2 <- (for {
        v1 <- local.get
        v2 <- arr.unsafeGet(1)
      } yield (v1, v2)).run
      (v1, v2) = v1v2
      _ <- assertEqualsF(v1, 42)
      _ <- assertEqualsF(v2, 42)
    } yield ()
  }
}
