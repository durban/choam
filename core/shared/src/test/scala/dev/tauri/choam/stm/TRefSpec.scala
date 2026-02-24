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
package stm

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

import cats.kernel.{ Eq, Hash }
import cats.effect.IO

import internal.mcas.{ Consts, MemoryLocation }

final class TRefSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with TRefSpec[IO]

trait TRefSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  protected def newTRef[A](initial: A): F[TRef[A]] =
    TRef[A](initial).commit

  test("TRef#set") {
    for {
      r <- newTRef(0)
      _ <- assertResultF((r.set(42) *> r.get).commit, 42)
      _ <- assertResultF(r.get.commit, 42)
    } yield ()
  }

  test("TRef#update") {
    for {
      r <- newTRef(0)
      _ <- assertResultF((r.update(_ + 1) *> r.get).commit, 1)
      _ <- assertResultF(r.get.commit, 1)
    } yield ()
  }

  test("TRef#modify") {
    for {
      r <- newTRef(0)
      _ <- assertResultF(r.modify(ov => (ov + 1, 42)).commit, 42)
      _ <- assertResultF(r.get.commit, 1)
    } yield ()
  }

  test("TRef#getAndSet") {
    for {
      r <- newTRef(0)
      _ <- assertResultF(r.getAndSet(42).commit, 0)
      _ <- assertResultF(r.get.commit, 42)
    } yield ()
  }

  test("TRef#updateAndGet") {
    for {
      ref <- newTRef(42)
      _ <- assertResultF(ref.updateAndGet(_ + 1).commit, 43)
      _ <- assertResultF(ref.get.commit, 43)
    } yield ()
  }

  test("TRef#flatModify") {
    for {
      ref <- newTRef(42)
      ctr <- newTRef(0)
      _ <- assertResultF(ref.flatModify { ov =>
        (ov + 1, ctr.update(_ + 1))
      }.*>(ctr.get).commit, 1)
      _ <- assertResultF(ref.get.commit, 43)
      _ <- assertResultF(ctr.get.commit, 1)
    } yield ()
  }

  test("Hash[TRef] instance") {
    for {
      ref1 <- newTRef(42)
      ref2 <- newTRef(42)
      _ <- assertF(Eq[TRef[Int]].eqv(ref1, ref1))
      _ <- assertF(Eq[TRef[Int]].neqv(ref1, ref2))
      _ <- assertEqualsF(Hash[TRef[Int]].hash(ref1), ref1.##)
      _ <- assertNotEqualsF(Hash[TRef[Int]].hash(ref1), Hash[TRef[Int]].hash(ref2)) // with high probability
    } yield ()
  }

  test("Internal: TRef should be a Ref") {
    for {
      tr <- newTRef(42)
      rr = tr.asInstanceOf[core.Ref[Int]]
      _ <- assertResultF(rr.updateAndGet(_ + 1).run, 43)
      _ <- assertResultF(tr.get.commit, 43)
    } yield ()
  }

  test("Internal: TRef.unsafeRefWithId") {
    val rr: core.Ref[String] = TRef.unsafeRefWithId("foo", this.mcasImpl.currentContext().refIdGen.nextId())
    val tr: TRef[String] = rr.asInstanceOf[TRef[String]]
    for {
      _ <- assertResultF(tr.updateAndGet(_ + "bar").commit, "foobar")
      _ <- assertResultF(rr.get.run, "foobar")
    } yield ()
  }

  test("Internal: TRef should have .withListeners") {
    def incr(ref: TRef[Int]): F[Unit] =
      ref.get.flatMap { ov => ref.set(ov + 1) }.commit
    def getVersion(loc: MemoryLocation[Int]): F[Long] =
      Txn.unsafe.delayContext { ctx => ctx.readIntoHwd(loc).version }.commit
    def regListener(wl: MemoryLocation.WithListeners, cb: Null => Unit, lastSeenVersion: Long): F[Long] =
      Txn.unsafe.delayContext { ctx => wl.unsafeRegisterListener(ctx, cb, lastSeenVersion) }.commit
    def check(ref: TRef[Int]): F[Unit] = for {
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
      r <- newTRef(42)
      _ <- check(r)
    } yield ()
  }

  test("Internal: refImpl") {
    for {
      r <- newTRef(42)
      rr = r.refImpl
      _ = (rr : core.Ref[Int])
      fib <- r.get.flatMap { v =>
        if (v > 0) Txn.retry
        else Txn.pure(v)
      }.commit.start
      _ <- F.sleep(0.01.second)
      _ <- rr.set(0).run[F]
      _ <- assertResultF(fib.joinWithNever, 0)
    } yield ()
  }
}
