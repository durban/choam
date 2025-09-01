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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.kernel.{ Deferred, Outcome }
import cats.effect.kernel.Outcome.{ Canceled, Succeeded, Errored }
import cats.effect.IO

import internal.mcas.MemoryLocation

final class TxnSpecTicked_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with TxnSpecTicked[IO]

trait TxnSpecTicked[F[_]] extends TxnBaseSpecTicked[F] { this: McasImplSpec =>

  private def txn1(r: TRef[Int]): Txn[String] = {
    r.get.flatMap {
      case i if i < 0 => Txn.retry
      case i => Txn.pure(i.toString)
    }
  }

  private def txn1Def(r: TRef[Int], d: Deferred[F, String]): F[String] = {
    txnToDef(txn1(r), d)
  }

  private def getEven(r: TRef[Int]): Txn[String] = {
    for {
      v <- r.get
      _ <- Txn.check((v % 2) == 0)
    } yield v.toString
  }

  private def txnToDef(txn: Txn[String], d: Deferred[F, String]): F[String] = {
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
      r <- TRef[Int](-1).commit
      d <- Deferred[F, String]
      fib <- txn1Def(r, d).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- r.set(1).commit
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, Some("1"))
      _ <- assertResultF(d.get, "1")
      _ <- assertResultF(fib.joinWithNever, "1")
    } yield ()
  }

  test("Txn.retry with no refs read") {
    for {
      d <- Deferred[F, String]
      fib <- txnToDef(Txn.retry[String], d).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- fib.cancel
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, Some("cancelled"))
      _ <- assertResultF(fib.join, Outcome.canceled[F, Throwable, String])
    } yield ()
  }

  test("Txn.check") {
    for {
      r <- TRef[Int](1).commit
      d <- Deferred[F, String]
      fib <- txnToDef(getEven(r), d).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- r.set(4).commit
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, Some("4"))
      _ <- assertResultF(fib.joinWithNever, "4")
    } yield ()
  }

  test("Txn.retry should be cancellable") {
    for {
      r <- TRef[Int](-1).commit
      d <- F.deferred[Unit]
      fib <- txn1(r).commit.guarantee(d.complete(()).void).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- fib.cancel
      _ <- assertResultF(fib.join, Canceled[F, Throwable, String]())
      _ <- assertResultF(d.tryGet, Some(()))
    } yield ()
  }

  test("Txn.retry should retry if a TRef read in any alt changes") {
    for {
      r1 <- TRef[Int](-1).commit
      r2 <- TRef[Int](-1).commit
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
      _ <- assertResultF(fib1.joinWithNever, "2")
      // reset:
      _ <- r2.set(-1).commit
      // 1st alt:
      fib2 <- tsk2.start
      _ <- this.tickAll
      _ <- assertResultF(d2.tryGet, None)
      _ <- r1.set(1).commit
      _ <- this.tickAll
      _ <- assertResultF(d2.tryGet, Some("1"))
      _ <- assertResultF(fib2.joinWithNever, "1")
    } yield ()
  }

  private final def numberOfListeners[A](ref: TRef[A]): F[Int] = F.delay {
    ref.asInstanceOf[MemoryLocation.WithListeners].unsafeNumberOfListeners()
  }

  test("Txn.retry should unsubscribe from TRefs when cancelled") {
    for {
      r0 <- TRef[Int](0).commit
      r1 <- TRef[Int](0).commit
      r2 <- TRef[Int](0).commit
      fib <- (r0.get *> (r1.get.flatMap(v1 => Txn.check(v1 > 0)) orElse r2.get.flatMap(v2 => Txn.check(v2 > 0)))).commit.start
      _ <- this.tickAll
      _ <- assertResultF(numberOfListeners(r0), 1)
      _ <- assertResultF(numberOfListeners(r1), 1)
      _ <- assertResultF(numberOfListeners(r2), 1)
      _ <- fib.cancel
      _ <- assertResultF(fib.join, Canceled[F, Throwable, Unit]())
      _ <- this.tickAll
      _ <- assertResultF(numberOfListeners(r0), 0)
      _ <- assertResultF(numberOfListeners(r1), 0)
      _ <- assertResultF(numberOfListeners(r2), 0)
    } yield ()
  }

  test("Txn.retry should unsubscribe from TRefs when completed") {
    for {
      r0 <- TRef[Int](0).commit
      r1 <- TRef[Int](0).commit
      r2 <- TRef[Int](0).commit
      fib <- (r0.get *> (r1.get.flatMap(v1 => Txn.check(v1 > 0)) orElse r2.get.flatMap(v2 => Txn.check(v2 > 0)))).commit.start
      _ <- this.tickAll
      _ <- assertResultF(numberOfListeners(r0), 1)
      _ <- assertResultF(numberOfListeners(r1), 1)
      _ <- assertResultF(numberOfListeners(r2), 1)
      _ <- r2.set(1).commit
      _ <- this.tickAll
      _ <- assertResultF(fib.joinWithNever, ())
      _ <- assertResultF(numberOfListeners(r0), 0)
      _ <- assertResultF(numberOfListeners(r1), 0)
      _ <- assertResultF(numberOfListeners(r2), 0)
    } yield ()
  }

  test("Txn.retry should unsubscribe from TRefs when it doesn't suspend (due to concurrent change)") {
    for {
      d <- F.deferred[Unit]
      r0 <- TRef[Int](0).commit
      r1 <- TRef[Int](0).commit
      stepper <- mkStepper
      txn = (r0.get *> (r1.get.flatMap { v1 => Txn.check(v1 > 0) } orElse r1.get.flatMap { v1 => Txn.check(v1 < 0) }))
      fib <- stepper.commit(txn).guarantee(d.complete(()).void).start
      _ <- this.tickAll // we're stopping at the `v1 > 0` retry
      // another transaction changes `r1`:
      _ <- r1.set(1).commit
      _ <- stepper.stepAndTickAll // we're stopping at the `v1 < 0` retry
      _ <- assertResultF(d.tryGet, None)
      _ <- stepper.stepAndTickAll // mustn't suspend
      _ <- assertResultF(d.tryGet, Some(()))
      _ <- fib.joinWithNever
      _ <- assertResultF(numberOfListeners(r0), 0)
      _ <- assertResultF(numberOfListeners(r1), 0)
    } yield ()
  }

  test("Txn.retry should not be cancellable when it doesn't suspend (due to concurrent change)".fail) {
    for {
      d <- F.deferred[Unit]
      r0 <- TRef[Int](0).commit
      r1 <- TRef[Int](0).commit
      stepper <- mkStepper
      txn = (r0.get *> (r1.get.flatMap { v1 => Txn.check(v1 > 0) } orElse r1.get.flatMap { v1 => Txn.check(v1 < 0) }))
      fib <- stepper.commit(txn).guarantee(d.complete(()).void).start
      _ <- this.tickAll // we're stopping at the `v1 > 0` retry
      // another transaction changes `r1`:
      _ <- r1.set(1).commit
      _ <- stepper.stepAndTickAll // we're stopping at the `v1 < 0` retry
      _ <- assertResultF(d.tryGet, None)
      // trying to cancel:
      cancelFib <- fib.cancel.start
      _ <- stepper.stepAndTickAll // mustn't suspend
      _ <- assertResultF(d.tryGet, Some(()))
      _ <- fib.joinWithNever
      _ <- assertResultF(numberOfListeners(r0), 0)
      _ <- assertResultF(numberOfListeners(r1), 0)
      _ <- cancelFib.joinWithNever
    } yield ()
  }

  test("Run with Stepper") {
    def checkPositive(ref: TRef[Int], ctr: AtomicInteger): Txn[Unit] =
      Txn.unsafe.delay { ctr.incrementAndGet() } *> ref.get.flatMap { v => Txn.check(v > 0) }
    for {
      d <- F.deferred[Unit]
      c1 <- F.delay(new AtomicInteger)
      r1 <- TRef[Int](0).commit
      c2 <- F.delay(new AtomicInteger)
      r2 <- TRef[Int](0).commit
      c3 <- F.delay(new AtomicInteger)
      r3 <- TRef[Int](0).commit
      c4 <- F.delay(new AtomicInteger)
      r4 <- TRef[Int](0).commit
      txn = checkPositive(r1, c1) orElse checkPositive(r2, c2) orElse checkPositive(r3, c3) orElse checkPositive(r4, c4)
      stepper <- mkStepper
      fib <- stepper.commit(txn).guarantee(d.complete(()).void).start
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- assertResultF(F.delay(c1.get()), 1)
      _ <- assertResultF(F.delay(c2.get()), 0)
      _ <- assertResultF(F.delay(c3.get()), 0)
      _ <- assertResultF(F.delay(c4.get()), 0)
      _ <- stepper.stepAndTickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- assertResultF(F.delay(c1.get()), 1)
      _ <- assertResultF(F.delay(c2.get()), 1)
      _ <- assertResultF(F.delay(c3.get()), 0)
      _ <- assertResultF(F.delay(c4.get()), 0)
      _ <- stepper.stepAndTickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- assertResultF(F.delay(c1.get()), 1)
      _ <- assertResultF(F.delay(c2.get()), 1)
      _ <- assertResultF(F.delay(c3.get()), 1)
      _ <- assertResultF(F.delay(c4.get()), 0)
      _ <- stepper.stepAndTickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- assertResultF(F.delay(c1.get()), 1)
      _ <- assertResultF(F.delay(c2.get()), 1)
      _ <- assertResultF(F.delay(c3.get()), 1)
      _ <- assertResultF(F.delay(c4.get()), 1)
      _ <- stepper.stepAndTickAll // suspends until changed
      _ <- assertResultF(stepper.stepAndTickAll.attempt.map(_.isLeft), true)
      _ <- assertResultF(stepper.stepAndTickAll.attempt.map(_.isLeft), true)
      _ <- r2.set(1).commit
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- assertResultF(F.delay(c1.get()), 2)
      _ <- assertResultF(F.delay(c2.get()), 1)
      _ <- assertResultF(F.delay(c3.get()), 1)
      _ <- assertResultF(F.delay(c4.get()), 1)
      _ <- stepper.stepAndTickAll
      _ <- assertResultF(d.tryGet, Some(()))
      _ <- assertResultF(F.delay(c1.get()), 2)
      _ <- assertResultF(F.delay(c2.get()), 2)
      _ <- assertResultF(F.delay(c3.get()), 1)
      _ <- assertResultF(F.delay(c4.get()), 1)
      _ <- fib.joinWithNever
    } yield ()
  }
}
