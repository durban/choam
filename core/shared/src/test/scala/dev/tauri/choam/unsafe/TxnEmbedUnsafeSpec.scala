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
package unsafe

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO

import core.{ Rxn, Ref }
import stm.{ Txn, TRef, TxnBaseSpec }
import UnsafeStm.{ updateTRef, newTRef }

final class TxnEmbedUnsafeSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with TxnEmbedUnsafeSpec[IO]

trait TxnEmbedUnsafeSpec[F[_]]
  extends CommonImperativeApiSpec[F]
  with TxnBaseSpec[F]
  /* TODO: with TxnEmbedUnsafeSpecPlatform[F] */ { this: McasImplSpec =>

  final override def runRoBlock[A](block: InRoRxn => A): F[A] = {
    // for now we don't have Txn.unsafe.embedUnsafeReadOnly:
    this.runBlock(block)
  }

  final override def runBlockWithAlts[A](block: InRxn => A, alts: Rxn[A]*): F[A] = {
    val txnAlts = alts.map[Txn[A]](_.impl)
    Txn.unsafe.embedUnsafeWithAlts(block, txnAlts: _*).commit
  }

  test("Txn.unsafe.embedUnsafe basics") {
    for {
      ref1 <- TRef(0).commit
      ref2 <- TRef(0).commit
      txn = ref1.get.flatMap { v1 =>
        ref1.set(v1 + 1) *> Txn.unsafe.embedUnsafe[Unit] { implicit u =>
          assertEquals(ref1.value, 1)
          assertEquals(ref2.value, 0)
          ref1.value = 42
          ref2.value = 99
        } *> (ref1.get, ref2.get).tupled
      }
      _ <- assertResultF(txn.commit, (42, 99))
      _ <- assertResultF(ref1.get.commit, 42)
      _ <- assertResultF(ref2.get.commit, 99)
    } yield ()
  }

  test("alwaysRetry in Txn.unsafe.embedUnsafe") {
    for {
      ctr <- F.delay(new AtomicInteger)
      ref <- TRef(0).commit
      ref2 <- TRef(0).commit
      res <- (ref2.update(_ + 1) *> Txn.unsafe.embedUnsafe { implicit u =>
        updateTRef(ref)(_ + 1)
        if (ctr.incrementAndGet() < 5) {
          alwaysRetry()
        } else {
          ref.value
        }
      }).commit
      _ <- assertEqualsF(res, 1)
      _ <- assertResultF(ref.get.commit, 1)
      _ <- assertResultF(ref2.get.commit, 1)
      _ <- assertResultF(F.delay(ctr.get()), 5)
    } yield ()
  }

  test("retryWhenChanged in Txn.unsafe.embedUnsafe") {
    for {
      ctr <- F.delay(new AtomicInteger)
      ref <- TRef(0).commit
      ref2 <- TRef(0).commit
      fib <- (ref2.update(_ + 1) *> Txn.unsafe.embedUnsafe { implicit u =>
        ctr.incrementAndGet() : Unit
        updateTRef(ref)(_ + 1)
        if (ref.value < 5) UnsafeStm.retryWhenChanged()
        else ref.value
      }).commit.start
      _ <- ref.set(6).commit
      res <- fib.joinWithNever
      _ <- assertEqualsF(res, 7)
      _ <- assertResultF(ref.get.commit, 7)
      _ <- assertResultF(ref2.get.commit, 1)
      _ <- assertResultF(F.delay(ctr.get()), 2)
    } yield ()
  }

  test("embedRxn in Txn.unsafe.embedUnsafe - simple") {
    def getAndIncrBoth(ref1: Ref[Int], ref2: Ref[Int]): Rxn[(Int, Int)] = {
      (ref1.get, ref2.get).flatMapN { (v1, v2) =>
        ref1.set(v1 + 1) *> ref2.set(v2 + 1).as((v1, v2))
      }
    }
    for {
      ref1 <- TRef(0).commit
      ref2 <- TRef(0).commit
      txn = ref1.get.flatMap { v1 =>
        ref1.set(v1 + 1) *> Txn.unsafe.embedUnsafe[Unit] { implicit ir =>
          assertEquals(ref1.value, 1)
          assertEquals(ref2.value, 0)
          ref1.value = 42
          ref2.value = 99
          val (m1, m2) = embedRxn(getAndIncrBoth(ref1.refImpl, ref2.refImpl))
          assertEquals(m1, 42)
          assertEquals(m2, 99)
          assertEquals(ref1.value, 43)
          assertEquals(ref2.value, 100)
          ref1.value = 44
          ref2.value = 101
        } *> (ref1.get, ref2.get).tupled
      }
      _ <- assertResultF(txn.commit, (44, 101))
      _ <- assertResultF(ref1.get.commit, 44)
      _ <- assertResultF(ref2.get.commit, 101)
    } yield ()
  }

  // TODO: test("embedTxn in Txn.unsafe.embedUnsafe - simple")

  test("embedRxn in Txn.unsafe.embedUnsafe - nested") {
    def layer0(ref1: Ref[Int], ref2: Ref[String], ctr0: AtomicInteger): Rxn[(Int, String)] = {
      Rxn.unsafe.embedUnsafe { implicit ir =>
        ctr0.getAndIncrement()
        val ov1 = ref1.value
        val ov2 = ref2.value
        ref1.value = ov1 + 1
        ref2.value = ov2 + "layer0"
        (ov1, ov2)
      }
    }
    def layer1(ref1: TRef[Int], ref2: TRef[String], ctr0: AtomicInteger, ctr1: AtomicInteger): Txn[(Int, String, Int, String)] = {
      Txn.unsafe.embedUnsafe { implicit ir =>
        ctr1.getAndIncrement()
        val ov1 = ref1.value
        val ov2 = ref2.value
        ref1.value = ov1 + 1
        ref2.value = ov2 + "layer1"
        val (eov1, eov2) = embedRxn(layer0(ref1.refImpl, ref2.refImpl, ctr0))
        assertEquals(eov1, ov1 + 1)
        assertEquals(eov2, ov2 + "layer1")
        assertEquals(ref1.value, ov1 + 1 + 1)
        assertEquals(ref2.value, ov2 + "layer1" + "layer0")
        updateTRef(ref1)(_ + 1)
        updateTRef(ref2)(_ + "layer1again")
        (ov1, ov2, eov1, eov2)
      }
    }
    def layer2(ctr0: AtomicInteger, ctr1: AtomicInteger): Txn[Int] = {
      TRef[Int](0).flatMap { ref1 =>
        Txn.unsafe.embedUnsafe { implicit ir =>
          newTRef("")
        }.flatMap { ref2 =>
          ref1.update(_ + 1) *> ref2.update(_ + "layer2") *> layer1(ref1, ref2, ctr0, ctr1).flatMap {
            case (ov1, ov2, eov1, eov2) =>
              Txn.unsafe.delay {
                assertEquals(ov1, 1)
                assertEquals(ov2, "layer2")
                assertEquals(eov1, 2)
                assertEquals(eov2, "layer2layer1")
                assertEquals(ctr0.get(), 1)
                assertEquals(ctr1.get(), 1)
              } *> Txn.unsafe.embedUnsafe { implicit ir =>
                assertEquals(ref1.value, 4)
                assertEquals(ref2.value, "layer2layer1layer0layer1again")
                42
              }
          }
        }
      }
    }
    for {
      ctr01 <- F.delay(new AtomicInteger)
      ctr11 <- F.delay(new AtomicInteger)
      _ <- assertResultF(layer2(ctr01, ctr11).commit, 42)
      ctr02 <- F.delay(new AtomicInteger)
      ctr12 <- F.delay(new AtomicInteger)
      _ <- assertResultF(layer2(ctr02, ctr12).commit, 42)
    } yield ()
  }

  // TODO: test("embedTxn in Txn.unsafe.embedUnsafe - nested")
}
