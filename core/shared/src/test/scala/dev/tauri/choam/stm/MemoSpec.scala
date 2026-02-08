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

import cats.effect.IO

import internal.mcas.Consts

final class MemoSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with MemoSpec[IO]

trait MemoSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  private final class Token

  private final class GetCtr[A] private (memo: Memo[Txn, (A, TRef[Int])]) {

    def get: Txn[A] = memo.getOrInit.flatMap {
      case (a, ctr) =>
        ctr.update(_ + 1).as(a)
    }

    def currentCount: Txn[Int] = memo.getOrInit.flatMap {
      case (_ , ctr) =>
        ctr.get
    }
  }

  private final object GetCtr {

    final def apply[A](act: Txn[A]): Txn[GetCtr[A]] = {
      Txn.memoize(act product TRef(0)).map { memo =>
        new GetCtr(memo)
      }
    }
  }

  test("Txn.memoize simple") {
    for {
      ctr <- TRef(0).commit
      act = ctr.getAndUpdate(_ + 1)
      memo1 <- Txn.memoize(act).commit
      _ <- assertResultF(ctr.get.commit, 0)
      memo2 <- Txn.memoize(act).commit
      _ <- assertResultF(ctr.get.commit, 0)
      _ <- assertResultF(((memo1.getOrInit <* Txn.retry).map(Some(_)) orElse Txn.none).commit, None)
      _ <- assertResultF(ctr.get.commit, 0)
      _ <- assertResultF(memo2.getOrInit.commit, 0)
      _ <- assertResultF(ctr.get.commit, 1)
      _ <- assertResultF(memo2.getOrInit.commit, 0)
      _ <- assertResultF(ctr.get.commit, 1)
      _ <- assertResultF(memo1.getOrInit.commit, 1)
      _ <- assertResultF(ctr.get.commit, 2)
      _ <- assertResultF(memo2.getOrInit.commit, 0)
      _ <- assertResultF(ctr.get.commit, 2)
      _ <- assertResultF(memo1.getOrInit.commit, 1)
      _ <- assertResultF(ctr.get.commit, 2)
    } yield ()
  }

  test("Txn.memoize concurrent access") {
    val t = for {
      ctr <- TRef(0).commit
      act = ctr.update(_ + 1) *> Txn.unsafe.delay { new Token }
      gctr <- GetCtr[Token](act).commit
      _ <- assertResultF(ctr.get.commit, 0)
      rr <- F.both(F.cede *> gctr.get.commit, F.cede *> gctr.get.commit)
      _ <- assertF(rr._1 eq rr._2)
      _ <- assertResultF(ctr.get.commit, 1)
      _ <- assertResultF(gctr.currentCount.commit, 2)
      _ <- assertResultF(gctr.get.commit, rr._1)
      _ <- assertResultF(ctr.get.commit, 1)
      _ <- assertResultF(gctr.currentCount.commit, 3)
      rr2 <- F.both(F.cede *> gctr.get.commit, F.cede *> gctr.get.commit)
      _ <- assertF(rr2._1 eq rr2._2)
      _ <- assertF(rr2._1 eq rr._1)
      _ <- assertResultF(ctr.get.commit, 1)
      _ <- assertResultF(gctr.currentCount.commit, 5)
    } yield ()
    t.parReplicateA_(10000)
  }

  test("Txn.memoize suspend") {
    val getTxnIdentity: Txn[Int] = Txn.unsafe.delayContext2[Int] { (_, interpSt) =>
      assert(interpSt ne null)
      (Consts.staffordMix04(System.identityHashCode(interpSt).toLong) >>> 32).toInt
    }
    val N = 20
    val t = for {
      ctr <- TRef(0).commit
      act = ctr.update(_ + 1) *> getTxnIdentity
      m1 <- Txn.memoize(act).commit
      m2 <- Txn.memoize(act).commit
      fibs <- m1.getOrInit.flatMap { i =>
        if ((i % 2) == 0) Txn.retry
        else getTxnIdentity.map(j => (i, j))
      }.commit.start.parReplicateA(N)
      results <- fibs.traverse(_.joinWithNever)
      _ <- assertResultF(ctr.get.commit, 1)
      winner = results.collect { case (i, j) if i == j => i }
      _ <- assertEqualsF(winner.size, 1)
      i = winner.head
      _ <- assertF(results.forall { case (ii, _) => ii == i })
      _ <- assertEqualsF(results.map(_._2).toSet.size, N) // with high probability
      i2 <- m2.getOrInit.commit
      _ <- assertF(i2 != i) // with high probability
      _ <- assertResultF(ctr.get.commit, 2)
    } yield ()
    t.replicateA_(1000)
  }
}
