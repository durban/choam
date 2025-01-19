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

import cats.effect.kernel.Deferred
import cats.effect.Outcome.{ Canceled, Succeeded, Errored }
import cats.effect.IO

final class TxnSpecTicked_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TxnSpecTicked[IO]

trait TxnSpecTicked[F[_]] extends TxnBaseSpec[F] with TestContextSpec[F] { this: McasImplSpec =>

  private def txn1(r: TRef[F, Int]): Txn[F, String] = {
    r.get.flatMap {
      case i if i < 0 => Txn.retry
      case i => Txn.pure(i.toString)
    }
  }

  private def txn1Def(r: TRef[F, Int], d: Deferred[F, String]): F[String] = {
    txnToDef(txn1(r), d)
  }

  private def getEven(r: TRef[F, Int]): Txn[F, String] = {
    for {
      v <- r.get
      _ <- Txn.check((v % 2) == 0)
    } yield v.toString
  }

  private def txnToDef(txn: Txn[F, String], d: Deferred[F, String]): F[String] = {
    txn.commit.guaranteeCase {
      case Canceled() =>
        d.complete("cancelled").void
      case Succeeded(fa) =>
        fa.flatMap(a => d.complete(a).void)
      case Errored(ex) =>
        d.complete(s"errored: $ex\n" + ex.getMessage).void
    }
  }

  test("Txn.retry") {
    for {
      r <- TRef[F, Int](-1).commit
      d <- Deferred[F, String]
      fib <- txn1Def(r, d).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- r.set(1).commit
      _ <- assertResultF(d.get, "1")
      _ <- fib.joinWithNever
    } yield ()
  }

  test("Txn.check") {
    for {
      r <- TRef[F, Int](1).commit
      d <- Deferred[F, String]
      fib <- txnToDef(getEven(r), d).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- r.set(4).commit
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, Some("4"))
      _ <- fib.joinWithNever
    } yield ()
  }

  test("Txn.retry should be cancellable") {
    for {
      r <- TRef[F, Int](-1).commit
      fib <- txn1(r).commit.start
      _ <- this.tickAll
      _ <- fib.cancel
      _ <- assertResultF(fib.join, Canceled[F, Throwable, String]())
    } yield ()
  }

  test("Txn.retry should retry if a TRef read in any alt changes".ignore) { // TODO: expected failure
    for {
      r1 <- TRef[F, Int](-1).commit
      r2 <- TRef[F, Int](-1).commit
      d1 <- Deferred[F, String]
      d2 <- Deferred[F, String]
      tsk1 = txnToDef(txn1(r1) orElse txn1(r2), d1)
      tsk2 = txnToDef(txn1(r1) orElse txn1(r2), d2)
      // 2nd alt:
      fib1 <- tsk1.start
      _ <- this.tickAll
      _ <- assertResultF(d1.tryGet, None)
      _ <- r2.set(2).commit
      _ <- this.tickAll
      _ <- assertResultF(d1.tryGet, Some("2"))
      _ <- fib1.joinWithNever
      // reset:
      _ <- r2.set(-1).commit
      // 1st alt:
      fib2 <- tsk2.start
      _ <- this.tickAll
      _ <- assertResultF(d2.tryGet, None)
      _ <- r1.set(1).commit
      _ <- this.tickAll
      _ <- assertResultF(d2.tryGet, Some("1")) // TODO: this fails
      _ <- fib2.joinWithNever
    } yield ()
  }
}
