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
package async

import cats.~>
import cats.arrow.FunctionK
import cats.effect.IO
import cats.effect.kernel.{ Outcome, Concurrent }

final class CountDownLatchSpec_ThreadConfinedMcas_IO_Real
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with CountDownLatchSpec[IO]

final class CountDownLatchSpec_ThreadConfinedMcas_IO_Ticked
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with CountDownLatchSpecTicked[IO]

trait CountDownLatchSpecTicked[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec with TestContextSpec[F] =>

  test("Reaching 0 should call all registered `await`s") {
    for {
      cdl <- CountDownLatch[F](2).run[F]
      fib1 <- cdl.await.start
      fib2 <- cdl.await.start
      _ <- this.tickAll
      _ <- F.both(cdl.release.run[F], cdl.release.run[F])
      _ <- fib1.joinWithNever
      _ <- fib2.joinWithNever
    } yield ()
  }

  test("A cancelled `await` should not be called") {
    @volatile var flag1 = false
    @volatile var flag2 = false
    for {
      cdl <- CountDownLatch[F](2).run[F]
      f1 <- F.uncancelable { poll => poll(cdl.await).flatTap(_ => F.delay { flag1 = true }) }.start
      f2 <- F.uncancelable { poll => poll(cdl.await).flatTap(_ => F.delay { flag2 = true }) }.start
      _ <- this.tickAll
      _ <- f1.cancel
      _ <- F.both(cdl.release.run[F], cdl.release.run[F])
      _ <- f2.joinWithNever
      _ <- assertResultF(f1.join, Outcome.canceled[F, Throwable, Unit])
      _ <- assertResultF(F.delay { flag1 }, false)
      _ <- assertResultF(F.delay { flag2 }, true)
    } yield ()
  }
}

trait CountDownLatchSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with AsyncReactiveSpec[F] { this: McasImplSpec =>

  test("Release race") {
    val tsk = for {
      cdl <- CountDownLatch[F](2).run[F]
      fibs <- cdl.await.start.replicateA(3)
      relTask = cdl.release.run[F]
      _ <- F.both(F.both(relTask, relTask), F.both(relTask, relTask))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()

    assumeF(this.mcasImpl.isThreadSafe) *> (
      tsk.parReplicateA_(if (this.isJvm()) 5000 else 1)(cats.effect.instances.spawn.parallelForGenSpawn)
    )
  }

  test("Releasing a completed CDL should not be a no-op") {
    for {
      cdl <- CountDownLatch[F](2).run[F]
      _ <- cdl.release.run[F]
      _ <- cdl.release.run[F]
      _ <- cdl.await
      _ <- cdl.release.run[F]
      _ <- cdl.await
      _ <- cdl.release.run[F]
      _ <- cdl.await
    } yield ()
  }

  test("CountDownLatch#mapK") {
    def tsk[G[_]](f: F ~> G)(implicit rG: Reactive[G], cG: Concurrent[G]): G[Unit] = {
      for {
        cdlF <- CountDownLatch[F](2).run[G]
        cdlG = cdlF.mapK(f)
        fib <- cdlG.await.start
        _ <- cG.both(cdlG.release.run[G], cdlG.release.run[G])
        _ <- fib.joinWithNever
      } yield ()
    }

    assumeF(this.mcasImpl.isThreadSafe) *> tsk[F](FunctionK.id[F])
  }

  test("CountDownLatch#toCats") {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      cdl1 <- CountDownLatch[F](2).run[F]
      ccdl1 = cdl1.toCats
      fib1 <- ccdl1.await.start
      _ <- F.both(cdl1.release.run[F], cdl1.release.run[F])
      _ <- fib1.joinWithNever
      cdl2 <- CountDownLatch[F](2).run[F]
      ccdl2 = cdl2.toCats
      fib2 <- cdl2.await.start
      _ <- F.both(ccdl2.release, ccdl2.release)
      _ <- fib2.joinWithNever
    } yield ()
  }
}
