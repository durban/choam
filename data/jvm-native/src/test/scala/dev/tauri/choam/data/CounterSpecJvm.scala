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
package data

import cats.effect.IO

final class CounterSpecSimple_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with CounterSpecSimple[IO]
  with CounterSpecJvm[IO]

final class CounterSpecStriped_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with CounterSpecStriped[IO]
  with CounterSpecJvm[IO]

trait CounterSpecJvm[F[_]] { this: CounterSpec[F] & McasImplSpec =>

  test("Parallel access") {
    val numCpu = java.lang.Runtime.getRuntime().availableProcessors()
    val parLimit = 2 * numCpu
    val replicas = 512
    val repeat1 = 2
    val repeat2 = 8
    val expResult = replicas.toLong * repeat1 * repeat2
    val t = for {
      ctr <- this.mkCounter(0L)
      r <- F.both(
        F.parReplicateAN(parLimit)(replicas = replicas, ma = (ctr.incr.replicateA_(repeat1)).run[F].replicateA_(repeat2)),
        F.cede *> ctr.count.run[F],
      )
      (_, c) = r
      _ <- assertF((c >= 0L) && (c <= expResult))
      _ <- assertResultF(ctr.count.run[F], expResult)
    } yield ()
    t.replicateA_(64)
  }
}
