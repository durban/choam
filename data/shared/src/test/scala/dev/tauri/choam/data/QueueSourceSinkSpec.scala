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
package data

import cats.effect.IO

final class QueueSourceSinkSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with QueueSourceSinkSpec[IO]

trait QueueSourceSinkSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("QueueSourceSink enq/deq") {
    for {
      q <- Queue.bounded[Int](bound = 3).run[F]
      _ <- assertResultF(q.poll.run[F], None)
      _ <- assertResultF(q.tryEnqueue(1).run[F], true)
      _ <- assertResultF(q.tryEnqueue(2).run[F], true)
      _ <- assertResultF(q.poll.run[F], Some(1))
      _ <- assertResultF(q.tryEnqueue(3).run[F], true)
      _ <- assertResultF(q.tryEnqueue(4).run[F], true)
      _ <- assertResultF(q.tryEnqueue(5).run[F], false)
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.poll.run[F], Some(3))
      _ <- assertResultF(q.poll.run[F], Some(4))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }

  test("QueueSourceSink multiple ops in one Rxn") {
    for {
      q <- Queue.bounded[Int](bound = 2).run[F]
      _ <- assertResultF(q.poll.run[F], None)
      rxn = (q.tryEnqueue(1) *> q.tryEnqueue(2) *> q.tryEnqueue(3)) * (
        q.poll * q.tryEnqueue(4)
      )
      _ <- assertResultF(rxn.run[F], (false, (Some(1), true)))
      _ <- assertResultF(q.poll.run[F], Some(2))
      _ <- assertResultF(q.poll.run[F], Some(4))
      _ <- assertResultF(q.poll.run[F], None)
    } yield ()
  }
}
