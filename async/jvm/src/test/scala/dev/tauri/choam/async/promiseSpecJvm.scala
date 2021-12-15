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

final class PromiseSpecJvm_SpinLockMCAS_IO_Real
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with PromiseSpecJvm[IO]

final class PromiseSpec_SpinLockMCAS_IO_Ticked
  extends BaseSpecTickedIO
  with SpecSpinLockMCAS
  with PromiseSpecTicked[IO]

final class PromiseSpecJvm_SpinLockMCAS_ZIO_Real
  extends BaseSpecZIO
  with SpecSpinLockMCAS
  with PromiseSpecJvm[zio.Task]

final class PromiseSpec_SpinLockMCAS_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecSpinLockMCAS
  with PromiseSpecTicked[zio.Task]

final class PromiseSpecJvm_EMCAS_IO_Real
  extends BaseSpecIO
  with SpecEMCAS
  with PromiseSpecJvm[IO]

final class PromiseSpec_EMCAS_IO_Ticked
  extends BaseSpecTickedIO
  with SpecEMCAS
  with PromiseSpecTicked[IO]

final class PromiseSpecJvm_EMCAS_ZIO_Real
  extends BaseSpecZIO
  with SpecEMCAS
  with PromiseSpecJvm[zio.Task]

final class PromiseSpec_EMCAS_ZIO_Ticked
  extends BaseSpecTickedZIO
  with SpecEMCAS
  with PromiseSpecTicked[zio.Task]

trait PromiseSpecJvm[F[_]] extends PromiseSpec[F] { this: KCASImplSpec =>

  test("Calling the callback should be followed by a thread shift") {
    @volatile var stop = false
    for {
      _ <- assumeF(this.kcasImpl.isThreadSafe)
      p <- Promise[F, Int].run[F]
      f <- p.get.map { v =>
        while (!stop) CompatPlatform.threadOnSpinWait()
        v + 1
      }.start
      ok <- p.complete(42)
      // now the fiber spins, hopefully on some other thread
      _ <- assertF(ok)
      _ <- F.sleep(0.1.seconds)
      _ <- F.delay { stop = true }
      _ <- f.joinWithNever
    } yield ()
  }
}
