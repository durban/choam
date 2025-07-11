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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._

import cats.effect.IO

final class QueueMsSpecJvm_SpinLockMcas_ZIO
  extends BaseSpecZIO
  with QueueMsSpecJvm[zio.Task]
  with SpecSpinLockMcas

final class QueueMsSpecJvm_SpinLockMcas_IO
  extends BaseSpecIO
  with QueueMsSpecJvm[IO]
  with SpecSpinLockMcas

final class QueueMsSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with QueueMsSpecJvm[zio.Task]
  with SpecEmcas

final class QueueMsSpecJvm_Emcas_IO
  extends BaseSpecIO
  with QueueMsSpecJvm[IO]
  with SpecEmcas

final class QueueWithRemoveSpecJvm_SpinLockMcas_ZIO
  extends BaseSpecZIO
  with QueueWithRemoveSpecJvm[zio.Task]
  with SpecSpinLockMcas

final class QueueWithRemoveSpecJvm_SpinLockMcas_IO
  extends BaseSpecIO
  with QueueWithRemoveSpecJvm[IO]
  with SpecSpinLockMcas

final class QueueWithRemoveSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with QueueWithRemoveSpecJvm[zio.Task]
  with SpecEmcas

final class QueueWithRemoveSpecJvm_Emcas_IO
  extends BaseSpecIO
  with QueueWithRemoveSpecJvm[IO]
  with SpecEmcas

final class QueueWithSizeSpecJvm_Emcas_IO
  extends BaseSpecIO
  with QueueWithSizeSpecJvm[IO]
  with SpecEmcas

final class QueueGcHostileSpecJvm_Emcas_IO
  extends BaseSpecIO
  with QueueGcHostileSpecJvm[IO]
  with SpecEmcas

trait QueueWithRemoveSpecJvm[F[_]]
  extends QueueWithRemoveSpec[F]
  with QueueJvmTests[F] { this: McasImplSpec =>
}

trait QueueWithSizeSpecJvm[F[_]]
  extends QueueWithSizeSpec[F]
  with QueueJvmTests[F] { this: McasImplSpec =>
}

trait QueueGcHostileSpecJvm[F[_]]
  extends QueueGcHostileSpec[F]
  with QueueJvmTests[F] { this: McasImplSpec =>
}

trait QueueMsSpecJvm[F[_]]
  extends QueueMsSpec[F]
  with QueueJvmTests[F] { this: McasImplSpec =>
}

trait QueueJvmTests[F[_]] { this: McasImplSpec & BaseQueueSpec[F] =>

  test("Queue should allow multiple producers and consumers") {
    val max = 5000
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      q <- newQueueFromList(List.empty[String])
      produce = F.blocking {
        for (i <- 0 until max) {
          q.enqueue(i.toString).unsafePerform(this.mcasImpl)
        }
      }
      cs <- F.delay { new ConcurrentLinkedQueue[String] }
      stop <- F.delay { new AtomicBoolean(false) }
      consume = F.blocking {
        @tailrec
        def go(last: Boolean = false): Unit = {
          q.tryDeque.unsafePerform(this.mcasImpl) match {
            case Some(s) =>
              cs.offer(s)
              go(last = last)
            case None =>
              if (stop.get()) {
                if (last) {
                  // we're done:
                  ()
                } else {
                  // read one last time:
                  go(last = true)
                }
              } else {
                // retry:
                go(last = false)
              }
          }
        }
        go()
      }
      tsk = for {
        p1 <- produce.start
        c1 <- consume.start
        p2 <- produce.start
        c2 <- consume.start
        _ <- p1.joinWithNever
        _ <- p2.joinWithNever
        _ <- F.delay { stop.set(true) }
        _ <- c1.joinWithNever
        _ <- c2.joinWithNever
      } yield ()

      _ <- tsk.guarantee(F.delay { stop.set(true) })

      _ <- assertEqualsF(
        cs.asScala.toVector.sorted,
        (0 until max).toVector.flatMap(n => Vector(n.toString, n.toString)).sorted
      )
    } yield ()
  }
}
