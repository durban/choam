/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package async

import scala.concurrent.duration._

import cats.effect.IO

final class BoundedQueueSpec_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEMCAS
  with BoundedQueueSpecJvm[IO]

final class BoundedQueueSpec_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEMCAS
  with BoundedQueueSpecJvm[zio.Task]

trait BoundedQueueSpecJvm[F[_]]
  extends BoundedQueueSpec[F] { this: KCASImplSpec with TestContextSpec[F] =>

  test("BoundedQueue big bound") {
    val n = 9999
    for {
      _ <- F.delay(assertIntIsNotCached(n))
      q <- BoundedQueue[F, String](bound = n).run[F]
      _ <- F.replicateA(n, q.enqueue("foo"))
      _ <- assertResultF(q.currentSize.run[F], n)
      fib <- q.enqueue("bar").start
      _ <- assertResultF(q.deque, "foo")
      _ <- fib.joinWithNever
      _ <- assertResultF(q.currentSize.run[F], n)
    } yield ()
  }

  // TODO: deadlocks on zio for some reason
  test("Sleeping".ignore) {
    for {
      _ <- F.delay(println("BEGIN"))
      q <- BoundedQueue[F, String](bound = 42).run[F]
      fib <- q.deque.start
      _ <- F.delay(println("started"))
      _ <- this.tickAll
      _ <- F.delay(println("will sleep"))
      _ <- F.sleep(2.seconds)
      _ <- this.tickAll
      _ <- q.enqueue("foo")
      _ <- this.tickAll
      _ <- assertResultF(fib.joinWithNever, "foo")
    } yield ()
  }
}
