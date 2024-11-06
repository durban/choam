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

import cats.effect.IO

import internal.mcas.MemoryLocation
import internal.mcas.Consts

final class TxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with TxnSpec[IO]

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
}
