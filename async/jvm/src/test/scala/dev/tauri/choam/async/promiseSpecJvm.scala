/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration._

import cats.effect.IO

final class PromiseSpecJvm_SpinLockMcas_IO_Real
  extends BaseSpecIO
  with SpecSpinLockMcas
  with PromiseSpecJvm[IO]

final class PromiseSpec_SpinLockMcas_IO_Ticked
  extends BaseSpecTickedIO
  with SpecSpinLockMcas
  with PromiseSpecTicked[IO]

final class PromiseSpecJvm_SpinLockMcas_ZIO_Real
  extends BaseSpecZIO
  with SpecSpinLockMcas
  with PromiseSpecJvm[zio.Task]

final class PromiseSpec_SpinLockMcas_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecSpinLockMcas
  with PromiseSpecTicked[zio.Task]

final class PromiseSpecJvm_Emcas_IO_Real
  extends BaseSpecIO
  with SpecEmcas
  with PromiseSpecJvm[IO]

final class PromiseSpec_Emcas_IO_Ticked
  extends BaseSpecTickedIO
  with SpecEmcas
  with PromiseSpecTicked[IO]

final class PromiseSpecJvm_Emcas_ZIO_Real
  extends BaseSpecZIO
  with SpecEmcas
  with PromiseSpecJvm[zio.Task]

final class PromiseSpec_Emcas_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecEmcas
  with PromiseSpecTicked[zio.Task]

trait PromiseSpecJvm[F[_]] extends PromiseSpec[F] { this: McasImplSpec =>

  test("Calling the callback should be followed by a thread shift (async boundary)") {
    @volatile var stop = false
    val spinCount = new AtomicLong(1L << 24)
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      p <- Promise[F, Int].run[F]
      // subscribe for the promise on another fiber:
      f <- p.get.map { v =>
        // we block this thread (until `stop`):
        while (!stop) Thread.onSpinWait()
        v + 1
      }.start
      // give a chance for registration to happen:
      _ <- F.sleep(1.second)
      // complete the promise:
      ok <- p.complete(42)
      // now the fiber spins, hopefully on some other thread
      _ <- assertF(ok)
      // at this point, we don't want to release this
      // thread (e.g., with a `sleep`), because the
      // forked fibercould get rescheduled to this one
      // (and our `sleep` timer could never fire)
      _ <- F.delay {
        // so... instead of sleeping, we spin :-(
        while (spinCount.getAndDecrement() > 0) {
          Thread.onSpinWait()
        }
      }
      _ <- F.delay { stop = true }
      _ <- f.joinWithNever
    } yield ()
  }
}
