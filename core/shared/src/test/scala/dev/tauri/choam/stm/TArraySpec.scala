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
package stm

import scala.concurrent.duration._

import cats.effect.IO

final class TArraySpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with TArraySpec[IO]

trait TArraySpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  protected def newTArray[A](size: Int, initial: A): F[TArray[A]] =
    TArray[A](size, initial).commit

  test("TRef#unsafeGet/Set/Update") {
    for {
      arr <- newTArray(3, "a")
      _ <- assertEqualsF(arr.length, 3)
      _ <- assertResultF(arr.unsafeGet(0).commit, "a")
      _ <- assertResultF(arr.unsafeGet(1).commit, "a")
      _ <- assertResultF(arr.unsafeGet(2).commit, "a")
      _ <- arr.unsafeSet(0, "b").commit
      _ <- assertResultF(arr.unsafeGet(0).commit, "b")
      _ <- assertResultF(arr.unsafeGet(1).commit, "a")
      _ <- assertResultF(arr.unsafeGet(2).commit, "a")
      _ <- arr.unsafeUpdate(0)(_ + "c").commit
      _ <- assertResultF(arr.unsafeGet(0).commit, "bc")
      _ <- assertResultF(arr.unsafeGet(1).commit, "a")
      _ <- assertResultF(arr.unsafeGet(2).commit, "a")
      _ <- arr.unsafeSet(1, "x").commit
      _ <- arr.unsafeSet(2, "y").commit
      _ <- assertResultF(arr.unsafeGet(0).commit, "bc")
      _ <- assertResultF(arr.unsafeGet(1).commit, "x")
      _ <- assertResultF(arr.unsafeGet(2).commit, "y")
      _ <- assertF(Either.catchNonFatal(arr.unsafeGet(-1)).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeGet(3)).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeGet(Int.MaxValue)).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeSet(-1, "")).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeSet(3, "")).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeSet(Int.MaxValue, "")).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeUpdate(-1)(_ + "!")).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeUpdate(3)(_ + "!")).isLeft)
      _ <- assertF(Either.catchNonFatal(arr.unsafeUpdate(Int.MaxValue)(_ + "!")).isLeft)
      _ <- assertResultF(arr.unsafeGet(0).commit, "bc")
      _ <- assertResultF(arr.unsafeGet(1).commit, "x")
      _ <- assertResultF(arr.unsafeGet(2).commit, "y")
    } yield ()
  }

  test("TRef#get/set/update") {
    for {
      arr <- newTArray(3, "a")
      _ <- assertResultF(arr.get(0).commit, Some("a"))
      _ <- assertResultF(arr.get(1).commit, Some("a"))
      _ <- assertResultF(arr.get(2).commit, Some("a"))
      _ <- assertResultF(arr.get(-1).commit, None)
      _ <- assertResultF(arr.get(3).commit, None)
      _ <- assertResultF(arr.set(0, "b").commit, true)
      _ <- assertResultF(arr.get(0).commit, Some("b"))
      _ <- assertResultF(arr.get(1).commit, Some("a"))
      _ <- assertResultF(arr.get(2).commit, Some("a"))
      _ <- assertResultF(arr.set(3, "x").commit, false)
      _ <- assertResultF(arr.get(0).commit, Some("b"))
      _ <- assertResultF(arr.get(1).commit, Some("a"))
      _ <- assertResultF(arr.get(2).commit, Some("a"))
      _ <- assertResultF(arr.update(0)(_ + "c").commit, true)
      _ <- assertResultF(arr.get(0).commit, Some("bc"))
      _ <- assertResultF(arr.get(1).commit, Some("a"))
      _ <- assertResultF(arr.get(2).commit, Some("a"))
      _ <- assertResultF(arr.update(3)(_ + "x").commit, false)
      _ <- assertResultF(arr.get(0).commit, Some("bc"))
      _ <- assertResultF(arr.get(1).commit, Some("a"))
      _ <- assertResultF(arr.get(2).commit, Some("a"))
    } yield ()
  }

  test("Various allocation strategies") {
    def checkArr(arr: TArray[Int]): F[Unit] = for {
      _ <- arr.unsafeSet(0, 42).commit
      _ <- assertResultF(arr.unsafeGet(0).commit, 42)
      fib <- arr.unsafeGet(0).flatMap { v =>
        Txn.check(v < 0)
      }.commit.start
      _ <- F.sleep(0.1.seconds)
      _ <- arr.unsafeSet(0, -1).commit
      _ <- fib.joinWithNever
    } yield ()
    for {
      arr1 <- TArray[Int](1, 0, TArray.DefaultAllocationStrategy).commit // NB: this is sparse && flat
      _ <- checkArr(arr1)
      arr2 <- TArray[Int](1, 0, AllocationStrategy(sparse = true, flat = false, padded = false)).commit
      _ <- checkArr(arr2)
      arr3 <- TArray[Int](1, 0, AllocationStrategy(sparse = false, flat = false, padded = false)).commit
      _ <- checkArr(arr3)
      arr4 <- TArray[Int](1, 0, AllocationStrategy(sparse = false, flat = true, padded = false)).commit
      _ <- checkArr(arr4)
    } yield ()
  }

  test("TArray of size 0") {
    for {
      arr <- TArray[String](0, "foo").commit
      _ <- assertResultF(arr.get(0).commit, None)
    } yield ()
  }
}
