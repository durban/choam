/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

final class BoundedQueueSpecLinked_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEmcas
  with BoundedQueueSpecLinked[IO]
  with BoundedQueueSpecJvm[IO]

final class BoundedQueueSpecLinked_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEmcas
  with BoundedQueueSpecLinked[zio.Task]
  with BoundedQueueSpecJvm[zio.Task]

final class BoundedQueueSpecArray_EMCAS_IO
  extends BaseSpecTickedIO
  with SpecEmcas
  with BoundedQueueSpecArray[IO]
  with BoundedQueueSpecJvm[IO]

final class BoundedQueueSpecArray_EMCAS_ZIO
  extends BaseSpecTickedZIO
  with SpecEmcas
  with BoundedQueueSpecArray[zio.Task]
  with BoundedQueueSpecJvm[zio.Task]

trait BoundedQueueSpecJvm[F[_]] { this: BoundedQueueSpec[F] with McasImplSpec with TestContextSpec[F] =>

  test("BoundedQueue big bound") {
    val n = 9999
    for {
      _ <- F.delay(assertIntIsNotCached(n))
      q <- newQueue[String](bound = n)
      _ <- F.replicateA(n, q.enqueue("foo"))
      _ <- assertResultF(q.currentSize.run[F], n)
      fib <- q.enqueue("bar").start
      _ <- assertResultF(q.deque, "foo")
      _ <- fib.joinWithNever
      _ <- assertResultF(q.currentSize.run[F], n)
    } yield ()
  }

  test("Sleeping") {
    for {
      q <- newQueue[String](bound = 42)
      fib <- q.deque.start
      _ <- F.sleep(2.seconds)
      _ <- q.enqueue("foo")
      _ <- assertResultF(fib.joinWithNever, "foo")
    } yield ()
  }
}
