/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicInteger
import java.time.{ Duration => JDuration }
import java.time.temporal.ChronoUnit

import scala.concurrent.duration._

import cats.effect.IO

import munit.CatsEffectSuite

import io.github.timwspence.cats.stm.STM
import zio.{ stm => zstm }

/**
 * This test shows that the current semantics of
 * the `+` (choice) combinator of `Rxn` are different
 * from the semantics of `orElse` in typical STM
 * implementations (cats-stm, zstm and scala-stm are
 * shown here).
 *
 * We inherited the current behavior from reagents.
 * It is not clear, whether this is an intentional
 * divergence from STM, although the "disjunction
 * of conjunctions" mentioned in section 3.8 seems
 * to imply the current behavior.
 *
 * The reagents paper mentions (in section 7.2) an
 * intentional difference from Haskell STM `orElse`,
 * but that is about a different issue: trying the
 * right-hand side even if the failure is transient
 * (unlike Haskell STM). `Rxn` doesn't even have
 * permanent failure, so in this case we don't really
 * have a choice: `+` doesn't even make sense if we
 * don't try the right-hand side on a transient
 * failure.
 *
 * TODO: Figure out which `+` semantics we really want.
 */
final class OrElseSemanticsSpec extends CatsEffectSuite {

  test("cats-stm orElse") {
    STM.runtime[IO].flatMap { stm =>
      catsStmOrElse(stm)
    }
  }

  def catsStmOrElse(stm: STM[IO]): IO[Unit] = {
    import stm._

    def txn1(ref1: TVar[Int], ref2: TVar[Int], ref3: TVar[Int]) = for {
      _ <- (ref1.modify(_ + 1)) orElse (ref2.modify(_ + 1))
      v3 <- ref3.get
      _ <- check(v3 > 0)
    } yield ()

    def txn2(ref3: TVar[Int]) =
      ref3.modify(_ + 1)

    for {
      ref1 <- commit(TVar.of(0))
      ref2 <- commit(TVar.of(0))
      ref3 <- commit(TVar.of(0))
      fib1 <- commit(txn1(ref1, ref2, ref3)).start
      _ <- IO.sleep(1.second)
      _ <- commit(txn2(ref3))
      _ <- fib1.joinWithNever
      v1 <- commit(ref1.get)
      v2 <- commit(ref2.get)
      v3 <- commit(ref3.get)
      _ <- IO.println(s"cats-stm:  v1 = ${v1}, v2 = ${v2}, v3 = ${v3}")
    } yield ()
  }

  test("zstm orElse") {
    def txn1(ref1: zstm.TRef[Int], ref2: zstm.TRef[Int], ref3: zstm.TRef[Int]) = for {
      _ <- (ref1.update(_ + 1)) orElse (ref2.update(_ + 1))
      v3 <- ref3.get
      _ <- zstm.STM.check(v3 > 0)
    } yield ()

    def txn2(ref3: zstm.TRef[Int]) =
      ref3.update(_ + 1)

    val tsk: zio.Task[Unit] = for {
      ref1 <- zstm.TRef.makeCommit(0)
      ref2 <- zstm.TRef.makeCommit(0)
      ref3 <- zstm.TRef.makeCommit(0)
      fib1 <- zstm.STM.atomically(txn1(ref1, ref2, ref3)).fork
      _ <- zio.ZIO.sleep(JDuration.of(1L, ChronoUnit.SECONDS))
      _ <- zstm.STM.atomically(txn2(ref3))
      _ <- fib1.join
      v1 <- zstm.STM.atomically(ref1.get)
      v2 <- zstm.STM.atomically(ref2.get)
      v3 <- zstm.STM.atomically(ref3.get)
      _ <- zio.ZIO.attempt(println(s"zstm:      v1 = ${v1}, v2 = ${v2}, v3 = ${v3}"))
    } yield ()

    zio.Unsafe.unsafe { implicit z =>
      zio.Runtime.default.unsafe.run(tsk).getOrThrow()
    }
  }

  test("scala-stm orElse") {
    import scala.concurrent.stm.{ atomic, Ref, wrapChainedAtomic, retry }

    def txn1(ref1: Ref[Int], ref2: Ref[Int], ref3: Ref[Int]): Unit = atomic { implicit txn =>
      wrapChainedAtomic(atomic { implicit txn =>
        ref1.transform(_ + 1)
      }) orAtomic { implicit txn =>
        ref2.transform(_ + 1)
      }

      val v3 = ref3.get
      if (v3 > 0) ()
      else retry
    }

    def txn2(ref3: Ref[Int]): Unit = atomic { implicit txn =>
      ref3.transform(_ + 1)
    }

    val ref1 = Ref(0)
    val ref2 = Ref(0)
    val ref3 = Ref(0)

    for {
      fib1 <- IO.blocking { txn1(ref1, ref2, ref3) }.start
      _ <- IO.sleep(1.second)
      _ <- IO.blocking { txn2(ref3) }
      _ <- fib1.joinWithNever
      _ <- IO {
        println(s"scala-stm: v1 = ${ref1.single.get}, v2 = ${ref2.single.get}, v3 = ${ref3.single.get}")
      }
    } yield ()
  }

  test("choam orElse") {
    def rxn1(ref1: Ref[Int], ref2: Ref[Int], ref3: AtomicInteger) = for {
      _ <- (ref1.update(_ + 1)) + (ref2.update(_ + 1))
      // force a retry *once*:
      v3 <- Axn.unsafe.delay { ref3.getAndIncrement() }
      _ <- if (v3 > 0) {
        Rxn.unit
      } else {
        Rxn.unsafe.retry
      }
    } yield ()

    for {
      ref1 <- Ref.apply(0).run[IO]
      ref2 <- Ref.apply(0).run[IO]
      ref3 <- IO { new AtomicInteger(0) }
      _ <- rxn1(ref1, ref2, ref3).run[IO]
      v1 <- ref1.get.run[IO]
      v2 <- ref2.get.run[IO]
      v3 <- IO { ref3.get() }
      _ <- IO.println(s"choam:     v1 = ${v1}, v2 = ${v2}, v3 = ${v3}")
    } yield ()
  }
}
