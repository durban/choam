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
package bench
package util

import cats.effect.IO

import io.github.timwspence.cats.stm.STM

import munit.CatsEffectSuite

final class StmStackCSpecIO
  extends BaseSpecIO
  with StmStackCSpec[IO]

trait StmStackCSpec[F[_]] extends CatsEffectSuite with BaseSpecAsyncF[F] with SpecNoMcas {

  test("StmStackC should be a correct stack") {
    for {
      s <- STM.runtime[F](STM.Make.asyncInstance(F))
      q <- {
        val qu = StmStackCLike[STM, F](s)
        s.commit(StmStackC.make(qu)(List(1, 2, 3)))
      }
      _ <- assertResultF(s.commit(q.tryPop), Some(3))
      _ <- assertResultF(s.commit(q.tryPop), Some(2))
      _ <- s.commit(q.push(9))
      _ <- assertResultF(s.commit(q.tryPop), Some(9))
      _ <- assertResultF(s.commit(q.tryPop.map2(q.tryPop)(_ -> _)), (Some(1), None))
    } yield ()
  }
}
