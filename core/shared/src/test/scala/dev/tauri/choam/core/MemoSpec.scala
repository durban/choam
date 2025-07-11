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

import cats.effect.IO
import cats.effect.instances.all._

final class MemoSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with MemoSpec[IO]

trait MemoSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  private final class Token

  private final class GetCtr[A] private (memo: Memo[(A, Ref[Int])]) {

    def get: Axn[A] = memo.getOrInit.flatMapF {
      case (a, ctr) =>
        ctr.update(_ + 1).as(a)
    }

    def currentCount: Axn[Int] = memo.getOrInit.flatMapF {
      case (_ , ctr) =>
        ctr.get
    }
  }

  private final object GetCtr {

    final def apply[A](act: Axn[A]): Axn[GetCtr[A]] = {
      Rxn.memoize(act * Ref(0)).map { memo =>
        new GetCtr(memo)
      }
    }
  }

  test("Rxn.memoize simple") {
    for {
      ctr <- Ref(0).run[F]
      act = ctr.getAndUpdate(_ + 1)
      memo1 <- Rxn.memoize(act).run[F]
      _ <- assertResultF(ctr.get.run[F], 0)
      memo2 <- Rxn.memoize(act).run[F]
      _ <- assertResultF(ctr.get.run[F], 0)
      _ <- assertResultF((memo1.getOrInit *> Rxn.unsafe.retry).attempt.run[F], None)
      _ <- assertResultF(ctr.get.run[F], 0)
      _ <- assertResultF(memo2.getOrInit.run[F], 0)
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(memo2.getOrInit.run[F], 0)
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(memo1.getOrInit.run[F], 1)
      _ <- assertResultF(ctr.get.run[F], 2)
      _ <- assertResultF(memo2.getOrInit.run[F], 0)
      _ <- assertResultF(ctr.get.run[F], 2)
      _ <- assertResultF(memo1.getOrInit.run[F], 1)
      _ <- assertResultF(ctr.get.run[F], 2)
    } yield ()
  }

  test("Rxn.memoize concurrent access") {
    val t = for {
      ctr <- Ref(0).run[F]
      act = ctr.update(_ + 1) *> Axn.unsafe.delay { new Token }
      gctr <- GetCtr[Token](act).run[F]
      _ <- assertResultF(ctr.get.run[F], 0)
      rr <- F.both(F.cede *> gctr.get.run[F], F.cede *> gctr.get.run[F])
      _ <- assertF(rr._1 eq rr._2)
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(gctr.currentCount.run[F], 2)
      _ <- assertResultF(gctr.get.run[F], rr._1)
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(gctr.currentCount.run[F], 3)
      rr2 <- F.both(F.cede *> gctr.get.run[F], F.cede *> gctr.get.run[F])
      _ <- assertF(rr2._1 eq rr2._2)
      _ <- assertF(rr2._1 eq rr._1)
      _ <- assertResultF(ctr.get.run[F], 1)
      _ <- assertResultF(gctr.currentCount.run[F], 5)
    } yield ()
    t.parReplicateA_(10000)
  }
}
