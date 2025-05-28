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
package core

import cats.Monad
import cats.effect.IO

// TODO: figure out if this can run on Scala.js

final class RxnImplSpec_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RxnImplSpec[IO]

final class RxnImplSpec_FlakyEMCAS_IO
  extends BaseSpecIO
  with SpecFlakyEMCAS
  with RxnImplSpec[IO]

final class RxnImplSpec_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with RxnImplSpec[IO]

final class RxnImplSpec_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with RxnImplSpec[zio.Task]

final class RxnImplSpec_FlakyEMCAS_ZIO
  extends BaseSpecZIO
  with SpecFlakyEMCAS
  with RxnImplSpec[zio.Task]

final class RxnImplSpec_SpinLockMcas_ZIO
  extends BaseSpecZIO
  with SpecSpinLockMcas
  with RxnImplSpec[zio.Task]

/** Specific implementation tests, which should also pass with `SpecFlakyEMCAS` */
trait RxnImplSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  private def computeStackLimit(): Int = {
    // NOT @tailrec
    def factorial(n: Long): Long = {
      if (n == 0L) 1L
      else n * factorial(n - 1L)
    }

    def tryOnce(n: Int): Boolean = {
      assert(n > 0)
      try { factorial(n.toLong); true } catch {
        case _: StackOverflowError =>
          false
      }
    }

    def compute(n: Int = 32): Int = {
      if (tryOnce(n)) compute(n * 2)
      else n
    }

    val res = compute()
    println(s"STACK_LIMIT <= ${res}")
    res
  }

  test("Creating and running deeply nested Rxn's should both be stack-safe") {
    val one = Rxn.lift[Int, Int](_ + 1)
    def nest(
      n: Int,
      combine: (Rxn[Int, Int], Rxn[Int, Int]) => Rxn[Int, Int]
    ): Rxn[Int, Int] = {
      (1 to n).map(_ => one).reduce(combine)
    }
    val N = computeStackLimit() * 4
    val r1: Rxn[Int, Int] = nest(N, _ >>> _)
    val r2: Rxn[Int, Int] = nest(N, (x, y) => (x * y).map(_._1 + 1))
    val r3: Rxn[Int, Int] = nest(N, (x, y) => x.flatMap { _ => y })
    val r3left: Rxn[Int, Int] = (1 to N).foldLeft(one) { (acc, _) =>
      acc.flatMap { _ => one }
    }
    val r3right: Rxn[Int, Int] = (1 to N).foldLeft(one) { (acc, _) =>
      one.flatMap { _ => acc }
    }
    val r4: Rxn[Int, Int] = nest(N, _ >> _)
    val r5: Rxn[Int, Int] = nest(N, _ + _)
    val r7: Rxn[Int, Int] = Monad[Rxn[Int, *]].tailRecM(N) { n =>
      if (n > 0) Rxn.lift[Int, Either[Int, Int]](_ => Left(n - 1))
      else Rxn.ret(Right(99))
    }
    assertEquals(r1.unsafePerform(42, this.mcasImpl), 42 + N)
    assertEquals(r2.unsafePerform(42, this.mcasImpl), 42 + N)
    assertEquals(r3.unsafePerform(42, this.mcasImpl), 42 + 1)
    assertEquals(r3left.unsafePerform(42, this.mcasImpl), 42 + 1)
    assertEquals(r3right.unsafePerform(42, this.mcasImpl), 42 + 1)
    assertEquals(r4.unsafePerform(42, this.mcasImpl), 42 + 1)
    assertEquals(r5.unsafePerform(42, this.mcasImpl), 42 + 1)
    assertEquals(r7.unsafePerform(42, this.mcasImpl), 99)

    def rNegativeTest: Rxn[Int, Int] = {
      // NOT @tailrec
      def go(n: Int): Rxn[Int, Int] = {
        if (n < 1) one
        else one *> go(n - 1) // *> is strict
      }
      go(N * 4)
    }
    try {
      rNegativeTest.unsafePerform(42, this.mcasImpl)
      this.fail("unexpected success")
    } catch {
      case _: StackOverflowError =>
        () // OK
    }

    def rPositiveTest: Rxn[Int, Int] = {
      // NOT @tailrec
      def go(n: Int): Rxn[Int, Int] = {
        if (n < 1) one
        else one >> go(n - 1) // >> is lazy
      }
      go(N * 4)
    }
    assertEquals(rPositiveTest.unsafePerform(42, this.mcasImpl), 42 + 1)
  }

  test("first and second") {
    for {
      _ <- F.unit
      rea = Rxn.lift[Int, String](_.toString).first[Boolean]
      _ <- assertResultF(F.delay { rea.unsafePerform((42, true), this.mcasImpl) }, ("42", true))
      rea = Rxn.lift[Int, String](_.toString).second[Float]
      _ <- assertResultF(F.delay { rea.unsafePerform((1.5f, 21), this.mcasImpl) }, (1.5f, "21"))
    } yield ()
  }

  test("postCommit") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      c <- Ref("c").run[F]
      rea = Rxn.unsafe.cas(a, "a", "aa").postCommit(
        Rxn.unsafe.cas(b, "b", "bb").postCommit(Rxn.unsafe.cas(c, "c", "cc"))
      ).postCommit(
        Rxn.unsafe.cas(b, "bb", "bbb").postCommit(Rxn.unsafe.cas(c, "cc", "ccc"))
      ).postCommit(
        Rxn.unsafe.cas(b, "bbb", "bbbb").postCommit(Rxn.unsafe.cas(c, "ccc", "cccc"))
      )
      _ <- assertResultF(rea.run[F], ())
      _ <- assertResultF(Rxn.unsafe.directRead(a).run, "aa")
      _ <- assertResultF(Rxn.unsafe.directRead(b).run, "bbbb")
      _ <- assertResultF(Rxn.unsafe.directRead(c).run, "cccc")
    } yield ()
  }
}
