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

package dev.tauri.choam
package stm

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

import cats.effect.{ IO, Deferred }
import cats.effect.Outcome.{ Canceled, Succeeded, Errored }

import internal.mcas.MemoryLocation
import internal.mcas.Consts

final class TxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with TxnSpec[IO]

final class TxnSpecTicked_ThreadConfinedMcas_IO
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with TxnSpecTicked[IO]

trait TxnSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  test("Hello World") {
    def txn(r: TRef[F, Int]): Txn[F, (Int, Int)] = for {
      v0 <- r.get
      _ <- r.set(99)
      v1 <- r.get
    } yield (v0, v1)

    for {
      r <- TRef[F, Int](42).commit
      _ <- assertResultF(txn(r).commit, (42, 99))
      _ <- assertResultF(r.get.commit, 99)
    } yield ()
  }

  test("Txn.retry") {
    def txn1(r: TRef[F, Int]): Txn[F, String] = {
      r.get.flatMap {
        case i if i < 0 => Txn.retry
        case i => Txn.pure(i.toString)
      }
    }

    for {
      r <- TRef[F, Int](0).commit
      _ <- assertResultF(txn1(r).commit, "0")
      _ <- r.set(-1).commit
      d <- Deferred[F, String]
      fib <- txn1(r).commit.guaranteeCase {
        case Canceled() =>
          d.complete("cancelled").void
        case Succeeded(fa) =>
          fa.flatMap(a => d.complete(a).void)
        case Errored(_) =>
          d.complete("errored").void
      }.start
      _ <- F.sleep(0.1.second)
      _ <- assertResultF(d.tryGet, None)
      _ <- r.set(1).commit
      _ <- assertResultF(d.get, "1")
      _ <- fib.joinWithNever
    } yield ()
  }

  test("TRef should have .withListeners") {
    def incr(ref: TRef[F, Int]): F[Unit] =
      ref.get.flatMap { ov => ref.set(ov + 1) }.commit
    def getVersion(loc: MemoryLocation[Int]): F[Long] =
      Txn.unsafe.delayContext { ctx => ctx.readIntoHwd(loc).version }.commit
    def regListener(wl: MemoryLocation.WithListeners, cb: Null => Unit, lastSeenVersion: Long): F[Long] =
      Txn.unsafe.delayContext { ctx => wl.unsafeRegisterListener(ctx, cb, lastSeenVersion) }.commit
    def check(ref: TRef[F, Int]): F[Unit] = for {
      loc <- F.delay(ref.asInstanceOf[MemoryLocation[Int]])
      wl <- F.delay(loc.withListeners)
      ctr <- F.delay(new AtomicInteger(0))
      firstVersion <- getVersion(loc)
      // registered listener should be called:
      lid <- regListener(wl, { _ => ctr.getAndIncrement(); () }, firstVersion)
      _ <- assertNotEqualsF(lid, Consts.InvalidListenerId)
      _ <- incr(ref)
      _ <- F.delay(assertEquals(ctr.get(), 1))
      // after it was called once, it shouldn't any more:
      _ <- incr(ref)
      _ <- F.delay(assertEquals(ctr.get(), 1))
      // registered, but then cancelled listener shouldn't be called:
      otherVersion <- getVersion(loc)
      _ <- assertF(firstVersion < otherVersion)
      lid <- regListener(wl, { _ => ctr.getAndIncrement(); () }, otherVersion)
      _ <- assertNotEqualsF(lid, Consts.InvalidListenerId)
      _ <- F.delay(wl.unsafeCancelListener(lid))
      _ <- incr(ref)
      _ <- F.delay(assertEquals(ctr.get(), 1))
      // failed registration due to outdated `lastSeenVersion`:
      lid <- regListener(wl, { _ => ctr.getAndIncrement(); () }, firstVersion)
      _ <- assertEqualsF(lid, Consts.InvalidListenerId)
      _ <- incr(ref)
      _ <- F.delay(assertEquals(ctr.get(), 1))
    } yield ()

    for {
      r <- TRef[F, Int](42).commit
      _ <- check(r)
    } yield ()
  }

  test("Txn#commit should be repeatable") {
    val t: Txn[F, Int] =
      Txn.pure(42)
    val tsk = t.commit
    assertResultF(tsk.replicateA(3), List(42, 42, 42))
  }
}

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

  test("TRef read twice") {
    for {
      r <- TRef[F, Int](1).commit
      _ <- assertResultF(r.get.flatMap { v1 =>
        r.set(v1 + 1).flatMap { _ =>
          r.get.map { v2 =>
            (v1, v2)
          }
        }
      }.commit, (1, 2))
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
