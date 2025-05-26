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
import cats.kernel.Monoid

final class StripedRefSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with StripedRefSpec[IO]

final class StripedRefSpec_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with StripedRefSpec[zio.Task]

trait StripedRefSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  private def length[A](sr: StripedRef[A]): Int = {
    sr.fold(0) { (n, _) => n + 1 }
  }

  private def sum[A](sr: StripedRef[A])(implicit A: Monoid[A]): Axn[A] = {
    sr.fold(Axn.pure(A.empty)) { (acc, ref) =>
      acc.flatMapF { acc =>
        ref.get.map { a =>
          A.combine(acc, a)
        }
      }
    }
  }

  test("Basics") {
    for {
      sr <- StripedRef(0).run[F]
      numCpu <- F.delay(Runtime.getRuntime().availableProcessors())
      _ <- assertEqualsF(length(sr), numCpu)
      _ <- assertResultF(sum(sr).run[F], 0)
      _ <- sr.current.update(_ + 1).run[F]
      _ <- assertResultF(sum(sr).run[F], 1)
      _ <- F.both(
        F.cede *> sr.current.update(_ + 2).run[F],
        F.cede *> sr.current.update(_ + 3).run[F],
      )
      _ <- assertResultF(sum(sr).run[F], 6)
    } yield ()
  }
}
